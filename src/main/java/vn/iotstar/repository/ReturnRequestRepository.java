package vn.iotstar.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import vn.iotstar.entity.ReturnRequest;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {
    boolean existsByOrderId(Long orderId);

    @EntityGraph(attributePaths = {"media", "order", "user", "shop", "resolvedBy"})
    Optional<ReturnRequest> findDetailedById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from ReturnRequest request where request.id = :id")
    Optional<ReturnRequest> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from ReturnRequest request where request.order.id = :orderId")
    Optional<ReturnRequest> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    @EntityGraph(attributePaths = {"media", "resolvedBy"})
    Optional<ReturnRequest> findByOrderId(Long orderId);

    List<ReturnRequest> findByOrderIdIn(Collection<Long> orderIds);
}
