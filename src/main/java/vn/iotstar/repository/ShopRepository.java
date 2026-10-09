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
}
