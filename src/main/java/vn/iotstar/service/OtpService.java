package vn.iotstar.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.DomainEnums.OtpPurpose;
import vn.iotstar.entity.OtpToken;
import vn.iotstar.repository.OtpTokenRepository;

@Service
@RequiredArgsConstructor
public class OtpService {
    private final OtpTokenRepository otpRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.otp.expiration-minutes:5}")
    private long expirationMinutes;

    @Value("${app.otp.cooldown-seconds:60}")
    private long cooldownSeconds;

    @Value("${app.otp.max-per-hour:5}")
    private long maxPerHour;

    @Transactional
    public void issue(String email, OtpPurpose purpose) {
        String normalized = email.trim().toLowerCase();
        LocalDateTime now = LocalDateTime.now();
        enforceSendLimit(normalized, now);
        otpRepository.findFirstByEmailIgnoreCaseAndPurposeAndUsedFalseOrderByCreatedAtDesc(normalized, purpose)
            .ifPresent(old -> { old.setUsed(true); otpRepository.save(old); });
        String code = String.format("%06d", random.nextInt(1_000_000));
        OtpToken token = new OtpToken();
        token.setEmail(normalized);
        token.setPurpose(purpose);
        token.setCodeHash(passwordEncoder.encode(code));
        token.setCreatedAt(now);
        token.setExpiresAt(now.plusMinutes(expirationMinutes));
        otpRepository.save(token);
        mailService.sendOtp(normalized, code, "kích hoạt tài khoản");
    }

    private void enforceSendLimit(String email, LocalDateTime now) {
        long sentLastHour = otpRepository.countByEmailIgnoreCaseAndCreatedAtGreaterThanEqual(email, now.minusHours(1));
        if (sentLastHour >= maxPerHour) {
            throw new IllegalArgumentException("Bạn chỉ được yêu cầu tối đa " + maxPerHour
                + " mã OTP trong một giờ. Vui lòng thử lại sau.");
        }
        otpRepository.findFirstByEmailIgnoreCaseOrderByCreatedAtDesc(email).ifPresent(latest -> {
            long elapsedSeconds = Math.max(0, Duration.between(latest.getCreatedAt(), now).getSeconds());
            if (elapsedSeconds < cooldownSeconds) {
                long remainingSeconds = cooldownSeconds - elapsedSeconds;
                throw new IllegalArgumentException("Vui lòng chờ " + remainingSeconds
                    + " giây trước khi yêu cầu gửi lại OTP.");
            }
        });
    }

    @Transactional(readOnly = true)
    public long remainingCooldownSeconds(String email) {
        if (email == null || email.isBlank()) return 0;
        LocalDateTime now = LocalDateTime.now();
        return otpRepository.findFirstByEmailIgnoreCaseOrderByCreatedAtDesc(email.trim().toLowerCase())
            .map(latest -> {
                long elapsedSeconds = Math.max(0, Duration.between(latest.getCreatedAt(), now).getSeconds());
                return Math.max(0, cooldownSeconds - elapsedSeconds);
            })
            .orElse(0L);
    }

    @Transactional(noRollbackFor = OtpVerificationException.class)
    public void verify(String email, OtpPurpose purpose, String code) {
        OtpToken token = otpRepository
            .findFirstByEmailIgnoreCaseAndPurposeAndUsedFalseOrderByCreatedAtDesc(email.trim().toLowerCase(), purpose)
            .orElseThrow(() -> new OtpVerificationException("Không tìm thấy OTP còn hiệu lực."));
        if (token.getAttempts() >= 5) {
            token.setUsed(true);
            throw new OtpVerificationException("Bạn đã nhập sai quá 5 lần. Hãy yêu cầu OTP mới.");
        }
        if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
            token.setUsed(true);
            throw new OtpVerificationException("OTP đã hết hạn. Hãy yêu cầu mã mới.");
        }
        token.setAttempts(token.getAttempts() + 1);
        if (!passwordEncoder.matches(code == null ? "" : code.trim(), token.getCodeHash())) {
            if (token.getAttempts() >= 5) token.setUsed(true);
            throw new OtpVerificationException("Mã OTP không đúng.");
        }
        token.setUsed(true);
    }
    public static final class OtpVerificationException extends IllegalArgumentException {
        public OtpVerificationException(String message) {
            super(message);
        }
    }
}
