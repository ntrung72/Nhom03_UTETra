package vn.iotstar.config;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import vn.iotstar.security.CustomUserDetailsService;
import vn.iotstar.security.JwtAuthenticationFilter;

@Configuration
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
    @Order(1)
    SecurityFilterChain authenticationSecurityFilterChain(HttpSecurity http,
                                                   AuthenticationProvider authenticationProvider,
                                                   @Value("${app.remember-me.key}") String rememberMeKey) throws Exception {
        // TV3 adds the role-specific authorization chains when integrating modules.
        http.securityMatcher("/login", "/logout", "/register", "/verify-account", "/resend-otp",
                "/forgot-password", "/reset-password", "/api/auth/login", "/api/me",
                "/css/**", "/js/**", "/images/**", "/error")
            .authenticationProvider(authenticationProvider)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/me").authenticated()
                .anyRequest().permitAll())
            .formLogin(form -> form.loginPage("/login").loginProcessingUrl("/login")
                .defaultSuccessUrl("/login?success", true).failureUrl("/login?error").permitAll())
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout").permitAll())
            .rememberMe(remember -> remember.key(rememberMeKey).tokenValiditySeconds(7 * 24 * 60 * 60)
                .userDetailsService(userDetailsService))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) -> {
                    if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    else response.sendRedirect(request.getContextPath() + "/login");
                }))
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/auth/login", "/api/me"))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
