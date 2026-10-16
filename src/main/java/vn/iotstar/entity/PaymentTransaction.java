package vn.iotstar.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.iotstar.entity.DomainEnums.PaymentTransactionStatus;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_transactions", uniqueConstraints = @UniqueConstraint(name = "uk_payment_attempt_order", columnNames = {"transaction_code", "order_id"}), indexes = {
    @Index(name = "idx_payment_transaction_code", columnList = "transaction_code"),
    @Index(name = "idx_payment_transaction_order", columnList = "order_id"),
    @Index(name = "idx_payment_transaction_expiry", columnList = "status,expires_at")
})
public class PaymentTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @Column(name = "transaction_code", nullable = false, length = 100)
    private String transactionCode;

    @Column(name = "checkout_reference", nullable = false, length = 50)
    private String checkoutReference;

    @Column(name = "reconciliation_required", nullable = false)
    private boolean reconciliationRequired;

    @Column(name = "response_code", length = 2)
    private String responseCode;

    @Column(name = "provider_status", length = 2)
    private String providerStatus;

    @Column(name = "provider_transaction_no", length = 30)
    private String providerTransactionNo;

    @Column(name = "client_ip", nullable = false, length = 45)
    private String clientIp;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentTransactionStatus status = PaymentTransactionStatus.PENDING;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
