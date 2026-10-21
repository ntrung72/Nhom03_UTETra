package vn.iotstar.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class MomoGatewayClient {
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public MomoGatewayClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> create(String endpoint, Map<String, Object> requestBody) {
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"test-payment.momo.vn".equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException("Chỉ hỗ trợ endpoint MoMo Sandbox chính thức.");
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalArgumentException("MoMo Sandbox chưa nhận yêu cầu thanh toán. Vui lòng thử lại.");
            }
            return mapper.readValue(response.body(), new TypeReference<>() {});
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Không thể kết nối MoMo Sandbox. Vui lòng thử lại.", ex);
        }
    }
}
