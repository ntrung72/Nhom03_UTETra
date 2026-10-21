package vn.iotstar.service;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import vn.iotstar.repository.PaymentTransactionRepository;
import vn.iotstar.entity.DomainEnums.*;
@Slf4j @Service @RequiredArgsConstructor
public class VnPayExpirationService {
    private final PaymentTransactionRepository transactions;
    private final VnPayService vnPay;
    private final MomoService momo;
    public int expirePendingPayments() {
        var references = transactions.findExpiredCheckoutReferences(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")),
            OrderStatus.NEW, PaymentStatus.UNPAID, PageRequest.of(0, 100));
        int count = 0;
        for (String reference : references) {
            try { count += vnPay.expireCheckout(reference); count += momo.expireCheckout(reference); }
            catch (RuntimeException ex) { log.error("Không thể hết hạn checkout {}; sẽ thử lại", reference, ex); }
        }
        return count;
    }
}
