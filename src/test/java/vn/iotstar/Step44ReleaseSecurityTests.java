package vn.iotstar;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Step 44: release security regression only; no changes to business services. */
@ActiveProfiles("test")
@SpringBootTest
class Step44ReleaseSecurityTests {
    @Autowired private WebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void prepare() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousCannotInspectSwagger() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
    }

    @Test
    void ordinaryUserCannotInspectSwagger() throws Exception {
        mockMvc.perform(get("/v3/api-docs").with(user("buyer").roles("USER")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/swagger-ui/index.html").with(user("shipper").roles("SHIPPER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void legacyPrivateMediaUrlCannotBeAccessedDirectly() throws Exception {
        mockMvc.perform(get("/uploads/returns/guess.jpg")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/uploads/delivery-proofs/guess.jpg").with(user("buyer").roles("USER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void csrfIsRequiredForManagerMutation() throws Exception {
        mockMvc.perform(post("/management/users/42/role")
                .with(user("admin").roles("ADMIN")).param("role", "USER"))
            .andExpect(status().isForbidden());
    }
}
