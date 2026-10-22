package vn.iotstar.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.dto.WebForms.ReturnRequestForm;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.ReturnRequest;
import vn.iotstar.entity.ReturnRequestMedia;
import vn.iotstar.entity.ReturnRequest.RefundVerification;
import vn.iotstar.entity.ReturnStatusHistory;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ReturnRequestMediaRepository;
import vn.iotstar.repository.ReturnRequestRepository;
import vn.iotstar.repository.ReturnStatusHistoryRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReturnRequestService {

    private final ReturnRequestRepository returnRequestRepository;
    private final ReturnRequestMediaRepository mediaRepository;
    private final ReturnStatusHistoryRepository historyRepository;
    private final OrderRepository orderRepository;
    private final ManagerShopRepository managerShopRepository;
    private final ShopRepository shopRepository;
    private final UserRepository userRepository;
    private final MediaStorageService mediaStorageService;
    private final MediaValidationService mediaValidationService;
    private final OrderService orderService;
    private final NotificationService notificationService;
    private final Validator validator;

    @PersistenceContext
    private EntityManager entityManager;

    public ReturnRequest forOrder(User user, Long orderId) {
        var account = requireBuyer(user);
        requirePositiveId(orderId);

        orderRepository.findByIdAndUserId(orderId, account.getId())
            .orElseThrow(this::notFound);

        var request = returnRequestRepository
            .findByOrderIdAndUserId(orderId, account.getId())
            .orElse(null);

        if (request != null) {
            prepareDetail(request);
        }

        return request;
    }

    public Map<Long, ReturnRequest> forOrders(
            User actor,
            List<Long> orderIds) {

        var account = requireAccount(actor);

        if (account.getRole() != Role.VENDOR
                && account.getRole() != Role.MANAGER
                && account.getRole() != Role.ADMIN) {
            throw forbidden();
        }

        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }

        if (orderIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException(
                "Danh sách đơn hàng không hợp lệ."
            );
        }

        var ids = orderIds.stream().distinct().toList();

        List<ReturnRequest> requests;

        if (account.getRole() == Role.VENDOR) {
            var shop = requireVendorShop(account);

            requests = returnRequestRepository
                .findByShopIdAndOrderIdIn(shop.getId(), ids);
        } else {
            requests = returnRequestRepository.findByOrderIdIn(ids);
        }

        var result = new LinkedHashMap<Long, ReturnRequest>();

        for (var request : requests) {
            if (canAccessShop(account, request.getShop())) {
                requireConsistentRequest(request);
                result.put(request.getOrder().getId(), request);
            }
        }

        return result;
    }

    public Page<ReturnRequest> forShop(
            User vendor,
            ReturnStatus status,
            int page) {

        var account = requireAccount(vendor);
        var shop = requireVendorShop(account);

        return returnRequestRepository.findForShop(
            shop.getId(),
            status,
            PageRequest.of(
                Math.max(0, page),
                15,
                Sort.by(
                    Sort.Order.desc("requestedAt"),
                    Sort.Order.desc("id")
                )
            )
        );
    }

    public ReturnRequest getForUser(User user, Long orderId) {
        var account = requireBuyer(user);
        requirePositiveId(orderId);

        var request = returnRequestRepository
            .findByOrderIdAndUserId(orderId, account.getId())
            .orElseThrow(this::notFound);

        prepareDetail(request);

        return request;
    }

    public ReturnRequest getForShop(User actor, Long requestId) {
        var account = requireAccount(actor);
        requirePositiveId(requestId);

        var request = returnRequestRepository
            .findDetailedById(requestId)
            .orElseThrow(this::notFound);

        ensureShopAccess(account, request);
        prepareDetail(request);

        return request;
    }

    public List<ReturnStatusHistory> historyForShop(
            User actor,
            Long requestId) {

        return readHistory(getForShop(actor, requestId));
    }

    public List<ReturnStatusHistory> historyForUser(
            User user,
            Long orderId) {

        return readHistory(getForUser(user, orderId));
    }

    @Transactional
    public ReturnRequest create(
            User user,
            Long orderId,
            ReturnRequestForm form,
            MultipartFile[] evidence) {

        var account = requireBuyer(user);
        requirePositiveId(orderId);

        var safeForm = validatedForm(form);

        var order = orderRepository
            .findOwnedForUpdate(orderId, account.getId())
            .orElseThrow(this::notFound);

        entityManager.refresh(
            order,
            LockModeType.PESSIMISTIC_WRITE
        );

        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new IllegalArgumentException(
                "Chỉ đơn đã giao mới có thể yêu cầu trả hàng."
            );
        }

        if (order.getPaymentStatus() != PaymentStatus.PAID) {
            throw new IllegalArgumentException(
                "Chỉ đơn đã thanh toán mới có thể yêu cầu hoàn tiền."
            );
        }

        if (returnRequestRepository.existsByOrderId(orderId)) {
            throw new IllegalArgumentException(
                "Đơn hàng này đã có yêu cầu trả hàng."
            );
        }

        var refundAmount = validateRefundAmount(
            safeForm.getRefundAmount(),
            order
        );

        // Kiểm tra toàn bộ file trước khi bắt đầu tải lên.
        var files = mediaValidationService.validateFiles(
            evidence,
            "returns"
        );

        if (files.isEmpty()) {
            throw new IllegalArgumentException(
                "Vui lòng tải lên ít nhất một ảnh hoặc video làm bằng chứng."
            );
        }

        var request = new ReturnRequest();
        request.setOrder(order);
        request.setUser(account);
        request.setShop(order.getShop());
        request.setReason(safeForm.getReason());
        request.setDescription(safeForm.getDescription());
        request.setRefundAmount(refundAmount);
        request.setStatus(ReturnStatus.REQUESTED);
        request.setRefundVerification(RefundVerification.PENDING);

        returnRequestRepository.saveAndFlush(request);

        for (var file : files) {
            var type = mediaValidationService.validate(file, "returns");
            var url = mediaStorageService.upload(file, "returns");

            if (url == null || url.isBlank() || url.length() > 500) {
                throw new IllegalStateException(
                    "Không nhận được đường dẫn bằng chứng hợp lệ."
                );
            }

            var media = new ReturnRequestMedia();
            media.setReturnRequest(request);
            media.setType(type);
            media.setUrl(url);

            request.getMedia().add(media);
            mediaRepository.save(media);
        }

        orderService.applyReturnStatus(
            order,
            OrderStatus.RETURN_REQUESTED,
            account,
            "Khách yêu cầu trả hàng: " + safeForm.getReason().getLabel()
        );

        recordStatus(
            request,
            null,
            ReturnStatus.REQUESTED,
            account,
            request.getRequestedAt(),
            "Lý do trả hàng: " + safeForm.getReason().getLabel()
        );

        notifyUser(
            order.getShop().getOwner(),
            "Có yêu cầu trả hàng mới",
            "Đơn " + order.getCode() + " đang chờ shop xử lý.",
            "/vendor/returns/" + request.getId()
        );

        entityManager.flush();

        return request;
    }

    @Transactional
    public void decide(
            User actor,
            Long requestId,
            ReturnStatus decision,
            BigDecimal refundAmount,
            String note) {

        var account = requireAccount(actor);
        var request = lockForShop(account, requestId);

        requireStatus(request, ReturnStatus.REQUESTED);
        requireActiveReturnOrder(request);

        if (decision != ReturnStatus.APPROVED
                && decision != ReturnStatus.REJECTED) {
            throw new IllegalArgumentException(
                "Quyết định xử lý không hợp lệ."
            );
        }

        var safeNote = requiredNote(
            note,
            "Ghi chú xử lý"
        );

        if (decision == ReturnStatus.APPROVED) {
            request.setRefundAmount(
                validateRefundAmount(refundAmount, request.getOrder())
            );
        }

        preserveExistingHistory(request);
        var now = LocalDateTime.now();

        request.setStatus(decision);
        request.setResolvedBy(account);
        request.setResolvedAt(now);
        request.setResolutionNote(safeNote);

        if (decision == ReturnStatus.REJECTED) {
            orderService.applyReturnStatus(
                request.getOrder(),
                OrderStatus.DELIVERED,
                account,
                shorten(
                    "Shop từ chối yêu cầu trả hàng: " + safeNote,
                    500
                )
            );
        }

        recordStatus(
            request,
            ReturnStatus.REQUESTED,
            decision,
            account,
            now,
            safeNote
        );

        notifyUser(
            request.getUser(),
            decision == ReturnStatus.APPROVED
                ? "Yêu cầu trả hàng đã được chấp nhận"
                : "Yêu cầu trả hàng đã bị từ chối",
            "Đơn " + request.getOrder().getCode() + ": " + safeNote,
            "/orders/" + request.getOrder().getId()
        );

        entityManager.flush();
    }

    @Transactional
    public void markReturning(User user, Long orderId) {
        var account = requireBuyer(user);
        requirePositiveId(orderId);

        var candidate = returnRequestRepository
            .findByOrderIdAndUserId(orderId, account.getId())
            .orElseThrow(this::notFound);

        var request = lockRequest(candidate);

        if (!request.getUser().getId().equals(account.getId())) {
            throw notFound();
        }

        requireStatus(request, ReturnStatus.APPROVED);
        requireActiveReturnOrder(request);

        preserveExistingHistory(request);
        var now = LocalDateTime.now();
        request.setStatus(ReturnStatus.RETURNING);

        recordStatus(
            request,
            ReturnStatus.APPROVED,
            ReturnStatus.RETURNING,
            account,
            now,
            "Khách xác nhận đã gửi hàng trả."
        );

        notifyUser(
            request.getShop().getOwner(),
            "Khách đang gửi trả hàng",
            "Đơn " + request.getOrder().getCode()
                + " đang được gửi về shop.",
            "/vendor/returns/" + request.getId()
        );

        entityManager.flush();
    }

    @Transactional
    public void vendorTransition(
            User actor,
            Long requestId,
            ReturnStatus target,
            String note) {

        var account = requireAccount(actor);
        var request = lockForShop(account, requestId);

        requireActiveReturnOrder(request);

        ReturnStatus expected;

        if (target == ReturnStatus.RECEIVED) {
            expected = ReturnStatus.RETURNING;
        } else if (target == ReturnStatus.REFUND_PENDING) {
            expected = ReturnStatus.RECEIVED;
        } else {
            throw new IllegalArgumentException(
                "Trạng thái xử lý trả hàng không hợp lệ."
            );
        }

        requireStatus(request, expected);

        var safeNote = target == ReturnStatus.REFUND_PENDING
            ? requiredNote(note, "Ghi chú xử lý hoàn tiền")
            : optionalNote(note);

        preserveExistingHistory(request);
        var now = LocalDateTime.now();

        request.setStatus(target);
        request.setResolvedBy(account);
        request.setResolvedAt(now);

        if (!safeNote.isBlank()) {
            request.setResolutionNote(safeNote);
        }

        recordStatus(
            request,
            expected,
            target,
            account,
            now,
            safeNote
        );

        if (target == ReturnStatus.RECEIVED) {
            notifyUser(
                request.getUser(),
                "Shop đã nhận hàng trả",
                "Shop đã xác nhận nhận hàng trả của đơn "
                    + request.getOrder().getCode() + ".",
                "/orders/" + request.getOrder().getId()
            );
        } else {
            notifyUser(
                request.getUser(),
                "Đang xử lý hoàn tiền",
                "Shop đang xử lý hoàn "
                    + request.getRefundAmount().toPlainString()
                    + "đ cho đơn " + request.getOrder().getCode() + ".",
                "/orders/" + request.getOrder().getId()
            );
        }

        entityManager.flush();
    }

    @Transactional
    public void confirmRefund(
            User actor,
            Long requestId,
            String refundReference,
            String refundNote) {

        var account = requireAccount(actor);
        var request = lockForShop(account, requestId);

        ensureRefundConfirmationAccess(account, request);

        requireStatus(request, ReturnStatus.REFUND_PENDING);
        requireActiveReturnOrder(request);

        validateRefundAmount(
            request.getRefundAmount(),
            request.getOrder()
        );

        var safeReference = refundReference == null
            ? ""
            : refundReference.trim();

        if (safeReference.length() < 4
                || safeReference.length() > 120) {
            throw new IllegalArgumentException(
                "Mã tham chiếu hoàn tiền phải từ 4 đến 120 ký tự."
            );
        }

        var safeNote = requiredNote(
            refundNote,
            "Ghi chú hoàn tiền"
        );

        if (request.getRefundVerification() != RefundVerification.PENDING
                || request.getRefundedAt() != null) {
            throw new IllegalArgumentException(
                "Yêu cầu đã có xác nhận hoàn tiền. Vui lòng tải lại trang."
            );
        }

        preserveExistingHistory(request);
        String gatewayCaveat = gatewayRefundCaveat(request.getOrder());

        // Khóa đơn và trạng thái yêu cầu đảm bảo chỉ hoàn kho một lần.
        orderService.applyReturnStatus(
            request.getOrder(),
            OrderStatus.RETURNED_REFUNDED,
            account,
            shorten(
                "Xác nhận thủ công hoàn "
                    + request.getRefundAmount().toPlainString()
                    + "đ, tham chiếu " + safeReference + "." + gatewayCaveat,
                500
            )
        );

        var now = LocalDateTime.now();

        request.setStatus(ReturnStatus.REFUNDED);
        request.setRefundVerification(RefundVerification.MANUAL_CONFIRMED);
        request.setRefundReference(safeReference);
        request.setRefundNote(safeNote);
        request.setRefundedAt(now);
        request.setResolvedBy(account);
        request.setResolvedAt(now);

        recordStatus(
            request,
            ReturnStatus.REFUND_PENDING,
            ReturnStatus.REFUNDED,
            account,
            now,
            safeNote
        );

        notifyUser(
            request.getUser(),
            "Đã ghi nhận xác nhận hoàn tiền thủ công",
            "Người xử lý xác nhận đã hoàn "
                + request.getRefundAmount().toPlainString()
                + "đ cho đơn " + request.getOrder().getCode()
                + ". Mã tham chiếu: " + safeReference + "." + gatewayCaveat,
            "/orders/" + request.getOrder().getId()
        );

        entityManager.flush();
    }

    private List<ReturnStatusHistory> readHistory(ReturnRequest request) {
        var stored = historyRepository
            .findByReturnRequestIdOrderByChangedAtAscIdAsc(request.getId());

        return List.copyOf(
            stored.isEmpty() ? legacySnapshots(request) : stored
        );
    }

    private void preserveExistingHistory(ReturnRequest request) {
        if (!historyRepository
                .findByReturnRequestIdOrderByChangedAtAscIdAsc(request.getId())
                .isEmpty()) {
            return;
        }

        for (var snapshot : legacySnapshots(request)) {
            saveHistory(request, snapshot);
        }
    }

    private List<ReturnStatusHistory> legacySnapshots(ReturnRequest request) {
        var snapshots = new ArrayList<ReturnStatusHistory>();

        if (request.getRequestedAt() != null && request.getUser() != null) {
            snapshots.add(newHistory(
                request,
                null,
                ReturnStatus.REQUESTED,
                request.getUser(),
                request.getRequestedAt(),
                "Thời điểm tạo yêu cầu từ dữ liệu hiện có."
            ));
        }

        // RETURNING vẫn giữ người và thời điểm duyệt của luồng cũ.
        ReturnStatus knownStatus = request.getStatus() == ReturnStatus.RETURNING
            ? ReturnStatus.APPROVED
            : request.getStatus();

        LocalDateTime knownTime = knownStatus == ReturnStatus.REFUNDED
                && request.getRefundedAt() != null
            ? request.getRefundedAt()
            : request.getResolvedAt();

        if (knownStatus != ReturnStatus.REQUESTED
                && request.getResolvedBy() != null
                && knownTime != null) {

            String knownNote = knownStatus == ReturnStatus.REFUNDED
                ? request.getRefundNote()
                : request.getResolutionNote();

            snapshots.add(newHistory(
                request,
                null,
                knownStatus,
                request.getResolvedBy(),
                knownTime,
                knownNote
            ));
        }

        snapshots.sort(
            java.util.Comparator.comparing(
                ReturnStatusHistory::getChangedAt
            )
        );

        return snapshots;
    }

    private void recordStatus(
            ReturnRequest request,
            ReturnStatus previous,
            ReturnStatus next,
            User actor,
            LocalDateTime changedAt,
            String note) {

        saveHistory(
            request,
            newHistory(request, previous, next, actor, changedAt, note)
        );
    }

    private ReturnStatusHistory newHistory(
            ReturnRequest request,
            ReturnStatus previous,
            ReturnStatus next,
            User actor,
            LocalDateTime changedAt,
            String note) {

        var history = new ReturnStatusHistory();
        history.setReturnRequest(request);
        history.setOldStatus(previous);
        history.setNewStatus(next);
        history.setChangedBy(actor);
        history.setChangedAt(changedAt);
        history.setNote(note);

        return history;
    }

    private void saveHistory(
            ReturnRequest request,
            ReturnStatusHistory history) {

        // Khởi tạo collection trước INSERT để không thêm trùng bản ghi vừa lưu.
        request.getStatusHistory().size();
        historyRepository.save(history);
        request.getStatusHistory().add(history);
    }

    private String gatewayRefundCaveat(CustomerOrder order) {
        return order.getPaymentMethod() == PaymentMethod.VNPAY
                || order.getPaymentMethod() == PaymentMethod.MOMO
            ? " Cổng thanh toán chưa xác nhận hoàn tiền."
            : "";
    }

    private ReturnRequest lockForShop(
            User account,
            Long requestId) {

        requirePositiveId(requestId);

        var candidate = returnRequestRepository
            .findDetailedById(requestId)
            .orElseThrow(this::notFound);

        ensureShopAccess(account, candidate);

        var request = lockRequest(candidate);

        // Kiểm tra lại sau khi lấy khóa và refresh.
        ensureShopAccess(account, request);

        return request;
    }

    private ReturnRequest lockRequest(ReturnRequest candidate) {
        Long orderId = candidate.getOrder().getId();

        // Các thao tác đều khóa theo cùng thứ tự: Order -> ReturnRequest.
        var order = orderRepository
            .findOrderForDeliveryUpdate(orderId)
            .orElseThrow(this::notFound);

        entityManager.refresh(
            order,
            LockModeType.PESSIMISTIC_WRITE
        );

        var request = returnRequestRepository
            .findById(candidate.getId())
            .orElseThrow(this::notFound);

        entityManager.refresh(
            request,
            LockModeType.PESSIMISTIC_WRITE
        );

        if (!request.getOrder().getId().equals(order.getId())) {
            throw new IllegalStateException(
                "Liên kết đơn hàng của yêu cầu trả hàng không hợp lệ."
            );
        }

        request.setOrder(order);
        requireConsistentRequest(request);

        return request;
    }

    private void prepareDetail(ReturnRequest request) {
        requireConsistentRequest(request);

        // Tải riêng hai collection để tránh fetch đồng thời hai List.
        request.getMedia().size();
        request.getOrder().getItems().size();
    }

    private void requireConsistentRequest(ReturnRequest request) {
        var order = request.getOrder();

        if (!order.getUser().getId().equals(request.getUser().getId())
                || !order.getShop().getId().equals(
                    request.getShop().getId()
                )) {
            throw new IllegalStateException(
                "Dữ liệu chủ đơn hoặc cửa hàng của yêu cầu không hợp lệ."
            );
        }
    }

    private void requireActiveReturnOrder(ReturnRequest request) {
        if (request.getOrder().getStatus()
                    != OrderStatus.RETURN_REQUESTED
                || request.getOrder().getPaymentStatus()
                    != PaymentStatus.PAID) {
            throw new IllegalArgumentException(
                "Đơn hàng không còn ở trạng thái chờ xử lý trả hàng."
            );
        }
    }

    private ReturnRequestForm validatedForm(ReturnRequestForm form) {
        if (form == null) {
            throw new IllegalArgumentException(
                "Thông tin yêu cầu trả hàng không hợp lệ."
            );
        }

        var safe = new ReturnRequestForm();
        safe.setReason(form.getReason());
        safe.setDescription(
            form.getDescription() == null
                ? null
                : form.getDescription().trim()
        );
        safe.setRefundAmount(form.getRefundAmount());

        var violations = validator.validate(safe);

        if (!violations.isEmpty()) {
            var message = violations.stream()
                .map(violation -> violation.getMessage())
                .sorted()
                .findFirst()
                .orElse("Thông tin yêu cầu trả hàng không hợp lệ.");

            throw new IllegalArgumentException(message);
        }

        return safe;
    }

    private BigDecimal validateRefundAmount(
            BigDecimal amount,
            CustomerOrder order) {

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                "Số tiền hoàn phải lớn hơn 0."
            );
        }

        BigDecimal normalized;

        try {
            normalized = amount.setScale(
                2,
                RoundingMode.UNNECESSARY
            );
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                "Số tiền hoàn chỉ được có tối đa 2 chữ số thập phân."
            );
        }

        if (normalized.compareTo(
                new BigDecimal("999999999999.99")) > 0) {
            throw new IllegalArgumentException(
                "Số tiền hoàn vượt giới hạn cho phép."
            );
        }

        if (normalized.compareTo(order.getTotalAmount()) != 0) {
            throw new IllegalArgumentException(
                "Yêu cầu trả hàng áp dụng cho toàn bộ đơn, "
                    + "số tiền hoàn phải bằng tổng thanh toán "
                    + order.getTotalAmount()
                        .stripTrailingZeros()
                        .toPlainString()
                    + "đ."
            );
        }

        return normalized;
    }

    private String requiredNote(String note, String label) {
        var safe = optionalNote(note);

        if (safe.length() < 5) {
            throw new IllegalArgumentException(
                label + " phải từ 5 đến 1.000 ký tự."
            );
        }

        return safe;
    }

    private String optionalNote(String note) {
        var safe = note == null ? "" : note.trim();

        if (safe.length() > 1000) {
            throw new IllegalArgumentException(
                "Ghi chú không được vượt quá 1.000 ký tự."
            );
        }

        return safe;
    }

    private void requireStatus(
            ReturnRequest request,
            ReturnStatus expected) {

        if (request.getStatus() != expected) {
            throw new IllegalArgumentException(
                "Yêu cầu không ở trạng thái "
                    + expected.getLabel()
                    + ". Vui lòng tải lại trang."
            );
        }
    }

    private User requireAccount(User actor) {
        if (actor == null || actor.getId() == null) {
            throw forbidden();
        }

        return userRepository.findById(actor.getId())
            .filter(account -> account.isEnabled() && !account.isLocked())
            .orElseThrow(this::forbidden);
    }

    private User requireBuyer(User actor) {
        var account = requireAccount(actor);

        if (account.getRole() != Role.USER
                && account.getRole() != Role.VENDOR) {
            throw forbidden();
        }

        return account;
    }

    private Shop requireVendorShop(User account) {
        if (account.getRole() != Role.VENDOR) {
            throw forbidden();
        }

        return shopRepository.findByOwnerId(account.getId())
            .orElseThrow(this::notFound);
    }

    private boolean canAccessShop(User account, Shop shop) {
        if (account.getRole() == Role.ADMIN) {
            return true;
        }

        if (account.getRole() == Role.VENDOR) {
            return shop.getOwner().getId().equals(account.getId());
        }

        return account.getRole() == Role.MANAGER
            && managerShopRepository.existsByManagerIdAndShopId(
                account.getId(),
                shop.getId()
            );
    }

    private void ensureShopAccess(
            User account,
            ReturnRequest request) {

        if (!canAccessShop(account, request.getShop())) {
            throw notFound();
        }
    }

    private void ensureRefundConfirmationAccess(
            User account,
            ReturnRequest request) {

        boolean allowed = account.getRole() == Role.ADMIN
            || (account.getRole() == Role.VENDOR
                && request.getShop().getOwner().getId()
                    .equals(account.getId()));

        if (!allowed) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Chỉ chủ cửa hàng hoặc Admin được xác nhận đã hoàn tiền."
            );
        }
    }

    private void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw notFound();
        }
    }

    private void notifyUser(
            User user,
            String title,
            String message,
            String link) {

        notificationService.send(
            user,
            title,
            shorten(message, 1000),
            link
        );
    }

    private String shorten(String value, int maximum) {
        return value.length() <= maximum
            ? value
            : value.substring(0, maximum - 1) + "…";
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Không tìm thấy yêu cầu trả hàng hoặc đơn hàng trong phạm vi của bạn."
        );
    }

    private ResponseStatusException forbidden() {
        return new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "Bạn không có quyền thực hiện thao tác trả hàng này."
        );
    }
}