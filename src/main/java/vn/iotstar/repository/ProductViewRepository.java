package vn.iotstar.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.ProductView;

public interface ProductViewRepository extends JpaRepository<ProductView, Long> {
    Optional<ProductView> findByUserIdAndProductId(Long userId, Long productId);
    Page<ProductView> findByUserIdOrderByViewedAtDesc(Long userId, Pageable pageable);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"product", "product.shop", "product.category"})
    @org.springframework.data.jpa.repository.Query("""
        select v from ProductView v where v.user.id = :userId
        and v.product.status = 'ACTIVE'
        and v.product.shop.status = 'APPROVED'
        and v.product.shop.enabled = true and v.product.category.active = true
        """)
    Page<ProductView> findPublicByUserId(@org.springframework.data.repository.query.Param("userId") Long userId, Pageable pageable);
}
