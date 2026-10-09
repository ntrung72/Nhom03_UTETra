package vn.iotstar.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import vn.iotstar.service.CatalogService;
import vn.iotstar.service.ProductPricingService;

@Controller
@RequiredArgsConstructor
public class HomeController {
    private final CatalogService catalogService;
    private final ProductPricingService productPricingService;

    @GetMapping("/")
    String home(Model model) {
        var products = catalogService.homeBestSellers();
        model.addAttribute("bestSellers", products);
        model.addAttribute("promotionPrices", productPricingService.prices(products));
        model.addAttribute("categories", catalogService.categories());
        model.addAttribute("shops", catalogService.shops());
        return "home";
    }
}
