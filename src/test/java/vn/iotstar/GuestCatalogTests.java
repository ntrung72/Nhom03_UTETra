package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.CatalogService;
import vn.iotstar.util.SlugUtils;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GuestCatalogTests {
    @Autowired WebApplicationContext context;
    @Autowired CatalogService catalog;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired ShopRepository shops;
    @Autowired UserRepository users;
    @Autowired ProductSizeRepository sizes;
    @Autowired ProductToppingRepository productToppings;
    @Autowired ToppingRepository toppings;
    MockMvc mvc;
    Shop shop;
    Category category;
    Product tea;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        shop = shop(ShopStatus.APPROVED);
        category = new Category();
        category.setName("Trà sữa"); category.setSlug("tra-sua-" + UUID.randomUUID());
        category = categories.save(category);
        tea = product(shop, category, "Trà sữa trân châu", "35000", 12);
        tea.setAverageRating(new BigDecimal("4.50")); tea.setReviewCount(2);
        tea.setFavoriteCount(2); tea.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        products.saveAndFlush(tea);
    }

    @Test
    void guestCanRenderHomeWithStrictBestSellerThresholdAndOrder() throws Exception {
        var best = product(shop, category, "Bán chạy", "40000", 50);
        product(shop, category, "Đúng mười lượt", "30000", 10);
        var result = mvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("home"))
            .andReturn();
        assertThat((List<?>) result.getModelAndView().getModel().get("bestSellers"))
            .extracting("id").containsExactly(best.getId(), tea.getId());
    }

    @Test
    void publicCatalogExcludesHiddenInactiveUnapprovedAndDisabledData() {
        var hidden = product(shop, category, "Ẩn", "10000", 100);
        hidden.setStatus(ProductStatus.HIDDEN); products.save(hidden);
        product(shop(ShopStatus.PENDING), category, "Chờ duyệt", "10000", 100);
        product(shop(ShopStatus.REJECTED), category, "Từ chối", "10000", 100);
        var disabled = shop(ShopStatus.APPROVED); disabled.setEnabled(false); shops.save(disabled);
        product(disabled, category, "Shop khóa", "10000", 100);
        var inactive = new Category(); inactive.setName("Ngừng bán");
        inactive.setSlug(UUID.randomUUID().toString()); inactive.setActive(false); categories.save(inactive);
        product(shop, inactive, "Danh mục ẩn", "10000", 100);
        assertThat(catalog.search(null, null, null, null, 0).getContent()).extracting(Product::getId)
            .containsExactly(tea.getId());
        assertThat(catalog.homeBestSellers()).extracting(Product::getId).containsExactly(tea.getId());
        assertThat(catalog.shops()).extracting(Shop::getId).containsExactly(shop.getId());
        assertThat(catalog.categories()).extracting(Category::getId).containsExactly(category.getId());
    }

    @Test
    void combinedFiltersSupportVietnameseWithoutAccents() throws Exception {
        product(shop, category, "Trà sữa cao cấp", "60000", 30);
        product(shop, category, "Cà phê", "35000", 30);
        mvc.perform(get("/products").param("q", " tra sua ")
                .param("category", category.getId().toString()).param("shop", shop.getId().toString())
                .param("minPrice", "30000").param("maxPrice", "40000")
                .param("minRating", "4").param("inStock", "true"))
            .andExpect(status().isOk()).andExpect(view().name("products/list"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Trà sữa trân châu")));
        assertThat(catalog.search("tra sua", category.getId(), shop.getId(), "bestselling", 0,
            new BigDecimal("30000"), new BigDecimal("40000"), new BigDecimal("4"), false, true)
            .getContent()).extracting(Product::getId).containsExactly(tea.getId());
    }

    @Test
    void stockFilterHidesSoldOutButDetailStillShowsStockState() throws Exception {
        tea.setStock(0); products.saveAndFlush(tea);
        assertThat(catalog.search(null, null, null, null, 0, null, null, null, false, true)).isEmpty();
        mvc.perform(get("/products/" + tea.getId())).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Tạm hết hàng")));
    }

    @Test
    void supportsAllSixSortOrders() {
        var other = product(shop, category, "Trà khác", "50000", 40);
        other.setAverageRating(new BigDecimal("5")); other.setFavoriteCount(10);
        other.setCreatedAt(LocalDateTime.of(2026, 2, 1, 0, 0)); products.saveAndFlush(other);
        for (String sort : List.of("newest", "bestselling", "rating", "favorite", "price-desc")) {
            assertThat(catalog.search(null, null, null, sort, 0).getContent()).extracting(Product::getId)
                .as(sort).containsExactly(other.getId(), tea.getId());
        }
        assertThat(catalog.search(null, null, null, "price-asc", 0).getContent()).extracting(Product::getId)
            .containsExactly(tea.getId(), other.getId());
    }

    @Test
    void pagesContainAtMostTwentyAndNavigationPreservesEveryFilter() throws Exception {
        for (int i = 0; i < 24; i++) {
            var item = product(shop, category, "Trà sữa " + i, "35000", 12);
            item.setAverageRating(new BigDecimal("4.5")); products.save(item);
        }
        var first = catalog.search("tra sua", category.getId(), shop.getId(), "price-asc", 0);
        var second = catalog.search("tra sua", category.getId(), shop.getId(), "price-asc", 1);
        assertThat(first.getContent()).hasSize(20);
        assertThat(second.getContent()).hasSize(5).doesNotContainAnyElementsOf(first.getContent());
        assertThat(first.getTotalElements()).isEqualTo(25);
        String html = mvc.perform(get("/products").param("q", "tra sua")
            .param("category", category.getId().toString()).param("shop", shop.getId().toString())
            .param("minPrice", "0").param("maxPrice", "50000").param("minRating", "3")
            .param("discounted", "false").param("inStock", "true").param("sort", "price-asc"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("q=tra%20sua", "category=" + category.getId(), "shop=" + shop.getId(),
            "minPrice=0", "maxPrice=50000", "minRating=3", "discounted=false", "inStock=true",
            "sort=price-asc", "page=1");
    }

    @Test
    void invalidRangesAndOutOfRangePagesAreHandledWithoutServerErrors() {
        assertThat(catalog.search(null, null, null, "unknown", -10).getNumber()).isZero();
        assertThat(catalog.search(null, null, null, null, 999)).isEmpty();
        assertThat(catalog.search(null, null, null, null, 0, new BigDecimal("50000"),
            new BigDecimal("20000"), null, false, false)).isEmpty();
        assertThat(catalog.search(null, null, null, null, 0, new BigDecimal("-10"),
            null, new BigDecimal("-1"), false, false).getTotalElements()).isEqualTo(1);
        assertThat(catalog.search(null, null, null, null, 0, null,
            null, new BigDecimal("9"), false, false)).isEmpty();
    }

    @Test
    void discountFilterDoesNotClaimBasePricedProductsArePromoted() throws Exception {
        mvc.perform(get("/products").param("discounted", "true")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Không tìm thấy sản phẩm phù hợp")));
        assertThat(catalog.search(null, null, null, null, 0, null, null, null, true, false)).isEmpty();
    }

    @Test
    void detailRendersOnlyActiveOptionsAndEscapesDescriptions() throws Exception {
        var size = new ProductSize(); size.setProduct(tea); size.setCode("L");
        size.setExtraPrice(new BigDecimal("5000")); sizes.save(size);
        var inactive = new ProductSize(); inactive.setProduct(tea); inactive.setCode("X");
        inactive.setActive(false); sizes.save(inactive);
        var topping = new Topping(); topping.setName("Thạch trái cây");
        topping.setPrice(new BigDecimal("3000")); toppings.save(topping);
        var link = new ProductTopping(); link.setProduct(tea); link.setTopping(topping); productToppings.save(link);
        tea.setDescription("<script>alert(1)</script>"); products.saveAndFlush(tea);
        String html = mvc.perform(get("/products/" + tea.getId())).andExpect(status().isOk())
            .andExpect(view().name("products/detail")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("L (+5,000 ₫)", "Thạch trái cây", "Mức đường", "Mức đá",
            "&lt;script&gt;", "/images/logo.svg").doesNotContain("X (+", "<script>alert(1)</script>");
    }

    @Test
    void nonPublicProductAndShopUrlsReturn404() throws Exception {
        tea.setStatus(ProductStatus.HIDDEN); products.saveAndFlush(tea);
        mvc.perform(get("/products/" + tea.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/products/99999999")).andExpect(status().isNotFound());
        shop.setStatus(ShopStatus.PENDING); shops.saveAndFlush(shop);
        mvc.perform(get("/shops/" + shop.getSlug())).andExpect(status().isNotFound());
        mvc.perform(get("/shops/missing")).andExpect(status().isNotFound());
    }

    @Test
    void shopPageDisplaysPublicInformationAndOnlyItsOwnProducts() throws Exception {
        product(shop(ShopStatus.APPROVED), category, "Khác shop", "20000", 30);
        var result = mvc.perform(get("/shops/" + shop.getSlug())).andExpect(status().isOk())
            .andExpect(view().name("products/shop")).andReturn();
        var page = (Page<?>) result.getModelAndView().getModel().get("products");
        assertThat(page.getContent()).extracting("id").containsExactly(tea.getId());
        assertThat(result.getResponse().getContentAsString()).contains(shop.getName(), "01 Võ Văn Ngân")
            .doesNotContain("Khác shop");
    }

    @Test
    void publicApiUsesSafeDtosWithSharedPricingAndTwentyItemPages() throws Exception {
        mvc.perform(get("/api/products").param("q", "tra sua"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(20))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(tea.getId().intValue()))
            .andExpect(jsonPath("$.content[0].price.salePrice").value(35000.0))
            .andExpect(jsonPath("$.content[0].price.promoted").value(false))
            .andExpect(jsonPath("$.content[0].owner").doesNotExist())
            .andExpect(jsonPath("$.content[0].shop.owner").doesNotExist());
    }

    private Shop shop(ShopStatus status) {
        String key = UUID.randomUUID().toString();
        var owner = new User(); owner.setUsername("v" + key); owner.setEmail(key + "@test.vn");
        owner.setFullName("Chủ shop"); owner.setPasswordHash("test-only"); owner.setRole(Role.VENDOR);
        owner.setEnabled(true); users.save(owner);
        var result = new Shop(); result.setOwner(owner); result.setName("Shop " + key);
        result.setSlug(key); result.setStatus(status); result.setAddress("01 Võ Văn Ngân");
        return shops.save(result);
    }

    private Product product(Shop store, Category type, String name, String price, long sold) {
        var result = new Product(); result.setShop(store); result.setCategory(type); result.setName(name);
        result.setSlug(SlugUtils.toSlug(name)); result.setSku(UUID.randomUUID().toString());
        result.setDescription("Trà tươi mỗi ngày."); result.setPrice(new BigDecimal(price));
        result.setStock(10); result.setSoldCount(sold);
        return products.saveAndFlush(result);
    }
}
