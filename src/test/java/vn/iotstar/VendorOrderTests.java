package vn.iotstar;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.OrderStatusHistory;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.InventoryTransactionRepository;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VendorOrderTests extends CheckoutTestFixtures {

    @Autowired
    InventoryTransactionRepository inventoryTransactions;

    @BeforeEach
    void vendorFixtures() {
        other.setRole(Role.VENDOR);
        other.setEnabled(true);
        other.setLocked(false);
        users.saveAndFlush(other);
    }

    @Test
    void validWorkflowRecordsActorAndNotesWithoutChangingStockOrPayment() {
        var order = buy(2);
        var total = order.getTotalAmount();

        assertThat(tea.getStock()).isEqualTo(8);

        orders.vendorUpdate(
            other,
            order.getId(),
            OrderStatus.CONFIRMED,
            "  Đã kiểm tra đơn  "
        );

        orders.vendorUpdate(
            other,
            order.getId(),
            OrderStatus.PREPARING
        );

        orders.vendorUpdate(
            other,
            order.getId(),
            OrderStatus.READY_FOR_PICKUP,
            "Đã đóng gói"
        );

        var saved = orders.getForVendor(other, order.getId());

        assertThat(saved.getStatus())
            .isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(saved.getPaymentStatus())
            .isEqualTo(PaymentStatus.UNPAID);
        assertThat(saved.getTotalAmount())
            .isEqualByComparingTo(total);
        assertThat(tea.getStock()).isEqualTo(8);

        var history = orders.vendorStatusHistory(other, order.getId());

        assertThat(history)
            .extracting(OrderStatusHistory::getNewStatus)
            .containsExactly(
                OrderStatus.NEW,
                OrderStatus.CONFIRMED,
                OrderStatus.PREPARING,
                OrderStatus.READY_FOR_PICKUP
            );

        assertThat(history)
            .extracting(OrderStatusHistory::getOldStatus)
            .containsExactly(
                null,
                OrderStatus.NEW,
                OrderStatus.CONFIRMED,
                OrderStatus.PREPARING
            );

        assertThat(history.getFirst().getChangedBy().getId())
            .isEqualTo(customer.getId());

        assertThat(history.subList(1, history.size()))
            .allSatisfy(entry -> {
                assertThat(entry.getChangedBy().getId())
                    .isEqualTo(other.getId());
                assertThat(entry.getChangedAt()).isNotNull();
            });

        assertThat(history.get(1).getNote())
            .isEqualTo("Đã kiểm tra đơn");
        assertThat(history.get(2).getNote())
            .isEqualTo("Cập nhật bởi người bán");
        assertThat(history.get(3).getNote())
            .isEqualTo("Đã đóng gói");

        assertThat(
            inventoryTransactions
                .findByProductIdOrderByCreatedAtAscIdAsc(tea.getId())
        ).hasSize(1);
    }

    @Test
    void cannotSkipRepeatReverseOrEnterShipperStatuses() {
        var order = buy(1);

        for (var target : List.of(
                OrderStatus.NEW,
                OrderStatus.PREPARING,
                OrderStatus.READY_FOR_PICKUP,
                OrderStatus.PICKED_UP,
                OrderStatus.SHIPPING,
                OrderStatus.DELIVERED,
                OrderStatus.DELIVERY_FAILED,
                OrderStatus.CANCELLED)) {

            assertThatThrownBy(() ->
                orders.vendorUpdate(other, order.getId(), target)
            ).isInstanceOf(IllegalArgumentException.class);
        }

        orders.vendorUpdate(
            other,
            order.getId(),
            OrderStatus.CONFIRMED
        );

        for (var target : List.of(
                OrderStatus.NEW,
                OrderStatus.CONFIRMED,
                OrderStatus.READY_FOR_PICKUP)) {

            assertThatThrownBy(() ->
                orders.vendorUpdate(other, order.getId(), target)
            ).isInstanceOf(IllegalArgumentException.class);
        }

        orders.vendorUpdate(
            other,
            order.getId(),
            OrderStatus.PREPARING
        );

        assertThatThrownBy(() ->
            orders.vendorUpdate(
                other,
                order.getId(),
                OrderStatus.CONFIRMED
            )
        ).isInstanceOf(IllegalArgumentException.class);

        orders.vendorUpdate(
            other,
            order.getId(),
            OrderStatus.READY_FOR_PICKUP
        );

        for (var target : OrderStatus.values()) {
            assertThatThrownBy(() ->
                orders.vendorUpdate(other, order.getId(), target)
            ).isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(order.getStatus())
            .isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(tea.getStock()).isEqualTo(9);

        assertThat(orders.vendorStatusHistory(other, order.getId()))
            .hasSize(4);
    }

    @Test
    void anotherVendorCannotReadHistoryOrUpdateTheOrder() {
        var order = buy(1);
        var outsider = anotherVendor();

        assertThat(orders.forVendor(outsider, null, 0).getContent())
            .isEmpty();

        assertNotFound(() ->
            orders.getForVendor(outsider, order.getId())
        );

        assertNotFound(() ->
            orders.vendorStatusHistory(outsider, order.getId())
        );

        assertNotFound(() ->
            orders.vendorUpdate(
                outsider,
                order.getId(),
                OrderStatus.CONFIRMED
            )
        );

        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(tea.getStock()).isEqualTo(9);

        assertThat(orders.vendorStatusHistory(other, order.getId()))
            .hasSize(1);
    }

    @Test
    void accountMustStillBeAnEnabledUnlockedVendor() {
        var order = buy(1);

        other.setRole(Role.USER);
        users.saveAndFlush(other);

        assertForbidden(() ->
            orders.forVendor(other, null, 0)
        );

        other.setRole(Role.VENDOR);
        other.setEnabled(false);
        users.saveAndFlush(other);

        assertForbidden(() ->
            orders.getForVendor(other, order.getId())
        );

        other.setEnabled(true);
        other.setLocked(true);
        users.saveAndFlush(other);

        assertForbidden(() ->
            orders.vendorUpdate(
                other,
                order.getId(),
                OrderStatus.CONFIRMED
            )
        );

        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);

        assertThat(
            histories.findByOrderIdOrderByChangedAtAscIdAsc(order.getId())
        ).hasSize(1);
    }

    @Test
    void listFiltersPagesAndCountsOnlyTheVendorsShop() {
        var created = new ArrayList<CustomerOrder>();

        for (int i = 0; i < 16; i++) {
            created.add(historyOrder(
                tea.getShop(),
                i < 2 ? OrderStatus.CONFIRMED : OrderStatus.NEW
            ));
        }

        var outsider = anotherVendor();
        var foreignShop = shops.findByOwnerId(outsider.getId())
            .orElseThrow();

        var foreignOrder = historyOrder(
            foreignShop,
            OrderStatus.READY_FOR_PICKUP
        );

        orderRepository.flush();

        var first = orders.forVendor(other, null, 0);
        var second = orders.forVendor(other, null, 1);

        assertThat(first.getTotalElements()).isEqualTo(16);
        assertThat(first.getContent()).hasSize(15);
        assertThat(second.getContent()).hasSize(1);

        assertThat(first.getContent())
            .extracting(CustomerOrder::getId)
            .doesNotContain(foreignOrder.getId());

        // Các đơn cùng createdAt phải được sắp xếp theo ID giảm dần.
        assertThat(first.getContent().getFirst().getId())
            .isEqualTo(created.getLast().getId());
        assertThat(second.getContent().getFirst().getId())
            .isEqualTo(created.getFirst().getId());

        assertThat(
            orders.forVendor(other, OrderStatus.CONFIRMED, 0)
                .getContent()
        ).hasSize(2).allSatisfy(order ->
            assertThat(order.getStatus())
                .isEqualTo(OrderStatus.CONFIRMED)
        );

        assertThat(orders.forVendor(other, null, -1).getNumber())
            .isZero();

        var stats = orders.vendorOrderStats(other);

        assertThat(stats.newOrders()).isEqualTo(14);
        assertThat(stats.confirmedOrders()).isEqualTo(2);
        assertThat(stats.preparingOrders()).isZero();
        assertThat(stats.readyOrders()).isZero();

        assertThat(orders.vendorOrderStats(outsider).readyOrders())
            .isEqualTo(1);
    }

    @Test
    void customerCancellationPreventsLaterVendorConfirmation() {
        var order = buy(2);

        orders.cancelByUser(customer, order.getId());

        assertThatThrownBy(() ->
            orders.vendorUpdate(
                other,
                order.getId(),
                OrderStatus.CONFIRMED
            )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(tea.getStock()).isEqualTo(10);

        assertThat(orders.vendorStatusHistory(other, order.getId()))
            .extracting(OrderStatusHistory::getNewStatus)
            .containsExactly(OrderStatus.NEW, OrderStatus.CANCELLED);
    }

    @Test
    void vendorConfirmationPreventsCustomerCancellation() {
        var order = buy(2);

        orders.vendorUpdate(
            other,
            order.getId(),
            OrderStatus.CONFIRMED
        );

        assertThatThrownBy(() ->
            orders.cancelByUser(customer, order.getId())
        ).isInstanceOf(IllegalArgumentException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(tea.getStock()).isEqualTo(8);

        assertThat(orders.vendorStatusHistory(other, order.getId()))
            .extracting(OrderStatusHistory::getNewStatus)
            .containsExactly(OrderStatus.NEW, OrderStatus.CONFIRMED);
    }

    @Test
    void oversizedNoteDoesNotChangeStatusOrAppendHistory() {
        var order = buy(1);

        assertThatThrownBy(() ->
            orders.vendorUpdate(
                other,
                order.getId(),
                OrderStatus.CONFIRMED,
                "a".repeat(501)
            )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);

        assertThat(orders.vendorStatusHistory(other, order.getId()))
            .hasSize(1);
    }

    @Test
    void emptyOrdersPageRenders() throws Exception {
        mvc.perform(
            get("/vendor/orders")
                .with(user(other.getUsername()).roles("VENDOR"))
        )
            .andExpect(status().isOk())
            .andExpect(view().name("vendor/orders"))
            .andExpect(model().attributeExists("orders", "orderStats"))
            .andExpect(content().string(
                containsString("Chưa có đơn hàng ở trạng thái này.")
            ));
    }

    @Test
    void listAndDetailRenderOrderSnapshotsAndHistory() throws Exception {
        var order = buy(1);

        tea.setName("Tên sản phẩm đã đổi");
        products.saveAndFlush(tea);

        mvc.perform(
            get("/vendor/orders")
                .with(user(other.getUsername()).roles("VENDOR"))
                .param("status", "NEW")
        )
            .andExpect(status().isOk())
            .andExpect(view().name("vendor/orders"))
            .andExpect(content().string(containsString(order.getCode())));

        mvc.perform(
            get("/vendor/orders")
                .with(user(other.getUsername()).roles("VENDOR"))
                .param("orderId", order.getId().toString())
        )
            .andExpect(status().isOk())
            .andExpect(model().attributeExists(
                "selectedOrder",
                "orderHistory",
                "selectedTransitions"
            ))
            .andExpect(content().string(containsString("Trà sữa test")))
            .andExpect(content().string(
                containsString("Khách hàng đặt đơn COD")
            ));

        mvc.perform(
            get("/vendor/orders")
                .with(user(other.getUsername()).roles("VENDOR"))
                .param("status", "INVALID")
        ).andExpect(status().isBadRequest());
    }

    @Test
    void foreignOrderGetAndPostReturnNotFound() throws Exception {
        var order = buy(1);
        var outsider = anotherVendor();

        mvc.perform(
            get("/vendor/orders")
                .with(user(outsider.getUsername()).roles("VENDOR"))
                .param("orderId", order.getId().toString())
        ).andExpect(status().isNotFound());

        mvc.perform(
            post("/vendor/orders/" + order.getId() + "/status")
                .with(user(outsider.getUsername()).roles("VENDOR"))
                .with(csrf())
                .param("status", "CONFIRMED")
        ).andExpect(status().isNotFound());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(orders.vendorStatusHistory(other, order.getId()))
            .hasSize(1);
    }

    @Test
    void statusPostRequiresCsrfAndPreservesFilterAndDetail() throws Exception {
        var order = buy(1);
        var endpoint = "/vendor/orders/" + order.getId() + "/status";

        mvc.perform(
            post(endpoint)
                .with(user(other.getUsername()).roles("VENDOR"))
                .param("status", "CONFIRMED")
        ).andExpect(status().isForbidden());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);

        mvc.perform(
            post(endpoint)
                .with(user(other.getUsername()).roles("VENDOR"))
                .with(csrf())
                .param("status", "INVALID")
        ).andExpect(status().isBadRequest());

        mvc.perform(
            post(endpoint)
                .with(user(other.getUsername()).roles("VENDOR"))
                .with(csrf())
                .param("status", "CONFIRMED")
                .param("note", "Xác nhận qua giao diện")
                .param("filterStatus", "NEW")
                .param("page", "1")
                .param("showDetail", "true")
        )
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("success"))
            .andExpect(redirectedUrl(
                "/vendor/orders?status=NEW&page=1&orderId=" + order.getId()
            ));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);

        // Vẫn xem được chi tiết khi đơn đã rời bộ lọc NEW.
        mvc.perform(
            get("/vendor/orders")
                .with(user(other.getUsername()).roles("VENDOR"))
                .param("status", "NEW")
                .param("orderId", order.getId().toString())
        )
            .andExpect(status().isOk())
            .andExpect(content().string(
                containsString("Xác nhận qua giao diện")
            ));
    }

    @Test
    void invalidTransitionPostShowsErrorWithoutChangingOrder() throws Exception {
        var order = buy(1);

        mvc.perform(
            post("/vendor/orders/" + order.getId() + "/status")
                .with(user(other.getUsername()).roles("VENDOR"))
                .with(csrf())
                .param("status", "SHIPPING")
        )
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("error"))
            .andExpect(redirectedUrl("/vendor/orders?page=0"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(tea.getStock()).isEqualTo(9);
        assertThat(orders.vendorStatusHistory(other, order.getId()))
            .hasSize(1);
    }

    @Test
    void customerCannotEnterVendorOrderRoutes() throws Exception {
        var order = buy(1);

        mvc.perform(
            get("/vendor/orders")
                .with(user(customer.getUsername()).roles("USER"))
        )
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/user/profile"));

        mvc.perform(
            post("/vendor/orders/" + order.getId() + "/status")
                .with(user(customer.getUsername()).roles("USER"))
                .with(csrf())
                .param("status", "CONFIRMED")
        )
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/user/profile"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(orders.vendorStatusHistory(other, order.getId()))
            .hasSize(1);
    }

    private CustomerOrder buy(int quantity) {
        return orders.checkout(
            customer,
            form(add(tea, medium, quantity).getId())
        ).getFirst();
    }

    private User anotherVendor() {
        var product = secondShopProduct();
        var vendor = product.getShop().getOwner();

        vendor.setRole(Role.VENDOR);
        vendor.setEnabled(true);
        vendor.setLocked(false);

        return users.saveAndFlush(vendor);
    }

    private CustomerOrder historyOrder(Shop shop, OrderStatus status) {
        var order = new CustomerOrder();
        var key = UUID.randomUUID().toString().replace("-", "");

        order.setCode(key);
        order.setCheckoutReference("TEST-" + key);
        order.setUser(customer);
        order.setShop(shop);
        order.setReceiverName("Người nhận kiểm thử");
        order.setReceiverPhone("0901234567");
        order.setShippingAddress("TP. Hồ Chí Minh");
        order.setSubtotal(BigDecimal.ZERO);
        order.setProductDiscount(BigDecimal.ZERO);
        order.setVoucherDiscount(BigDecimal.ZERO);
        order.setShippingDiscount(BigDecimal.ZERO);
        order.setFinalShippingFee(BigDecimal.ZERO);
        order.setTotalAmount(BigDecimal.ZERO);
        order.setStatus(status);
        order.setCreatedAt(LocalDateTime.of(2026, 10, 1, 8, 0));

        return orderRepository.saveAndFlush(order);
    }

    private void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception ->
                    assertThat(exception.getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND)
            );
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception ->
                    assertThat(exception.getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN)
            );
    }
}