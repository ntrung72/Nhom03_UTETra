package vn.iotstar;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import vn.iotstar.entity.Address;
import vn.iotstar.entity.DomainEnums.InventoryTransactionType;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.VoucherType;
import vn.iotstar.entity.DomainEnums.VoucherUsageStatus;
import vn.iotstar.repository.CartRepository;
import vn.iotstar.repository.InventoryTransactionRepository;
import vn.iotstar.repository.NotificationRepository;

@SpringBootTest
@ActiveProfiles("test")
class CheckoutPersistenceTests extends CheckoutTestFixtures {

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    CartRepository cartRepository;

    @Autowired
    InventoryTransactionRepository inventoryTransactions;

    @Autowired
    NotificationRepository notificationRepository;

    Long voucherId;
    Long extraAddressId;

    @Test
    void requestsCommitExactlyOnceAndInvalidVoucherRollsBackAllDatabaseChanges()
            throws Exception {

        var line = add(tea, medium, 2);
        var kept = add(tea, large, 1);
        var v = voucher(VoucherType.FIXED, "5000");
        voucherId = v.getId();

        mvc.perform(
                post("/checkout")
                    .with(user(customer.getUsername()).roles("USER"))
                    .with(csrf())
                    .param("itemIds", line.getId().toString())
                    .param("addressId", address.getId().toString())
                    .param("carrierId", carrier.getId().toString())
                    .param("voucherCode", "UNKNOWN-VOUCHER")
            )
            .andExpect(status().is3xxRedirection());

        assertThat(products.findById(tea.getId())
            .orElseThrow().getStock()).isEqualTo(10);
        assertThat(orderRepository.count()).isZero();
        assertThat(cartItems.existsById(line.getId())).isTrue();
        assertThat(inventoryTransactions
            .findByProductIdOrderByCreatedAtAscIdAsc(tea.getId()))
            .isEmpty();

        mvc.perform(
                post("/checkout")
                    .with(user(customer.getUsername()).roles("USER"))
                    .with(csrf())
                    .param("itemIds", line.getId().toString())
                    .param("addressId", address.getId().toString())
                    .param("carrierId", carrier.getId().toString())
                    .param("voucherCode", v.getCode())
            )
            .andExpect(redirectedUrl("/orders"));

        assertThat(products.findById(tea.getId())
            .orElseThrow().getStock()).isEqualTo(8);
        assertThat(vouchers.findById(voucherId)
            .orElseThrow().getQuantity()).isEqualTo(2);
        assertThat(cartItems.existsById(line.getId())).isFalse();
        assertThat(cartItems.existsById(kept.getId())).isTrue();
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(orderItems.count()).isEqualTo(1);
        assertThat(histories.count()).isEqualTo(1);
        assertThat(usages.countByVoucherId(voucherId)).isEqualTo(1);

        var saleHistory = inventoryTransactions
            .findByProductIdOrderByCreatedAtAscIdAsc(tea.getId());

        assertThat(saleHistory).hasSize(1);
        assertThat(saleHistory.getFirst().getType())
            .isEqualTo(InventoryTransactionType.SALE);
        assertThat(saleHistory.getFirst().getQuantityChange())
            .isEqualTo(-2);
        assertThat(saleHistory.getFirst().getStockBefore())
            .isEqualTo(10);
        assertThat(saleHistory.getFirst().getStockAfter())
            .isEqualTo(8);

        mvc.perform(
                post("/checkout")
                    .with(user(customer.getUsername()).roles("USER"))
                    .with(csrf())
                    .param("itemIds", line.getId().toString())
                    .param("addressId", address.getId().toString())
                    .param("carrierId", carrier.getId().toString())
            )
            .andExpect(status().isNotFound());

        assertThat(orderRepository.count()).isEqualTo(1);

        var orderId = orderRepository.findAll()
            .getFirst().getId();

        mvc.perform(
                post("/orders/" + orderId + "/cancel")
                    .with(user(customer.getUsername()).roles("USER"))
                    .with(csrf())
            )
            .andExpect(redirectedUrl("/orders/" + orderId));

        mvc.perform(
                post("/orders/" + orderId + "/cancel")
                    .with(user(customer.getUsername()).roles("USER"))
                    .with(csrf())
            )
            .andExpect(redirectedUrl("/orders/" + orderId));

        assertThat(products.findById(tea.getId())
            .orElseThrow().getStock()).isEqualTo(10);
        assertThat(vouchers.findById(voucherId)
            .orElseThrow().getQuantity()).isEqualTo(3);
        assertThat(histories.count()).isEqualTo(2);

        assertThat(usages.countByUserIdAndVoucherIdAndStatus(
            customer.getId(),
            voucherId,
            VoucherUsageStatus.CANCELLED
        )).isEqualTo(1);

        var movements = inventoryTransactions
            .findByProductIdOrderByCreatedAtAscIdAsc(tea.getId());

        assertThat(movements)
            .extracting(entry -> entry.getType())
            .containsExactly(
                InventoryTransactionType.SALE,
                InventoryTransactionType.CANCELLATION
            );

        assertThat(movements.getLast().getQuantityChange())
            .isEqualTo(2);
        assertThat(movements.getLast().getStockBefore())
            .isEqualTo(8);
        assertThat(movements.getLast().getStockAfter())
            .isEqualTo(10);
    }

    @Test
    void simultaneousCancellationRestoresStockAndVoucherExactlyOnce()
            throws Exception {

        var line = add(tea, medium, 10);
        var v = voucher(VoucherType.FIXED, "5000");
        voucherId = v.getId();

        var f = form(line.getId());
        f.setVoucherCode(v.getCode());

        var order = orders.checkout(customer, f).getFirst();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);

        try {
            Callable<Boolean> cancel = () -> {
                ready.countDown();
                start.await();

                try {
                    orders.cancelByUser(customer, order.getId());
                    return true;
                } catch (IllegalArgumentException exception) {
                    return false;
                }
            };

            var first = pool.submit(cancel);
            var second = pool.submit(cancel);

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                first.get(20, TimeUnit.SECONDS),
                second.get(20, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(true, false);

            assertThat(products.findById(tea.getId())
                .orElseThrow().getStock()).isEqualTo(10);
            assertThat(vouchers.findById(voucherId)
                .orElseThrow().getQuantity()).isEqualTo(3);
            assertThat(histories.count()).isEqualTo(2);

            var movements = inventoryTransactions
                .findByProductIdOrderByCreatedAtAscIdAsc(tea.getId());

            assertThat(movements)
                .extracting(entry -> entry.getType())
                .containsExactly(
                    InventoryTransactionType.SALE,
                    InventoryTransactionType.CANCELLATION
                );

            assertThat(movements.getLast().getQuantityChange())
                .isEqualTo(10);
            assertThat(movements.getLast().getStockBefore())
                .isZero();
            assertThat(movements.getLast().getStockAfter())
                .isEqualTo(10);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void onlyOneCustomerCanCheckoutTheLastProduct()
            throws Exception {

        tea.setStock(1);
        tea = products.saveAndFlush(tea);

        var firstLine = add(tea, medium, 1);

        var secondAddress = new Address();
        secondAddress.setUser(other);
        secondAddress.setReceiverName(other.getFullName());
        secondAddress.setPhone("0901234568");
        secondAddress.setProvince("TP HCM");
        secondAddress.setDistrict("Thủ Đức");
        secondAddress.setWard("Linh Trung");
        secondAddress.setDetail("2 Võ Văn Ngân");
        addresses.saveAndFlush(secondAddress);
        extraAddressId = secondAddress.getId();

        carts.add(
            other,
            tea.getId(),
            1,
            medium.getId(),
            null,
            null,
            List.of()
        );

        Long secondLineId = transactions.execute(tx ->
            carts.get(other).getItems().stream()
                .filter(item ->
                    item.getProduct().getId().equals(tea.getId())
                )
                .findFirst()
                .orElseThrow()
                .getId()
        );

        var firstForm = form(firstLine.getId());
        var secondForm = form(secondLineId);
        secondForm.setAddressId(secondAddress.getId());

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);

        try {
            Callable<Boolean> firstCheckout = () -> {
                ready.countDown();
                start.await();

                try {
                    orders.checkout(customer, firstForm);
                    return true;
                } catch (IllegalArgumentException exception) {
                    return false;
                }
            };

            Callable<Boolean> secondCheckout = () -> {
                ready.countDown();
                start.await();

                try {
                    orders.checkout(other, secondForm);
                    return true;
                } catch (IllegalArgumentException exception) {
                    return false;
                }
            };

            var first = pool.submit(firstCheckout);
            var second = pool.submit(secondCheckout);

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                first.get(20, TimeUnit.SECONDS),
                second.get(20, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(true, false);

            var persisted = products.findById(tea.getId())
                .orElseThrow();

            assertThat(persisted.getStock()).isZero();
            assertThat(persisted.getStatus())
                .isEqualTo(ProductStatus.OUT_OF_STOCK);

            long createdOrders = orderRepository.findAll().stream()
                .filter(order ->
                    order.getUser().getId().equals(customer.getId())
                        || order.getUser().getId().equals(other.getId())
                )
                .count();

            assertThat(createdOrders).isEqualTo(1);

            var movements = inventoryTransactions
                .findByProductIdOrderByCreatedAtAscIdAsc(tea.getId());

            assertThat(movements).hasSize(1);
            assertThat(movements.getFirst().getType())
                .isEqualTo(InventoryTransactionType.SALE);
            assertThat(movements.getFirst().getQuantityChange())
                .isEqualTo(-1);
            assertThat(movements.getFirst().getStockBefore())
                .isEqualTo(1);
            assertThat(movements.getFirst().getStockAfter())
                .isZero();
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @AfterEach
    void cleanup() {
        transactions.executeWithoutResult(tx -> {
            // Notifications created by checkout/cancel must be removed before their test recipients.
            notificationRepository.deleteAll(notificationRepository.findAll().stream()
                .filter(n -> n.getUser().getId().equals(customer.getId())
                    || n.getUser().getId().equals(other.getId())).toList());
            notificationRepository.flush();
            // Xóa lịch sử kho trước các bản ghi được tham chiếu.
            inventoryTransactions.deleteAll(
                inventoryTransactions
                    .findByProductIdOrderByCreatedAtAscIdAsc(
                        tea.getId()
                    )
            );
            inventoryTransactions.flush();

            if (voucherId != null) {
                usages.deleteAll(
                    usages.findAll().stream()
                        .filter(usage ->
                            usage.getVoucher().getId()
                                .equals(voucherId)
                        )
                        .toList()
                );
            }

            var owned = orderRepository.findAll().stream()
                .filter(order ->
                    order.getUser().getId().equals(customer.getId())
                        || order.getUser().getId().equals(other.getId())
                )
                .toList();

            for (var order : owned) {
                histories.deleteAll(
                    histories.findByOrderIdOrderByChangedAtAscIdAsc(
                        order.getId()
                    )
                );
            }

            orderRepository.deleteAll(owned);
            orderRepository.flush();

            cartRepository.findByUserId(customer.getId())
                .ifPresent(cartRepository::delete);
            cartRepository.findByUserId(other.getId())
                .ifPresent(cartRepository::delete);
            cartRepository.flush();

            addresses.deleteById(address.getId());

            if (extraAddressId != null) {
                addresses.deleteById(extraAddressId);
            }

            carriers.deleteById(carrier.getId());

            if (voucherId != null) {
                vouchers.deleteById(voucherId);
            }

            sizes.deleteById(medium.getId());
            sizes.deleteById(large.getId());
            sizes.flush();

            var shopId = tea.getShop().getId();
            var categoryId = tea.getCategory().getId();

            products.deleteById(tea.getId());
            products.flush();
            shops.deleteById(shopId);
            categories.deleteById(categoryId);
            users.deleteById(customer.getId());
            users.deleteById(other.getId());
        });
    }
}