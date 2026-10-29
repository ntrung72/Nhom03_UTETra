package vn.iotstar.controller;

import java.math.BigDecimal;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import vn.iotstar.entity.Product;
import vn.iotstar.security.CustomUserDetailsService;
import vn.iotstar.security.JwtService;
import vn.iotstar.service.CatalogService;
import vn.iotstar.service.CurrentUserService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {
    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final JwtService jwtService;
    private final CatalogService catalogService;
    private final CurrentUserService currentUserService;

    @PostMapping("/auth/login")
    @Operation(summary = "Đăng nhập để nhận JWT")
    Map<String, Object> login(@RequestBody LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        UserDetails details = userDetailsService.loadUserByUsername(auth.getName());
        return Map.of("token", jwtService.generateToken(details), "type", "Bearer", "expiresInMinutes", 1440);
    }

    @GetMapping("/products")
    Page<ProductSummary> products(@RequestParam(required = false) String q,
                                  @RequestParam(required = false) Long category,
                                  @RequestParam(defaultValue = "0") int page) {
        return catalogService.search(q, category, null, "bestselling", page).map(ProductSummary::from);
    }

    @GetMapping("/me")
    @Operation(summary = "Thông tin tài khoản hiện tại", security = @SecurityRequirement(name = "bearerAuth"))
    Map<String, Object> me() {
        var user = currentUserService.require();
        return Map.of("id", user.getId(), "username", user.getUsername(), "email", user.getEmail(), "role", user.getRole().name());
    }

    public record LoginRequest(String username, String password) {}
    public record ProductSummary(Long id, String name, BigDecimal price, String imageUrl, String shop, double rating, long sold) {
        static ProductSummary from(Product p) {
            return new ProductSummary(p.getId(), p.getName(), p.getPrice(), p.getImageUrl(), p.getShop().getName(),
                p.getAverageRating().doubleValue(), p.getSoldCount());
        }
    }
}
