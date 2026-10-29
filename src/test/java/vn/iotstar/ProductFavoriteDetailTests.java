package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
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
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductFavoriteDetailTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private ShopRepository shops;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void detailShowsFavoriteCountAndCustomerCanToggleWithoutReload() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User vendor = saveUser("favorite-vendor-" + suffix, Role.VENDOR);
        User customer = saveUser("favorite-user-" + suffix, Role.USER);

        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Favorite Test");
        shop.setSlug("favorite-shop-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shops.save(shop);

        Category category = new Category();
        category.setName("Trà sữa test");
        category.setSlug("favorite-category-" + suffix);
        category = categories.save(category);

        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName("Trà sữa yêu thích");
        product.setSlug("favorite-tea-" + suffix);
        product.setSku("FAVORITE-" + suffix);
        product.setDescription("Sản phẩm dùng để kiểm thử lượt thích.");
        product.setPrice(new BigDecimal("30000"));
        product.setStock(10);
        product = products.save(product);

        String guestHtml = mvc.perform(get("/products/{id}", product.getId()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(guestHtml.contains("product-image-favorite-guest"));

        String customerHtml = mvc.perform(get("/products/{id}", product.getId())
                .with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(customerHtml.contains("data-favorite-toggle-url"));
        assertTrue(customerHtml.contains("data-favorite-count"));
        assertTrue(customerHtml.contains("name=\"_csrf\""));

        mvc.perform(post("/user/favorites/{id}/toggle", product.getId())
                .with(user(customer.getUsername()).roles("USER")).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.liked").value(true))
            .andExpect(jsonPath("$.count").value(1));
        mvc.perform(post("/user/favorites/{id}/toggle", product.getId())
                .with(user(customer.getUsername()).roles("USER")).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.liked").value(false))
            .andExpect(jsonPath("$.count").value(0));
    }

    private User saveUser(String username, Role role) {
        User account = new User();
        account.setUsername(username);
        account.setEmail(username + "@utetra.test");
        account.setPasswordHash("encoded-password");
        account.setFullName(username);
        account.setRole(role);
        account.setEnabled(true);
        return users.save(account);
    }
}
