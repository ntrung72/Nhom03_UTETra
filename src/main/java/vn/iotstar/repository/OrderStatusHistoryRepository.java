package vn.iotstar.repository;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.OrderStatusHistory;
public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {
    List<OrderStatusHistory> findByOrderIdOrderByChangedAtAscIdAsc(Long orderId);
}
