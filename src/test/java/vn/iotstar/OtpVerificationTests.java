package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import vn.iotstar.entity.DomainEnums.OtpPurpose;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.OtpToken;
import vn.iotstar.entity.User;
import vn.iotstar.repository.OtpTokenRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.AuthService;
import vn.iotstar.service.OtpService;

@ActiveProfiles("test")
@SpringBootTest
class OtpVerificationTests {
    private static final String CODE = "123456";

    @Autowired private OtpService otpService;
    @Autowired private AuthService authService;
    @Autowired private OtpTokenRepository otpRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void fiveWrongCodesPersistAcrossSeparateTransactionsAndInvalidateToken() {
        OtpToken token = token(uniqueEmail(), OtpPurpose.REGISTER);

        for (int attempt = 1; attempt <= 5; attempt++) {
            int expectedAttempts = attempt;
            assertThrows(IllegalArgumentException.class,
                () -> otpService.verify(token.getEmail(), OtpPurpose.REGISTER, "000000"));
            OtpToken saved = otpRepository.findById(token.getId()).orElseThrow();
            assertEquals(expectedAttempts, saved.getAttempts());
            assertEquals(expectedAttempts == 5, saved.isUsed());
        }
        assertThrows(IllegalArgumentException.class,
            () -> otpService.verify(token.getEmail(), OtpPurpose.REGISTER, CODE));
    }

    @Test
    void activationFailureKeepsAttemptsButValidCodeStillActivatesAccount() {
        User user = user(uniqueEmail(), false);
        OtpToken token = token(user.getEmail(), OtpPurpose.REGISTER);

        assertThrows(IllegalArgumentException.class, () -> authService.activate(user.getEmail(), "000000"));
        assertEquals(1, otpRepository.findById(token.getId()).orElseThrow().getAttempts());
        assertFalse(userRepository.findById(user.getId()).orElseThrow().isEnabled());

        authService.activate(user.getEmail(), CODE);
        assertTrue(userRepository.findById(user.getId()).orElseThrow().isEnabled());
        assertTrue(otpRepository.findById(token.getId()).orElseThrow().isUsed());
        assertEquals(1, otpRepository.findById(token.getId()).orElseThrow().getAttempts());
    }

    @Test
    void resetPasswordFailureKeepsAttemptsAndDoesNotChangePassword() {
        User user = user(uniqueEmail(), true);
        OtpToken token = token(user.getEmail(), OtpPurpose.RESET_PASSWORD);

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThrows(IllegalArgumentException.class,
                () -> authService.resetPassword(user.getEmail(), "000000", "new-password", "new-password"));
            assertEquals(attempt, otpRepository.findById(token.getId()).orElseThrow().getAttempts());
        }
        assertTrue(otpRepository.findById(token.getId()).orElseThrow().isUsed());
        assertTrue(passwordEncoder.matches("old-password",
            userRepository.findById(user.getId()).orElseThrow().getPasswordHash()));
        assertThrows(IllegalArgumentException.class,
            () -> authService.resetPassword(user.getEmail(), CODE, "new-password", "new-password"));
    }

    private User user(String email, boolean enabled) {
        User user = new User();
        user.setUsername(email.substring(0, email.indexOf('@')));
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("old-password"));
        user.setFullName("OTP verification test");
        user.setRole(Role.USER);
        user.setEnabled(enabled);
        return userRepository.save(user);
    }

    private OtpToken token(String email, OtpPurpose purpose) {
        OtpToken token = new OtpToken();
        token.setEmail(email);
        token.setPurpose(purpose);
        token.setCodeHash(passwordEncoder.encode(CODE));
        token.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        return otpRepository.save(token);
    }

    private String uniqueEmail() {
        return "otp-verify-" + UUID.randomUUID().toString().substring(0, 8) + "@utetra.test";
    }
}
