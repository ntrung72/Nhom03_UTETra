package vn.iotstar.controller;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.service.CatalogService;
import vn.iotstar.service.ProductOptionService;
import vn.iotstar.service.ProductPricingService;

@Controller
@RequiredArgsConstructor
public class ProductController {
    private final CatalogService catalogService;
    private final ProductOptionService productOptionService;
    private final ProductPricingService productPricingService;

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
        var products = catalogService.search(q, category, shop, sort, page,
            minPrice, maxPrice, minRating, discounted, inStock);
        model.addAttribute("products", products);
        model.addAttribute("promotionPrices", productPricingService.prices(products.getContent()));
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
        return "products/list";
    }

    @GetMapping("/products/{id}")
    String detail(@PathVariable Long id, Model model) {
        var product = catalogService.getPublicProduct(id);
        model.addAttribute("product", product);
        model.addAttribute("productPrice", productPricingService.price(product));
        model.addAttribute("sizes", productOptionService.availableSizes(product));
        model.addAttribute("toppings", productOptionService.availableToppings(product));
        model.addAttribute("sugarLevels", SugarLevel.values());
        model.addAttribute("iceLevels", IceLevel.values());
        return "products/detail";
    }

    @GetMapping("/shops/{slug}")
    String shop(@PathVariable String slug, @RequestParam(defaultValue = "0") int page, Model model) {
        var shop = catalogService.getPublicShop(slug);
        var products = catalogService.search(null, null, shop.getId(), "bestselling", page);
        model.addAttribute("shop", shop);
        model.addAttribute("products", products);
        model.addAttribute("promotionPrices", productPricingService.prices(products.getContent()));
        return "products/shop";
    }
}
