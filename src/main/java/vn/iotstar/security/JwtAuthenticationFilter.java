package vn.iotstar.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
            && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7);
            try {
                String username = jwtService.extractUsername(token);
                UserDetails details = userDetailsService.loadUserByUsername(username);
                if (details.isEnabled() && details.isAccountNonLocked()
                    && details.isAccountNonExpired() && details.isCredentialsNonExpired()
                    && jwtService.isValid(token, details)) {
                    UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (RuntimeException ignored) {
                // Token sai được xem như request chưa đăng nhập; Security xử lý quyền ở bước sau.
            }
        }
        chain.doFilter(request, response);
    }
}
