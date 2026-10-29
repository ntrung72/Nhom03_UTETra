package vn.iotstar.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.iotstar.entity.DomainEnums.PaymentTransactionStatus;
import vn.iotstar.repository.PaymentTransactionRepository;

@Component
@RequiredArgsConstructor
public class PayOsReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(PayOsReconciliationScheduler.class);
    private final PaymentTransactionRepository transactions;
    private final PayOsPaymentService payments;

    @Scheduled(initialDelayString = "${app.payos.recheck-ms:120000}",
               fixedDelayString = "${app.payos.recheck-ms:120000}")
    public void recheck() {
        for (var payment : transactions.findRecheckablePayOs(
            java.util.List.of(PaymentTransactionStatus.PENDING, PaymentTransactionStatus.AWAITING_REVIEW))) {
            try {
                payments.reconcile(payment.getId());
            } catch (RuntimeException ex) {
                log.warn("Không thể kiểm tra trạng thái payOS của giao dịch #{}", payment.getId(), ex);
                if (payment.getExpiresAt() != null
                    && !payment.getExpiresAt().isAfter(LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")))) {
                    try {
                        payments.reportReconciliationFailure(payment.getId());
                    } catch (RuntimeException reportError) {
                        log.warn("Không thể báo Admin về giao dịch payOS #{}", payment.getId(), reportError);
                    }
                }
            }
        }
    }
}
