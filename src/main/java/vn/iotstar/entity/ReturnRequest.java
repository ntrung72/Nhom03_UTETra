package vn.iotstar.entity;

import vn.iotstar.util.VietnamTime;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.iotstar.entity.DomainEnums.ReturnReason;
import vn.iotstar.entity.DomainEnums.ReturnStatus;
import vn.iotstar.entity.DomainEnums.RefundVerification;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "return_requests", uniqueConstraints =
    @UniqueConstraint(name = "uk_return_request_order", columnNames = "order_id"), indexes = {
    @Index(name = "idx_return_shop_status", columnList = "shop_id,status"),
    @Index(name = "idx_return_user", columnList = "user_id")
})
public class ReturnRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReturnReason reason;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(name = "refund_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refund_bank_name", length = 120)
    private String refundBankName;

    @Column(name = "refund_account_name", length = 120)
    private String refundAccountName;

    @Column(name = "refund_account_number", length = 24)
    private String refundAccountNumber;

    @Column(name = "return_required")
    private Boolean returnRequired;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReturnStatus status = ReturnStatus.REQUESTED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "refund_reference", length = 120)
    private String refundReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_verification", length = 24)
    private RefundVerification refundVerification;

    @Column(name = "refund_note", length = 1000)
    private String refundNote;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt = VietnamTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = VietnamTime.now();

    @OneToMany(mappedBy = "returnRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ReturnRequestMedia> media = new ArrayList<>();

    @PreUpdate
    void touch() { updatedAt = VietnamTime.now(); }

    public RefundVerification getRefundVerification() {
        if (refundVerification != null) return refundVerification;
        return status == ReturnStatus.REFUNDED
            ? RefundVerification.LEGACY_UNKNOWN : RefundVerification.PENDING;
    }
}
