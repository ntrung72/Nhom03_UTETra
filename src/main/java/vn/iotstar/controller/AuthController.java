package vn.iotstar.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import vn.iotstar.dto.WebForms.RegisterForm;
import vn.iotstar.service.AuthService;

@Controller
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @GetMapping("/login")
    String login() { return "auth/login"; }

    @GetMapping("/register")
    String register(Model model) {
        if (!model.containsAttribute("form")) model.addAttribute("form", new RegisterForm());
        return "auth/register";
    }

    @PostMapping("/register")
    String register(@Valid @ModelAttribute("form") RegisterForm form, BindingResult result, Model model) {
        if (result.hasErrors()) return "auth/register";
        try {
            authService.register(form);
            return redirectWithEmail("/verify-account", form.getEmail());
        } catch (IllegalArgumentException ex) {
            model.addAttribute("error", ex.getMessage());
            return "auth/register";
        }
    }

    @GetMapping("/verify-account")
    String verify(@RequestParam String email, Model model) {
        model.addAttribute("email", email);
        model.addAttribute("otpCooldownSeconds", authService.remainingOtpCooldown(email));
        return "auth/verify";
    }

    @PostMapping("/verify-account")
    String verify(@RequestParam String email, @RequestParam String code, RedirectAttributes redirect) {
        try {
            authService.activate(email, code);
            redirect.addFlashAttribute("success", "Kích hoạt tài khoản thành công. Bạn có thể đăng nhập.");
            return "redirect:/login";
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return redirectWithEmail("/verify-account", email);
        }
    }

    @PostMapping("/resend-otp")
    String resend(@RequestParam String email, RedirectAttributes redirect) {
        try {
            authService.resendActivation(email);
            redirect.addFlashAttribute("success", "Đã gửi OTP mới.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return redirectWithEmail("/verify-account", email);
    }

    @GetMapping("/forgot-password")
    String forgot() { return "auth/forgot"; }

    @PostMapping("/forgot-password")
    String forgot(@RequestParam String email, RedirectAttributes redirect) {
        try {
            authService.forgotPassword(email);
            redirect.addFlashAttribute("success", "OTP đặt lại mật khẩu đã được gửi.");
            return redirectWithEmail("/reset-password", email);
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/forgot-password";
        }
    }

    @GetMapping("/reset-password")
    String reset(@RequestParam String email, Model model) {
        model.addAttribute("email", email);
        return "auth/reset";
    }

    @PostMapping("/reset-password")
    String reset(@RequestParam String email, @RequestParam String code,
                 @RequestParam String password, @RequestParam String confirmPassword,
                 RedirectAttributes redirect) {
        try {
            authService.resetPassword(email, code, password, confirmPassword);
            redirect.addFlashAttribute("success", "Đổi mật khẩu thành công.");
            return "redirect:/login";
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return redirectWithEmail("/reset-password", email);
        }
    }

    private String redirectWithEmail(String path, String email) {
        return "redirect:" + UriComponentsBuilder.fromPath(path).queryParam("email", email).build().encode().toUriString();
    }
}
