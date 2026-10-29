package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.repository.*;
import vn.iotstar.service.CartService;
import vn.iotstar.service.OrderService;
import vn.iotstar.service.ReorderService;

@ActiveProfiles("test")
@SpringBootTest
class ReorderTests {
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private CarrierRepository carrierRepository;
    @Autowired private ProductSizeRepository productSizeRepository;
    @Autowired private ProductToppingRepository productToppingRepository;
    @Autowired private ToppingRepository toppingRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private CartService cartService;
    @Autowired private OrderService orderService;
    @Autowired private ReorderService reorderService;

    @Test
    void buysAgainWithOriginalOptionsButCurrentPrices() {
        Fixture fixture = fixture();
        Product product = product(fixture, "Matcha");
        ProductSize size = productSizeRepository.findByProductIdAndActiveTrueOrderByDisplayOrderAscIdAsc(product.getId())
            .stream().filter(option -> option.getCode().equals("L")).findFirst().orElseThrow();
        Long toppingId = productToppingRepository.findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(product.getId())
            .getFirst().getTopping().getId();
        cartService.add(fixture.customer(), product.getId(), 2, size.getId(), SugarLevel.PERCENT_50,
            IceLevel.LESS_ICE, List.of(toppingId));
        CustomerOrder order = checkout(fixture);
        OrderItem original = order.getItems().getFirst();
        BigDecimal oldUnitPrice = original.getUnitPrice();
        assertEquals(toppingId.toString(), original.getToppingIds());

        product = productRepository.findById(product.getId()).orElseThrow();
        product.setPrice(new BigDecimal("45000"));
        productRepository.save(product);
        size.setExtraPrice(size.getExtraPrice().add(new BigDecimal("5000")));
        productSizeRepository.save(size);

        ReorderService.ReorderResult result = reorderService.reorder(fixture.customer(), order.getId());
        assertEquals(1, result.addedLines());
        assertTrue(result.skipped().isEmpty());
        CartItem current = cartService.get(fixture.customer()).getItems().getFirst();
        assertEquals(2, current.getQuantity());
        assertEquals("L", current.getSize());
        assertEquals(SugarLevel.PERCENT_50, current.getSugarLevel());
        assertEquals(IceLevel.LESS_ICE, current.getIceLevel());
        assertEquals(toppingId.toString(), current.getToppingIds());
        assertEquals(0, new BigDecimal("45000").compareTo(current.getBasePrice()));
        assertTrue(current.getUnitPrice().compareTo(oldUnitPrice) > 0);
        assertEquals(0, oldUnitPrice.compareTo(orderItemRepository.findById(original.getId()).orElseThrow().getUnitPrice()));
    }

    @Test
    void skipsUnavailableItemsAndKeepsAvailableOnes() {
        Fixture fixture = fixture();
        Product valid = product(fixture, "Valid");
        Product missingTopping = product(fixture, "Missing topping");
        Product soldOut = product(fixture, "Sold out");
        Long toppingId = productToppingRepository.findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(missingTopping.getId())
            .getFirst().getTopping().getId();
        cartService.add(fixture.customer(), valid.getId(), 1);
        cartService.add(fixture.customer(), missingTopping.getId(), 1, null, null, null, List.of(toppingId));
        cartService.add(fixture.customer(), soldOut.getId(), 1);
        CustomerOrder order = checkout(fixture);

        ProductTopping link = productToppingRepository.findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(missingTopping.getId())
            .stream().filter(option -> option.getTopping().getId().equals(toppingId)).findFirst().orElseThrow();
        productToppingRepository.delete(link);
        soldOut = productRepository.findById(soldOut.getId()).orElseThrow();
        soldOut.setStock(0);
        productRepository.save(soldOut);

        ReorderService.ReorderResult result = reorderService.reorder(fixture.customer(), order.getId());
        assertEquals(1, result.addedLines());
        assertEquals(2, result.skipped().size());
        assertTrue(result.skipped().stream().anyMatch(message -> message.contains("topping")));
        assertTrue(result.skipped().stream().anyMatch(message -> message.contains("hết hàng")));
        List<CartItem> cartItems = cartService.get(fixture.customer()).getItems();
        assertEquals(1, cartItems.size());
        assertEquals(valid.getId(), cartItems.getFirst().getProduct().getId());

        User stranger = user("stranger-" + UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> reorderService.reorder(stranger, order.getId()));
        assertTrue(cartService.get(stranger).getItems().isEmpty());
    }

    @Test
    void oldOrderWithoutToppingIdsFallsBackToNames() {
        Fixture fixture = fixture();
        Product product = product(fixture, "Legacy");
        Long toppingId = productToppingRepository.findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(product.getId())
            .getFirst().getTopping().getId();
        cartService.add(fixture.customer(), product.getId(), 1, null, null, null, List.of(toppingId));
        CustomerOrder order = checkout(fixture);
        OrderItem historical = order.getItems().getFirst();
        historical.setToppingIds(null);
        orderItemRepository.save(historical);

        ReorderService.ReorderResult result = reorderService.reorder(fixture.customer(), order.getId());
        assertEquals(1, result.addedLines());
        assertEquals(toppingId.toString(), cartService.get(fixture.customer()).getItems().getFirst().getToppingIds());
    }

    @Test
    void oldBlackPearlNameStillResolvesAfterCatalogRename() {
        Fixture fixture = fixture();
        Product product = product(fixture, "Legacy pearl");
        Topping pearl = productToppingRepository.findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(product.getId())
            .getFirst().getTopping();
        pearl.setName("Trân châu đường đen");
        toppingRepository.save(pearl);
        cartService.add(fixture.customer(), product.getId(), 1, null, null, null, List.of(pearl.getId()));
        CustomerOrder order = checkout(fixture);
        OrderItem historical = order.getItems().getFirst();
        historical.setToppingIds(null);
        historical.setToppings("Trân châu đen");
        orderItemRepository.save(historical);

        ReorderService.ReorderResult result = reorderService.reorder(fixture.customer(), order.getId());
        assertEquals(1, result.addedLines());
        assertEquals(pearl.getId().toString(), cartService.get(fixture.customer()).getItems().getFirst().getToppingIds());
    }

    @Test
    void hiddenProductAndDisabledSizeCannotBeBoughtAgain() {
        Fixture fixture = fixture();
        Product hidden = product(fixture, "Hidden");
        Product disabledSize = product(fixture, "Disabled size");
        ProductSize sizeL = productSizeRepository
            .findByProductIdAndActiveTrueOrderByDisplayOrderAscIdAsc(disabledSize.getId()).stream()
            .filter(size -> size.getCode().equals("L")).findFirst().orElseThrow();
        cartService.add(fixture.customer(), hidden.getId(), 1);
        cartService.add(fixture.customer(), disabledSize.getId(), 1, sizeL.getId(),
            SugarLevel.PERCENT_50, IceLevel.LESS_ICE, List.of());
        CustomerOrder order = checkout(fixture);

        hidden = productRepository.findById(hidden.getId()).orElseThrow();
        hidden.setStatus(ProductStatus.HIDDEN);
        productRepository.save(hidden);
        sizeL.setActive(false);
        productSizeRepository.save(sizeL);

        ReorderService.ReorderResult result = reorderService.reorder(fixture.customer(), order.getId());
        assertEquals(0, result.addedLines());
        assertEquals(2, result.skipped().size());
        assertTrue(result.skipped().stream().anyMatch(message -> message.contains("ngừng bán")));
        assertTrue(result.skipped().stream().anyMatch(message -> message.contains("Size L")));
        assertTrue(cartService.get(fixture.customer()).getItems().isEmpty());
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = user("buyer-" + suffix);
        User vendor = user("vendor-" + suffix);
        vendor.setRole(Role.VENDOR);
        userRepository.save(vendor);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra reorder " + suffix);
        shop.setSlug("reorder-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shopRepository.save(shop);
        Category category = new Category();
        category.setName("Reorder " + suffix);
        category.setSlug("reorder-" + suffix);
        category = categoryRepository.save(category);
        Address address = new Address();
        address.setUser(customer);
        address.setReceiverName("Khách mua lại");
        address.setPhone("0901234567");
        address.setProvince("TP. Hồ Chí Minh");
        address.setDistrict("Thủ Đức");
        address.setWard("Linh Trung");
        address.setDetail("01 Võ Văn Ngân");
        address = addressRepository.save(address);
        Carrier carrier = new Carrier();
        carrier.setName("Reorder carrier " + suffix);
        carrier.setShippingFee(new BigDecimal("15000"));
        carrier = carrierRepository.save(carrier);
        return new Fixture(customer, shop, category, address, carrier, suffix);
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(username);
        user.setRole(Role.USER);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private Product product(Fixture fixture, String name) {
        Product product = new Product();
        product.setShop(fixture.shop());
        product.setCategory(fixture.category());
        product.setName(name);
        product.setSlug(name.toLowerCase().replace(' ', '-') + "-" + fixture.suffix());
        product.setSku(name.replace(' ', '-') + "-" + fixture.suffix());
        product.setDescription("Sản phẩm kiểm thử mua lại.");
        product.setPrice(new BigDecimal("30000"));
        product.setStock(20);
        product = productRepository.save(product);
        ProductSize sizeM = new ProductSize();
        sizeM.setProduct(product);
        sizeM.setCode("M");
        sizeM.setDisplayOrder(0);
        productSizeRepository.save(sizeM);
        ProductSize sizeL = new ProductSize();
        sizeL.setProduct(product);
        sizeL.setCode("L");
        sizeL.setExtraPrice(new BigDecimal("7000"));
        sizeL.setDisplayOrder(1);
        productSizeRepository.save(sizeL);
        Topping topping = new Topping();
        topping.setName("Reorder topping " + product.getSku());
        topping.setPrice(new BigDecimal("5000"));
        topping = toppingRepository.save(topping);
        ProductTopping link = new ProductTopping();
        link.setProduct(product);
        link.setTopping(topping);
        productToppingRepository.save(link);
        return product;
    }

    private CustomerOrder checkout(Fixture fixture) {
        CheckoutForm form = new CheckoutForm();
        form.setAddressId(fixture.address().getId());
        form.setCarrierId(fixture.carrier().getId());
        return orderService.checkout(fixture.customer(), form).getFirst();
    }

    private record Fixture(User customer, Shop shop, Category category, Address address,
                           Carrier carrier, String suffix) {}
}
