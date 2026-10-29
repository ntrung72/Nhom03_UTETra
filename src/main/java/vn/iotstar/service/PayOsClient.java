package vn.iotstar.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.iotstar.entity.PaymentTransaction;

@Component
public class PayOsClient {
    private static final String CHECKOUT_PREFIX = "https://pay.payos.vn/web/";
    private static final long ORDER_CODE_BASE = 1_000_000_000_000L;
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${app.payos.client-id:}") private String clientId;
    @Value("${app.payos.api-key:}") private String apiKey;
    @Value("${app.payos.checksum-key:}") private String checksumKey;
    @Value("${app.payos.api-url:https://api-merchant.payos.vn}") private String apiUrl;
    @Value("${app.payos.public-base-url:http://localhost:8090}") private String publicBaseUrl;

    public PayOsClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void requireConfigured() {
        if (clientId.isBlank() || apiKey.isBlank() || checksumKey.isBlank()
            || !publicBaseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("payOS chưa được cấu hình đầy đủ. Vui lòng liên hệ quản trị viên.");
        }
    }

    public String create(PaymentTransaction payment) {
        requireConfigured();
        long amount = payment.getAmount().longValueExact();
        long orderCode = orderCodeFor(payment);
        String description = payment.getOrder().getCode();
        String returnUrl = publicBaseUrl + "/bank-transfer/" + description;
        String cancelUrl = returnUrl;
        String raw = "amount=" + amount + "&cancelUrl=" + cancelUrl + "&description=" + description
            + "&orderCode=" + orderCode + "&returnUrl=" + returnUrl;
        long expiresAt = payment.getExpiresAt().atZone(VIETNAM_ZONE).toEpochSecond();
        JsonNode data = request("POST", "/v2/payment-requests", Map.of(
            "orderCode", orderCode, "amount", amount, "description", description,
            "cancelUrl", cancelUrl, "returnUrl", returnUrl, "expiredAt", expiresAt,
            "signature", hmac(raw)));
        String url = data.path("checkoutUrl").asText("");
        String id = data.path("paymentLinkId").asText("");
        if (!url.equals(CHECKOUT_PREFIX + id) || !id.matches("[a-zA-Z0-9]{16,64}")
            || data.path("orderCode").asLong(-1) != orderCode
            || data.path("amount").asLong(-1) != amount || !"VND".equals(data.path("currency").asText())) {
            throw new IllegalStateException("payOS trả về link thanh toán không khớp đơn hàng.");
        }
        return url;
    }

    public JsonNode get(PaymentTransaction payment) {
        return request("GET", "/v2/payment-requests/" + orderCodeFor(payment), null);
    }

    public void cancel(PaymentTransaction payment) {
        request("POST", "/v2/payment-requests/" + orderCodeFor(payment) + "/cancel",
            Map.of("cancellationReason", "UTETra order cancelled"));
    }

    public boolean isPayOs(PaymentTransaction payment) {
        return payment.getGatewayTransactionId() != null
            && payment.getGatewayTransactionId().matches("https://pay\\.payos\\.vn/web/[a-zA-Z0-9]{16,64}");
    }

    public long orderCodeFor(PaymentTransaction payment) {
        return Math.addExact(ORDER_CODE_BASE, payment.getId());
    }

    public long paymentIdFromOrderCode(long orderCode) {
        return orderCode - ORDER_CODE_BASE;
    }

    public boolean validWebhookSignature(JsonNode data, String signature) {
        if (data == null || !data.isObject() || signature == null || !signature.matches("(?i)[a-f0-9]{64}")
            || checksumKey.isBlank()) return false;
        StringBuilder raw = new StringBuilder();
        data.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!raw.isEmpty()) raw.append('&');
            JsonNode value = entry.getValue();
            raw.append(entry.getKey()).append('=').append(value.isNull() ? "" : value.asText());
        });
        byte[] expected = HexFormat.of().parseHex(hmac(raw.toString()));
        byte[] actual = HexFormat.of().parseHex(signature.toLowerCase());
        return MessageDigest.isEqual(expected, actual);
    }

    private String hmac(String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(checksumKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Không thể ký dữ liệu payOS.", ex);
        }
    }

    private JsonNode request(String method, String path, Object body) {
        requireConfigured();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiUrl + path))
                .timeout(Duration.ofSeconds(12))
                .header("x-client-id", clientId).header("x-api-key", apiKey)
                .header("Content-Type", "application/json");
            if ("POST".equals(method)) builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            else builder.GET();
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            if (response.statusCode() / 100 != 2 || !"00".equals(root.path("code").asText())
                || !root.path("data").isObject()) {
                throw new IllegalStateException("payOS không thể xử lý yêu cầu (HTTP " + response.statusCode() + ").");
            }
            return root.path("data");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Không thể kết nối payOS.", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Không thể kết nối payOS.", ex);
        }
    }
}
