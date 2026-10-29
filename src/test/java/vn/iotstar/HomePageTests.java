package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HomePageTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private ShopRepository shops;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private PromotionRepository promotions;
    @Autowired private CatalogService catalog;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void bestSellersAreStrictlyAboveTenAndOrderedByUnitsSold() throws Exception {
        Shop shop = saveShop();
        Category active = saveCategory("home-active", true);
        Category inactive = saveCategory("home-inactive", false);
        saveProduct("HOME-10", shop, active, 10);
        Product lower = saveProduct("HOME-11", shop, active, 11);
        Product higher = saveProduct("HOME-25", shop, active, 25);
        saveProduct("HOME-HIDDEN", shop, inactive, 40);

        assertEquals(List.of(higher.getId(), lower.getId()),
            catalog.homeBestSellers().stream().map(Product::getId).toList());

        String home = mvc.perform(get("/")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String bestSellerSection = home.substring(home.indexOf("id=\"ban-chay\""), home.indexOf("home-categories"));
        assertTrue(bestSellerSection.contains("HOME-25"));
        assertTrue(bestSellerSection.contains("HOME-11"));
        assertTrue(bestSellerSection.indexOf("HOME-25") < bestSellerSection.indexOf("HOME-11"));
        assertFalse(bestSellerSection.contains("HOME-10"));
        assertFalse(bestSellerSection.contains("HOME-HIDDEN"));
    }

    @Test
    void homepageKeepsOnlyEightHighestSellingDrinks() {
        Shop shop = saveShop();
        Category category = saveCategory("home-top-eight", true);
        for (int i = 0; i < 10; i++) saveProduct("HOME-TOP-" + i, shop, category, 11 + i);

        List<Product> bestSellers = catalog.homeBestSellers();
        assertEquals(8, bestSellers.size());
        assertEquals(20, bestSellers.getFirst().getSoldCount());
        assertEquals(13, bestSellers.getLast().getSoldCount());
    }

    @Test
    void guestSeesRealPromotionsCardsAndShopDirectory() throws Exception {
        Shop shop = saveShop();
        Category category = saveCategory("home-category", true);
        Product product = saveProduct("HOME-PROMO", shop, category, 12);
        Promotion discount = new Promotion();
        discount.setName("Ưu đãi trà sữa");
        discount.setType(PromotionType.PRODUCT_PERCENT);
        discount.setDiscountValue(new BigDecimal("20"));
        discount.setShop(shop);
        discount.setProduct(product);
        discount.setStartsAt(LocalDateTime.now().minusDays(1));
        discount.setEndsAt(LocalDateTime.now().plusDays(1));
        promotions.save(discount);

        String home = mvc.perform(get("/")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertTrue(home.contains("Bán chạy trên 10 ly"));
        assertTrue(home.contains("Món ngon, giá tốt"));
        assertTrue(home.contains("Xem món đang giảm giá"));
        assertTrue(home.contains("Sản phẩm nổi bật"));
        assertTrue(home.contains("Cửa hàng nổi bật"));
        assertTrue(home.contains("-20%"));
        assertTrue(home.contains("36.000 ₫"));
        assertTrue(home.contains("45.000 ₫"));
        assertFalse(home.contains("product-card-action"));
        assertFalse(home.contains("bi bi-arrow-down"));
        assertFalse(home.contains("bi-box-arrow-in-right"));
        assertTrue(home.contains("COD"));
        assertFalse(home.contains("Đặt COD, theo dõi đơn hàng"));

        User customer = new User();
        customer.setUsername("home-customer");
        customer.setEmail("home-customer@utetra.test");
        customer.setPasswordHash("encoded-password");
        customer.setFullName("Khách hàng");
        customer.setRole(Role.USER);
        customer.setEnabled(true);
        users.save(customer);
        String userHome = mvc.perform(get("/").with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(userHome.contains("Bán chạy trên 10 ly"));
        assertTrue(userHome.contains("Giỏ hàng"));

        String directory = mvc.perform(get("/shops")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertTrue(directory.contains("Tìm cửa hàng của bạn"));
        assertTrue(directory.contains("UTETra Home"));
    }

    private Shop saveShop() {
        User owner = new User();
        owner.setUsername("home-vendor");
        owner.setEmail("home-vendor@utetra.test");
        owner.setPasswordHash("encoded-password");
        owner.setFullName("Chủ shop");
        owner.setRole(Role.VENDOR);
        owner.setEnabled(true);
        users.save(owner);

        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName("UTETra Home");
        shop.setSlug("utetra-home");
        shop.setStatus(ShopStatus.APPROVED);
        return shops.save(shop);
    }

    private Category saveCategory(String slug, boolean active) {
        Category category = new Category();
        category.setName(slug);
        category.setSlug(slug);
        category.setActive(active);
        return categories.save(category);
    }

    private Product saveProduct(String sku, Shop shop, Category category, long soldCount) {
        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName(sku);
        product.setSlug(sku.toLowerCase());
        product.setSku(sku);
        product.setDescription("Một ly trà sữa thơm ngon cho ngày mới.");
        product.setPrice(new BigDecimal("45000"));
        product.setStock(20);
        product.setSoldCount(soldCount);
        return products.save(product);
    }
}
