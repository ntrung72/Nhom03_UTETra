package vn.iotstar.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import vn.iotstar.entity.DomainEnums.VoucherUsageStatus;
import vn.iotstar.entity.VoucherUsage;

public interface VoucherUsageRepository extends JpaRepository<VoucherUsage, Long> {
    long countByUserIdAndVoucherId(Long userId, Long voucherId);
    long countByUserIdAndVoucherIdAndStatus(Long userId, Long voucherId, VoucherUsageStatus status);
    long countByVoucherId(Long voucherId);
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"user", "voucher"})
    Page<VoucherUsage> findByVoucherIdOrderByUsedAtDesc(Long voucherId, Pageable pageable);
    boolean existsByOrderId(Long orderId);
    boolean existsByCheckoutReference(String checkoutReference);
    boolean existsByVoucherId(Long voucherId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from VoucherUsage u where u.checkoutReference = :checkoutReference")
    Optional<VoucherUsage> findByCheckoutReferenceForUpdate(@Param("checkoutReference") String checkoutReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from VoucherUsage u where u.orderId = :orderId")
    Optional<VoucherUsage> findByOrderIdForUpdate(@Param("orderId") Long orderId);
}
