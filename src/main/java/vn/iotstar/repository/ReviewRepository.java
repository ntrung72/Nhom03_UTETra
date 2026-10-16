package vn.iotstar.repository;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.Review;
public interface ReviewRepository extends JpaRepository<Review, Long> {
    @EntityGraph(attributePaths = {"user", "orderItem"})
    Page<Review> findByProductIdOrderByCreatedAtDesc(Long productId, Pageable pageable);
    boolean existsByOrderItemId(Long orderItemId);
    long countByProductId(Long productId);
    @Query("select avg(r.rating) from Review r where r.product.id = :productId")
    Double averageRating(@Param("productId") Long productId);
    @Query("select r.orderItem.id from Review r where r.user.id = :userId and r.orderItem.order.id = :orderId")
    Set<Long> reviewedItemIds(@Param("userId") Long userId, @Param("orderId") Long orderId);
}
