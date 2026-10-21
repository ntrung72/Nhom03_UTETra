package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.Notification;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.OrderStatusHistory;
import vn.iotstar.entity.User;
import vn.iotstar.repository.NotificationRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.NotificationService;

@ActiveProfiles("test")
@SpringBootTest
class NotificationCommitTests {
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private NotificationService notificationService;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private WebApplicationContext context;
    @MockitoBean private SimpMessagingTemplate messagingTemplate;

    @Test
    void recentPreviewIsLimitedToFiveAndOnlyReturnsOwnNotifications() throws Exception {
        User recipient = recipient();
        User other = recipient();
        LocalDateTime start = LocalDateTime.of(2026, 10, 16, 8, 0);
        for (int i = 0; i < 7; i++) {
            Notification notification = new Notification();
            notification.setUser(recipient);
            notification.setTitle("preview-" + i);
            notification.setMessage("Nội dung " + i);
            notification.setLink(i == 6 ? "https://unsafe.example/" : "/vendor/orders");
            notification.setCreatedAt(start.plusMinutes(i));
            notificationRepository.save(notification);
        }
        Notification hidden = new Notification();
        hidden.setUser(other);
        hidden.setTitle("Không thuộc tài khoản này");
        hidden.setMessage("Không được trả về");
        hidden.setCreatedAt(start.plusDays(1));
        notificationRepository.save(hidden);

        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/notifications/recent")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/recent")
                .with(user(recipient.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[0].title").value("preview-6"))
            .andExpect(jsonPath("$[0].link").value("/user/notifications"))
            .andExpect(jsonPath("$[4].title").value("preview-2"));
    }

    @Test
    void notificationPersistsAndPublishesOnlyAfterCommit() throws Exception {
        User recipient = recipient();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        transactions.executeWithoutResult(status -> {
            notificationService.send(recipient, "Đơn hàng mới", "Có đơn hàng cần xử lý", "/vendor/orders");
            assertEquals(1, notificationRepository.countByUserIdAndReadFalse(recipient.getId()));
            verifyNoInteractions(messagingTemplate);
            status.setRollbackOnly();
        });
        assertEquals(0, notificationRepository.countByUserIdAndReadFalse(recipient.getId()));
        verifyNoInteractions(messagingTemplate);

        transactions.executeWithoutResult(status -> {
            notificationService.send(recipient, "Đơn hàng mới", "Có đơn hàng cần xử lý", "/vendor/orders");
            verifyNoInteractions(messagingTemplate);
        });
        assertEquals(1, notificationRepository.countByUserIdAndReadFalse(recipient.getId()));
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(eq(recipient.getUsername()),
            eq("/queue/notifications"), payload.capture());
        Map<?, ?> message = (Map<?, ?>) payload.getValue();
        assertNotNull(message.get("id"));
        assertEquals(1L, message.get("unreadCount"));

        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/unread-count")
                .with(user(recipient.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void orderTimelineEventIsDiscardedOnRollback() {
        User recipient = recipient();
        CustomerOrder order = new CustomerOrder();
        order.setId(999L);
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setChangedBy(recipient);
        history.setNewStatus(OrderStatus.CONFIRMED);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        transactions.executeWithoutResult(status -> {
            notificationService.sendOrderStatus(recipient, history);
            verifyNoInteractions(messagingTemplate);
            status.setRollbackOnly();
        });
        verifyNoInteractions(messagingTemplate);

        transactions.executeWithoutResult(status -> {
            notificationService.sendOrderStatus(recipient, history);
            verifyNoInteractions(messagingTemplate);
        });
        verify(messagingTemplate).convertAndSendToUser(eq(recipient.getUsername()),
            eq("/queue/order-status"), org.mockito.ArgumentMatchers.any());
    }

    private User recipient() {
        return recipient(Role.VENDOR);
    }

    private User recipient(Role role) {
        String name = "notify-" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(name);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }
}
