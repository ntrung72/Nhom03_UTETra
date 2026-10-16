package vn.iotstar.config;
import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Getter @Setter @Component @ConfigurationProperties(prefix = "app.vnpay")
public class VnPayProperties {
    private String paymentUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private String tmnCode;
    private String hashSecret;
    private String returnUrl = "http://localhost:8090/payments/vnpay/return";
    private int expirationMinutes = 15;
    public boolean isConfigured() {
        try {
            var payment = URI.create(paymentUrl); var target = URI.create(returnUrl);
            return tmnCode != null && tmnCode.matches("[A-Za-z0-9]{8}") && hashSecret != null && !hashSecret.isBlank()
                && "https".equals(payment.getScheme()) && java.util.Set.of("sandbox.vnpayment.vn", "pay.vnpay.vn").contains(payment.getHost())
                && payment.getRawQuery() == null && payment.getRawFragment() == null && payment.getUserInfo() == null
                && target.getHost() != null && java.util.Set.of("http", "https").contains(target.getScheme())
                && target.getRawQuery() == null && target.getRawFragment() == null && target.getUserInfo() == null
                && expirationMinutes >= 1 && expirationMinutes <= 60;
        } catch (RuntimeException ex) { return false; }
    }
}
