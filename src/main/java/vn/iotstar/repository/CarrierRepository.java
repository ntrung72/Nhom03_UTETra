package vn.iotstar.repository;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.Carrier;

public interface CarrierRepository extends JpaRepository<Carrier, Long> {
    List<Carrier> findByActiveTrueOrderByShippingFeeAsc();
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    @Query("select c from Carrier c where :q is null or lower(c.name) like lower(concat('%', :q, '%'))")
    Page<Carrier> search(@Param("q") String q, Pageable pageable);
}
