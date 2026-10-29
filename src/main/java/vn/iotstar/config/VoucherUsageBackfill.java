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
import vn.iotstar.entity.Voucher;
import vn.iotstar.entity.VoucherUsage;
import vn.iotstar.entity.DomainEnums.VoucherUsageStatus;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.VoucherRepository;
import vn.iotstar.repository.VoucherUsageRepository;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class VoucherUsageBackfill implements ApplicationRunner {
    private final VoucherRepository voucherRepository;
    private final OrderRepository orderRepository;
    private final VoucherUsageRepository voucherUsageRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Voucher> legacyVouchers = voucherRepository.findByMaxUsesPerUserIsNull();
        legacyVouchers.forEach(voucher -> voucher.setMaxUsesPerUser(1));
        voucherRepository.saveAll(legacyVouchers);

        List<CustomerOrder> legacyOrders = orderRepository.findByProductDiscountIsNull();
        legacyOrders.forEach(CustomerOrder::backfillDiscountBreakdown);
        orderRepository.saveAll(legacyOrders);

        int usageCount = 0;
        for (CustomerOrder order : orderRepository.findByVoucherIsNotNull()) {
            if (order.getCheckoutReference() != null
                && voucherUsageRepository.existsByCheckoutReference(order.getCheckoutReference())) continue;
            if (voucherUsageRepository.existsByOrderId(order.getId())) continue;
            VoucherUsage usage = new VoucherUsage();
            usage.setUser(order.getUser());
            usage.setVoucher(order.getVoucher());
            usage.setOrder(order);
            usage.setCheckoutReference("LEGACY-ORDER-" + order.getId());
            usage.setUsedAt(order.getCreatedAt());
            usage.setStatus(VoucherUsageStatus.USED);
            order.setCheckoutReference(usage.getCheckoutReference());
            voucherUsageRepository.save(usage);
            usageCount++;
        }
        for (VoucherUsage usage : voucherUsageRepository.findAll()) {
            if (usage.getStatus() == null) usage.setStatus(VoucherUsageStatus.USED);
            if (usage.getOrder().getCheckoutReference() == null) {
                usage.getOrder().setCheckoutReference(usage.getCheckoutReference());
            }
        }
        if (!legacyVouchers.isEmpty() || !legacyOrders.isEmpty() || usageCount > 0) {
            log.info("Đã nâng cấp voucher: {} mã, {} đơn, {} lượt dùng cũ.",
                legacyVouchers.size(), legacyOrders.size(), usageCount);
        }
    }
}
