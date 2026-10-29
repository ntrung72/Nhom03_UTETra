package vn.iotstar.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long>, PublicProductSearchRepository {
    Optional<Product> findBySkuIgnoreCase(String sku);
    boolean existsBySkuIgnoreCase(String sku);
    boolean existsBySkuIgnoreCaseAndIdNot(String sku, Long id);
    List<Product> findByShopIdOrderByNameAsc(Long shopId);
    List<Product> findByShopIdInOrderByNameAsc(List<Long> shopIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :productIds order by p.id")
    List<Product> findAllByIdForCheckout(@Param("productIds") List<Long> productIds);

    @Query("select p from Product p where p.shop.id = :shopId and p.stock <= :threshold order by p.stock, p.name")
    List<Product> findLowStockByShop(@Param("shopId") Long shopId, @Param("threshold") int threshold,
                                     Pageable pageable);

    @Query("select p from Product p where p.shop.id in :shopIds and p.stock <= :threshold order by p.stock, p.name")
    List<Product> findLowStockByShopIds(@Param("shopIds") List<Long> shopIds,
                                        @Param("threshold") int threshold, Pageable pageable);

    @Query("select p from Product p where p.stock <= :threshold order by p.stock, p.name")
    List<Product> findLowStock(@Param("threshold") int threshold, Pageable pageable);

    @Query("""
        select p from Product p
        where p.status = :productStatus and p.shop.status = :shopStatus and p.shop.enabled = true
          and p.category.active = true
          and p.soldCount > :minimumSold
        order by p.soldCount desc, p.id desc
        """)
    List<Product> findBestSellers(@Param("minimumSold") long minimumSold,
                                  @Param("productStatus") ProductStatus productStatus,
                                  @Param("shopStatus") ShopStatus shopStatus,
                                  Pageable pageable);

    @Query("""
        select p from Product p where p.shop.id = :shopId
          and (:q is null or lower(p.name) like lower(concat('%', :q, '%'))
               or lower(p.sku) like lower(concat('%', :q, '%'))
               or lower(p.slug) like lower(concat('%', :searchSlug, '%')))
        """)
    Page<Product> searchByShop(@Param("shopId") Long shopId, @Param("q") String q,
                               @Param("searchSlug") String searchSlug, Pageable pageable);

    @Query("""
        select p from Product p where
          (:q is null or lower(p.name) like lower(concat('%', :q, '%'))
           or lower(p.sku) like lower(concat('%', :q, '%'))
           or lower(p.slug) like lower(concat('%', :searchSlug, '%')))
          and (:shopId is null or p.shop.id = :shopId)
          and (:categoryId is null or p.category.id = :categoryId)
        """)
    Page<Product> searchManagement(@Param("q") String q, @Param("searchSlug") String searchSlug,
                                   @Param("shopId") Long shopId,
                                   @Param("categoryId") Long categoryId, Pageable pageable);

    @Query("""
        select p from Product p where p.shop.id in :shopIds
          and (:q is null or lower(p.name) like lower(concat('%', :q, '%'))
               or lower(p.sku) like lower(concat('%', :q, '%'))
               or lower(p.slug) like lower(concat('%', :searchSlug, '%')))
          and (:shopId is null or p.shop.id = :shopId)
          and (:categoryId is null or p.category.id = :categoryId)
        """)
    Page<Product> searchManagementAssigned(@Param("shopIds") List<Long> shopIds, @Param("q") String q,
                                           @Param("searchSlug") String searchSlug,
                                           @Param("shopId") Long shopId, @Param("categoryId") Long categoryId,
                                           Pageable pageable);

    long countByShopId(Long shopId);
    long countByShopIdIn(List<Long> shopIds);
    long countByShopIdInAndStockLessThanEqual(List<Long> shopIds, int threshold);

}
