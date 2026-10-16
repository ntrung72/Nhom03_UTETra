package vn.iotstar.service;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.PaymentMethod;
@Service @RequiredArgsConstructor
public class CheckoutPaymentService {
    private final OrderService orders;
    private final VnPayService vnPay;
    private final vn.iotstar.repository.OrderRepository repository;
    @Transactional(noRollbackFor = OrderService.PriceChangedException.class)
    public CheckoutResult checkout(User user, CheckoutForm form, String clientIp) {
        if (form != null && form.getPaymentMethod() == PaymentMethod.VNPAY) vnPay.requireConfigured();
        var created = orders.checkout(user, form);
        String url = form.getPaymentMethod() == PaymentMethod.VNPAY ? vnPay.createPaymentUrl(user, created, clientIp) : null;
        return new CheckoutResult(List.copyOf(created), url);
    }
    @Transactional
    public void cancel(User user, Long orderId) {
        var order = repository.findOwnedPaymentSummary(orderId, user.getId()).orElseThrow(() ->
            new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        if (order.getPaymentMethod() == PaymentMethod.VNPAY) vnPay.cancelCheckout(user, orderId);
        else orders.cancelByUser(user, orderId);
    }
    public record CheckoutResult(List<CustomerOrder> orders, String paymentUrl) {}
}
