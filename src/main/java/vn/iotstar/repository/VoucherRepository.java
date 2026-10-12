package vn.iotstar.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import vn.iotstar.entity.Voucher;

public interface VoucherRepository extends JpaRepository<Voucher, Long> {
    Optional<Voucher> findByCodeIgnoreCase(String code);
    List<Voucher> findByMaxUsesPerUserIsNull();
    boolean existsByCodeIgnoreCase(String code);
    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    @Query("""
        select v from Voucher v
        where (:q is null or lower(v.code) like lower(concat('%', :q, '%'))
            or lower(v.name) like lower(concat('%', :q, '%')))
          and (:type is null or v.type = :type)
          and (:active is null or v.active = :active)
        """)
    Page<Voucher> search(@Param("q") String q,
                         @Param("type") vn.iotstar.entity.DomainEnums.VoucherType type,
                         @Param("active") Boolean active,
                         Pageable pageable);

    @Query("select v from Voucher v where v.active = true and v.quantity > 0 and v.startsAt <= :now and v.endsAt >= :now order by v.endsAt asc")
    List<Voucher> findAvailable(@Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where upper(v.code) = upper(:code)")
    Optional<Voucher> findByCodeForCheckout(@Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where v.id = :id")
    Optional<Voucher> findByIdForUpdate(@Param("id") Long id);
}
