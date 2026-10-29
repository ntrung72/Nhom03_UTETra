package vn.iotstar.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.iotstar.entity.Cart;
import vn.iotstar.entity.CartItem;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.Favorite;
import vn.iotstar.service.CartService;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.EngagementService;
import vn.iotstar.service.OrderService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class CustomerApiController {
    private final CurrentUserService currentUserService;
    private final OrderService orderService;
    private final EngagementService engagementService;
    private final CartService cartService;

    @Operation(summary = "Đơn hàng của tôi", description = "Phân trang 15 đơn; có thể lọc bằng status, ví dụ NEW hoặc DELIVERED.")
    @GetMapping("/orders")
    Page<OrderSummary> orders(@RequestParam(required = false) OrderStatus status,
                              @RequestParam(defaultValue = "0") int page) {
        return orderService.forUser(currentUserService.require(), status, page).map(OrderSummary::from);
    }

    @Operation(summary = "Sản phẩm yêu thích của tôi", description = "Phân trang 20 sản phẩm.")
    @GetMapping("/favorites")
    Page<FavoriteSummary> favorites(@RequestParam(defaultValue = "0") int page) {
        return engagementService.favorites(currentUserService.require(), page).map(FavoriteSummary::from);
    }

    @Operation(summary = "Giỏ hàng của tôi", description = "Chỉ đọc; trả giỏ trống nếu tài khoản chưa có giỏ hàng.")
    @GetMapping("/cart")
    CartSummary cart() {
        return cartService.findExisting(currentUserService.require())
            .map(CartSummary::from).orElseGet(() -> new CartSummary(List.of(), 0, BigDecimal.ZERO));
    }

    public record OrderSummary(Long id, String code, String shop, OrderStatus status, String statusLabel,
                               String paymentMethod, String paymentStatus,
                               BigDecimal totalAmount, LocalDateTime createdAt) {
        static OrderSummary from(CustomerOrder order) {
            return new OrderSummary(order.getId(), order.getCode(), order.getShop().getName(),
                order.getStatus(), order.getStatus().getLabel(), order.getPaymentMethod().name(),
                order.getPaymentStatus().name(), order.getTotalAmount(), order.getCreatedAt());
        }
    }

    public record FavoriteSummary(Long productId, String name, String imageUrl, BigDecimal price,
                                  String shop, LocalDateTime addedAt) {
        static FavoriteSummary from(Favorite favorite) {
            var product = favorite.getProduct();
            return new FavoriteSummary(product.getId(), product.getName(), product.getImageUrl(),
                product.getPrice(), product.getShop().getName(), favorite.getCreatedAt());
        }
    }

    public record CartSummary(List<CartLine> items, int totalQuantity, BigDecimal subtotal) {
        static CartSummary from(Cart cart) {
            return new CartSummary(cart.getItems().stream().map(CartLine::from).toList(),
                cart.getTotalQuantity(), cart.getSubtotal());
        }
    }

    public record CartLine(Long id, Long productId, String productName, String imageUrl, String shop,
                           int quantity, String size, String sugarLevel, String iceLevel,
                           String sugarLevelLabel, String iceLevelLabel, String toppings,
                           BigDecimal unitPrice, BigDecimal lineTotal) {
        static CartLine from(CartItem item) {
            var product = item.getProduct();
            return new CartLine(item.getId(), product.getId(), product.getName(), product.getImageUrl(),
                product.getShop().getName(), item.getQuantity(), item.getSize(),
                item.getSugarLevel() == null ? null : item.getSugarLevel().name(),
                item.getIceLevel() == null ? null : item.getIceLevel().name(),
                item.getSugarLevel() == null ? null : item.getSugarLevel().getLabel(),
                item.getIceLevel() == null ? null : item.getIceLevel().getLabel(), item.getToppings(),
                item.getEffectiveUnitPrice(), item.getLineTotal());
        }
    }
}
