package vn.iotstar;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.DomainEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
@SpringBootTest(properties={"app.vnpay.tmn-code=TESTCODE","app.vnpay.hash-secret=test-only-vnpay-hmac-key"})
@ActiveProfiles("test") @Transactional
class VnPayTests extends VnPayTestFixtures {
    @Test void signatureMatchesIndependentPythonHmacVector() {
        assertThat(vnPay.hasValidSignature(Map.of("vnp_Amount","1000000","vnp_OrderInfo","Thanh toan UTETra",
            "vnp_TmnCode","TESTCODE","vnp_TxnRef","UTTEST","vnp_SecureHash","3b5aa577ad9487d8a46c50feb5fccf2cb47b7806d4136082e60737657db9fd790105045e0c4fcf8c68e0bff64f1967051dfaa50c5b0ba905dfe03c38e0b0d2eb"))).isTrue();
    }
    @Test void urlUsesOfficialHostSignedAmountAndCheckoutSnapshots() {
        var line=add(tea,medium,1); var checkout=online(line.getId()); var p=query(checkout.paymentUrl()); var o=checkout.orders().getFirst();
        assertThat(checkout.paymentUrl()).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        assertThat(p).containsEntry("vnp_Amount","5500000").containsEntry("vnp_CurrCode","VND");
        assertThat(vnPay.hasValidSignature(p)).isTrue(); assertThat(p.get("vnp_SecureHash")).hasSize(128);
        assertThat(o.getPaymentMethod()).isEqualTo(PaymentMethod.VNPAY); assertThat(o.getPaymentExpiresAt()).isNotNull();
        assertThat(o.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID); assertThat(tea.getStock()).isEqualTo(9);
    }
    @Test void liveRetryReusesReferenceAndDoesNotExtendReservationDeadline() {
        var result=online(add(tea,medium,1).getId()); var o=result.orders().getFirst(); var deadline=o.getPaymentExpiresAt();
        var retry=vnPay.retryPaymentUrl(customer,o.getId(),"10.0.0.1");
        assertThat(retry).isEqualTo(result.paymentUrl()); assertThat(paymentRows.count()).isEqualTo(1);
        assertThat(o.getPaymentExpiresAt()).isEqualTo(deadline);
    }
    @Test void validIpnMarksPaidOnceWithoutChangingOrderDeliveryStateOrSoldCount() {
        var result=online(add(tea,medium,1).getId()); var o=result.orders().getFirst(); var cb=callback(result.paymentUrl(),true);
        assertThat(vnPay.processCallback(cb).responseCode()).isEqualTo("00");
        assertThat(vnPay.processCallback(cb).responseCode()).isEqualTo("02");
        assertThat(o.getPaymentStatus()).isEqualTo(PaymentStatus.PAID); assertThat(o.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(tea.getStock()).isEqualTo(9); assertThat(tea.getSoldCount()).isZero();
        var t=paymentRows.findFirstByOrderIdOrderByCreatedAtDescIdDesc(o.getId()).orElseThrow();
        assertThat(t.getStatus()).isEqualTo(PaymentTransactionStatus.SUCCESS); assertThat(t.getProviderTransactionNo()).isEqualTo("123456789");
    }
    @Test void invalidSignatureWrongAmountAndUnknownReferenceDoNotSettle() {
        var result=online(add(tea,medium,1).getId()); var o=result.orders().getFirst(); var cb=callback(result.paymentUrl(),true);
        cb.put("vnp_Amount","1"); assertThat(vnPay.processCallback(cb).responseCode()).isEqualTo("97");
        assertThat(vnPay.processCallback(signed(cb)).responseCode()).isEqualTo("04");
        cb.put("vnp_TxnRef","UNKNOWN"); assertThat(vnPay.processCallback(signed(cb)).responseCode()).isEqualTo("01");
        assertThat(o.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
    }
    @Test void failedAttemptCanRetryButOldCallbackCannotResurrectIt() {
        var result=online(add(tea,medium,1).getId()); var o=result.orders().getFirst();
        assertThat(vnPay.processCallback(callback(result.paymentUrl(),false)).responseCode()).isEqualTo("00");
        var retry=vnPay.retryPaymentUrl(customer,o.getId(),"127.0.0.1");
        assertThat(query(retry).get("vnp_TxnRef")).isNotEqualTo(query(result.paymentUrl()).get("vnp_TxnRef"));
        assertThat(vnPay.processCallback(callback(result.paymentUrl(),true)).responseCode()).isEqualTo("02");
        assertThat(o.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        assertThat(vnPay.processCallback(callback(retry,true)).success()).isTrue(); assertThat(o.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }
    @Test void multiShopPaymentUsesOneReferenceAndSettlesAllOrdersAtomically() {
        var second=secondShopProduct(); var a=add(tea,medium,1); var b=add(second,size(second,"M","0"),1);
        var result=online(a.getId(),b.getId()); assertThat(result.orders()).hasSize(2);
        assertThat(query(result.paymentUrl()).get("vnp_Amount")).isEqualTo("9000000");
        assertThat(paymentRows.findAll()).extracting(t->t.getTransactionCode()).containsOnly(query(result.paymentUrl()).get("vnp_TxnRef"));
        vnPay.processCallback(callback(result.paymentUrl(),true));
        assertThat(result.orders()).allSatisfy(o->assertThat(o.getPaymentStatus()).isEqualTo(PaymentStatus.PAID));
    }
    @Test void expiryRestoresStockVoucherAndDoesNotAcceptLateSuccess() {
        var v=voucher(VoucherType.FIXED,"5000"); var f=form(add(tea,medium,2).getId()); f.setPaymentMethod(PaymentMethod.VNPAY); f.setVoucherCode(v.getCode());
        var result=payments.checkout(customer,f,"127.0.0.1"); var o=result.orders().getFirst();
        var past=LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusMinutes(1);
        o.setPaymentExpiresAt(past); orderRepository.saveAndFlush(o);
        paymentRows.findAll().forEach(t->t.setExpiresAt(past)); paymentRows.flush();
        assertThat(expiration.expirePendingPayments()).isEqualTo(1); assertThat(expiration.expirePendingPayments()).isZero();
        assertThat(tea.getStock()).isEqualTo(10); assertThat(v.getQuantity()).isEqualTo(3);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED); assertThat(vnPay.processCallback(callback(result.paymentUrl(),true)).responseCode()).isEqualTo("02");
        assertThat(o.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        assertThat(paymentRows.findAll().getFirst().getStatus()).isEqualTo(PaymentTransactionStatus.EXPIRED);
        assertThat(vnPay.needsReconciliation(customer,o.getId())).isTrue();
    }
    @Test void cancellationCancelsAllShopOrdersAndReturnsOneVoucherUse() {
        var second=secondShopProduct(); var a=add(tea,medium,1); var b=add(second,size(second,"M","0"),1);
        var v=voucher(VoucherType.FIXED,"5000"); var f=form(a.getId(),b.getId()); f.setPaymentMethod(PaymentMethod.VNPAY); f.setVoucherCode(v.getCode());
        var result=payments.checkout(customer,f,"127.0.0.1"); payments.cancel(customer,result.orders().getLast().getId());
        assertThat(result.orders()).allSatisfy(o->assertThat(o.getStatus()).isEqualTo(OrderStatus.CANCELLED));
        assertThat(paymentRows.findAll()).allSatisfy(t->assertThat(t.getStatus()).isEqualTo(PaymentTransactionStatus.CANCELLED));
        assertThat(v.getQuantity()).isEqualTo(3); assertThat(tea.getStock()).isEqualTo(10); assertThat(second.getStock()).isEqualTo(20);
    }
    @Test void onlyOwnerCanRetryOrCancelAndPaidOrdersCannotRetryOrCancel() {
        var result=online(add(tea,medium,1).getId()); var id=result.orders().getFirst().getId();
        assertThatThrownBy(()->vnPay.retryPaymentUrl(other,id,"127.0.0.1")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->payments.cancel(other,id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void paidOrderCannotBeCancelledAndUnpaidOnlineOrderCannotBeProcessedByVendor() {
        other.setRole(Role.VENDOR); users.saveAndFlush(other);
        var result=online(add(tea,medium,1).getId()); var id=result.orders().getFirst().getId();
        assertThatThrownBy(()->orders.vendorUpdate(other,id,OrderStatus.CONFIRMED)).isInstanceOf(IllegalArgumentException.class);
        vnPay.processCallback(callback(result.paymentUrl(),true));
        assertThatThrownBy(()->payments.cancel(customer,id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->vnPay.retryPaymentUrl(customer,id,"127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void returnIsReadOnlyAndPublicIpnRejectsDuplicateParameters() throws Exception {
        var result=online(add(tea,medium,1).getId()); var o=result.orders().getFirst(); var cb=callback(result.paymentUrl(),true);
        var ret=get("/payments/vnpay/return"); cb.forEach(ret::param); mvc.perform(ret).andExpect(redirectedUrl("/orders"));
        assertThat(o.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        var failedReturn=get("/payments/vnpay/return"); callback(result.paymentUrl(),false).forEach(failedReturn::param);
        mvc.perform(failedReturn).andExpect(flash().attributeExists("error"));
        assertThat(o.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        var duplicate=get("/payments/vnpay/ipn"); cb.forEach(duplicate::param); duplicate.param("vnp_Amount","1");
        mvc.perform(duplicate).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("97"));
        var ipn=get("/payments/vnpay/ipn"); cb.forEach(ipn::param);
        mvc.perform(ipn).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("00"));
        assertThat(o.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }
    @Test void retryEndpointRequiresCsrfAndMissingConfigurationKeepsCodAvailable() throws Exception {
        var result=online(add(tea,medium,1).getId());
        mvc.perform(post("/orders/"+result.orders().getFirst().getId()+"/payment/retry").with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isForbidden());
        var config=new vn.iotstar.config.VnPayProperties(); assertThat(config.isConfigured()).isFalse();
        config.setTmnCode("TESTCODE"); config.setHashSecret("test-only"); assertThat(config.isConfigured()).isTrue();
        config.setPaymentUrl("https://evil.invalid/pay"); assertThat(config.isConfigured()).isFalse();
    }
}
