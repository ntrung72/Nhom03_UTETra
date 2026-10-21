package vn.iotstar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.config.MomoProperties;
import vn.iotstar.service.*;
import vn.iotstar.repository.PaymentTransactionRepository;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"app.momo.partner-code=TESTPARTNER","app.momo.access-key=TESTACCESS","app.momo.secret-key=TESTSECRET"})
@ActiveProfiles("test") @Transactional
class MomoPaymentTests extends CheckoutTestFixtures {
 @Autowired MomoService momo;
 @Autowired CheckoutPaymentService payments;
 @Autowired MomoProperties config;
 @Autowired PaymentTransactionRepository transactions;
 @MockitoBean MomoGatewayClient gateway;
 @BeforeEach void gateway(){when(gateway.create(anyString(),anyMap())).thenAnswer(call->{Map<String,Object> body=call.getArgument(1);
    assertThat(body.get("autoCapture")).isEqualTo(true);assertThat(body.get("signature").toString()).hasSize(64);
    return Map.of("resultCode",0,"orderId",body.get("orderId"),"requestId",body.get("requestId"),"amount",body.get("amount"),"payUrl","https://test-payment.momo.vn/v2/gateway/pay?reference="+body.get("orderId"));});}
 List<CustomerOrder> checkout(){var line=add(tea,medium,1);var form=form(line.getId());form.setPaymentMethod(PaymentMethod.MOMO);return payments.checkout(customer,form,"127.0.0.1").orders();}
 Map<String,String> callback(CustomerOrder order,String code) throws Exception {
  var row=transactions.findFirstByOrderIdOrderByCreatedAtDescIdDesc(order.getId()).orElseThrow();var ref=row.getTransactionCode();
  var result=new HashMap<String,String>();result.put("partnerCode",config.getPartnerCode());result.put("orderId",ref);result.put("requestId",ref);result.put("amount",transactions.findByTransactionCodeOrderByIdAsc(ref).stream().map(PaymentTransaction::getAmount).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add).toBigIntegerExact().toString());
  result.put("extraData","");result.put("message","Successful");result.put("orderInfo","Thanh toan don hang UTETra "+ref);result.put("orderType","momo_wallet");result.put("payType","qr");result.put("responseTime",Long.toString(System.currentTimeMillis()));result.put("resultCode",code);result.put("transId","123456789");return sign(result);
 }
 Map<String,String> sign(Map<String,String> data) throws Exception {
  String raw="accessKey="+config.getAccessKey();for(String key:List.of("amount","extraData","message","orderId","orderInfo","orderType","partnerCode","payType","requestId","responseTime","resultCode","transId"))raw+="&"+key+"="+data.getOrDefault(key,"");
  var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(config.getSecretKey().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));data.put("signature",HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))));return data;
 }
 @Test void correctIpnSettlesOnceAndBrowserReturnNeverSettles() throws Exception {
  var order=checkout().getFirst();var cb=callback(order,"0");
  mvc.perform(get("/payments/momo/return").param("resultCode","0")).andExpect(status().is3xxRedirection());assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
  assertThat(momo.processCallback(cb).success()).isTrue();var paid=transactions.findByTransactionCodeOrderByIdAsc(cb.get("orderId")).getFirst().getPaidAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  assertThat(momo.processCallback(cb).success()).isTrue();assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
  assertThat(transactions.findByTransactionCodeOrderByIdAsc(cb.get("orderId"))).singleElement().extracting(t->t.getPaidAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS)).isEqualTo(paid);
 }
 @Test void signedWrongAmountOrMerchantAndBadSignatureAreRejected() throws Exception {
  var order=checkout().getFirst();var bad=callback(order,"0");bad.put("amount","1");assertThat(momo.processCallback(sign(bad)).valid()).isFalse();
  bad=callback(order,"0");bad.put("partnerCode","OTHER");assertThat(momo.processCallback(sign(bad)).valid()).isFalse();
  bad=callback(order,"0");bad.put("signature","0".repeat(64));assertThat(momo.processCallback(bad).valid()).isFalse();assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
 }
 @Test void authorizationDoesNotMarkPaidAndLiveRetryReusesReference() throws Exception {
  var order=checkout().getFirst();var reference=callback(order,"9000");assertThat(momo.processCallback(reference).success()).isFalse();assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
  momo.retryPaymentUrl(customer,order.getId(),"127.0.0.1");verify(gateway,times(1)).create(anyString(),anyMap());
  assertThat(momo.processCallback(callback(order,"0")).success()).isTrue();
 }
 @Test void failureCanRetryButLateSuccessIsFlaggedWithoutRevivingOldAttempt() throws Exception {
  var order=checkout().getFirst();var old=callback(order,"0");assertThat(momo.processCallback(callback(order,"1006")).valid()).isTrue();
  momo.retryPaymentUrl(customer,order.getId(),"127.0.0.1");assertThat(momo.processCallback(old).success()).isFalse();
  assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);assertThat(transactions.existsByOrderIdAndReconciliationRequiredTrue(order.getId())).isTrue();
 }
 @Test void expirationRestoresInventoryOnceAndLatePaymentRequiresReconciliation() throws Exception {
  var order=checkout().getFirst();var cb=callback(order,"0");var past=LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusMinutes(1);
  order.setPaymentExpiresAt(past);orderRepository.saveAndFlush(order);transactions.findByTransactionCodeOrderByIdAsc(cb.get("orderId")).forEach(t->t.setExpiresAt(past));transactions.flush();
  assertThat(momo.expireCheckout(order.getCheckoutReference())).isEqualTo(1);assertThat(momo.expireCheckout(order.getCheckoutReference())).isZero();
  assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);assertThat(products.findById(tea.getId()).orElseThrow().getStock()).isEqualTo(10);
  momo.processCallback(cb);assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);assertThat(transactions.existsByOrderIdAndReconciliationRequiredTrue(order.getId())).isTrue();
 }
 @Test void invalidGatewayResponseRollsBackCheckout(){
  doReturn(Map.of("resultCode",0,"orderId","wrong","requestId","wrong","payUrl","https://evil.example")).when(gateway).create(anyString(),anyMap());
  var line=add(tea,medium,1);var form=form(line.getId());form.setPaymentMethod(PaymentMethod.MOMO);
  assertThatThrownBy(()->payments.checkout(customer,form,"127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
 }
}
