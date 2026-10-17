package vn.iotstar.controller;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import vn.iotstar.entity.DomainEnums.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.BadCredentialsException;
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
    @Operation(summary = "Đăng nhập để nhận Bearer JWT")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException("Invalid credentials");
        }
        Authentication auth = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        UserDetails details = userDetailsService.loadUserByUsername(auth.getName());
        return new LoginResponse(jwtService.generateToken(details), "Bearer", jwtService.getExpirationMinutes());
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Thông tin tài khoản trong JWT hiện tại")
    MeResponse me() {
        var user = currentUserService.require();
        return new MeResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole());
    }

    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, String> invalidCredentials() {
        return Map.of("error", "Tài khoản hoặc mật khẩu không hợp lệ.");
    }

    public record LoginResponse(@Schema(description = "JWT dùng trong Authorization: Bearer <token>") String token,
                                @Schema(example = "Bearer") String type, long expiresInMinutes) {}
    public record MeResponse(Long id, String username, String email, Role role) {}

    public record LoginRequest(@NotBlank @Size(max = 160) String username,
                               @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
                               @NotBlank @Size(max = 72) String password) {}

}
