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
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import vn.iotstar.security.CustomUserDetailsService;
import vn.iotstar.security.JwtAuthenticationFilter;
import vn.iotstar.security.JwtService;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtService jwtService;
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
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                               AuthenticationProvider authenticationProvider) throws Exception {
        // Bearer tokens authenticate APIs; web sessions and Remember Me stay in the web chain.
        http.securityMatcher("/api/**")
            .authenticationProvider(authenticationProvider)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
            .csrf(csrf -> csrf.disable())
            .addFilterBefore(new JwtAuthenticationFilter(jwtService, userDetailsService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain authenticationSecurityFilterChain(HttpSecurity http,
                                                   AuthenticationProvider authenticationProvider,
                                                   @Value("${app.remember-me.key}") String rememberMeKey) throws Exception {
        // TV3 adds the role-specific authorization chains when integrating modules.
        http.securityMatcher("/login", "/logout", "/register", "/verify-account", "/resend-otp",
                "/forgot-password", "/reset-password", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                "/css/**", "/js/**", "/images/**", "/error")
            .authenticationProvider(authenticationProvider)
            .authorizeHttpRequests(auth -> auth
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
                }));
        return http.build();
    }
}
