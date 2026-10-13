package vn.iotstar;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.service.OrderService;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CheckoutTests extends CheckoutTestFixtures {
    @Test
    void codSnapshotsAddressOptionsRateAndOnlyRemovesPurchasedLines() {
        tea.getShop().setCommissionRate(new BigDecimal("12.50")); shops.saveAndFlush(tea.getShop());
        var bought = add(tea, medium, 2); var remaining = add(tea, large, 1);
        var f = form(bought.getId(), bought.getId()); f.setNote("  Ít ngọt  ");
        var created = orders.checkout(customer, f); assertThat(created).hasSize(1);
        var order = created.getFirst();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.COD);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        assertThat(order.getCommissionRate()).isEqualByComparingTo("12.50");
        assertThat(order.getReceiverName()).isEqualTo("Tân"); assertThat(order.getNote()).isEqualTo("Ít ngọt");
        assertThat(order.getShippingAddress()).isEqualTo(address.getFullAddress());
        assertThat(order.getItems().getFirst().getSize()).isEqualTo("M");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("90000");
        assertThat(tea.getStock()).isEqualTo(8);
        assertThat(carts.get(customer).getItems()).containsExactly(remaining);
        assertThat(histories.findByOrderIdOrderByChangedAtAscIdAsc(order.getId())).singleElement()
            .satisfies(h -> { assertThat(h.getOldStatus()).isNull(); assertThat(h.getNewStatus()).isEqualTo(OrderStatus.NEW); });
    }

    @Test
    void splitsByShopAndAllocatesOneFixedVoucherAcrossTheWholeCheckout() {
        var second = secondShopProduct(); var secondSize = size(second, "M", "0");
        var a = add(tea, medium, 1); var b = add(second, secondSize, 1);
        var v = voucher(VoucherType.FIXED, "10000"); var f = form(a.getId(), b.getId()); f.setVoucherCode(v.getCode());
        var preview = orders.preview(customer, carrier.getId(), v.getCode(), f.getItemIds());
        assertThat(preview.totalAmount()).isEqualByComparingTo("80000"); assertThat(preview.shopCount()).isEqualTo(2);
        var created = orders.checkout(customer, f); assertThat(created).hasSize(2);
        assertThat(created.stream().map(o -> o.getCheckoutReference()).distinct()).hasSize(1);
        assertThat(created.stream().map(o -> o.getVoucherDiscount()).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("10000");
        assertThat(created.stream().map(o -> o.getTotalAmount()).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(preview.totalAmount());
        assertThat(v.getQuantity()).isEqualTo(2); assertThat(usages.countByVoucherId(v.getId())).isEqualTo(1);
        assertThat(carts.get(customer).getItems()).isEmpty();
    }

    @Test
    void productPromotionIsAppliedOnceAndVoucherUsesNetMerchandise() {
        promotion(PromotionType.PRODUCT_PERCENT, "20");
        var line = add(tea, large, 2); var v = voucher(VoucherType.PERCENT, "10");
        var f = form(line.getId()); f.setVoucherCode(v.getCode());
        var preview = orders.preview(customer, carrier.getId(), v.getCode(), f.getItemIds());
        assertThat(preview.subtotal()).isEqualByComparingTo("84000");
        assertThat(preview.productDiscount()).isEqualByComparingTo("14000");
        assertThat(preview.voucherDiscount()).isEqualByComparingTo("7000");
        assertThat(preview.totalAmount()).isEqualByComparingTo("83000");
        assertThat(orders.checkout(customer, f).getFirst().getTotalAmount()).isEqualByComparingTo("83000");
    }

    @Test
    void shippingVoucherUsesShippingAfterShopPromotionAndDoesNotDiscountMerchandise() {
        promotion(PromotionType.SHIPPING_DISCOUNT, "5000");
        var line = add(tea, medium, 1); var v = voucher(VoucherType.SHIPPING, "12000");
        var f = form(line.getId()); f.setVoucherCode(v.getCode());
        var order = orders.checkout(customer, f).getFirst();
        assertThat(order.getVoucherDiscount()).isEqualByComparingTo("0");
        assertThat(order.getShippingDiscount()).isEqualByComparingTo("17000");
        assertThat(order.getFinalShippingFee()).isEqualByComparingTo("3000");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("38000");
    }

    @Test
    void largeFixedVoucherIsCappedAndCentRemainderIsAllocatedExactly() {
        var second = secondShopProduct(); var b = add(second, size(second, "M", "0"), 1); var a = add(tea, medium, 1);
        var v = voucher(VoucherType.FIXED, "0.01"); var f = form(a.getId(), b.getId()); f.setVoucherCode(v.getCode());
        var created = orders.checkout(customer, f);
        assertThat(created.stream().map(o -> o.getVoucherDiscount()).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0.01");
        var line = add(tea, medium, 1); var capped = voucher(VoucherType.FIXED, "999999");
        var f2 = form(line.getId()); f2.setVoucherCode(capped.getCode());
        var order = orders.checkout(customer, f2).getFirst();
        assertThat(order.getVoucherDiscount()).isEqualByComparingTo("35000");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("20000");
    }

    @Test
    void selectedStockIsAggregatedAcrossVariantsButUnselectedItemsDoNotBlock() {
        var a = add(tea, medium, 3); var b = add(tea, large, 3);
        tea.setStock(4); products.saveAndFlush(tea);
        assertThatThrownBy(() -> orders.checkout(customer, form(a.getId(), b.getId())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(orderRepository.count()).isZero(); assertThat(tea.getStock()).isEqualTo(4);
    }

    @Test
    void partialCheckoutStillWorksWhenOnlyTheUnselectedVariantExceedsStock() {
        var a = add(tea, medium, 3); add(tea, large, 3);
        tea.setStock(4); products.saveAndFlush(tea);
        assertThat(orders.checkout(customer, form(a.getId()))).hasSize(1);
        assertThat(tea.getStock()).isEqualTo(1); assertThat(carts.get(customer).getItems()).hasSize(1);
    }

    @Test
    void rejectsForeignAddressCartItemInactiveCarrierAndRetiredSize() {
        var line = add(tea, medium, 1); var f = form(line.getId());
        f.setAddressId(999999L); assertThatThrownBy(() -> orders.checkout(customer, f)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orders.checkout(other, form(line.getId())))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void rejectsInactiveCarrier() {
        var line = add(tea, medium, 1); carrier.setActive(false); carriers.saveAndFlush(carrier);
        assertThatThrownBy(() -> orders.checkout(customer, form(line.getId()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void changedPriceRequiresConfirmationBeforeStockOrVoucherIsConsumed() {
        var line = add(tea, medium, 1); tea.setPrice(new BigDecimal("40000")); products.saveAndFlush(tea);
        assertThatThrownBy(() -> orders.checkout(customer, form(line.getId()))).isInstanceOf(OrderService.PriceChangedException.class);
        assertThat(tea.getStock()).isEqualTo(10); assertThat(orderRepository.count()).isZero();
        assertThat(line.getUnitPrice()).isEqualByComparingTo("40000");
    }

    @Test
    void emptySelectionNeverFallsBackToBuyingTheWholeCart() {
        add(tea, medium, 1);
        assertThatThrownBy(() -> orders.checkout(customer, form())).isInstanceOf(IllegalArgumentException.class);
        assertThat(orderRepository.count()).isZero(); assertThat(carts.get(customer).getItems()).hasSize(1);
    }

    @Test
    void minimumVoucherValueIsCheckedAfterPromotion() {
        promotion(PromotionType.PRODUCT_PERCENT, "50"); var line = add(tea, medium, 1);
        var v = voucher(VoucherType.FIXED, "1000"); v.setMinimumOrder(new BigDecimal("30000")); vouchers.saveAndFlush(v);
        var f = form(line.getId()); f.setVoucherCode(v.getCode());
        assertThatThrownBy(() -> orders.checkout(customer, f)).isInstanceOf(IllegalArgumentException.class);
        assertThat(v.getQuantity()).isEqualTo(3); assertThat(tea.getStock()).isEqualTo(10);
    }

    @Test
    void checkoutPageAndPostUseFreshTotalsIgnoreClientPriceAndRequireCsrf() throws Exception {
        var line = add(tea, medium, 1);
        mvc.perform(get("/checkout").with(user(customer.getUsername()).roles("USER")).param("itemIds", line.getId().toString()))
            .andExpect(status().isOk()).andExpect(view().name("cart/checkout"));
        mvc.perform(post("/checkout").with(user(customer.getUsername()).roles("USER"))) .andExpect(status().isForbidden());
        mvc.perform(post("/checkout").with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("addressId", address.getId().toString()).param("carrierId", carrier.getId().toString())
            .param("itemIds", line.getId().toString()).param("totalAmount", "1").param("unitPrice", "0"))
            .andExpect(redirectedUrl("/cart"));
        assertThat(orderRepository.findAll().getFirst().getTotalAmount()).isEqualByComparingTo("55000");
    }

    @Test
    void checkoutRejectsUnsupportedPaymentAndOversizedNote() throws Exception {
        var line = add(tea, medium, 1);
        mvc.perform(post("/checkout").with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("addressId", address.getId().toString()).param("carrierId", carrier.getId().toString())
            .param("itemIds", line.getId().toString()).param("paymentMethod", "VNPAY"))
            .andExpect(status().is3xxRedirection());
        var f = form(line.getId()); f.setNote("x".repeat(501));
        assertThatThrownBy(() -> orders.checkout(customer, f)).isInstanceOf(IllegalArgumentException.class);
        assertThat(orderRepository.count()).isZero();
    }
}
