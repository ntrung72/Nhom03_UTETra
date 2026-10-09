package vn.iotstar.controller;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import vn.iotstar.service.CatalogService;
import vn.iotstar.service.ProductPricingService;

@RestController
@RequiredArgsConstructor
public class CatalogApiController {
    private final CatalogService catalogService;
    private final ProductPricingService productPricingService;

    @GetMapping("/api/products")
    @Operation(summary = "Danh sách sản phẩm công khai với bộ lọc và phân trang")
    ProductsResponse products(@RequestParam(required = false) String q,
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) Long shop,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) BigDecimal minRating,
            @RequestParam(defaultValue = "false") boolean discounted,
            @RequestParam(defaultValue = "false") boolean inStock,
            @RequestParam(defaultValue = "bestselling") String sort,
            @RequestParam(defaultValue = "0") int page) {
        var products = catalogService.search(q, category, shop, sort, page,
            minPrice, maxPrice, minRating, discounted, inStock);
        var prices = productPricingService.prices(products.getContent());
        var content = products.stream().map(p -> new ProductSummary(p.getId(), p.getName(), p.getSlug(),
            p.getDescription(), p.getImageUrl(), p.getShop().getId(), p.getShop().getName(),
            p.getShop().getSlug(), p.getCategory().getId(), p.getCategory().getName(),
            prices.get(p.getId()), p.getStock(), p.getSoldCount(), p.getAverageRating(), p.getReviewCount()))
            .toList();
        return new ProductsResponse(content, products.getNumber(), products.getSize(),
            products.getTotalElements(), products.getTotalPages());
    }

    public record ProductsResponse(List<ProductSummary> content, int number, int size,
        long totalElements, int totalPages) {}

    public record ProductSummary(Long id, String name, String slug, String description, String imageUrl,
        Long shopId, String shopName, String shopSlug, Long categoryId, String categoryName,
        ProductPricingService.ProductPrice price, int stock, long soldCount,
        BigDecimal averageRating, long reviewCount) {}
}
