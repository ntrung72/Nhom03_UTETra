package vn.iotstar;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomerOrderTests extends CheckoutTestFixtures {
    CustomerOrder buy(int quantity) { return orders.checkout(customer, form(add(tea, medium, quantity).getId())).getFirst(); }

    @Test
    void ownHistoryFiltersStatusAndPagesFifteenOrdersWithStableOrdering() {
        for (int i = 0; i < 17; i++) {
            var order = new CustomerOrder(); order.setUser(customer); order.setShop(tea.getShop());
            order.setCode("HISTORY-" + i); order.setCheckoutReference("HISTORY-" + i);
            order.setReceiverName("Tân"); order.setReceiverPhone("0901234567"); order.setShippingAddress("TP HCM");
            order.setSubtotal(BigDecimal.ZERO); order.setProductDiscount(BigDecimal.ZERO); order.setVoucherDiscount(BigDecimal.ZERO);
            order.setShippingDiscount(BigDecimal.ZERO); order.setFinalShippingFee(BigDecimal.ZERO); order.setTotalAmount(BigDecimal.ZERO);
            order.setStatus(i < 2 ? OrderStatus.CANCELLED : OrderStatus.NEW); orderRepository.save(order);
        }
        orderRepository.flush();
        assertThat(orders.forUser(customer, null, 0).getContent()).hasSize(15);
        assertThat(orders.forUser(customer, null, 1).getContent()).hasSize(2);
        assertThat(orders.forUser(customer, OrderStatus.CANCELLED, 0).getContent()).hasSize(2);
        assertThat(orders.forUser(customer, null, -1).getNumber()).isZero();
        assertThat(orders.forUser(other, null, 0).getContent()).isEmpty();
    }

    @Test
    void shopOwnerIsNotAllowedToReadOrCancelAnotherCustomersOrderFromCustomerRoutes() throws Exception {
        var order = buy(1);
        assertThatThrownBy(() -> orders.getForUser(other, order.getId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        mvc.perform(get("/orders/" + order.getId()).with(user(other.getUsername()).roles("VENDOR"))) .andExpect(status().isNotFound());
        mvc.perform(post("/orders/" + order.getId() + "/cancel").with(user(other.getUsername()).roles("VENDOR")).with(csrf()))
            .andExpect(status().isNotFound());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW); assertThat(tea.getStock()).isEqualTo(9);
    }

    @Test
    void detailAndHistoryRenderSnapshotsAndRequireValidStatusFilter() throws Exception {
        var order = buy(1); tea.setName("Tên mới sau mua"); products.saveAndFlush(tea);
        mvc.perform(get("/orders").with(user(customer.getUsername()).roles("USER")).param("status", "NEW"))
            .andExpect(status().isOk()).andExpect(view().name("orders/list"));
        mvc.perform(get("/orders/" + order.getId()).with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andExpect(view().name("orders/detail"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Trà sữa test")));
        mvc.perform(get("/orders").with(user(customer.getUsername()).roles("USER")).param("status", "INVALID"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void cancellingNewOrderRestoresStockOnceAndAppendsHistory() {
        var order = buy(10); assertThat(tea.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);
        orders.cancelByUser(customer, order.getId());
        assertThat(tea.getStock()).isEqualTo(10); assertThat(tea.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orders.statusHistory(customer, order.getId())).extracting(h -> h.getNewStatus())
            .containsExactly(OrderStatus.NEW, OrderStatus.CANCELLED);
    }

    @Test
    void repeatedCancellationCannotRestoreStockTwice() {
        var order = buy(2); orders.cancelByUser(customer, order.getId());
        assertThatThrownBy(() -> orders.cancelByUser(customer, order.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(tea.getStock()).isEqualTo(10);
    }

    @Test
    void confirmsOnlyNewUnpaidOrdersCanBeCancelled() {
        var order = buy(1); order.setStatus(OrderStatus.CONFIRMED); orderRepository.saveAndFlush(order);
        assertThatThrownBy(() -> orders.cancelByUser(customer, order.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(tea.getStock()).isEqualTo(9); assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void paidNewOrderCannotBeCancelledByCustomer() {
        var order = buy(1); order.setPaymentStatus(PaymentStatus.PAID); orderRepository.saveAndFlush(order);
        assertThatThrownBy(() -> orders.cancelByUser(customer, order.getId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(tea.getStock()).isEqualTo(9);
    }

    @Test
    void cancellationPreservesHiddenProductStatus() {
        var order = buy(1); tea.setStatus(ProductStatus.HIDDEN); products.saveAndFlush(tea);
        orders.cancelByUser(customer, order.getId());
        assertThat(tea.getStatus()).isEqualTo(ProductStatus.HIDDEN); assertThat(tea.getStock()).isEqualTo(10);
    }

    @Test
    void restoresOneVoucherOnlyAfterAllShopOrdersInCheckoutAreCancelled() {
        var second = secondShopProduct(); var a = add(tea, medium, 1); var b = add(second, size(second, "M", "0"), 1);
        var v = voucher(VoucherType.FIXED, "5000"); var f = form(a.getId(), b.getId()); f.setVoucherCode(v.getCode());
        var created = orders.checkout(customer, f); assertThat(v.getQuantity()).isEqualTo(2);
        orders.cancelByUser(customer, created.getFirst().getId());
        assertThat(v.getQuantity()).isEqualTo(2);
        assertThat(usages.countByUserIdAndVoucherIdAndStatus(customer.getId(), v.getId(), VoucherUsageStatus.USED)).isEqualTo(1);
        orders.cancelByUser(customer, created.getLast().getId());
        assertThat(v.getQuantity()).isEqualTo(3);
        var usage = usages.findByCheckoutReferenceForUpdate(created.getFirst().getCheckoutReference()).orElseThrow();
        assertThat(usage.getStatus()).isEqualTo(VoucherUsageStatus.CANCELLED); assertThat(usage.getReleasedAt()).isNotNull();
        assertThat(orders.availableVouchers(customer)).extracting(x -> x.getId()).contains(v.getId());
        assertThat(tea.getStock()).isEqualTo(10); assertThat(second.getStock()).isEqualTo(20);
    }

    @Test
    void cancellationPostRequiresCsrfAndDisplaysUpdatedDetail() throws Exception {
        var order = buy(1);
        mvc.perform(post("/orders/" + order.getId() + "/cancel").with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isForbidden());
        mvc.perform(post("/orders/" + order.getId() + "/cancel").with(user(customer.getUsername()).roles("USER")).with(csrf()))
            .andExpect(redirectedUrl("/orders/" + order.getId()));
        mvc.perform(get("/orders/" + order.getId()).with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Đã hủy")));
    }
}
