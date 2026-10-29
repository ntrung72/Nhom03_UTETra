package vn.iotstar.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import jakarta.persistence.EntityManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.AuditAction;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentMethod;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.PaymentTransactionStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.PaymentTransaction;
import vn.iotstar.entity.User;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.PaymentTransactionRepository;
import vn.iotstar.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class BankTransferService {
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final EntityManager entityManager;
    private final PaymentTransactionRepository transactions;
    private final OrderRepository orders;
    private final UserRepository users;
    private final OrderService orderService;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final PayOsClient payOsClient;

    @Value("${app.bank-transfer.bank-name:BIDV}") private String bankName;
    @Value("${app.bank-transfer.bank-bin:970418}") private String bankBin;
    @Value("${app.bank-transfer.account-number:}") private String accountNumber;
    @Value("${app.bank-transfer.account-name:}") private String accountName;
    @Value("${app.bank-transfer.expiration-hours:24}") private int expirationHours;

    public void requireConfigured() {
        if (bankBin == null || !bankBin.matches("[0-9]{6}")
            || accountNumber == null || !accountNumber.matches("[0-9A-Za-z]{1,19}")
            || accountName == null || accountName.isBlank()) {
            throw new IllegalArgumentException("Tài khoản nhận chuyển khoản chưa được cấu hình hợp lệ.");
        }
    }

    @Transactional
    public String createPayment(List<CustomerOrder> checkoutOrders) {
        payOsClient.requireConfigured();
        if (checkoutOrders == null || checkoutOrders.isEmpty()
            || checkoutOrders.stream().anyMatch(order -> order.getPaymentMethod() != PaymentMethod.BANK_TRANSFER
                || order.getStatus() != OrderStatus.NEW || order.getPaymentStatus() != PaymentStatus.UNPAID
                || order.getTotalAmount() == null || order.getTotalAmount().signum() <= 0
                || order.getCheckoutReference() == null
                || !order.getCheckoutReference().equals(checkoutOrders.getFirst().getCheckoutReference())
                || !order.getUser().getId().equals(checkoutOrders.getFirst().getUser().getId()))) {
            throw new IllegalArgumentException("Đơn chuyển khoản không hợp lệ.");
        }
        LocalDateTime expiresAt = LocalDateTime.now(VIETNAM_ZONE).plusHours(Math.max(1, expirationHours));
        List<PaymentTransaction> created = new ArrayList<>();
        try {
            for (CustomerOrder order : checkoutOrders) {
                PaymentTransaction payment = new PaymentTransaction();
                payment.setOrder(order);
                payment.setTransactionCode(order.getCode());
                payment.setAmount(order.getTotalAmount());
                payment.setExpiresAt(expiresAt);
                transactions.save(payment);
                payment.setGatewayTransactionId(payOsClient.create(payment));
                created.add(payment);
            }
        } catch (RuntimeException ex) {
            for (PaymentTransaction payment : created) {
                try { payOsClient.cancel(payment); } catch (RuntimeException ignored) { }
            }
            throw ex;
        }
        return checkoutOrders.getFirst().getCheckoutReference();
    }

    @Transactional(readOnly = true)
    public List<BankTransferDetails> checkoutDetails(User buyer, String checkoutReference) {
        List<CustomerOrder> checkoutOrders = orders.findByCheckoutReferenceAndUserIdOrderByIdAsc(
            checkoutReference, buyer.getId());
        if (checkoutOrders.isEmpty() || checkoutOrders.stream()
            .anyMatch(order -> order.getPaymentMethod() != PaymentMethod.BANK_TRANSFER)) {
            throw new IllegalArgumentException("Không tìm thấy lần đặt hàng chuyển khoản của bạn.");
        }
        return checkoutOrders.stream().map(order -> details(buyer, order.getCode())).toList();
    }

    @Transactional(readOnly = true)
    public BankTransferDetails details(User buyer, String reference) {
        List<PaymentTransaction> payments = ownedPayments(buyer, reference);
        BigDecimal total = payments.stream().map(PaymentTransaction::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean payable = payments.stream().allMatch(payment -> payment.getOrder().getStatus() == OrderStatus.NEW
            && payment.getOrder().getPaymentStatus() == PaymentStatus.UNPAID
            && (payment.getStatus() == PaymentTransactionStatus.PENDING
                || payment.getStatus() == PaymentTransactionStatus.AWAITING_REVIEW));
        boolean awaiting = payments.stream().allMatch(payment -> payment.getStatus() == PaymentTransactionStatus.AWAITING_REVIEW);
        boolean paid = payments.stream().allMatch(payment -> payment.getStatus() == PaymentTransactionStatus.SUCCESS
            && payment.getOrder().getPaymentStatus() == PaymentStatus.PAID);
        return new BankTransferDetails(reference, bankName, accountNumber, accountName, total,
            payments.getFirst().getExpiresAt(), payable, awaiting, paid,
            payments.stream().map(PaymentTransaction::getOrder).toList(),
            payOsClient.isPayOs(payments.getFirst()) ? payments.getFirst().getGatewayTransactionId() : null,
            payments.getFirst().getId());
    }

    @Transactional
    public void markSent(User buyer, String reference) {
        List<PaymentTransaction> payments = transactions.findByCodeForUpdate(reference);
        payments.forEach(entityManager::refresh);
        requireOwned(buyer, payments);
        if (payments.stream().anyMatch(payOsClient::isPayOs)) {
            throw new IllegalArgumentException("payOS tự động xác nhận thanh toán; bạn không cần báo đã chuyển tiền.");
        }
        if (payments.stream().anyMatch(payment -> payment.getOrder().getStatus() != OrderStatus.NEW
            || payment.getOrder().getPaymentStatus() != PaymentStatus.UNPAID
            || (payment.getStatus() != PaymentTransactionStatus.PENDING
                && payment.getStatus() != PaymentTransactionStatus.AWAITING_REVIEW))) {
            throw new IllegalArgumentException("Đơn không còn chờ chuyển khoản.");
        }
        boolean newlyReported = payments.stream()
            .anyMatch(payment -> payment.getStatus() == PaymentTransactionStatus.PENDING);
        payments.forEach(payment -> payment.setStatus(PaymentTransactionStatus.AWAITING_REVIEW));
        if (newlyReported) {
            users.findByRoleAndEnabledTrueAndLockedFalseOrderByFullNameAsc(Role.ADMIN)
                .forEach(admin -> notificationService.send(admin, "Khách báo đã chuyển khoản",
                    "Đơn " + reference + " đang chờ kiểm tra tiền vào BIDV trước khi xác nhận.",
                    "/management/payments?q=" + reference));
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void confirm(User admin, Long paymentId, String bankTransactionId, BigDecimal receivedAmount) {
        if (admin.getRole() != Role.ADMIN) throw new IllegalArgumentException("Chỉ Admin được xác nhận tiền vào.");
        PaymentTransaction selected = transactions.findById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy giao dịch."));
        String reference = selected.getTransactionCode();
        List<PaymentTransaction> group = transactions.findByCodeForUpdate(reference);
        group.forEach(entityManager::refresh);
        if (group.stream().anyMatch(payOsClient::isPayOs)) {
            throw new IllegalArgumentException("Giao dịch payOS phải được xác nhận qua webhook hoặc kiểm tra lại với payOS.");
        }
        if (group.isEmpty() || group.stream().anyMatch(payment -> payment.getOrder().getPaymentMethod() != PaymentMethod.BANK_TRANSFER
            || (payment.getStatus() != PaymentTransactionStatus.PENDING
                && payment.getStatus() != PaymentTransactionStatus.AWAITING_REVIEW))) {
            throw new IllegalArgumentException("Giao dịch không còn chờ xác nhận.");
        }
        BigDecimal expected = group.stream().map(PaymentTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (receivedAmount == null || receivedAmount.compareTo(expected) != 0) {
            throw new IllegalArgumentException("Số tiền thực nhận không khớp tổng tiền cần chuyển.");
        }
        String bankId = bankTransactionId == null ? "" : bankTransactionId.trim();
        if (bankId.length() < 4 || bankId.length() > 100) {
            throw new IllegalArgumentException("Nhập mã giao dịch ngân hàng từ 4 đến 100 ký tự để đối chiếu.");
        }
        if (transactions.findByGatewayTransactionId(bankId).stream()
            .anyMatch(other -> !reference.equals(other.getTransactionCode()))) {
            throw new IllegalArgumentException("Mã giao dịch ngân hàng đã được dùng cho đơn khác.");
        }
        List<CustomerOrder> lockedOrders = group.stream()
            .map(payment -> orders.findDetailedByIdForUpdate(payment.getOrder().getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."))).toList();
        lockedOrders.forEach(entityManager::refresh);
        if (lockedOrders.stream().anyMatch(order -> order.getStatus() != OrderStatus.NEW
            || order.getPaymentStatus() != PaymentStatus.UNPAID)) {
            throw new IllegalArgumentException("Có đơn trong lần thanh toán đã đổi trạng thái; cần đối soát thủ công.");
        }
        LocalDateTime now = LocalDateTime.now(VIETNAM_ZONE);
        group.forEach(payment -> {
            payment.setStatus(PaymentTransactionStatus.SUCCESS);
            payment.setPaidAt(now);
            payment.setGatewayTransactionId(bankId);
        });
        lockedOrders.forEach(order -> {
            order.setPaymentStatus(PaymentStatus.PAID);
            notificationService.send(order.getUser(), "Đã xác nhận thanh toán",
                "UTETra đã nhận tiền cho đơn " + order.getCode() + ".", "/orders/" + order.getId());
            notificationService.send(order.getShop().getOwner(), "Có đơn hàng mới",
                "Đơn " + order.getCode() + " đã được xác nhận thanh toán và đang chờ shop xử lý.", "/vendor/orders");
        });
        auditLogService.record(AuditAction.BANK_TRANSFER_CONFIRMED, admin, "BANK_TRANSFER", selected.getId(),
            reference, "Chờ xác nhận", "Đã thanh toán", "Mã giao dịch ngân hàng: " + bankId);
    }

    @Transactional
    public void cancelUnreceived(User admin, Long paymentId) {
        if (admin.getRole() != Role.ADMIN) throw new IllegalArgumentException("Chỉ Admin được đóng đơn chưa nhận tiền.");
        PaymentTransaction selected = transactions.findById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy giao dịch."));
        List<PaymentTransaction> group = transactions.findByCodeForUpdate(selected.getTransactionCode());
        group.forEach(entityManager::refresh);
        if (group.stream().anyMatch(payOsClient::isPayOs)) {
            throw new IllegalArgumentException("Vui lòng kiểm tra trạng thái link payOS trước khi đóng đơn.");
        }
        if (group.size() != 1 || group.getFirst().getOrder().getPaymentMethod() != PaymentMethod.BANK_TRANSFER
            || (group.getFirst().getStatus() != PaymentTransactionStatus.PENDING
                && group.getFirst().getStatus() != PaymentTransactionStatus.AWAITING_REVIEW)) {
            throw new IllegalArgumentException("Giao dịch không còn chờ xác nhận.");
        }
        CustomerOrder order = group.getFirst().getOrder();
        if (order.getStatus() != OrderStatus.NEW || order.getPaymentStatus() != PaymentStatus.UNPAID) {
            throw new IllegalArgumentException("Đơn đã thay đổi trạng thái; cần kiểm tra lại trước khi đóng.");
        }
        orderService.managementUpdate(admin, order.getId(), OrderStatus.CANCELLED);
        group.getFirst().setStatus(PaymentTransactionStatus.CANCELLED);
        notificationService.send(order.getUser(), "Đơn chuyển khoản đã đóng",
            "Đơn " + order.getCode() + " đã đóng vì Admin chưa ghi nhận tiền chuyển khoản. Nếu bạn đã chuyển, hãy liên hệ để đối chiếu.",
            "/orders/" + order.getId());
    }

    @Transactional(readOnly = true)
    public BigDecimal totalForReference(String reference) {
        return transactions.findByTransactionCodeOrderByIdAsc(reference).stream()
            .map(PaymentTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public byte[] qrImage(User buyer, String reference) {
        BankTransferDetails details = details(buyer, reference);
        if (details.payOsUrl() != null) throw new IllegalArgumentException("Hãy mở link payOS để xem mã QR thanh toán.");
        if (!details.payable()) throw new IllegalArgumentException("Mã QR của đơn này không còn hiệu lực.");
        String payload = payload(details.total(), reference);
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 400, 400, hints);
            BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xFF11233B : 0xFFFFFFFF);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Không tạo được mã QR chuyển khoản.", ex);
        }
    }

    String payload(BigDecimal amount, String reference) {
        requireConfigured();
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("Số tiền không hợp lệ.");
        String beneficiary = field("00", bankBin) + field("01", accountNumber);
        String account = field("00", "A000000727") + field("01", beneficiary) + field("02", "QRIBFTTA");
        String body = field("00", "01") + field("01", "12") + field("38", account)
            + field("53", "704") + field("54", amount.toBigIntegerExact().toString())
            + field("58", "VN") + field("59", accountName.toUpperCase(Locale.ROOT))
            + field("62", field("08", reference)) + "6304";
        int crc = 0xFFFF;
        for (byte value : body.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
            crc ^= (value & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            crc &= 0xFFFF;
        }
        return body + String.format(Locale.ROOT, "%04X", crc);
    }

    private static String field(String id, String value) {
        if (value.length() > 99) throw new IllegalArgumentException("Thông tin mã QR quá dài.");
        return id + String.format(Locale.ROOT, "%02d", value.length()) + value;
    }

    private List<PaymentTransaction> ownedPayments(User buyer, String reference) {
        List<PaymentTransaction> payments = transactions.findByTransactionCodeOrderByIdAsc(reference);
        requireOwned(buyer, payments);
        return payments;
    }

    private static void requireOwned(User buyer, List<PaymentTransaction> payments) {
        if (payments.isEmpty() || payments.stream().anyMatch(payment ->
            payment.getOrder().getPaymentMethod() != PaymentMethod.BANK_TRANSFER
                || !payment.getOrder().getUser().getId().equals(buyer.getId()))) {
            throw new IllegalArgumentException("Không tìm thấy lần chuyển khoản của bạn.");
        }
    }

    public record BankTransferDetails(String reference, String bankName, String accountNumber,
                                      String accountName, BigDecimal total, LocalDateTime expiresAt,
                                      boolean payable, boolean awaiting, boolean paid,
                                       List<CustomerOrder> orders, String payOsUrl, Long paymentId) {}
}
