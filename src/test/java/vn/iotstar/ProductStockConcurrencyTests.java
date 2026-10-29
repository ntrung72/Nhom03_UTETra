package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.AddressRepository;
import vn.iotstar.repository.CarrierRepository;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.CartService;
import vn.iotstar.service.OrderService;

@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProductStockConcurrencyTests {
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private CarrierRepository carrierRepository;
    @Autowired private CartService cartService;
    @Autowired private OrderService orderService;

    @Test
    void onlyOneCheckoutCanBuyTheLastProduct() throws Exception {
        User vendor = saveUser("stock-lock-vendor", Role.VENDOR);
        User firstCustomer = saveUser("stock-lock-first", Role.USER);
        User secondCustomer = saveUser("stock-lock-second", Role.USER);

        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Stock Lock");
        shop.setSlug("utetra-stock-lock");
        shop.setStatus(ShopStatus.APPROVED);
        shop = shopRepository.save(shop);

        Category category = new Category();
        category.setName("Concurrency category");
        category.setSlug("concurrency-category");
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName("Ly cuối cùng");
        product.setSlug("ly-cuoi-cung");
        product.setSku("STOCK-LOCK-001");
        product.setDescription("Sản phẩm kiểm thử khóa tồn kho.");
        product.setPrice(new BigDecimal("30000"));
        product.setStock(1);
        product = productRepository.save(product);

        Carrier carrier = new Carrier();
        carrier.setName("Stock lock carrier");
        carrier.setShippingFee(new BigDecimal("15000"));
        carrier = carrierRepository.save(carrier);
        Address firstAddress = saveAddress(firstCustomer, "0901000001");
        Address secondAddress = saveAddress(secondCustomer, "0901000002");
        cartService.add(firstCustomer, product.getId(), 1);
        cartService.add(secondCustomer, product.getId(), 1);

        CheckoutForm firstForm = checkout(firstAddress, carrier);
        CheckoutForm secondForm = checkout(secondAddress, carrier);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> checkoutAfterSignal(start, firstCustomer, firstForm));
            var second = executor.submit(() -> checkoutAfterSignal(start, secondCustomer, secondForm));
            start.countDown();
            int successes = (first.get(15, TimeUnit.SECONDS) ? 1 : 0)
                + (second.get(15, TimeUnit.SECONDS) ? 1 : 0);

            Product persisted = productRepository.findById(product.getId()).orElseThrow();
            assertEquals(1, successes);
            assertEquals(0, persisted.getStock());
            assertTrue(persisted.getVersion() > 0);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean checkoutAfterSignal(CountDownLatch start, User user, CheckoutForm form) throws InterruptedException {
        start.await();
        try {
            orderService.checkout(user, form);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(username);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private Address saveAddress(User user, String phone) {
        Address address = new Address();
        address.setUser(user);
        address.setReceiverName(user.getFullName());
        address.setPhone(phone);
        address.setProvince("TP. Hồ Chí Minh");
        address.setDistrict("TP. Thủ Đức");
        address.setWard("Linh Trung");
        address.setDetail("01 Võ Văn Ngân");
        return addressRepository.save(address);
    }

    private CheckoutForm checkout(Address address, Carrier carrier) {
        CheckoutForm form = new CheckoutForm();
        form.setAddressId(address.getId());
        form.setCarrierId(carrier.getId());
        return form;
    }
}
