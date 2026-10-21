package vn.iotstar.service;

import java.util.Map;
import java.util.List;
import java.time.format.DateTimeFormatter;
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

/** Notification persistence and per-user realtime delivery, shared with TV1 in step 23. */
@Service
@RequiredArgsConstructor
public class NotificationService {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);
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
        Long id = notification.getId();
        publishAfterCommit(user.getUsername(), "/queue/notifications", () -> Map.of(
            "id", id, "title", title, "message", message,
            "link", safeLink(link),
            "unreadCount", notificationRepository.countByUserIdAndReadFalse(user.getId())));
    }

    public void sendOrderStatus(User user, OrderStatusHistory history) {
        // Copy fields while entities are still attached to a persistence context.
        var payload = Map.of(
            "orderId", history.getOrder().getId(),
            "status", history.getNewStatus().name(),
            "label", history.getDisplayLabel(),
            "changedAt", history.getChangedAt().toString(),
            "changedBy", history.getChangedBy().getFullName(),
            "note", history.getNote() == null ? "" : history.getNote()
        );
        publishAfterCommit(user.getUsername(), "/queue/order-status", () -> payload);
    }

    private void publishAfterCommit(String username, String destination,
                                    java.util.function.Supplier<Object> payload) {
        Runnable publish = () -> {
            try {
                messagingTemplate.convertAndSendToUser(username, destination, payload.get());
            } catch (RuntimeException ex) {
                // A realtime transport failure must not roll back an already committed order.
                LOG.warn("WebSocket delivery failed for {}", username, ex);
            }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(User user, int page) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(
            user.getId(), PageRequest.of(Math.max(0, page), 20,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")));
    }

    @Transactional(readOnly = true)
    public List<NotificationPreview> recent(User user) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, 5))
            .getContent().stream()
            .map(n -> new NotificationPreview(n.getId(), n.getTitle(), n.getMessage(),
                safeLink(n.getLink()), n.getCreatedAt().format(PREVIEW_TIME), n.isRead()))
            .toList();
    }

    private String safeLink(String link) {
        return link != null && link.startsWith("/") && !link.startsWith("//")
            && !link.contains("\\") && !link.contains("\r") && !link.contains("\n")
            ? link : "/user/notifications";
    }

    public record NotificationPreview(Long id, String title, String message,
                                      String link, String createdAt, boolean read) {}

    @Transactional(readOnly = true)
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
