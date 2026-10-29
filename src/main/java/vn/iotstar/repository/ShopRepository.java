package vn.iotstar.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Shop;

public interface ShopRepository extends JpaRepository<Shop, Long> {
    @Query("""
        select s from Shop s where s.status = :status and s.enabled = true
          and (lower(s.name) like lower(concat('%', :q, '%'))
            or lower(s.slug) like lower(concat('%', :slugQuery, '%')))
        order by s.name
        """)
    List<Shop> suggestPublic(@Param("q") String q, @Param("slugQuery") String slugQuery,
                             @Param("status") ShopStatus status, Pageable pageable);
    Optional<Shop> findByOwnerId(Long ownerId);
    Optional<Shop> findBySlug(String slug);
    Optional<Shop> findBySlugAndStatusAndEnabledTrue(String slug, ShopStatus status);
    List<Shop> findByStatusAndEnabledTrueOrderByNameAsc(ShopStatus status);
    boolean existsByOwnerId(Long ownerId);
    boolean existsBySlug(String slug);

    @Query("""
        select s from Shop s
        where (:q is null or lower(s.name) like lower(concat('%', :q, '%'))
            or lower(s.owner.username) like lower(concat('%', :q, '%'))
            or lower(s.owner.fullName) like lower(concat('%', :q, '%'))
            or lower(s.owner.email) like lower(concat('%', :q, '%')))
          and (:status is null or s.status = :status)
        """)
    Page<Shop> search(@Param("q") String q, @Param("status") ShopStatus status, Pageable pageable);

    @Query("""
        select s from Shop s
        where s.id in :shopIds
          and (:q is null or lower(s.name) like lower(concat('%', :q, '%'))
            or lower(s.owner.username) like lower(concat('%', :q, '%'))
            or lower(s.owner.fullName) like lower(concat('%', :q, '%'))
            or lower(s.owner.email) like lower(concat('%', :q, '%')))
          and (:status is null or s.status = :status)
        """)
    Page<Shop> searchAssigned(@Param("shopIds") List<Long> shopIds, @Param("q") String q,
                              @Param("status") ShopStatus status, Pageable pageable);
}
