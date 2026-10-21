package vn.iotstar.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.util.SlugUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogService {

    private static final int PAGE_SIZE = 20;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ShopRepository shopRepository;

    public List<Product> homeBestSellers() {
        return productRepository.findBestSellers(
            10,
            ProductStatus.ACTIVE,
            ShopStatus.APPROVED,
            PageRequest.of(0, PAGE_SIZE)
        );
    }

    public Page<Product> search(
            String q,
            Long categoryId,
            Long shopId,
            String sort,
            int page) {

        return search(
            q,
            categoryId,
            shopId,
            sort,
            page,
            null,
            null,
            null,
            false,
            false
        );
    }

    public Page<Product> search(
            String q,
            Long categoryId,
            Long shopId,
            String sort,
            int page,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            BigDecimal minRating,
            boolean discountedOnly,
            boolean inStockOnly) {

        String query = SlugUtils.cleanQuery(q);
        String slugQuery = query == null
            ? null
            : SlugUtils.toSlug(query);

        String ordering = switch (sort == null ? "bestselling" : sort) {
            case "newest", "rating", "favorite", "price-asc", "price-desc" -> sort;
            default -> "bestselling";
        };

        return productRepository.searchPublic(
            query,
            slugQuery,
            categoryId,
            shopId,
            minPrice,
            maxPrice,
            minRating,
            discountedOnly,
            inStockOnly,
            LocalDateTime.now(),
            ordering,
            PageRequest.of(Math.max(0, page), PAGE_SIZE)
        );
    }

    public Product getPublicProduct(Long id) {
        return productRepository.findPublicById(
            id,
            ProductStatus.ACTIVE,
            ShopStatus.APPROVED
        ).orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Không tìm thấy sản phẩm."
        ));
    }

    public Shop getPublicShop(String slug) {
        return shopRepository.findBySlugAndStatusAndEnabledTrue(
            slug,
            ShopStatus.APPROVED
        ).orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Không tìm thấy cửa hàng."
        ));
    }

    public List<Category> categories() {
        return categoryRepository.findByActiveTrueOrderByNameAsc();
    }

    public List<Shop> shops() {
        return shopRepository.findByStatusAndEnabledTrueOrderByNameAsc(
            ShopStatus.APPROVED
        );
    }
}