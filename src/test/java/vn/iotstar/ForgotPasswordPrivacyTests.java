package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
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
import vn.iotstar.entity.DomainEnums.OtpPurpose;
import vn.iotstar.entity.User;
import vn.iotstar.repository.OtpTokenRepository;
import vn.iotstar.repository.UserRepository;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ForgotPasswordPrivacyTests {
    private static final String KNOWN_EMAIL = "forgot-known@utetra.test";
    private static final String UNKNOWN_EMAIL = "forgot-unknown@utetra.test";
    private static final String GENERIC_SUCCESS =
        "Nếu email tồn tại trong hệ thống, mã OTP đặt lại mật khẩu đã được gửi.";
    private static final String GENERIC_OTP_ERROR = "Mã OTP không hợp lệ hoặc đã hết hạn.";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private OtpTokenRepository otpRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("forgot-known");
        user.setEmail(KNOWN_EMAIL);
        user.setFullName("Forgot Password Test");
        user.setPasswordHash("encoded-password");
        user.setEnabled(true);
        userRepository.save(user);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void knownUnknownAndRateLimitedEmailsHaveSamePublicResponse() throws Exception {
        for (String email : new String[] {KNOWN_EMAIL, UNKNOWN_EMAIL, KNOWN_EMAIL}) {
            String redirect = mockMvc.perform(post("/forgot-password").with(csrf()).param("email", email))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", GENERIC_SUCCESS))
                .andReturn().getResponse().getRedirectedUrl();
            assertTrue(redirect.startsWith("/reset-password?email="));
        }
        assertTrue(otpRepository.findFirstByEmailIgnoreCaseAndPurposeAndUsedFalseOrderByCreatedAtDesc(
            KNOWN_EMAIL, OtpPurpose.RESET_PASSWORD).isPresent());
        assertTrue(otpRepository.findFirstByEmailIgnoreCaseAndPurposeAndUsedFalseOrderByCreatedAtDesc(
            UNKNOWN_EMAIL, OtpPurpose.RESET_PASSWORD).isEmpty());
    }

    @Test
    void wrongCodeAndUnknownEmailHaveSameResetError() throws Exception {
        mockMvc.perform(post("/forgot-password").with(csrf()).param("email", KNOWN_EMAIL))
            .andExpect(status().is3xxRedirection());

        for (String email : new String[] {UNKNOWN_EMAIL, KNOWN_EMAIL}) {
            mockMvc.perform(post("/reset-password").with(csrf())
                    .param("email", email).param("code", "invalid")
                    .param("password", "new-password-123")
                    .param("confirmPassword", "new-password-123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", GENERIC_OTP_ERROR));
        }
    }
}
