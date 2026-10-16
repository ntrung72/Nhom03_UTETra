package vn.iotstar;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"app.vnpay.tmn-code=TESTCODE","app.vnpay.hash-secret=test-only-vnpay-hmac-key"})
@ActiveProfiles("test")
class VnPayPersistenceTests extends VnPayTestFixtures {
    @Autowired TransactionTemplate tx;
    @Autowired CartRepository cartRepository;
    @Autowired InventoryTransactionRepository inventory;
    @Autowired NotificationRepository notifications;
    Long voucherId;
    @Test void concurrentIpnsSettleOnlyOnce() throws Exception {
        var result=online(add(tea,medium,1).getId()); var callback=callback(result.paymentUrl(),true);
        var replies=race(()->vnPay.processCallback(callback).responseCode(),()->vnPay.processCallback(callback).responseCode());
        assertThat(replies).containsExactlyInAnyOrder("00","02");
        assertThat(orderRepository.findById(result.orders().getFirst().getId()).orElseThrow().getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(products.findById(tea.getId()).orElseThrow().getStock()).isEqualTo(9);
        assertThat(paymentRows.findAll()).singleElement().satisfies(t->assertThat(t.getStatus()).isEqualTo(PaymentTransactionStatus.SUCCESS));
    }
    @Test void cancellationAndIpnCannotProducePaidCancelledOrderOrDuplicateStock() throws Exception {
        var v=voucher(VoucherType.FIXED,"5000"); voucherId=v.getId();
        var f=form(add(tea,medium,1).getId()); f.setPaymentMethod(PaymentMethod.VNPAY); f.setVoucherCode(v.getCode());
        var result=payments.checkout(customer,f,"127.0.0.1"); var id=result.orders().getFirst().getId(); var cb=callback(result.paymentUrl(),true);
        race(()->vnPay.processCallback(cb).responseCode(),()->{
            try { payments.cancel(customer,id); return "cancelled"; } catch(IllegalArgumentException ex) { return "paid"; }
        });
        var saved=orderRepository.findById(id).orElseThrow();
        if(saved.getStatus()==OrderStatus.CANCELLED) {
            assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
            assertThat(products.findById(tea.getId()).orElseThrow().getStock()).isEqualTo(10);
            assertThat(vouchers.findById(voucherId).orElseThrow().getQuantity()).isEqualTo(3);
        } else {
            assertThat(saved.getStatus()).isEqualTo(OrderStatus.NEW); assertThat(saved.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(products.findById(tea.getId()).orElseThrow().getStock()).isEqualTo(9);
            assertThat(vouchers.findById(voucherId).orElseThrow().getQuantity()).isEqualTo(2);
        }
    }
    @Test void invalidOnlineAmountRollsBackOrdersStockVoucherAndSelectedCartDeletion() {
        tx.executeWithoutResult(s->{ carrier.setShippingFee(java.math.BigDecimal.ZERO); carriers.saveAndFlush(carrier); promotion(PromotionType.PRODUCT_PERCENT,"100"); });
        var line=add(tea,medium,1);
        assertThatThrownBy(()->online(line.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(orderRepository.count()).isZero(); assertThat(paymentRows.count()).isZero();
        assertThat(products.findById(tea.getId()).orElseThrow().getStock()).isEqualTo(10);
        assertThat(cartItems.existsById(line.getId())).isTrue();
    }
    List<String> race(Callable<String> a, Callable<String> b) throws Exception {
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<String> one=()->{ready.countDown();start.await();return a.call();};
            Callable<String> two=()->{ready.countDown();start.await();return b.call();};
            var fa=pool.submit(one);var fb=pool.submit(two); assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            return List.of(fa.get(25,TimeUnit.SECONDS),fb.get(25,TimeUnit.SECONDS));
        } finally { start.countDown();pool.shutdownNow(); }
    }
    @AfterEach void cleanup() {
        tx.executeWithoutResult(s->{
            var owned=orderRepository.findAll().stream().filter(o->o.getUser().getId().equals(customer.getId())).toList();
            paymentRows.deleteAll(paymentRows.findAll().stream().filter(t->t.getOrder().getUser().getId().equals(customer.getId())).toList());paymentRows.flush();
            notifications.deleteAll(notifications.findAll().stream().filter(n->n.getUser().getId().equals(customer.getId())||n.getUser().getId().equals(other.getId())).toList());notifications.flush();
            inventory.deleteAll(inventory.findByProductIdOrderByCreatedAtAscIdAsc(tea.getId()));inventory.flush();
            if(voucherId!=null) usages.deleteAll(usages.findAll().stream().filter(u->u.getVoucher().getId().equals(voucherId)).toList());usages.flush();
            for(var o:owned) histories.deleteAll(histories.findByOrderIdOrderByChangedAtAscIdAsc(o.getId()));histories.flush();
            orderRepository.deleteAll(owned);orderRepository.flush();
            cartRepository.findByUserId(customer.getId()).ifPresent(cartRepository::delete);cartRepository.flush();
            addresses.deleteById(address.getId());carriers.deleteById(carrier.getId());
            if(voucherId!=null)vouchers.deleteById(voucherId);
            promotions.deleteAll(promotions.findByShopIdOrderByStartsAtDesc(tea.getShop().getId()));promotions.flush();
            sizes.deleteById(medium.getId());sizes.deleteById(large.getId());sizes.flush();
            products.deleteById(tea.getId());products.flush();shops.deleteById(tea.getShop().getId());shops.flush();
            categories.deleteById(tea.getCategory().getId());users.deleteById(customer.getId());users.deleteById(other.getId());
        });
    }
}
