package vn.iotstar.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.OrderItem;
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {}
