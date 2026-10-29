package vn.iotstar.service;

import vn.iotstar.util.VietnamTime;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.Cart;
import vn.iotstar.entity.CartItem;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.ProductSize;
import vn.iotstar.entity.ProductTopping;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.repository.CartItemRepository;
import vn.iotstar.repository.CartRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ProductSizeRepository;
import vn.iotstar.repository.ProductToppingRepository;

@Service
@RequiredArgsConstructor
public class CartService {
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final ProductSizeRepository productSizeRepository;
    private final ProductToppingRepository productToppingRepository;

    @Transactional(readOnly = true)
    public Optional<Cart> findExisting(User user) {
        return cartRepository.findByUserId(user.getId());
    }

    @Transactional
    public Cart get(User user) {
        return cartRepository.findByUserId(user.getId()).orElseGet(() -> {
            Cart cart = new Cart();
            cart.setUser(user);
            return cartRepository.save(cart);
        });
    }

    @Transactional
    public void add(User user, Long productId, int quantity) {
        add(user, productId, quantity, null, null, null, List.of());
    }

    @Transactional
    public void add(User user, Long productId, int quantity, Long sizeId, SugarLevel sugarLevel,
                    IceLevel iceLevel, List<Long> toppingIds) {
        addInternal(user, productId, quantity, sizeId, sugarLevel, iceLevel, toppingIds, false);
    }

    @Transactional
    public Long addForImmediateCheckout(User user, Long productId, int quantity, Long sizeId,
                                        SugarLevel sugarLevel, IceLevel iceLevel, List<Long> toppingIds) {
        return addInternal(user, productId, quantity, sizeId, sugarLevel, iceLevel, toppingIds, true).getId();
    }

    private CartItem addInternal(User user, Long productId, int quantity, Long sizeId,
                                 SugarLevel sugarLevel, IceLevel iceLevel, List<Long> toppingIds,
                                 boolean separateLine) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm."));
        if (!product.isAvailable()) throw new IllegalArgumentException("Sản phẩm hiện không khả dụng.");
        if (quantity < 1) throw new IllegalArgumentException("Số lượng phải từ 1 trở lên.");
        Cart cart = get(user);

        List<ProductSize> sizes = productSizeRepository.findByProductIdAndActiveTrueOrderByDisplayOrderAscIdAsc(productId);
        ProductSize size = null;
        if (!sizes.isEmpty()) {
            size = sizeId == null ? sizes.getFirst() : productSizeRepository.findByIdAndProductIdAndActiveTrue(sizeId, productId)
                .orElseThrow(() -> new IllegalArgumentException("Kích thước đã chọn không hợp lệ."));
            if (sugarLevel == null) sugarLevel = SugarLevel.PERCENT_100;
            if (iceLevel == null) iceLevel = IceLevel.NORMAL;
        }

        List<ProductTopping> allowedToppings = productToppingRepository
            .findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(productId);
        var requestedIds = new LinkedHashSet<>(toppingIds == null ? List.<Long>of() : toppingIds);
        List<ProductTopping> selectedToppings = new ArrayList<>();
        for (Long toppingId : requestedIds) {
            ProductTopping match = allowedToppings.stream()
                .filter(link -> link.getTopping().getId().equals(toppingId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Topping đã chọn không áp dụng cho sản phẩm này."));
            selectedToppings.add(match);
        }
        selectedToppings.sort(java.util.Comparator.comparing(link -> link.getTopping().getId()));

        String selectedIds = selectedToppings.stream().map(link -> link.getTopping().getId().toString())
            .collect(java.util.stream.Collectors.joining(","));
        String optionKey = sizes.isEmpty() ? (selectedIds.isBlank() ? "BASE" : "BASE|" + selectedIds)
            : size.getId() + "|" + sugarLevel.name() + "|" + iceLevel.name() + "|" + selectedIds;
        if (separateLine) optionKey = "BUY_NOW|" + UUID.randomUUID() + "|" + optionKey;
        SugarLevel finalSugarLevel = sugarLevel;
        IceLevel finalIceLevel = iceLevel;
        CartItem item = (separateLine ? Optional.<CartItem>empty()
            : cartItemRepository.findByCartIdAndProductIdAndOptionKey(cart.getId(), productId, optionKey)).orElseGet(() -> {
            CartItem created = new CartItem();
            created.setCart(cart);
            created.setProduct(product);
            cart.getItems().add(created);
            return created;
        });
        int total = item.getQuantity() + quantity;
        if (total > product.getStock()) throw new IllegalArgumentException("Số lượng vượt quá tồn kho.");
        item.setQuantity(total);
        BigDecimal sizePrice = size == null ? BigDecimal.ZERO : size.getExtraPrice();
        BigDecimal toppingPrice = selectedToppings.stream().map(link -> link.getTopping().getPrice())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        item.setOptionKey(optionKey);
        item.setSize(size == null ? null : size.getCode());
        item.setSugarLevel(sizes.isEmpty() ? null : finalSugarLevel);
        item.setIceLevel(sizes.isEmpty() ? null : finalIceLevel);
        item.setToppingIds(selectedIds.isBlank() ? null : selectedIds);
        item.setToppings(selectedToppings.isEmpty() ? null : selectedToppings.stream()
            .map(link -> link.getTopping().getName()).collect(java.util.stream.Collectors.joining(", ")));
        item.setBasePrice(product.getPrice());
        item.setOptionPrice(sizePrice.add(toppingPrice));
        item.setUnitPrice(product.getPrice().add(item.getOptionPrice()));
        CartItem saved = cartItemRepository.save(item);
        cart.setUpdatedAt(VietnamTime.now());
        return saved;
    }

    @Transactional
    public void update(User user, Long itemId, int quantity) {
        CartItem item = cartItemRepository.findByIdAndCart_User_Id(itemId, user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm trong giỏ."));
        if (quantity <= 0) {
            removeItem(item);
            return;
        }
        if (quantity > item.getProduct().getStock()) throw new IllegalArgumentException("Số lượng vượt quá tồn kho.");
        item.setQuantity(quantity);
        item.getCart().setUpdatedAt(VietnamTime.now());
    }

    @Transactional
    public void remove(User user, Long itemId) {
        CartItem item = cartItemRepository.findByIdAndCart_User_Id(itemId, user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm trong giỏ."));
        removeItem(item);
    }

    private void removeItem(CartItem item) {
        Cart cart = item.getCart();
        cart.getItems().remove(item);
        cart.setUpdatedAt(VietnamTime.now());
    }

    @Transactional
    public void clear(Cart cart) {
        cart.getItems().clear();
        cart.setUpdatedAt(VietnamTime.now());
    }

    @Transactional
    public void removeItems(Cart cart, List<CartItem> items) {
        cart.getItems().removeAll(items);
        cart.setUpdatedAt(VietnamTime.now());
    }

    @Transactional
    public CartSelection prepareSelection(User user, List<Long> itemIds, boolean selectAllWhenEmpty) {
        Cart cart = get(user);
        List<CartItem> items = selectItems(cart, itemIds, selectAllWhenEmpty);
        CartRepriceResult repricing = reprice(items);
        validateStock(items);
        BigDecimal subtotal = items.stream().map(CartItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartSelection(cart, List.copyOf(items), subtotal, repricing);
    }

    public List<CartItem> selectItems(Cart cart, List<Long> itemIds, boolean selectAllWhenEmpty) {
        LinkedHashSet<Long> requested = itemIds == null ? new LinkedHashSet<>() : itemIds.stream()
            .filter(java.util.Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (requested.isEmpty() && selectAllWhenEmpty) {
            requested.addAll(cart.getItems().stream().map(CartItem::getId).toList());
        }
        if (requested.isEmpty()) throw new IllegalArgumentException("Vui lòng chọn ít nhất một sản phẩm để thanh toán.");
        List<CartItem> selected = cart.getItems().stream().filter(item -> requested.contains(item.getId())).toList();
        if (selected.size() != requested.size()) {
            throw new IllegalArgumentException("Có sản phẩm đã chọn không còn nằm trong giỏ hàng của bạn.");
        }
        return selected;
    }

    public void validateStock(List<CartItem> items) {
        Map<Long, Integer> requested = new LinkedHashMap<>();
        Map<Long, Product> products = new LinkedHashMap<>();
        for (CartItem item : items) {
            Product product = item.getProduct();
            requested.merge(product.getId(), item.getQuantity(), Integer::sum);
            products.putIfAbsent(product.getId(), product);
        }
        for (Map.Entry<Long, Integer> entry : requested.entrySet()) {
            Product product = products.get(entry.getKey());
            if (entry.getValue() > product.getStock()) {
                throw new IllegalArgumentException("Sản phẩm " + product.getName() + " chỉ còn "
                    + product.getStock() + " ly, bạn đã chọn " + entry.getValue()
                    + " ly. Vui lòng điều chỉnh giỏ hàng.");
            }
        }
    }

    @Transactional
    public CartRepriceResult reprice(User user) {
        return reprice(get(user));
    }

    @Transactional
    public CartRepriceResult reprice(Cart cart) {
        CartRepriceResult result = reprice(cart.getItems());
        if (result.pricesChanged()) cart.setUpdatedAt(VietnamTime.now());
        return result;
    }

    @Transactional
    public CartRepriceResult reprice(List<CartItem> items) {
        LinkedHashSet<String> changedProducts = new LinkedHashSet<>();
        for (CartItem item : items) {
            Product product = item.getProduct();
            if (!product.isAvailable()) {
                throw new IllegalArgumentException("Sản phẩm " + product.getName()
                    + " hiện không khả dụng. Vui lòng cập nhật giỏ hàng.");
            }
            BigDecimal sizePrice = BigDecimal.ZERO;
            if (item.getSize() != null && !item.getSize().isBlank()) {
                ProductSize currentSize = productSizeRepository
                    .findByProductIdAndActiveTrueOrderByDisplayOrderAscIdAsc(product.getId()).stream()
                    .filter(size -> size.getCode().equalsIgnoreCase(item.getSize()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                        "Size " + item.getSize() + " của " + product.getName() + " không còn khả dụng. Vui lòng cập nhật giỏ hàng."));
                sizePrice = currentSize.getExtraPrice();
            }

            Map<Long, ProductTopping> allowedToppings = productToppingRepository
                .findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(product.getId()).stream()
                .collect(Collectors.toMap(link -> link.getTopping().getId(), link -> link,
                    (first, ignored) -> first, LinkedHashMap::new));
            List<Long> selectedIds = parseToppingIds(item.getToppingIds(), product.getName());
            List<ProductTopping> selectedToppings = selectedIds.stream().map(id -> {
                ProductTopping link = allowedToppings.get(id);
                if (link == null) {
                    throw new IllegalArgumentException("Một topping của " + product.getName()
                        + " không còn khả dụng. Vui lòng cập nhật giỏ hàng.");
                }
                return link;
            }).toList();
            BigDecimal toppingPrice = selectedToppings.stream().map(link -> link.getTopping().getPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal basePrice = product.getPrice();
            BigDecimal optionPrice = sizePrice.add(toppingPrice);
            BigDecimal unitPrice = basePrice.add(optionPrice);
            if (!sameMoney(item.getBasePrice(), basePrice)
                || !sameMoney(item.getOptionPrice(), optionPrice)
                || !sameMoney(item.getUnitPrice(), unitPrice)) {
                changedProducts.add(product.getName());
            }
            item.setBasePrice(basePrice);
            item.setOptionPrice(optionPrice);
            item.setUnitPrice(unitPrice);
            item.setToppings(selectedToppings.isEmpty() ? null : selectedToppings.stream()
                .map(link -> link.getTopping().getName()).collect(Collectors.joining(", ")));
        }
        if (!changedProducts.isEmpty() && !items.isEmpty()) {
            items.getFirst().getCart().setUpdatedAt(VietnamTime.now());
        }
        return new CartRepriceResult(!changedProducts.isEmpty(), List.copyOf(changedProducts));
    }

    private List<Long> parseToppingIds(String toppingIds, String productName) {
        if (toppingIds == null || toppingIds.isBlank()) return List.of();
        try {
            return java.util.Arrays.stream(toppingIds.split(","))
                .filter(value -> !value.isBlank()).map(String::trim).map(Long::valueOf).toList();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Topping đã lưu của " + productName
                + " không hợp lệ. Vui lòng cập nhật giỏ hàng.");
        }
    }

    private boolean sameMoney(BigDecimal first, BigDecimal second) {
        return first != null && second != null && first.compareTo(second) == 0;
    }

    public record CartRepriceResult(boolean pricesChanged, List<String> changedProducts) {}
    public record CartSelection(Cart cart, List<CartItem> items, BigDecimal subtotal,
                                CartRepriceResult repricing) {}
}
