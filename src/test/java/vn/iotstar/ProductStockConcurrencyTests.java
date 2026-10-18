package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.entity.Address;
import vn.iotstar.entity.Carrier;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.InventoryTransactionType;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentMethod;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.InventoryTransaction;
import vn.iotstar.entity.OrderItem;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.ProductSize;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.AddressRepository;
import vn.iotstar.repository.CarrierRepository;
import vn.iotstar.repository.CartRepository;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.InventoryTransactionRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.OrderStatusHistoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ProductSizeRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.CartService;
import vn.iotstar.service.InventoryService;
import vn.iotstar.service.OrderService;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:utetra-stock-concurrency;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000",
		"spring.datasource.hikari.maximum-pool-size=8" })
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductStockConcurrencyTests {

	@Autowired
	PlatformTransactionManager transactionManager;
	@Autowired
	EntityManager entityManager;
	@Autowired
	UserRepository users;
	@Autowired
	ShopRepository shops;
	@Autowired
	CategoryRepository categories;
	@Autowired
	ProductRepository products;
	@Autowired
	ProductSizeRepository sizes;
	@Autowired
	AddressRepository addresses;
	@Autowired
	CarrierRepository carriers;
	@Autowired
	CartRepository cartRepository;
	@Autowired
	OrderRepository orderRepository;
	@Autowired
	OrderStatusHistoryRepository histories;
	@Autowired
	InventoryTransactionRepository movements;
	@Autowired
	CartService carts;
	@Autowired
	OrderService orders;
	@Autowired
	InventoryService inventory;

	// Deliberately no @Transactional on this class: fixtures must be committed
	// before worker threads open independent transactions and read them.

	@Test
	void onlyOneOfTwoBuyersCanPurchaseTheLastUnit() throws Exception {
		Scenario scenario = scenario(1, 2, 1, false);

		List<Attempt> attempts = race(scenario);

		assertOutcome(scenario, attempts, 1, 0, 1);
	}

	@Test
	void rejectedCheckoutDoesNotPartiallyConsumeStock() throws Exception {
		Scenario scenario = scenario(3, 2, 2, false);

		List<Attempt> attempts = race(scenario);

		assertOutcome(scenario, attempts, 1, 1, 1);
	}

	@Test
	void threeBuyersCompetingForTwoUnitsProduceTwoOrdersAndTwoSales() throws Exception {
		Scenario scenario = scenario(2, 3, 1, false);

		List<Attempt> attempts = race(scenario);

		assertOutcome(scenario, attempts, 2, 0, 2);
	}

	@Test
	void differentSizeLinesShareTheSameProductStockDuringConcurrentCheckout() throws Exception {
		// Each buyer has one M and one L. Each checkout needs two units.
		Scenario scenario = scenario(3, 2, 2, true);

		List<Attempt> attempts = race(scenario);

		assertOutcome(scenario, attempts, 1, 1, 2);
	}

	@Test
	void changingStockWithoutPessimisticLockIsRejected() {
		Scenario scenario = scenario(5, 1, 1, false);

		assertThatThrownBy(() -> inTransaction(() -> {
			Product unlocked = products.findById(scenario.productId()).orElseThrow();
			User actor = users.findById(scenario.buyers().getFirst().userId()).orElseThrow();
			inventory.setStock(unlocked, 1, actor);
			return null;
		})).isInstanceOf(IllegalStateException.class).hasMessageContaining("Phải khóa sản phẩm");

		inTransaction(() -> {
			assertThat(products.findById(scenario.productId()).orElseThrow().getStock()).isEqualTo(5);
			assertThat(saleHistory(scenario)).isEmpty();
			assertThat(shopOrders(scenario)).isEmpty();
			return null;
		});
	}

	@Test
	void rollingBackCheckoutRestoresStockHistoryOrdersAndCartTogether() {
		Scenario scenario = scenario(5, 1, 2, false);
		Buyer buyer = scenario.buyers().getFirst();

		transaction().execute(status -> {
			User user = users.findById(buyer.userId()).orElseThrow();
			List<CustomerOrder> created = orders.checkout(user, checkoutForm(scenario, buyer));
			entityManager.flush();
			assertThat(created).hasSize(1);
			assertThat(products.findById(scenario.productId()).orElseThrow().getStock()).isEqualTo(3);
			assertThat(saleHistory(scenario)).hasSize(1);
			assertThat(cartRepository.findByUserId(buyer.userId()).orElseThrow().getItems()).isEmpty();
			status.setRollbackOnly();
			return null;
		});

		inTransaction(() -> {
			Product restored = products.findById(scenario.productId()).orElseThrow();
			assertThat(restored.getStock()).isEqualTo(5);
			assertThat(restored.getStatus()).isEqualTo(ProductStatus.ACTIVE);
			assertThat(saleHistory(scenario)).isEmpty();
			assertThat(shopOrders(scenario)).isEmpty();
			var restoredItems = cartRepository.findByUserId(buyer.userId()).orElseThrow().getItems();
			assertThat(restoredItems).extracting(item -> item.getId()).containsExactlyElementsOf(buyer.itemIds());
			assertThat(restoredItems.stream().mapToInt(item -> item.getQuantity()).sum()).isEqualTo(2);
			return null;
		});
	}

	private List<Attempt> race(Scenario scenario) throws Exception {
		int count = scenario.buyers().size();
		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(count);
		List<Future<Attempt>> futures = new ArrayList<>();
		try {
			for (Buyer buyer : scenario.buyers()) {
				futures.add(pool.submit(() -> attempt(scenario, buyer, ready, start)));
			}
			assertThat(ready.await(15, TimeUnit.SECONDS)).as("All buyers loaded the product before checkout begins")
					.isTrue();
			start.countDown();
			List<Attempt> results = new ArrayList<>();
			for (Future<Attempt> future : futures) {
				// Unexpected database, locking, or transaction errors fail the test.
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			start.countDown();
			pool.shutdownNow();
			assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).as("Checkout worker threads have stopped").isTrue();
		}
	}

	private Attempt attempt(Scenario scenario, Buyer buyer, CountDownLatch ready, CountDownLatch start) {
		try {
			List<Long> ids = inTransaction(() -> {
				User user = users.findById(buyer.userId()).orElseThrow();
				// Every transaction intentionally caches the original stock before
				// the barrier. Checkout must refresh it while holding the write lock.
				Product cached = products.findById(scenario.productId()).orElseThrow();
				assertThat(cached.getStock()).isEqualTo(scenario.initialStock());
				ready.countDown();
				awaitStart(start);
				return orders.checkout(user, checkoutForm(scenario, buyer)).stream().map(CustomerOrder::getId).toList();
			});
			return new Attempt(buyer.userId(), ids, null);
		} catch (IllegalArgumentException rejection) {
			// Catch outside the transaction so a rejected checkout has rolled back.
			return new Attempt(buyer.userId(), List.of(), rejection);
		}
	}

	private void awaitStart(CountDownLatch start) {
		try {
			if (!start.await(15, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting to start concurrent checkout");
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Concurrent checkout was interrupted", ex);
		}
	}

	private void assertOutcome(Scenario scenario, List<Attempt> attempts, int acceptedCount, int remainingStock,
			int movementCount) {
		assertThat(attempts).hasSize(scenario.buyers().size());
		assertThat(attempts.stream().filter(Attempt::accepted).count()).isEqualTo(acceptedCount);
		for (Attempt attempt : attempts) {
			if (attempt.accepted()) {
				assertThat(attempt.orderIds()).hasSize(1);
			} else {
				assertThat(attempt.rejection()).hasMessageContaining("tồn kho");
				assertThat(attempt.orderIds()).isEmpty();
			}
		}
		List<Long> acceptedIds = attempts.stream().flatMap(result -> result.orderIds().stream()).toList();

		inTransaction(() -> {
			Product product = products.findById(scenario.productId()).orElseThrow();
			assertThat(product.getStock()).isEqualTo(remainingStock).isNotNegative();
			assertThat(product.getStatus())
					.isEqualTo(remainingStock == 0 ? ProductStatus.OUT_OF_STOCK : ProductStatus.ACTIVE);
			List<CustomerOrder> savedOrders = shopOrders(scenario);
			assertThat(savedOrders).extracting(CustomerOrder::getId).containsExactlyInAnyOrderElementsOf(acceptedIds);
			assertThat(savedOrders).allMatch(order -> order.getStatus() == OrderStatus.NEW);
			int orderedQuantity = savedOrders.stream().flatMap(order -> order.getItems().stream())
					.mapToInt(OrderItem::getQuantity).sum();
			assertThat(orderedQuantity).isEqualTo(scenario.initialStock() - remainingStock);
			for (CustomerOrder order : savedOrders) {
				assertThat(order.getItems()).allMatch(item -> item.getProduct().getId().equals(scenario.productId()));
				assertThat(histories.findByOrderIdOrderByChangedAtAscIdAsc(order.getId())).hasSize(1);
			}

			List<InventoryTransaction> history = saleHistory(scenario);
			assertThat(history).hasSize(movementCount);
			int stock = scenario.initialStock();
			for (InventoryTransaction movement : history) {
				assertThat(movement.getType()).isEqualTo(InventoryTransactionType.SALE);
				assertThat(movement.getStockBefore()).isEqualTo(stock);
				assertThat(movement.getQuantityChange()).isNegative();
				stock += movement.getQuantityChange();
				assertThat(movement.getStockAfter()).isEqualTo(stock).isNotNegative();
				assertThat(movement.getOrder().getId()).isIn(acceptedIds);
				CustomerOrder order = orderRepository.findById(movement.getOrder().getId()).orElseThrow();
				assertThat(movement.getActor().getId()).isEqualTo(order.getUser().getId());
			}
			assertThat(stock).isEqualTo(remainingStock);

			for (Attempt attempt : attempts) {
				Buyer buyer = scenario.buyers().stream()
						.filter(candidate -> candidate.userId().equals(attempt.userId())).findFirst().orElseThrow();
				var items = cartRepository.findByUserId(buyer.userId()).orElseThrow().getItems();
				if (attempt.accepted()) {
					assertThat(items).isEmpty();
				} else {
					assertThat(items).extracting(item -> item.getId())
							.containsExactlyInAnyOrderElementsOf(buyer.itemIds());
					assertThat(items.stream().mapToInt(item -> item.getQuantity()).sum()).isEqualTo(buyer.quantity());
				}
			}
			return null;
		});
	}

	private Scenario scenario(int initialStock, int buyerCount, int quantity, boolean twoSizes) {
		return inTransaction(() -> {
			User vendor = user(Role.VENDOR);
			Shop shop = new Shop();
			shop.setOwner(vendor);
			shop.setName("Concurrent stock shop");
			shop.setSlug(UUID.randomUUID().toString());
			shop.setStatus(ShopStatus.APPROVED);
			shop.setEnabled(true);
			shops.saveAndFlush(shop);
			Category category = new Category();
			category.setName("Concurrent stock category");
			category.setSlug(UUID.randomUUID().toString());
			category.setActive(true);
			categories.saveAndFlush(category);
			Product product = new Product();
			product.setShop(shop);
			product.setCategory(category);
			product.setName("Stock race tea");
			product.setSlug(UUID.randomUUID().toString());
			product.setSku(UUID.randomUUID().toString());
			product.setDescription("Sản phẩm kiểm thử các giao dịch mua hàng đồng thời.");
			product.setPrice(new BigDecimal("35000"));
			product.setStock(initialStock);
			product.setStatus(ProductStatus.ACTIVE);
			products.saveAndFlush(product);
			ProductSize medium = size(product, "M", "0");
			ProductSize large = twoSizes ? size(product, "L", "7000") : null;
			Carrier carrier = new Carrier();
			carrier.setName("Stock carrier " + UUID.randomUUID());
			carrier.setShippingFee(new BigDecimal("20000"));
			carrier.setActive(true);
			carriers.saveAndFlush(carrier);

			List<Buyer> buyers = new ArrayList<>();
			for (int i = 0; i < buyerCount; i++) {
				User customer = user(Role.USER);
				Address address = new Address();
				address.setUser(customer);
				address.setReceiverName("Khách hàng kiểm thử");
				address.setPhone("0901234567");
				address.setProvince("TP HCM");
				address.setDistrict("Thủ Đức");
				address.setWard("Linh Trung");
				address.setDetail("1 Võ Văn Ngân");
				address.setDefaultAddress(true);
				addresses.saveAndFlush(address);
				if (twoSizes) {
					carts.add(customer, product.getId(), 1, medium.getId(), null, null, List.of());
					carts.add(customer, product.getId(), 1, large.getId(), null, null, List.of());
				} else {
					carts.add(customer, product.getId(), quantity, medium.getId(), null, null, List.of());
				}
				List<Long> itemIds = cartRepository.findByUserId(customer.getId()).orElseThrow().getItems().stream()
						.map(item -> item.getId()).toList();
				buyers.add(new Buyer(customer.getId(), address.getId(), itemIds, twoSizes ? 2 : quantity));
			}
			return new Scenario(product.getId(), shop.getId(), carrier.getId(), initialStock, List.copyOf(buyers));
		});
	}

	private User user(Role role) {
		String key = "stock-" + UUID.randomUUID();
		User user = new User();
		user.setUsername(key);
		user.setEmail(key + "@test.vn");
		user.setFullName("Stock test user");
		user.setPasswordHash("unused-test-password-hash");
		user.setRole(role);
		user.setEnabled(true);
		user.setLocked(false);
		return users.saveAndFlush(user);
	}

	private ProductSize size(Product product, String code, String extra) {
		ProductSize size = new ProductSize();
		size.setProduct(product);
		size.setCode(code);
		size.setExtraPrice(new BigDecimal(extra));
		size.setActive(true);
		return sizes.saveAndFlush(size);
	}

	private CheckoutForm checkoutForm(Scenario scenario, Buyer buyer) {
		CheckoutForm form = new CheckoutForm();
		form.setAddressId(buyer.addressId());
		form.setCarrierId(scenario.carrierId());
		form.setItemIds(buyer.itemIds());
		form.setPaymentMethod(PaymentMethod.COD);
		return form;
	}

	private List<CustomerOrder> shopOrders(Scenario scenario) {
		return orderRepository.findForShop(scenario.shopId(), null, PageRequest.of(0, 20)).getContent();
	}

	private List<InventoryTransaction> saleHistory(Scenario scenario) {
		return movements.findByProductIdOrderByCreatedAtAscIdAsc(scenario.productId()).stream()
				.sorted(Comparator.comparing(InventoryTransaction::getId)).toList();
	}

	private TransactionTemplate transaction() {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		template.setTimeout(25);
		return template;
	}

	private <T> T inTransaction(Supplier<T> work) {
		return transaction().execute(status -> work.get());
	}

	private record Buyer(Long userId, Long addressId, List<Long> itemIds, int quantity) {
	}

	private record Scenario(Long productId, Long shopId, Long carrierId, int initialStock, List<Buyer> buyers) {
	}

	private record Attempt(Long userId, List<Long> orderIds, IllegalArgumentException rejection) {
		boolean accepted() {
			return rejection == null;
		}
	}
}
