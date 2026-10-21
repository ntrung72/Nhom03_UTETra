package vn.iotstar.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.iotstar.entity.DomainEnums.LoyaltyPointType;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "loyalty_point_transactions", uniqueConstraints = {
    @UniqueConstraint(name = "uk_loyalty_order_type", columnNames = {"order_id", "type"})
}, indexes = {
    @Index(name = "idx_loyalty_user_time", columnList = "user_id,created_at")
})
public class LoyaltyPointTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LoyaltyPointType type;

    @Column(name = "points_change", nullable = false)
    private long pointsChange;

    @Column(name = "eligible_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal eligibleAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
