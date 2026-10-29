package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.entity.Address;
import vn.iotstar.entity.Carrier;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.InventoryTransactionType;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.AddressRepository;
import vn.iotstar.repository.CarrierRepository;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.InventoryTransactionRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.OrderStatusHistoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.CartService;
import vn.iotstar.service.OrderService;

@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderStatusConcurrencyTests {
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private CarrierRepository carrierRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderStatusHistoryRepository historyRepository;
    @Autowired private InventoryTransactionRepository inventoryRepository;
    @Autowired private CartService cartService;
    @Autowired private OrderService orderService;

    @Test
    void customerAndVendorCannotCancelSameOrderTwiceOrRestoreStockTwice() throws Exception {
        OrderFixture fixture = createOrder();
        User customer = fixture.customer();
        User vendor = fixture.vendor();
        Long orderId = fixture.orderId();
        Long productId = fixture.productId();
        assertEquals(1, productRepository.findById(productId).orElseThrow().getStock());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var customerCancel = executor.submit(() -> cancelAfterSignal(ready, start,
                () -> orderService.cancelByUser(customer, orderId)));
            var vendorCancel = executor.submit(() -> cancelAfterSignal(ready, start,
                () -> orderService.vendorUpdate(vendor, orderId, OrderStatus.CANCELLED)));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int successes = (customerCancel.get(15, TimeUnit.SECONDS) ? 1 : 0)
                + (vendorCancel.get(15, TimeUnit.SECONDS) ? 1 : 0);

            assertEquals(1, successes);
            assertCancelledOnce(orderId, productId);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void customerCancellationExpiresExactlyOneHourAfterPlacement() {
        OrderFixture fixture = createOrder();
        var order = orderRepository.findById(fixture.orderId()).orElseThrow();
        LocalDateTime deadline = order.getCreatedAt().plusHours(1);
        assertNull(OrderService.cancellationBlockReason(order, deadline.minusNanos(1)));
        assertTrue(OrderService.cancellationBlockReason(order, deadline).contains("quá 1 giờ"));

        order.setCreatedAt(LocalDateTime.now().minusHours(1).minusSeconds(1));
        orderRepository.saveAndFlush(order);
        var error = assertThrows(IllegalArgumentException.class,
            () -> orderService.cancelByUser(fixture.customer(), fixture.orderId()));
        assertTrue(error.getMessage().contains("quá 1 giờ"));
        assertEquals(OrderStatus.NEW, orderRepository.findById(fixture.orderId()).orElseThrow().getStatus());
        assertEquals(1, productRepository.findById(fixture.productId()).orElseThrow().getStock());
    }

    @Test
    void customerCannotCancelAfterShopConfirms() {
        OrderFixture fixture = createOrder();
        orderService.vendorUpdate(fixture.vendor(), fixture.orderId(), OrderStatus.CONFIRMED);
        var error = assertThrows(IllegalArgumentException.class,
            () -> orderService.cancelByUser(fixture.customer(), fixture.orderId()));
        assertTrue(error.getMessage().contains("trước khi cửa hàng xác nhận"));
        assertEquals(OrderStatus.CONFIRMED, orderRepository.findById(fixture.orderId()).orElseThrow().getStatus());
        assertEquals(1, productRepository.findById(fixture.productId()).orElseThrow().getStock());
    }

    @Test
    void twoCustomerRequestsRestoreStockOnlyOnce() throws Exception {
        OrderFixture fixture = createOrder();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> cancelAfterSignal(ready, start,
                () -> orderService.cancelByUser(fixture.customer(), fixture.orderId())));
            var second = executor.submit(() -> cancelAfterSignal(ready, start,
                () -> orderService.cancelByUser(fixture.customer(), fixture.orderId())));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (first.get(15, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(15, TimeUnit.SECONDS) ? 1 : 0));
            assertCancelledOnce(fixture.orderId(), fixture.productId());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private OrderFixture createOrder() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = user("order-lock-customer-" + suffix, Role.USER);
        User vendor = user("order-lock-vendor-" + suffix, Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("Order lock shop");
        shop.setSlug("order-lock-shop-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shopRepository.save(shop);

        Category category = new Category();
        category.setName("Order lock category");
        category.setSlug("order-lock-category-" + suffix);
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName("Trà sữa kiểm thử đồng thời");
        product.setSlug("order-lock-product-" + suffix);
        product.setSku("ORDER-LOCK-" + suffix);
        product.setDescription("Kiểm tra hủy đơn đồng thời và hoàn kho đúng một lần.");
        product.setPrice(new BigDecimal("30000"));
        product.setStock(2);
        product = productRepository.save(product);

        Carrier carrier = new Carrier();
        carrier.setName("Order lock carrier " + suffix);
        carrier.setShippingFee(new BigDecimal("15000"));
        carrier = carrierRepository.save(carrier);

        Address address = new Address();
        address.setUser(customer);
        address.setReceiverName(customer.getFullName());
        address.setPhone("0901234567");
        address.setProvince("TP. Hồ Chí Minh");
        address.setDistrict("TP. Thủ Đức");
        address.setWard("Linh Trung");
        address.setDetail("01 Võ Văn Ngân");
        address = addressRepository.save(address);

        cartService.add(customer, product.getId(), 1);
        CheckoutForm form = new CheckoutForm();
        form.setAddressId(address.getId());
        form.setCarrierId(carrier.getId());
        form.setItemIds(List.of(cartService.get(customer).getItems().getFirst().getId()));
        Long orderId = orderService.checkout(customer, form).getFirst().getId();
        Long productId = product.getId();
        return new OrderFixture(customer, vendor, productId, orderId);
    }

    private void assertCancelledOnce(Long orderId, Long productId) {
        assertEquals(OrderStatus.CANCELLED, orderRepository.findById(orderId).orElseThrow().getStatus());
        assertEquals(2, productRepository.findById(productId).orElseThrow().getStock());
        assertEquals(2, historyRepository.findByOrderIdOrderByChangedAtAscIdAsc(orderId).size());
        var movements = inventoryRepository.findByProductIdOrderByCreatedAtAscIdAsc(productId);
        assertEquals(2, movements.size());
        assertEquals(InventoryTransactionType.SALE, movements.get(0).getType());
        assertEquals(InventoryTransactionType.CANCELLATION, movements.get(1).getType());
    }

    private record OrderFixture(User customer, User vendor, Long productId, Long orderId) {}

    private boolean cancelAfterSignal(CountDownLatch ready, CountDownLatch start, Runnable action)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            action.run();
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private User user(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(username);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }
}
