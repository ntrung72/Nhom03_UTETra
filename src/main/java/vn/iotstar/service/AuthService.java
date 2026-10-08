package vn.iotstar.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.RegisterForm;
import vn.iotstar.entity.DomainEnums.OtpPurpose;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.User;
import vn.iotstar.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;

    @Transactional
    public User register(RegisterForm form) {
        String username = form.getUsername().trim();
        String email = form.getEmail().trim().toLowerCase();
        if (!form.getPassword().equals(form.getConfirmPassword())) {
            throw new IllegalArgumentException("Mật khẩu xác nhận không khớp.");
        }
        validatePassword(form.getPassword());
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("Tên đăng nhập đã tồn tại.");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("Email đã được sử dụng.");
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFullName(form.getFullName().trim());
        user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        user.setRole(Role.USER);
        user.setEnabled(false);
        userRepository.save(user);
        otpService.issue(email, OtpPurpose.REGISTER);
        return user;
    }

    @Transactional(noRollbackFor = OtpService.OtpVerificationException.class)
    public void activate(String email, String code) {
        otpService.verify(email, OtpPurpose.REGISTER, code);
        User user = userRepository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản."));
        user.setEnabled(true);
    }

    public void resendActivation(String email) {
        User user = userRepository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản."));
        if (user.isEnabled()) throw new IllegalArgumentException("Tài khoản đã được kích hoạt.");
        otpService.issue(user.getEmail(), OtpPurpose.REGISTER);
    }

    public long remainingOtpCooldown(String email) {
        return otpService.remainingCooldownSeconds(email);
    }

    private void validatePassword(String password) {
        if (password == null || password.isBlank() || password.length() < 8
            || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Mật khẩu phải có ít nhất 8 ký tự và không vượt quá 72 byte UTF-8.");
        }
    }
}
