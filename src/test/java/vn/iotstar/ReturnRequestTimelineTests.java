package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.dto.WebForms.ReturnRequestForm;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.InventoryTransactionType;
import vn.iotstar.entity.DomainEnums.LoyaltyPointType;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentMethod;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.ReturnReason;
import vn.iotstar.entity.DomainEnums.ReturnStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.InventoryTransaction;
import vn.iotstar.entity.ManagerShop;
import vn.iotstar.entity.ReturnRequest;
import vn.iotstar.entity.ReturnRequest.RefundVerification;
import vn.iotstar.entity.ReturnStatusHistory;
import vn.iotstar.entity.User;
import vn.iotstar.repository.InventoryTransactionRepository;
import vn.iotstar.repository.LoyaltyPointTransactionRepository;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.ReturnRequestRepository;
import vn.iotstar.repository.ReturnStatusHistoryRepository;
import vn.iotstar.service.LoyaltyPointService;
import vn.iotstar.service.MediaStorageService;
import vn.iotstar.service.ReturnRequestService;
import vn.iotstar.support.MediaTestFiles;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReturnRequestTimelineTests extends CheckoutTestFixtures {

    @Autowired ReturnRequestService returns;
    @Autowired ReturnRequestRepository returnRequests;
    @Autowired ReturnStatusHistoryRepository returnHistory;
    @Autowired InventoryTransactionRepository inventoryTransactions;
    @Autowired ManagerShopRepository managerShops;
    @Autowired LoyaltyPointService loyalty;
    @Autowired LoyaltyPointTransactionRepository loyaltyTransactions;

    @MockitoBean
    MediaStorageService mediaStorage;

    @PersistenceContext
    EntityManager entityManager;

    @BeforeEach
    void timelineFixtures() {
        other.setRole(Role.VENDOR);
        other.setEnabled(true);
        other.setLocked(false);
        other.setFullName("Người bán kiểm thử timeline");
        users.saveAndFlush(other);

        when(mediaStorage.upload(any(MultipartFile.class), eq("returns")))
            .thenReturn("private:returns/timeline-proof.png");
    }

    @Test
    void completeFlowPersistsEveryTransitionAndActor() {
        var order = deliveredOrder(2, PaymentMethod.COD);
        var request = pending(order);

        returns.confirmRefund(
            other, request.getId(),
            "BANK-TIMELINE-001", "Đã hoàn đủ tiền cho khách"
        );

        reload();

        var entries = entries(request.getId());

        assertThat(entries).extracting(ReturnStatusHistory::getNewStatus)
            .containsExactly(
                ReturnStatus.REQUESTED,
                ReturnStatus.APPROVED,
                ReturnStatus.RETURNING,
                ReturnStatus.RECEIVED,
                ReturnStatus.REFUND_PENDING,
                ReturnStatus.REFUNDED
            );

        assertThat(entries).extracting(ReturnStatusHistory::getOldStatus)
            .containsExactly(
                null,
                ReturnStatus.REQUESTED,
                ReturnStatus.APPROVED,
                ReturnStatus.RETURNING,
                ReturnStatus.RECEIVED,
                ReturnStatus.REFUND_PENDING
            );

        assertThat(entries).extracting(entry -> entry.getChangedBy().getId())
            .containsExactly(
                customer.getId(), other.getId(), customer.getId(),
                other.getId(), other.getId(), other.getId()
            );

        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.getId()).isNotNull();
            assertThat(entry.getChangedAt()).isNotNull();
            assertThat(entry.getReturnRequest().getId())
                .isEqualTo(request.getId());
        });

        assertThat(entries)
            .extracting(ReturnStatusHistory::getChangedAt)
            .isSorted();

        var stored = returnRequests.findById(request.getId()).orElseThrow();

        assertThat(entries.getFirst().getChangedAt())
            .isEqualTo(stored.getRequestedAt());
        assertThat(entries.getLast().getChangedAt())
            .isEqualTo(stored.getRefundedAt());
        assertThat(stored.getRefundReference())
            .isEqualTo("BANK-TIMELINE-001");
        assertThat(stored.getRefundVerification())
            .isEqualTo(RefundVerification.MANUAL_CONFIRMED);
    }

    @Test
    void rejectionKeepsCreationAndRejectionWithoutRefundingStock() {
        var order = deliveredOrder(1, PaymentMethod.COD);
        var request = open(order);
        String note = "Bằng chứng chưa đủ để chấp nhận trả hàng";

        returns.decide(
            other, request.getId(), ReturnStatus.REJECTED, null, note
        );

        reload();

        assertThat(entries(request.getId()))
            .extracting(ReturnStatusHistory::getNewStatus)
            .containsExactly(
                ReturnStatus.REQUESTED,
                ReturnStatus.REJECTED
            );

        assertThat(entries(request.getId()).getLast().getNote())
            .isEqualTo(note);
        assertThat(orderRepository.findById(order.getId())
            .orElseThrow().getStatus())
            .isEqualTo(OrderStatus.DELIVERED);
        assertThat(products.findById(tea.getId()).orElseThrow().getStock())
            .isEqualTo(9);
        assertThat(refundMovements(order.getId())).isEmpty();

        assertThat(loyaltyTransactions.existsByOrderIdAndType(
            order.getId(), LoyaltyPointType.REFUND_REVERSAL
        )).isFalse();
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    void manualRefundNeverClaimsGatewayConfirmation(PaymentMethod method) {
        var order = deliveredOrder(1, method);
        var request = pending(order);

        returns.confirmRefund(
            other, request.getId(),
            "MANUAL-" + method.name(),
            "Người bán đã đối soát khoản hoàn ngoài hệ thống"
        );

        reload();

        var stored = returnRequests.findById(request.getId()).orElseThrow();

        assertThat(stored.getRefundVerification())
            .isEqualTo(RefundVerification.MANUAL_CONFIRMED);
        assertThat(stored.getRefundVerification())
            .isNotEqualTo(RefundVerification.GATEWAY_CONFIRMED);
        assertThat(stored.getRefundReference())
            .isEqualTo("MANUAL-" + method.name());
        assertThat(orderRepository.findById(order.getId())
            .orElseThrow().getPaymentStatus())
            .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(entries(request.getId())).hasSize(6);
    }

    @Test
    void duplicateRefundDoesNotRepeatTimelineInventoryOrPointReversal() {
        var order = deliveredOrder(2, PaymentMethod.COD);
        long earned = loyalty.balance(customer);

        assertThat(earned).isPositive();

        var request = pending(order);

        assertThat(loyalty.balance(customer)).isEqualTo(earned);
        assertThat(refundMovements(order.getId())).isEmpty();

        returns.confirmRefund(
            other, request.getId(),
            "BANK-ONCE-001", "Đã hoàn tiền và đối soát lần đầu"
        );

        reload();

        var historyIds = entries(request.getId()).stream()
            .map(ReturnStatusHistory::getId)
            .toList();

        var reversal = loyaltyTransactions.findByOrderIdAndType(
            order.getId(), LoyaltyPointType.REFUND_REVERSAL
        ).orElseThrow();

        Long reversalId = reversal.getId();

        assertThat(reversal.getPointsChange()).isEqualTo(-earned);
        assertThat(loyalty.balance(customer)).isZero();
        assertThat(refundMovements(order.getId())).hasSize(1);
        assertThat(refundMovements(order.getId()).getFirst().getQuantityChange())
            .isEqualTo(2);

        assertThatThrownBy(() -> returns.confirmRefund(
            other, request.getId(),
            "BANK-ONCE-002", "Thử xác nhận hoàn tiền lần thứ hai"
        )).isInstanceOf(IllegalArgumentException.class);

        reload();

        assertThat(entries(request.getId()))
            .extracting(ReturnStatusHistory::getId)
            .containsExactlyElementsOf(historyIds);

        assertThat(refundMovements(order.getId())).hasSize(1);
        assertThat(products.findById(tea.getId()).orElseThrow().getStock())
            .isEqualTo(10);
        assertThat(products.findById(tea.getId()).orElseThrow().getSoldCount())
            .isZero();
        assertThat(loyalty.balance(customer)).isZero();

        assertThat(loyaltyTransactions.findByOrderIdAndType(
            order.getId(), LoyaltyPointType.REFUND_REVERSAL
        ).orElseThrow().getId()).isEqualTo(reversalId);

        assertThat(returnRequests.findById(request.getId())
            .orElseThrow().getRefundReference())
            .isEqualTo("BANK-ONCE-001");
    }

    @Test
    void invalidTransitionDoesNotAppendTimeline() {
        var order = deliveredOrder(1, PaymentMethod.COD);
        var request = open(order);

        assertThatThrownBy(() -> returns.vendorTransition(
            other, request.getId(),
            ReturnStatus.RECEIVED, "Thử bỏ qua bước duyệt"
        )).isInstanceOf(IllegalArgumentException.class);

        reload();

        assertThat(entries(request.getId()))
            .extracting(ReturnStatusHistory::getNewStatus)
            .containsExactly(ReturnStatus.REQUESTED);

        assertThat(returnRequests.findById(request.getId())
            .orElseThrow().getStatus())
            .isEqualTo(ReturnStatus.REQUESTED);
    }

    @Test
    void invalidRefundReferenceDoesNotAppendTimelineOrReversePoints() {
        var order = deliveredOrder(1, PaymentMethod.COD);
        var request = pending(order);
        long balance = loyalty.balance(customer);

        assertThatThrownBy(() -> returns.confirmRefund(
            other, request.getId(),
            "abc", "Mã tham chiếu không đủ độ dài"
        )).isInstanceOf(IllegalArgumentException.class);

        reload();

        assertThat(entries(request.getId())).hasSize(5);
        assertThat(entries(request.getId()).getLast().getNewStatus())
            .isEqualTo(ReturnStatus.REFUND_PENDING);
        assertThat(refundMovements(order.getId())).isEmpty();
        assertThat(loyalty.balance(customer)).isEqualTo(balance);

        assertThat(loyaltyTransactions.existsByOrderIdAndType(
            order.getId(), LoyaltyPointType.REFUND_REVERSAL
        )).isFalse();

        assertThat(returnRequests.findById(request.getId())
            .orElseThrow().getRefundVerification())
            .isEqualTo(RefundVerification.PENDING);
    }

    @Test
    void timelineReadsRejectForeignVendorAndForeignCustomer() {
        var order = deliveredOrder(1, PaymentMethod.COD);
        var request = open(order);
        User foreignVendor = account(Role.VENDOR);
        User foreignCustomer = account(Role.USER);

        assertHttp(
            HttpStatus.NOT_FOUND,
            () -> returns.historyForShop(foreignVendor, request.getId())
        );

        assertHttp(
            HttpStatus.NOT_FOUND,
            () -> returns.historyForUser(foreignCustomer, order.getId())
        );

        assertHttp(
            HttpStatus.NOT_FOUND,
            () -> returns.decide(
                foreignVendor, request.getId(),
                ReturnStatus.REJECTED, null,
                "Thử xử lý yêu cầu của shop khác"
            )
        );

        assertThat(returns.historyForUser(customer, order.getId())).hasSize(1);
        assertThat(returns.historyForShop(other, request.getId())).hasSize(1);
    }

    @Test
    void assignedManagerIsRecordedButOnlyAdminOrOwnerCanConfirmRefund() {
        var order = deliveredOrder(1, PaymentMethod.COD);
        var request = open(order);
        User manager = account(Role.MANAGER);
        User admin = account(Role.ADMIN);

        var assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(order.getShop());
        assignment.setAssignedBy(admin);
        managerShops.saveAndFlush(assignment);

        returns.decide(
            manager, request.getId(), ReturnStatus.APPROVED,
            order.getTotalAmount(),
            "Manager duyệt yêu cầu trong shop được giao"
        );

        returns.markReturning(customer, order.getId());

        returns.vendorTransition(
            manager, request.getId(), ReturnStatus.RECEIVED,
            "Manager xác nhận đã nhận đủ hàng trả"
        );

        returns.vendorTransition(
            manager, request.getId(), ReturnStatus.REFUND_PENDING,
            "Chuyển chủ cửa hàng xác nhận hoàn tiền"
        );

        assertThat(returns.historyForShop(manager, request.getId())).hasSize(5);

        assertHttp(
            HttpStatus.FORBIDDEN,
            () -> returns.confirmRefund(
                manager, request.getId(),
                "MANAGER-001", "Manager thử xác nhận hoàn tiền"
            )
        );

        returns.confirmRefund(
            admin, request.getId(),
            "ADMIN-001", "Admin xác nhận đã đối soát khoản hoàn tiền"
        );

        reload();

        assertThat(entries(request.getId()))
            .extracting(entry -> entry.getChangedBy().getId())
            .containsExactly(
                customer.getId(), manager.getId(), customer.getId(),
                manager.getId(), manager.getId(), admin.getId()
            );
    }

    @Test
    void fullLengthNotesSurviveLaterTransitions() {
        var order = deliveredOrder(1, PaymentMethod.COD);
        var request = open(order);

        String approvalNote = "A".repeat(1000);
        String receivedNote = "B".repeat(1000);
        String pendingNote = "C".repeat(1000);
        String refundNote = "D".repeat(1000);

        returns.decide(
            other, request.getId(), ReturnStatus.APPROVED,
            order.getTotalAmount(), approvalNote
        );

        returns.markReturning(customer, order.getId());

        returns.vendorTransition(
            other, request.getId(), ReturnStatus.RECEIVED, receivedNote
        );

        returns.vendorTransition(
            other, request.getId(), ReturnStatus.REFUND_PENDING, pendingNote
        );

        returns.confirmRefund(
            other, request.getId(), "LONG-NOTES-001", refundNote
        );

        reload();

        var entries = entries(request.getId());

        assertThat(entries.get(1).getNote()).isEqualTo(approvalNote);
        assertThat(entries.get(3).getNote()).isEqualTo(receivedNote);
        assertThat(entries.get(4).getNote()).isEqualTo(pendingNote);
        assertThat(entries.get(5).getNote()).isEqualTo(refundNote);
    }

    @Test
    void legacyPendingRequestIsReadWithoutWritesAndPreservedBeforeRefund() {
        var order = deliveredOrder(1, PaymentMethod.COD);
        var request = legacyRequest(order, ReturnStatus.REFUND_PENDING);
        LocalDateTime originalTime = request.getResolvedAt();
        String originalNote = request.getResolutionNote();

        reload();

        assertThat(entries(request.getId())).isEmpty();

        var visible = returns.historyForShop(other, request.getId());

        assertThat(visible).extracting(ReturnStatusHistory::getNewStatus)
            .containsExactly(
                ReturnStatus.REQUESTED,
                ReturnStatus.REFUND_PENDING
            );

        assertThat(visible).allSatisfy(
            entry -> assertThat(entry.getId()).isNull()
        );

        assertThat(entries(request.getId())).isEmpty();

        returns.confirmRefund(
            other, request.getId(), "LEGACY-001",
            "Đã hoàn tiền cho yêu cầu tồn tại từ phiên bản cũ"
        );

        reload();

        var stored = entries(request.getId());

        assertThat(stored).extracting(ReturnStatusHistory::getNewStatus)
            .containsExactly(
                ReturnStatus.REQUESTED,
                ReturnStatus.REFUND_PENDING,
                ReturnStatus.REFUNDED
            );

        assertThat(stored.get(1).getOldStatus()).isNull();
        assertThat(stored.get(1).getChangedAt()).isEqualTo(originalTime);
        assertThat(stored.get(1).getNote()).isEqualTo(originalNote);
        assertThat(stored.getLast().getOldStatus())
            .isEqualTo(ReturnStatus.REFUND_PENDING);
    }

    @Test
    void legacyReturningRequestKeepsApprovalWithoutInventingBuyerTimestamp() {
        var order = deliveredOrder(1, PaymentMethod.COD);
        var request = legacyRequest(order, ReturnStatus.RETURNING);
        LocalDateTime approvalTime = request.getResolvedAt();

        returns.vendorTransition(
            other, request.getId(), ReturnStatus.RECEIVED,
            "Đã nhận hàng của yêu cầu từ phiên bản cũ"
        );

        reload();

        var entries = entries(request.getId());

        assertThat(entries).extracting(ReturnStatusHistory::getNewStatus)
            .containsExactly(
                ReturnStatus.REQUESTED,
                ReturnStatus.APPROVED,
                ReturnStatus.RECEIVED
            );

        assertThat(entries.get(1).getChangedAt()).isEqualTo(approvalTime);
        assertThat(entries.get(1).getChangedBy().getId())
            .isEqualTo(other.getId());
        assertThat(entries.getLast().getOldStatus())
            .isEqualTo(ReturnStatus.RETURNING);
    }

    @Test
    void equalTimestampsStillReturnTimelineInInsertionOrder() {
        var order = deliveredOrder(1, PaymentMethod.COD);
        var request = open(order);
        approve(request);
        returns.markReturning(customer, order.getId());

        LocalDateTime sameTime = LocalDateTime.now()
            .minusMinutes(1)
            .withNano(0);

        entries(request.getId()).forEach(
            entry -> entry.setChangedAt(sameTime)
        );

        reload();

        var entries = entries(request.getId());

        assertThat(entries).extracting(ReturnStatusHistory::getNewStatus)
            .containsExactly(
                ReturnStatus.REQUESTED,
                ReturnStatus.APPROVED,
                ReturnStatus.RETURNING
            );

        assertThat(entries).extracting(ReturnStatusHistory::getId).isSorted();
    }

    @Test
    void vendorPageRendersEscapedTimelineAndProtectedEvidence() throws Exception {
        var order = deliveredOrder(1, PaymentMethod.COD);
        var request = open(order);

        returns.decide(
            other, request.getId(), ReturnStatus.APPROVED,
            order.getTotalAmount(),
            "Đã duyệt <script>alert('x')</script>"
        );

        reload();

        String html = mvc.perform(
                get("/vendor/returns/" + request.getId())
                    .with(user(other.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk())
            .andExpect(view().name("vendor/return-detail"))
            .andExpect(model().attributeExists(
                "timeline", "returnRequest", "shop"
            ))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(
            "Lịch sử xử lý trả hàng",
            "&lt;script&gt;"
        );

        assertThat(html).doesNotContain(
            "<script>alert('x')</script>",
            "private:returns/",
            "/uploads/returns/"
        );

        var media = returnRequests.findDetailedById(request.getId())
            .orElseThrow().getMedia().getFirst();

        String url = "/private-media/returns/" + media.getId();

        assertThat(html).contains(
            "href=\"" + url + "\"",
            "src=\"" + url + "\""
        );
    }

    @Test
    void refundedOnlineOrderPageExplainsManualConfirmation() throws Exception {
        var order = deliveredOrder(1, PaymentMethod.MOMO);
        var request = pending(order);

        returns.confirmRefund(
            other, request.getId(), "BANK-ONLINE-001",
            "Đã hoàn ngoài hệ thống qua tài khoản ngân hàng"
        );

        reload();

        String html = mvc.perform(
                get("/vendor/returns/" + request.getId())
                    .with(user(other.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk())
            .andExpect(model().attributeExists("timeline"))
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(
            "Đã xác nhận hoàn tiền thủ công",
            "Chưa có xác nhận hoàn tiền từ cổng thanh toán.",
            "BANK-ONLINE-001"
        );

        assertThat(html).doesNotContain(
            "action=\"/vendor/returns/" + request.getId() + "/refund\""
        );
    }

    private CustomerOrder deliveredOrder(int quantity, PaymentMethod method) {
        var order = orders.checkout(
            customer,
            form(add(tea, medium, quantity).getId())
        ).getFirst();

        order.setStatus(OrderStatus.DELIVERED);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaymentMethod(method);
        order.setDeliveredAt(LocalDateTime.now());

        tea.setSoldCount(tea.getSoldCount() + quantity);
        products.saveAndFlush(tea);
        orderRepository.saveAndFlush(order);

        loyalty.awardForDeliveredOrder(order);
        entityManager.flush();

        return order;
    }

    private ReturnRequest open(CustomerOrder order) {
        var form = new ReturnRequestForm();
        form.setReason(ReturnReason.DAMAGED);
        form.setDescription(
            "Ly bị vỡ và sản phẩm bị đổ khi nhận hàng từ shipper."
        );
        form.setRefundAmount(order.getTotalAmount());

        return returns.create(
            customer, order.getId(), form,
            new MultipartFile[] {
                MediaTestFiles.image("evidence", "proof.png")
            }
        );
    }

    private void approve(ReturnRequest request) {
        returns.decide(
            other, request.getId(), ReturnStatus.APPROVED,
            request.getOrder().getTotalAmount(),
            "Đồng ý nhận lại đầy đủ sản phẩm"
        );
    }

    private ReturnRequest pending(CustomerOrder order) {
        var request = open(order);
        approve(request);

        returns.markReturning(customer, order.getId());

        returns.vendorTransition(
            other, request.getId(), ReturnStatus.RECEIVED,
            "Đã nhận đủ hàng khách gửi trả"
        );

        returns.vendorTransition(
            other, request.getId(), ReturnStatus.REFUND_PENDING,
            "Chuẩn bị chuyển khoản hoàn tiền cho khách"
        );

        return request;
    }

    private ReturnRequest legacyRequest(
            CustomerOrder order,
            ReturnStatus status) {

        order.setStatus(OrderStatus.RETURN_REQUESTED);
        orderRepository.saveAndFlush(order);

        var request = new ReturnRequest();
        request.setOrder(order);
        request.setUser(customer);
        request.setShop(order.getShop());
        request.setReason(ReturnReason.DAMAGED);
        request.setDescription(
            "Yêu cầu được tạo từ phiên bản chưa có bảng lịch sử."
        );
        request.setRefundAmount(order.getTotalAmount());
        request.setStatus(status);
        request.setRequestedAt(
            LocalDateTime.now().minusHours(2).withNano(0)
        );
        request.setResolvedBy(other);
        request.setResolvedAt(
            LocalDateTime.now().minusHours(1).withNano(0)
        );
        request.setResolutionNote(
            "Ghi chú xử lý được giữ từ phiên bản trước"
        );

        return returnRequests.saveAndFlush(request);
    }

    private User account(Role role) {
        var account = createUser();
        account.setRole(role);
        return users.saveAndFlush(account);
    }

    private List<ReturnStatusHistory> entries(Long requestId) {
        return returnHistory.findByReturnRequestIdOrderByChangedAtAscIdAsc(
            requestId
        );
    }

    private List<InventoryTransaction> refundMovements(Long orderId) {
        return inventoryTransactions
            .findByProductIdOrderByCreatedAtAscIdAsc(tea.getId())
            .stream()
            .filter(entry ->
                entry.getType() == InventoryTransactionType.RETURN_REFUND
            )
            .filter(entry ->
                entry.getOrder() != null
                    && entry.getOrder().getId().equals(orderId)
            )
            .toList();
    }

    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    private void assertHttp(HttpStatus expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode())
                .isEqualTo(expected)
        );
    }
}