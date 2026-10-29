package vn.iotstar.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.ProductView;

public interface ProductViewRepository extends JpaRepository<ProductView, Long> {
    Optional<ProductView> findByUserIdAndProductId(Long userId, Long productId);
    Page<ProductView> findByUserIdOrderByViewedAtDesc(Long userId, Pageable pageable);
}
