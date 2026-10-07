package vn.iotstar.controller;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import vn.iotstar.security.CustomUserDetailsService;
import vn.iotstar.security.JwtService;
import vn.iotstar.service.CurrentUserService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {
    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final JwtService jwtService;
    private final CurrentUserService currentUserService;

    @PostMapping("/auth/login")
    Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        UserDetails details = userDetailsService.loadUserByUsername(auth.getName());
        return Map.of("token", jwtService.generateToken(details), "type", "Bearer", "expiresInMinutes", jwtService.getExpirationMinutes());
    }

    @GetMapping("/me")
    Map<String, Object> me() {
        var user = currentUserService.require();
        return Map.of("id", user.getId(), "username", user.getUsername(), "email", user.getEmail(), "role", user.getRole().name());
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, String> invalidCredentials() {
        return Map.of("error", "Tài khoản hoặc mật khẩu không hợp lệ.");
    }

    public record LoginRequest(@NotBlank @Size(max = 160) String username,
                               @NotBlank @Size(max = 72) String password) {}

}
