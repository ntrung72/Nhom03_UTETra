package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.ManagerShop;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.NotificationRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.NotificationService;
import vn.iotstar.service.OrderService;

/** Step 22: database notifications, recipient isolation and integration with Step 21. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManagementStep22Tests {
    @Autowired NotificationService notifications;
    @Autowired NotificationRepository notificationRepository;
    @Autowired OrderService orders;
    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;
    @Autowired ShopRepository shopRepository;
    @Autowired ManagerShopRepository managerShops;

    @Test
    void notificationIsPersistedUnreadAndCanOnlyBeMarkedReadByRecipient() {
        User customer = user(Role.USER);
        User other = user(Role.USER);
        notifications.send(customer, "Đơn đã giao", "Đơn hàng của bạn thành công", "/orders/1");
        var page = notifications.list(customer, 0);
        assertThat(page.getContent()).hasSize(1);
        var saved = page.getContent().getFirst();
        assertThat(saved.isRead()).isFalse();
        assertThat(saved.getLink()).isEqualTo("/orders/1");
        assertThat(notifications.unreadCount(customer)).isEqualTo(1);
        assertThat(notifications.unreadCount(other)).isZero();
        assertThat(notifications.list(other, 0).getContent()).isEmpty();
        assertThatThrownBy(() -> notifications.markRead(other, saved.getId()))
            .isInstanceOf(IllegalArgumentException.class);
        notifications.markRead(customer, saved.getId());
        notificationRepository.flush();
        assertThat(notifications.unreadCount(customer)).isZero();
    }

    @Test
    void assignmentAndShippingEventsNotifyCorrectPeopleWithoutChangingCheckout() {
        User admin = user(Role.ADMIN);
        User shipper = user(Role.SHIPPER);
        User customer = user(Role.USER);
        Shop shop = shop();
        CustomerOrder order = order(shop, customer, OrderStatus.READY_FOR_PICKUP);

        orders.assignShipper(admin, order.getId(), shipper.getId());
        assertThat(notifications.unreadCount(shipper)).isEqualTo(1);
        assertThat(notifications.unreadCount(customer)).isZero();
        orders.shipperUpdate(shipper, order.getId(), OrderStatus.PICKED_UP, null, null);
        orders.shipperUpdate(shipper, order.getId(), OrderStatus.SHIPPING, null, null);
        assertThat(notifications.unreadCount(customer)).isEqualTo(2);
        assertThat(notifications.list(customer, 0).getContent())
            .allSatisfy(n -> assertThat(n.getLink()).isEqualTo("/orders/" + order.getId()));
        assertThat(notifications.unreadCount(shop.getOwner())).isEqualTo(2);
        assertThat(orders.getForUser(customer, order.getId()).getStatus()).isEqualTo(OrderStatus.SHIPPING);
    }

    @Test
    void deliveryFailureNotifiesOnlyManagersOfTheShopAndActiveAdmins() {
        User admin = user(Role.ADMIN);
        User manager = user(Role.MANAGER);
        User unrelatedManager = user(Role.MANAGER);
        User shipper = user(Role.SHIPPER);
        User customer = user(Role.USER);
        Shop shop = shop();
        var association = new ManagerShop();
        association.setManager(manager);
        association.setShop(shop);
        association.setAssignedBy(admin);
        managerShops.saveAndFlush(association);
        CustomerOrder order = order(shop, customer, OrderStatus.READY_FOR_PICKUP);
        orders.assignShipper(admin, order.getId(), shipper.getId());
        orders.shipperUpdate(shipper, order.getId(), OrderStatus.PICKED_UP, null, null);
        orders.shipperUpdate(shipper, order.getId(), OrderStatus.SHIPPING, null, null);
        orders.shipperUpdate(shipper, order.getId(), OrderStatus.DELIVERY_FAILED, "Không liên lạc được", null);

        assertThat(notifications.unreadCount(customer)).isEqualTo(3);
        assertThat(notifications.unreadCount(manager)).isEqualTo(1);
        assertThat(notifications.unreadCount(admin)).isEqualTo(1);
        assertThat(notifications.unreadCount(unrelatedManager)).isZero();
        assertThat(orders.getForUser(customer, order.getId()).getStatus())
            .isEqualTo(OrderStatus.DELIVERY_FAILED);
    }

    private User user(Role role) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User user = new User();
        user.setUsername("s22" + id);
        user.setEmail("s22" + id + "@example.invalid");
        user.setFullName("Step 22 " + id);
        user.setPasswordHash("test-hash");
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.saveAndFlush(user);
    }

    private Shop shop() {
        Shop shop = new Shop();
        shop.setOwner(user(Role.VENDOR));
        shop.setName("Shop " + UUID.randomUUID());
        shop.setSlug(UUID.randomUUID().toString());
        shop.setStatus(ShopStatus.APPROVED);
        return shopRepository.saveAndFlush(shop);
    }

    private CustomerOrder order(Shop shop, User owner, OrderStatus status) {
        CustomerOrder order = new CustomerOrder();
        order.setCode("S22" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        order.setUser(owner);
        order.setShop(shop);
        order.setStatus(status);
        order.setReceiverName("Người nhận");
        order.setReceiverPhone("0900000000");
        order.setShippingAddress("Địa chỉ nhận hàng");
        order.setSubtotal(new BigDecimal("100000"));
        order.setProductDiscount(BigDecimal.ZERO);
        order.setVoucherDiscount(BigDecimal.ZERO);
        order.setShippingDiscount(BigDecimal.ZERO);
        order.setFinalShippingFee(BigDecimal.ZERO);
        order.setTotalAmount(new BigDecimal("100000"));
        return orderRepository.saveAndFlush(order);
    }
}
