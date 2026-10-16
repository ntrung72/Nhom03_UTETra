package vn.iotstar.repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import vn.iotstar.entity.PaymentTransaction;
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    boolean existsByOrderIdAndReconciliationRequiredTrue(Long orderId);
    Optional<PaymentTransaction> findFirstByTransactionCodeOrderByIdAsc(String code);
    Optional<PaymentTransaction> findFirstByCheckoutReferenceOrderByIdDesc(String reference);
    Optional<PaymentTransaction> findFirstByOrderIdOrderByCreatedAtDescIdDesc(Long orderId);
    List<PaymentTransaction> findByTransactionCodeOrderByIdAsc(String transactionCode);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentTransaction t where t.transactionCode = :code order by t.id")
    List<PaymentTransaction> findByCodeForUpdate(@Param("code") String code);
    @Query("""
        select distinct t.checkoutReference from PaymentTransaction t
        where t.expiresAt <= :now and t.order.status = :orderStatus
          and t.order.paymentStatus = :paymentStatus
        """)
    List<String> findExpiredCheckoutReferences(@Param("now") LocalDateTime now,
        @Param("orderStatus") vn.iotstar.entity.DomainEnums.OrderStatus orderStatus,
        @Param("paymentStatus") vn.iotstar.entity.DomainEnums.PaymentStatus paymentStatus, Pageable pageable);
    @Query("select t from PaymentTransaction t where t.checkoutReference = :reference order by t.id")
    List<PaymentTransaction> findForCheckout(@Param("reference") String reference);
}
