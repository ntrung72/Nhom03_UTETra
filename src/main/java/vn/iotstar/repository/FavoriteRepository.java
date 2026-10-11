package vn.iotstar.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.Favorite;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {
    Optional<Favorite> findByUserIdAndProductId(Long userId, Long productId);
    boolean existsByUserIdAndProductId(Long userId, Long productId);
    Page<Favorite> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"product", "product.shop", "product.category"})
    @org.springframework.data.jpa.repository.Query("""
        select f from Favorite f where f.user.id = :userId
        and f.product.status = 'ACTIVE'
        and f.product.shop.status = 'APPROVED'
        and f.product.shop.enabled = true and f.product.category.active = true
        """)
    Page<Favorite> findPublicByUserId(@org.springframework.data.repository.query.Param("userId") Long userId, Pageable pageable);
    long countByProductId(Long productId);
}
