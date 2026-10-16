package vn.iotstar.service;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "app.vnpay.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class VnPayExpirationScheduler {
    private final VnPayExpirationService expiration;
    @Scheduled(initialDelayString = "${app.vnpay.expiration-check-ms:60000}", fixedDelayString = "${app.vnpay.expiration-check-ms:60000}")
    public void expirePendingPayments() { expiration.expirePendingPayments(); }
}
