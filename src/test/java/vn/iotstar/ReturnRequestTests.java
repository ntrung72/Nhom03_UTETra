package vn.iotstar;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.dto.WebForms.ReturnRequestForm;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.entity.InventoryTransaction;
import vn.iotstar.entity.ManagerShop;
import vn.iotstar.entity.OrderStatusHistory;
import vn.iotstar.entity.ReturnRequest;
import vn.iotstar.entity.ReturnRequestMedia;
import vn.iotstar.repository.InventoryTransactionRepository;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.ReturnRequestRepository;
import vn.iotstar.service.MediaStorageService;
import vn.iotstar.service.NotificationService;
import vn.iotstar.service.ReturnRequestService;
import vn.iotstar.support.MediaTestFiles;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReturnRequestTests extends CheckoutTestFixtures {

    // MP4 thật, 16 x 16 pixel, một frame.
    private static final byte[] PROOF_MP4 = Base64.getDecoder()
        .decode(
            "AAAAHGZ0eXBpc29tAAACAGlzb21pc28ybXA0MQAAAAhmcmVlAAAAHG1kYXQAAAGzABAHAAABthYFGFBtgtAoPwAAAwttb292"
            + "AAAAbG12aGQAAAAAAAAAAAAAAAAAAAPoAAAD6AABAAABAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAA"
            + "AAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAACWnRyYWsAAABcdGtoZAAAAAMAAAAAAAAAAAAAAAEAAAAA"
            + "AAAD6AAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAEAAAABAAAAAAACRlZHRz"
            + "AAAAHGVsc3QAAAAAAAAAAQAAA+gAAAAAAAEAAAAAAdJtZGlhAAAAIG1kaGQAAAAAAAAAAAAAAAAAAEAAAABAAFXEAAAAAAAt"
            + "aGRscgAAAAAAAAAAdmlkZQAAAAAAAAAAAAAAAFZpZGVvSGFuZGxlcgAAAAF9bWluZgAAABR2bWhkAAAAAQAAAAAAAAAAAAAA"
            + "JGRpbmYAAAAcZHJlZgAAAAAAAAABAAAADHVybCAAAAABAAABPXN0YmwAAADZc3RzZAAAAAAAAAABAAAAyW1wNHYAAAAAAAAA"
            + "AQAAAAAAAAAAAAAAAAAAAAAAEAAQAEgAAABIAAAAAAAAAAEKTGF2YyBtcGVnNAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAY//8A"
            + "AABPZXNkcwAAAAADgICAPgABAASAgIAwIBEAAAAAAw1AAAAAoAWAgIAeAAABsAEAAAG1iRMAAAEAAAABIADEjYgADQCEAhRj"
            + "BoCAgAECAAAAEHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAADDUAAAACgAAAAGHN0dHMAAAAAAAAAAQAAAAEAAEAAAAAAHHN0"
            + "c2MAAAAAAAAAAQAAAAEAAAABAAAAAQAAABRzdHN6AAAAAAAAABQAAAABAAAAFHN0Y28AAAAAAAAAAQAAACwAAAA9dWR0YQAA"
            + "ADVtZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAAAhpbHN0"
        );

    @Autowired
    ReturnRequestService returns;

    @Autowired
    ReturnRequestRepository returnRequests;

    @Autowired
    InventoryTransactionRepository inventoryTransactions;

    @Autowired
    ManagerShopRepository managerShops;

    @Autowired
    NotificationService notifications;

    @MockitoBean
    MediaStorageService mediaStorage;

    @BeforeEach
    void returnFixtures() {
        other.setRole(Role.VENDOR);
        other.setEnabled(true);
        other.setLocked(false);
        users.saveAndFlush(other);

        when(mediaStorage.upload(any(MultipartFile.class), eq("returns")))
            .thenAnswer(invocation -> {
                MultipartFile file = invocation.getArgument(0);
                return "private:returns/test-" + file.getOriginalFilename();
            });
    }

    @Test
    void completeFlowRefundsPaymentAndStockOnlyAtFinalConfirmation() {
        var order = deliveredOrder(2);
        long customerNotifications = notifications.unreadCount(customer);
        long vendorNotifications = notifications.unreadCount(other);

        var request = open(order);

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(tea.getStock()).isEqualTo(8);
        assertThat(tea.getSoldCount()).isEqualTo(2);

        approve(request);
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.APPROVED);

        returns.markReturning(customer, order.getId());
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.RETURNING);

        returns.vendorTransition(
            other,
            request.getId(),
            ReturnStatus.RECEIVED,
            "Đã nhận đủ sản phẩm trả về"
        );
        assertThat(request.getStatus()).isEqualTo(ReturnStatus.RECEIVED);

        returns.vendorTransition(
            other,
            request.getId(),
            ReturnStatus.REFUND_PENDING,
            "Chuẩn bị chuyển khoản hoàn tiền cho khách"
        );

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REFUND_PENDING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURN_REQUESTED);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(tea.getStock()).isEqualTo(8);
        assertThat(refundMovements()).isEmpty();

        returns.confirmRefund(
            other,
            request.getId(),
            "BANK-RETURN-001",
            "Đã chuyển khoản và đối soát thành công"
        );

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.RETURNED_REFUNDED);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(tea.getStock()).isEqualTo(10);
        assertThat(tea.getSoldCount()).isZero();

        assertThat(request.getRefundReference()).isEqualTo("BANK-RETURN-001");
        assertThat(request.getRefundedAt()).isNotNull();
        assertThat(request.getResolvedBy().getId()).isEqualTo(other.getId());

        assertThat(refundMovements()).hasSize(1);

        var movement = refundMovements().getFirst();
        assertThat(movement.getQuantityChange()).isEqualTo(2);
        assertThat(movement.getStockBefore()).isEqualTo(8);
        assertThat(movement.getStockAfter()).isEqualTo(10);
        assertThat(movement.getOrder().getId()).isEqualTo(order.getId());

        assertThat(histories.findByOrderIdOrderByChangedAtAscIdAsc(order.getId()))
            .extracting(OrderStatusHistory::getNewStatus)
            .endsWith(
                OrderStatus.RETURN_REQUESTED,
                OrderStatus.RETURNED_REFUNDED
            );

        assertThat(notifications.unreadCount(customer))
            .isGreaterThan(customerNotifications);

        assertThat(notifications.unreadCount(other))
            .isGreaterThan(vendorNotifications);
    }

    @Test
    void repeatedRefundDoesNotRestoreStockOrWriteHistoryAgain() {
        var order = deliveredOrder(2);
        var request = pending(order);

        returns.confirmRefund(
            other,
            request.getId(),
            "BANK-ONCE-001",
            "Đã hoàn đủ tiền cho khách hàng"
        );

        int historyCount = histories
            .findByOrderIdOrderByChangedAtAscIdAsc(order.getId())
            .size();

        assertThatThrownBy(() -> returns.confirmRefund(
            other,
            request.getId(),
            "BANK-ONCE-002",
            "Thử xác nhận hoàn tiền lần thứ hai"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(tea.getStock()).isEqualTo(10);
        assertThat(tea.getSoldCount()).isZero();
        assertThat(refundMovements()).hasSize(1);
        assertThat(request.getRefundReference()).isEqualTo("BANK-ONCE-001");

        assertThat(histories.findByOrderIdOrderByChangedAtAscIdAsc(order.getId()))
            .hasSize(historyCount);
    }

    @Test
    void rejectionRestoresDeliveredWithoutChangingStockOrSoldCount() {
        var order = deliveredOrder(2);
        var request = open(order);

        returns.decide(
            other,
            request.getId(),
            ReturnStatus.REJECTED,
            null,
            "Bằng chứng chưa chứng minh được lỗi sản phẩm"
        );

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REJECTED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(tea.getStock()).isEqualTo(8);
        assertThat(tea.getSoldCount()).isEqualTo(2);
        assertThat(refundMovements()).isEmpty();

        assertThatThrownBy(() -> open(order))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyDeliveredAndPaidOrdersMayOpenReturn() {
        var newOrder = buy(1);

        assertThatThrownBy(() -> open(newOrder))
            .isInstanceOf(IllegalArgumentException.class);

        var unpaidOrder = deliveredOrder(1);
        unpaidOrder.setPaymentStatus(PaymentStatus.UNPAID);
        orderRepository.saveAndFlush(unpaidOrder);

        assertThatThrownBy(() -> open(unpaidOrder))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(returnRequests.existsByOrderId(newOrder.getId())).isFalse();
        assertThat(returnRequests.existsByOrderId(unpaidOrder.getId())).isFalse();

        verify(mediaStorage, never())
            .upload(any(MultipartFile.class), anyString());
    }

    @Test
    void invalidFormAmountAndEvidenceAreRejectedBeforeUpload() {
        var order = deliveredOrder(1);

        for (var amount : List.of(
                BigDecimal.ZERO,
                order.getTotalAmount().subtract(BigDecimal.ONE),
                order.getTotalAmount().add(BigDecimal.ONE),
                new BigDecimal("1.001"))) {

            var invalid = requestForm(order);
            invalid.setRefundAmount(amount);

            assertThatThrownBy(() -> returns.create(
                customer, order.getId(), invalid, evidence()
            )).isInstanceOf(IllegalArgumentException.class);
        }

        var shortDescription = requestForm(order);
        shortDescription.setDescription("Ngắn");

        assertThatThrownBy(() -> returns.create(
            customer, order.getId(), shortDescription, evidence()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> returns.create(
            customer,
            order.getId(),
            requestForm(order),
            new MultipartFile[0]
        )).isInstanceOf(IllegalArgumentException.class);

        var invalidFile = new MockMultipartFile(
            "evidence",
            "proof.exe",
            "application/octet-stream",
            new byte[] {1, 2, 3}
        );

        assertThatThrownBy(() -> returns.create(
            customer,
            order.getId(),
            requestForm(order),
            new MultipartFile[] {invalidFile}
        )).isInstanceOf(IllegalArgumentException.class);

        var image = evidence()[0];

        assertThatThrownBy(() -> returns.create(
            customer,
            order.getId(),
            requestForm(order),
            new MultipartFile[] {image, image, image, image, image, image}
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(returnRequests.existsByOrderId(order.getId())).isFalse();
        assertThat(refundMovements()).isEmpty();

        verify(mediaStorage, never())
            .upload(any(MultipartFile.class), anyString());
    }

    @Test
    void imageAndVideoEvidenceAreSavedWithTheirTypes() {
        var order = deliveredOrder(1);
        var image = evidence()[0];

        var video = new MockMultipartFile(
            "evidence", "proof.mp4", "video/mp4", PROOF_MP4.clone()
        );

        var request = returns.create(
            customer,
            order.getId(),
            requestForm(order),
            new MultipartFile[] {image, video}
        );

        var saved = returns.getForShop(other, request.getId());

        assertThat(saved.getMedia())
            .extracting(ReturnRequestMedia::getType)
            .containsExactly(MediaType.IMAGE, MediaType.VIDEO);

        assertThat(saved.getMedia()).allSatisfy(media -> {
            assertThat(media.getId()).isNotNull();
            assertThat(media.getUrl()).startsWith("private:returns/");
            assertThat(media.getReturnRequest().getId())
                .isEqualTo(request.getId());
        });

        verify(mediaStorage, times(2))
            .upload(any(MultipartFile.class), eq("returns"));
    }

    @Test
    void anotherBuyerCannotCreateReadOrMarkReturning() {
        var order = deliveredOrder(1);
        var stranger = createUser();

        assertHttp(
            HttpStatus.NOT_FOUND,
            () -> returns.create(
                stranger, order.getId(), requestForm(order), evidence()
            )
        );

        var request = open(order);
        approve(request);

        assertHttp(
            HttpStatus.NOT_FOUND,
            () -> returns.forOrder(stranger, order.getId())
        );

        assertHttp(
            HttpStatus.NOT_FOUND,
            () -> returns.getForUser(stranger, order.getId())
        );

        assertHttp(
            HttpStatus.NOT_FOUND,
            () -> returns.markReturning(stranger, order.getId())
        );

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.APPROVED);
    }

    @Test
    void anotherVendorCannotReadOrProcessForeignReturn() {
        var order = deliveredOrder(1);
        var request = open(order);
        var outsider = secondShopProduct().getShop().getOwner();

        outsider.setRole(Role.VENDOR);
        users.saveAndFlush(outsider);

        assertThat(returns.forShop(outsider, null, 0).getContent()).isEmpty();
        assertThat(returns.forOrders(outsider, List.of(order.getId()))).isEmpty();

        assertHttp(
            HttpStatus.NOT_FOUND,
            () -> returns.getForShop(outsider, request.getId())
        );

        assertHttp(
            HttpStatus.NOT_FOUND,
            () -> returns.decide(
                outsider,
                request.getId(),
                ReturnStatus.APPROVED,
                order.getTotalAmount(),
                "Thử duyệt yêu cầu của shop khác"
            )
        );

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(tea.getStock()).isEqualTo(9);
    }

    @Test
    void managerNeedsShopAssignmentAndCannotConfirmFinalRefund() {
        var order = deliveredOrder(1);
        var request = pending(order);

        var manager = createUser();
        manager.setRole(Role.MANAGER);
        users.saveAndFlush(manager);

        assertHttp(
            HttpStatus.NOT_FOUND,
            () -> returns.getForShop(manager, request.getId())
        );

        var admin = createUser();
        admin.setRole(Role.ADMIN);
        users.saveAndFlush(admin);

        var assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(order.getShop());
        assignment.setAssignedBy(admin);
        managerShops.saveAndFlush(assignment);

        assertThat(returns.getForShop(manager, request.getId()).getId())
            .isEqualTo(request.getId());

        assertHttp(
            HttpStatus.FORBIDDEN,
            () -> returns.confirmRefund(
                manager,
                request.getId(),
                "BANK-MANAGER-001",
                "Manager thử xác nhận hoàn tiền"
            )
        );

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REFUND_PENDING);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(tea.getStock()).isEqualTo(9);

        returns.confirmRefund(
            admin,
            request.getId(),
            "BANK-ADMIN-001",
            "Admin đã xác nhận chuyển khoản hoàn tiền"
        );

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(request.getResolvedBy().getId()).isEqualTo(admin.getId());
        assertThat(tea.getStock()).isEqualTo(10);
    }

    @Test
    void transitionsCannotSkipStepsAndRefundNeedsReferenceAndNote() {
        var order = deliveredOrder(1);
        var request = open(order);

        assertThatThrownBy(() -> returns.vendorTransition(
            other, request.getId(), ReturnStatus.RECEIVED, null
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            returns.markReturning(customer, order.getId())
        ).isInstanceOf(IllegalArgumentException.class);

        approve(request);
        returns.markReturning(customer, order.getId());

        returns.vendorTransition(
            other,
            request.getId(),
            ReturnStatus.RECEIVED,
            "Đã nhận hàng khách gửi trả"
        );

        returns.vendorTransition(
            other,
            request.getId(),
            ReturnStatus.REFUND_PENDING,
            "Chuẩn bị hoàn tiền qua ngân hàng"
        );

        assertThatThrownBy(() -> returns.confirmRefund(
            other,
            request.getId(),
            "",
            "Đã hoàn tiền cho khách hàng"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> returns.confirmRefund(
            other,
            request.getId(),
            "BANK-INVALID-001",
            "abc"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REFUND_PENDING);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(tea.getStock()).isEqualTo(9);
        assertThat(refundMovements()).isEmpty();
    }

    @Test
    void returnDoesNotReleaseVoucherUsage() {
        var voucher = voucher(VoucherType.FIXED, "10000");
        var checkout = form(add(tea, medium, 1).getId());
        checkout.setVoucherCode(voucher.getCode());

        var order = orders.checkout(customer, checkout).getFirst();
        markDelivered(order, 1);

        int remaining = voucher.getQuantity();
        assertThat(remaining).isEqualTo(2);

        var request = pending(order);

        returns.confirmRefund(
            other,
            request.getId(),
            "BANK-VOUCHER-001",
            "Đã hoàn tiền đơn hàng có sử dụng voucher"
        );

        var usage = usages.findAll().stream()
            .filter(value ->
                order.getCheckoutReference().equals(value.getCheckoutReference())
            )
            .findFirst()
            .orElseThrow();

        assertThat(voucher.getQuantity()).isEqualTo(remaining);
        assertThat(usage.getStatus()).isEqualTo(VoucherUsageStatus.USED);
        assertThat(usage.getReleasedAt()).isNull();
    }

    @Test
    void vendorPagesRenderEmptyListEvidenceAndEveryWorkflowState() throws Exception {
        mvc.perform(
                get("/vendor/returns")
                    .with(user(other.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk())
            .andExpect(view().name("vendor/returns"))
            .andExpect(content().string(
                containsString("Chưa có yêu cầu trả hàng")
            ));

        var order = deliveredOrder(1);
        var request = open(order);

        mvc.perform(
                get("/vendor/returns")
                    .with(user(other.getUsername()).roles("VENDOR"))
                    .param("status", "REQUESTED"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(order.getCode())));

        assertThat(returns.forShop(other, ReturnStatus.REJECTED, 0)).isEmpty();
        assertThat(returns.forShop(other, null, -1).getNumber()).isZero();

        renderDetail(request, "Chấp nhận trả hàng");

        approve(request);
        renderDetail(request, "Đang chờ khách xác nhận");

        returns.markReturning(customer, order.getId());
        renderDetail(request, "Xác nhận đã nhận hàng");

        returns.vendorTransition(
            other,
            request.getId(),
            ReturnStatus.RECEIVED,
            "Đã nhận hàng trả về"
        );
        renderDetail(request, "Chuyển sang chờ hoàn tiền");

        returns.vendorTransition(
            other,
            request.getId(),
            ReturnStatus.REFUND_PENDING,
            "Sẽ hoàn qua tài khoản ngân hàng"
        );
        renderDetail(request, "Xác nhận tiền đã hoàn");

        returns.confirmRefund(
            other,
            request.getId(),
            "BANK-RENDER-001",
            "Đã chuyển khoản hoàn tiền cho khách"
        );
        renderDetail(request, "BANK-RENDER-001");
    }

    @Test
    void decisionPostRequiresCsrfAndValidatesDecision() throws Exception {
        var order = deliveredOrder(1);
        var request = open(order);
        var endpoint = "/vendor/returns/" + request.getId() + "/decision";

        mvc.perform(
                post(endpoint)
                    .with(user(other.getUsername()).roles("VENDOR"))
                    .param("decision", "APPROVED"))
            .andExpect(status().isForbidden());

        mvc.perform(
                post(endpoint)
                    .with(user(other.getUsername()).roles("VENDOR"))
                    .with(csrf())
                    .param("decision", "INVALID"))
            .andExpect(status().isBadRequest());

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REQUESTED);

        mvc.perform(
                post(endpoint)
                    .with(user(other.getUsername()).roles("VENDOR"))
                    .with(csrf())
                    .param("decision", "APPROVED")
                    .param(
                        "refundAmount",
                        order.getTotalAmount().toPlainString()
                    )
                    .param("note", "Đồng ý nhận hàng trả qua giao diện"))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attributeExists("success"))
            .andExpect(redirectedUrl("/vendor/returns/" + request.getId()));

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.APPROVED);
    }

    @Test
    void vendorRoutesBlockForeignShopAndCustomer() throws Exception {
        var order = deliveredOrder(1);
        var request = open(order);
        var outsider = secondShopProduct().getShop().getOwner();

        outsider.setRole(Role.VENDOR);
        users.saveAndFlush(outsider);

        mvc.perform(
                get("/vendor/returns/" + request.getId())
                    .with(user(outsider.getUsername()).roles("VENDOR")))
            .andExpect(status().isNotFound());

        mvc.perform(
                post("/vendor/returns/" + request.getId() + "/decision")
                    .with(user(outsider.getUsername()).roles("VENDOR"))
                    .with(csrf())
                    .param("decision", "REJECTED")
                    .param("note", "Thử xử lý yêu cầu của cửa hàng khác"))
            .andExpect(status().isNotFound());

        mvc.perform(
                get("/vendor/returns")
                    .with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/user/profile"));

        assertThat(request.getStatus()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(tea.getStock()).isEqualTo(9);
    }

    private CustomerOrder buy(int quantity) {
        return orders.checkout(
            customer,
            form(add(tea, medium, quantity).getId())
        ).getFirst();
    }

    private CustomerOrder deliveredOrder(int quantity) {
        var order = buy(quantity);
        markDelivered(order, quantity);
        return order;
    }

    private void markDelivered(CustomerOrder order, int quantity) {
        order.setStatus(OrderStatus.DELIVERED);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setDeliveredAt(LocalDateTime.now());

        tea.setSoldCount(tea.getSoldCount() + quantity);
        products.saveAndFlush(tea);
        orderRepository.saveAndFlush(order);
    }

    private ReturnRequestForm requestForm(CustomerOrder order) {
        var form = new ReturnRequestForm();
        form.setReason(ReturnReason.DAMAGED);
        form.setDescription(
            "Ly bị đổ và nắp bị vỡ khi tôi nhận sản phẩm từ shipper."
        );
        form.setRefundAmount(order.getTotalAmount());
        return form;
    }

    private MultipartFile[] evidence() {
        return new MultipartFile[] {
            MediaTestFiles.image("evidence", "proof.png")
        };
    }

    private ReturnRequest open(CustomerOrder order) {
        return returns.create(
            customer,
            order.getId(),
            requestForm(order),
            evidence()
        );
    }

    private void approve(ReturnRequest request) {
        returns.decide(
            other,
            request.getId(),
            ReturnStatus.APPROVED,
            request.getOrder().getTotalAmount(),
            "Đồng ý nhận lại đầy đủ sản phẩm"
        );
    }

    private ReturnRequest pending(CustomerOrder order) {
        var request = open(order);
        approve(request);

        returns.markReturning(customer, order.getId());

        returns.vendorTransition(
            other,
            request.getId(),
            ReturnStatus.RECEIVED,
            "Đã nhận đủ hàng trả"
        );

        returns.vendorTransition(
            other,
            request.getId(),
            ReturnStatus.REFUND_PENDING,
            "Chuẩn bị hoàn tiền qua ngân hàng"
        );

        return request;
    }

    private List<InventoryTransaction> refundMovements() {
        return inventoryTransactions
            .findByProductIdOrderByCreatedAtAscIdAsc(tea.getId())
            .stream()
            .filter(value ->
                value.getType() == InventoryTransactionType.RETURN_REFUND
            )
            .toList();
    }

    private void renderDetail(
            ReturnRequest request,
            String expectedText) throws Exception {

        String html = mvc.perform(
                get("/vendor/returns/" + request.getId())
                    .with(user(other.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk())
            .andExpect(view().name("vendor/return-detail"))
            .andExpect(model().attributeExists(
                "returnRequest", "shop", "canConfirmRefund"
            ))
            .andExpect(content().string(containsString(expectedText)))
            .andReturn().getResponse().getContentAsString();

        assertThat(request.getMedia()).isNotEmpty();

        assertThat(html).doesNotContain(
            "private:returns/",
            "/uploads/returns/"
        );

        for (ReturnRequestMedia media : request.getMedia()) {
            assertThat(media.getId()).isNotNull();

            String evidenceUrl = "/private-media/returns/" + media.getId();

            assertThat(html).contains(
                "href=\"" + evidenceUrl + "\"",
                "src=\"" + evidenceUrl + "\""
            );
        }
    }

    private void assertHttp(HttpStatus expected, Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode())
                    .isEqualTo(expected)
            );
    }
}