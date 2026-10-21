package vn.iotstar.controller;

import java.math.BigDecimal;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.service.CatalogService;
import vn.iotstar.service.ProductGalleryService;
import vn.iotstar.service.ProductOptionService;
import vn.iotstar.service.ProductPricingService;

@Controller
@RequiredArgsConstructor
public class ProductController {

    private final vn.iotstar.service.CurrentUserService currentUserService;
    private final vn.iotstar.service.EngagementService engagementService;
    private final CatalogService catalogService;
    private final vn.iotstar.service.ShopFollowService shopFollowService;
    private final vn.iotstar.service.SearchSuggestionService searchSuggestionService;
    private final ProductOptionService productOptionService;
    private final ProductPricingService productPricingService;
    private final ProductGalleryService productGalleryService;

    @GetMapping("/products")
    String products(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) Long shop,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(defaultValue = "false") boolean discounted,
            @RequestParam(defaultValue = "false") boolean inStock,
            @RequestParam(defaultValue = "bestselling") String sort,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        var products = catalogService.search(
            q,
            category,
            shop,
            sort,
            page,
            minPrice,
            maxPrice,
            minRating,
            discounted,
            inStock
        );

        searchSuggestionService.recordSearch(currentUserService.optional().orElse(null), q);
        model.addAttribute("products", products);
        model.addAttribute(
            "promotionPrices",
            productPricingService.prices(products.getContent())
        );
        model.addAttribute("navCategories", catalogService.categories());
        model.addAttribute("shops", catalogService.shops());
        model.addAttribute("q", q);
        model.addAttribute("selectedCategory", category);
        model.addAttribute("selectedShop", shop);
        model.addAttribute("minPrice", minPrice);
        model.addAttribute("maxPrice", maxPrice);
        model.addAttribute("minRating", minRating);
        model.addAttribute("discounted", discounted);
        model.addAttribute("inStock", inStock);
        model.addAttribute("sort", sort);
        var filters = new java.util.LinkedHashMap<String, Object>();
        if (q != null && !q.isBlank()) filters.put("q", q);
        if (category != null) filters.put("category", category);
        if (shop != null) filters.put("shop", shop);
        if (minPrice != null) filters.put("minPrice", minPrice);
        if (maxPrice != null) filters.put("maxPrice", maxPrice);
        if (minRating != null) filters.put("minRating", minRating);
        if (discounted) filters.put("discounted", true);
        if (inStock) filters.put("inStock", true);
        var labels = java.util.Map.of("q", "Từ khóa", "category", "Danh mục", "shop", "Cửa hàng",
            "minPrice", "Giá từ", "maxPrice", "Giá đến", "minRating", "Số sao", "discounted", "Giảm giá", "inStock", "Còn hàng");
        var chips = new java.util.ArrayList<FilterChip>();
        filters.forEach((key, value) -> {
            var link = org.springframework.web.util.UriComponentsBuilder.fromPath("/products");
            filters.forEach((k, v) -> { if (!k.equals(key)) link.queryParam(k, v); });
            if (sort != null) link.queryParam("sort", sort);
            String display = value.toString();
            if (key.equals("category")) display = catalogService.categories().stream()
                .filter(c -> c.getId().equals(category)).map(c -> c.getName()).findFirst().orElse(display);
            if (key.equals("shop")) display = catalogService.shops().stream()
                .filter(c -> c.getId().equals(shop)).map(c -> c.getName()).findFirst().orElse(display);
            chips.add(new FilterChip(labels.get(key) + (value instanceof Boolean ? "" : ": " + display),
                link.build().encode().toUriString()));
        });
        model.addAttribute("activeFilters", chips);

        return "products/list";
    }

    public record FilterChip(String label, String url) {}

    @GetMapping("/products/{id}")
    String detail(@PathVariable Long id, @RequestParam(defaultValue = "0") int reviewPage, Model model) {
        var product = catalogService.getPublicProduct(id);
        var customer = currentUserService.optional().filter(user ->
            user.getRole() == vn.iotstar.entity.DomainEnums.Role.USER
            || user.getRole() == vn.iotstar.entity.DomainEnums.Role.VENDOR).orElse(null);
        engagementService.recordView(product, customer);
        model.addAttribute("favorite", engagementService.isFavorite(customer, product));

        model.addAttribute("reviews", engagementService.reviews(product, reviewPage));
        model.addAttribute("product", product);
        model.addAttribute(
            "gallery",
            productGalleryService.displayImages(product)
        );
        model.addAttribute(
            "productPrice",
            productPricingService.price(product)
        );
        model.addAttribute(
            "sizes",
            productOptionService.availableSizes(product)
        );
        model.addAttribute(
            "toppings",
            productOptionService.availableToppings(product)
        );
        model.addAttribute("sugarLevels", SugarLevel.values());
        model.addAttribute("iceLevels", IceLevel.values());

        return "products/detail";
    }

    @GetMapping("/shops/{slug}")
    String shop(
            @PathVariable String slug,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        var shop = catalogService.getPublicShop(slug);
        var products = catalogService.search(
            null,
            null,
            shop.getId(),
            "bestselling",
            page
        );

        model.addAttribute("shop", shop);
        model.addAttribute("following", shopFollowService.isFollowing(currentUserService.optional().orElse(null), shop.getId()));
        model.addAttribute("products", products);
        model.addAttribute(
            "promotionPrices",
            productPricingService.prices(products.getContent())
        );

        return "products/shop";
    }
}