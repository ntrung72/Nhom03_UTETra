package vn.iotstar.repository;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.ProductTopping;

public interface ProductToppingRepository
        extends JpaRepository<ProductTopping, Long> {

    @EntityGraph(attributePaths = "topping")
    List<ProductTopping>
        findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(
            Long productId
        );

    boolean existsByProductIdAndToppingId(
        Long productId,
        Long toppingId
    );

    boolean existsByProductId(Long productId);

    boolean existsByToppingId(Long toppingId);

    long countByToppingId(Long toppingId);

    void deleteByProductId(Long productId);
}