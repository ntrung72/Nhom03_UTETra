package vn.iotstar.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.PaymentTransaction;
import vn.iotstar.entity.DomainEnums.PaymentTransactionStatus;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentTransaction t where t.id = :id")
    Optional<PaymentTransaction> findByIdForUpdate(@Param("id") Long id);

    @Query("select t from PaymentTransaction t where t.status in :statuses and t.gatewayTransactionId like 'https://pay.payos.vn/web/%'")
    List<PaymentTransaction> findRecheckablePayOs(@Param("statuses") List<PaymentTransactionStatus> statuses);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PaymentTransaction t where t.transactionCode = :code order by t.id")
    List<PaymentTransaction> findByCodeForUpdate(@Param("code") String code);

    List<PaymentTransaction> findByTransactionCodeOrderByIdAsc(String transactionCode);

    List<PaymentTransaction> findByGatewayTransactionId(String gatewayTransactionId);

    Optional<PaymentTransaction> findFirstByOrderIdOrderByCreatedAtDescIdDesc(Long orderId);

    Optional<PaymentTransaction> findFirstByOrderIdAndStatusOrderByPaidAtDescIdDesc(
        Long orderId, PaymentTransactionStatus status);

    long countByOrderIdAndStatus(Long orderId, PaymentTransactionStatus status);

    @Query("select t.order.id from PaymentTransaction t where t.order.id in :orderIds "
        + "and t.status = :status group by t.order.id having count(t.id) > 1")
    List<Long> findOrderIdsWithMultipleTransactionsInStatus(@Param("orderIds") List<Long> orderIds,
                                                             @Param("status") PaymentTransactionStatus status);

    @EntityGraph(attributePaths = {"order", "order.user", "order.shop"})
    @Query("""
        select transaction from PaymentTransaction transaction
        where (:q is null
          or lower(transaction.transactionCode) like lower(concat('%', :q, '%'))
          or lower(transaction.order.code) like lower(concat('%', :q, '%'))
          or lower(transaction.order.user.username) like lower(concat('%', :q, '%'))
          or lower(transaction.order.user.email) like lower(concat('%', :q, '%')))
          and (:status is null or transaction.status = :status)
        """)
    Page<PaymentTransaction> searchManagement(@Param("q") String q,
                                              @Param("status") PaymentTransactionStatus status,
                                              Pageable pageable);
}
