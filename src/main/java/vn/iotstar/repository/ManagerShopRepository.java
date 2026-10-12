package vn.iotstar.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.ManagerShop;

public interface ManagerShopRepository extends JpaRepository<ManagerShop, Long> {
    List<ManagerShop> findByManagerIdOrderByShopNameAsc(Long managerId);
    Optional<ManagerShop> findByManagerIdAndShopId(Long managerId, Long shopId);
    boolean existsByManagerIdAndShopId(Long managerId, Long shopId);
    void deleteByManagerId(Long managerId);

    @Query("select ms.shop.id from ManagerShop ms where ms.manager.id = :managerId order by ms.shop.name")
    List<Long> findShopIdsByManagerId(@Param("managerId") Long managerId);
}
