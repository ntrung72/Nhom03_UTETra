package vn.iotstar.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.AuditLog;
import vn.iotstar.entity.DomainEnums.AuditAction;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    @Query("""
        select log from AuditLog log
        where (:action is null or log.action = :action)
          and (:q is null or lower(log.actorUsername) like lower(concat('%', :q, '%'))
            or lower(log.targetLabel) like lower(concat('%', :q, '%')))
        """)
    Page<AuditLog> search(@Param("q") String q, @Param("action") AuditAction action, Pageable pageable);
}
