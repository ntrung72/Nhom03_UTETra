package vn.iotstar.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.ReturnRequest;
import vn.iotstar.entity.DomainEnums.ReturnStatus;

public interface ReturnRequestRepository
        extends JpaRepository<ReturnRequest, Long> {

    boolean existsByOrderId(Long orderId);

    long countByShopIdAndStatusIn(
        Long shopId,
        Collection<ReturnStatus> statuses
    );

    @EntityGraph(attributePaths = {
        "media",
        "order",
        "order.carrier",
        "user",
        "shop",
        "shop.owner",
        "resolvedBy"
    })
    Optional<ReturnRequest> findDetailedById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select r from ReturnRequest r
        where r.id = :id
        """)
    Optional<ReturnRequest> findByIdForUpdate(
        @Param("id") Long id
    );

    @EntityGraph(attributePaths = {
        "media",
        "order",
        "order.carrier",
        "user",
        "shop",
        "shop.owner",
        "resolvedBy"
    })
    Optional<ReturnRequest> findByOrderId(Long orderId);

    @EntityGraph(attributePaths = {
        "media",
        "order",
        "order.carrier",
        "user",
        "shop",
        "shop.owner",
        "resolvedBy"
    })
    Optional<ReturnRequest> findByOrderIdAndUserId(
        Long orderId,
        Long userId
    );

    @EntityGraph(attributePaths = {
        "media",
        "order",
        "order.carrier",
        "user",
        "shop",
        "shop.owner",
        "resolvedBy"
    })
    Optional<ReturnRequest> findByIdAndShopId(
        Long id,
        Long shopId
    );

    @EntityGraph(attributePaths = {
        "order",
        "user",
        "shop",
        "resolvedBy"
    })
    List<ReturnRequest> findByOrderIdIn(
        Collection<Long> orderIds
    );

    @EntityGraph(attributePaths = {
        "order",
        "user",
        "shop",
        "resolvedBy"
    })
    List<ReturnRequest> findByShopIdAndOrderIdIn(
        Long shopId,
        Collection<Long> orderIds
    );

    @EntityGraph(attributePaths = {
        "order",
        "user",
        "shop",
        "resolvedBy"
    })
    @Query("""
        select r from ReturnRequest r
        where r.shop.id = :shopId
          and (:status is null or r.status = :status)
        """)
    Page<ReturnRequest> findForShop(
        @Param("shopId") Long shopId,
        @Param("status") ReturnStatus status,
        Pageable pageable
    );
}