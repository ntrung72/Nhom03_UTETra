package vn.iotstar.service;

import vn.iotstar.util.VietnamTime;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.ReturnRequestForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;

@Service
@RequiredArgsConstructor
public class ReturnRequestService {
    private final ReturnRequestRepository returnRequestRepository;
    private final ReturnStatusHistoryRepository returnStatusHistoryRepository;
    private final ReturnRequestMediaRepository mediaRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ManagerShopRepository managerShopRepository;
    private final MediaStorageService mediaStorageService;
    private final MediaValidationService mediaValidationService;
    private final OrderService orderService;
    private final NotificationService notificationService;

    public ReturnRequest forOrder(Long orderId) {
        return returnRequestRepository.findByOrderId(orderId).orElse(null);
    }

    public Map<Long, ReturnRequest> forOrders(List<Long> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        return returnRequestRepository.findByOrderIdIn(orderIds).stream()
            .collect(Collectors.toMap(request -> request.getOrder().getId(), Function.identity()));
    }

    public ReturnRequest getForUser(User user, Long orderId) {
        ReturnRequest request = returnRequestRepository.findByOrderId(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu trả hàng."));
        if (!request.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền xem yêu cầu trả hàng này.");
        }
        return request;
    }

    public ReturnRequest getForShop(User actor, Long requestId) {
        ReturnRequest request = returnRequestRepository.findDetailedById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu trả hàng."));
        ensureShopAccess(actor, request.getShop());
        return request;
    }

    public List<ReturnStatusHistory> statusHistory(Long requestId) {
        return returnStatusHistoryRepository.findByReturnRequestIdOrderByChangedAtAscIdAsc(requestId);
    }

    @Transactional
    public ReturnRequest create(User user, Long orderId, ReturnRequestForm form, MultipartFile[] evidence) {
        CustomerOrder order = orderRepository.findDetailedByIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."));
        if (!order.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Đơn hàng không thuộc tài khoản của bạn.");
        }
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new IllegalArgumentException("Chỉ đơn đã giao mới có thể yêu cầu trả hàng.");
        }
        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            throw new IllegalArgumentException("Đơn hàng chưa ghi nhận đã thanh toán nên chưa thể yêu cầu hoàn tiền.");
        }
        if (returnRequestRepository.existsByOrderId(orderId)) {
            throw new IllegalArgumentException("Đơn hàng này đã có yêu cầu trả hàng.");
        }
        validateRefundAmount(form.getRefundAmount(), order);
        if (!"SAVED".equals(form.getRefundBankAccount())) {
            throw new IllegalArgumentException("Vui lòng chọn tài khoản ngân hàng nhận tiền hoàn.");
        }
        requireSavedBank(user);
        List<MultipartFile> files = mediaValidationService.validateFiles(evidence, "returns");
        if (files.isEmpty()) throw new IllegalArgumentException("Vui lòng tải lên ít nhất một ảnh hoặc video làm bằng chứng.");

        ReturnRequest request = new ReturnRequest();
        request.setOrder(order);
        request.setUser(user);
        request.setShop(order.getShop());
        request.setReason(form.getReason());
        request.setDescription(form.getDescription().trim());
        request.setRefundAmount(form.getRefundAmount());
        snapshotBank(request, user);
        request.setRefundVerification(RefundVerification.PENDING);
        returnRequestRepository.save(request);
        recordStatus(request, null, ReturnStatus.REQUESTED, user, "Khách gửi yêu cầu trả hàng");
        for (MultipartFile file : files) {
            ReturnRequestMedia media = new ReturnRequestMedia();
            media.setReturnRequest(request);
            media.setType(mediaValidationService.validate(file, "returns"));
            media.setUrl(mediaStorageService.upload(file, "returns"));
            request.getMedia().add(media);
            mediaRepository.save(media);
        }
        orderService.applyStatus(order, OrderStatus.RETURN_REQUESTED, user,
            "Khách yêu cầu trả hàng: " + form.getReason().getLabel());
        notificationService.send(order.getShop().getOwner(), "Có yêu cầu trả hàng mới",
            "Đơn " + order.getCode() + " đang chờ shop xử lý.", "/vendor/returns/" + request.getId());
        return request;
    }

    @Transactional
    public void decide(User actor, Long requestId, ReturnStatus decision,
                       BigDecimal refundAmount, String note) {
        ReturnRequest request = returnRequestRepository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu trả hàng."));
        ensureShopAccess(actor, request.getShop());
        CustomerOrder order = orderRepository.findDetailedByIdForUpdate(request.getOrder().getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."));
        if (request.getStatus() != ReturnStatus.REQUESTED) {
            throw new IllegalArgumentException("Yêu cầu này đã được xử lý.");
        }
        if (decision != ReturnStatus.APPROVED && decision != ReturnStatus.REJECTED) {
            throw new IllegalArgumentException("Quyết định xử lý không hợp lệ.");
        }
        String safeNote = note == null ? "" : note.trim();
        if (safeNote.length() < 5) throw new IllegalArgumentException("Vui lòng nhập ghi chú xử lý ít nhất 5 ký tự.");
        if (decision == ReturnStatus.APPROVED) {
            validateRefundAmount(refundAmount, order);
            request.setRefundAmount(refundAmount);
        }
        ReturnStatus previous = request.getStatus();
        request.setStatus(decision);
        request.setResolvedBy(actor);
        request.setResolvedAt(VietnamTime.now());
        request.setResolutionNote(safeNote);
        recordStatus(request, previous, decision, actor, safeNote);
        if (decision == ReturnStatus.REJECTED) {
            orderService.applyStatus(order, OrderStatus.DELIVERED, actor,
                "Shop từ chối yêu cầu trả hàng: " + safeNote);
        }
        notificationService.send(request.getUser(), decision == ReturnStatus.APPROVED
                ? "Yêu cầu trả hàng đã được chấp nhận" : "Yêu cầu trả hàng đã bị từ chối",
            "Đơn " + request.getOrder().getCode() + ": " + safeNote,
            "/orders/" + request.getOrder().getId());
    }

    @Transactional
    public void requirePhysicalReturn(User actor, Long requestId) {
        ReturnRequest request = returnRequestRepository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu trả hàng."));
        ensureShopAccess(actor, request.getShop());
        requireStatus(request, ReturnStatus.APPROVED);
        if (request.getReturnRequired() != null) {
            throw new IllegalArgumentException("Cửa hàng đã chọn cách xử lý sản phẩm trả.");
        }
        String address = request.getShop().getFullAddress();
        if (address.isBlank()) {
            throw new IllegalArgumentException("Cửa hàng cần cập nhật địa chỉ trước khi yêu cầu khách trả sản phẩm.");
        }
        request.setReturnRequired(true);
        recordStatus(request, ReturnStatus.APPROVED, ReturnStatus.APPROVED, actor,
            "Cửa hàng yêu cầu khách trả sản phẩm tại " + address);
        notificationService.send(request.getUser(), "Cửa hàng yêu cầu trả sản phẩm",
            "Vui lòng mang sản phẩm của đơn " + request.getOrder().getCode()
                + " đến " + request.getShop().getName() + ": " + address + ".",
            "/orders/" + request.getOrder().getId());
    }

    @Transactional
    public void markReturning(User user, Long orderId) {
        ReturnRequest request = returnRequestRepository.findByOrderIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu trả hàng."));
        if (!request.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền xem yêu cầu trả hàng này.");
        }
        requireStatus(request, ReturnStatus.APPROVED);
        if (!Boolean.TRUE.equals(request.getReturnRequired())) {
            throw new IllegalArgumentException("Vui lòng chờ cửa hàng xác nhận có cần trả sản phẩm hay không.");
        }
        request.setStatus(ReturnStatus.RETURNING);
        recordStatus(request, ReturnStatus.APPROVED, ReturnStatus.RETURNING, user,
            "Khách xác nhận đã giao sản phẩm trả cho cửa hàng");
        notificationService.send(request.getShop().getOwner(), "Khách đã giao sản phẩm trả",
            "Vui lòng xác nhận đã nhận sản phẩm của đơn " + request.getOrder().getCode() + ".",
            "/vendor/returns/" + request.getId());
    }

    @Transactional
    public void vendorTransition(User actor, Long requestId, ReturnStatus target, String note) {
        ReturnRequest request = returnRequestRepository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu trả hàng."));
        ensureShopAccess(actor, request.getShop());
        ReturnStatus previous = request.getStatus();
        boolean allowed = target == ReturnStatus.RECEIVED && previous == ReturnStatus.RETURNING
            || target == ReturnStatus.REFUND_PENDING
                && (previous == ReturnStatus.RECEIVED || previous == ReturnStatus.APPROVED);
        if (!allowed) {
            throw new IllegalArgumentException("Không thể chuyển yêu cầu sang trạng thái " + target.getLabel() + ".");
        }
        if (previous == ReturnStatus.APPROVED && request.getReturnRequired() != null) {
            throw new IllegalArgumentException("Cửa hàng đã yêu cầu trả sản phẩm; vui lòng chờ nhận hàng trước khi hoàn tiền.");
        }
        String safeNote = note == null ? "" : note.trim();
        if (target == ReturnStatus.REFUND_PENDING && safeNote.length() < 5) {
            throw new IllegalArgumentException("Vui lòng nhập ghi chú xử lý hoàn tiền ít nhất 5 ký tự.");
        }
        request.setStatus(target);
        if (previous == ReturnStatus.APPROVED && target == ReturnStatus.REFUND_PENDING) {
            request.setReturnRequired(false);
        }
        if (!safeNote.isBlank()) request.setResolutionNote(safeNote);
        recordStatus(request, previous, target, actor, safeNote);
        if (target == ReturnStatus.REFUND_PENDING) {
            notificationService.send(request.getUser(), "Đang chờ hoàn tiền",
                "Shop đã duyệt hoàn " + request.getRefundAmount().toPlainString() + "đ cho đơn "
                    + request.getOrder().getCode() + ". Admin sẽ chuyển khoản đến tài khoản bạn đã chọn.",
                "/orders/" + request.getOrder().getId());
            for (User admin : userRepository.findByRoleOrderByFullNameAsc(Role.ADMIN)) {
                notificationService.send(admin, "Có đơn chờ chuyển khoản hoàn tiền",
                    "Đơn " + request.getOrder().getCode() + " cần hoàn "
                        + request.getRefundAmount().toPlainString() + "đ.",
                    "/vendor/returns/" + request.getId());
            }
        }
    }

    @Transactional
    public void confirmRefund(User actor, Long requestId, String refundReference, String refundNote) {
        ReturnRequest request = returnRequestRepository.findByIdForUpdate(requestId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu trả hàng."));
        ensureShopAccess(actor, request.getShop());
        requireStatus(request, ReturnStatus.REFUND_PENDING);
        ensureRefundConfirmationAccess(actor);
        if (request.getRefundAccountNumber() == null || request.getRefundAccountNumber().isBlank()) {
            throw new IllegalArgumentException("Khách chưa chọn tài khoản ngân hàng nhận tiền hoàn.");
        }
        CustomerOrder order = orderRepository.findDetailedByIdForUpdate(request.getOrder().getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."));
        if (order.getStatus() != OrderStatus.RETURN_REQUESTED) {
            throw new IllegalArgumentException("Trạng thái đơn hàng không còn phù hợp để hoàn tiền.");
        }
        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            throw new IllegalArgumentException("Đơn hàng chưa ghi nhận đã thanh toán nên không thể xác nhận hoàn tiền.");
        }

        String safeReference = refundReference == null ? "" : refundReference.trim();
        String safeNote = refundNote == null ? "" : refundNote.trim();
        if (safeReference.length() < 4 || safeReference.length() > 120) {
            throw new IllegalArgumentException("Vui lòng nhập mã tham chiếu hoàn tiền từ 4 đến 120 ký tự.");
        }
        if (safeNote.length() < 5 || safeNote.length() > 1000) {
            throw new IllegalArgumentException("Vui lòng nhập ghi chú hoàn tiền từ 5 đến 1000 ký tự.");
        }

        LocalDateTime now = VietnamTime.now();
        request.setStatus(ReturnStatus.REFUNDED);
        request.setRefundVerification(RefundVerification.MANUAL_CONFIRMED);
        request.setRefundReference(safeReference);
        request.setRefundNote(safeNote);
        request.setRefundedAt(now);
        request.setResolvedBy(actor);
        request.setResolvedAt(now);
        recordStatus(request, ReturnStatus.REFUND_PENDING, ReturnStatus.REFUNDED, actor,
            "Admin đã chuyển khoản hoàn tiền; mã giao dịch " + safeReference + ".");
        boolean goodsReturned = returnStatusHistoryRepository
            .findByReturnRequestIdOrderByChangedAtAscIdAsc(request.getId()).stream()
            .anyMatch(history -> history.getNewStatus() == ReturnStatus.RECEIVED);
        orderService.applyStatus(order, OrderStatus.RETURNED_REFUNDED, actor,
            "Admin đã chuyển khoản hoàn " + request.getRefundAmount().toPlainString()
                + "đ, mã giao dịch " + safeReference + ".", goodsReturned);
        notificationService.send(request.getUser(), "Đơn hàng đã được hoàn tiền",
            "Admin đã chuyển khoản hoàn " + request.getRefundAmount().toPlainString()
                + "đ cho đơn " + request.getOrder().getCode()
                + ". Mã giao dịch: " + safeReference + ".",
            "/orders/" + request.getOrder().getId());
    }

    private void recordStatus(ReturnRequest request, ReturnStatus previous, ReturnStatus next,
                              User actor, String note) {
        ReturnStatusHistory history = new ReturnStatusHistory();
        history.setReturnRequest(request);
        history.setOldStatus(previous);
        history.setNewStatus(next);
        history.setChangedBy(actor);
        String safeNote = note == null ? null : note.strip();
        history.setNote(safeNote == null ? null : safeNote.substring(0, Math.min(safeNote.length(), 500)));
        returnStatusHistoryRepository.save(history);
    }

    private void validateRefundAmount(BigDecimal amount, CustomerOrder order) {
        if (amount == null || amount.compareTo(order.getTotalAmount()) != 0) {
            throw new IllegalArgumentException(
                "Yêu cầu trả hàng áp dụng cho toàn bộ đơn, số tiền hoàn phải bằng tổng thanh toán "
                    + order.getTotalAmount().stripTrailingZeros().toPlainString() + "đ.");
        }
    }

    private void requireStatus(ReturnRequest request, ReturnStatus expected) {
        if (request.getStatus() != expected) {
            throw new IllegalArgumentException("Yêu cầu không ở trạng thái " + expected.getLabel() + ".");
        }
    }

    private void ensureShopAccess(User actor, Shop shop) {
        boolean allowed = (actor.getRole() == Role.VENDOR && shop.getOwner().getId().equals(actor.getId()))
            || actor.getRole() == Role.ADMIN
            || (actor.getRole() == Role.MANAGER
                && managerShopRepository.existsByManagerIdAndShopId(actor.getId(), shop.getId()));
        if (!allowed) throw new IllegalArgumentException("Bạn không có quyền xử lý yêu cầu của cửa hàng này.");
    }

    private void ensureRefundConfirmationAccess(User actor) {
        if (actor.getRole() != Role.ADMIN) {
            throw new IllegalArgumentException("Chỉ Admin được xác nhận đã chuyển khoản hoàn tiền.");
        }
    }

    public static boolean hasSavedBank(User user) {
        return user != null && user.getBankName() != null && !user.getBankName().isBlank()
            && user.getBankAccountName() != null && !user.getBankAccountName().isBlank()
            && user.getBankAccountNumber() != null && !user.getBankAccountNumber().isBlank();
    }

    private void requireSavedBank(User user) {
        if (!hasSavedBank(user)) {
            throw new IllegalArgumentException("Vui lòng cập nhật tài khoản ngân hàng trong hồ sơ trước khi yêu cầu hoàn tiền.");
        }
    }

    private void snapshotBank(ReturnRequest request, User user) {
        request.setRefundBankName(user.getBankName());
        request.setRefundAccountName(user.getBankAccountName());
        request.setRefundAccountNumber(user.getBankAccountNumber());
    }

    @Transactional
    public void selectRefundBank(User user, Long orderId) {
        ReturnRequest request = returnRequestRepository.findByOrderIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy yêu cầu trả hàng."));
        if (!request.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Bạn không có quyền chọn tài khoản cho yêu cầu này.");
        }
        if (request.getStatus() == ReturnStatus.REJECTED || request.getStatus() == ReturnStatus.REFUNDED
            || request.getRefundAccountNumber() != null) {
            throw new IllegalArgumentException("Yêu cầu này không còn cần chọn tài khoản nhận tiền.");
        }
        requireSavedBank(user);
        snapshotBank(request, user);
    }
}
