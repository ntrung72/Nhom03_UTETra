package vn.iotstar.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.ShopFollow;

public interface ShopFollowRepository extends JpaRepository<ShopFollow, Long> {
    Optional<ShopFollow> findByUserIdAndShopId(Long userId, Long shopId);
    boolean existsByUserIdAndShopId(Long userId, Long shopId);

    @EntityGraph(attributePaths = "shop")
    Page<ShopFollow> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);
}
