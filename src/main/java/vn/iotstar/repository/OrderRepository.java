package vn.iotstar.repository;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

	@EntityGraph(attributePaths = { "shop", "carrier" })
	@Query("""
			select o from CustomerOrder o
			where o.user.id = :userId
			  and (:status is null or o.status = :status)
			""")
	Page<CustomerOrder> findForUser(@Param("userId") Long userId, @Param("status") OrderStatus status,
			Pageable pageable);

	@EntityGraph(attributePaths = { "shop", "carrier", "voucher", "items", "items.product" })
	Optional<CustomerOrder> findByIdAndUserId(Long id, Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select o from CustomerOrder o
			where o.id = :id
			  and o.user.id = :userId
			""")
	Optional<CustomerOrder> findOwnedForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	long countByCheckoutReferenceAndIdNotAndStatusNot(String checkoutReference, Long id, OrderStatus status);

	@EntityGraph(attributePaths = { "shop", "carrier", "user" })
	@Query("""
			select o from CustomerOrder o
			where o.shop.id = :shopId
			  and (:status is null or o.status = :status)
			""")
	Page<CustomerOrder> findForShop(@Param("shopId") Long shopId, @Param("status") OrderStatus status,
			Pageable pageable);

	@EntityGraph(attributePaths = { "shop", "carrier", "voucher", "user", "items", "items.product" })
	Optional<CustomerOrder> findByIdAndShopId(Long id, Long shopId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select o from CustomerOrder o
			where o.id = :id
			  and o.shop.id = :shopId
			""")
	Optional<CustomerOrder> findShopOrderForUpdate(@Param("id") Long id, @Param("shopId") Long shopId);

	@Query("""
			select o.status, count(o)
			from CustomerOrder o
			where o.shop.id = :shopId
			group by o.status
			""")
	List<Object[]> countByStatusForShop(@Param("shopId") Long shopId);

	// ==================== Step 21: Order Management / Shipper ====================
	@EntityGraph(attributePaths = { "shop", "user", "shipper", "carrier" })
	@Query("select o from CustomerOrder o where o.shipper.id = :shipperId and (:status is null or o.status = :status)")
	Page<CustomerOrder> findForShipper(@Param("shipperId") Long shipperId, @Param("status") OrderStatus status,
			Pageable pageable);

	@EntityGraph(attributePaths = { "shop", "user", "shipper" })
	@Query("""
			select o from CustomerOrder o where
			  (:q is null or lower(o.code) like lower(concat('%', :q, '%'))
			      or lower(o.user.username) like lower(concat('%', :q, '%')))
			  and (:shopId is null or o.shop.id = :shopId)
			  and (:status is null or o.status = :status)
			""")
	Page<CustomerOrder> searchManagement(@Param("q") String q, @Param("shopId") Long shopId,
			@Param("status") OrderStatus status, Pageable pageable);

	@EntityGraph(attributePaths = { "shop", "user", "shipper" })
	@Query("""
			select o from CustomerOrder o where o.shop.id in :shopIds
			  and (:q is null or lower(o.code) like lower(concat('%', :q, '%'))
			      or lower(o.user.username) like lower(concat('%', :q, '%')))
			  and (:shopId is null or o.shop.id = :shopId)
			  and (:status is null or o.status = :status)
			""")
	Page<CustomerOrder> searchManagementAssigned(@Param("shopIds") List<Long> shopIds, @Param("q") String q,
			@Param("shopId") Long shopId, @Param("status") OrderStatus status, Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from CustomerOrder o where o.id = :id")
	Optional<CustomerOrder> findOrderForDeliveryUpdate(@Param("id") Long id);

	interface PaymentSummary {
		String getCheckoutReference();

		vn.iotstar.entity.DomainEnums.PaymentMethod getPaymentMethod();
	}

	@Query("select o.checkoutReference as checkoutReference, o.paymentMethod as paymentMethod from CustomerOrder o where o.id = :id and o.user.id = :userId")
	Optional<PaymentSummary> findOwnedPaymentSummary(@Param("id") Long id, @Param("userId") Long userId);

	List<CustomerOrder> findByCheckoutReferenceOrderByIdAsc(String checkoutReference);

	long countByShipperId(Long shipperId);

	long countByShipperIdAndStatus(Long shipperId, OrderStatus status);

	// ==================== Step 29: Revenue / Vendor Dashboard ====================

	List<CustomerOrder> findByCommissionBaseIsNull();

	@EntityGraph(attributePaths = { "items", "items.product", "shop" })
	@Query("""
			select distinct o from CustomerOrder o where o.shop.id = :shopId
			  and ((o.deliveredAt is not null and o.deliveredAt >= :from)
			    or (o.deliveredAt is null and o.createdAt >= :from))
			""")
	List<CustomerOrder> findDashboardOrdersByShop(@Param("shopId") Long shopId, @Param("from") LocalDateTime from);

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


	@EntityGraph(attributePaths = { "shop" })
	@Query("""
			select o from CustomerOrder o
			where ((o.deliveredAt is not null and o.deliveredAt >= :from and o.deliveredAt < :until)
			    or (o.deliveredAt is null and o.createdAt >= :from and o.createdAt < :until))
			  and (:status is null or o.status = :status)
			order by o.createdAt desc, o.id desc
			""")
	List<CustomerOrder> findReportOrders(@Param("from") LocalDateTime from, @Param("until") LocalDateTime until,
			@Param("status") OrderStatus status);

	@EntityGraph(attributePaths = { "shop" })
	@Query("""
			select o from CustomerOrder o
			where o.shop.id in :shopIds
			  and ((o.deliveredAt is not null and o.deliveredAt >= :from and o.deliveredAt < :until)
			    or (o.deliveredAt is null and o.createdAt >= :from and o.createdAt < :until))
			  and (:status is null or o.status = :status)
			order by o.createdAt desc, o.id desc
			""")
	List<CustomerOrder> findReportOrdersByShopIds(@Param("shopIds") List<Long> shopIds,
			@Param("from") LocalDateTime from, @Param("until") LocalDateTime until,
			@Param("status") OrderStatus status);

    @Query("select o.status, count(o) from CustomerOrder o where o.shop.id in :shopIds group by o.status")
    List<Object[]> countByStatusForShopIds(@Param("shopIds") List<Long> shopIds);

    @Query("select o.status, count(o) from CustomerOrder o group by o.status")
    List<Object[]> countByStatusForAllShops();


	@Query("select sum(o.totalAmount) from CustomerOrder o where o.shop.id = :shopId and o.paymentStatus = :paymentStatus and o.status = :orderStatus")
	BigDecimal sumRevenue(@Param("shopId") Long shopId, @Param("paymentStatus") PaymentStatus paymentStatus,
			@Param("orderStatus") OrderStatus orderStatus);

	@Query("select sum(o.sellerRevenue) from CustomerOrder o where o.shop.id = :shopId and o.paymentStatus = :paymentStatus and o.status = :orderStatus")
	BigDecimal sumSellerRevenue(@Param("shopId") Long shopId, @Param("paymentStatus") PaymentStatus paymentStatus,
			@Param("orderStatus") OrderStatus orderStatus);

	@Query("select sum(o.platformRevenue) from CustomerOrder o where o.shop.id = :shopId and o.paymentStatus = :paymentStatus and o.status = :orderStatus")
	BigDecimal sumPlatformRevenueByShop(@Param("shopId") Long shopId,
			@Param("paymentStatus") PaymentStatus paymentStatus, @Param("orderStatus") OrderStatus orderStatus);

	long countByShopIdAndStatus(Long shopId, OrderStatus status);
}
