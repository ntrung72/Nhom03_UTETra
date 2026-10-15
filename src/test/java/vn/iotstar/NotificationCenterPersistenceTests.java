package vn.iotstar;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.User;
import vn.iotstar.repository.NotificationRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.NotificationService;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest
@ActiveProfiles("test")
class NotificationCenterPersistenceTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired NotificationRepository repository;
    @Autowired NotificationService notifications;
    User customer;

    @Test
    void readStateAndHeaderCountPersistAcrossIndependentRequests() throws Exception {
        String key = UUID.randomUUID().toString();
        customer = new User(); customer.setUsername(key); customer.setEmail(key + "@test.vn");
        customer.setFullName("Khách nhận thông báo"); customer.setPasswordHash("test-hash"); customer.setEnabled(true);
        users.saveAndFlush(customer);
        notifications.send(customer, "Đơn đang giao", "Đơn hàng đã được giao cho shipper", "/orders/1");
        var n = notifications.list(customer, 0).getContent().getFirst();
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/user/notifications").with(user(key).roles("USER")))
            .andExpect(status().isOk()).andExpect(model().attribute("unreadNotifications", 1L));
        assertThat(repository.findById(n.getId()).orElseThrow().isRead()).isFalse();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/user/notifications/" + n.getId() + "/read").with(user(key).roles("USER")).with(csrf()))
                .andExpect(redirectedUrl("/user/notifications?page=0"));
        }
        assertThat(repository.findById(n.getId()).orElseThrow().isRead()).isTrue();
        assertThat(notifications.list(customer, 0).getTotalElements()).isEqualTo(1);
        mvc.perform(get("/user/notifications").with(user(key).roles("USER")))
            .andExpect(status().isOk()).andExpect(model().attribute("unreadNotifications", 0L));
    }

    @AfterEach
    void cleanup() {
        if (customer == null || customer.getId() == null) return;
        repository.deleteAll(repository.findByUserIdOrderByCreatedAtDesc(customer.getId(), PageRequest.of(0, 20)).getContent());
        users.deleteById(customer.getId());
    }
}
