package vn.iotstar.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.Cart;

public interface CartRepository extends JpaRepository<Cart, Long> {
    @EntityGraph(attributePaths = {"items", "items.product", "items.product.shop", "items.product.category"})
    Optional<Cart> findByUserId(Long userId);
}
