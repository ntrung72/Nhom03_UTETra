package vn.iotstar.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import vn.iotstar.entity.DomainEnums.AuditAction;
import vn.iotstar.entity.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    @Query("""
        select log from AuditLog log
        where (:action is null or log.action = :action)
          and (:q is null or lower(log.actorUsername) like lower(concat('%', :q, '%'))
            or lower(log.targetLabel) like lower(concat('%', :q, '%')))
        """)
    Page<AuditLog> search(@Param("q") String q, @Param("action") AuditAction action, Pageable pageable);
    /** Managers only see events concerning shops they are assigned to. */
    @Query("""
        select log from AuditLog log
        where (:action is null or log.action = :action)
          and (:q is null or lower(log.actorUsername) like lower(concat('%', :q, '%'))
             or lower(log.targetLabel) like lower(concat('%', :q, '%')))
          and (
             (log.targetType = 'SHOP' and log.targetId in :shopIds)
             or (log.targetType = 'ORDER' and exists (
                 select o.id from CustomerOrder o
                 where o.id = log.targetId and o.shop.id in :shopIds
             ))
             or (log.targetType = 'USER'
                 and log.action in :scopedUserActions and exists (
                     select s.id from Shop s
                     where s.owner.id = log.targetId and s.id in :shopIds
                 ))
          )
        """)
    Page<AuditLog> searchAssigned(@Param("q") String q,
                                  @Param("action") AuditAction action,
                                  @Param("shopIds") List<Long> shopIds,
                                  @Param("scopedUserActions") List<AuditAction> scopedUserActions,
                                  Pageable pageable);
}
