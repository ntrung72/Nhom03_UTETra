package vn.iotstar.service;

import vn.iotstar.util.VietnamTime;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.Shop;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.PromotionRepository;
import vn.iotstar.repository.ReviewRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.util.SlugUtils;

@Service
public class CatalogService {
    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final CategoryRepository categoryRepository;
    private final ShopRepository shopRepository;
    private final PromotionRepository promotionRepository;

    public CatalogService(ProductRepository productRepository, ReviewRepository reviewRepository,
                          CategoryRepository categoryRepository, ShopRepository shopRepository,
                          PromotionRepository promotionRepository) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.categoryRepository = categoryRepository;
        this.shopRepository = shopRepository;
        this.promotionRepository = promotionRepository;
    }

    public List<Product> homeBestSellers() {
        return productRepository.findBestSellers(10, ProductStatus.ACTIVE, ShopStatus.APPROVED, PageRequest.of(0, 8));
    }

    public List<Product> homePromotions() {
        return search(null, null, null, "bestselling", 0, null, null, null, true, true)
            .getContent().stream().limit(4).toList();
    }

    public List<Product> homeFeaturedProducts() {
        return search(null, null, null, "rating", 0, null, null, null, false, true)
            .getContent().stream().limit(4).toList();
    }

    public List<ShopSpotlight> homeFeaturedShops() {
        return shopSpotlights().stream().limit(4).toList();
    }

    public List<ShopSpotlight> shopSpotlights() {
        return shops().stream()
            .map(shop -> new ShopSpotlight(shop, shopRating(shop)))
            .sorted(Comparator.comparingDouble(ShopSpotlight::rating).reversed()
                .thenComparing(spotlight -> spotlight.shop().getName()))
            .toList();
    }

    public record ShopSpotlight(Shop shop, double rating) {}

    public Page<Product> search(String q, Long categoryId, Long shopId, String sort, int page) {
        return search(q, categoryId, shopId, sort, page, null, null, null, false, false);
    }

    public Page<Product> search(String q, Long categoryId, Long shopId, String sort, int page,
                                BigDecimal minPrice, BigDecimal maxPrice, BigDecimal minRating,
                                boolean discountedOnly, boolean inStockOnly) {
        String priceError = priceFilterError(minPrice, maxPrice);
        if (priceError != null) throw new IllegalArgumentException(priceError);
        String cleanedQuery = SlugUtils.cleanQuery(q);
        String slugQuery = cleanedQuery == null ? null : SlugUtils.toSlug(cleanedQuery);
        BigDecimal safeMinRating = clampRating(minRating);
        return productRepository.searchPublic(cleanedQuery, slugQuery, categoryId, shopId,
            minPrice, maxPrice, safeMinRating, discountedOnly, inStockOnly,
            VietnamTime.now(), sort, PageRequest.of(Math.max(page, 0), 20));
    }

    public String priceFilterError(BigDecimal minPrice, BigDecimal maxPrice) {
        if ((minPrice != null && minPrice.signum() < 0) || (maxPrice != null && maxPrice.signum() < 0)) {
            return "Giá lọc không được âm.";
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            return "Giá tối thiểu không được lớn hơn giá tối đa.";
        }
        return null;
    }

    private BigDecimal clampRating(BigDecimal value) {
        if (value == null) return null;
        return value.max(BigDecimal.ZERO).min(BigDecimal.valueOf(5));
    }

    @Transactional(readOnly = true)
    public Product getPublicProduct(Long id) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm."));
        if (product.getStatus() != ProductStatus.ACTIVE || !product.getShop().isApproved()
            || !product.getCategory().isActive()) {
            throw new IllegalArgumentException("Sản phẩm hiện không được bán.");
        }
        return product;
    }

    public Shop getPublicShop(String slug) {
        return shopRepository.findBySlugAndStatusAndEnabledTrue(slug, ShopStatus.APPROVED)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cửa hàng."));
    }

    public List<Promotion> activeShopPromotions(Shop shop) {
        if (shop == null || !shop.isApproved()) return List.of();
        return promotionRepository.findActiveForPublicShop(shop.getId(), VietnamTime.now(),
            ProductStatus.ACTIVE, PageRequest.of(0, 8));
    }

    public double shopRating(Shop shop) {
        Double rating = reviewRepository.averageRatingByShopId(shop.getId());
        return rating == null ? 0D : rating;
    }

    public List<Category> categories() { return categoryRepository.findByActiveTrueOrderByNameAsc(); }
    public List<Shop> shops() { return shopRepository.findByStatusAndEnabledTrueOrderByNameAsc(ShopStatus.APPROVED); }
}
