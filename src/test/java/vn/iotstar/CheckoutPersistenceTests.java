package vn.iotstar;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import vn.iotstar.repository.CartRepository;
import vn.iotstar.entity.DomainEnums.VoucherType;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest
@ActiveProfiles("test")
class CheckoutPersistenceTests extends CheckoutTestFixtures {
    @Autowired TransactionTemplate transactions;
    @Autowired CartRepository cartRepository;
    Long voucherId;

    @Test
    void requestsCommitExactlyOnceAndInvalidVoucherRollsBackAllDatabaseChanges() throws Exception {
        var line = add(tea, medium, 2); var kept = add(tea, large, 1);
        var v = voucher(VoucherType.FIXED, "5000"); voucherId = v.getId();
        mvc.perform(post("/checkout").with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("itemIds", line.getId().toString()).param("addressId", address.getId().toString())
            .param("carrierId", carrier.getId().toString()).param("voucherCode", "UNKNOWN-VOUCHER"))
            .andExpect(status().is3xxRedirection());
        assertThat(products.findById(tea.getId()).orElseThrow().getStock()).isEqualTo(10);
        assertThat(orderRepository.count()).isZero(); assertThat(cartItems.existsById(line.getId())).isTrue();
        mvc.perform(post("/checkout").with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("itemIds", line.getId().toString()).param("addressId", address.getId().toString())
            .param("carrierId", carrier.getId().toString()).param("voucherCode", v.getCode()))
            .andExpect(redirectedUrl("/cart"));
        assertThat(products.findById(tea.getId()).orElseThrow().getStock()).isEqualTo(8);
        assertThat(vouchers.findById(voucherId).orElseThrow().getQuantity()).isEqualTo(2);
        assertThat(cartItems.existsById(line.getId())).isFalse(); assertThat(cartItems.existsById(kept.getId())).isTrue();
        assertThat(orderRepository.count()).isEqualTo(1); assertThat(orderItems.count()).isEqualTo(1);
        assertThat(histories.count()).isEqualTo(1); assertThat(usages.countByVoucherId(voucherId)).isEqualTo(1);
        mvc.perform(post("/checkout").with(user(customer.getUsername()).roles("USER")).with(csrf())
            .param("itemIds", line.getId().toString()).param("addressId", address.getId().toString())
            .param("carrierId", carrier.getId().toString())).andExpect(status().isNotFound());
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @AfterEach
    void cleanup() {
        transactions.executeWithoutResult(tx -> {
            if (voucherId != null) { usages.deleteAll(usages.findAll().stream().filter(u -> u.getVoucher().getId().equals(voucherId)).toList()); }
            var owned = orderRepository.findAll().stream().filter(o -> o.getUser().getId().equals(customer.getId())).toList();
            for (var o : owned) histories.deleteAll(histories.findByOrderIdOrderByChangedAtAscIdAsc(o.getId()));
            orderRepository.deleteAll(owned); orderRepository.flush();
            cartRepository.findByUserId(customer.getId()).ifPresent(cartRepository::delete); cartRepository.flush();
            addresses.deleteById(address.getId()); carriers.deleteById(carrier.getId());
            if (voucherId != null) vouchers.deleteById(voucherId);
            sizes.deleteById(medium.getId()); sizes.deleteById(large.getId()); sizes.flush();
            var shopId = tea.getShop().getId(); var categoryId = tea.getCategory().getId();
            products.deleteById(tea.getId()); products.flush(); shops.deleteById(shopId); categories.deleteById(categoryId);
            users.deleteById(customer.getId()); users.deleteById(other.getId());
        });
    }
}
