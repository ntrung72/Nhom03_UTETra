package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.PaymentMethod;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.PaymentTransactionStatus;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.PaymentTransaction;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.PaymentTransactionRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;

@SpringBootTest(properties = "app.payos.checksum-key=integration-test-secret")
@ActiveProfiles("test")
@Transactional
class PayOsPaymentTests {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private ShopRepository shops;
    @Autowired private OrderRepository orders;
    @Autowired private PaymentTransactionRepository payments;

    @Test
    void validWebhookPaysOrderAndDuplicateDoesNotChangeIt() throws Exception {
        PaymentTransaction payment = createPayment("PAYOS-VALID", "58000");
        String body = webhook(payment, 58000, "1234567890123456");
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(post("/payments/payos/webhook").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        assertThat(orders.findById(payment.getOrder().getId()).orElseThrow().getPaymentStatus())
            .isEqualTo(PaymentStatus.PAID);
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentTransactionStatus.SUCCESS);
        mvc.perform(post("/payments/payos/webhook").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        assertThat(payments.findById(payment.getId()).orElseThrow().getStatus())
            .isEqualTo(PaymentTransactionStatus.SUCCESS);
    }

    @Test
    void wrongAmountRequiresAdminAndTamperedSignatureCannotPay() throws Exception {
        PaymentTransaction payment = createPayment("PAYOS-WRONG", "58000");
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String tampered = webhook(payment, 58000, "1234567890123456")
            .replace("\"amount\":58000", "\"amount\":1");
        mvc.perform(post("/payments/payos/webhook").contentType(MediaType.APPLICATION_JSON).content(tampered))
            .andExpect(status().isBadRequest());
        assertThat(payment.getOrder().getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        mvc.perform(post("/payments/payos/webhook").contentType(MediaType.APPLICATION_JSON)
            .content(webhook(payment, 57000, "1234567890123456"))).andExpect(status().isOk());
        assertThat(payment.getOrder().getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        assertThat(payment.getStatus()).isEqualTo(PaymentTransactionStatus.AWAITING_REVIEW);
    }

    @Test
    void paymentReceivedAfterCancellationNeedsAdminReview() throws Exception {
        PaymentTransaction payment = createPayment("PAYOS-LATE", "58000");
        payment.getOrder().setStatus(OrderStatus.CANCELLED);
        payment.setStatus(PaymentTransactionStatus.CANCELLED);
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(post("/payments/payos/webhook").contentType(MediaType.APPLICATION_JSON)
            .content(webhook(payment, 58000, "1234567890123456"))).andExpect(status().isOk());
        assertThat(payment.getOrder().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(payment.getOrder().getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        assertThat(payment.getStatus()).isEqualTo(PaymentTransactionStatus.AWAITING_REVIEW);
    }

    private PaymentTransaction createPayment(String code, String amount) {
        User buyer = new User();
        buyer.setUsername("buyer-" + code);
        buyer.setEmail("buyer-" + code + "@example.test");
        buyer.setPasswordHash("test-hash");
        buyer.setFullName("Buyer");
        buyer.setRole(Role.USER);
        buyer.setEnabled(true);
        users.save(buyer);
        User vendor = new User();
        vendor.setUsername("vendor-" + code);
        vendor.setEmail("vendor-" + code + "@example.test");
        vendor.setPasswordHash("test-hash");
        vendor.setFullName("Vendor");
        vendor.setRole(Role.VENDOR);
        vendor.setEnabled(true);
        users.save(vendor);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("Shop " + code);
        shop.setSlug("shop-" + code.toLowerCase());
        shop.setStatus(ShopStatus.APPROVED);
        shops.save(shop);
        CustomerOrder order = new CustomerOrder();
        order.setCode(code);
        order.setUser(buyer);
        order.setShop(shop);
        order.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        order.setReceiverName("Buyer");
        order.setReceiverPhone("0901234567");
        order.setShippingAddress("Thu Duc");
        order.setSubtotal(new BigDecimal(amount));
        order.setTotalAmount(new BigDecimal(amount));
        orders.save(order);
        PaymentTransaction payment = new PaymentTransaction();
        payment.setOrder(order);
        payment.setTransactionCode(code);
        payment.setAmount(new BigDecimal(amount));
        payment.setGatewayTransactionId("https://pay.payos.vn/web/1234567890123456");
        return payments.save(payment);
    }

    private String webhook(PaymentTransaction payment, long amount, String linkId) throws Exception {
        String raw = "{\"orderCode\":" + (1_000_000_000_000L + payment.getId()) + ",\"amount\":" + amount
            + ",\"description\":\"" + payment.getOrder().getCode() + "\",\"currency\":\"VND\""
            + ",\"paymentLinkId\":\"" + linkId + "\",\"code\":\"00\"}";
        JsonNode data = mapper.readTree(raw);
        StringBuilder signatureData = new StringBuilder();
        data.properties().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            if (!signatureData.isEmpty()) signatureData.append('&');
            signatureData.append(entry.getKey()).append('=').append(entry.getValue().asText());
        });
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("integration-test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(signatureData.toString().getBytes(StandardCharsets.UTF_8)));
        return "{\"success\":true,\"code\":\"00\",\"data\":" + raw
            + ",\"signature\":\"" + signature + "\"}";
    }
}
