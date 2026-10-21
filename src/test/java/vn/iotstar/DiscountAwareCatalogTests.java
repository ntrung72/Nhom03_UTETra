package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.ProductSize;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.service.CatalogService;
import vn.iotstar.service.ProductPricingService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DiscountAwareCatalogTests {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private ProductPricingService pricingService;

    private String prefix;
    private LocalDateTime now;
    private Shop shop;
    private Category category;
    private int sequence;

    @BeforeEach
    void setUp() {
        prefix = "s38" + UUID.randomUUID().toString().replace("-", "");
        now = LocalDateTime.now().withNano(0);
        sequence = 0;

        // Thay đổi này chỉ nằm trong transaction test và được rollback.
        entityManager.createQuery("update Promotion p set p.active = false")
            .executeUpdate();

        category = createCategory(true);
        shop = createShop(ShopStatus.APPROVED, true);
    }

    @Test
    void filtersUsingSellingPriceInsteadOfOriginalPrice() {
        Product discounted = product("discounted", "100.00");
        product("regular", "60.00");
        promotion(shop, discounted, "50");

        Page<Product> page = search(
            "45", "55", false, false, "price-asc", 0
        );

        assertIds(page, discounted);
        assertThat(page.getTotalElements()).isEqualTo(1);

        assertThat(pricingService.price(page.getContent().getFirst()).salePrice())
            .isEqualByComparingTo("50.00");
    }

    @Test
    void sortsBySellingPriceWithStableIdOrderForEqualPrices() {
        Product discounted = product("discounted", "100");
        Product regular = product("regular", "60");
        Product sameSellingPrice = product("same-price", "80");

        promotion(shop, discounted, "60");
        promotion(shop, sameSellingPrice, "25");

        assertIds(
            search(null, null, false, false, "price-asc", 0),
            discounted, sameSellingPrice, regular
        );

        assertIds(
            search(null, null, false, false, "price-desc", 0),
            sameSellingPrice, regular, discounted
        );
    }

    @Test
    void ignoresExpiredFutureInactiveShippingAndNonPositivePromotions() {
        Product product = product("promotion-status", "100");
        promotion(shop, product, "20");

        Promotion expired = promotion(shop, product, "90");
        expired.setStartsAt(now.minusDays(2));
        expired.setEndsAt(now.minusDays(1));

        Promotion future = promotion(shop, product, "90");
        future.setStartsAt(now.plusDays(1));
        future.setEndsAt(now.plusDays(2));

        Promotion inactive = promotion(shop, product, "90");
        inactive.setActive(false);

        Promotion shipping = promotion(shop, product, "90");
        shipping.setType(PromotionType.SHIPPING_DISCOUNT);

        promotion(shop, product, "0");
        promotion(shop, product, "-10");

        assertIds(
            search("80", "80", true, false, "price-asc", 0),
            product
        );

        assertThat(pricingService.price(product).salePrice())
            .isEqualByComparingTo("80.00");
    }

    @Test
    void includesPromotionAtItsExactStartAndEndTime() {
        Product product = product("boundary", "100");
        Promotion promotion = promotion(shop, product, "25");
        promotion.setStartsAt(now);
        promotion.setEndsAt(now);

        assertIds(
            search("75", "75", true, false, "price-asc", 0),
            product
        );
    }

    @Test
    void choosesHighestApplicablePercentWithoutStackingOrCrossShopLeakage() {
        Shop otherShop = createShop(ShopStatus.APPROVED, true);

        Product target = product("target", "100");
        Product sibling = product("sibling", "100");
        Product other = product(
            otherShop, category, "other-shop", "100"
        );

        promotion(null, null, "10");
        promotion(shop, null, "20");
        promotion(shop, target, "30");
        promotion(otherShop, target, "90");

        assertIds(
            search("70", "70", true, false, "price-asc", 0),
            target
        );

        assertIds(
            search("80", "80", true, false, "price-asc", 0),
            sibling
        );

        assertIds(
            search("90", "90", true, false, "price-asc", 0),
            other
        );

        assertThat(pricingService.price(target).salePrice())
            .isEqualByComparingTo("70");

        assertThat(pricingService.price(sibling).salePrice())
            .isEqualByComparingTo("80");

        assertThat(pricingService.price(other).salePrice())
            .isEqualByComparingTo("90");
    }

    @Test
    void capsDiscountAtOneHundredAndRequiresAnActualPriceReduction() {
        Product fullyDiscounted = product("fully-discounted", "100");
        Product free = product("already-free", "0");
        Product tiny = product("tiny", "0.01");

        promotion(shop, fullyDiscounted, "200");
        promotion(shop, free, "50");
        promotion(shop, tiny, "1");

        assertIds(
            search("0", "0", true, false, "price-asc", 0),
            fullyDiscounted
        );

        assertIds(
            search(null, null, true, false, "price-asc", 0),
            fullyDiscounted
        );

        assertThat(pricingService.price(fullyDiscounted).salePrice())
            .isEqualByComparingTo("0");

        assertThat(pricingService.price(tiny).salePrice())
            .isEqualByComparingTo("0.01");
    }

    @Test
    void roundsTheDiscountAmountBeforeSubtractingItFromThePrice() {
        Product halfCent = product("half-cent", "0.05");
        Product decimal = product("decimal", "19.99");

        promotion(shop, halfCent, "10");
        promotion(shop, decimal, "12.50");

        assertIds(
            search("0.04", "0.04", true, false, "price-asc", 0),
            halfCent
        );

        assertIds(
            search("17.49", "17.49", true, false, "price-asc", 0),
            decimal
        );

        assertThat(pricingService.price(halfCent).salePrice())
            .isEqualByComparingTo("0.04");

        assertThat(pricingService.price(decimal).salePrice())
            .isEqualByComparingTo("17.49");
    }

    @Test
    void paginatesEqualSellingPricesWithoutDuplicatesOrIncorrectTotals() {
        List<Long> expectedIds = new ArrayList<>();

        for (int i = 0; i < 45; i++) {
            expectedIds.add(product("page-" + i, "100").getId());
        }

        promotion(shop, null, "10");
        promotion(shop, null, "20");
        expectedIds.sort(Comparator.reverseOrder());

        Page<Product> first = search(
            null, null, true, false, "price-asc", 0
        );

        Page<Product> second = search(
            null, null, true, false, "price-asc", 1
        );

        Page<Product> third = search(
            null, null, true, false, "price-asc", 2
        );

        assertThat(first.getTotalElements()).isEqualTo(45);
        assertThat(second.getTotalElements()).isEqualTo(45);
        assertThat(third.getTotalElements()).isEqualTo(45);
        assertThat(first.getTotalPages()).isEqualTo(3);

        assertThat(first.getContent()).extracting(Product::getId)
            .containsExactlyElementsOf(expectedIds.subList(0, 20));

        assertThat(second.getContent()).extracting(Product::getId)
            .containsExactlyElementsOf(expectedIds.subList(20, 40));

        assertThat(third.getContent()).extracting(Product::getId)
            .containsExactlyElementsOf(expectedIds.subList(40, 45));

        Page<Product> beyondLast = search(
            null, null, true, false, "price-asc", Integer.MAX_VALUE
        );

        assertThat(beyondLast.getContent()).isEmpty();
        assertThat(beyondLast.getTotalElements()).isEqualTo(45);
    }

    @Test
    void limitsRowsInDatabaseAndFetchesShopAndCategoryWithoutNPlusOne() {
        List<Shop> shops = List.of(
            shop,
            createShop(ShopStatus.APPROVED, true),
            createShop(ShopStatus.APPROVED, true)
        );

        for (int i = 0; i < 25; i++) {
            product(
                shops.get(i % shops.size()),
                category,
                "fetch-" + i,
                "100"
            );
        }

        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManager.getEntityManagerFactory()
            .unwrap(SessionFactory.class)
            .getStatistics();

        boolean previouslyEnabled = statistics.isStatisticsEnabled();

        try {
            statistics.setStatisticsEnabled(true);
            statistics.clear();

            Page<Product> page = productRepository.searchPublic(
                prefix, prefix, null, null, null, null, null,
                false, false, now, "price-asc", PageRequest.of(0, 100)
            );

            assertThat(page.getSize()).isEqualTo(20);
            assertThat(page.getNumberOfElements()).isEqualTo(20);
            assertThat(page.getTotalElements()).isEqualTo(25);

            for (Product product : page) {
                assertThat(product.getShop().getName()).isNotBlank();
                assertThat(product.getCategory().getName()).isNotBlank();
            }

            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
            assertThat(statistics.getEntityLoadCount()).isLessThanOrEqualTo(24);
        } finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
    }

    @Test
    void excludesNonPublicProductsAndAppliesStockFilter() {
        Product available = product("available", "10");

        Product empty = product("empty", "20");
        empty.setStock(0);

        Product hidden = product("hidden", "30");
        hidden.setStatus(ProductStatus.HIDDEN);

        Product stopped = product("stopped", "40");
        stopped.setStatus(ProductStatus.OUT_OF_STOCK);

        product(
            createShop(ShopStatus.PENDING, true),
            category,
            "pending",
            "50"
        );

        product(
            createShop(ShopStatus.SUSPENDED, true),
            category,
            "suspended",
            "60"
        );

        product(
            createShop(ShopStatus.APPROVED, false),
            category,
            "disabled",
            "70"
        );

        product(
            shop,
            createCategory(false),
            "inactive-category",
            "80"
        );

        assertIds(
            search(null, null, false, false, "price-asc", 0),
            available, empty
        );

        assertIds(
            search(null, null, false, true, "price-asc", 0),
            available
        );
    }

    @Test
    void combinesCategoryShopRatingStockDiscountAndPriceFilters() {
        Product target = product("combined", "100");
        target.setAverageRating(new BigDecimal("4.50"));

        Product lowRating = product("low-rating", "100");
        lowRating.setAverageRating(new BigDecimal("3.00"));

        Product empty = product("empty", "100");
        empty.setAverageRating(new BigDecimal("5.00"));
        empty.setStock(0);

        Product otherCategory = product(
            shop, createCategory(true), "other-category", "100"
        );
        otherCategory.setAverageRating(new BigDecimal("5.00"));

        Product otherShop = product(
            createShop(ShopStatus.APPROVED, true),
            category,
            "other-shop",
            "100"
        );
        otherShop.setAverageRating(new BigDecimal("5.00"));

        promotion(null, null, "50");

        Page<Product> page = productRepository.searchPublic(
            prefix, prefix, category.getId(), shop.getId(),
            money("45"), money("55"), money("4"),
            true, true, now, "price-asc", PageRequest.of(0, 20)
        );

        assertIds(page, target);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void normalizesNegativePricesAndReturnsEmptyForReversedRanges() {
        Product free = product("free", "0");
        Product middle = product("middle", "40");
        product("expensive", "80");

        assertIds(
            search("-10", "40", false, false, "price-asc", 0),
            free, middle
        );

        Page<Product> reversed = search(
            "60", "30", false, false, "price-asc", 0
        );

        assertThat(reversed.getContent()).isEmpty();
        assertThat(reversed.getTotalElements()).isZero();

        assertIds(
            search(null, "-1", false, false, "price-asc", 0),
            free
        );
    }

    @Test
    void clampsRatingAndKeepsOtherSortModes() {
        Product first = product("first", "100");
        first.setSoldCount(20);
        first.setFavoriteCount(2);
        first.setAverageRating(money("5"));
        first.setCreatedAt(now.minusDays(1));

        Product second = product("second", "100");
        second.setSoldCount(10);
        second.setFavoriteCount(10);
        second.setAverageRating(money("4"));
        second.setCreatedAt(now);

        assertIds(
            search(null, null, false, false, "bestselling", 0),
            first, second
        );

        assertIds(
            search(null, null, false, false, "newest", 0),
            second, first
        );

        assertIds(
            search(null, null, false, false, "favorite", 0),
            second, first
        );

        assertIds(
            search(null, null, false, false, "rating", 0),
            first, second
        );

        Page<Product> highestRating = productRepository.searchPublic(
            prefix, prefix, null, null, null, null, money("10"),
            false, false, now, "rating", PageRequest.of(0, 20)
        );

        assertIds(highestRating, first);

        Page<Product> negativeRating = productRepository.searchPublic(
            prefix, prefix, null, null, null, null, money("-1"),
            false, false, now, "rating", PageRequest.of(0, 20)
        );

        assertIds(negativeRating, first, second);
    }

    @Test
    void catalogAndDetailUseTheSameBasePriceAsOptionPricing() {
        Product product = product("service-price", "100");
        promotion(shop, product, "25");

        ProductSize size = new ProductSize();
        size.setProduct(product);
        size.setCode("M");
        size.setExtraPrice(money("2.50"));
        size.setActive(true);
        entityManager.persist(size);

        Page<Product> page = catalogService.search(
            prefix,
            category.getId(),
            shop.getId(),
            "unknown-sort",
            -1,
            money("74"),
            money("76"),
            null,
            true,
            true
        );

        assertIds(page, product);
        assertThat(page.getNumber()).isZero();
        assertThat(page.getSize()).isEqualTo(20);

        Product listed = page.getContent().getFirst();
        Product detail = catalogService.getPublicProduct(product.getId());

        assertThat(pricingService.price(listed).salePrice())
            .isEqualByComparingTo("75.00");

        assertThat(pricingService.price(detail).salePrice())
            .isEqualByComparingTo("75.00");

        assertThat(pricingService.unitPrice(detail, size.getId(), List.of()))
            .isEqualByComparingTo("77.50");
    }

    private Page<Product> search(
            String min,
            String max,
            boolean discountedOnly,
            boolean inStockOnly,
            String sort,
            int page) {

        return productRepository.searchPublic(
            prefix,
            prefix,
            null,
            null,
            money(min),
            money(max),
            null,
            discountedOnly,
            inStockOnly,
            now,
            sort,
            PageRequest.of(page, 20)
        );
    }

    private Product product(String label, String price) {
        return product(shop, category, label, price);
    }

    private Product product(
            Shop ownerShop,
            Category productCategory,
            String label,
            String price) {

        Product product = new Product();
        product.setShop(ownerShop);
        product.setCategory(productCategory);
        product.setName(prefix + " " + label);
        product.setSlug(prefix + "-" + (++sequence));
        product.setSku(prefix + "-" + sequence);
        product.setDescription("Catalog pricing regression test");
        product.setPrice(money(price));
        product.setStock(10);
        product.setStatus(ProductStatus.ACTIVE);
        entityManager.persist(product);

        return product;
    }

    private Promotion promotion(
            Shop promotionShop,
            Product product,
            String percent) {

        Promotion promotion = new Promotion();
        promotion.setName(prefix + " promotion");
        promotion.setType(PromotionType.PRODUCT_PERCENT);
        promotion.setShop(promotionShop);
        promotion.setProduct(product);
        promotion.setDiscountValue(money(percent));
        promotion.setStartsAt(now.minusDays(1));
        promotion.setEndsAt(now.plusDays(1));
        promotion.setActive(true);
        entityManager.persist(promotion);

        return promotion;
    }

    private Category createCategory(boolean active) {
        Category created = new Category();
        created.setName("Catalog " + UUID.randomUUID());
        created.setSlug("catalog-" + UUID.randomUUID());
        created.setActive(active);
        entityManager.persist(created);

        return created;
    }

    private Shop createShop(ShopStatus status, boolean enabled) {
        String token = UUID.randomUUID().toString().replace("-", "");

        User owner = new User();
        owner.setUsername("catalog-" + token);
        owner.setEmail(token + "@catalog.test");
        owner.setPasswordHash("unused-test-password");
        owner.setFullName("Catalog vendor");
        owner.setRole(Role.VENDOR);
        owner.setEnabled(true);
        entityManager.persist(owner);

        Shop created = new Shop();
        created.setOwner(owner);
        created.setName("Catalog shop " + token);
        created.setSlug("catalog-shop-" + token);
        created.setStatus(status);
        created.setEnabled(enabled);
        entityManager.persist(created);

        return created;
    }

    private void assertIds(Page<Product> page, Product... products) {
        Long[] ids = java.util.Arrays.stream(products)
            .map(Product::getId)
            .toArray(Long[]::new);

        assertThat(page.getContent()).extracting(Product::getId)
            .containsExactly(ids);
    }

    private BigDecimal money(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}