package vn.iotstar.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.repository.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final ProductOptionService productOptionService;
    private final ProductPricingService productPricingService;

    public Optional<Cart> findExisting(User user) {
        return cartRepository.findByUserId(user.getId());
    }

    @Transactional
    public Cart get(User user) {
        var owner = userRepository.findByIdForUpdate(user.getId()).orElseThrow();
        return cartRepository.findByUserId(owner.getId()).orElseGet(() -> {
            var cart = new Cart(); cart.setUser(owner); return cartRepository.saveAndFlush(cart);
        });
    }

    @Transactional
    public void add(User user, Long productId, int quantity, Long sizeId, SugarLevel sugar,
            IceLevel ice, List<Long> toppingIds) {
        if (quantity <= 0) throw new IllegalArgumentException("Số lượng phải lớn hơn 0.");
        var cart = get(user);
        var product = productRepository.findByIdForUpdate(productId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm."));
        if (!product.isAvailable()) throw new IllegalArgumentException("Sản phẩm hiện không khả dụng.");
        checkStock(cart, product, null, quantity);
        var quote = quote(product, sizeId, sugar, ice, toppingIds, true);
        var item = cart.getItems().stream().filter(line -> line.getProduct().getId().equals(productId)
            && line.getOptionKey().equals(quote.optionKey())).findFirst().orElseGet(() -> {
                var created = new CartItem(); created.setCart(cart); created.setProduct(product);
                cart.getItems().add(created); return created;
            });
        item.setQuantity(item.getQuantity() + quantity);
        apply(item, quote);
        cartItemRepository.saveAndFlush(item);
        cart.setUpdatedAt(LocalDateTime.now());
    }

    @Transactional
    public void update(User user, Long itemId, int quantity) {
        if (quantity < 0) throw new IllegalArgumentException("Số lượng không được âm.");
        var cart = get(user);
        var item = ownedItem(cart, itemId);
        if (quantity == 0) { cart.getItems().remove(item); }
        else {
            var product = productRepository.findByIdForUpdate(item.getProduct().getId()).orElseThrow();
            if (!product.isAvailable()) throw new IllegalArgumentException("Sản phẩm hiện không khả dụng.");
            checkStock(cart, product, itemId, quantity);
            apply(item, quote(product, item.getSizeId(), item.getSugarLevel(), item.getIceLevel(),
                parseToppingIds(item.getToppingIds()), false));
            item.setQuantity(quantity);
        }
        cart.setUpdatedAt(LocalDateTime.now());
    }

    @Transactional
    public void remove(User user, Long itemId) {
        var cart = get(user); cart.getItems().remove(ownedItem(cart, itemId));
        cart.setUpdatedAt(LocalDateTime.now());
    }

    @Transactional
    public CartView view(User user) {
        return reprice(get(user));
    }

    @Transactional
    public CartSelection prepareSelection(User user, List<Long> itemIds) {
        var view = reprice(get(user));
        if (itemIds == null || itemIds.isEmpty() || itemIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("Vui lòng chọn ít nhất một sản phẩm hợp lệ.");
        var ids = new LinkedHashSet<>(itemIds);
        var selected = view.cart().getItems().stream().filter(item -> ids.contains(item.getId())).toList();
        if (selected.size() != ids.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Sản phẩm đã chọn không thuộc giỏ hàng của bạn.");
        if (ids.stream().anyMatch(view.invalidItemIds()::contains))
            throw new IllegalArgumentException("Một sản phẩm đã chọn không còn khả dụng. Vui lòng cập nhật giỏ hàng.");
        return new CartSelection(view.cart(), selected,
            selected.stream().map(CartItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private CartItem ownedItem(Cart cart, Long id) {
        return cart.getItems().stream().filter(item -> item.getId().equals(id)).findFirst().orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm trong giỏ của bạn."));
    }

    private void checkStock(Cart cart, Product product, Long replacedItemId, int requested) {
        long otherQuantity = cart.getItems().stream()
            .filter(item -> item.getProduct().getId().equals(product.getId()) && !item.getId().equals(replacedItemId))
            .mapToLong(CartItem::getQuantity).sum();
        if (otherQuantity + requested > product.getStock())
            throw new IllegalArgumentException("Tổng số lượng của sản phẩm trong giỏ vượt quá tồn kho.");
    }

    private CartView reprice(Cart cart) {
        var invalid = new LinkedHashSet<Long>();
        var warnings = new LinkedHashSet<String>();
        boolean changed = false;
        for (var item : cart.getItems()) {
            var product = item.getProduct();
            if (!product.isAvailable()) {
                invalid.add(item.getId()); warnings.add(product.getName() + " hiện không khả dụng."); continue;
            }
            long quantity = cart.getItems().stream().filter(line -> line.getProduct().getId().equals(product.getId()))
                .mapToLong(CartItem::getQuantity).sum();
            if (quantity > product.getStock()) {
                invalid.add(item.getId()); warnings.add(product.getName() + ": tổng số lượng vượt tồn kho, vui lòng giảm số lượng.");
            }
            try {
                var quote = quote(product, item.getSizeId(), item.getSugarLevel(), item.getIceLevel(),
                    parseToppingIds(item.getToppingIds()), false);
                changed |= item.getUnitPrice() == null || item.getUnitPrice().compareTo(quote.unitPrice()) != 0;
                apply(item, quote);
            } catch (IllegalArgumentException ex) {
                invalid.add(item.getId()); warnings.add(product.getName() + ": " + ex.getMessage());
            }
        }
        if (changed) cart.setUpdatedAt(LocalDateTime.now());
        return new CartView(cart, changed, List.copyOf(warnings), Set.copyOf(invalid));
    }

    private OptionQuote quote(Product product, Long sizeId, SugarLevel sugar, IceLevel ice,
            List<Long> toppingIds, boolean allowDefault) {
        if (product.getPrice() == null || product.getPrice().signum() < 0)
            throw new IllegalArgumentException("Giá sản phẩm không hợp lệ.");
        if (toppingIds != null && toppingIds.stream().anyMatch(id -> id == null || id <= 0))
            throw new IllegalArgumentException("Mã topping không hợp lệ.");
        var ids = new TreeSet<Long>(toppingIds == null ? List.of() : toppingIds);
        var toppingProduct = "topping".equals(product.getCategory().getSlug());
        ProductSize selectedSize = null;
        if (toppingProduct) {
            if (sizeId != null || sugar != null || ice != null || !ids.isEmpty())
                throw new IllegalArgumentException("Topping bán riêng không có tùy chọn đồ uống.");
        } else {
            var sizes = productOptionService.availableSizes(product);
            if (sizeId == null && allowDefault && !sizes.isEmpty()) selectedSize = sizes.getFirst();
            else selectedSize = sizes.stream().filter(size -> size.getId().equals(sizeId)).findFirst().orElseThrow(() ->
                new IllegalArgumentException("Size đã chọn không còn khả dụng."));
            if (selectedSize.getExtraPrice() == null || selectedSize.getExtraPrice().signum() < 0)
                throw new IllegalArgumentException("Phụ thu size không hợp lệ.");
            if (sugar == null) sugar = SugarLevel.PERCENT_100;
            if (ice == null) ice = IceLevel.NORMAL;
        }
        var allowed = productOptionService.availableToppings(product);
        var selectedToppings = new ArrayList<Topping>();
        for (Long id : ids) {
            var topping = allowed.stream().map(ProductTopping::getTopping).filter(t -> t.getId().equals(id))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Topping đã chọn không còn áp dụng."));
            if (topping.getPrice() == null || topping.getPrice().signum() < 0)
                throw new IllegalArgumentException("Giá topping không hợp lệ.");
            selectedToppings.add(topping);
        }
        var selectedIds = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        var names = selectedToppings.stream().map(Topping::getName).collect(Collectors.joining(", "));
        Long selectedSizeId = selectedSize == null ? null : selectedSize.getId();
        String key = toppingProduct ? "BASE" : selectedSizeId + "|" + sugar.name() + "|" + ice.name() + "|" + selectedIds;
        if (key.length() > 500 || names.length() > 1000) throw new IllegalArgumentException("Quá nhiều tùy chọn topping.");
        // Validation above keeps unavailable options out of the shared pricing service.
        // This also allows the cart to show a removable invalid line without rolling back other repricing.
        var basePrice = productPricingService.price(product).salePrice();
        var unitPrice = productPricingService.unitPrice(product, selectedSizeId, new ArrayList<>(ids));
        if (unitPrice.signum() < 0 || unitPrice.compareTo(new BigDecimal("999999999999.99")) > 0)
            throw new IllegalArgumentException("Giá sau tùy chọn vượt giới hạn cho phép.");
        return new OptionQuote(key, selectedSizeId, selectedSize == null ? null : selectedSize.getCode(), sugar, ice,
            selectedIds, names, basePrice, unitPrice.subtract(basePrice), unitPrice);
    }

    private List<Long> parseToppingIds(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return Arrays.stream(value.split(",")).map(Long::valueOf).toList(); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Topping đã lưu không hợp lệ."); }
    }

    private void apply(CartItem item, OptionQuote quote) {
        item.setOptionKey(quote.optionKey()); item.setSizeId(quote.sizeId()); item.setSize(quote.size());
        item.setSugarLevel(quote.sugar()); item.setIceLevel(quote.ice()); item.setToppingIds(quote.toppingIds());
        item.setToppings(quote.toppings().isEmpty() ? null : quote.toppings());
        item.setBasePrice(quote.basePrice()); item.setOptionPrice(quote.optionPrice()); item.setUnitPrice(quote.unitPrice());
    }

    private record OptionQuote(String optionKey, Long sizeId, String size, SugarLevel sugar, IceLevel ice,
        String toppingIds, String toppings, BigDecimal basePrice, BigDecimal optionPrice, BigDecimal unitPrice) {}
    public record CartView(Cart cart, boolean pricesChanged, List<String> warnings, Set<Long> invalidItemIds) {}
    public record CartSelection(Cart cart, List<CartItem> items, BigDecimal subtotal) {}
}
