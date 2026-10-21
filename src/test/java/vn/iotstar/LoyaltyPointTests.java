package vn.iotstar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.service.*;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.dto.WebForms.ReturnRequestForm;
import vn.iotstar.support.MediaTestFiles;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
@SpringBootTest @ActiveProfiles("test") @Transactional
class LoyaltyPointTests extends CheckoutTestFixtures {
 @Autowired LoyaltyPointService points;
 @Autowired ReturnRequestService returns;
 CustomerOrder placed(){var line=add(tea,medium,4);return orders.checkout(customer,form(line.getId())).getFirst();}
 @Test void requiresDeliveredAndPaidAndExcludesShippingAndRoundsDown(){
  var order=placed();points.awardForDeliveredOrder(order);assertThat(points.balance(customer)).isZero();
  order.setStatus(OrderStatus.DELIVERED);points.awardForDeliveredOrder(order);assertThat(points.balance(customer)).isZero();
  order.setPaymentStatus(PaymentStatus.PAID);order.setCommissionBase(new java.math.BigDecimal("125999"));
  points.awardForDeliveredOrder(order);points.awardForDeliveredOrder(order);assertThat(points.balance(customer)).isEqualTo(12);
  assertThat(points.history(customer,0)).hasSize(1);assertThat(points.history(other,0)).isEmpty();
 }
 @Test void realDeliveryHookAwardsAndLoyaltyPageOnlyShowsOwnerData() throws Exception {
  var order=placed();var shipper=createUser();shipper.setRole(Role.SHIPPER);users.saveAndFlush(shipper);
  order.setShipper(shipper);order.setStatus(OrderStatus.SHIPPING);orderRepository.saveAndFlush(order);
  orders.shipperUpdate(shipper,order.getId(),OrderStatus.DELIVERED,null,MediaTestFiles.image("proof.png"));
  assertThat(points.balance(customer)).isEqualTo(14);assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
  mvc.perform(get("/user/loyalty").with(user(customer.getUsername()).roles("USER"))).andExpect(status().isOk());
  assertThat(points.balance(other)).isZero();
 }
 @Test void refundWorkflowReversesExactlyWhatWasEarned(){
  var order=placed();order.setStatus(OrderStatus.DELIVERED);order.setPaymentStatus(PaymentStatus.PAID);orderRepository.saveAndFlush(order);
  points.awardForDeliveredOrder(order);assertThat(points.balance(customer)).isEqualTo(14);
  other.setRole(Role.VENDOR);users.saveAndFlush(other);
  var form=new ReturnRequestForm();form.setReason(ReturnReason.values()[0]);form.setDescription("Sản phẩm không đúng với yêu cầu trong đơn hàng");form.setRefundAmount(order.getTotalAmount());
  var request=returns.create(customer,order.getId(),form,new org.springframework.web.multipart.MultipartFile[]{MediaTestFiles.image("evidence.png")});
  returns.decide(other,request.getId(),ReturnStatus.APPROVED,order.getTotalAmount(),"Đồng ý hoàn toàn bộ đơn hàng");
  returns.markReturning(customer,order.getId());returns.vendorTransition(other,request.getId(),ReturnStatus.RECEIVED,"Đã nhận đủ hàng trả");
  returns.vendorTransition(other,request.getId(),ReturnStatus.REFUND_PENDING,"Đang đối chiếu thông tin chuyển tiền");
  returns.confirmRefund(other,request.getId(),"BANK-TEST-001","Đã chuyển khoản hoàn toàn bộ tiền");
  assertThat(points.balance(customer)).isZero();points.reverseForRefundedOrder(order);assertThat(points.history(customer,0)).hasSize(2);
 }
}
