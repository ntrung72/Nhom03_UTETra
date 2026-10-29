package vn.iotstar.entity;

import vn.iotstar.util.VietnamTime;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.iotstar.entity.DomainEnums.VoucherUsageStatus;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "voucher_usages", uniqueConstraints = {
    @UniqueConstraint(name = "uk_voucher_usage_checkout", columnNames = "checkout_reference"),
    @UniqueConstraint(name = "uk_voucher_usage_order", columnNames = "order_id")
}, indexes = {
    @Index(name = "idx_voucher_usage_user_voucher", columnList = "user_id,voucher_id"),
    @Index(name = "idx_voucher_usage_used_at", columnList = "used_at")
})
public class VoucherUsage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "voucher_id", nullable = false)
    private Voucher voucher;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @Column(name = "checkout_reference", nullable = false, length = 50)
    private String checkoutReference;

    @Column(name = "used_at", nullable = false)
    private LocalDateTime usedAt = VietnamTime.now();

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) default 'USED'")
    private VoucherUsageStatus status = VoucherUsageStatus.USED;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "release_reason", length = 255)
    private String releaseReason;
}
