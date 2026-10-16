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
import vn.iotstar.repository.ManagerShopRepository;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final AuditLogRepository repository;
    private final ManagerShopRepository managerShopRepository;

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
        log.setDescription(limit(action.getLabel() + ": " + (targetLabel == null ? targetType : targetLabel), 500));
        String beforeAfter = (oldValue == null ? "" : "before=" + oldValue)
            + (newValue == null ? "" : "; after=" + newValue);
        log.setMetadata(beforeAfter.isBlank() ? null : limit(beforeAfter, 1000));
        repository.save(log);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> search(User actor, String q, AuditAction action, int page) {
        if (actor == null || (actor.getRole() != Role.ADMIN && actor.getRole() != Role.MANAGER)) {
            throw new AccessDeniedException("Chỉ Admin/Manager được xem nhật ký thao tác.");
        }
        String query = q == null || q.isBlank() ? null : q.trim();
        var pageable = PageRequest.of(Math.max(0, page), 20,
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        if (actor.getRole() == Role.ADMIN) {
            return repository.search(query, action, pageable);
        }
        List<Long> shopIds = managerShopRepository.findShopIdsByManagerId(actor.getId());
        if (shopIds.isEmpty()) return Page.empty(pageable);
        return repository.searchAssigned(query, action, shopIds,
            List.of(AuditAction.USER_LOCKED, AuditAction.USER_UNLOCKED), pageable);
    }

    private String limit(String value, int maxLength) {
        return value == null ? null : value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
