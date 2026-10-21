package vn.iotstar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import vn.iotstar.service.LoyaltyPointService;
import vn.iotstar.entity.DomainEnums.*;
import java.util.concurrent.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:loyalty-race;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class LoyaltyConcurrencyTests extends CheckoutTestFixtures {
 @Autowired LoyaltyPointService points;
 @Test void concurrentDeliveryEventsAwardOnlyOnce() throws Exception {
  var line=add(tea,medium,2);var order=orders.checkout(customer,form(line.getId())).getFirst();
  order.setStatus(OrderStatus.DELIVERED);order.setPaymentStatus(PaymentStatus.PAID);orderRepository.saveAndFlush(order);
  try(var pool=Executors.newFixedThreadPool(2)){
   var start=new CountDownLatch(1);
   var jobs=List.of(pool.submit(()->{start.await();points.awardForDeliveredOrder(order);return true;}),pool.submit(()->{start.await();points.awardForDeliveredOrder(order);return true;}));
   start.countDown();for(var job:jobs)assertThat(job.get(20,TimeUnit.SECONDS)).isTrue();
  }
  assertThat(points.balance(customer)).isEqualTo(7);assertThat(points.history(customer,0)).hasSize(1);
 }
}
