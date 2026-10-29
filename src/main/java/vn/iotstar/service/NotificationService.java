package vn.iotstar.service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.iotstar.entity.Notification;
import vn.iotstar.entity.OrderStatusHistory;
import vn.iotstar.entity.User;
import vn.iotstar.repository.NotificationRepository;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final DateTimeFormatter PREVIEW_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private final NotificationRepository notificationRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public void send(User user, String title, String message, String link) {
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setLink(link);
        notificationRepository.save(notification);
        Long notificationId = notification.getId();
        Long userId = user.getId();
        String username = user.getUsername();
        publishAfterCommit(() -> messagingTemplate.convertAndSendToUser(username, "/queue/notifications",
            Map.of("id", notificationId, "title", title, "message", message,
                "link", link == null ? "" : link,
                "unreadCount", notificationRepository.countByUserIdAndReadFalse(userId))));
    }

    public void sendOrderStatus(User user, OrderStatusHistory history) {
        String username = user.getUsername();
        Map<String, Object> payload = Map.of(
            "orderId", history.getOrder().getId(),
            "status", history.getNewStatus().name(),
            "label", history.getDisplayLabel(),
            "changedAt", history.getChangedAt().toString(),
            "changedBy", history.getChangedBy().getFullName(),
            "note", history.getNote() == null ? "" : history.getNote()
        );
        publishAfterCommit(() -> messagingTemplate.convertAndSendToUser(username, "/queue/order-status", payload));
    }

    private void publishAfterCommit(Runnable publish) {
        Runnable safePublish = () -> {
            try {
                publish.run();
            } catch (RuntimeException ex) {
                log.warn("Không thể phát thông báo realtime sau khi dữ liệu đã commit.", ex);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safePublish.run();
                }
            });
        } else {
            safePublish.run();
        }
    }

    public Page<Notification> list(User user, int page) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(Math.max(0, page), 20));
    }

    @Transactional(readOnly = true)
    public List<NotificationPreview> recent(User user) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, 5))
            .getContent().stream()
            .map(notification -> new NotificationPreview(notification.getId(), notification.getTitle(),
                notification.getMessage(), safeLink(notification.getLink()),
                notification.getCreatedAt().format(PREVIEW_TIME), notification.isRead()))
            .toList();
    }

    private String safeLink(String link) {
        return link != null && link.startsWith("/") && !link.startsWith("//")
            && !link.contains("\\") && !link.contains("\r") && !link.contains("\n")
            ? link : "/notifications";
    }

    public record NotificationPreview(Long id, String title, String message, String link,
                                      String createdAt, boolean read) {}

    public long unreadCount(User user) {
        return notificationRepository.countByUserIdAndReadFalse(user.getId());
    }

    @Transactional
    public void markRead(User user, Long id) {
        Notification notification = notificationRepository.findByIdAndUserId(id, user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông báo."));
        notification.setRead(true);
    }
}
