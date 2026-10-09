package vn.iotstar.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySkuIgnoreCase(String sku);

    boolean existsBySkuIgnoreCaseAndIdNot(String sku, Long id);

    @EntityGraph(attributePaths = {"shop", "category"})
    Optional<Product> findByIdAndShopId(Long id, Long shopId);

    @EntityGraph(attributePaths = {"shop", "category"})
    @Query("""
        select p from Product p
        where p.shop.id = :shopId
          and (
              :q is null
              or lower(p.name) like lower(concat('%', :q, '%'))
              or lower(p.sku) like lower(concat('%', :q, '%'))
          )
        """)
    Page<Product> searchByShop(
        @Param("shopId") Long shopId,
        @Param("q") String q,
        Pageable pageable
    );
}