package vn.iotstar.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.DomainEnums.LoyaltyPointType;
import vn.iotstar.entity.LoyaltyPointTransaction;

public interface LoyaltyPointTransactionRepository extends JpaRepository<LoyaltyPointTransaction, Long> {
    Optional<LoyaltyPointTransaction> findByOrderIdAndType(Long orderId, LoyaltyPointType type);

    boolean existsByOrderIdAndType(Long orderId, LoyaltyPointType type);

    @Query("select coalesce(sum(t.pointsChange), 0) from LoyaltyPointTransaction t where t.user.id = :userId")
    Long balanceByUserId(@Param("userId") Long userId);

    @EntityGraph(attributePaths = "order")
    Page<LoyaltyPointTransaction> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);
}
