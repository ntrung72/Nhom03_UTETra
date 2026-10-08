package vn.iotstar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class SecurityNavigationTests {
    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void guestReturnsToPreviousPublicPage() throws Exception {
        mockMvc.perform(get("/management").header("Referer", "http://localhost/products/1"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/products/1"));
    }

    @Test
    void guestWithoutPreviousPageReturnsHome() throws Exception {
        mockMvc.perform(get("/shipper"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotEnterManagement() throws Exception {
        mockMvc.perform(get("/management"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/user/profile"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void forbiddenPreviousPageCannotCauseRedirectLoop() throws Exception {
        mockMvc.perform(get("/management").header("Referer", "http://localhost/vendor"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/user/profile"));
    }

    @Test
    @WithMockUser(roles = "VENDOR")
    void vendorReturnsToVendorPage() throws Exception {
        mockMvc.perform(get("/management").header("Referer", "http://localhost/vendor"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/vendor"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void managerCannotEnterVendorArea() throws Exception {
        mockMvc.perform(get("/vendor"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/management"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void managerCannotEnterAdminOnlyManagementAreas() throws Exception {
        mockMvc.perform(get("/management/managers"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/management"));
        mockMvc.perform(get("/management/vouchers"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/management"));
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void managerCannotChangeUserRolesByDirectPost() throws Exception {
        mockMvc.perform(post("/management/users/99/role").with(csrf()).param("role", "ADMIN"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/management"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCannotEnterShipperArea() throws Exception {
        mockMvc.perform(get("/shipper"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/management"));
    }

    @Test
    @WithMockUser(roles = "SHIPPER")
    void shipperReturnsToShipperPage() throws Exception {
        mockMvc.perform(get("/management").header("Referer", "http://localhost/shipper"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/shipper"));
    }

    @Test
    void apiKeepsHttpUnauthorizedStatus() throws Exception {
        mockMvc.perform(get("/api/me"))
            .andExpect(status().isUnauthorized());
    }
}
