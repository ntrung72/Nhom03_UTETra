package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import vn.iotstar.support.MediaTestFiles;

/** Step 21: scoped Management orders and assigned-shipper workflow only. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.upload-dir=${java.io.tmpdir}/utetra-step21-proofs",
		"app.private-upload-dir=${java.io.tmpdir}/utetra-step21-private-proofs" })
@Transactional
class ManagementStep21Tests {

	@Autowired
	OrderService orderService;

	@Autowired
	ManagementService managementService;

	@Autowired
	OrderRepository orders;

	@Autowired
	OrderStatusHistoryRepository histories;

	@Autowired
	UserRepository users;

	@Autowired
	ShopRepository shops;

	@Autowired
	ManagerShopRepository managerShops;

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

		assertThat(managementService.orders(manager, null, null, null, 0).getContent()).extracting(CustomerOrder::getId)
				.contains(assigned.getId()).doesNotContain(forbidden.getId());

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

		assertThat(orderService.forShipper(shipper, null, 0).getContent()).extracting(CustomerOrder::getId)
				.contains(ready.getId());

		assertThat(orderService.shipperTotal(shipper)).isEqualTo(1);
	}

	@Test
	void shipperMustBeAssignedAndFollowTransitionSequence() {
		CustomerOrder ready = order(shop, OrderStatus.READY_FOR_PICKUP);

		orderService.assignShipper(admin, ready.getId(), shipper.getId());

		assertThatThrownBy(
				() -> orderService.shipperUpdate(otherShipper, ready.getId(), OrderStatus.PICKED_UP, null, null))
				.isInstanceOf(AccessDeniedException.class);

		assertThatThrownBy(() -> orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.DELIVERED, null, null))
				.isInstanceOf(IllegalArgumentException.class);

		orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.PICKED_UP, null, null);

		assertThat(ready.getPickedUpAt()).isNotNull();

		orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.SHIPPING, null, null);

		assertThatThrownBy(() -> orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.DELIVERED, null, null))
				.isInstanceOf(IllegalArgumentException.class);

		var proof = MediaTestFiles.image("deliveryProof", "delivered.jpg");

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

		assertThatThrownBy(
				() -> orderService.shipperUpdate(shipper, ready.getId(), OrderStatus.DELIVERY_FAILED, "bad", null))
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

		User user = new User();
		user.setUsername("s21" + unique);
		user.setEmail("s21" + unique + "@example.invalid");
		user.setFullName("Step 21 " + unique);
		user.setPasswordHash("hash");
		user.setRole(role);
		user.setEnabled(true);

		return users.saveAndFlush(user);
	}

	private Shop shop() {
		Shop shop = new Shop();
		shop.setOwner(user(Role.VENDOR));
		shop.setName("Shop " + UUID.randomUUID());
		shop.setSlug(UUID.randomUUID().toString());
		shop.setStatus(ShopStatus.APPROVED);

		return shops.saveAndFlush(shop);
	}

	private CustomerOrder order(Shop shop, OrderStatus status) {
		CustomerOrder order = new CustomerOrder();

		order.setCode("S21" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));

		order.setUser(customer);
		order.setShop(shop);
		order.setStatus(status);
		order.setReceiverName("Người nhận");
		order.setReceiverPhone("0900000000");
		order.setShippingAddress("123 Đường Test");
		order.setSubtotal(new BigDecimal("100000.00"));
		order.setTotalAmount(new BigDecimal("100000.00"));
		order.setProductDiscount(BigDecimal.ZERO);
		order.setVoucherDiscount(BigDecimal.ZERO);
		order.setShippingDiscount(BigDecimal.ZERO);
		order.setFinalShippingFee(BigDecimal.ZERO);

		return orders.saveAndFlush(order);
	}
}