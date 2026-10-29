package vn.iotstar.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.iotstar.entity.CartItem;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.entity.OrderItem;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.ProductSize;
import vn.iotstar.entity.ProductTopping;
import vn.iotstar.entity.User;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ProductSizeRepository;
import vn.iotstar.repository.ProductToppingRepository;

@Service
@RequiredArgsConstructor
public class ReorderService {
    private final PlatformTransactionManager transactionManager;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final ProductSizeRepository productSizeRepository;
    private final ProductToppingRepository productToppingRepository;
    private final CartService cartService;

    public ReorderResult reorder(User user, Long orderId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<PreviousItem> previousItems = transaction.execute(status -> {
            CustomerOrder order = orderRepository.findDetailedById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."));
            if (!order.getUser().getId().equals(user.getId())) {
                throw new IllegalArgumentException("Bạn không có quyền mua lại đơn hàng này.");
            }
            return order.getItems().stream().map(this::snapshot).toList();
        });

        int added = 0;
        List<String> skipped = new ArrayList<>();
        for (PreviousItem item : Objects.requireNonNull(previousItems)) {
            try {
                transaction.executeWithoutResult(status -> addCurrentItem(user, item));
                added++;
            } catch (IllegalArgumentException ex) {
                skipped.add(item.productName() + ": " + ex.getMessage());
            }
        }
        return new ReorderResult(added, List.copyOf(skipped));
    }

    private PreviousItem snapshot(OrderItem item) {
        return new PreviousItem(item.getProduct().getId(), item.getProductName(), item.getQuantity(),
            item.getSize(), item.getSugarLevel(), item.getIceLevel(), item.getToppingIds(), item.getToppings());
    }

    private void addCurrentItem(User user, PreviousItem previous) {
        Product product = productRepository.findById(previous.productId())
            .orElseThrow(() -> new IllegalArgumentException("Sản phẩm đã bị xóa."));
        if (!product.isAvailable()) {
            throw new IllegalArgumentException("Sản phẩm đã ngừng bán hoặc hết hàng.");
        }
        if (previous.quantity() <= 0) {
            throw new IllegalArgumentException("Số lượng trong đơn cũ không hợp lệ.");
        }
        int alreadyInCart = cartService.get(user).getItems().stream()
            .filter(line -> line.getProduct().getId().equals(product.getId()))
            .mapToInt(CartItem::getQuantity).sum();
        if ((long) alreadyInCart + previous.quantity() > product.getStock()) {
            throw new IllegalArgumentException("Không đủ tồn kho cho số lượng đã mua; hiện còn "
                + product.getStock() + " sản phẩm.");
        }

        List<ProductSize> sizes = productSizeRepository
            .findByProductIdAndActiveTrueOrderByDisplayOrderAscIdAsc(product.getId());
        Long sizeId = null;
        if (previous.size() == null) {
            if (!sizes.isEmpty()) throw new IllegalArgumentException("Size cũ không còn phù hợp với sản phẩm hiện tại.");
        } else {
            sizeId = sizes.stream().filter(size -> size.getCode().equalsIgnoreCase(previous.size()))
                .map(ProductSize::getId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Size " + previous.size() + " không còn bán."));
            if (previous.sugarLevel() == null || previous.iceLevel() == null) {
                throw new IllegalArgumentException("Cấu hình đường/đá cũ không còn đầy đủ.");
            }
        }

        List<ProductTopping> availableToppings = productToppingRepository
            .findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(product.getId());
        List<Long> toppingIds = resolveToppings(previous, availableToppings);
        cartService.add(user, product.getId(), previous.quantity(), sizeId,
            previous.sugarLevel(), previous.iceLevel(), toppingIds);
    }

    private List<Long> resolveToppings(PreviousItem previous, List<ProductTopping> available) {
        if (previous.toppingIds() != null && !previous.toppingIds().isBlank()) {
            List<Long> ids;
            try {
                ids = Arrays.stream(previous.toppingIds().split(","))
                    .map(String::trim).map(Long::parseLong).toList();
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Dữ liệu topping của đơn cũ không hợp lệ.");
            }
            if (new LinkedHashSet<>(ids).size() != ids.size()) {
                throw new IllegalArgumentException("Dữ liệu topping của đơn cũ không hợp lệ.");
            }
            for (Long id : ids) {
                if (available.stream().noneMatch(link -> link.getTopping().getId().equals(id))) {
                    throw new IllegalArgumentException("Một topping đã ngừng bán hoặc không còn áp dụng.");
                }
            }
            return ids;
        }
        if (previous.toppings() == null || previous.toppings().isBlank()) return List.of();
        List<Long> ids = new ArrayList<>();
        for (String oldName : previous.toppings().split(",")) {
            String name = oldName.trim();
            Long id = available.stream().filter(link -> link.getTopping().getName().equalsIgnoreCase(name))
                .map(link -> link.getTopping().getId()).findFirst().orElse(null);
            if (id == null && "Trân châu đen".equalsIgnoreCase(name)) {
                id = available.stream().filter(link -> "Trân châu đường đen".equalsIgnoreCase(link.getTopping().getName()))
                    .map(link -> link.getTopping().getId()).findFirst().orElse(null);
            }
            if (id == null) throw new IllegalArgumentException("Topping '" + name + "' đã ngừng bán hoặc đổi tên.");
            ids.add(id);
        }
        return ids;
    }

    public record ReorderResult(int addedLines, List<String> skipped) {}

    private record PreviousItem(Long productId, String productName, int quantity, String size,
                                SugarLevel sugarLevel, IceLevel iceLevel, String toppingIds, String toppings) {}
}
