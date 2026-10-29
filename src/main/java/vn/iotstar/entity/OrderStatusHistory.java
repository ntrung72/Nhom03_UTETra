package vn.iotstar.entity;

import vn.iotstar.util.VietnamTime;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.iotstar.entity.DomainEnums.OrderStatus;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "order_status_history", indexes = {
    @Index(name = "idx_order_status_history_order_time", columnList = "order_id,changed_at")
})
public class OrderStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", length = 30)
    private OrderStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 30)
    private OrderStatus newStatus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "changed_by", nullable = false)
    private User changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt = VietnamTime.now();

    @Column(length = 500)
    private String note;

    @Column(name = "delivery_proof_url", length = 500)
    private String deliveryProofUrl;

    public String getDisplayLabel() {
        return switch (newStatus) {
            case NEW -> "Đặt hàng";
            case CONFIRMED -> "Shop đã xác nhận";
            case PREPARING -> "Shop đang chuẩn bị hàng";
            case READY_FOR_PICKUP -> "Đơn sẵn sàng để shipper lấy";
            case PICKED_UP -> "Shipper đã lấy hàng";
            case SHIPPING -> "Đang giao hàng";
            case DELIVERED -> "Giao thành công";
            case DELIVERY_FAILED -> "Giao hàng thất bại";
            case CANCELLED -> "Đã hủy đơn";
            case RETURN_REQUESTED -> "Đã yêu cầu trả hàng";
            case RETURNED_REFUNDED -> "Đã hoàn tiền thủ công";
        };
    }
}
