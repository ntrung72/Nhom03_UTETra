package vn.iotstar.config;

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
        List<CustomerOrder> legacyOrders = orderRepository.findByCommissionBaseIsNull();
        for (CustomerOrder order : legacyOrders) {
            order.snapshotCommission(order.getShop().getCommissionRate());
        }
        if (!legacyOrders.isEmpty()) {
            orderRepository.saveAll(legacyOrders);
            log.info("Đã chuẩn hóa commission không gồm phí vận chuyển cho {} đơn hàng cũ.", legacyOrders.size());
        }
    }
}
