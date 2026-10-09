package vn.iotstar.service;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.util.SlugUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ShopRepository shopRepository;
    private final ProductPricingService productPricingService;

    public List<Product> homeBestSellers() {
        return productRepository.findBestSellers(10, ProductStatus.ACTIVE, ShopStatus.APPROVED,
            PageRequest.of(0, 20));
    }

    public Page<Product> search(String q, Long categoryId, Long shopId, String sort, int page) {
        return search(q, categoryId, shopId, sort, page, null, null, null, false, false);
    }

    public Page<Product> search(String q, Long categoryId, Long shopId, String sort, int page,
            BigDecimal minPrice, BigDecimal maxPrice, BigDecimal minRating,
            boolean discountedOnly, boolean inStockOnly) {
        String query = SlugUtils.cleanQuery(q);
        String slugQuery = query == null ? null : SlugUtils.toSlug(query);
        Sort ordering = switch (sort == null ? "bestselling" : sort) {
            case "newest" -> Sort.by(Sort.Direction.DESC, "createdAt");
            case "rating" -> Sort.by(Sort.Direction.DESC, "averageRating");
            case "favorite" -> Sort.by(Sort.Direction.DESC, "favoriteCount");
            case "price-asc" -> Sort.by(Sort.Direction.ASC, "price");
            case "price-desc" -> Sort.by(Sort.Direction.DESC, "price");
            default -> Sort.by(Sort.Direction.DESC, "soldCount");
        };
        ordering = ordering.and(Sort.by(Sort.Direction.DESC, "id"));
        PageRequest request = PageRequest.of(Math.max(0, page), 20, ordering);
        var candidates = productRepository.searchPublic(query, slugQuery, categoryId, shopId,
            nonNegative(minPrice), nonNegative(maxPrice),
            minRating == null ? null : minRating.max(BigDecimal.ZERO).min(BigDecimal.valueOf(5)),
            inStockOnly, ProductStatus.ACTIVE, ShopStatus.APPROVED,
            discountedOnly ? Pageable.unpaged(ordering) : request);
        if (!discountedOnly) return candidates;

        // Pricing remains owned by TV2; no dependency on the later Promotion module.
        var prices = productPricingService.prices(candidates.getContent());
        List<Product> discounted = candidates.stream().filter(product -> {
            var price = prices.get(product.getId());
            return price != null && price.promoted() && price.salePrice().compareTo(price.originalPrice()) < 0;
        }).toList();
        int from = (int) Math.min(request.getOffset(), discounted.size());
        int to = Math.min(from + request.getPageSize(), discounted.size());
        return new PageImpl<>(discounted.subList(from, to), request, discounted.size());
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null ? null : value.max(BigDecimal.ZERO);
    }

    public Product getPublicProduct(Long id) {
        return productRepository.findPublicById(id, ProductStatus.ACTIVE, ShopStatus.APPROVED)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm."));
    }

    public Shop getPublicShop(String slug) {
        return shopRepository.findBySlugAndStatusAndEnabledTrue(slug, ShopStatus.APPROVED)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy cửa hàng."));
    }

    public List<Category> categories() {
        return categoryRepository.findByActiveTrueOrderByNameAsc();
    }

    public List<Shop> shops() {
        return shopRepository.findByStatusAndEnabledTrueOrderByNameAsc(ShopStatus.APPROVED);
    }
}
