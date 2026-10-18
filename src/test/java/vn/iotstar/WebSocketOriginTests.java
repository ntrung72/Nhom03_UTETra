package vn.iotstar;

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
import org.springframework.web.context.WebApplicationContext;

@ActiveProfiles("test")
@SpringBootTest
class WebSocketOriginTests {
    @Autowired private WebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void sameOriginSockJsRequestAllowedButForeignOriginRejected() throws Exception {
        mockMvc.perform(get("/ws/info").with(user("customer").roles("USER"))
                .header("Origin", "http://localhost"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/ws/info").with(user("customer").roles("USER"))
                .header("Origin", "https://evil.example"))
            .andExpect(status().isForbidden());
    }
}
