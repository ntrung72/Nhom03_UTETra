package vn.iotstar.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.Shop;

public interface ShopRepository extends JpaRepository<Shop, Long> {

    Optional<Shop> findByOwnerId(Long ownerId);

    Optional<Shop> findBySlug(String slug);

    boolean existsByOwnerId(Long ownerId);

    boolean existsBySlug(String slug);
}