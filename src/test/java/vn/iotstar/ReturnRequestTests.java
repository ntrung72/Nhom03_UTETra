package vn.iotstar;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.dto.WebForms.ReturnRequestForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.ReturnRequestService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ReturnRequestTests {
    @Autowired private WebApplicationContext context;
    @Autowired private ReturnRequestService returnRequestService;
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private InventoryTransactionRepository inventoryTransactionRepository;
    @Autowired private ReturnStatusHistoryRepository returnStatusHistoryRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void oldRefundWithoutVerificationDoesNotClaimManualConfirmation() {
        ReturnRequest oldRequest = new ReturnRequest();
        oldRequest.setStatus(ReturnStatus.REFUNDED);
        assertEquals(RefundVerification.LEGACY_UNKNOWN, oldRequest.getRefundVerification());
    }

    @Test
    void unpaidDeliveredOrderCannotRequestRefund() {
        Fixture fixture = fixture();
        fixture.order().setPaymentStatus(PaymentStatus.UNPAID);

        assertThrows(IllegalArgumentException.class, () -> returnRequestService.create(
            fixture.customer(), fixture.order().getId(), form("100000"), evidence()));
    }

    @Test
    void completesFlowOnlyAfterManualRefundIsConfirmed() {
        Fixture fixture = fixture();
        fixture.order().setPaymentMethod(PaymentMethod.VNPAY);
        ReturnRequest request = returnRequestService.create(fixture.customer(), fixture.order().getId(),
            form("100000"), evidence());

        assertEquals(ReturnStatus.REQUESTED, request.getStatus());
        assertEquals(RefundVerification.PENDING, request.getRefundVerification());
        assertEquals(OrderStatus.RETURN_REQUESTED, fixture.order().getStatus());
        assertEquals(1, request.getMedia().size());

        returnRequestService.decide(fixture.vendor(), request.getId(), ReturnStatus.APPROVED,
            new BigDecimal("100000"), "Đồng ý nhận lại sản phẩm.");
        assertEquals(ReturnStatus.APPROVED, request.getStatus());
        assertEquals(new BigDecimal("100000"), request.getRefundAmount());

        returnRequestService.requirePhysicalReturn(fixture.vendor(), request.getId());
        assertEquals(Boolean.TRUE, request.getReturnRequired());
        returnRequestService.markReturning(fixture.customer(), fixture.order().getId());
        assertEquals(ReturnStatus.RETURNING, request.getStatus());
        returnRequestService.vendorTransition(fixture.vendor(), request.getId(), ReturnStatus.RECEIVED,
            "Đã nhận sản phẩm trả về.");
        assertEquals(ReturnStatus.RECEIVED, request.getStatus());
        returnRequestService.vendorTransition(fixture.vendor(), request.getId(), ReturnStatus.REFUND_PENDING,
            "Chuẩn bị hoàn tiền ngoài hệ thống.");

        assertEquals(ReturnStatus.REFUND_PENDING, request.getStatus());
        assertEquals(OrderStatus.RETURN_REQUESTED, fixture.order().getStatus());
        assertEquals(PaymentStatus.PAID, fixture.order().getPaymentStatus());
        assertEquals(10, fixture.product().getStock());
        assertEquals(3, fixture.product().getSoldCount());

        User admin = user("return-admin-" + UUID.randomUUID().toString().substring(0, 8), Role.ADMIN);
        returnRequestService.confirmRefund(admin, request.getId(), "VNPAY-RF-123456",
            "Đã hoàn tiền ngoài hệ thống và đối soát thành công.");

        assertEquals(ReturnStatus.REFUNDED, request.getStatus());
        assertEquals(RefundVerification.MANUAL_CONFIRMED, request.getRefundVerification());
        assertNotEquals(RefundVerification.GATEWAY_CONFIRMED, request.getRefundVerification());
        assertEquals(OrderStatus.RETURNED_REFUNDED, fixture.order().getStatus());
        assertEquals(PaymentStatus.REFUNDED, fixture.order().getPaymentStatus());
        assertEquals(admin.getId(), request.getResolvedBy().getId());
        assertNotNull(request.getResolvedAt());
        assertNotNull(request.getRefundedAt());
        assertEquals("VNPAY-RF-123456", request.getRefundReference());
        assertEquals("Đã hoàn tiền ngoài hệ thống và đối soát thành công.", request.getRefundNote());
        assertEquals(11, fixture.product().getStock());
        assertEquals(2, fixture.product().getSoldCount());
        var movements = inventoryTransactionRepository.findByProductIdOrderByCreatedAtAscIdAsc(fixture.product().getId());
        assertEquals(1, movements.size());
        assertEquals(InventoryTransactionType.RETURN_REFUND, movements.getFirst().getType());
        assertEquals(1, movements.getFirst().getQuantityChange());
        assertEquals(fixture.order().getId(), movements.getFirst().getOrder().getId());
        var history = returnStatusHistoryRepository.findByReturnRequestIdOrderByChangedAtAscIdAsc(request.getId());
        assertEquals(7, history.size());
        assertNull(history.getFirst().getOldStatus());
        assertEquals(ReturnStatus.REQUESTED, history.getFirst().getNewStatus());
        assertEquals(ReturnStatus.REFUND_PENDING, history.getLast().getOldStatus());
        assertEquals(ReturnStatus.REFUNDED, history.getLast().getNewStatus());
        assertTrue(history.getLast().getNote().contains("Admin đã chuyển khoản hoàn tiền"));
    }

    @Test
    void customerMustWaitForShopToChooseWhetherProductIsReturned() {
        Fixture fixture = fixture();
        ReturnRequest request = returnRequestService.create(fixture.customer(), fixture.order().getId(),
            form("100000"), evidence());
        returnRequestService.decide(fixture.vendor(), request.getId(), ReturnStatus.APPROVED,
            new BigDecimal("100000"), "Đồng ý xử lý hoàn tiền cho khách.");

        assertNull(request.getReturnRequired());
        assertThrows(IllegalArgumentException.class,
            () -> returnRequestService.markReturning(fixture.customer(), fixture.order().getId()));
        assertEquals(ReturnStatus.APPROVED, request.getStatus());

        returnRequestService.requirePhysicalReturn(fixture.vendor(), request.getId());
        assertEquals(Boolean.TRUE, request.getReturnRequired());
        assertThrows(IllegalArgumentException.class,
            () -> returnRequestService.vendorTransition(fixture.vendor(), request.getId(),
                ReturnStatus.REFUND_PENDING, "Bỏ qua bước nhận hàng trả."));
        assertEquals(ReturnStatus.APPROVED, request.getStatus());

        returnRequestService.markReturning(fixture.customer(), fixture.order().getId());
        assertEquals(ReturnStatus.RETURNING, request.getStatus());
    }

    @Test
    void customerPageShowsReturnButtonOnlyAfterShopRequestsProduct() throws Exception {
        Fixture fixture = fixture();
        ReturnRequest request = returnRequestService.create(fixture.customer(), fixture.order().getId(),
            form("100000"), evidence());
        returnRequestService.decide(fixture.vendor(), request.getId(), ReturnStatus.APPROVED,
            new BigDecimal("100000"), "Đồng ý xử lý hoàn tiền cho khách.");

        mockMvc.perform(get("/orders/{id}", fixture.order().getId())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(fixture.customer().getUsername()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Vui lòng chờ shop quyết định")))
            .andExpect(content().string(not(containsString("Tôi đã giao sản phẩm trả cho cửa hàng"))));

        mockMvc.perform(get("/vendor/returns/{id}", request.getId())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(fixture.vendor().getUsername()).roles("VENDOR")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Yêu cầu khách trả sản phẩm tại cửa hàng")))
            .andExpect(content().string(containsString("Không cần trả hàng")));

        returnRequestService.requirePhysicalReturn(fixture.vendor(), request.getId());

        mockMvc.perform(get("/orders/{id}", fixture.order().getId())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(fixture.customer().getUsername()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Tôi đã giao sản phẩm trả cho cửa hàng")))
            .andExpect(content().string(containsString("01 Võ Văn Ngân, Thủ Đức")));

        mockMvc.perform(get("/vendor/returns/{id}", request.getId())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                    .user(fixture.vendor().getUsername()).roles("VENDOR")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Đã yêu cầu khách mang sản phẩm")))
            .andExpect(content().string(not(containsString("Không cần trả hàng"))));
    }

    @Test
    void noPhysicalReturnChoiceDoesNotExposeCustomerReturnAction() {
        Fixture fixture = fixture();
        ReturnRequest request = returnRequestService.create(fixture.customer(), fixture.order().getId(),
            form("100000"), evidence());
        returnRequestService.decide(fixture.vendor(), request.getId(), ReturnStatus.APPROVED,
            new BigDecimal("100000"), "Đồng ý xử lý hoàn tiền cho khách.");

        returnRequestService.vendorTransition(fixture.vendor(), request.getId(), ReturnStatus.REFUND_PENDING,
            "Sản phẩm bị đổ, không cần khách trả lại.");

        assertEquals(Boolean.FALSE, request.getReturnRequired());
        assertEquals(ReturnStatus.REFUND_PENDING, request.getStatus());
        assertThrows(IllegalArgumentException.class,
            () -> returnRequestService.markReturning(fixture.customer(), fixture.order().getId()));
    }

    @Test
    void rejectionRestoresDeliveredWithoutCountingSoldAgain() {
        Fixture fixture = fixture();
        ReturnRequest request = returnRequestService.create(fixture.customer(), fixture.order().getId(),
            form("100000"), evidence());
        long soldBefore = fixture.product().getSoldCount();

        returnRequestService.decide(fixture.vendor(), request.getId(), ReturnStatus.REJECTED,
            null, "Bằng chứng không phù hợp.");

        assertEquals(ReturnStatus.REJECTED, request.getStatus());
        assertEquals(OrderStatus.DELIVERED, fixture.order().getStatus());
        assertEquals(PaymentStatus.PAID, fixture.order().getPaymentStatus());
        assertEquals(soldBefore, fixture.product().getSoldCount());
    }

    @Test
    void requiresEvidenceAndRejectsInvalidTransition() {
        Fixture fixture = fixture();
        assertThrows(IllegalArgumentException.class, () -> returnRequestService.create(
            fixture.customer(), fixture.order().getId(), form("100000"), new MockMultipartFile[0]));

        ReturnRequest request = returnRequestService.create(fixture.customer(), fixture.order().getId(),
            form("100000"), evidence());
        assertThrows(IllegalArgumentException.class, () -> returnRequestService.vendorTransition(
            fixture.vendor(), request.getId(), ReturnStatus.REFUNDED, null));
    }

    @Test
    void missingRefundReferenceDoesNotMarkPaymentRefundedOrRestoreStock() {
        Fixture fixture = fixture();
        ReturnRequest request = advanceToRefundPending(fixture);

        assertThrows(IllegalArgumentException.class, () -> returnRequestService.confirmRefund(
            user("return-admin-" + UUID.randomUUID().toString().substring(0, 8), Role.ADMIN),
            request.getId(), "", "Đã hoàn tiền cho khách."));

        assertEquals(ReturnStatus.REFUND_PENDING, request.getStatus());
        assertEquals(OrderStatus.RETURN_REQUESTED, fixture.order().getStatus());
        assertEquals(PaymentStatus.PAID, fixture.order().getPaymentStatus());
        assertEquals(RefundVerification.PENDING, request.getRefundVerification());
        assertEquals(10, fixture.product().getStock());
        assertEquals(3, fixture.product().getSoldCount());
        assertTrue(inventoryTransactionRepository.findByProductIdOrderByCreatedAtAscIdAsc(
            fixture.product().getId()).isEmpty());
    }

    @Test
    void repeatedManualConfirmationCannotRestoreStockOrRecordHistoryTwice() {
        Fixture fixture = fixture();
        ReturnRequest request = advanceToRefundPending(fixture);

        User admin = user("return-admin-" + UUID.randomUUID().toString().substring(0, 8), Role.ADMIN);
        returnRequestService.confirmRefund(admin, request.getId(), "BANK-12345",
            "Đã chuyển khoản hoàn tiền cho khách.");
        assertThrows(IllegalArgumentException.class, () -> returnRequestService.confirmRefund(
            admin, request.getId(), "BANK-12345", "Thử ghi nhận lần hai."));

        assertEquals(11, fixture.product().getStock());
        assertEquals(1, inventoryTransactionRepository.findByProductIdOrderByCreatedAtAscIdAsc(
            fixture.product().getId()).size());
        assertEquals(7, returnStatusHistoryRepository.findByReturnRequestIdOrderByChangedAtAscIdAsc(
            request.getId()).size());
    }

    @Test
    void anotherShopVendorCannotConfirmRefund() {
        Fixture fixture = fixture();
        ReturnRequest request = advanceToRefundPending(fixture);
        User otherVendor = user("other-refund-vendor-" + UUID.randomUUID().toString().substring(0, 8), Role.VENDOR);

        var error = assertThrows(IllegalArgumentException.class, () -> returnRequestService.confirmRefund(
            otherVendor, request.getId(), "BANK-12345", "Đã chuyển khoản hoàn tiền cho khách."));
        assertTrue(error.getMessage().contains("không có quyền"));
        assertEquals(ReturnStatus.REFUND_PENDING, request.getStatus());
        assertEquals(PaymentStatus.PAID, fixture.order().getPaymentStatus());
        assertEquals(10, fixture.product().getStock());
        assertEquals(6, returnStatusHistoryRepository.findByReturnRequestIdOrderByChangedAtAscIdAsc(
            request.getId()).size());
    }

    @Test
    void ownShopVendorCannotConfirmRefund() {
        Fixture fixture = fixture();
        ReturnRequest request = advanceToRefundPending(fixture);
        assertThrows(IllegalArgumentException.class, () -> returnRequestService.confirmRefund(
            fixture.vendor(), request.getId(), "BANK-12345", "Đã chuyển khoản hoàn tiền."));
        assertEquals(ReturnStatus.REFUND_PENDING, request.getStatus());
    }

    @Test
    void requestRequiresSavedBankAndSnapshotsSelection() {
        Fixture fixture = fixture();
        fixture.customer().setBankName(null);
        fixture.customer().setBankAccountNumber(null);
        fixture.customer().setBankAccountName(null);
        assertThrows(IllegalArgumentException.class, () -> returnRequestService.create(
            fixture.customer(), fixture.order().getId(), form("100000"), evidence()));
        fixture.customer().setBankName("BIDV");
        fixture.customer().setBankAccountNumber("123456789");
        fixture.customer().setBankAccountName("TEST CUSTOMER");
        ReturnRequest request = returnRequestService.create(fixture.customer(), fixture.order().getId(),
            form("100000"), evidence());
        fixture.customer().setBankAccountNumber("987654321");
        assertEquals("123456789", request.getRefundAccountNumber());
    }

    @Test
    void existingPendingRequestMustSelectSavedBankBeforeAdminCanConfirm() {
        Fixture fixture = fixture();
        ReturnRequest request = returnRequestService.create(fixture.customer(), fixture.order().getId(),
            form("100000"), evidence());
        request.setRefundBankName(null);
        request.setRefundAccountName(null);
        request.setRefundAccountNumber(null);
        returnRequestService.decide(fixture.vendor(), request.getId(), ReturnStatus.APPROVED,
            new BigDecimal("100000"), "Đồng ý hoàn tiền cho khách.");
        returnRequestService.vendorTransition(fixture.vendor(), request.getId(), ReturnStatus.REFUND_PENDING,
            "Không cần thu hồi sản phẩm.");
        User admin = user("return-admin-" + UUID.randomUUID().toString().substring(0, 8), Role.ADMIN);

        assertThrows(IllegalArgumentException.class, () -> returnRequestService.confirmRefund(
            admin, request.getId(), "BANK-12345", "Đã chuyển khoản hoàn tiền."));
        assertEquals(ReturnStatus.REFUND_PENDING, request.getStatus());
        assertEquals(PaymentStatus.PAID, fixture.order().getPaymentStatus());

        returnRequestService.selectRefundBank(fixture.customer(), fixture.order().getId());
        assertEquals("123456789", request.getRefundAccountNumber());
        returnRequestService.confirmRefund(admin, request.getId(), "BANK-12345",
            "Đã chuyển khoản hoàn tiền.");
        assertEquals(ReturnStatus.REFUNDED, request.getStatus());
        assertEquals(PaymentStatus.REFUNDED, fixture.order().getPaymentStatus());
    }

    @Test
    void refundWithoutPhysicalReturnDoesNotRestoreStock() {
        Fixture fixture = fixture();
        ReturnRequest request = returnRequestService.create(fixture.customer(), fixture.order().getId(),
            form("100000"), evidence());
        returnRequestService.decide(fixture.vendor(), request.getId(), ReturnStatus.APPROVED,
            new BigDecimal("100000"), "Đồng ý hoàn tiền cho sản phẩm bị đổ.");
        returnRequestService.vendorTransition(fixture.vendor(), request.getId(), ReturnStatus.REFUND_PENDING,
            "Không cần khách trả lại sản phẩm đã bị đổ.");

        assertEquals(Boolean.FALSE, request.getReturnRequired());

        User admin = user("return-admin-" + UUID.randomUUID().toString().substring(0, 8), Role.ADMIN);
        returnRequestService.confirmRefund(admin, request.getId(), "BANK-98765",
            "Đã chuyển khoản hoàn tiền cho khách.");

        assertEquals(ReturnStatus.REFUNDED, request.getStatus());
        assertEquals(OrderStatus.RETURNED_REFUNDED, fixture.order().getStatus());
        assertEquals(10, fixture.product().getStock());
        assertEquals(2, fixture.product().getSoldCount());
        assertTrue(inventoryTransactionRepository.findByProductIdOrderByCreatedAtAscIdAsc(
            fixture.product().getId()).isEmpty());
    }

    @Test
    void rejectsPartialRefundBecauseRequestCoversWholeOrder() {
        Fixture fixture = fixture();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> returnRequestService.create(fixture.customer(), fixture.order().getId(),
                form("75000"), evidence()));

        assertTrue(error.getMessage().contains("toàn bộ đơn"));
        assertEquals(OrderStatus.DELIVERED, fixture.order().getStatus());
        assertEquals(10, fixture.product().getStock());
        assertEquals(3, fixture.product().getSoldCount());
    }

    private ReturnRequestForm form(String amount) {
        ReturnRequestForm form = new ReturnRequestForm();
        form.setReason(ReturnReason.DAMAGED);
        form.setDescription("Ly bị đổ và nắp bị vỡ khi tôi nhận sản phẩm từ shipper.");
        form.setRefundAmount(new BigDecimal(amount));
        form.setRefundBankAccount("SAVED");
        return form;
    }

    private ReturnRequest advanceToRefundPending(Fixture fixture) {
        ReturnRequest request = returnRequestService.create(fixture.customer(), fixture.order().getId(),
            form("100000"), evidence());
        returnRequestService.decide(fixture.vendor(), request.getId(), ReturnStatus.APPROVED,
            new BigDecimal("100000"), "Đồng ý nhận lại sản phẩm.");
        returnRequestService.requirePhysicalReturn(fixture.vendor(), request.getId());
        returnRequestService.markReturning(fixture.customer(), fixture.order().getId());
        returnRequestService.vendorTransition(fixture.vendor(), request.getId(), ReturnStatus.RECEIVED,
            "Đã nhận sản phẩm trả về.");
        returnRequestService.vendorTransition(fixture.vendor(), request.getId(), ReturnStatus.REFUND_PENDING,
            "Chuẩn bị hoàn tiền ngoài hệ thống.");
        return request;
    }

    private MockMultipartFile[] evidence() {
        return new MockMultipartFile[] { new MockMultipartFile("evidence", "proof.png", "image/png",
            new byte[] { (byte) 0x89, 0x50, 0x4e, 0x47 }) };
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User customer = user("return-user-" + suffix, Role.USER);
        customer.setBankName("BIDV");
        customer.setBankAccountName("TEST CUSTOMER");
        customer.setBankAccountNumber("123456789");
        userRepository.save(customer);
        User vendor = user("return-vendor-" + suffix, Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("Return shop " + suffix);
        shop.setSlug("return-shop-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop.setAddress("01 Võ Văn Ngân");
        shop.setDistrict("Thủ Đức");
        shop = shopRepository.save(shop);
        Category category = new Category();
        category.setName("Return category " + suffix);
        category.setSlug("return-category-" + suffix);
        category = categoryRepository.save(category);
        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName("Return product " + suffix);
        product.setSlug("return-product-" + suffix);
        product.setSku("RET-" + suffix);
        product.setDescription("Sản phẩm kiểm thử quy trình hoàn trả.");
        product.setPrice(new BigDecimal("100000"));
        product.setStock(10);
        product.setSoldCount(3);
        product = productRepository.save(product);

        CustomerOrder order = new CustomerOrder();
        order.setCode("RETURN-" + suffix);
        order.setUser(customer);
        order.setShop(shop);
        order.setStatus(OrderStatus.DELIVERED);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setReceiverName(customer.getFullName());
        order.setReceiverPhone("0901234567");
        order.setShippingAddress("01 Võ Văn Ngân, Thủ Đức");
        order.setSubtotal(new BigDecimal("100000"));
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setProductDiscount(BigDecimal.ZERO);
        order.setVoucherDiscount(BigDecimal.ZERO);
        order.setShippingFee(BigDecimal.ZERO);
        order.setShippingDiscount(BigDecimal.ZERO);
        order.setFinalShippingFee(BigDecimal.ZERO);
        order.setTotalAmount(new BigDecimal("100000"));
        order.snapshotCommission(BigDecimal.ZERO);
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setProductName(product.getName());
        item.setBasePrice(product.getPrice());
        item.setOptionPrice(BigDecimal.ZERO);
        item.setUnitPrice(product.getPrice());
        item.setQuantity(1);
        item.setLineTotal(product.getPrice());
        order.getItems().add(item);
        order = orderRepository.save(order);
        return new Fixture(customer, vendor, product, order);
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

    private record Fixture(User customer, User vendor, Product product, CustomerOrder order) {}
}
