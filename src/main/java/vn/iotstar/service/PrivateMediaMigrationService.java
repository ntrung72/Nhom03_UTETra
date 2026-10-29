package vn.iotstar.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ReturnRequestMediaRepository;

@Service
@Profile("!test")
@RequiredArgsConstructor
public class PrivateMediaMigrationService {
    private static final Logger log = LoggerFactory.getLogger(PrivateMediaMigrationService.class);
    private final OrderRepository orderRepository;
    private final ReturnRequestMediaRepository mediaRepository;
    private final MediaStorageService mediaStorageService;
    private final PlatformTransactionManager transactionManager;

    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyEvidence() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        for (Long orderId : orderRepository.findLegacyDeliveryProofOrderIds()) {
            try {
                transactions.executeWithoutResult(status -> {
                    var order = orderRepository.findById(orderId).orElseThrow();
                    order.setDeliveryProofUrl(mediaStorageService.migrateLegacyPrivateMedia(
                        order.getDeliveryProofUrl(), "delivery-proofs"));
                });
            } catch (RuntimeException ex) {
                log.error("Không thể chuyển bằng chứng giao hàng của đơn {} sang lưu trữ riêng tư.", orderId, ex);
            }
        }
        for (Long mediaId : mediaRepository.findLegacyEvidenceIds()) {
            try {
                transactions.executeWithoutResult(status -> {
                    var media = mediaRepository.findById(mediaId).orElseThrow();
                    media.setUrl(mediaStorageService.migrateLegacyPrivateMedia(media.getUrl(), "returns"));
                });
            } catch (RuntimeException ex) {
                log.error("Không thể chuyển bằng chứng trả hàng {} sang lưu trữ riêng tư.", mediaId, ex);
            }
        }
        mediaStorageService.quarantineUnreferencedLegacyFiles();
    }
}
