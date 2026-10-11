package vn.iotstar.repository;

import java.util.Optional;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.ShopStatus;

public interface ProductRepository extends JpaRepository<Product, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"shop", "category"})
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {"shop", "category"})
    @Query("""
        select p from Product p where p.status = :productStatus
        and p.shop.status = :shopStatus and p.shop.enabled = true and p.category.active = true
        and p.soldCount > :minSold order by p.soldCount desc, p.id desc
        """)
    List<Product> findBestSellers(@Param("minSold") long minSold,
        @Param("productStatus") ProductStatus productStatus,
        @Param("shopStatus") ShopStatus shopStatus, Pageable pageable);

    @EntityGraph(attributePaths = {"shop", "category"})
    @Query("""
        select p from Product p where p.status = :productStatus
        and p.shop.status = :shopStatus and p.shop.enabled = true and p.category.active = true
        and (:q is null or lower(p.name) like lower(concat('%', :q, '%'))
             or p.slug like concat('%', :slugQuery, '%'))
        and (:categoryId is null or p.category.id = :categoryId)
        and (:shopId is null or p.shop.id = :shopId)
        and (:minPrice is null or p.price >= :minPrice)
        and (:maxPrice is null or p.price <= :maxPrice)
        and (:minRating is null or p.averageRating >= :minRating)
        and (:inStockOnly = false or p.stock > 0)
        """)
    Page<Product> searchPublic(@Param("q") String q, @Param("slugQuery") String slugQuery,
        @Param("categoryId") Long categoryId, @Param("shopId") Long shopId,
        @Param("minPrice") BigDecimal minPrice, @Param("maxPrice") BigDecimal maxPrice,
        @Param("minRating") BigDecimal minRating, @Param("inStockOnly") boolean inStockOnly,
        @Param("productStatus") ProductStatus productStatus,
        @Param("shopStatus") ShopStatus shopStatus, Pageable pageable);

    @EntityGraph(attributePaths = {"shop", "category"})
    @Query("""
        select p from Product p where p.id = :id and p.status = :productStatus
        and p.shop.status = :shopStatus and p.shop.enabled = true and p.category.active = true
        """)
    Optional<Product> findPublicById(@Param("id") Long id,
        @Param("productStatus") ProductStatus productStatus, @Param("shopStatus") ShopStatus shopStatus);

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
