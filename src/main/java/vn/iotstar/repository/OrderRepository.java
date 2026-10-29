package vn.iotstar.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {
    @Query("select o.id from CustomerOrder o where o.shipper is not null "
        + "and o.status in :statuses and (o.shipperAssignmentStatus = :pending or o.shipperAssignmentStatus is null)")
    List<Long> findPendingShipperAssignmentIds(@Param("statuses") List<OrderStatus> statuses,
                                                @Param("pending") vn.iotstar.entity.DomainEnums.ShipperAssignmentStatus pending);
    @Query("select o.id from CustomerOrder o where o.deliveryProofUrl is not null and o.deliveryProofUrl not like 'private:%'")
    List<Long> findLegacyDeliveryProofOrderIds();
    @Query("select o from CustomerOrder o where o.user.id = :userId and (:status is null or o.status = :status)")
    Page<CustomerOrder> findForUser(@Param("userId") Long userId, @Param("status") OrderStatus status, Pageable pageable);

    @Query("select o from CustomerOrder o where o.shop.id = :shopId and (:status is null or o.status = :status)")
    Page<CustomerOrder> findForShop(@Param("shopId") Long shopId, @Param("status") OrderStatus status, Pageable pageable);

    @Query("select o from CustomerOrder o where o.shop.id = :shopId "
        + "and (:status is null or o.status = :status) "
        + "and (:pending = false or (o.status in :pendingStatuses "
        + "and not (o.status = vn.iotstar.entity.DomainEnums.OrderStatus.NEW "
        + "and o.paymentMethod = vn.iotstar.entity.DomainEnums.PaymentMethod.BANK_TRANSFER "
        + "and o.paymentStatus = vn.iotstar.entity.DomainEnums.PaymentStatus.UNPAID))) "
        + "and (:q is null or lower(o.code) like lower(concat('%', :q, '%')) "
        + "or lower(o.receiverName) like lower(concat('%', :q, '%')))")
    Page<CustomerOrder> findForShopSearch(@Param("shopId") Long shopId, @Param("status") OrderStatus status,
                                          @Param("pending") boolean pending,
                                          @Param("pendingStatuses") List<OrderStatus> pendingStatuses,
                                          @Param("q") String q, Pageable pageable);

    @Query("""
        select o from CustomerOrder o where o.shipper.id = :shipperId
          and (:status is null or o.status = :status)
          and (:q is null or lower(o.code) like lower(concat('%', :q, '%')) escape '!')
        order by case when :oldest = true then
                   case when o.status = :failedStatus then o.updatedAt
                        else coalesce(o.deliveredAt, o.pickedUpAt, o.createdAt) end end asc,
                 case when :oldest = false then
                   case when o.status = :failedStatus then o.updatedAt
                        else coalesce(o.deliveredAt, o.pickedUpAt, o.createdAt) end end desc,
                 o.id desc
        """)
    Page<CustomerOrder> findForShipper(@Param("shipperId") Long shipperId, @Param("status") OrderStatus status,
                                       @Param("q") String q, @Param("failedStatus") OrderStatus failedStatus,
                                       @Param("oldest") boolean oldest, Pageable pageable);

    @Query("""
        select o from CustomerOrder o where
          (:q is null or lower(o.code) like lower(concat('%', :q, '%'))
             or lower(o.user.username) like lower(concat('%', :q, '%')))
          and (:shopId is null or o.shop.id = :shopId)
          and (:status is null or o.status = :status)
          and (:pending = false or (o.status in :pendingStatuses
            and not (o.status = vn.iotstar.entity.DomainEnums.OrderStatus.NEW
              and o.paymentMethod = vn.iotstar.entity.DomainEnums.PaymentMethod.BANK_TRANSFER
              and o.paymentStatus = vn.iotstar.entity.DomainEnums.PaymentStatus.UNPAID)))
        """)
    Page<CustomerOrder> searchManagement(@Param("q") String q, @Param("shopId") Long shopId,
                                         @Param("status") OrderStatus status,
                                         @Param("pending") boolean pending,
                                         @Param("pendingStatuses") List<OrderStatus> pendingStatuses,
                                         Pageable pageable);

    @Query("""
        select o from CustomerOrder o where o.shop.id in :shopIds
          and (:q is null or lower(o.code) like lower(concat('%', :q, '%'))
             or lower(o.user.username) like lower(concat('%', :q, '%')))
          and (:shopId is null or o.shop.id = :shopId)
          and (:status is null or o.status = :status)
          and (:pending = false or (o.status in :pendingStatuses
            and not (o.status = vn.iotstar.entity.DomainEnums.OrderStatus.NEW
              and o.paymentMethod = vn.iotstar.entity.DomainEnums.PaymentMethod.BANK_TRANSFER
              and o.paymentStatus = vn.iotstar.entity.DomainEnums.PaymentStatus.UNPAID)))
        """)
    Page<CustomerOrder> searchManagementAssigned(@Param("shopIds") java.util.List<Long> shopIds,
                                                  @Param("q") String q, @Param("shopId") Long shopId,
                                                  @Param("status") OrderStatus status,
                                                  @Param("pending") boolean pending,
                                                  @Param("pendingStatuses") List<OrderStatus> pendingStatuses,
                                                  Pageable pageable);

    @EntityGraph(attributePaths = {"items", "items.product", "shop", "carrier", "shipper", "user"})
    Optional<CustomerOrder> findDetailedById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id = :id")
    Optional<CustomerOrder> findDetailedByIdForUpdate(@Param("id") Long id);

    Optional<CustomerOrder> findByIdAndUserId(Long id, Long userId);
    Optional<CustomerOrder> findByIdAndShopId(Long id, Long shopId);
    Optional<CustomerOrder> findByIdAndShipperId(Long id, Long shipperId);
    Optional<CustomerOrder> findByCode(String code);
    boolean existsByVoucherId(Long voucherId);
    long countByCheckoutReferenceAndIdNotAndStatusNot(String checkoutReference, Long id, OrderStatus status);

    List<CustomerOrder> findByCheckoutReferenceAndUserIdOrderByIdAsc(String checkoutReference, Long userId);
    java.util.List<CustomerOrder> findByCommissionBaseIsNull();
    java.util.List<CustomerOrder> findByProductDiscountIsNull();
    java.util.List<CustomerOrder> findByVoucherIsNotNull();

    @EntityGraph(attributePaths = {"items", "items.product", "shop"})
    @Query("""
        select distinct o from CustomerOrder o where o.shop.id = :shopId
          and ((o.deliveredAt is not null and o.deliveredAt >= :from)
            or (o.deliveredAt is null and o.createdAt >= :from))
        """)
    List<CustomerOrder> findDashboardOrdersByShop(@Param("shopId") Long shopId,
                                                   @Param("from") LocalDateTime from);

    @EntityGraph(attributePaths = {"items", "items.product", "shop"})
    @Query("""
        select distinct o from CustomerOrder o where o.shop.id in :shopIds
          and ((o.deliveredAt is not null and o.deliveredAt >= :from)
            or (o.deliveredAt is null and o.createdAt >= :from))
        """)
    List<CustomerOrder> findDashboardOrdersByShopIds(@Param("shopIds") List<Long> shopIds,
                                                      @Param("from") LocalDateTime from);

    @EntityGraph(attributePaths = {"items", "items.product", "shop"})
    @Query("""
        select distinct o from CustomerOrder o
        where (o.deliveredAt is not null and o.deliveredAt >= :from)
           or (o.deliveredAt is null and o.createdAt >= :from)
        """)
    List<CustomerOrder> findDashboardOrders(@Param("from") LocalDateTime from);

    @Query("""
        select coalesce(sum(o.sellerRevenue), 0) from CustomerOrder o
        where o.shop.id = :shopId and o.status = :delivered and o.paymentStatus = :paid
          and coalesce(o.deliveredAt, o.createdAt) >= :from
          and coalesce(o.deliveredAt, o.createdAt) < :until
        """)
    BigDecimal sumCompletedSellerRevenueForShopBetween(@Param("shopId") Long shopId,
        @Param("delivered") OrderStatus delivered, @Param("paid") PaymentStatus paid,
        @Param("from") LocalDateTime from, @Param("until") LocalDateTime until);

    @Query("""
        select coalesce(sum(o.totalAmount), 0) from CustomerOrder o
        where o.shop.id in :shopIds and o.status = :delivered and o.paymentStatus = :paid
          and coalesce(o.deliveredAt, o.createdAt) >= :from
          and coalesce(o.deliveredAt, o.createdAt) < :until
        """)
    BigDecimal sumCompletedGrossRevenueForShopsBetween(@Param("shopIds") List<Long> shopIds,
        @Param("delivered") OrderStatus delivered, @Param("paid") PaymentStatus paid,
        @Param("from") LocalDateTime from, @Param("until") LocalDateTime until);

    @EntityGraph(attributePaths = {"shop"})
    @Query("""
        select o from CustomerOrder o
        where ((o.deliveredAt is not null and o.deliveredAt >= :from and o.deliveredAt < :until)
            or (o.deliveredAt is null and o.createdAt >= :from and o.createdAt < :until))
          and (:status is null or o.status = :status)
        order by o.createdAt desc, o.id desc
        """)
    List<CustomerOrder> findReportOrders(@Param("from") LocalDateTime from,
                                         @Param("until") LocalDateTime until,
                                         @Param("status") OrderStatus status);

    @EntityGraph(attributePaths = {"shop"})
    @Query("""
        select o from CustomerOrder o
        where o.shop.id in :shopIds
          and ((o.deliveredAt is not null and o.deliveredAt >= :from and o.deliveredAt < :until)
            or (o.deliveredAt is null and o.createdAt >= :from and o.createdAt < :until))
          and (:status is null or o.status = :status)
        order by o.createdAt desc, o.id desc
        """)
    List<CustomerOrder> findReportOrdersByShopIds(@Param("shopIds") List<Long> shopIds,
                                                   @Param("from") LocalDateTime from,
                                                   @Param("until") LocalDateTime until,
                                                   @Param("status") OrderStatus status);

    @Query("select o.status, count(o) from CustomerOrder o where o.shop.id = :shopId "
        + "and not (o.status = vn.iotstar.entity.DomainEnums.OrderStatus.NEW "
        + "and o.paymentMethod = vn.iotstar.entity.DomainEnums.PaymentMethod.BANK_TRANSFER "
        + "and o.paymentStatus = vn.iotstar.entity.DomainEnums.PaymentStatus.UNPAID) group by o.status")
    List<Object[]> countByStatusForShop(@Param("shopId") Long shopId);

    @Query("select o.status, count(o) from CustomerOrder o where o.shop.id in :shopIds "
        + "and not (o.status = vn.iotstar.entity.DomainEnums.OrderStatus.NEW "
        + "and o.paymentMethod = vn.iotstar.entity.DomainEnums.PaymentMethod.BANK_TRANSFER "
        + "and o.paymentStatus = vn.iotstar.entity.DomainEnums.PaymentStatus.UNPAID) group by o.status")
    List<Object[]> countByStatusForShopIds(@Param("shopIds") List<Long> shopIds);

    @Query("select o.status, count(o) from CustomerOrder o where not "
        + "(o.status = vn.iotstar.entity.DomainEnums.OrderStatus.NEW "
        + "and o.paymentMethod = vn.iotstar.entity.DomainEnums.PaymentMethod.BANK_TRANSFER "
        + "and o.paymentStatus = vn.iotstar.entity.DomainEnums.PaymentStatus.UNPAID) group by o.status")
    List<Object[]> countByStatusForAllShops();

    @Query("select sum(o.totalAmount) from CustomerOrder o where o.shop.id = :shopId and o.paymentStatus = :paymentStatus and o.status = :orderStatus")
    BigDecimal sumRevenue(@Param("shopId") Long shopId, @Param("paymentStatus") PaymentStatus paymentStatus,
                          @Param("orderStatus") OrderStatus orderStatus);

    @Query("select sum(o.sellerRevenue) from CustomerOrder o where o.shop.id = :shopId and o.paymentStatus = :paymentStatus and o.status = :orderStatus")
    BigDecimal sumSellerRevenue(@Param("shopId") Long shopId, @Param("paymentStatus") PaymentStatus paymentStatus,
                                @Param("orderStatus") OrderStatus orderStatus);

    @Query("select sum(o.platformRevenue) from CustomerOrder o where o.shop.id = :shopId and o.paymentStatus = :paymentStatus and o.status = :orderStatus")
    BigDecimal sumPlatformRevenueByShop(@Param("shopId") Long shopId, @Param("paymentStatus") PaymentStatus paymentStatus,
                                        @Param("orderStatus") OrderStatus orderStatus);

    @Query("select sum(o.platformRevenue) from CustomerOrder o where o.paymentStatus = :paymentStatus and o.status = :orderStatus")
    BigDecimal sumPlatformRevenue(@Param("paymentStatus") PaymentStatus paymentStatus,
                                  @Param("orderStatus") OrderStatus orderStatus);

    @Query("select sum(o.sellerRevenue) from CustomerOrder o where o.paymentStatus = :paymentStatus and o.status = :orderStatus")
    BigDecimal sumSellerRevenue(@Param("paymentStatus") PaymentStatus paymentStatus,
                                @Param("orderStatus") OrderStatus orderStatus);

    @Query("select sum(o.platformRevenue) from CustomerOrder o where o.shop.id in :shopIds and o.paymentStatus = :paymentStatus and o.status = :orderStatus")
    BigDecimal sumPlatformRevenueByShopIds(@Param("shopIds") java.util.List<Long> shopIds,
                                           @Param("paymentStatus") PaymentStatus paymentStatus,
                                           @Param("orderStatus") OrderStatus orderStatus);

    @Query("select sum(o.sellerRevenue) from CustomerOrder o where o.shop.id in :shopIds and o.paymentStatus = :paymentStatus and o.status = :orderStatus")
    BigDecimal sumSellerRevenueByShopIds(@Param("shopIds") java.util.List<Long> shopIds,
                                         @Param("paymentStatus") PaymentStatus paymentStatus,
                                         @Param("orderStatus") OrderStatus orderStatus);

    long countByShopIdAndStatus(Long shopId, OrderStatus status);
    @Query("select count(o) from CustomerOrder o where o.shop.id = :shopId "
        + "and o.status = vn.iotstar.entity.DomainEnums.OrderStatus.NEW "
        + "and not (o.paymentMethod = vn.iotstar.entity.DomainEnums.PaymentMethod.BANK_TRANSFER "
        + "and o.paymentStatus = vn.iotstar.entity.DomainEnums.PaymentStatus.UNPAID)")
    long countActionableNewOrdersForShop(@Param("shopId") Long shopId);
    long countByShopIdIn(java.util.List<Long> shopIds);
    long countByShipperId(Long shipperId);
    long countByShipperIdAndStatus(Long shipperId, OrderStatus status);
}
