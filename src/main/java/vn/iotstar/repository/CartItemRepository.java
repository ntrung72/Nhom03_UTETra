package vn.iotstar.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.CartItem;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    @Query("select distinct item.product.id from CartItem item where item.cart.user.id = :userId order by item.product.id")
    List<Long> findProductIdsByUserId(@Param("userId") Long userId);

    @Query("select distinct item.product.id from CartItem item where item.cart.user.id = :userId and item.id in :itemIds order by item.product.id")
    List<Long> findProductIdsByUserIdAndItemIds(@Param("userId") Long userId,
                                                 @Param("itemIds") Collection<Long> itemIds);

    @Query("select count(item) from CartItem item where item.cart.user.id = :userId and item.id in :itemIds")
    long countOwnedItems(@Param("userId") Long userId, @Param("itemIds") Collection<Long> itemIds);

    Optional<CartItem> findByCartIdAndProductId(Long cartId, Long productId);
    Optional<CartItem> findByCartIdAndProductIdAndOptionKey(Long cartId, Long productId, String optionKey);
    Optional<CartItem> findByIdAndCart_User_Id(Long id, Long userId);
}
