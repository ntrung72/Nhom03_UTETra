package vn.iotstar;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.DomainEnums.*;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @ActiveProfiles("test") @Transactional
class VoucherRecommendationTests extends CheckoutTestFixtures {
 @Test void ranksActualSavingsAcrossPercentFixedAndShippingAndRendersCheckout() throws Exception {
  var line=add(tea,medium,2);var percent=voucher(VoucherType.PERCENT,"10");var fixed=voucher(VoucherType.FIXED,"10000");var ship=voucher(VoucherType.SHIPPING,"20000");
  var result=orders.recommendVouchers(customer,carrier.getId(),List.of(line.getId()));
  assertThat(result).extracting(r->r.code()).containsExactly(ship.getCode(),fixed.getCode(),percent.getCode());
  assertThat(result.getFirst().saving()).isEqualByComparingTo("20000");
  assertThat(result.getFirst().totalAmount()).isEqualByComparingTo(orders.preview(customer,carrier.getId(),ship.getCode(),List.of(line.getId())).totalAmount());
  mvc.perform(get("/checkout").param("itemIds",line.getId().toString()).with(user(customer.getUsername()).roles("USER")))
   .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Tiết kiệm nhất")));
 }
 @Test void recommendationDoesNotConsumeQuotaAndMinimumUsesPriceAfterPromotion(){
  promotion(PromotionType.PRODUCT_PERCENT,"50");var line=add(tea,medium,1);var v=voucher(VoucherType.FIXED,"5000");v.setMinimumOrder(new BigDecimal("20000"));vouchers.saveAndFlush(v);
  var result=orders.recommendVouchers(customer,carrier.getId(),List.of(line.getId()));
  assertThat(result).singleElement().satisfies(r->assertThat(r.eligible()).isFalse());assertThat(v.getQuantity()).isEqualTo(3);assertThat(usages.count()).isZero();
 }
 @Test void changedStockAndExpiredVoucherAreRecheckedAtCheckout(){
  var line=add(tea,medium,1);var v=voucher(VoucherType.FIXED,"5000");orders.recommendVouchers(customer,carrier.getId(),List.of(line.getId()));
  v.setActive(false);vouchers.saveAndFlush(v);var form=form(line.getId());form.setVoucherCode(v.getCode());
  assertThatThrownBy(()->orders.checkout(customer,form)).isInstanceOf(IllegalArgumentException.class);
 }
}
