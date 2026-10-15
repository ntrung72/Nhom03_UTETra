package vn.iotstar;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.Notification;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.repository.NotificationRepository;
import vn.iotstar.service.NotificationService;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationCenterTests extends CustomerTestFixtures {
    @Autowired NotificationRepository repository;
    @Autowired NotificationService notifications;

    Notification notification(User user, String title, String link) {
        var n = new Notification(); n.setUser(user); n.setTitle(title); n.setMessage("Nội dung " + title); n.setLink(link);
        return repository.saveAndFlush(n);
    }

    @Test
    void ownListShowsReadUnreadCountAndDoesNotExposeAnotherRecipientsMessages() throws Exception {
        var first = notification(customer, "Đơn của Tân", "/orders/1");
        var read = notification(customer, "Ưu đãi mới", "/products/2");
        read.setRead(true); repository.saveAndFlush(read);
        notification(other, "Nội dung riêng tư của bạn khác", "/orders/9");
        var result = mvc.perform(get("/user/notifications").with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andExpect(view().name("user/notifications"))
            .andExpect(model().attribute("unreadNotifications", 1L)).andReturn();
        var html = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("Đơn của Tân", "Ưu đãi mới", "Chưa đọc", "Đã đọc", "data-unread-notifications")
            .doesNotContain("Nội dung riêng tư của bạn khác");
        assertThat(notifications.unreadCount(customer)).isEqualTo(1);
        assertThat(repository.findById(first.getId()).orElseThrow().isRead()).isFalse();
    }

    @Test
    void listIsPaginatedByTwentyWithDeterministicOrderForEqualTimestamps() throws Exception {
        var ids = new ArrayList<Long>(); var sameTime = LocalDateTime.of(2026, 10, 1, 12, 0);
        for (int i = 0; i < 25; i++) {
            var n = notification(customer, "Thông báo " + i, null); n.setCreatedAt(sameTime); repository.saveAndFlush(n); ids.add(n.getId());
        }
        var first = notifications.list(customer, 0); var second = notifications.list(customer, 1);
        assertThat(first.getContent()).hasSize(20); assertThat(second.getContent()).hasSize(5);
        assertThat(first.getContent()).extracting(Notification::getId).containsExactlyElementsOf(ids.reversed().subList(0, 20));
        assertThat(second.getContent()).extracting(Notification::getId).doesNotContainAnyElementsOf(first.getContent().stream().map(Notification::getId).toList());
        mvc.perform(get("/user/notifications").with(user(customer.getUsername()).roles("USER")).param("page", "1"))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Trang 2/2")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"page\" value=\"1\"")));
        assertThat(notifications.list(customer, -3).getNumber()).isZero();
    }

    @Test
    void markReadUsesAuthenticatedRecipientIsIdempotentAndKeepsCurrentPage() throws Exception {
        var n = notification(customer, "Đơn sẵn sàng", "/orders/1"); notification(other, "Riêng tư", "/orders/2");
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/user/notifications/" + n.getId() + "/read").with(user(customer.getUsername()).roles("USER"))
                .with(csrf()).param("page", "1").param("userId", other.getId().toString()))
                .andExpect(redirectedUrl("/user/notifications?page=1"));
        }
        assertThat(notifications.unreadCount(customer)).isZero(); assertThat(notifications.unreadCount(other)).isEqualTo(1);
        assertThat(repository.findById(n.getId()).orElseThrow().isRead()).isTrue();
        mvc.perform(get("/products").with(user(customer.getUsername()).roles("USER")))
            .andExpect(model().attribute("unreadNotifications", 0L))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-unread-notifications"))));
    }

    @Test
    void foreignAndMissingNotificationsBothReturn404AndLeaveUnreadStateUnchanged() throws Exception {
        var foreign = notification(other, "Thông báo bí mật", "/orders/1");
        for (Long id : List.of(foreign.getId(), Long.MAX_VALUE)) {
            mvc.perform(post("/user/notifications/" + id + "/read").with(user(customer.getUsername()).roles("USER")).with(csrf()))
                .andExpect(status().isNotFound());
        }
        assertThat(notifications.unreadCount(other)).isEqualTo(1);
        assertThat(repository.findById(foreign.getId()).orElseThrow().isRead()).isFalse();
    }

    @Test
    void readRequiresPostCsrfAndCustomerRole() throws Exception {
        var n = notification(customer, "Thông báo", null); String path = "/user/notifications/" + n.getId() + "/read";
        mvc.perform(get(path).with(user(customer.getUsername()).roles("USER"))).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(path).with(user(customer.getUsername()).roles("USER"))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf())).andExpect(status().is3xxRedirection());
        for (String role : List.of("ADMIN", "MANAGER", "SHIPPER")) {
            mvc.perform(get("/user/notifications").with(user(customer.getUsername()).roles(role)))
                .andExpect(status().is3xxRedirection());
            mvc.perform(post(path).with(user(customer.getUsername()).roles(role)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        }
        assertThat(notifications.unreadCount(customer)).isEqualTo(1);
    }

    @Test
    void linksPreserveOrderProductShopTargetsAndSuppressExternalOrExecutableUrls() throws Exception {
        var order = notification(customer, "Đơn", "/orders/123");
        var product = notification(customer, "Món mới", "/products/42");
        var shop = notification(customer, "Shop mới", "/shops/ute-tra");
        for (String link : List.of("javascript:alert(1)", "https://outside.invalid", "//outside.invalid", "/orders/../management", "data:text/html,x", ""))
            notification(customer, "Không có liên kết an toàn", link);
        var result = mvc.perform(get("/user/notifications").with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andReturn();
        assertThat(result.getModelAndView().getModel().get("notificationLinks")).isEqualTo(Map.of(
            order.getId(), "/orders/123", product.getId(), "/products/42", shop.getId(), "/shops/ute-tra"));
        String html = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(html).contains("href=\"/orders/123\"", "href=\"/products/42\"", "href=\"/shops/ute-tra\"")
            .doesNotContain("javascript:alert(1)", "outside.invalid", "data:text/html,x", "/orders/../management");
    }

    @Test
    void vendorCanUseTheCenterAndFollowExistingVendorOrderNotifications() throws Exception {
        customer.setRole(Role.VENDOR); users.saveAndFlush(customer);
        var n = notification(customer, "Shop có đơn mới", "/vendor/orders");
        mvc.perform(get("/user/notifications").with(user(customer.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk()).andExpect(model().attribute("notificationLinks", Map.of(n.getId(), "/vendor/orders")));
        mvc.perform(post("/user/notifications/" + n.getId() + "/read").with(user(customer.getUsername()).roles("VENDOR")).with(csrf()))
            .andExpect(redirectedUrl("/user/notifications?page=0"));
        assertThat(notifications.unreadCount(customer)).isZero();
    }

    @Test
    void titlesAndMessagesAreEscapedAndUnsupportedRoleLinksAreHidden() throws Exception {
        var n = notification(customer, "<script>alert(1)</script>", "/vendor/orders");
        n.setMessage("<img src=x onerror=alert(1)>"); repository.saveAndFlush(n);
        var result = mvc.perform(get("/user/notifications").with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andReturn();
        assertThat((Map<?, ?>) result.getModelAndView().getModel().get("notificationLinks")).isEmpty();
        assertThat(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
            .contains("&lt;script&gt;", "&lt;img").doesNotContain("<script>alert(1)</script>", "<img src=x onerror=alert(1)>");
    }

    @Test
    void emptyAndOutOfRangePagesRenderAndAnonymousHeaderDoesNotExposeUserCounts() throws Exception {
        mvc.perform(get("/user/notifications").with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Chưa có thông báo")));
        mvc.perform(get("/user/notifications").with(user(customer.getUsername()).roles("USER")).param("page", "999"))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Về trang đầu")));
        mvc.perform(get("/products")).andExpect(status().isOk()).andExpect(model().attribute("unreadNotifications", 0L));
        mvc.perform(get("/user/notifications").with(user(customer.getUsername()).roles("USER")).param("page", "invalid"))
            .andExpect(status().isBadRequest());
    }
}
