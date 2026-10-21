package vn.iotstar.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.momo")
public class MomoProperties {
    private String endpoint = "https://test-payment.momo.vn/v2/gateway/api/create";
    private String partnerCode;
    private String accessKey;
    private String secretKey;
    private String redirectUrl = "http://localhost:8090/payments/momo/return";
    private String ipnUrl = "http://localhost:8090/payments/momo/ipn";
    private int expirationMinutes = 15;

    public boolean isConfigured() {
        return hasText(endpoint) && hasText(partnerCode) && hasText(accessKey) && hasText(secretKey)
            && hasText(redirectUrl) && hasText(ipnUrl);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
