package vn.iotstar.controller;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.service.CatalogService;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.EngagementService;
import vn.iotstar.service.ProductPricingService;
import vn.iotstar.service.ProductOptionService;
import vn.iotstar.service.ProductGalleryService;
import vn.iotstar.service.SearchSuggestionService;
import vn.iotstar.service.ShopFollowService;
import vn.iotstar.service.SavedRecipeService;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.SugarLevel;

@Controller
public class ProductController {
    private final CatalogService catalogService;
    private final EngagementService engagementService;
    private final CurrentUserService currentUserService;
    private final ProductOptionService productOptionService;
    private final ProductPricingService productPricingService;
    private final ProductGalleryService productGalleryService;
    private final SearchSuggestionService searchSuggestionService;
    private final ShopFollowService shopFollowService;
    private final SavedRecipeService savedRecipeService;

    public ProductController(CatalogService catalogService, EngagementService engagementService,
                             CurrentUserService currentUserService, ProductOptionService productOptionService,
                             ProductPricingService productPricingService, ProductGalleryService productGalleryService,
                             SearchSuggestionService searchSuggestionService, ShopFollowService shopFollowService,
                             SavedRecipeService savedRecipeService) {
        this.catalogService = catalogService;
        this.engagementService = engagementService;
        this.currentUserService = currentUserService;
        this.productOptionService = productOptionService;
        this.productPricingService = productPricingService;
        this.productGalleryService = productGalleryService;
        this.searchSuggestionService = searchSuggestionService;
        this.shopFollowService = shopFollowService;
        this.savedRecipeService = savedRecipeService;
    }

    @GetMapping("/products")
    String products(@RequestParam(required = false) String q,
                    @RequestParam(required = false) Long category,
                    @RequestParam(required = false) Long shop,
                    @RequestParam(required = false) BigDecimal minPrice,
                    @RequestParam(required = false) BigDecimal maxPrice,
                    @RequestParam(required = false) BigDecimal minRating,
                    @RequestParam(defaultValue = "false") boolean discounted,
                    @RequestParam(defaultValue = "false") boolean inStock,
                    @RequestParam(defaultValue = "bestselling") String sort,
                    @RequestParam(defaultValue = "0") int page, Model model) {
        String priceRangeError = catalogService.priceFilterError(minPrice, maxPrice);
        Page<Product> products = priceRangeError != null ? Page.empty(PageRequest.of(0, 20))
            : catalogService.search(q, category, shop, sort, page,
                minPrice, maxPrice, minRating, discounted, inStock);
        if (priceRangeError == null && page == 0) {
            searchSuggestionService.recordSearch(currentUserService.optional().orElse(null), q);
        }
        List<Category> categories = catalogService.categories();
        List<Shop> shops = catalogService.shops();
        model.addAttribute("products", products);
        model.addAttribute("promotionPrices", productPricingService.prices(products.getContent()));
        model.addAttribute("categories", categories);
        model.addAttribute("shops", shops);
        model.addAttribute("q", q);
        model.addAttribute("selectedCategory", category);
        model.addAttribute("selectedShop", shop);
        model.addAttribute("minPrice", minPrice);
        model.addAttribute("maxPrice", maxPrice);
        model.addAttribute("minRating", minRating);
        model.addAttribute("discounted", discounted);
        model.addAttribute("inStock", inStock);
        model.addAttribute("sort", sort);
        model.addAttribute("priceRangeError", priceRangeError);
        Map<String, String> filterParams = filterParams(q, category, shop, minPrice, maxPrice,
            minRating, discounted, inStock, sort);
        model.addAttribute("filterChips", filterChips(filterParams, categories, shops));
        model.addAttribute("clearFiltersUrl", filterUrl(Map.of("sort", sort)));
        return "products/list";
    }

    private Map<String, String> filterParams(String q, Long category, Long shop,
                                              BigDecimal minPrice, BigDecimal maxPrice,
                                              BigDecimal minRating, boolean discounted,
                                              boolean inStock, String sort) {
        Map<String, String> params = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) params.put("q", q.trim());
        if (category != null) params.put("category", category.toString());
        if (shop != null) params.put("shop", shop.toString());
        if (minPrice != null) params.put("minPrice", minPrice.toPlainString());
        if (maxPrice != null) params.put("maxPrice", maxPrice.toPlainString());
        if (minRating != null) params.put("minRating", minRating.toPlainString());
        if (discounted) params.put("discounted", "true");
        if (inStock) params.put("inStock", "true");
        if (sort != null && !sort.isBlank() && !sort.equals("bestselling")) params.put("sort", sort);
        return params;
    }

    private List<FilterChip> filterChips(Map<String, String> params, List<Category> categories,
                                         List<Shop> shops) {
        List<FilterChip> chips = new ArrayList<>();
        if (params.containsKey("q")) addChip(chips, params, "q", "Từ khóa: " + params.get("q"));
        if (params.containsKey("category")) {
            String name = categories.stream().filter(item -> item.getId().toString().equals(params.get("category")))
                .map(Category::getName).findFirst().orElse("#" + params.get("category"));
            addChip(chips, params, "category", "Danh mục: " + name);
        }
        if (params.containsKey("shop")) {
            String name = shops.stream().filter(item -> item.getId().toString().equals(params.get("shop")))
                .map(Shop::getName).findFirst().orElse("#" + params.get("shop"));
            addChip(chips, params, "shop", "Cửa hàng: " + name);
        }
        NumberFormat money = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"));
        if (params.containsKey("minPrice")) addChip(chips, params, "minPrice",
            "Giá từ " + money.format(new BigDecimal(params.get("minPrice"))) + " ₫");
        if (params.containsKey("maxPrice")) addChip(chips, params, "maxPrice",
            "Giá đến " + money.format(new BigDecimal(params.get("maxPrice"))) + " ₫");
        if (params.containsKey("minRating")) addChip(chips, params, "minRating",
            "Từ " + new BigDecimal(params.get("minRating")).stripTrailingZeros().toPlainString().replace('.', ',') + " sao");
        if (params.containsKey("discounted")) addChip(chips, params, "discounted", "Đang giảm giá");
        if (params.containsKey("inStock")) addChip(chips, params, "inStock", "Còn hàng");
        return chips;
    }

    private void addChip(List<FilterChip> chips, Map<String, String> params,
                         String removed, String label) {
        Map<String, String> remaining = new LinkedHashMap<>(params);
        remaining.remove(removed);
        chips.add(new FilterChip(label, filterUrl(remaining)));
    }

    private String filterUrl(Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/products");
        params.forEach(builder::queryParam);
        return builder.build().encode().toUriString();
    }

    public record FilterChip(String label, String removeUrl) {}

    @GetMapping("/products/{id}")
    String detail(@PathVariable Long id, @RequestParam(defaultValue = "0") int reviewPage,
                  @RequestParam(required = false) Integer rating,
                  @RequestParam(defaultValue = "false") boolean mediaOnly, Model model) {
        Product product = catalogService.getPublicProduct(id);
        User user = currentUserService.optional().orElse(null);
        User customer = user != null && (user.getRole() == Role.USER || user.getRole() == Role.VENDOR) ? user : null;
        var reviews = engagementService.reviews(product, reviewPage, rating, mediaOnly);
        engagementService.recordView(product, customer);
        var productPrice = productPricingService.price(product);
        var sizes = productOptionService.availableSizes(product);
        BigDecimal initialOptionPrice = sizes.isEmpty() ? BigDecimal.ZERO : sizes.getFirst().getExtraPrice();
        model.addAttribute("product", product);
        model.addAttribute("gallery", productGalleryService.displayImages(product));
        model.addAttribute("productPrice", productPrice);
        model.addAttribute("initialUnitPrice", productPrice.salePrice().add(initialOptionPrice));
        model.addAttribute("initialOriginalPrice", productPrice.originalPrice().add(initialOptionPrice));
        model.addAttribute("initialSavings", productPrice.originalPrice().subtract(productPrice.salePrice()));
        model.addAttribute("canPurchase", product.isAvailable());
        model.addAttribute("reviews", reviews);
        model.addAttribute("selectedRating", rating);
        model.addAttribute("mediaOnly", mediaOnly);
        model.addAttribute("favorite", engagementService.isFavorite(customer, product));
        model.addAttribute("sizes", sizes);
        model.addAttribute("savedRecipePresets", customer == null || !product.isAvailable() || sizes.isEmpty()
            ? List.of() : savedRecipeService.presetsForProduct(customer, product.getId()));
        model.addAttribute("toppings", productOptionService.availableToppings(product));
        model.addAttribute("sugarLevels", SugarLevel.values());
        model.addAttribute("iceLevels", IceLevel.values());
        return "products/detail";
    }

    @GetMapping("/shops/{slug}")
    String shop(@PathVariable String slug, @RequestParam(defaultValue = "0") int page, Model model) {
        Shop shop = catalogService.getPublicShop(slug);
        model.addAttribute("shop", shop);
        model.addAttribute("shopRating", catalogService.shopRating(shop));
        model.addAttribute("shopPromotions", catalogService.activeShopPromotions(shop));
        model.addAttribute("followingShop", shopFollowService.isFollowing(
            currentUserService.optional().orElse(null), shop.getId()));
        var products = catalogService.search(null, null, shop.getId(), "bestselling", page);
        model.addAttribute("products", products);
        model.addAttribute("promotionPrices", productPricingService.prices(products.getContent()));
        return "products/shop";
    }
}
