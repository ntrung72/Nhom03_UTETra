package vn.iotstar.config;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.repository.OrderRepository;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class CommissionSnapshotBackfill implements ApplicationRunner {

    private final OrderRepository orderRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<CustomerOrder> legacyOrders =
            orderRepository.findByCommissionBaseIsNull();

        if (legacyOrders.isEmpty()) {
            return;
        }

        for (CustomerOrder order : legacyOrders) {
            BigDecimal rate = order.getCommissionRate();

            if (rate == null) {
                rate = order.getShop().getCommissionRate();
            }

            order.snapshotCommission(rate);
        }

        orderRepository.saveAll(legacyOrders);

        log.info(
            "Đã bổ sung snapshot hoa hồng và doanh thu cho {} đơn hàng cũ.",
            legacyOrders.size()
        );
    }
}