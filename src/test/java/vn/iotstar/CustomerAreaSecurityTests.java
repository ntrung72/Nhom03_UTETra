package vn.iotstar;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class CustomerAreaSecurityTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        saveUser("customer-area-user", Role.USER);
        saveUser("customer-area-vendor", Role.VENDOR);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void staffCannotUseCustomerPagesOrReadApis() throws Exception {
        for (String role : new String[] {"MANAGER", "ADMIN", "SHIPPER"}) {
            String home = "SHIPPER".equals(role) ? "/shipper" : "/management";
            for (String path : new String[] {"/user", "/user/profile", "/cart", "/checkout", "/orders", "/orders/123"}) {
                mockMvc.perform(get(path).with(user("staff-" + role).roles(role)))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(home));
            }
            mockMvc.perform(post("/cart/add/123").with(user("staff-" + role).roles(role)).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(home));
            mockMvc.perform(get("/api/cart").with(user("staff-" + role).roles(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void userAndVendorRetainCustomerAccess() throws Exception {
        for (String role : new String[] {"USER", "VENDOR"}) {
            String username = "customer-area-" + role.toLowerCase(java.util.Locale.ROOT);
            mockMvc.perform(get("/user").with(user(username).roles(role)))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/user/profile"));
            mockMvc.perform(get("/cart").with(user(username).roles(role))).andExpect(status().isOk());
            mockMvc.perform(get("/checkout").with(user(username).roles(role)))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/user/profile"));
            mockMvc.perform(get("/orders").with(user(username).roles(role))).andExpect(status().isOk());
            mockMvc.perform(get("/api/cart").with(user(username).roles(role)))
                .andExpect(status().isOk());
        }
    }

    @Test
    void deniedStaffRefererCannotRedirectBackIntoCustomerArea() throws Exception {
        mockMvc.perform(get("/cart").with(user("staff-manager").roles("MANAGER"))
                .header("Referer", "http://localhost/user/profile"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/management"));
    }

    private void saveUser(String username, Role role) {
        User account = new User();
        account.setUsername(username);
        account.setEmail(username + "@utetra.test");
        account.setPasswordHash("encoded-password");
        account.setFullName(username);
        account.setRole(role);
        account.setEnabled(true);
        userRepository.save(account);
    }
}
