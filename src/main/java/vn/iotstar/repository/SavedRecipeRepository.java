package vn.iotstar.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.SavedRecipe;

public interface SavedRecipeRepository extends JpaRepository<SavedRecipe, Long> {
    @EntityGraph(attributePaths = {"product", "product.shop", "product.category"})
    Page<SavedRecipe> findByUserIdOrderByUpdatedAtDescIdDesc(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"product", "product.shop", "product.category"})
    Optional<SavedRecipe> findByIdAndUserId(Long id, Long userId);
}
