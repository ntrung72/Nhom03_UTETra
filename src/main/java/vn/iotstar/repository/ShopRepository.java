package vn.iotstar.repository;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.DomainEnums.ShopStatus;

public interface ShopRepository extends JpaRepository<Shop, Long> {

    Optional<Shop> findByOwnerId(Long ownerId);

    Optional<Shop> findBySlug(String slug);

    boolean existsByOwnerId(Long ownerId);

    boolean existsBySlug(String slug);

    Optional<Shop> findBySlugAndStatusAndEnabledTrue(String slug, ShopStatus status);

    List<Shop> findByStatusAndEnabledTrueOrderByNameAsc(ShopStatus status);

    @org.springframework.data.jpa.repository.Query("""
        select s from Shop s where (:q is null or lower(s.name) like lower(concat('%', :q, '%'))
          or lower(s.slug) like lower(concat('%', :q, '%'))
          or lower(s.owner.username) like lower(concat('%', :q, '%'))
          or lower(s.owner.email) like lower(concat('%', :q, '%')))
          and (:status is null or s.status = :status)
        """)
    org.springframework.data.domain.Page<Shop> searchManagement(
        @org.springframework.data.repository.query.Param("q") String q,
        @org.springframework.data.repository.query.Param("status") ShopStatus status,
        org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("""
        select s from Shop s where s.id in :ids
          and (:q is null or lower(s.name) like lower(concat('%', :q, '%'))
          or lower(s.slug) like lower(concat('%', :q, '%'))
          or lower(s.owner.username) like lower(concat('%', :q, '%'))
          or lower(s.owner.email) like lower(concat('%', :q, '%')))
          and (:status is null or s.status = :status)
        """)
    org.springframework.data.domain.Page<Shop> searchAssigned(
        @org.springframework.data.repository.query.Param("ids") java.util.List<Long> ids,
        @org.springframework.data.repository.query.Param("q") String q,
        @org.springframework.data.repository.query.Param("status") ShopStatus status,
        org.springframework.data.domain.Pageable pageable);
}
