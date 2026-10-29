package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.dto.WebForms.PromotionForm;
import vn.iotstar.entity.Address;
import vn.iotstar.entity.Carrier;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.AuditAction;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentMethod;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.AddressRepository;
import vn.iotstar.repository.AuditLogRepository;
import vn.iotstar.repository.CarrierRepository;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ProductSizeRepository;
import vn.iotstar.repository.ProductToppingRepository;
import vn.iotstar.repository.OrderStatusHistoryRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ToppingRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.service.CartService;
import vn.iotstar.service.DashboardService;
import vn.iotstar.service.OrderService;
import vn.iotstar.service.ProductOptionService;
import vn.iotstar.service.VendorService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ProductOptionsTests {
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AddressRepository addressRepository;
    @Autowired private CarrierRepository carrierRepository;
    @Autowired private ProductSizeRepository productSizeRepository;
    @Autowired private ProductToppingRepository productToppingRepository;
    @Autowired private OrderStatusHistoryRepository orderStatusHistoryRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ToppingRepository toppingRepository;
    @Autowired private ProductOptionService productOptionService;
    @Autowired private CartService cartService;
    @Autowired private DashboardService dashboardService;
    @Autowired private OrderService orderService;
    @Autowired private VendorService vendorService;
    @Autowired private AuditLogRepository auditLogRepository;

    @Test
    void separatesVendorAndShipperTransitionsAndLocksInvalidAssignments() {
        assertEquals(List.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
            orderService.vendorTransitions(OrderStatus.NEW));
        assertEquals(List.of(OrderStatus.PREPARING, OrderStatus.CANCELLED),
            orderService.vendorTransitions(OrderStatus.CONFIRMED));
        assertEquals(List.of(OrderStatus.READY_FOR_PICKUP, OrderStatus.CANCELLED),
            orderService.vendorTransitions(OrderStatus.PREPARING));
        assertTrue(orderService.vendorTransitions(OrderStatus.READY_FOR_PICKUP).isEmpty());
        assertEquals(List.of(OrderStatus.PICKED_UP),
            orderService.shipperTransitions(OrderStatus.READY_FOR_PICKUP));
        assertEquals(List.of(OrderStatus.DELIVERED, OrderStatus.DELIVERY_FAILED),
            orderService.shipperTransitions(OrderStatus.SHIPPING));
        assertEquals(List.of(OrderStatus.CANCELLED),
            orderService.managementTransitions(OrderStatus.DELIVERY_FAILED));
        for (OrderStatus stage : List.of(OrderStatus.NEW, OrderStatus.CONFIRMED,
                OrderStatus.PREPARING)) {
            assertEquals(List.of(OrderStatus.CANCELLED), orderService.managementTransitions(stage));
        }
        assertTrue(orderService.managementTransitions(OrderStatus.READY_FOR_PICKUP).isEmpty());
        assertTrue(orderService.shipperTransitions(OrderStatus.CONFIRMED).isEmpty());
        assertTrue(orderService.canAssignShipper(OrderStatus.CONFIRMED));
        assertTrue(orderService.canAssignShipper(OrderStatus.PREPARING));
        assertTrue(orderService.canAssignShipper(OrderStatus.READY_FOR_PICKUP));
        assertEquals(false, orderService.canAssignShipper(OrderStatus.PICKED_UP));
        assertEquals(false, orderService.canAssignShipper(OrderStatus.DELIVERED));
        assertEquals(false, orderService.canAssignShipper(OrderStatus.DELIVERY_FAILED));
        assertEquals(false, orderService.canAssignShipper(OrderStatus.CANCELLED));
        assertEquals(false, orderService.canAssignShipper(OrderStatus.RETURNED_REFUNDED));
    }

    @Test
    void vendorShippingPromotionCannotTargetSingleProduct() {
        PromotionForm form = new PromotionForm();
        form.setType(PromotionType.SHIPPING_DISCOUNT);
        form.setDiscountValue(new BigDecimal("15000"));
        form.setProductId(99L);
        form.setStartsAt(LocalDateTime.now());
        form.setEndsAt(LocalDateTime.now().plusDays(1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> vendorService.validatePromotion(form));
        assertEquals("Khuyến mãi phí vận chuyển không áp dụng riêng cho sản phẩm.", error.getMessage());

        form.setProductId(null);
        vendorService.validatePromotion(form);
    }

    @Test
    void commissionUsesNetMerchandiseAndExcludesShippingFee() {
        CustomerOrder order = new CustomerOrder();
        order.setSubtotal(new BigDecimal("100000"));
        order.setProductDiscount(new BigDecimal("10000"));
        order.setVoucherDiscount(new BigDecimal("5000"));
        order.setFinalShippingFee(new BigDecimal("20000"));
        order.setTotalAmount(new BigDecimal("105000"));

        order.snapshotCommission(new BigDecimal("10"));

        assertEquals(0, new BigDecimal("85000.00").compareTo(order.getCommissionBase()));
        assertEquals(0, new BigDecimal("8500.00").compareTo(order.getCommissionAmount()));
        assertEquals(0, new BigDecimal("76500.00").compareTo(order.getSellerRevenue()));
        assertEquals(0, new BigDecimal("8500.00").compareTo(order.getPlatformRevenue()));
    }

    @Test
    void cartSeparatesConfigurationsAndOrderKeepsTheirPriceSnapshots() {
        User customer = saveUser("option-customer", "option-customer@utetra.test", Role.USER);
        User vendor = saveUser("option-vendor", "option-vendor@utetra.test", Role.VENDOR);
        User shipper = saveUser("option-shipper", "option-shipper@utetra.test", Role.SHIPPER);
        User retryShipper = saveUser("option-retry-shipper", "option-retry-shipper@utetra.test", Role.SHIPPER);
        User admin = saveUser("option-admin", "option-admin@utetra.test", Role.ADMIN);

        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Options Test");
        shop.setSlug("utetra-options-test");
        shop.setStatus(ShopStatus.APPROVED);
        shop = shopRepository.save(shop);

        Category category = new Category();
        category.setName("Trà sữa kiểm thử");
        category.setSlug("tra-sua-kiem-thu-options");
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName("Trà sữa Matcha");
        product.setSlug("tra-sua-matcha-options-test");
        product.setSku("OPTIONS-TEST-001");
        product.setDescription("Sản phẩm dùng để kiểm thử tùy chọn.");
        product.setPrice(new BigDecimal("30000"));
        product.setStock(20);
        product = productRepository.save(product);
        productOptionService.configureDefaults(product);

        var sizes = productSizeRepository.findByProductIdAndActiveTrueOrderByDisplayOrderAscIdAsc(product.getId());
        var sizeM = sizes.stream().filter(size -> size.getCode().equals("M")).findFirst().orElseThrow();
        var sizeL = sizes.stream().filter(size -> size.getCode().equals("L")).findFirst().orElseThrow();
        var whitePearl = toppingRepository.findByNameIgnoreCase("Trân châu trắng").orElseThrow();
        var cheese = toppingRepository.findByNameIgnoreCase("Kem cheese").orElseThrow();

        cartService.add(customer, product.getId(), 1, sizeL.getId(), SugarLevel.PERCENT_50,
            IceLevel.LESS_ICE, List.of(whitePearl.getId(), cheese.getId()));
        cartService.add(customer, product.getId(), 1, sizeM.getId(), SugarLevel.PERCENT_100,
            IceLevel.NORMAL, List.of());

        var cart = cartService.get(customer);
        assertEquals(2, cart.getItems().size());
        assertNotEquals(cart.getItems().get(0).getOptionKey(), cart.getItems().get(1).getOptionKey());
        var configuredLine = cart.getItems().stream().filter(item -> "L".equals(item.getSize())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("32000").compareTo(configuredLine.getOptionPrice()));
        assertEquals(0, new BigDecimal("62000").compareTo(configuredLine.getUnitPrice()));
        assertTrue(configuredLine.getToppings().contains("Trân châu trắng"));
        assertTrue(configuredLine.getToppings().contains("Kem cheese"));

        Address address = new Address();
        address.setUser(customer);
        address.setReceiverName("Khách kiểm thử");
        address.setPhone("0901234567");
        address.setProvince("TP. Hồ Chí Minh");
        address.setDistrict("TP. Thủ Đức");
        address.setWard("Linh Trung");
        address.setDetail("Số 1 Võ Văn Ngân");
        address = addressRepository.save(address);

        Carrier carrier = new Carrier();
        carrier.setName("Nhà vận chuyển kiểm thử options");
        carrier.setShippingFee(new BigDecimal("15000"));
        carrier = carrierRepository.save(carrier);

        CheckoutForm form = new CheckoutForm();
        form.setAddressId(address.getId());
        form.setCarrierId(carrier.getId());
        var order = orderService.checkout(customer, form).getFirst();

        assertEquals(2, order.getItems().size());
        var snapshot = order.getItems().stream().filter(item -> "L".equals(item.getSize())).findFirst().orElseThrow();
        assertEquals(SugarLevel.PERCENT_50, snapshot.getSugarLevel());
        assertEquals(IceLevel.LESS_ICE, snapshot.getIceLevel());
        assertEquals(0, new BigDecimal("30000").compareTo(snapshot.getBasePrice()));
        assertEquals(0, new BigDecimal("32000").compareTo(snapshot.getOptionPrice()));
        assertEquals(0, new BigDecimal("62000").compareTo(snapshot.getUnitPrice()));
        assertEquals(0, new BigDecimal("92000").compareTo(order.getSubtotal()));
        assertEquals(0, new BigDecimal("107000").compareTo(order.getTotalAmount()));
        assertEquals(0, order.getProductDiscount().compareTo(BigDecimal.ZERO));
        assertEquals(0, order.getVoucherDiscount().compareTo(BigDecimal.ZERO));
        assertEquals(0, order.getShippingDiscount().compareTo(BigDecimal.ZERO));
        assertEquals(0, new BigDecimal("15000").compareTo(order.getFinalShippingFee()));
        assertEquals(0, new BigDecimal("5.00").compareTo(order.getCommissionRate()));
        assertEquals(0, new BigDecimal("92000.00").compareTo(order.getCommissionBase()));
        assertEquals(0, new BigDecimal("4600.00").compareTo(order.getCommissionAmount()));
        assertEquals(0, new BigDecimal("4600.00").compareTo(order.getPlatformRevenue()));
        assertEquals(0, new BigDecimal("87400.00").compareTo(order.getSellerRevenue()));

        order.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        order.setPaymentStatus(PaymentStatus.PAID);
        var paidButNotDelivered = orderService.revenueSummary(shop);
        assertEquals(0, paidButNotDelivered.grossRevenue().compareTo(BigDecimal.ZERO));
        assertEquals(0, paidButNotDelivered.sellerRevenue().compareTo(BigDecimal.ZERO));
        assertEquals(0, dashboardService.forVendor(shop).revenue30Days().compareTo(BigDecimal.ZERO));

        var history = orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAscIdAsc(order.getId());
        assertEquals(1, history.size());
        assertEquals(null, history.getFirst().getOldStatus());
        assertEquals(OrderStatus.NEW, history.getFirst().getNewStatus());
        assertEquals(customer.getId(), history.getFirst().getChangedBy().getId());

        orderService.vendorUpdate(vendor, order.getId(), OrderStatus.CONFIRMED);
        history = orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAscIdAsc(order.getId());
        assertEquals(2, history.size());
        assertEquals(OrderStatus.NEW, history.getLast().getOldStatus());
        assertEquals(OrderStatus.CONFIRMED, history.getLast().getNewStatus());
        assertEquals(vendor.getId(), history.getLast().getChangedBy().getId());

        orderService.assignShipper(admin, order.getId(), shipper.getId());
        assertEquals("Shipper này đã được phân công cho đơn hàng.",
            assertThrows(IllegalArgumentException.class,
                () -> orderService.assignShipper(admin, order.getId(), shipper.getId())).getMessage());
        shop.setCommissionRate(new BigDecimal("10.00"));
        orderService.vendorUpdate(vendor, order.getId(), OrderStatus.PREPARING);
        assertThrows(IllegalArgumentException.class,
            () -> orderService.vendorUpdate(vendor, order.getId(), OrderStatus.PICKED_UP));
        orderService.vendorUpdate(vendor, order.getId(), OrderStatus.READY_FOR_PICKUP);
        assertThrows(IllegalArgumentException.class,
            () -> orderService.shipperUpdate(shipper, order.getId(), OrderStatus.PICKED_UP));
        orderService.respondToShipperAssignment(shipper, order.getId(), true, null);
        orderService.shipperUpdate(shipper, order.getId(), OrderStatus.PICKED_UP);
        assertNotNull(order.getPickedUpAt());
        orderService.shipperUpdate(shipper, order.getId(), OrderStatus.SHIPPING);
        assertThrows(IllegalArgumentException.class,
            () -> orderService.shipperUpdate(shipper, order.getId(), OrderStatus.DELIVERED, null, null));
        orderService.shipperUpdate(shipper, order.getId(), OrderStatus.DELIVERED, null,
            proof("delivered.png"));
        assertNotNull(order.getDeliveredAt());
        assertEquals(null, order.getCodCollectedAt());
        assertTrue(order.getDeliveryProofUrl().contains("delivery-proofs"));
        assertTrue(auditLogRepository.findAll().stream().anyMatch(log ->
            log.getAction() == AuditAction.SHIPPER_ASSIGNED && log.getTargetId().equals(order.getId())
                && shipper.getUsername().equals(log.getNewValue())));
        assertTrue(auditLogRepository.findAll().stream().anyMatch(log ->
            log.getAction() == AuditAction.SHIPPER_ORDER_STATUS && log.getTargetId().equals(order.getId())
                && "Đã giao".equals(log.getNewValue()) && shipper.getUsername().equals(log.getActorUsername())));
        assertThrows(IllegalArgumentException.class,
            () -> orderService.assignShipper(admin, order.getId(), shipper.getId()));
        assertEquals(0, new BigDecimal("5.00").compareTo(order.getCommissionRate()));
        assertEquals(0, new BigDecimal("4600.00").compareTo(order.getCommissionAmount()));
        var revenue = orderService.revenueSummary(shop);
        assertEquals(0, new BigDecimal("107000.00").compareTo(revenue.grossRevenue()));
        assertEquals(0, new BigDecimal("4600.00").compareTo(revenue.platformRevenue()));
        assertEquals(0, new BigDecimal("87400.00").compareTo(revenue.sellerRevenue()));
        assertEquals(0, new BigDecimal("87400.00").compareTo(dashboardService.forVendor(shop).revenue30Days()));

        CustomerOrder failedOrder = new CustomerOrder();
        failedOrder.setCode("FAILED-DELIVERY-TEST");
        failedOrder.setUser(customer);
        failedOrder.setShop(shop);
        failedOrder.setCarrier(carrier);
        failedOrder.setShipper(shipper);
        failedOrder.setStatus(OrderStatus.SHIPPING);
        failedOrder.setReceiverName("Khách kiểm thử");
        failedOrder.setReceiverPhone("0901234567");
        failedOrder.setShippingAddress("Số 1 Võ Văn Ngân");
        failedOrder.setSubtotal(new BigDecimal("30000"));
        failedOrder.setTotalAmount(new BigDecimal("30000"));
        failedOrder.snapshotCommission(shop.getCommissionRate());
        failedOrder = orderRepository.save(failedOrder);

        Long failedOrderId = failedOrder.getId();
        assertThrows(IllegalArgumentException.class, () -> orderService.shipperUpdate(
            shipper, failedOrderId, OrderStatus.DELIVERY_FAILED, "Sai", null));
        orderService.shipperUpdate(shipper, failedOrderId, OrderStatus.DELIVERY_FAILED,
            "Khách không nghe máy sau nhiều lần liên hệ", proof("failed.png"));
        assertEquals(OrderStatus.DELIVERY_FAILED, failedOrder.getStatus());
        assertEquals("Khách không nghe máy sau nhiều lần liên hệ", failedOrder.getFailureReason());
        assertNotNull(failedOrder.getDeliveryProofUrl());
        assertEquals(null, failedOrder.getDeliveredAt());

        CustomerOrder failedOrderForRetry = failedOrder;
        assertThrows(IllegalArgumentException.class,
            () -> orderService.retryFailedDelivery(vendor, failedOrderForRetry.getId(), retryShipper.getId()));
        orderService.retryFailedDelivery(admin, failedOrderForRetry.getId(), shipper.getId());
        assertEquals(OrderStatus.PICKED_UP, failedOrder.getStatus());
        assertEquals(shipper.getId(), failedOrder.getShipper().getId());
        assertEquals(null, failedOrder.getFailureReason());
        assertEquals(null, failedOrder.getDeliveryProofUrl());
        assertTrue(orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAscIdAsc(failedOrderId).stream()
            .anyMatch(historyItem -> historyItem.getNewStatus() == OrderStatus.DELIVERY_FAILED
                && historyItem.getDeliveryProofUrl() != null));
        orderService.respondToShipperAssignment(shipper, failedOrder.getId(), true, null);
        orderService.shipperUpdate(shipper, failedOrder.getId(), OrderStatus.SHIPPING);
        orderService.shipperUpdate(shipper, failedOrder.getId(), OrderStatus.DELIVERY_FAILED,
            "Khách vẫn chưa thể nhận hàng", null);
        assertThrows(IllegalArgumentException.class,
            () -> orderService.retryFailedDelivery(admin, failedOrderId, retryShipper.getId()));
        orderService.confirmReturnedToShop(shipper, failedOrder.getId());
        orderService.retryFailedDelivery(admin, failedOrder.getId(), retryShipper.getId());
        assertEquals(OrderStatus.READY_FOR_PICKUP, failedOrder.getStatus());
        assertEquals(retryShipper.getId(), failedOrder.getShipper().getId());
        assertEquals(null, failedOrder.getPickedUpAt());

        orderService.respondToShipperAssignment(retryShipper, failedOrder.getId(), true, null);
        orderService.shipperUpdate(retryShipper, failedOrder.getId(), OrderStatus.PICKED_UP);
        orderService.shipperUpdate(retryShipper, failedOrder.getId(), OrderStatus.SHIPPING);
        orderService.shipperUpdate(retryShipper, failedOrder.getId(), OrderStatus.DELIVERY_FAILED,
            "Khách vẫn không thể nhận hàng trong lần giao lại", null);
        assertThrows(IllegalArgumentException.class,
            () -> orderService.managementUpdate(admin, failedOrderId, OrderStatus.CANCELLED));
        orderService.confirmReturnedToShop(retryShipper, failedOrder.getId());
        orderService.managementUpdate(admin, failedOrder.getId(), OrderStatus.CANCELLED);
        assertEquals(OrderStatus.CANCELLED, failedOrder.getStatus());
        assertTrue(orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAscIdAsc(failedOrder.getId()).stream()
            .anyMatch(item -> item.getOldStatus() == OrderStatus.DELIVERY_FAILED
                && item.getNewStatus() == OrderStatus.READY_FOR_PICKUP));

        ProductForm optionConfig = new ProductForm();
        optionConfig.setSizeMActive(true);
        optionConfig.setSizeMExtraPrice(BigDecimal.ZERO);
        optionConfig.setSizeLActive(false);
        optionConfig.setSizeLExtraPrice(new BigDecimal("9000"));
        optionConfig.setSizeXlActive(true);
        optionConfig.setSizeXlExtraPrice(new BigDecimal("15000"));
        optionConfig.setToppingIds(List.of(whitePearl.getId()));
        productOptionService.saveConfiguration(product, optionConfig);
        productOptionService.configureAllProducts();

        var configuredSizes = productSizeRepository.findByProductIdOrderByDisplayOrderAscIdAsc(product.getId());
        var configuredL = configuredSizes.stream().filter(size -> "L".equals(size.getCode())).findFirst().orElseThrow();
        assertEquals(false, configuredL.isActive());
        assertEquals(0, new BigDecimal("9000").compareTo(configuredL.getExtraPrice()));
        var configuredToppings = productToppingRepository
            .findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(product.getId());
        assertEquals(1, configuredToppings.size());
        assertEquals(whitePearl.getId(), configuredToppings.getFirst().getTopping().getId());
        Long configuredProductId = product.getId();
        assertThrows(IllegalArgumentException.class, () -> cartService.add(customer, configuredProductId, 1,
            sizeL.getId(), SugarLevel.PERCENT_50, IceLevel.LESS_ICE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> cartService.add(customer, configuredProductId, 1,
            sizeM.getId(), SugarLevel.PERCENT_50, IceLevel.LESS_ICE, List.of(cheese.getId())));
    }

    private MockMultipartFile proof(String filename) {
        return new MockMultipartFile("deliveryProof", filename, "image/png", new byte[] {1, 2, 3});
    }

    private User saveUser(String username, String email, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash("encoded-password-for-test");
        user.setFullName(username);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }
}
