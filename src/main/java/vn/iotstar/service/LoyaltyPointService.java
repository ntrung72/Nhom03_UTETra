package vn.iotstar.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.LoyaltyPointType;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.LoyaltyPointTransaction;
import vn.iotstar.entity.User;
import vn.iotstar.repository.LoyaltyPointTransactionRepository;

@Service
@RequiredArgsConstructor
public class LoyaltyPointService {
    private final LoyaltyPointTransactionRepository transactionRepository;

    @Value("${app.loyalty.vnd-per-point:10000}")
    private long vndPerPoint;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    @jakarta.annotation.PostConstruct
    void validatePolicy() {
        if (vndPerPoint <= 0) throw new IllegalStateException("Số tiền quy đổi điểm phải lớn hơn 0.");
    }

    private CustomerOrder lock(CustomerOrder order) {
        if (order == null || order.getId() == null) throw new IllegalArgumentException("Đơn hàng không hợp lệ.");
        return entityManager.find(CustomerOrder.class, order.getId(), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    @Transactional
    public void awardForDeliveredOrder(CustomerOrder input) {
        CustomerOrder order = lock(input);
        if (order.getStatus() != OrderStatus.DELIVERED || order.getPaymentStatus() != PaymentStatus.PAID
            || transactionRepository.existsByOrderIdAndType(order.getId(), LoyaltyPointType.EARN)) return;

        BigDecimal eligibleAmount = eligibleAmount(order);
        long points = eligibleAmount.divideToIntegralValue(BigDecimal.valueOf(vndPerPoint)).longValueExact();
        if (points <= 0) return;

        LoyaltyPointTransaction transaction = new LoyaltyPointTransaction();
        transaction.setUser(order.getUser());
        transaction.setOrder(order);
        transaction.setType(LoyaltyPointType.EARN);
        transaction.setPointsChange(points);
        transaction.setEligibleAmount(eligibleAmount);
        transactionRepository.save(transaction);
    }

    @Transactional
    public void reverseForRefundedOrder(CustomerOrder input) {
        CustomerOrder order = lock(input);
        if (order.getStatus() != OrderStatus.RETURNED_REFUNDED || order.getPaymentStatus() != PaymentStatus.REFUNDED
            || transactionRepository.existsByOrderIdAndType(order.getId(), LoyaltyPointType.REFUND_REVERSAL)) return;

        transactionRepository.findByOrderIdAndType(order.getId(), LoyaltyPointType.EARN).ifPresent(earned -> {
            LoyaltyPointTransaction reversal = new LoyaltyPointTransaction();
            reversal.setUser(order.getUser());
            reversal.setOrder(order);
            reversal.setType(LoyaltyPointType.REFUND_REVERSAL);
            reversal.setPointsChange(-earned.getPointsChange());
            reversal.setEligibleAmount(earned.getEligibleAmount());
            transactionRepository.save(reversal);
        });
    }

    @Transactional(readOnly = true)
    public long balance(User user) {
        return transactionRepository.balanceByUserId(user.getId());
    }

    @Transactional(readOnly = true)
    public Page<LoyaltyPointTransaction> history(User user, int page) {
        return transactionRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId(),
            PageRequest.of(Math.max(0, page), 20));
    }

    public long getVndPerPoint() {
        return vndPerPoint;
    }

    private BigDecimal eligibleAmount(CustomerOrder order) {
        if (order.getCommissionBase() != null) return order.getCommissionBase().max(BigDecimal.ZERO);
        BigDecimal subtotal = order.getSubtotal() == null ? BigDecimal.ZERO : order.getSubtotal();
        BigDecimal discount = order.getProductDiscount() == null && order.getVoucherDiscount() == null
            ? (order.getDiscountAmount() == null ? BigDecimal.ZERO : order.getDiscountAmount())
            : (order.getProductDiscount() == null ? BigDecimal.ZERO : order.getProductDiscount())
                .add(order.getVoucherDiscount() == null ? BigDecimal.ZERO : order.getVoucherDiscount());
        return subtotal.subtract(discount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
