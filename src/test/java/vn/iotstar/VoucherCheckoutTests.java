package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.DomainEnums.PaymentMethod;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.VoucherType;
import vn.iotstar.entity.DomainEnums.VoucherUsageStatus;
import vn.iotstar.repository.*;
import vn.iotstar.service.CartService;
import vn.iotstar.service.OrderService;
import vn.iotstar.service.ProductOptionService;
import vn.iotstar.service.ProductPricingService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class VoucherCheckoutTests {
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private CarrierRepository carrierRepository;
    @Autowired private VoucherRepository voucherRepository;
    @Autowired private VoucherUsageRepository voucherUsageRepository;
    @Autowired private ProductSizeRepository productSizeRepository;
    @Autowired private ProductToppingRepository productToppingRepository;
    @Autowired private PromotionRepository promotionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductOptionService productOptionService;
    @Autowired private ProductPricingService productPricingService;
    @Autowired private CartService cartService;
    @Autowired private OrderService orderService;

    @Test
    void recommendationExplainsWhenUserHasExhaustedVoucherUses() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = saveUser("quota-customer-" + suffix, Role.USER);
        Category category = saveCategory("quota-category-" + suffix);
        Product product = saveProduct("QUOTA-" + suffix, saveShop("quota-shop-" + suffix), category, "100000");
        Carrier carrier = saveCarrier("Quota carrier " + suffix, "20000");
        Address address = saveAddress(customer);
        Voucher voucher = saveVoucher("QUOTA-" + suffix, VoucherType.FIXED, "10000", 1);
        cartService.add(customer, product.getId(), 1);
        orderService.checkout(customer, checkout(address, carrier, voucher));
        cartService.add(customer, product.getId(), 1);

        Long itemId = cartService.get(customer).getItems().getFirst().getId();
        var recommendations = orderService.recommendVouchers(customer, carrier.getId(), List.of(itemId));
        var candidate = recommendations.vouchers().stream()
            .filter(item -> item.code().equals(voucher.getCode())).findFirst().orElseThrow();
        assertEquals(false, candidate.applicable());
        assertEquals("Bạn đã sử dụng hết số lượt cho phép của mã này.", candidate.condition());
        assertEquals(null, recommendations.bestCode());
    }

    @Test
    void recommendsBestVoucherButAllowsCheckoutWithAnotherVoucher() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = saveUser("recommend-customer-" + suffix, Role.USER);
        Category category = saveCategory("recommend-category-" + suffix);
        Product product = saveProduct("RECOMMEND-" + suffix, saveShop("recommend-shop-" + suffix), category, "100000");
        Carrier carrier = saveCarrier("Recommend carrier " + suffix, "20000");
        Address address = saveAddress(customer);
        Voucher shipping = saveVoucher("BEST-SHIP-" + suffix, VoucherType.SHIPPING, "20000", 1);
        Voucher fixed = saveVoucher("OTHER-FIXED-" + suffix, VoucherType.FIXED, "15000", 1);
        cartService.add(customer, product.getId(), 1);

        OrderService.VoucherRecommendations recommendations = orderService.recommendVouchers(
            customer, carrier.getId(), List.of(cartService.get(customer).getItems().getFirst().getId()));
        assertEquals(shipping.getCode(), recommendations.bestCode());
        assertEquals(shipping.getCode(), recommendations.vouchers().getFirst().code());
        assertMoney("20000", recommendations.vouchers().getFirst().savings());
        assertMoney("15000", recommendations.vouchers().get(1).savings());
        assertEquals(true, recommendations.vouchers().stream().allMatch(OrderService.VoucherRecommendation::applicable));

        assertMoney("105000", orderService.preview(customer, carrier.getId(), fixed.getCode()).totalAmount());
        CustomerOrder order = orderService.checkout(customer, checkout(address, carrier, fixed)).getFirst();
        assertEquals(fixed.getId(), order.getVoucher().getId());
        assertMoney("105000", order.getTotalAmount());
    }

    @Test
    void recommendationShowsMissingMinimumAfterProductPromotion() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = saveUser("minimum-customer-" + suffix, Role.USER);
        Category category = saveCategory("minimum-category-" + suffix);
        Product product = saveProduct("MINIMUM-" + suffix, saveShop("minimum-shop-" + suffix), category, "100000");
        Carrier carrier = saveCarrier("Minimum carrier " + suffix, "20000");
        saveProductPromotion(product, "50");
        Voucher voucher = saveVoucher("MINIMUM-" + suffix, VoucherType.FIXED, "10000", 1);
        voucher.setMinimumOrder(new BigDecimal("60000"));
        cartService.add(customer, product.getId(), 1);

        OrderService.VoucherRecommendations recommendations = orderService.recommendVouchers(
            customer, carrier.getId(), List.of(cartService.get(customer).getItems().getFirst().getId()));
        OrderService.VoucherRecommendation candidate = recommendations.vouchers().stream()
            .filter(item -> item.code().equals(voucher.getCode())).findFirst().orElseThrow();
        assertEquals(false, candidate.applicable());
        assertMoney("10000", candidate.missingAmount());
        assertEquals(null, recommendations.bestCode());
        assertThrows(IllegalArgumentException.class,
            () -> orderService.preview(customer, carrier.getId(), voucher.getCode()));
    }

    @Test
    void shippingVoucherReducesShippingAndCreatesOneUsageForMultiShopCheckout() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = saveUser("ship-customer-" + suffix, Role.USER);
        Category category = saveCategory("ship-category-" + suffix);
        Product first = saveProduct("SHIP-A-" + suffix, saveShop("ship-a-" + suffix), category, "50000");
        Product second = saveProduct("SHIP-B-" + suffix, saveShop("ship-b-" + suffix), category, "50000");
        Carrier carrier = saveCarrier("Shipping carrier " + suffix, "28000");
        Address address = saveAddress(customer);
        Voucher voucher = saveVoucher("FREESHIP-" + suffix, VoucherType.SHIPPING, "18000", 1);

        cartService.add(customer, first.getId(), 1);
        cartService.add(customer, second.getId(), 1);
        OrderService.CheckoutPreview preview = orderService.preview(customer, carrier.getId(), voucher.getCode());
        assertEquals(0, new BigDecimal("100000").compareTo(preview.subtotal()));
        assertEquals(0, preview.productDiscount().compareTo(BigDecimal.ZERO));
        assertEquals(0, preview.voucherDiscount().compareTo(BigDecimal.ZERO));
        assertEquals(0, new BigDecimal("56000").compareTo(preview.shippingFee()));
        assertEquals(0, new BigDecimal("18000").compareTo(preview.shippingDiscount()));
        assertEquals(0, new BigDecimal("38000").compareTo(preview.finalShippingFee()));
        assertEquals(0, new BigDecimal("138000").compareTo(preview.totalAmount()));
        assertEquals(2, preview.shopCount());
        List<CustomerOrder> orders = orderService.checkout(customer, checkout(address, carrier, voucher));

        assertEquals(2, orders.size());
        assertEquals(0, new BigDecimal("56000").compareTo(
            orders.stream().map(CustomerOrder::getShippingFee).reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertEquals(0, new BigDecimal("18000").compareTo(
            orders.stream().map(CustomerOrder::getShippingDiscount).reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertEquals(0, new BigDecimal("38000").compareTo(
            orders.stream().map(CustomerOrder::getFinalShippingFee).reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertEquals(0, orders.stream().map(CustomerOrder::getVoucherDiscount)
            .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal.ZERO));
        assertEquals(1, voucherUsageRepository.countByUserIdAndVoucherId(customer.getId(), voucher.getId()));
        assertEquals(1, voucherUsageRepository.countByUserIdAndVoucherIdAndStatus(
            customer.getId(), voucher.getId(), VoucherUsageStatus.USED));
        assertEquals(4, voucherRepository.findById(voucher.getId()).orElseThrow().getQuantity());
        assertEquals(orders.getFirst().getCheckoutReference(), orders.getLast().getCheckoutReference());

        orderService.cancelByUser(customer, orders.getFirst().getId());
        assertEquals(4, voucherRepository.findById(voucher.getId()).orElseThrow().getQuantity());
        assertEquals(1, voucherUsageRepository.countByUserIdAndVoucherIdAndStatus(
            customer.getId(), voucher.getId(), VoucherUsageStatus.USED));

        orderService.cancelByUser(customer, orders.getLast().getId());
        assertEquals(5, voucherRepository.findById(voucher.getId()).orElseThrow().getQuantity());
        assertEquals(0, voucherUsageRepository.countByUserIdAndVoucherIdAndStatus(
            customer.getId(), voucher.getId(), VoucherUsageStatus.USED));
        VoucherUsage cancelledUsage = voucherUsageRepository.findAll().stream()
            .filter(usage -> usage.getVoucher().getId().equals(voucher.getId()))
            .findFirst().orElseThrow();
        assertEquals(VoucherUsageStatus.CANCELLED, cancelledUsage.getStatus());
        assertEquals(true, cancelledUsage.getReleasedAt() != null);

        cartService.add(customer, first.getId(), 1);
        List<CustomerOrder> replacementOrders = orderService.checkout(customer, checkout(address, carrier, voucher));
        assertEquals(1, replacementOrders.size());
        assertEquals(4, voucherRepository.findById(voucher.getId()).orElseThrow().getQuantity());
        assertEquals(1, voucherUsageRepository.countByUserIdAndVoucherIdAndStatus(
            customer.getId(), voucher.getId(), VoucherUsageStatus.USED));
        assertEquals(2, voucherUsageRepository.countByUserIdAndVoucherId(customer.getId(), voucher.getId()));
    }

    @Test
    void fixedVoucherOnlyReducesMerchandiseAndKeepsShippingSeparate() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = saveUser("fixed-customer-" + suffix, Role.USER);
        Category category = saveCategory("fixed-category-" + suffix);
        Product product = saveProduct("FIXED-" + suffix, saveShop("fixed-shop-" + suffix), category, "100000");
        Carrier carrier = saveCarrier("Fixed carrier " + suffix, "28000");
        Address address = saveAddress(customer);
        Voucher voucher = saveVoucher("FIXED-" + suffix, VoucherType.FIXED, "20000", 2);

        cartService.add(customer, product.getId(), 1);
        OrderService.CheckoutPreview preview = orderService.preview(customer, carrier.getId(), voucher.getCode());
        assertEquals(0, new BigDecimal("20000").compareTo(preview.voucherDiscount()));
        assertEquals(0, preview.shippingDiscount().compareTo(BigDecimal.ZERO));
        assertEquals(0, new BigDecimal("108000").compareTo(preview.totalAmount()));
        CustomerOrder order = orderService.checkout(customer, checkout(address, carrier, voucher)).getFirst();

        assertEquals(0, order.getProductDiscount().compareTo(BigDecimal.ZERO));
        assertEquals(0, new BigDecimal("20000").compareTo(order.getVoucherDiscount()));
        assertEquals(0, order.getShippingDiscount().compareTo(BigDecimal.ZERO));
        assertEquals(0, new BigDecimal("28000").compareTo(order.getFinalShippingFee()));
        assertEquals(0, new BigDecimal("108000").compareTo(order.getTotalAmount()));
    }

    @Test
    void voucherIsFullyAllocatedAfterPromotionAcrossShops() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = saveUser("promo-shops-" + suffix, Role.USER);
        Category category = saveCategory("promo-shops-category-" + suffix);
        Product first = saveProduct("PROMO-A-" + suffix, saveShop("promo-a-" + suffix), category, "100000");
        Product second = saveProduct("PROMO-B-" + suffix, saveShop("promo-b-" + suffix), category, "100000");
        Carrier carrier = saveCarrier("Promotion carrier " + suffix, "10000");
        Address address = saveAddress(customer);
        Voucher voucher = saveVoucher("PROMO-FIXED-" + suffix, VoucherType.FIXED, "50000", 1);
        saveProductPromotion(first, "100");

        cartService.add(customer, first.getId(), 1);
        cartService.add(customer, second.getId(), 1);
        OrderService.CheckoutPreview preview = orderService.preview(customer, carrier.getId(), voucher.getCode());
        assertMoney("200000", preview.subtotal());
        assertMoney("100000", preview.productDiscount());
        assertMoney("50000", preview.voucherDiscount());
        assertMoney("20000", preview.shippingFee());
        assertMoney("70000", preview.totalAmount());

        List<CustomerOrder> orders = orderService.checkout(customer, checkout(address, carrier, voucher));
        assertEquals(2, orders.size());
        CustomerOrder firstOrder = orders.stream()
            .filter(order -> order.getShop().getId().equals(first.getShop().getId())).findFirst().orElseThrow();
        CustomerOrder secondOrder = orders.stream()
            .filter(order -> order.getShop().getId().equals(second.getShop().getId())).findFirst().orElseThrow();
        assertMoney("100000", firstOrder.getProductDiscount());
        assertMoney("0", firstOrder.getVoucherDiscount());
        assertMoney("10000", firstOrder.getTotalAmount());
        assertMoney("0", secondOrder.getProductDiscount());
        assertMoney("50000", secondOrder.getVoucherDiscount());
        assertMoney("60000", secondOrder.getTotalAmount());
        assertMoney("70000", orders.stream().map(CustomerOrder::getTotalAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void productPromotionDiscountsOnlyBasePriceAndVoucherUsesRemainingMerchandise() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = saveUser("promo-options-" + suffix, Role.USER);
        Category category = saveCategory("promo-options-category-" + suffix);
        Product product = saveProduct("PROMO-OPTIONS-" + suffix,
            saveShop("promo-options-shop-" + suffix), category, "30000");
        Carrier carrier = saveCarrier("Option promotion carrier " + suffix, "12000");
        Address address = saveAddress(customer);
        Voucher voucher = saveVoucher("PROMO-PERCENT-" + suffix, VoucherType.PERCENT, "10", 1);
        saveProductPromotion(product, "10");
        ProductSize size = productSizeRepository.findByProductIdAndActiveTrueOrderByDisplayOrderAscIdAsc(product.getId())
            .stream().filter(option -> option.getCode().equals("L")).findFirst().orElseThrow();
        ProductTopping topping = productToppingRepository
            .findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(product.getId()).stream()
            .filter(link -> link.getTopping().getPrice().compareTo(new BigDecimal("10000")) == 0)
            .findFirst().orElseThrow();

        cartService.add(customer, product.getId(), 1, size.getId(),
            DomainEnums.SugarLevel.PERCENT_50, DomainEnums.IceLevel.LESS_ICE,
            List.of(topping.getTopping().getId()));
        assertMoney("27000", productPricingService.price(product).salePrice());
        OrderService.CheckoutPreview preview = orderService.preview(customer, carrier.getId(), voucher.getCode());
        assertMoney("47000", preview.subtotal());
        assertMoney("3000", preview.productDiscount());
        assertMoney("4400", preview.voucherDiscount());
        assertMoney("51600", preview.totalAmount());

        CustomerOrder order = orderService.checkout(customer, checkout(address, carrier, voucher)).getFirst();
        assertMoney("47000", order.getSubtotal());
        assertMoney("3000", order.getProductDiscount());
        assertMoney("4400", order.getVoucherDiscount());
        assertMoney("51600", order.getTotalAmount());
        assertMoney("47000", order.getItems().getFirst().getUnitPrice());
    }

    @Test
    void voucherMinimumIsCheckedAfterProductPromotion() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = saveUser("promo-minimum-" + suffix, Role.USER);
        Category category = saveCategory("promo-minimum-category-" + suffix);
        Product product = saveProduct("PROMO-MINIMUM-" + suffix,
            saveShop("promo-minimum-shop-" + suffix), category, "100000");
        Carrier carrier = saveCarrier("Minimum promotion carrier " + suffix, "10000");
        Voucher voucher = saveVoucher("PROMO-MINIMUM-" + suffix, VoucherType.FIXED, "10000", 1);
        voucher.setMinimumOrder(new BigDecimal("95000"));
        saveProductPromotion(product, "10");
        cartService.add(customer, product.getId(), 1);

        assertThrows(IllegalArgumentException.class,
            () -> orderService.preview(customer, carrier.getId(), voucher.getCode()));
    }

    @Test
    void repricesProductSizeAndToppingAndStopsStaleCheckout() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = saveUser("reprice-customer-" + suffix, Role.USER);
        Category category = saveCategory("reprice-category-" + suffix);
        Product product = saveProduct("REPRICE-" + suffix, saveShop("reprice-shop-" + suffix), category, "50000");
        Carrier carrier = saveCarrier("Reprice carrier " + suffix, "18000");
        Address address = saveAddress(customer);
        ProductSize size = productSizeRepository.findByProductIdAndActiveTrueOrderByDisplayOrderAscIdAsc(product.getId())
            .stream().filter(option -> option.getCode().equals("L")).findFirst().orElseThrow();
        ProductTopping productTopping = productToppingRepository
            .findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(product.getId()).getFirst();
        cartService.add(customer, product.getId(), 1, size.getId(),
            DomainEnums.SugarLevel.PERCENT_50, DomainEnums.IceLevel.LESS_ICE,
            List.of(productTopping.getTopping().getId()));

        product.setPrice(new BigDecimal("60000"));
        size.setExtraPrice(new BigDecimal("9000"));
        productTopping.getTopping().setPrice(new BigDecimal("13000"));
        OrderService.CheckoutPreview preview = orderService.preview(customer, carrier.getId(), null);

        assertEquals(true, preview.pricesChanged());
        assertEquals(List.of(product.getName()), preview.changedProducts());
        assertEquals(0, new BigDecimal("82000").compareTo(preview.subtotal()));

        product.setPrice(new BigDecimal("65000"));
        long orderCount = orderRepository.count();
        assertThrows(OrderService.PriceChangedException.class,
            () -> orderService.checkout(customer, checkout(address, carrier, null)));
        assertEquals(orderCount, orderRepository.count());
        assertEquals(0, new BigDecimal("87000").compareTo(cartService.get(customer).getSubtotal()));
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

    private Shop saveShop(String slug) {
        User owner = saveUser("vendor-" + slug, Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName(slug);
        shop.setSlug(slug);
        shop.setStatus(ShopStatus.APPROVED);
        return shopRepository.save(shop);
    }

    private Category saveCategory(String slug) {
        Category category = new Category();
        category.setName(slug);
        category.setSlug(slug);
        return categoryRepository.save(category);
    }

    private Product saveProduct(String sku, Shop shop, Category category, String price) {
        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName(sku);
        product.setSlug(sku.toLowerCase());
        product.setSku(sku);
        product.setDescription("Sản phẩm kiểm thử voucher.");
        product.setPrice(new BigDecimal(price));
        product.setStock(20);
        product = productRepository.save(product);
        productOptionService.configureDefaults(product);
        return product;
    }

    private Address saveAddress(User user) {
        Address address = new Address();
        address.setUser(user);
        address.setReceiverName("Khách kiểm thử");
        address.setPhone("0901234567");
        address.setProvince("TP. Hồ Chí Minh");
        address.setDistrict("Thủ Đức");
        address.setWard("Linh Trung");
        address.setDetail("01 Võ Văn Ngân");
        return addressRepository.save(address);
    }

    private Carrier saveCarrier(String name, String fee) {
        Carrier carrier = new Carrier();
        carrier.setName(name);
        carrier.setShippingFee(new BigDecimal(fee));
        return carrierRepository.save(carrier);
    }

    private Voucher saveVoucher(String code, VoucherType type, String value, int maxUses) {
        Voucher voucher = new Voucher();
        voucher.setCode(code.toUpperCase());
        voucher.setName(code);
        voucher.setType(type);
        voucher.setDiscountValue(new BigDecimal(value));
        voucher.setMinimumOrder(BigDecimal.ZERO);
        voucher.setQuantity(5);
        voucher.setMaxUsesPerUser(maxUses);
        voucher.setStartsAt(LocalDateTime.now().minusMinutes(1));
        voucher.setEndsAt(LocalDateTime.now().plusDays(1));
        voucher.setActive(true);
        return voucherRepository.save(voucher);
    }

    private Promotion saveProductPromotion(Product product, String percent) {
        Promotion promotion = new Promotion();
        promotion.setName("Promotion " + product.getSku());
        promotion.setShop(product.getShop());
        promotion.setProduct(product);
        promotion.setType(PromotionType.PRODUCT_PERCENT);
        promotion.setDiscountValue(new BigDecimal(percent));
        promotion.setStartsAt(LocalDateTime.now().minusMinutes(1));
        promotion.setEndsAt(LocalDateTime.now().plusDays(1));
        return promotionRepository.save(promotion);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    private CheckoutForm checkout(Address address, Carrier carrier, Voucher voucher) {
        CheckoutForm form = new CheckoutForm();
        form.setAddressId(address.getId());
        form.setCarrierId(carrier.getId());
        form.setVoucherCode(voucher == null ? null : voucher.getCode());
        return form;
    }
}
