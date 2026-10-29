package vn.iotstar.config;

import java.io.IOException;
import java.net.URI;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import vn.iotstar.security.CustomUserDetailsService;
import vn.iotstar.security.JwtAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtFilter;
    private final CustomUserDetailsService userDetailsService;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationProvider authenticationProvider(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            AuthenticationProvider authenticationProvider,
                                            @Value("${app.remember-me.key}") String rememberMeKey) throws Exception {
        http
            .authenticationProvider(authenticationProvider)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/uploads/returns/**", "/uploads/delivery-proofs/**").denyAll()
                .requestMatchers(HttpMethod.GET, "/api/search/suggestions").permitAll()
                .requestMatchers("/", "/policies", "/products/**", "/shops", "/shops/**", "/login", "/register", "/verify-account",
                    "/resend-otp", "/forgot-password", "/reset-password", "/css/**", "/js/**", "/images/**",
                    "/uploads/avatars/**", "/uploads/products/**", "/uploads/shops/**",
                    "/uploads/categories/**", "/uploads/reviews/**", "/error", "/api/auth/login", "/api/products",
                    "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**",
                    "/payments/payos/webhook").permitAll()
                .requestMatchers(HttpMethod.GET, "/private-media/**")
                    .hasAnyRole("USER", "VENDOR", "SHIPPER", "MANAGER", "ADMIN")
                .requestMatchers("/notifications", "/notifications/**", "/api/notifications/**").authenticated()
                .requestMatchers("/vendor/register").hasAnyRole("USER", "VENDOR")
                .requestMatchers("/vendor/returns/**").hasAnyRole("VENDOR", "ADMIN")
                .requestMatchers("/vendor/**").hasRole("VENDOR")
                .requestMatchers("/management/managers/**", "/management/vouchers/**", "/management/payments/**",
                    "/management/audit-logs", "/management/audit-logs/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/management/users/*/role").hasRole("ADMIN")
                .requestMatchers("/management/**").hasAnyRole("MANAGER", "ADMIN")
                .requestMatchers("/shipper/**").hasRole("SHIPPER")
                .requestMatchers("/bank-transfer/**").hasAnyRole("USER", "VENDOR")
                .requestMatchers("/user", "/user/**", "/cart", "/cart/**", "/checkout", "/checkout/**",
                    "/orders", "/orders/**", "/api/checkout", "/api/checkout/**",
                    "/api/orders", "/api/favorites", "/api/cart")
                    .hasAnyRole("USER", "VENDOR")
                .requestMatchers("/api/me").authenticated()
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .successHandler((request, response, authentication) -> redirectAfterLogin(response, authentication))
                .failureUrl("/login?error")
                .permitAll())
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/?logout").permitAll())
            .rememberMe(remember -> remember.key(rememberMeKey).tokenValiditySeconds(7 * 24 * 60 * 60)
                .userDetailsService(userDetailsService))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(this::redirectUnauthenticated)
                .accessDeniedHandler(this::redirectAccessDenied))
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/ws/**", "/payments/payos/webhook"))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void redirectAfterLogin(jakarta.servlet.http.HttpServletResponse response,
                                    org.springframework.security.core.Authentication authentication) throws IOException {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_MANAGER"));
        boolean shipper = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SHIPPER"));
        boolean vendor = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_VENDOR"));
        response.sendRedirect(admin ? "/management" : shipper ? "/shipper" : vendor ? "/vendor" : "/");
    }

    private void redirectAccessDenied(HttpServletRequest request, HttpServletResponse response,
                                      org.springframework.security.access.AccessDeniedException exception) throws IOException {
        if (isApiRequest(request) || isProtectedMediaRequest(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        Authentication authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String target = safePreviousPage(request, authentication);
        if (target == null) target = defaultPageFor(request);

        request.getSession(true).setAttribute("accessDeniedError", "Bạn không có quyền truy cập chức năng này.");
        response.sendRedirect(request.getContextPath() + target);
    }

    private void redirectUnauthenticated(HttpServletRequest request, HttpServletResponse response,
                                         org.springframework.security.core.AuthenticationException exception) throws IOException {
        if (isApiRequest(request) || isProtectedMediaRequest(request)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String target = safePreviousPage(request, null);
        if (target == null) target = "/";
        request.getSession(true).setAttribute("accessDeniedError", "Vui lòng đăng nhập để sử dụng chức năng này.");
        response.sendRedirect(request.getContextPath() + target);
    }

    private String safePreviousPage(HttpServletRequest request, Authentication authentication) {
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) return null;
        try {
            URI uri = URI.create(referer);
            if (!request.getScheme().equalsIgnoreCase(uri.getScheme())
                || !request.getServerName().equalsIgnoreCase(uri.getHost())
                || effectivePort(uri) != request.getServerPort()) return null;
            String path = withoutContextPath(request, uri.getRawPath());
            String requestedPath = withoutContextPath(request, request.getRequestURI());
            if (path == null || path.isBlank() || path.equals(requestedPath)
                || !isAllowedPage(path, authentication)) return null;
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String withoutContextPath(HttpServletRequest request, String path) {
        if (path == null) return null;
        String contextPath = request.getContextPath();
        return !contextPath.isBlank() && path.startsWith(contextPath)
            ? path.substring(contextPath.length()) : path;
    }

    private boolean isAllowedPage(String path, Authentication authentication) {
        if (isPublicPage(path)) return true;
        if (authentication == null || !authentication.isAuthenticated()) return false;
        if (matchesArea(path, "/vendor/register")) return hasAnyRole(authentication, "USER", "VENDOR");
        if (matchesArea(path, "/vendor")) return hasAnyRole(authentication, "VENDOR");
        if (matchesArea(path, "/management/managers") || matchesArea(path, "/management/vouchers")) {
            return hasAnyRole(authentication, "ADMIN");
        }
        if (matchesArea(path, "/management")) return hasAnyRole(authentication, "MANAGER", "ADMIN");
        if (matchesArea(path, "/shipper")) return hasAnyRole(authentication, "SHIPPER");
        if (matchesArea(path, "/user") || matchesArea(path, "/cart")
            || matchesArea(path, "/checkout") || matchesArea(path, "/orders")) {
            return hasAnyRole(authentication, "USER", "VENDOR");
        }
        return false;
    }

    private boolean isPublicPage(String path) {
        return path.equals("/") || matchesArea(path, "/products") || matchesArea(path, "/shops")
            || matchesArea(path, "/login") || matchesArea(path, "/register")
            || matchesArea(path, "/verify-account") || matchesArea(path, "/resend-otp")
            || matchesArea(path, "/forgot-password") || matchesArea(path, "/reset-password");
    }

    private boolean matchesArea(String path, String area) {
        return path.equals(area) || path.startsWith(area + "/");
    }

    private boolean hasAnyRole(Authentication authentication, String... roles) {
        for (String role : roles) {
            if (authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role))) return true;
        }
        return false;
    }

    private boolean isApiRequest(HttpServletRequest request) {
        return withoutContextPath(request, request.getRequestURI()).startsWith("/api/");
    }

    private boolean isProtectedMediaRequest(HttpServletRequest request) {
        String path = withoutContextPath(request, request.getRequestURI());
        return matchesArea(path, "/private-media")
            || matchesArea(path, "/uploads/returns")
            || matchesArea(path, "/uploads/delivery-proofs");
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private String defaultPageFor(HttpServletRequest request) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            if (authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_MANAGER"))) return "/management";
            if (authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SHIPPER"))) return "/shipper";
            if (authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_VENDOR"))) return "/vendor";
        }
        return "/user/profile";
    }
}
