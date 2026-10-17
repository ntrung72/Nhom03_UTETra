package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.DomainEnums.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VoucherCheckoutTests extends CheckoutTestFixtures {
    @Test
    void cancelledCheckoutReleasesUsageAndAllowsVoucherOnNextPurchase() {
        var v = voucher(VoucherType.FIXED, "5000"); v.setMaxUsesPerUser(1); vouchers.saveAndFlush(v);
        var firstForm = form(add(tea, medium, 1).getId()); firstForm.setVoucherCode(v.getCode());
        var first = orders.checkout(customer, firstForm).getFirst();
        assertThat(orders.availableVouchers(customer)).extracting(x -> x.getId()).doesNotContain(v.getId());
        orders.cancelByUser(customer, first.getId());
        var nextForm = form(add(tea, medium, 1).getId()); nextForm.setVoucherCode(v.getCode());
        var next = orders.checkout(customer, nextForm).getFirst();
        assertThat(next.getVoucherDiscount()).isEqualByComparingTo("5000");
        assertThat(v.getQuantity()).isEqualTo(2);
        assertThat(usages.countByUserIdAndVoucherIdAndStatus(customer.getId(), v.getId(), VoucherUsageStatus.USED)).isEqualTo(1);
        assertThat(usages.countByUserIdAndVoucherIdAndStatus(customer.getId(), v.getId(), VoucherUsageStatus.CANCELLED)).isEqualTo(1);
    }

    @Test
    void missingCsrfOnCancellationCannotReleaseVoucherOrStock() throws Exception {
        var v = voucher(VoucherType.FIXED, "5000");
        var f = form(add(tea, medium, 1).getId()); f.setVoucherCode(v.getCode());
        var order = orders.checkout(customer, f).getFirst();
        mvc.perform(post("/orders/" + order.getId() + "/cancel").with(user(customer.getUsername())))
            .andExpect(status().isForbidden());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(tea.getStock()).isEqualTo(9); assertThat(v.getQuantity()).isEqualTo(2);
        assertThat(usages.countByUserIdAndVoucherIdAndStatus(customer.getId(), v.getId(), VoucherUsageStatus.USED)).isEqualTo(1);
    }
}
