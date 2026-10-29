package vn.iotstar.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.ProductStatus;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {
    @Query("""
        select promotion from Promotion promotion left join promotion.product product
          left join product.category category left join product.shop productShop
        where promotion.shop.id = :shopId
          and promotion.active = true and promotion.startsAt <= :now and promotion.endsAt >= :now
          and (product is null or (product.status = :activeStatus
            and category.active = true and productShop.id = :shopId))
        order by promotion.endsAt asc, promotion.id asc
        """)
    List<Promotion> findActiveForPublicShop(@Param("shopId") Long shopId,
                                           @Param("now") LocalDateTime now,
                                           @Param("activeStatus") ProductStatus activeStatus,
                                           Pageable pageable);

    List<Promotion> findByShopIdOrderByStartsAtDesc(Long shopId);
    List<Promotion> findAllByOrderByStartsAtDesc();

    @Query("""
        select p from Promotion p
        where (:q is null or lower(p.name) like lower(concat('%', :q, '%')))
          and (:shopId is null or p.shop.id = :shopId)
          and (:type is null or p.type = :type)
        """)
    Page<Promotion> search(@Param("q") String q, @Param("shopId") Long shopId,
                           @Param("type") PromotionType type, Pageable pageable);

    @Query("""
        select p from Promotion p
        where p.shop.id in :shopIds
          and (:q is null or lower(p.name) like lower(concat('%', :q, '%')))
          and (:shopId is null or p.shop.id = :shopId)
          and (:type is null or p.type = :type)
        """)
    Page<Promotion> searchAssigned(@Param("shopIds") List<Long> shopIds, @Param("q") String q,
                                   @Param("shopId") Long shopId, @Param("type") PromotionType type,
                                   Pageable pageable);

    @Query("""
        select p from Promotion p
        where p.active = true and p.startsAt <= :now and p.endsAt >= :now
          and (p.shop is null or p.shop.id = :shopId)
          and (p.product is null or p.product.id in :productIds)
        """)
    List<Promotion> findApplicable(@Param("shopId") Long shopId,
                                   @Param("productIds") List<Long> productIds,
                                   @Param("now") LocalDateTime now);
}
