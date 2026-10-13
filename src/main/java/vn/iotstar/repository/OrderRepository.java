package vn.iotstar.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {
    @EntityGraph(attributePaths = {"shop", "carrier"})
    @Query("select o from CustomerOrder o where o.user.id = :userId and (:status is null or o.status = :status)")
    Page<CustomerOrder> findForUser(@Param("userId") Long userId, @Param("status") OrderStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"shop", "carrier", "voucher", "items", "items.product"})
    Optional<CustomerOrder> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id = :id and o.user.id = :userId")
    Optional<CustomerOrder> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);

    long countByCheckoutReferenceAndIdNotAndStatusNot(String checkoutReference, Long id, OrderStatus status);
}
