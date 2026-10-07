package vn.iotstar.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${app.mail.from:}")
    private String from;

    @Async
    public void sendOtp(String email, String code, String purpose) {
        if (mailUsername == null || mailUsername.isBlank()) {
            log.warn("Gmail chưa cấu hình. OTP DEV cho {} ({}): {}", email, purpose, code);
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from == null || from.isBlank() ? mailUsername : from);
        message.setTo(email);
        message.setSubject("UTETra - Mã OTP " + purpose);
        message.setText("Mã OTP của bạn là: " + code + "\nMã có hiệu lực 5 phút và chỉ dùng một lần.");
        mailSender.send(message);
    }
}
