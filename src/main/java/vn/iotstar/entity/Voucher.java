package vn.iotstar.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.iotstar.entity.DomainEnums.VoucherType;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "vouchers", uniqueConstraints = @UniqueConstraint(name = "uk_voucher_code", columnNames = "code"))
public class Voucher {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 180)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VoucherType type;

    @Column(name = "discount_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "minimum_order", nullable = false, precision = 14, scale = 2)
    private BigDecimal minimumOrder = BigDecimal.ZERO;

    @Column(name = "max_discount", precision = 14, scale = 2)
    private BigDecimal maxDiscount;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "max_uses_per_user")
    private Integer maxUsesPerUser = 1;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Column(nullable = false)
    private boolean active = true;

    public boolean canApply(BigDecimal subtotal, LocalDateTime now) {
        return active && quantity > 0 && !now.isBefore(startsAt) && !now.isAfter(endsAt)
            && subtotal.compareTo(minimumOrder) >= 0;
    }

    public BigDecimal calculateDiscount(BigDecimal subtotal, BigDecimal shippingFee) {
        BigDecimal result = switch (type) {
            case PERCENT -> subtotal.multiply(discountValue).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            case FIXED -> discountValue.min(subtotal);
            case SHIPPING -> discountValue.min(shippingFee);
        };
        return maxDiscount == null ? result : result.min(maxDiscount);
    }

    public int getEffectiveMaxUsesPerUser() {
        return maxUsesPerUser == null || maxUsesPerUser < 1 ? 1 : maxUsesPerUser;
    }
}
