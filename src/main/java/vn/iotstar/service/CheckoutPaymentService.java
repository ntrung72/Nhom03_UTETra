package vn.iotstar.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.PaymentMethod;
import vn.iotstar.entity.User;

@Service
@RequiredArgsConstructor
public class CheckoutPaymentService {
    private final OrderService orderService;
    private final BankTransferService bankTransferService;
    private final PayOsClient payOsClient;

    @Transactional(noRollbackFor = OrderService.PriceChangedException.class)
    public CheckoutResult checkout(User user, CheckoutForm form) {
        if (form.getPaymentMethod() != PaymentMethod.COD && form.getPaymentMethod() != PaymentMethod.BANK_TRANSFER) {
            throw new IllegalArgumentException("Vui lòng chọn COD hoặc chuyển khoản QR.");
        }
        if (form.getPaymentMethod() == PaymentMethod.BANK_TRANSFER) {
            payOsClient.requireConfigured();
        }
        List<CustomerOrder> orders = orderService.checkout(user, form);
        String paymentUrl = switch (form.getPaymentMethod()) {
            case BANK_TRANSFER -> "/bank-transfer/checkout/" + createBankTransfer(orders);
            case COD -> null;
            default -> throw new IllegalArgumentException("Phương thức thanh toán không còn áp dụng cho đơn mới.");
        };
        return new CheckoutResult(List.copyOf(orders), paymentUrl);
    }

    private String createBankTransfer(List<CustomerOrder> orders) {
        try {
            return bankTransferService.createPayment(orders);
        } catch (IllegalStateException ex) {
            throw new IllegalArgumentException("Không thể tạo link thanh toán payOS lúc này. Vui lòng thử lại sau.", ex);
        }
    }

    public record CheckoutResult(List<CustomerOrder> orders, String paymentUrl) {}
}
