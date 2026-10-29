package vn.iotstar.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.AuditLog;
import vn.iotstar.entity.DomainEnums.AuditAction;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.User;
import vn.iotstar.repository.AuditLogRepository;

@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final AuditLogRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, User actor, String targetType, Long targetId,
                       String targetLabel, String oldValue, String newValue, String note) {
        if (actor == null || action == null || targetId == null) {
            throw new IllegalArgumentException("Dữ liệu nhật ký thao tác không hợp lệ.");
        }
        AuditLog log = new AuditLog();
        log.setActor(actor);
        log.setActorUsername(actor.getUsername());
        log.setActorRole(actor.getRole());
        log.setAction(action);
        log.setTargetType(limit(targetType, 30));
        log.setTargetId(targetId);
        log.setTargetLabel(limit(targetLabel, 180));
        log.setOldValue(limit(oldValue, 500));
        log.setNewValue(limit(newValue, 500));
        log.setNote(limit(note, 500));
        repository.save(log);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> search(User actor, String q, AuditAction action, int page) {
        if (actor == null || actor.getRole() != Role.ADMIN) {
            throw new IllegalArgumentException("Chỉ Admin được xem nhật ký thao tác.");
        }
        String query = q == null || q.isBlank() ? null : q.trim();
        return repository.search(query, action,
            PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    private String limit(String value, int maxLength) {
        return value == null ? null : value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
