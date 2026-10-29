package vn.iotstar.entity;

import vn.iotstar.util.VietnamTime;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.iotstar.entity.DomainEnums.ReturnStatus;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "return_status_history", indexes = {
    @Index(name = "idx_return_history_request_time", columnList = "return_request_id,changed_at")
})
public class ReturnStatusHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_request_id", nullable = false)
    private ReturnRequest returnRequest;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", length = 20)
    private ReturnStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private ReturnStatus newStatus;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "changed_by", nullable = false)
    private User changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt = VietnamTime.now();

    @Column(length = 500)
    private String note;
}
