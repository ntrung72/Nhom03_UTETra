package vn.iotstar.entity;

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

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "return_requests",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_return_request_order",
            columnNames = "order_id"
        )
    },
    indexes = {
        @Index(
            name = "idx_return_shop_status",
            columnList = "shop_id,status"
        ),
        @Index(
            name = "idx_return_user",
            columnList = "user_id"
        )
    }
)
public class ReturnRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private long version;

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

    @Column(
        name = "refund_amount",
        nullable = false,
        precision = 14,
        scale = 2
    )
    private BigDecimal refundAmount;

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

    @Column(name = "refund_note", length = 1000)
    private String refundNote;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @OneToMany(
        mappedBy = "returnRequest",
        cascade = CascadeType.ALL,
        orphanRemoval = true
    )
    @OrderBy("id ASC")
    private List<ReturnRequestMedia> media = new ArrayList<>();

    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}