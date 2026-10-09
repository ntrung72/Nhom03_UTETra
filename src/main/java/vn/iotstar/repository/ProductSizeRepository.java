package vn.iotstar.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.ProductSize;

public interface ProductSizeRepository
        extends JpaRepository<ProductSize, Long> {

    List<ProductSize>
        findByProductIdAndActiveTrueOrderByDisplayOrderAscIdAsc(
            Long productId
        );

    List<ProductSize> findByProductIdOrderByDisplayOrderAscIdAsc(
        Long productId
    );

    Optional<ProductSize> findByIdAndProductIdAndActiveTrue(
        Long id,
        Long productId
    );

    Optional<ProductSize> findByProductIdAndCodeIgnoreCase(
        Long productId,
        String code
    );

    boolean existsByProductId(Long productId);

    boolean existsByProductIdAndCodeIgnoreCase(
        Long productId,
        String code
    );

    void deleteByProductId(Long productId);
}