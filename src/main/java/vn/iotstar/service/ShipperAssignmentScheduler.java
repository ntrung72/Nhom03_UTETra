package vn.iotstar.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ShipperAssignmentScheduler {
    private final OrderService orderService;

    @Scheduled(initialDelayString = "${app.shipper.assignment-check-ms:60000}",
               fixedDelayString = "${app.shipper.assignment-check-ms:60000}")
    public void checkPendingAssignments() {
        try {
            orderService.checkPendingShipperAssignments();
        } catch (RuntimeException ex) {
            log.error("Không thể kiểm tra thời hạn chấp nhận đơn của shipper.", ex);
        }
    }
}
