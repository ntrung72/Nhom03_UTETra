package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.PromotionRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.CatalogService;
import vn.iotstar.controller.ProductController.FilterChip;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class AdvancedProductFilterTests {
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PromotionRepository promotionRepository;
    @Autowired private CatalogService catalogService;
    @Autowired private WebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void advancedFilterFormRendersAndAcceptsAllParameters() throws Exception {
        mockMvc.perform(get("/products")
                .param("minPrice", "20000")
                .param("maxPrice", "80000")
                .param("minRating", "4.5")
                .param("discounted", "true")
                .param("inStock", "true"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Đánh giá tối thiểu")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Chỉ sản phẩm còn hàng")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("catalog-sidebar")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("catalog-results")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Kết quả tìm kiếm")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Sắp xếp theo")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Xóa toàn bộ bộ lọc")));
    }

    @Test
    void invalidPriceRangeKeepsValuesAndExplainsTheError() throws Exception {
        var result = mockMvc.perform(get("/products")
                .param("minPrice", "90000")
                .param("maxPrice", "20000")
                .param("sort", "newest"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(
                "Giá tối thiểu không được lớn hơn giá tối đa.")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Khoảng giá chưa hợp lệ")))
            .andReturn();
        String html = result.getResponse().getContentAsString();
        assertTrue(html.contains("name=\"minPrice\" type=\"number\""));
        assertTrue(html.contains("name=\"maxPrice\" type=\"number\""));
        assertTrue(html.contains("value=\"90000\""));
        assertTrue(html.contains("value=\"20000\""));
        assertTrue(html.contains("catalog-filter-error"));
        assertTrue(html.contains("href=\"#minPrice\""));
        assertThrows(IllegalArgumentException.class, () -> catalogService.search(
            null, null, null, "newest", 0, new BigDecimal("90000"),
            new BigDecimal("20000"), null, false, false));
    }

    @Test
    void negativePriceIsRejectedByPageAndService() throws Exception {
        String html = mockMvc.perform(get("/products").param("minPrice", "-1000")
                .param("maxPrice", "45000"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Giá lọc không được âm.")))
            .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("value=\"-1000\""));
        assertThrows(IllegalArgumentException.class, () -> catalogService.search(
            null, null, null, "price-asc", 0, new BigDecimal("-1000"),
            new BigDecimal("45000"), null, false, false));
    }

    @Test
    void noResultsExplainsHowToBroadenTheSearch() throws Exception {
        String html = mockMvc.perform(get("/products")
                .param("q", "khong-co-san-pham-" + UUID.randomUUID()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Chưa tìm thấy món phù hợp"));
        assertTrue(html.contains("Xóa bộ lọc"));
        assertTrue(html.contains("Kết quả tìm kiếm"));
    }

    @Test
    void filtersAndOrdersByPromotedPriceRatherThanOriginalPrice() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Category category = saveCategory("sale-category-" + suffix);
        Shop shop = saveShop("sale-shop-" + suffix);
        Product cheap = saveProduct("SALE-CHEAP-" + suffix, shop, category, "39000", "4.5", 10);
        Product promoted = saveProduct("SALE-PROMO-" + suffix, shop, category, "50000", "4.5", 10);
        Product regular = saveProduct("SALE-REGULAR-" + suffix, shop, category, "42000", "4.5", 10);
        saveProduct("SALE-EXPENSIVE-" + suffix, shop, category, "60000", "4.5", 10);
        Promotion promotion = savePromotion("Giảm 20%", promoted,
            LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));
        promotion.setDiscountValue(new BigDecimal("20"));
        productRepository.flush();
        promotionRepository.flush();

        var ascending = catalogService.search(null, category.getId(), null, "price-asc", 0,
            new BigDecimal("30000"), new BigDecimal("45000"), null, false, false);
        assertEquals(3, ascending.getTotalElements());
        assertEquals(List.of(cheap.getId(), promoted.getId(), regular.getId()),
            ascending.getContent().stream().map(Product::getId).toList());
        var descending = catalogService.search(null, category.getId(), null, "price-desc", 0,
            new BigDecimal("30000"), new BigDecimal("45000"), null, false, false);
        assertEquals(List.of(regular.getId(), promoted.getId(), cheap.getId()),
            descending.getContent().stream().map(Product::getId).toList());
        var exact = catalogService.search(null, category.getId(), null, "price-asc", 0,
            new BigDecimal("40000"), new BigDecimal("40000"), null, true, false);
        assertEquals(List.of(promoted.getId()), exact.getContent().stream().map(Product::getId).toList());

        String html = mockMvc.perform(get("/products").param("category", category.getId().toString())
                .param("minPrice", "30000").param("maxPrice", "45000")
                .param("sort", "price-asc"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains(promoted.getName()));
        assertTrue(html.contains("40.000"));
    }

    @Test
    void priceBoundaryUsesTheSameDiscountRoundingAsProductCards() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Category category = saveCategory("round-category-" + suffix);
        Shop shop = saveShop("round-shop-" + suffix);
        Product product = saveProduct("ROUND-" + suffix, shop, category, "100.01", "4.5", 10);
        Promotion promotion = savePromotion("Giảm 50%", product,
            LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));
        promotion.setDiscountValue(new BigDecimal("50"));
        productRepository.flush();
        promotionRepository.flush();

        var exact = catalogService.search(null, category.getId(), null, "price-asc", 0,
            new BigDecimal("50.00"), new BigDecimal("50.00"), null, false, false);
        assertEquals(List.of(product.getId()), exact.getContent().stream().map(Product::getId).toList());
    }

    @Test
    void shopPromotionUsesDatabasePaginationAndKeepsFilterLinks() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Category category = saveCategory("sale-page-category-" + suffix);
        Shop shop = saveShop("sale-page-shop-" + suffix);
        for (int i = 0; i < 21; i++) {
            saveProduct("SALE-PAGE-" + suffix + "-" + i, shop, category, "50000", "4.5", 10);
        }
        Promotion promotion = new Promotion();
        promotion.setName("Giảm cả shop");
        promotion.setType(PromotionType.PRODUCT_PERCENT);
        promotion.setDiscountValue(new BigDecimal("20"));
        promotion.setShop(shop);
        promotion.setStartsAt(LocalDateTime.now().minusDays(1));
        promotion.setEndsAt(LocalDateTime.now().plusDays(1));
        promotionRepository.save(promotion);
        productRepository.flush();
        promotionRepository.flush();

        var first = catalogService.search(null, category.getId(), shop.getId(), "price-asc", 0,
            new BigDecimal("40000"), new BigDecimal("40000"), null, true, true);
        var second = catalogService.search(null, category.getId(), shop.getId(), "price-asc", 1,
            new BigDecimal("40000"), new BigDecimal("40000"), null, true, true);
        assertEquals(20, first.getNumberOfElements());
        assertEquals(1, second.getNumberOfElements());
        assertEquals(21, first.getTotalElements());
        assertEquals(2, first.getTotalPages());

        String html = mockMvc.perform(get("/products").param("category", category.getId().toString())
                .param("shop", shop.getId().toString()).param("minPrice", "40000")
                .param("maxPrice", "40000").param("discounted", "true")
                .param("inStock", "true").param("sort", "price-asc"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("page=1"));
        assertTrue(html.contains("minPrice=40000"));
        assertTrue(html.contains("maxPrice=40000"));
        assertTrue(html.contains("discounted=true"));
        assertTrue(html.contains("inStock=true"));
        assertTrue(html.contains("sort=price-asc"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void removingOneChipPreservesOtherFiltersAndSort() throws Exception {
        var result = mockMvc.perform(get("/products")
                .param("q", "trà sữa")
                .param("minPrice", "20000")
                .param("maxPrice", "80000")
                .param("discounted", "true")
                .param("sort", "newest"))
            .andExpect(status().isOk()).andReturn();
        List<FilterChip> chips = (List<FilterChip>) result.getModelAndView().getModel().get("filterChips");
        assertEquals(4, chips.size());
        String removeMin = chips.stream().filter(chip -> chip.label().startsWith("Giá từ"))
            .findFirst().orElseThrow().removeUrl();
        assertFalse(removeMin.contains("minPrice="));
        assertTrue(removeMin.contains("maxPrice=80000"));
        assertTrue(removeMin.contains("discounted=true"));
        assertTrue(removeMin.contains("sort=newest"));
        assertTrue(removeMin.contains("q=tr%C3%A0%20s%E1%BB%AFa"));
        assertEquals("/products?sort=newest", result.getModelAndView().getModel().get("clearFiltersUrl"));
    }

    @Test
    void paginationKeepsTwentyProductsPerPage() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Category category = saveCategory("page-category-" + suffix);
        Shop shop = saveShop("page-shop-" + suffix);
        for (int i = 0; i < 21; i++) {
            saveProduct("PAGE-" + suffix + "-" + i, shop, category, "30000", "4.0", 10);
        }
        productRepository.flush();
        var first = catalogService.search(null, category.getId(), null, "newest", 0);
        var second = catalogService.search(null, category.getId(), null, "newest", 1);
        assertEquals(20, first.getNumberOfElements());
        assertEquals(1, second.getNumberOfElements());
        assertEquals(21, first.getTotalElements());
        assertEquals(2, first.getTotalPages());
        assertFalse(first.getContent().stream().anyMatch(product ->
            product.getId().equals(second.getContent().getFirst().getId())));
    }

    @Test
    void filtersByPriceRatingStockAndOnlyRunningProductPromotion() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Category category = saveCategory("advanced-category-" + suffix);
        Shop shop = saveShop("advanced-shop-" + suffix);
        Product affordable = saveProduct("ADV-A-" + suffix, shop, category, "30000", "4.70", 10);
        Product outOfStock = saveProduct("ADV-B-" + suffix, shop, category, "50000", "3.50", 0);
        Product promoted = saveProduct("ADV-C-" + suffix, shop, category, "70000", "4.90", 10);
        savePromotion("Running promotion", promoted, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1));
        savePromotion("Expired promotion", affordable, LocalDateTime.now().minusDays(3), LocalDateTime.now().minusDays(1));
        productRepository.flush();
        promotionRepository.flush();

        List<Long> matchingAll = catalogService.search(null, category.getId(), null, "price-asc", 0,
                new BigDecimal("40000"), new BigDecimal("80000"), new BigDecimal("4"), false, true)
            .getContent().stream().map(Product::getId).toList();
        assertEquals(List.of(promoted.getId()), matchingAll);

        List<Long> discounted = catalogService.search(null, category.getId(), null, "price-asc", 0,
                null, null, null, true, false)
            .getContent().stream().map(Product::getId).toList();
        assertEquals(List.of(promoted.getId()), discounted);

        List<Long> stockFiltered = catalogService.search(null, category.getId(), null, "price-asc", 0,
                null, null, null, false, true)
            .getContent().stream().map(Product::getId).toList();
        assertEquals(List.of(affordable.getId(), promoted.getId()), stockFiltered);
        assertEquals(0, outOfStock.getStock());
    }

    private User saveUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(username);
        user.setRole(Role.VENDOR);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private Shop saveShop(String slug) {
        Shop shop = new Shop();
        shop.setOwner(saveUser("vendor-" + slug));
        shop.setName(slug);
        shop.setSlug(slug);
        shop.setStatus(ShopStatus.APPROVED);
        return shopRepository.save(shop);
    }

    private Category saveCategory(String slug) {
        Category category = new Category();
        category.setName(slug);
        category.setSlug(slug);
        category.setActive(true);
        return categoryRepository.save(category);
    }

    private Product saveProduct(String sku, Shop shop, Category category, String price, String rating, int stock) {
        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName(sku);
        product.setSlug(sku.toLowerCase());
        product.setSku(sku);
        product.setDescription("Sản phẩm kiểm thử bộ lọc nâng cao.");
        product.setPrice(new BigDecimal(price));
        product.setAverageRating(new BigDecimal(rating));
        product.setStock(stock);
        return productRepository.save(product);
    }

    private Promotion savePromotion(String name, Product product, LocalDateTime startsAt, LocalDateTime endsAt) {
        Promotion promotion = new Promotion();
        promotion.setName(name);
        promotion.setType(PromotionType.PRODUCT_PERCENT);
        promotion.setDiscountValue(new BigDecimal("10"));
        promotion.setShop(product.getShop());
        promotion.setProduct(product);
        promotion.setStartsAt(startsAt);
        promotion.setEndsAt(endsAt);
        promotion.setActive(true);
        return promotionRepository.save(promotion);
    }
}
