package vn.iotstar.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.InventoryTransaction;

public interface InventoryTransactionRepository extends JpaRepository<InventoryTransaction, Long> {
    @EntityGraph(attributePaths = {"actor", "order"})
    Page<InventoryTransaction> findByShopIdOrderByCreatedAtDescIdDesc(Long shopId, Pageable pageable);

    @EntityGraph(attributePaths = {"actor", "order"})
    Page<InventoryTransaction> findByShopIdAndProductIdOrderByCreatedAtDescIdDesc(
        Long shopId, Long productId, Pageable pageable);

    java.util.List<InventoryTransaction> findByProductIdOrderByCreatedAtAscIdAsc(Long productId);
}
