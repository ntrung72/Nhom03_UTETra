package vn.iotstar.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.CartItem;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.ProductSize;
import vn.iotstar.entity.ProductTopping;
import vn.iotstar.entity.SavedRecipe;
import vn.iotstar.entity.User;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ProductSizeRepository;
import vn.iotstar.repository.ProductToppingRepository;
import vn.iotstar.repository.SavedRecipeRepository;

@Service
@RequiredArgsConstructor
public class SavedRecipeService {
    private final SavedRecipeRepository recipeRepository;
    private final ProductRepository productRepository;
    private final ProductSizeRepository sizeRepository;
    private final ProductToppingRepository toppingRepository;
    private final CartService cartService;

    @Transactional
    public SavedRecipe save(User user, Long productId, String name, Long sizeId,
                            SugarLevel sugarLevel, IceLevel iceLevel, List<Long> toppingIds) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm."));
        if (!product.isAvailable()) throw new IllegalArgumentException("Sản phẩm đã ngừng bán hoặc hết hàng.");
        if (sizeId == null) throw new IllegalArgumentException("Vui lòng chọn size đang bán cho công thức.");
        ProductSize size = sizeRepository.findByIdAndProductIdAndActiveTrue(sizeId, productId)
            .orElseThrow(() -> new IllegalArgumentException("Vui lòng chọn size đang bán cho công thức."));
        if (sugarLevel == null || iceLevel == null) {
            throw new IllegalArgumentException("Vui lòng chọn mức đường và đá cho công thức.");
        }
        List<ProductTopping> selected = validateToppings(productId, toppingIds);
        String safeName = name == null || name.isBlank()
            ? product.getName() + " · Size " + size.getCode() : name.strip();
        if (safeName.length() > 80 && (name == null || name.isBlank())) safeName = safeName.substring(0, 80);
        validateName(safeName);

        SavedRecipe recipe = new SavedRecipe();
        recipe.setUser(user);
        recipe.setProduct(product);
        recipe.setName(safeName);
        recipe.setSizeCode(size.getCode());
        recipe.setSugarLevel(sugarLevel);
        recipe.setIceLevel(iceLevel);
        recipe.setToppingIds(ids(selected));
        recipe.setToppingNames(names(selected));
        return recipeRepository.save(recipe);
    }

    @Transactional(readOnly = true)
    public Page<RecipeDisplay> list(User user, int page) {
        return recipeRepository.findByUserIdOrderByUpdatedAtDescIdDesc(user.getId(),
            PageRequest.of(Math.max(0, page), 12)).map(recipe -> {
                try {
                    CurrentOptions options = currentOptions(recipe);
                    return new RecipeDisplay(recipe, null, options.unitPrice(), options.toppingNames());
                } catch (IllegalArgumentException ex) {
                    return new RecipeDisplay(recipe, ex.getMessage(), null, recipe.getToppingNames());
                }
            });
    }

    @Transactional(readOnly = true)
    public List<RecipePreset> presetsForProduct(User user, Long productId) {
        return recipeRepository.findByUserIdAndProductIdOrderByUpdatedAtDescIdDesc(user.getId(), productId)
            .stream().map(recipe -> {
                try {
                    CurrentOptions options = currentOptions(recipe);
                    return new RecipePreset(recipe.getName(), options.sizeId(),
                        recipe.getSizeCode(), recipe.getSugarLevel().name(), recipe.getIceLevel().name(),
                        recipe.getSugarLevel().getLabel(), recipe.getIceLevel().getLabel(),
                        recipe.getToppingIds(), options.toppingNames());
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            }).filter(java.util.Objects::nonNull).limit(6).toList();
    }

    @Transactional
    public void addToCart(User user, Long recipeId, int quantity) {
        SavedRecipe recipe = owned(user, recipeId);
        if (quantity < 1) throw new IllegalArgumentException("Số lượng phải từ 1 trở lên.");
        CurrentOptions options = currentOptions(recipe);
        Product product = recipe.getProduct();
        int alreadyInCart = cartService.get(user).getItems().stream()
            .filter(item -> item.getProduct().getId().equals(product.getId()))
            .mapToInt(CartItem::getQuantity).sum();
        if ((long) alreadyInCart + quantity > product.getStock()) {
            throw new IllegalArgumentException("Không đủ tồn kho cho " + product.getName()
                + "; hiện còn " + product.getStock() + " ly.");
        }
        cartService.add(user, product.getId(), quantity, options.sizeId(), recipe.getSugarLevel(),
            recipe.getIceLevel(), options.toppingIds());
    }

    @Transactional
    public void rename(User user, Long recipeId, String name) {
        SavedRecipe recipe = owned(user, recipeId);
        String safeName = name == null ? "" : name.strip();
        validateName(safeName);
        recipe.setName(safeName);
    }

    @Transactional
    public void delete(User user, Long recipeId) {
        recipeRepository.delete(owned(user, recipeId));
    }

    private SavedRecipe owned(User user, Long recipeId) {
        return recipeRepository.findByIdAndUserId(recipeId, user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy công thức của bạn."));
    }

    private CurrentOptions currentOptions(SavedRecipe recipe) {
        Product product = recipe.getProduct();
        if (!product.isAvailable()) throw new IllegalArgumentException("Sản phẩm đã ngừng bán hoặc hết hàng.");
        ProductSize size = sizeRepository.findByProductIdAndCodeIgnoreCase(product.getId(), recipe.getSizeCode())
            .filter(ProductSize::isActive)
            .orElseThrow(() -> new IllegalArgumentException("Size " + recipe.getSizeCode() + " không còn bán."));
        if (recipe.getSugarLevel() == null || recipe.getIceLevel() == null) {
            throw new IllegalArgumentException("Cấu hình đường/đá không còn hợp lệ.");
        }
        List<ProductTopping> toppings = validateToppings(product.getId(), parseIds(recipe.getToppingIds()));
        BigDecimal extras = toppings.stream().map(link -> link.getTopping().getPrice())
            .reduce(size.getExtraPrice(), BigDecimal::add);
        return new CurrentOptions(size.getId(), toppings.stream().map(link -> link.getTopping().getId()).toList(),
            product.getPrice().add(extras), names(toppings));
    }

    private List<ProductTopping> validateToppings(Long productId, List<Long> toppingIds) {
        List<Long> ids = toppingIds == null ? List.of() : toppingIds;
        if (ids.size() > 20 || ids.stream().anyMatch(id -> id == null || id < 1)
            || new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("Danh sách topping không hợp lệ.");
        }
        Map<Long, ProductTopping> allowed = toppingRepository
            .findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(productId).stream()
            .collect(Collectors.toMap(link -> link.getTopping().getId(), Function.identity()));
        List<ProductTopping> selected = new ArrayList<>();
        for (Long id : ids) {
            ProductTopping link = allowed.get(id);
            if (link == null) throw new IllegalArgumentException("Một topping đã ngừng bán hoặc không còn áp dụng.");
            selected.add(link);
        }
        selected.sort(Comparator.comparing(link -> link.getTopping().getId()));
        return selected;
    }

    private List<Long> parseIds(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return java.util.Arrays.stream(value.split(",", -1)).map(String::strip)
                .map(Long::parseLong).toList();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Dữ liệu topping của công thức không hợp lệ.");
        }
    }

    private String ids(List<ProductTopping> selected) {
        return selected.isEmpty() ? null : selected.stream().map(link -> link.getTopping().getId().toString())
            .collect(Collectors.joining(","));
    }

    private String names(List<ProductTopping> selected) {
        return selected.isEmpty() ? null : selected.stream().map(link -> link.getTopping().getName())
            .collect(Collectors.joining(", "));
    }

    private void validateName(String name) {
        if (name.length() < 2 || name.length() > 80) {
            throw new IllegalArgumentException("Tên công thức phải từ 2 đến 80 ký tự.");
        }
    }

    public record RecipeDisplay(SavedRecipe recipe, String issue, BigDecimal currentUnitPrice,
                                String toppingNames) {
        public boolean isAvailable() { return issue == null; }
    }

    public record RecipePreset(String name, Long sizeId, String sizeCode, String sugarLevel,
                               String iceLevel, String sugarLabel, String iceLabel,
                               String toppingIds, String toppingNames) {}

    private record CurrentOptions(Long sizeId, List<Long> toppingIds, BigDecimal unitPrice,
                                  String toppingNames) {}
}
