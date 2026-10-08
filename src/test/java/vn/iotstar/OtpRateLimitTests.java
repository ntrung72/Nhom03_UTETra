package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.DomainEnums.OtpPurpose;
import vn.iotstar.entity.OtpToken;
import vn.iotstar.repository.OtpTokenRepository;
import vn.iotstar.service.OtpService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class OtpRateLimitTests {
    @Autowired private OtpService otpService;
    @Autowired private OtpTokenRepository otpRepository;

    @Test
    void requiresSixtySecondsBeforeSendingAgain() {
        String email = "otp-cooldown@utetra.test";
        otpService.issue(email, OtpPurpose.REGISTER);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> otpService.issue(email, OtpPurpose.REGISTER));

        assertTrue(error.getMessage().contains("giây"));
        assertTrue(otpService.remainingCooldownSeconds(email) > 0);
        assertEquals(1, otpRepository.countByEmailIgnoreCaseAndCreatedAtGreaterThanEqual(
            email, LocalDateTime.now().minusHours(1)));
    }

    @Test
    void allowsOnlyFiveOtpEmailsPerHourAcrossPurposes() {
        String email = "otp-hourly@utetra.test";
        for (int index = 0; index < 5; index++) {
            otpService.issue(email, index % 2 == 0 ? OtpPurpose.REGISTER : OtpPurpose.RESET_PASSWORD);
            OtpToken latest = otpRepository.findFirstByEmailIgnoreCaseOrderByCreatedAtDesc(email).orElseThrow();
            latest.setCreatedAt(LocalDateTime.now().minusMinutes(2));
            otpRepository.saveAndFlush(latest);
        }

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> otpService.issue(email, OtpPurpose.REGISTER));

        assertTrue(error.getMessage().contains("tối đa 5"));
        assertEquals(5, otpRepository.countByEmailIgnoreCaseAndCreatedAtGreaterThanEqual(
            email, LocalDateTime.now().minusHours(1)));
    }
}
