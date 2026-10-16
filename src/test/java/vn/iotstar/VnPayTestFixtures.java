package vn.iotstar;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.PaymentTransactionRepository;
import vn.iotstar.service.*;
abstract class VnPayTestFixtures extends CheckoutTestFixtures {
    static final String SECRET = "test-only-vnpay-hmac-key";
    @Autowired VnPayService vnPay;
    @Autowired CheckoutPaymentService payments;
    @Autowired VnPayExpirationService expiration;
    @Autowired PaymentTransactionRepository paymentRows;
    CheckoutPaymentService.CheckoutResult online(Long... ids) {
        var f = form(ids); f.setPaymentMethod(PaymentMethod.VNPAY);
        return payments.checkout(customer, f, "127.0.0.1");
    }
    Map<String,String> query(String url) {
        var result = new TreeMap<String,String>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            var split = pair.split("=",2); result.put(URLDecoder.decode(split[0],StandardCharsets.UTF_8), URLDecoder.decode(split[1],StandardCharsets.UTF_8));
        }
        return result;
    }
    Map<String,String> callback(String url, boolean success) {
        var sent = query(url); var p = new TreeMap<String,String>();
        p.put("vnp_TmnCode","TESTCODE"); p.put("vnp_TxnRef",sent.get("vnp_TxnRef")); p.put("vnp_Amount",sent.get("vnp_Amount"));
        p.put("vnp_ResponseCode",success?"00":"24"); p.put("vnp_TransactionStatus",success?"00":"02");
        p.put("vnp_TransactionNo","123456789"); p.put("vnp_PayDate",LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        return signed(p);
    }
    Map<String,String> signed(Map<String,String> input) {
        var p = new TreeMap<>(input); p.remove("vnp_SecureHash");
        var text = p.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));
        try {
            var mac=Mac.getInstance("HmacSHA512"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA512"));
            p.put("vnp_SecureHash", HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)))); return p;
        } catch(Exception e) { throw new AssertionError(e); }
    }
}
