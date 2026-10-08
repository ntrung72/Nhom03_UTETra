package vn.iotstar;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import vn.iotstar.entity.DomainEnums.OtpPurpose;
import vn.iotstar.entity.OtpToken;
import vn.iotstar.entity.User;
import vn.iotstar.repository.OtpTokenRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.AuthService;
import vn.iotstar.service.OtpService;

@ActiveProfiles("test")
@SpringBootTest
class OtpVerificationPersistenceTests {
    @Autowired OtpTokenRepository tokens;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired AuthService auth;
    @Autowired OtpService otp;

    // No test-level transaction: each failed service call must commit its failed-attempt counter.
    @Test
    void failedActivationAttemptsPersistAndLockAfterFiveFailures() {
        User user = user();
        OtpToken token = token(user, OtpPurpose.REGISTER, LocalDateTime.now().plusMinutes(5));
        for (int i = 1; i <= 5; i++) {
            assertThrows(OtpService.OtpVerificationException.class, () -> auth.activate(user.getEmail(), "000000"));
            assertEquals(i, tokens.findById(token.getId()).orElseThrow().getAttempts());
        }
        assertTrue(tokens.findById(token.getId()).orElseThrow().isUsed());
        assertFalse(users.findById(user.getId()).orElseThrow().isEnabled());
        assertThrows(OtpService.OtpVerificationException.class, () -> auth.activate(user.getEmail(), "123456"));
    }

    @Test
    void expiredOtpIsPersistentlyInvalidated() {
        User user = user();
        OtpToken token = token(user, OtpPurpose.REGISTER, LocalDateTime.now().minusMinutes(1));
        assertThrows(OtpService.OtpVerificationException.class, () -> otp.verify(user.getEmail(), OtpPurpose.REGISTER, "123456"));
        assertTrue(tokens.findById(token.getId()).orElseThrow().isUsed());
    }

    @Test
    void validActivationConsumesOtpAndCannotBeReplayed() {
        User user = user();
        OtpToken token = token(user, OtpPurpose.REGISTER, LocalDateTime.now().plusMinutes(5));
        auth.activate(user.getEmail(), "123456");
        assertTrue(users.findById(user.getId()).orElseThrow().isEnabled());
        assertTrue(tokens.findById(token.getId()).orElseThrow().isUsed());
        assertThrows(OtpService.OtpVerificationException.class, () -> auth.activate(user.getEmail(), "123456"));
    }

    @Test
    void failedPasswordResetPersistsAttemptsAndKeepsPasswordUnchanged() {
        User user = user();
        OtpToken token = token(user, OtpPurpose.RESET_PASSWORD, LocalDateTime.now().plusMinutes(5));
        assertThrows(OtpService.OtpVerificationException.class,
            () -> auth.resetPassword(user.getEmail(), "000000", "NewPassword123", "NewPassword123"));
        assertEquals(1, tokens.findById(token.getId()).orElseThrow().getAttempts());
        assertTrue(encoder.matches("OldPassword123", users.findById(user.getId()).orElseThrow().getPasswordHash()));
    }

    @Test
    void invalidNewPasswordDoesNotConsumeOtp() {
        User user = user();
        OtpToken token = token(user, OtpPurpose.RESET_PASSWORD, LocalDateTime.now().plusMinutes(5));
        String password = "ệ".repeat(25);
        assertThrows(IllegalArgumentException.class,
            () -> auth.resetPassword(user.getEmail(), "123456", password, password));
        OtpToken stored = tokens.findById(token.getId()).orElseThrow();
        assertFalse(stored.isUsed());
        assertEquals(0, stored.getAttempts());
        assertTrue(encoder.matches("OldPassword123", users.findById(user.getId()).orElseThrow().getPasswordHash()));
    }

    private OtpToken token(User user, OtpPurpose purpose, LocalDateTime expiresAt) {
        OtpToken token = new OtpToken(); token.setEmail(user.getEmail()); token.setPurpose(purpose);
        token.setCodeHash(encoder.encode("123456")); token.setExpiresAt(expiresAt);
        return tokens.saveAndFlush(token);
    }

    private User user() {
        User user = new User(); String suffix = UUID.randomUUID().toString();
        user.setUsername(suffix); user.setEmail(suffix + "@utetra.test"); user.setFullName(suffix);
        user.setPasswordHash(encoder.encode("OldPassword123"));
        return users.saveAndFlush(user);
    }
}
