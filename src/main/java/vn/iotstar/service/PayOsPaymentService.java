package vn.iotstar.service;

import tools.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentMethod;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.PaymentTransactionStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.PaymentTransaction;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.PaymentTransactionRepository;
import vn.iotstar.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class PayOsPaymentService {
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final EntityManager entityManager;
    private final PaymentTransactionRepository transactions;
    private final OrderRepository orders;
    private final UserRepository users;
    private final NotificationService notifications;
    private final PayOsClient payOs;
    private final OrderService orderService;

    @Transactional
    public void receive(JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("Webhook payOS không hợp lệ.");
        JsonNode data = body.path("data");
        if (!payOs.validWebhookSignature(data, body.path("signature").asText(null))) {
            throw new IllegalArgumentException("Chữ ký webhook payOS không hợp lệ.");
        }
        if (!body.path("success").asBoolean(false) || !"00".equals(body.path("code").asText())
            || !"00".equals(data.path("code").asText())) return;
        long code = data.path("orderCode").asLong(-1);
        long paymentId = payOs.paymentIdFromOrderCode(code);
        if (paymentId < 1) return;
        PaymentTransaction payment = transactions.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null) {
            if (code != 123) users.findByRoleAndEnabledTrueAndLockedFalseOrderByFullNameAsc(Role.ADMIN)
                .forEach(admin -> notifications.send(admin, "Giao dịch payOS chưa có đơn khớp",
                    "payOS báo thanh toán cho mã giao dịch #" + code + " nhưng UTETra không tìm thấy đơn tương ứng.",
                    "/management/payments"));
            return;
        }
        entityManager.refresh(payment);
        if (!payOs.isPayOs(payment)) return;
        if (payment.getStatus() == PaymentTransactionStatus.SUCCESS) return;

        String expectedLinkId = payment.getGatewayTransactionId().substring("https://pay.payos.vn/web/".length());
        boolean matched = expectedLinkId.equals(data.path("paymentLinkId").asText())
            && payment.getAmount().compareTo(BigDecimal.valueOf(data.path("amount").asLong(-1))) == 0
            && "VND".equals(data.path("currency").asText())
            && payment.getOrder().getCode().equals(data.path("description").asText())
            && payment.getOrder().getPaymentMethod() == PaymentMethod.BANK_TRANSFER;
        if (!matched) {
            flag(payment, "payOS báo giao dịch không khớp mã link, số tiền hoặc mã đơn. Hãy kiểm tra trước khi xử lý.");
            return;
        }

        CustomerOrder order = orders.findDetailedByIdForUpdate(payment.getOrder().getId()).orElseThrow();
        entityManager.refresh(order);
        if (order.getStatus() != OrderStatus.NEW || order.getPaymentStatus() != PaymentStatus.UNPAID
            || payment.getStatus() != PaymentTransactionStatus.PENDING
                && payment.getStatus() != PaymentTransactionStatus.AWAITING_REVIEW) {
            flag(payment, "payOS báo đã thanh toán nhưng đơn đã đổi trạng thái. Cần kiểm tra tiền và đơn.");
            return;
        }
        payment.setStatus(PaymentTransactionStatus.SUCCESS);
        payment.setPaidAt(LocalDateTime.now(VIETNAM_ZONE));
        order.setPaymentStatus(PaymentStatus.PAID);
        notifications.send(order.getUser(), "Đã xác nhận thanh toán",
            "payOS đã xác nhận thanh toán đơn " + order.getCode() + ".", "/orders/" + order.getId());
        notifications.send(order.getShop().getOwner(), "Có đơn hàng mới",
            "Đơn " + order.getCode() + " đã thanh toán và đang chờ shop xử lý.", "/vendor/orders");
    }

    @Transactional
    public void reconcile(Long paymentId) {
        PaymentTransaction snapshot = transactions.findById(paymentId).orElse(null);
        if (snapshot == null || !payOs.isPayOs(snapshot)
            || snapshot.getStatus() != PaymentTransactionStatus.PENDING
                && snapshot.getStatus() != PaymentTransactionStatus.AWAITING_REVIEW) return;
        JsonNode data = payOs.get(snapshot);
        PaymentTransaction payment = transactions.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentTransactionStatus.PENDING
            && payment.getStatus() != PaymentTransactionStatus.AWAITING_REVIEW) return;
        entityManager.refresh(payment);
        if (payment.getStatus() != PaymentTransactionStatus.PENDING
            && payment.getStatus() != PaymentTransactionStatus.AWAITING_REVIEW) return;
        if (data.path("orderCode").asLong(-1) != payOs.orderCodeFor(payment)
            || !payment.getGatewayTransactionId().endsWith("/" + data.path("id").asText())
            || data.path("amount").asLong(-1) != payment.getAmount().longValueExact()) {
            flag(payment, "Thông tin link payOS không khớp đơn hàng. Cần kiểm tra.");
            return;
        }
        if ("PAID".equals(data.path("status").asText())) {
            if (data.path("amountPaid").asLong(-1) != payment.getAmount().longValueExact()) {
                flag(payment, "payOS báo đã thanh toán nhưng số tiền thực nhận không khớp.");
                return;
            }
            CustomerOrder order = orders.findDetailedByIdForUpdate(payment.getOrder().getId()).orElseThrow();
            entityManager.refresh(order);
            if (order.getStatus() != OrderStatus.NEW || order.getPaymentStatus() != PaymentStatus.UNPAID) {
                flag(payment, "payOS báo đã thanh toán cho đơn đã đổi trạng thái. Cần kiểm tra.");
                return;
            }
            payment.setStatus(PaymentTransactionStatus.SUCCESS);
            payment.setPaidAt(LocalDateTime.now(VIETNAM_ZONE));
            order.setPaymentStatus(PaymentStatus.PAID);
            notifications.send(order.getUser(), "Đã xác nhận thanh toán",
                "payOS đã xác nhận thanh toán đơn " + order.getCode() + ".", "/orders/" + order.getId());
            notifications.send(order.getShop().getOwner(), "Có đơn hàng mới",
                "Đơn " + order.getCode() + " đã thanh toán và đang chờ shop xử lý.", "/vendor/orders");
        } else if (("CANCELLED".equals(data.path("status").asText()) || "EXPIRED".equals(data.path("status").asText()))
            && data.path("amountPaid").asLong(0) > 0) {
            flag(payment, "Link payOS đã kết thúc nhưng có tiền vào. Cần kiểm tra thủ công.");
        } else if ("CANCELLED".equals(data.path("status").asText()) || "EXPIRED".equals(data.path("status").asText())) {
            if (payment.getStatus() == PaymentTransactionStatus.PENDING) {
                payment.setStatus(PaymentTransactionStatus.EXPIRED);
                orderService.expireUnpaidOnlineOrder(payment.getOrder().getId());
            }
        }
    }

    @Transactional
    public void reportReconciliationFailure(Long paymentId) {
        PaymentTransaction payment = transactions.findByIdForUpdate(paymentId).orElse(null);
        if (payment == null || !payOs.isPayOs(payment)
            || payment.getStatus() != PaymentTransactionStatus.PENDING) return;
        flag(payment, "Không thể kiểm tra payOS khi link đã hết hạn. Cần đối chiếu trước khi đóng đơn.");
    }

    private void flag(PaymentTransaction payment, String message) {
        if (payment.getStatus() == PaymentTransactionStatus.AWAITING_REVIEW) return;
        payment.setStatus(PaymentTransactionStatus.AWAITING_REVIEW);
        users.findByRoleAndEnabledTrueAndLockedFalseOrderByFullNameAsc(Role.ADMIN)
            .forEach(admin -> notifications.send(admin, "Cần kiểm tra thanh toán payOS",
                "Đơn " + payment.getOrder().getCode() + ": " + message,
                "/management/payments?q=" + payment.getOrder().getCode()));
    }
}
