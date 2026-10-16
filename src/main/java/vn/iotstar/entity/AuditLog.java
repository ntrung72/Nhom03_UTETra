package vn.iotstar.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.iotstar.entity.DomainEnums.AuditAction;
import vn.iotstar.entity.DomainEnums.Role;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_created", columnList = "created_at,id"),
    @Index(name = "idx_audit_action_created", columnList = "action,created_at"),
    @Index(name = "idx_audit_target", columnList = "target_type,target_id")
})
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Column(name = "actor_username", nullable = false, length = 60)
    private String actorUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, length = 20)
    private Role actorRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditAction action;

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "target_label", nullable = false, length = 180)
    private String targetLabel;

    @Column(name = "old_value", length = 500)
    private String oldValue;

    @Column(name = "new_value", length = 500)
    private String newValue;

    @Column(length = 500)
    private String note;

    /** Human-readable action summary; never store tokens or payment signatures. */
    @Column(length = 500)
    private String description;

    /** Safe business-state details (not raw HTTP/payment payloads). */
    @Column(length = 1000)
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
