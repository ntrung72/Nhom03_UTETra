package vn.iotstar.service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.config.VnPayProperties;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;

@Service @RequiredArgsConstructor
@Transactional(readOnly = true)
public class VnPayService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final VnPayProperties properties;
    private final PaymentTransactionRepository transactions;
    private final OrderRepository orders;
    private final UserRepository users;
    private final OrderService orderService;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    public boolean isConfigured() { return properties.isConfigured(); }
    public void requireConfigured() {
        if (!isConfigured()) throw new IllegalArgumentException("Thanh toán VNPay chưa sẵn sàng. Vui lòng chọn COD.");
    }

    @Transactional
    public String createPaymentUrl(User buyer, List<CustomerOrder> created, String clientIp) {
        requireConfigured();
        if (created == null || created.isEmpty()) throw new IllegalArgumentException("Không có đơn hàng để thanh toán.");
        var group = lockCheckout(created.getFirst().getCheckoutReference(), buyer.getId());
        return paymentUrl(group, clientIp);
    }

    @Transactional
    public String retryPaymentUrl(User buyer, Long orderId, String clientIp) {
        requireConfigured();
        var owned = orders.findOwnedPaymentSummary(orderId, buyer.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return paymentUrl(lockCheckout(owned.getCheckoutReference(), buyer.getId()), clientIp);
    }

    private String paymentUrl(List<CustomerOrder> group, String clientIp) {
        if (group.isEmpty() || group.stream().anyMatch(o -> o.getPaymentMethod() != PaymentMethod.VNPAY
                || o.getStatus() != OrderStatus.NEW || o.getPaymentStatus() != PaymentStatus.UNPAID
                || o.getPaymentExpiresAt() == null || !o.getPaymentExpiresAt().isAfter(now())))
            throw new IllegalArgumentException("Chỉ đơn VNPay mới, chưa thanh toán và còn hạn mới được thanh toán.");
        var deadline = group.stream().map(CustomerOrder::getPaymentExpiresAt).min(LocalDateTime::compareTo).orElseThrow();
        var total = group.stream().map(CustomerOrder::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String encodedAmount = amount(total);
        String checkout = group.getFirst().getCheckoutReference();
        var previous = transactions.findFirstByCheckoutReferenceOrderByIdDesc(checkout).orElse(null);
        PaymentTransaction attempt;
        if (previous != null && previous.getStatus() == PaymentTransactionStatus.PENDING) {
            // Reuse a live attempt: opening multiple tabs must not create multiple charge references.
            attempt = previous;
        } else {
            String reference = "UT" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
            String ip = clientIp == null || clientIp.isBlank() || "0:0:0:0:0:0:0:1".equals(clientIp) ? "127.0.0.1" : clientIp;
            if (!ip.matches("[0-9A-Fa-f:.]{3,45}")) ip = "127.0.0.1";
            var createdAt = now();
            for (var order : group) {
                var row = new PaymentTransaction(); row.setOrder(order); row.setCheckoutReference(checkout);
                row.setTransactionCode(reference); row.setAmount(order.getTotalAmount()); row.setExpiresAt(deadline);
                row.setCreatedAt(createdAt); row.setClientIp(ip); transactions.save(row);
            }
            transactions.flush();
            attempt = transactions.findFirstByTransactionCodeOrderByIdAsc(reference).orElseThrow();
        }
        var params = new TreeMap<String,String>();
        params.put("vnp_Version", "2.1.0"); params.put("vnp_Command", "pay"); params.put("vnp_TmnCode", properties.getTmnCode());
        params.put("vnp_Amount", encodedAmount); params.put("vnp_CurrCode", "VND"); params.put("vnp_TxnRef", attempt.getTransactionCode());
        params.put("vnp_OrderInfo", "Thanh toan UTETra " + attempt.getTransactionCode()); params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn"); params.put("vnp_ReturnUrl", properties.getReturnUrl()); params.put("vnp_IpAddr", attempt.getClientIp());
        params.put("vnp_CreateDate", attempt.getCreatedAt().format(TIME)); params.put("vnp_ExpireDate", deadline.format(TIME));
        String data = canonical(params);
        return properties.getPaymentUrl() + "?" + data + "&vnp_SecureHash=" + sign(data);
    }

    /** Only the server-to-server IPN is permitted to settle payment. */
    @Transactional
    public CallbackResult processCallback(Map<String,String> callback) {
        if (!hasValidSignature(callback) || !Objects.equals(properties.getTmnCode(), callback.get("vnp_TmnCode")))
            return result(false, "97", "Invalid signature");
        String response = callback.get("vnp_ResponseCode"), state = callback.get("vnp_TransactionStatus");
        if (response == null || state == null || !response.matches("[0-9]{2}") || !state.matches("[0-9]{2}"))
            return result(false, "97", "Invalid response fields");
        var candidate = transactions.findFirstByTransactionCodeOrderByIdAsc(callback.get("vnp_TxnRef")).orElse(null);
        if (candidate == null) return result(false, "01", "Order not found");
        var group = lockCheckout(candidate.getCheckoutReference(), candidate.getOrder().getUser().getId());
        var rows = transactions.findByCodeForUpdate(candidate.getTransactionCode());
        rows.forEach(t -> entityManager.refresh(t, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
        var total = rows.stream().map(PaymentTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!amount(total).equals(callback.get("vnp_Amount"))) return result(false, "04", "Invalid amount");
        if (rows.size() != group.size() || !new HashSet<>(rows.stream().map(t -> t.getOrder().getId()).toList())
                .equals(new HashSet<>(group.stream().map(CustomerOrder::getId).toList())))
            return result(false, "99", "Checkout data mismatch");
        boolean paid = "00".equals(response) && "00".equals(state);
        if (rows.stream().anyMatch(t -> t.getStatus() != PaymentTransactionStatus.PENDING)) {
            boolean settled = rows.stream().allMatch(t -> t.getStatus() == PaymentTransactionStatus.SUCCESS);
            if (!settled && paid) flagLateSuccess(rows, callback);
            return result(settled, "02", "Order already confirmed");
        }
        if (group.stream().anyMatch(o -> o.getStatus() != OrderStatus.NEW || o.getPaymentStatus() != PaymentStatus.UNPAID
                || o.getPaymentMethod() != PaymentMethod.VNPAY || o.getPaymentExpiresAt() == null
                || !o.getPaymentExpiresAt().isAfter(now())))
            { if (paid) flagLateSuccess(rows, callback); return result(false, "02", "Order cancelled or expired"); }
        LocalDateTime paidAt = null;
        if (paid) {
            String providerNo = callback.get("vnp_TransactionNo");
            try {
                if (providerNo == null || !providerNo.matches("[0-9]{1,30}")) return result(false, "97", "Invalid provider reference");
                paidAt = LocalDateTime.parse(callback.get("vnp_PayDate"), TIME);
                var earliest = rows.stream().map(PaymentTransaction::getCreatedAt).min(LocalDateTime::compareTo).orElseThrow();
                var deadline = rows.getFirst().getExpiresAt();
                if (paidAt.isBefore(earliest.minusMinutes(1)) || paidAt.isAfter(deadline) || paidAt.isAfter(now().plusMinutes(1)))
                    return result(false, "97", "Invalid payment time");
            } catch (RuntimeException ex) { return result(false, "97", "Invalid payment time"); }
        }
        for (var row : rows) {
            row.setResponseCode(response); row.setProviderStatus(state); row.setCompletedAt(now());
            String providerNo = callback.get("vnp_TransactionNo");
            row.setProviderTransactionNo(providerNo != null && providerNo.matches("[0-9]{1,30}") ? providerNo : null);
            row.setStatus(paid ? PaymentTransactionStatus.SUCCESS : PaymentTransactionStatus.FAILED);
            if (paid) { row.setPaidAt(paidAt); row.getOrder().setPaymentStatus(PaymentStatus.PAID); }
        }
        return result(paid, "00", "Confirm success");
    }

    public boolean returnSuccessful(Map<String,String> callback) {
        return hasValidSignature(callback) && Objects.equals(properties.getTmnCode(), callback.get("vnp_TmnCode"))
            && "00".equals(callback.get("vnp_ResponseCode")) && "00".equals(callback.get("vnp_TransactionStatus"));
    }

    /** ReturnURL displays a message and never updates orders or payment transactions. */
    public String returnMessage(Map<String,String> callback) {
        if (!hasValidSignature(callback) || !Objects.equals(properties.getTmnCode(), callback.get("vnp_TmnCode")))
            return "Không xác minh được kết quả thanh toán. Vui lòng kiểm tra trạng thái đơn hàng.";
        return "00".equals(callback.get("vnp_ResponseCode")) && "00".equals(callback.get("vnp_TransactionStatus"))
            ? "Đã nhận kết quả từ VNPay. Trạng thái đơn sẽ cập nhật sau xác nhận thanh toán."
            : "Thanh toán chưa thành công. Bạn có thể tiếp tục thanh toán trong thời hạn của đơn.";
    }

    public PaymentTransaction latestForOrder(User buyer, Long id) {
        orderService.getForUser(buyer, id);
        return transactions.findFirstByOrderIdOrderByCreatedAtDescIdDesc(id).orElse(null);
    }

    @Transactional
    public void cancelCheckout(User buyer, Long id) {
        var owned = orders.findOwnedPaymentSummary(id, buyer.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var group = lockCheckout(owned.getCheckoutReference(), buyer.getId());
        if (group.stream().anyMatch(o -> o.getPaymentMethod() != PaymentMethod.VNPAY || o.getStatus() != OrderStatus.NEW
                || o.getPaymentStatus() != PaymentStatus.UNPAID)) throw new IllegalArgumentException("Chỉ có thể hủy các đơn VNPay mới chưa thanh toán.");
        cancelOrders(group);
        transactions.findForCheckout(owned.getCheckoutReference()).stream().filter(t -> t.getStatus() == PaymentTransactionStatus.PENDING)
            .forEach(t -> { t.setStatus(PaymentTransactionStatus.CANCELLED); t.setCompletedAt(now()); });
    }

    @Transactional
    public int expireCheckout(String checkoutReference) {
        var candidate = transactions.findFirstByCheckoutReferenceOrderByIdDesc(checkoutReference).orElse(null);
        if (candidate == null) return 0;
        var group = lockCheckout(checkoutReference, candidate.getOrder().getUser().getId());
        if (group.stream().anyMatch(o -> o.getPaymentMethod() != PaymentMethod.VNPAY || o.getPaymentStatus() != PaymentStatus.UNPAID
                || (o.getStatus() != OrderStatus.NEW && o.getStatus() != OrderStatus.CANCELLED)
                || o.getPaymentExpiresAt() == null || o.getPaymentExpiresAt().isAfter(now()))) return 0;
        if (group.stream().noneMatch(o -> o.getStatus() == OrderStatus.NEW)) return 0;
        cancelOrders(group);
        int count = 0;
        for (var row : transactions.findForCheckout(checkoutReference)) {
            if (row.getStatus() == PaymentTransactionStatus.PENDING) {
                row.setStatus(PaymentTransactionStatus.EXPIRED); row.setCompletedAt(now()); count++;
            }
        }
        return count;
    }

    private void flagLateSuccess(List<PaymentTransaction> rows, Map<String,String> callback) {
        // Keep evidence for reconciliation without reviving cancelled orders or reserved stock.
        for (var row : rows) {
            row.setReconciliationRequired(true); row.setResponseCode(callback.get("vnp_ResponseCode"));
            row.setProviderStatus(callback.get("vnp_TransactionStatus"));
            String number = callback.get("vnp_TransactionNo");
            if (number != null && number.matches("[0-9]{1,30}")) row.setProviderTransactionNo(number);
        }
    }

    public boolean needsReconciliation(User buyer, Long orderId) {
        orderService.getForUser(buyer, orderId);
        return transactions.existsByOrderIdAndReconciliationRequiredTrue(orderId);
    }

    private void cancelOrders(List<CustomerOrder> group) {
        // Acquire all product locks in ID order before restoring multiple shop orders.
        group.stream().flatMap(o -> o.getItems().stream()).map(OrderItem::getProduct).distinct()
            .sorted(Comparator.comparing(Product::getId))
            .forEach(p -> entityManager.refresh(p, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
        for (var order : group) if (order.getStatus() == OrderStatus.NEW) orderService.cancelByUser(order.getUser(), order.getId());
    }

    private List<CustomerOrder> lockCheckout(String reference, Long ownerId) {
        if (reference == null || reference.isBlank()) throw new IllegalArgumentException("Không tìm thấy lần đặt hàng.");
        users.findByIdForUpdate(ownerId).orElseThrow();
        var group = orders.findByCheckoutReferenceOrderByIdAsc(reference);
        if (group.isEmpty() || group.stream().anyMatch(o -> !o.getUser().getId().equals(ownerId)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        group.forEach(o -> entityManager.refresh(o, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
        return group;
    }

    public boolean hasValidSignature(Map<String,String> callback) {
        if (!isConfigured() || callback == null) return false;
        String supplied = callback.get("vnp_SecureHash");
        if (supplied == null || !supplied.matches("[A-Fa-f0-9]{128}")) return false;
        var fields = new TreeMap<>(callback); fields.remove("vnp_SecureHash"); fields.remove("vnp_SecureHashType");
        return MessageDigest.isEqual(HexFormat.of().parseHex(sign(canonical(fields))), HexFormat.of().parseHex(supplied));
    }
    private String canonical(Map<String,String> params) {
        return new TreeMap<>(params).entrySet().stream().filter(e -> e.getKey().startsWith("vnp_") && e.getValue() != null && !e.getValue().isEmpty())
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(java.util.stream.Collectors.joining("&"));
    }
    private String sign(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA512"); mac.init(new SecretKeySpec(properties.getHashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException("Không thể xác minh thanh toán.", ex); }
    }
    private String amount(BigDecimal total) {
        try {
            String value = total.movePointRight(2).toBigIntegerExact().toString();
            if (total.signum() <= 0 || value.length() > 12) throw new IllegalArgumentException("Số tiền không phù hợp để thanh toán VNPay. Vui lòng chọn COD.");
            return value;
        } catch (ArithmeticException ex) { throw new IllegalArgumentException("Số tiền thanh toán không hợp lệ."); }
    }
    private LocalDateTime now() { return LocalDateTime.now(ZONE); }
    private CallbackResult result(boolean success, String code, String message) { return new CallbackResult(success, code, message, "02".equals(code)); }
    public record CallbackResult(boolean success, String responseCode, String message, boolean alreadyProcessed) {}
}
