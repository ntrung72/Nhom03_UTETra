package vn.iotstar.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.iotstar.entity.CartItem;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    Optional<CartItem> findByIdAndCart_User_Id(Long id, Long userId);
    Optional<CartItem> findByCartIdAndProductIdAndOptionKey(Long cartId, Long productId, String optionKey);
}
