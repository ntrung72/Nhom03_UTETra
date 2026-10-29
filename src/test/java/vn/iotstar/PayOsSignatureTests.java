package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.PaymentTransaction;
import vn.iotstar.service.PayOsClient;

class PayOsSignatureTests {
    @Test
    void createsPaymentLinkWithOrderAmountAndReturnAddress() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> received = new AtomicReference<>();
        AtomicInteger fetched = new AtomicInteger();
        AtomicInteger cancelled = new AtomicInteger();
        server.createContext("/v2/payment-requests", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"code":"00","data":{"orderCode":1000000000042,"amount":58000,"currency":"VND",
                "paymentLinkId":"1234567890123456","checkoutUrl":"https://pay.payos.vn/web/1234567890123456"}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v2/payment-requests/1000000000042", exchange -> {
            fetched.incrementAndGet();
            byte[] response = "{\"code\":\"00\",\"data\":{\"orderCode\":1000000000042,\"status\":\"PENDING\"}}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/v2/payment-requests/1000000000042/cancel", exchange -> {
            cancelled.incrementAndGet();
            byte[] response = "{\"code\":\"00\",\"data\":{\"orderCode\":1000000000042,\"status\":\"CANCELLED\"}}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var mapper = new ObjectMapper();
            var client = new PayOsClient(mapper);
            ReflectionTestUtils.setField(client, "clientId", "client-test");
            ReflectionTestUtils.setField(client, "apiKey", "api-test");
            ReflectionTestUtils.setField(client, "checksumKey", "checksum-test");
            ReflectionTestUtils.setField(client, "apiUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(client, "publicBaseUrl", "https://utetra.example");
            CustomerOrder order = new CustomerOrder();
            order.setCode("UTE123456789");
            PaymentTransaction payment = new PaymentTransaction();
            payment.setId(42L);
            payment.setOrder(order);
            payment.setAmount(new BigDecimal("58000"));
            payment.setExpiresAt(LocalDateTime.now().plusHours(1));
            assertThat(client.create(payment)).isEqualTo("https://pay.payos.vn/web/1234567890123456");
            var sent = mapper.readTree(received.get());
            assertThat(sent.path("orderCode").asLong()).isEqualTo(1_000_000_000_042L);
            assertThat(sent.path("amount").asLong()).isEqualTo(58000);
            assertThat(sent.path("description").asText()).isEqualTo("UTE123456789");
            assertThat(sent.path("returnUrl").asText())
                .isEqualTo("https://utetra.example/bank-transfer/UTE123456789");
            assertThat(sent.path("signature").asText()).hasSize(64);
            String signed = "amount=58000&cancelUrl=https://utetra.example/bank-transfer/UTE123456789"
                + "&description=UTE123456789&orderCode=1000000000042"
                + "&returnUrl=https://utetra.example/bank-transfer/UTE123456789";
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("checksum-test".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            assertThat(sent.path("signature").asText()).isEqualTo(HexFormat.of().formatHex(
                mac.doFinal(signed.getBytes(StandardCharsets.UTF_8))));
            assertThat(client.get(payment).path("status").asText()).isEqualTo("PENDING");
            client.cancel(payment);
            assertThat(fetched.get()).isEqualTo(1);
            assertThat(cancelled.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsOfficialWebhookExampleAndRejectsChangedAmount() {
        var mapper = new ObjectMapper();
        var client = new PayOsClient(mapper);
        ReflectionTestUtils.setField(client, "checksumKey",
            "1a54716c8f0efb2744fb28b6e38b25da7f67a925d98bc1c18bd8faaecadd7675");
        var data = mapper.readTree("""
            {"orderCode":123,"amount":3000,"description":"VQRIO123","accountNumber":"12345678",
             "reference":"TF230204212323","transactionDateTime":"2023-02-04 18:25:00",
             "currency":"VND","paymentLinkId":"124c33293c43417ab7879e14c8d9eb18",
             "code":"00","desc":"Thành công","counterAccountBankId":"",
             "counterAccountBankName":"","counterAccountName":"","counterAccountNumber":"",
             "virtualAccountName":"","virtualAccountNumber":""}
            """);
        String signature = "412e915d2871504ed31be63c8f62a149a4410d34c4c42affc9006ef9917eaa03";
        assertThat(client.validWebhookSignature(data, signature)).isTrue();
        var changed = mapper.readTree(data.toString().replace("\"amount\":3000", "\"amount\":3001"));
        assertThat(client.validWebhookSignature(changed, signature)).isFalse();
    }
}
