package vn.iotstar.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Promotion;

@Repository
public class PublicProductSearchRepositoryImpl implements PublicProductSearchRepository {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final EntityManager entityManager;

    public PublicProductSearchRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Product> searchPublic(String q, String slugQuery, Long categoryId, Long shopId,
                                      BigDecimal minPrice, BigDecimal maxPrice, BigDecimal minRating,
                                      boolean discountedOnly, boolean inStockOnly, LocalDateTime now,
                                      String sort, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Product> resultQuery = cb.createQuery(Product.class);
        Root<Product> resultRoot = resultQuery.from(Product.class);
        SearchCriteria result = criteria(cb, resultQuery, resultRoot, q, slugQuery, categoryId, shopId,
            minPrice, maxPrice, minRating, discountedOnly, inStockOnly, now,
            "price-asc".equals(sort) || "price-desc".equals(sort));
        resultQuery.select(resultRoot).where(result.predicates().toArray(Predicate[]::new));
        resultQuery.orderBy(orderBy(cb, resultRoot, result.salePrice(), sort));

        TypedQuery<Product> typedQuery = entityManager.createQuery(resultQuery);
        typedQuery.setFirstResult(Math.toIntExact(pageable.getOffset()));
        typedQuery.setMaxResults(pageable.getPageSize());
        List<Product> content = typedQuery.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Product> countRoot = countQuery.from(Product.class);
        SearchCriteria count = criteria(cb, countQuery, countRoot, q, slugQuery, categoryId, shopId,
            minPrice, maxPrice, minRating, discountedOnly, inStockOnly, now, false);
        countQuery.select(cb.count(countRoot)).where(count.predicates().toArray(Predicate[]::new));
        long total = entityManager.createQuery(countQuery).getSingleResult();
        return new PageImpl<>(content, pageable, total);
    }

    private SearchCriteria criteria(CriteriaBuilder cb, AbstractQuery<?> query, Root<Product> product,
                                    String q, String slugQuery, Long categoryId, Long shopId,
                                    BigDecimal minPrice, BigDecimal maxPrice, BigDecimal minRating,
                                    boolean discountedOnly, boolean inStockOnly, LocalDateTime now,
                                    boolean priceSort) {
        List<Predicate> filters = new ArrayList<>();
        filters.add(cb.equal(product.get("status"), ProductStatus.ACTIVE));
        filters.add(cb.equal(product.get("shop").get("status"), ShopStatus.APPROVED));
        filters.add(cb.isTrue(product.get("shop").get("enabled")));
        filters.add(cb.isTrue(product.get("category").get("active")));
        if (q != null) {
            filters.add(cb.or(
                cb.like(cb.lower(product.get("name")), "%" + q.toLowerCase(Locale.ROOT) + "%"),
                cb.like(cb.lower(product.get("slug")), "%" + slugQuery.toLowerCase(Locale.ROOT) + "%")));
        }
        if (categoryId != null) filters.add(cb.equal(product.get("category").get("id"), categoryId));
        if (shopId != null) filters.add(cb.equal(product.get("shop").get("id"), shopId));
        if (minRating != null) filters.add(cb.ge(product.get("averageRating"), minRating));
        if (inStockOnly) filters.add(cb.gt(product.get("stock"), 0));

        Expression<BigDecimal> salePrice = null;
        if (minPrice != null || maxPrice != null || discountedOnly || priceSort) {
            Subquery<BigDecimal> bestDiscount = bestDiscount(cb, query, product, now);
            if (discountedOnly) filters.add(cb.gt(bestDiscount, BigDecimal.ZERO));
            if (minPrice != null || maxPrice != null || priceSort) {
                Expression<BigDecimal> percent = cb.coalesce(bestDiscount, BigDecimal.ZERO);
                Expression<BigDecimal> capped = cb.<BigDecimal>selectCase()
                    .when(cb.greaterThan(percent, HUNDRED), HUNDRED).otherwise(percent);
                Expression<Number> rawDiscount = cb.prod(product.<BigDecimal>get("price"),
                    cb.quot(capped, HUNDRED));
                Expression<BigDecimal> roundedDiscount = cb.function("round", BigDecimal.class,
                    rawDiscount, cb.literal(2));
                salePrice = cb.diff(product.<BigDecimal>get("price"), roundedDiscount);
                if (minPrice != null) filters.add(cb.ge(salePrice, minPrice));
                if (maxPrice != null) filters.add(cb.le(salePrice, maxPrice));
            }
        }
        return new SearchCriteria(filters, salePrice);
    }

    private Subquery<BigDecimal> bestDiscount(CriteriaBuilder cb, AbstractQuery<?> query,
                                               Root<Product> product, LocalDateTime now) {
        Subquery<BigDecimal> subquery = query.subquery(BigDecimal.class);
        Root<Promotion> promotion = subquery.from(Promotion.class);
        subquery.select(cb.max(promotion.get("discountValue")));
        subquery.where(
            cb.isTrue(promotion.get("active")),
            cb.equal(promotion.get("type"), PromotionType.PRODUCT_PERCENT),
            cb.gt(promotion.get("discountValue"), BigDecimal.ZERO),
            cb.lessThanOrEqualTo(promotion.get("startsAt"), now),
            cb.greaterThanOrEqualTo(promotion.get("endsAt"), now),
            cb.or(cb.isNull(promotion.get("shop")), cb.equal(promotion.get("shop"), product.get("shop"))),
            cb.or(cb.isNull(promotion.get("product")), cb.equal(promotion.get("product"), product)));
        return subquery;
    }

    private List<Order> orderBy(CriteriaBuilder cb, Root<Product> product,
                                Expression<BigDecimal> salePrice, String sort) {
        Order byId = cb.desc(product.get("id"));
        return switch (sort == null ? "bestselling" : sort) {
            case "newest" -> List.of(cb.desc(product.get("createdAt")), byId);
            case "rating" -> List.of(cb.desc(product.get("averageRating")), byId);
            case "favorite" -> List.of(cb.desc(product.get("favoriteCount")), byId);
            case "price-asc" -> List.of(cb.asc(salePrice), byId);
            case "price-desc" -> List.of(cb.desc(salePrice), byId);
            default -> List.of(cb.desc(product.get("soldCount")), byId);
        };
    }

    private record SearchCriteria(List<Predicate> predicates, Expression<BigDecimal> salePrice) {}
}
