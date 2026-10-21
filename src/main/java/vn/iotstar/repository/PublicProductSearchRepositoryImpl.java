package vn.iotstar.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Promotion;

@Repository
@Transactional(readOnly = true)
public class PublicProductSearchRepositoryImpl
        implements PublicProductSearchRepository {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal PERCENT_FACTOR = new BigDecimal("0.01");
    private static final int MAX_PAGE_SIZE = 20;

    private final EntityManager entityManager;

    public PublicProductSearchRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<Product> searchPublic(
            String q,
            String slugQuery,
            Long categoryId,
            Long shopId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            BigDecimal minRating,
            boolean discountedOnly,
            boolean inStockOnly,
            LocalDateTime now,
            String sort,
            Pageable pageable) {

        Objects.requireNonNull(
            now,
            "Thời điểm truy vấn không được để trống."
        );

        BigDecimal minimum = nonNegative(minPrice);
        BigDecimal maximum = nonNegative(maxPrice);

        SearchFilters filters = new SearchFilters(
            clean(q),
            clean(slugQuery),
            categoryId,
            shopId,
            minimum,
            maximum,
            minRating == null
                ? null
                : minRating.max(BigDecimal.ZERO).min(new BigDecimal("5")),
            discountedOnly,
            inStockOnly,
            now
        );

        Pageable request = pageable == null || pageable.isUnpaged()
            ? PageRequest.of(0, MAX_PAGE_SIZE)
            : PageRequest.of(
                pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), MAX_PAGE_SIZE),
                pageable.getSort()
            );

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Product> countRoot = countQuery.from(Product.class);

        SearchCriteria count = criteria(
            cb,
            countQuery,
            countRoot,
            filters,
            false
        );

        countQuery.select(cb.count(countRoot))
            .where(count.predicates().toArray(Predicate[]::new));

        long total = entityManager.createQuery(countQuery)
            .getSingleResult();

        if (total == 0 || request.getOffset() >= total) {
            return new PageImpl<>(List.of(), request, total);
        }

        CriteriaQuery<Product> resultQuery = cb.createQuery(Product.class);
        Root<Product> resultRoot = resultQuery.from(Product.class);

        resultRoot.fetch("shop");
        resultRoot.fetch("category");

        boolean priceSort = "price-asc".equals(sort)
            || "price-desc".equals(sort);

        SearchCriteria result = criteria(
            cb,
            resultQuery,
            resultRoot,
            filters,
            priceSort
        );

        resultQuery.select(resultRoot)
            .where(result.predicates().toArray(Predicate[]::new))
            .orderBy(orderBy(
                cb,
                resultRoot,
                result.salePrice(),
                sort
            ));

        List<Product> content = entityManager.createQuery(resultQuery)
            .setFirstResult(Math.toIntExact(request.getOffset()))
            .setMaxResults(request.getPageSize())
            .getResultList();

        return new PageImpl<>(content, request, total);
    }

    private SearchCriteria criteria(
            CriteriaBuilder cb,
            AbstractQuery<?> query,
            Root<Product> product,
            SearchFilters search,
            boolean priceSort) {

        List<Predicate> filters = new ArrayList<>();

        filters.add(
            cb.equal(product.get("status"), ProductStatus.ACTIVE)
        );

        filters.add(
            cb.equal(
                product.get("shop").get("status"),
                ShopStatus.APPROVED
            )
        );

        filters.add(
            cb.isTrue(product.get("shop").get("enabled"))
        );

        filters.add(
            cb.isTrue(product.get("category").get("active"))
        );

        if (search.q() != null) {
            Predicate nameMatches = cb.like(
                cb.lower(product.get("name")),
                "%" + search.q().toLowerCase(Locale.ROOT) + "%"
            );

            if (search.slugQuery() == null) {
                filters.add(nameMatches);
            } else {
                filters.add(cb.or(
                    nameMatches,
                    cb.like(
                        cb.lower(product.get("slug")),
                        "%" + search.slugQuery()
                            .toLowerCase(Locale.ROOT) + "%"
                    )
                ));
            }
        }

        if (search.categoryId() != null) {
            filters.add(cb.equal(
                product.get("category").get("id"),
                search.categoryId()
            ));
        }

        if (search.shopId() != null) {
            filters.add(cb.equal(
                product.get("shop").get("id"),
                search.shopId()
            ));
        }

        if (search.minRating() != null) {
            filters.add(cb.ge(
                product.<BigDecimal>get("averageRating"),
                search.minRating()
            ));
        }

        if (search.inStockOnly()) {
            filters.add(cb.gt(
                product.<Integer>get("stock"),
                0
            ));
        }

        Expression<BigDecimal> salePrice = null;

        if (search.minPrice() != null
                || search.maxPrice() != null
                || search.discountedOnly()
                || priceSort) {

            Expression<BigDecimal> originalPrice = product.get("price");

            Expression<BigDecimal> percent = cb.coalesce(
                bestDiscount(cb, query, product, search.now()),
                BigDecimal.ZERO
            );

            Expression<BigDecimal> cappedPercent =
                cb.<BigDecimal>selectCase()
                    .when(
                        cb.greaterThan(percent, HUNDRED),
                        HUNDRED
                    )
                    .otherwise(percent);

            Expression<BigDecimal> rawDiscount = cb.prod(
                cb.prod(originalPrice, cappedPercent),
                PERCENT_FACTOR
            );

            // Làm tròn khoản giảm trước khi trừ, giống ProductPricingService.
            Expression<BigDecimal> discountAmount =
                cb.round(rawDiscount, 2);

            salePrice = cb.diff(originalPrice, discountAmount);

            if (search.minPrice() != null) {
                filters.add(cb.ge(salePrice, search.minPrice()));
            }

            if (search.maxPrice() != null) {
                filters.add(cb.le(salePrice, search.maxPrice()));
            }

            if (search.discountedOnly()) {
                filters.add(cb.lessThan(salePrice, originalPrice));
            }
        }

        return new SearchCriteria(filters, salePrice);
    }

    private Subquery<BigDecimal> bestDiscount(
            CriteriaBuilder cb,
            AbstractQuery<?> query,
            Root<Product> product,
            LocalDateTime now) {

        Subquery<BigDecimal> subquery =
            query.subquery(BigDecimal.class);

        Root<Promotion> promotion = subquery.from(Promotion.class);

        subquery.select(
            cb.max(promotion.<BigDecimal>get("discountValue"))
        );

        subquery.where(
            cb.isTrue(promotion.get("active")),

            cb.equal(
                promotion.get("type"),
                PromotionType.PRODUCT_PERCENT
            ),

            cb.gt(
                promotion.<BigDecimal>get("discountValue"),
                BigDecimal.ZERO
            ),

            cb.lessThanOrEqualTo(
                promotion.<LocalDateTime>get("startsAt"),
                now
            ),

            cb.greaterThanOrEqualTo(
                promotion.<LocalDateTime>get("endsAt"),
                now
            ),

            cb.or(
                cb.isNull(promotion.get("shop")),
                cb.equal(
                    promotion.get("shop"),
                    product.get("shop")
                )
            ),

            cb.or(
                cb.isNull(promotion.get("product")),
                cb.equal(
                    promotion.get("product"),
                    product
                )
            )
        );

        return subquery;
    }

    private List<Order> orderBy(
            CriteriaBuilder cb,
            Root<Product> product,
            Expression<BigDecimal> salePrice,
            String sort) {

        Order byId = cb.desc(product.get("id"));

        return switch (sort == null ? "bestselling" : sort) {
            case "newest" -> List.of(
                cb.desc(product.get("createdAt")),
                byId
            );

            case "rating" -> List.of(
                cb.desc(product.get("averageRating")),
                byId
            );

            case "favorite" -> List.of(
                cb.desc(product.get("favoriteCount")),
                byId
            );

            case "price-asc" -> List.of(
                cb.asc(salePrice),
                byId
            );

            case "price-desc" -> List.of(
                cb.desc(salePrice),
                byId
            );

            default -> List.of(
                cb.desc(product.get("soldCount")),
                byId
            );
        };
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null
            ? null
            : value.max(BigDecimal.ZERO);
    }

    private String clean(String value) {
        return value == null || value.isBlank()
            ? null
            : value.strip();
    }

    private record SearchFilters(
        String q,
        String slugQuery,
        Long categoryId,
        Long shopId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal minRating,
        boolean discountedOnly,
        boolean inStockOnly,
        LocalDateTime now
    ) {
    }

    private record SearchCriteria(
        List<Predicate> predicates,
        Expression<BigDecimal> salePrice
    ) {
    }
}