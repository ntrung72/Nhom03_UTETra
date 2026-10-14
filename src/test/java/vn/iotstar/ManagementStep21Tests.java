package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.ManagerShop;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.OrderStatusHistoryRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.ManagementService;
import vn.iotstar.service.OrderService;

/** Step 21: scoped Management orders and assigned-shipper workflow only. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.upload-dir=${java.io.tmpdir}/utetra-step21-proofs")
@Transactional
class ManagementStep21Tests {
    @Autowired OrderService orderService;
    @Autowired ManagementService managementService;
    @Autowired OrderRepository orders;
    @Autowired OrderStatusHistoryRepository histories;
    @Autowired UserRepository users;
    @Autowired ShopRepository shops;
    @Autowired ManagerShopRepository managerShops;

    User admin, manager, shipper, otherShipper, customer;
    Shop shop, otherShop;

    @BeforeEach
    void setup() {
        admin = user(Role.ADMIN);
        manager = user(Role.MANAGER);
        shipper = user(Role.SHIPPER);
        otherShipper = user(Role.SHIPPER);
        customer = user(Role.USER);
        shop = shop();
        otherShop = shop();
        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(shop);
        assignment.setAssignedBy(admin);
        managerShops.saveAndFlush(assignment);
    }

    @Test
    void managementSearchEnforcesShopScopeAndBlocksForgedAssignment() {
        CustomerOrder assigned = order(shop, OrderStatus.READY_FOR_PICKUP);
        CustomerOrder forbidden = order(otherShop, OrderStatus.READY_FOR_PICKUP);
        assertThat(managementService.orders(manager, null, null, null, 0).getContent())
            .extracting(CustomerOrder::getId).contains(assigned.getId()).doesNotContain(forbidden.getId());
        assertThatThrownBy(() -> managementService.orders(manager, null, otherShop.getId(), null, 0))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orderService.assignShipper(manager, forbidden.getId(), shipper.getId()))
            .isInstanceOf(AccessDeniedException.class);
        orderService.assignShipper(manager, assigned.getId(), shipper.getId());
        assertThat(orders.findById(assigned.getId()).orElseThrow().getShipper().getId()).isEqualTo(shipper.getId());
        assertThat(histories.findByOrderIdOrderByChangedAtAscIdAsc(assigned.getId())).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void assignmentRejectsEarlyOrdersAndInvalidShipper() {
        CustomerOrder newOrder = order(shop, OrderStatus.NEW);
        assertThatThrownBy(() -> orderService.assignShipper(admin, newOrder.getId(), shipper.getId()))
            .isInstanceOf(IllegalArgumentException.class);
        CustomerOrder ready = order(shop, OrderStatus.READY_FOR_PICKUP);
        assertThatThrownBy(() -> orderService.assignShipper(admin, ready.getId(), customer.getId()))
            .isInstanceOf(IllegalArgumentException.class);
        orderService.assignShipper(admin, ready.getId(), shipper.getId());
        assertThat(orderService.forShipper(shipper, null, 0).getContent())
            .extracting(CustomerOrder::getId).contains(ready.getId());
        assertThat(orderService.shipperTotal(shipper)).isEqualTo(1);
    }

    @Test
    void shipperMustBeAssignedAndFollowTransitionSequence() {
        CustomerOrder ready = order(shop, OrderStatus.READY_FOR_PICKUP);
        orderService.assignShipper(admin, ready.getId(), shipper.getId());
        assertThatThrownBy(() -> orderService.shipperUpdate(otherShipper, ready.getId(), OrderStatus.PICKED_UP, null, null))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.DELIVERED, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.PICKED_UP, null, null);
        assertThat(ready.getPickedUpAt()).isNotNull();
        orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.SHIPPING, null, null);
        assertThatThrownBy(() -> orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.DELIVERED, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        var proof = new MockMultipartFile("deliveryProof", "delivered.jpg", "image/jpeg", new byte[]{1, 2, 3});
        orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.DELIVERED, null, proof);
        assertThat(ready.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(ready.getDeliveredAt()).isNotNull();
        assertThat(ready.getDeliveryProofUrl()).contains("delivery-proofs");
        assertThat(histories.findByOrderIdOrderByChangedAtAscIdAsc(ready.getId())).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void failedDeliveryCanRetryOnlyWithDifferentShipper() {
        CustomerOrder ready = order(shop, OrderStatus.READY_FOR_PICKUP);
        orderService.assignShipper(admin, ready.getId(), shipper.getId());
        orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.PICKED_UP, null, null);
        orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.SHIPPING, null, null);
        assertThatThrownBy(() -> orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.DELIVERY_FAILED, "bad", null))
            .isInstanceOf(IllegalArgumentException.class);
        orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.DELIVERY_FAILED, "Khách không nhận", null);
        assertThat(ready.getFailureReason()).isEqualTo("Khách không nhận");
        assertThatThrownBy(() -> orderService.retryFailedDelivery(admin, ready.getId(), shipper.getId()))
            .isInstanceOf(IllegalArgumentException.class);
        orderService.retryFailedDelivery(manager, ready.getId(), otherShipper.getId());
        assertThat(ready.getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(ready.getShipper().getId()).isEqualTo(otherShipper.getId());
        assertThat(ready.getFailureReason()).isNull();
        assertThat(ready.getPickedUpAt()).isNull();
    }

    @Test
    void onlyManagementMayCancelFailedOrders() {
        CustomerOrder failed = order(shop, OrderStatus.DELIVERY_FAILED);
        assertThatThrownBy(() -> orderService.managementUpdate(customer, failed.getId(), OrderStatus.CANCELLED))
            .isInstanceOf(AccessDeniedException.class);
        orderService.managementUpdate(manager, failed.getId(), OrderStatus.CANCELLED);
        assertThat(failed.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(histories.findByOrderIdOrderByChangedAtAscIdAsc(failed.getId())).hasSize(1);
    }

    private User user(Role role) {
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User u = new User();
        u.setUsername("s21" + unique);
        u.setEmail("s21" + unique + "@example.invalid");
        u.setFullName("Step 21 " + unique);
        u.setPasswordHash("hash");
        u.setRole(role);
        u.setEnabled(true);
        return users.saveAndFlush(u);
    }

    private Shop shop() {
        Shop s = new Shop();
        s.setOwner(user(Role.VENDOR));
        s.setName("Shop " + UUID.randomUUID());
        s.setSlug(UUID.randomUUID().toString());
        s.setStatus(ShopStatus.APPROVED);
        return shops.saveAndFlush(s);
    }

    private CustomerOrder order(Shop s, OrderStatus status) {
        CustomerOrder o = new CustomerOrder();
        o.setCode("S21" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setUser(customer);
        o.setShop(s);
        o.setStatus(status);
        o.setReceiverName("Người nhận");
        o.setReceiverPhone("0900000000");
        o.setShippingAddress("123 Đường Test");
        o.setSubtotal(new BigDecimal("100000.00"));
        o.setTotalAmount(new BigDecimal("100000.00"));
        o.setProductDiscount(BigDecimal.ZERO);
        o.setVoucherDiscount(BigDecimal.ZERO);
        o.setShippingDiscount(BigDecimal.ZERO);
        o.setFinalShippingFee(BigDecimal.ZERO);
        return orders.saveAndFlush(o);
    }
}
