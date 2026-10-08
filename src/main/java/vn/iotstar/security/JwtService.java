package vn.iotstar.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class JwtService {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private final ObjectMapper mapper;
    private final byte[] secret;
    private final long expirationSeconds;

    public JwtService(ObjectMapper mapper,
                      @Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-minutes:1440}") long expirationMinutes) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("JWT_SECRET phải có ít nhất 32 ký tự.");
        }
        this.mapper = mapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expirationSeconds = expirationMinutes * 60;
    }

    public long getExpirationMinutes() {
        return expirationSeconds / 60;
    }

    public String generateToken(UserDetails user) {
        try {
            String header = B64.encodeToString(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
            long now = Instant.now().getEpochSecond();
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", user.getUsername());
            claims.put("iat", now);
            claims.put("exp", now + expirationSeconds);
            claims.put("roles", user.getAuthorities().stream().map(Object::toString).toList());
            String payload = B64.encodeToString(mapper.writeValueAsBytes(claims));
            String unsigned = header + "." + payload;
            return unsigned + "." + B64.encodeToString(sign(unsigned));
        } catch (Exception ex) {
            throw new IllegalStateException("Không thể tạo JWT.", ex);
        }
    }

    public String extractUsername(String token) {
        Object subject = readClaims(token).get("sub");
        return subject == null ? null : subject.toString();
    }

    public boolean isValid(String token, UserDetails user) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return false;
            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] actual = B64D.decode(parts[2]);
            Map<String, Object> claims = readClaims(token);
            long expiresAt = ((Number) claims.get("exp")).longValue();
            return MessageDigest.isEqual(expected, actual)
                && user.getUsername().equals(claims.get("sub"))
                && expiresAt > Instant.now().getEpochSecond();
        } catch (Exception ignored) {
            return false;
        }
    }

    private Map<String, Object> readClaims(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) throw new IllegalArgumentException("JWT không hợp lệ.");
            return mapper.readValue(B64D.decode(parts[1]), new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalArgumentException("JWT không hợp lệ.", ex);
        }
    }

    private byte[] sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
