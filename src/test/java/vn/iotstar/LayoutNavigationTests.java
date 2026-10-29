package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.User;
import vn.iotstar.repository.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LayoutNavigationTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    private MockMvc mvc;

    @BeforeEach
    void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void guestSeesCompactNavbarAndRealFooterLinks() throws Exception {
        String home = mvc.perform(get("/")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertTrue(home.contains("data-nav-catalog"));
        assertTrue(home.contains("class=\"navbar-search\""));
        assertTrue(home.contains("id=\"siteConfirmDialog\""));
        assertFalse(home.contains("id=\"cua-hang\""));
        assertFalse(home.contains("class=\"home-promotion-band\""));
        assertTrue(home.contains("Xem món đang giảm giá"));
        assertTrue(home.contains("/shops"));
        assertTrue(home.contains("/policies#returns"));
        assertFalse(home.contains("Trần Nam Trung"));

        String policies = mvc.perform(get("/policies")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertTrue(policies.contains("id=\"payment\""));
        assertTrue(policies.contains("id=\"shipping\""));
        assertTrue(policies.contains("id=\"returns\""));
        assertTrue(policies.contains("id=\"privacy\""));
    }

    @Test
    void signedInCustomerSeesCartNotificationsAndAccount() throws Exception {
        User customer = new User();
        customer.setUsername("layout-customer");
        customer.setEmail("layout-customer@utetra.test");
        customer.setPasswordHash("encoded-password");
        customer.setFullName("Khách hàng");
        customer.setRole(Role.USER);
        customer.setEnabled(true);
        users.save(customer);

        String page = mvc.perform(get("/products").with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("data-nav-cart"));
        assertTrue(page.contains("data-nav-notifications"));
        assertTrue(page.contains("data-nav-account"));
        assertTrue(page.contains("Hồ sơ và địa chỉ"));
    }

    @Test
    void managementMenuKeepsAdminOnlyToolsOutOfManagerView() throws Exception {
        User manager = saveUser("layout-manager", Role.MANAGER);
        String managerPage = mvc.perform(get("/management/carriers")
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(managerPage.contains("Quản lý khác"));
        assertTrue(managerPage.contains("Nhà vận chuyển"));
        assertFalse(managerPage.contains("Phân công Manager"));

        User admin = saveUser("layout-admin", Role.ADMIN);
        String adminPage = mvc.perform(get("/management/carriers")
                .with(user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(adminPage.contains("Phân công Manager"));
        assertTrue(adminPage.contains(">Thanh toán</a>"));
    }

    private User saveUser(String username, Role role) {
        User account = new User();
        account.setUsername(username);
        account.setEmail(username + "@utetra.test");
        account.setPasswordHash("encoded-password");
        account.setFullName(username);
        account.setRole(role);
        account.setEnabled(true);
        return users.save(account);
    }
}
