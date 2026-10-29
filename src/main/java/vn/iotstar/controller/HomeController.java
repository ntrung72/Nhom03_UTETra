package vn.iotstar.controller;

import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import vn.iotstar.service.CatalogService;
import vn.iotstar.service.ProductPricingService;
import vn.iotstar.entity.Product;

@Controller
@RequiredArgsConstructor
public class HomeController {
    private final CatalogService catalogService;
    private final ProductPricingService productPricingService;

    @GetMapping("/")
    String home(Model model) {
        var bestSellers = catalogService.homeBestSellers();
        var promotions = catalogService.homePromotions();
        var featuredProducts = catalogService.homeFeaturedProducts();
        var productsById = new LinkedHashMap<Long, Product>();
        List.of(bestSellers, promotions, featuredProducts).forEach(group ->
            group.forEach(product -> productsById.putIfAbsent(product.getId(), product)));
        var promotionPrices = productPricingService.prices(productsById.values());
        promotions = promotions.stream()
            .filter(product -> promotionPrices.containsKey(product.getId())
                && promotionPrices.get(product.getId()).promoted())
            .toList();
        model.addAttribute("bestSellers", bestSellers);
        model.addAttribute("heroProduct", !bestSellers.isEmpty() ? bestSellers.get(0)
            : featuredProducts.isEmpty() ? null : featuredProducts.get(0));
        model.addAttribute("promotions", promotions);
        model.addAttribute("featuredProducts", featuredProducts);
        model.addAttribute("promotionPrices", promotionPrices);
        model.addAttribute("categories", catalogService.categories());
        model.addAttribute("shopSpotlights", catalogService.homeFeaturedShops());
        return "home";
    }

    @GetMapping("/policies")
    String policies() {
        return "site/policies";
    }

    @GetMapping("/shops")
    String shops(Model model) {
        model.addAttribute("shopSpotlights", catalogService.shopSpotlights());
        return "site/shops";
    }
}
