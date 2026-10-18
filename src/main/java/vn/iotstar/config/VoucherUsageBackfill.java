package vn.iotstar.config;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
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
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.VoucherUsageStatus;
import vn.iotstar.entity.Voucher;
import vn.iotstar.entity.VoucherUsage;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.VoucherRepository;
import vn.iotstar.repository.VoucherUsageRepository;

/**
 * Tương thích dữ liệu Voucher/Order cũ, theo UTETra. Ở project nhóm VoucherUsage
 * lưu Long orderId thay vì quan hệ JPA order; giữ nguyên mô hình của TV1.
 * Chạy lặp an toàn: chỉ bổ sung bản ghi chưa có, không trừ lại số lượng Voucher.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RequiredArgsConstructor
public class VoucherUsageBackfill implements ApplicationRunner {
    private final VoucherRepository voucherRepository;
    private final OrderRepository orderRepository;
    private final VoucherUsageRepository voucherUsageRepository;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Voucher> legacyVouchers = voucherRepository.findByMaxUsesPerUserIsNull();
        legacyVouchers.forEach(v -> v.setMaxUsesPerUser(1));
        if (!legacyVouchers.isEmpty()) voucherRepository.saveAll(legacyVouchers);

        List<CustomerOrder> legacyOrders = entityManager.createQuery(
            "select o from CustomerOrder o where o.productDiscount is null " +
                "or o.voucherDiscount is null or o.shippingDiscount is null " +
                "or o.finalShippingFee is null", CustomerOrder.class).getResultList();
        legacyOrders.forEach(CustomerOrder::backfillDiscountBreakdown);
        if (!legacyOrders.isEmpty()) orderRepository.saveAll(legacyOrders);

        int usageCount = 0;
        List<CustomerOrder> ordersWithVoucher = entityManager.createQuery(
            "select o from CustomerOrder o where o.voucher is not null order by o.id",
            CustomerOrder.class).getResultList();
        for (CustomerOrder order : ordersWithVoucher) {
            String reference = order.getCheckoutReference();
            if (reference == null || reference.isBlank()) {
                reference = "LEGACY-ORDER-" + order.getId();
            }
            // Một lượt dùng cho một checkout, kể cả khi checkout tạo nhiều đơn theo Shop.
            if (voucherUsageRepository.existsByCheckoutReference(reference)
                || voucherUsageRepository.existsByOrderId(order.getId())) continue;
            VoucherUsage usage = new VoucherUsage();
            usage.setUser(order.getUser());
            usage.setVoucher(order.getVoucher());
            usage.setOrderId(order.getId());
            usage.setCheckoutReference(reference);
            usage.setUsedAt(order.getCreatedAt() == null ? LocalDateTime.now() : order.getCreatedAt());
            usage.setStatus(usageStatus(order.getStatus()));
            if (order.getCheckoutReference() == null || order.getCheckoutReference().isBlank()) {
                order.setCheckoutReference(reference);
            }
            voucherUsageRepository.saveAndFlush(usage);
            usageCount++;
        }
        for (VoucherUsage usage : voucherUsageRepository.findAll()) {
            if (usage.getStatus() == null) usage.setStatus(VoucherUsageStatus.USED);
            if (usage.getOrderId() == null && usage.getCheckoutReference() != null) {
                entityManager.createQuery(
                    "select o.id from CustomerOrder o where o.checkoutReference = :reference order by o.id",
                    Long.class).setParameter("reference", usage.getCheckoutReference())
                    .setMaxResults(1).getResultList().stream().findFirst().ifPresent(usage::setOrderId);
            }
        }
        if (!legacyVouchers.isEmpty() || !legacyOrders.isEmpty() || usageCount > 0) {
            log.info("Đã nâng cấp voucher: {} mã, {} đơn, {} lượt dùng cũ.",
                legacyVouchers.size(), legacyOrders.size(), usageCount);
        }
    }

    private VoucherUsageStatus usageStatus(OrderStatus status) {
        if (status == OrderStatus.CANCELLED) return VoucherUsageStatus.CANCELLED;
        if (status == OrderStatus.RETURNED_REFUNDED) return VoucherUsageStatus.REFUNDED;
        return VoucherUsageStatus.USED;
    }
}
