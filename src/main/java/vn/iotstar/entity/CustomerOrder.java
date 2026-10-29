package vn.iotstar.entity;

import vn.iotstar.util.VietnamTime;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentMethod;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.ShipperAssignmentStatus;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "customer_orders", indexes = {
    @Index(name = "idx_order_user_status", columnList = "user_id,status"),
    @Index(name = "idx_order_shop_status", columnList = "shop_id,status"),
    @Index(name = "idx_order_shipper_status", columnList = "shipper_id,status"),
    @Index(name = "idx_order_checkout_reference", columnList = "checkout_reference")
})
public class CustomerOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_code", nullable = false, unique = true, length = 32)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "carrier_id")
    private Carrier carrier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipper_id")
    private User shipper;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipper_assignment_status", length = 20)
    private ShipperAssignmentStatus shipperAssignmentStatus;

    @Column(name = "shipper_rejection_reason", length = 500)
    private String shipperRejectionReason;

    @Column(name = "shipper_assigned_at")
    private LocalDateTime shipperAssignedAt;

    @Column(name = "shipper_reminder_sent_at")
    private LocalDateTime shipperReminderSentAt;

    @Column(name = "returned_to_shop_at")
    private LocalDateTime returnedToShopAt;

    @Column(name = "cod_collected_at")
    private LocalDateTime codCollectedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voucher_id")
    private Voucher voucher;

    @Column(name = "checkout_reference", length = 50)
    private String checkoutReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status = OrderStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod = PaymentMethod.COD;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Column(name = "receiver_name", nullable = false, length = 120)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false, length = 20)
    private String receiverPhone;

    @Column(name = "shipping_address", nullable = false, length = 500)
    private String shippingAddress;

    @Column(length = 500)
    private String note;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "product_discount", precision = 14, scale = 2)
    private BigDecimal productDiscount;

    @Column(name = "voucher_discount", precision = 14, scale = 2)
    private BigDecimal voucherDiscount;

    @Column(name = "shipping_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal shippingFee = BigDecimal.ZERO;

    @Column(name = "shipping_discount", precision = 14, scale = 2)
    private BigDecimal shippingDiscount;

    @Column(name = "final_shipping_fee", precision = 14, scale = 2)
    private BigDecimal finalShippingFee;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "commission_rate", precision = 5, scale = 2)
    private BigDecimal commissionRate;

    @Column(name = "commission_base", precision = 14, scale = 2)
    private BigDecimal commissionBase;

    @Column(name = "commission_amount", precision = 14, scale = 2)
    private BigDecimal commissionAmount;

    @Column(name = "seller_revenue", precision = 14, scale = 2)
    private BigDecimal sellerRevenue;

    @Column(name = "platform_revenue", precision = 14, scale = 2)
    private BigDecimal platformRevenue;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "delivery_proof_url", length = 500)
    private String deliveryProofUrl;

    @Column(name = "picked_up_at")
    private LocalDateTime pickedUpAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = VietnamTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = VietnamTime.now();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    @PreUpdate
    void touch() { updatedAt = VietnamTime.now(); }

    @Transient
    public boolean isAwaitingBankTransferConfirmation() {
        return paymentMethod == PaymentMethod.BANK_TRANSFER
            && paymentStatus == PaymentStatus.UNPAID && status == OrderStatus.NEW;
    }

    @Transient
    public String getDisplayStatusLabel() {
        return isAwaitingBankTransferConfirmation() ? "Chờ xác nhận thanh toán" : status.getLabel();
    }

    public void snapshotCommission(BigDecimal rate) {
        BigDecimal merchandise = subtotal == null ? BigDecimal.ZERO : subtotal.max(BigDecimal.ZERO);
        BigDecimal merchandiseDiscount;
        if (productDiscount == null && voucherDiscount == null) {
            merchandiseDiscount = discountAmount == null ? BigDecimal.ZERO : discountAmount.max(BigDecimal.ZERO);
        } else {
            merchandiseDiscount = zero(productDiscount).add(zero(voucherDiscount));
        }
        BigDecimal base = merchandise.subtract(merchandiseDiscount).max(BigDecimal.ZERO);
        BigDecimal safeRate = rate == null ? BigDecimal.ZERO : rate.max(BigDecimal.ZERO).min(new BigDecimal("100"));
        BigDecimal fee = base.multiply(safeRate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        commissionRate = safeRate.setScale(2, RoundingMode.HALF_UP);
        commissionBase = base.setScale(2, RoundingMode.HALF_UP);
        commissionAmount = fee;
        platformRevenue = fee;
        sellerRevenue = base.subtract(fee).max(BigDecimal.ZERO);
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }

    public void backfillDiscountBreakdown() {
        if (productDiscount == null) productDiscount = discountAmount == null ? BigDecimal.ZERO : discountAmount;
        if (voucherDiscount == null) voucherDiscount = BigDecimal.ZERO;
        if (shippingDiscount == null) shippingDiscount = BigDecimal.ZERO;
        if (finalShippingFee == null) finalShippingFee = shippingFee == null ? BigDecimal.ZERO : shippingFee;
    }
}
