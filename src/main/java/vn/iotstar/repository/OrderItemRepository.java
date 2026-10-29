package vn.iotstar.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    @Query("select item from OrderItem item where item.order.id in :orderIds order by item.order.id, item.id")
    List<OrderItem> findItemsForOrders(@Param("orderIds") List<Long> orderIds);

    Optional<OrderItem> findByIdAndOrder_User_Id(Long id, Long userId);
    Optional<OrderItem> findFirstByOrder_User_IdAndProduct_IdAndOrder_StatusOrderByIdDesc(
        Long userId, Long productId, OrderStatus status);
}
