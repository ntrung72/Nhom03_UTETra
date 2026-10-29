package vn.iotstar;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.CartService;
import vn.iotstar.service.SavedRecipeService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class SavedRecipeTests {
    @Autowired private SavedRecipeService recipes;
    @Autowired private CartService carts;
    @Autowired private UserRepository users;
    @Autowired private ShopRepository shops;
    @Autowired private CategoryRepository categories;
    @Autowired private ProductRepository products;
    @Autowired private ProductSizeRepository sizes;
    @Autowired private ToppingRepository toppings;
    @Autowired private ProductToppingRepository productToppings;
    @Autowired private WebApplicationContext context;

    private User customer;
    private User other;
    private Product product;
    private ProductSize size;
    private Topping topping;

    @BeforeEach
    void setUp() {
        customer = createUser("recipe-customer", Role.USER);
        other = createUser("recipe-other", Role.USER);
        User vendor = createUser("recipe-vendor", Role.VENDOR);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra Test Recipes");
        shop.setSlug("utetra-test-recipes");
        shop.setStatus(ShopStatus.APPROVED);
        shop = shops.save(shop);
        Category category = new Category();
        category.setName("Trà sữa công thức");
        category.setSlug("tra-sua-cong-thuc");
        category = categories.save(category);
        product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName("Trà sữa Matcha");
        product.setSlug("tra-sua-matcha-recipes");
        product.setSku("RECIPES-001");
        product.setDescription("Món dùng kiểm thử công thức.");
        product.setPrice(new BigDecimal("30000"));
        product.setStock(4);
        product = products.save(product);
        size = new ProductSize();
        size.setProduct(product);
        size.setCode("L");
        size.setExtraPrice(new BigDecimal("7000"));
        size = sizes.save(size);
        topping = new Topping();
        topping.setName("Topping riêng test recipe");
        topping.setPrice(new BigDecimal("5000"));
        topping = toppings.save(topping);
        ProductTopping link = new ProductTopping();
        link.setProduct(product);
        link.setTopping(topping);
        productToppings.save(link);
    }

    @Test
    void savesOptionsAndAddsAtCurrentPrices() {
        SavedRecipe recipe = recipes.save(customer, product.getId(), "Matcha của tôi", size.getId(),
            SugarLevel.PERCENT_50, IceLevel.LESS_ICE, List.of(topping.getId()));
        assertEquals(1, recipes.list(customer, 0).getTotalElements());
        assertEquals(0, new BigDecimal("42000").compareTo(recipes.list(customer, 0).getContent().getFirst().currentUnitPrice()));
        recipes.rename(customer, recipe.getId(), "Matcha ít ngọt");
        product.setPrice(new BigDecimal("35000"));
        size.setExtraPrice(new BigDecimal("8000"));
        topping.setPrice(new BigDecimal("6000"));
        recipes.addToCart(customer, recipe.getId(), 2);
        CartItem item = carts.get(customer).getItems().getFirst();
        assertEquals("Matcha ít ngọt", recipes.list(customer, 0).getContent().getFirst().recipe().getName());
        assertEquals(2, item.getQuantity());
        assertEquals("L", item.getSize());
        assertEquals(SugarLevel.PERCENT_50, item.getSugarLevel());
        assertEquals(IceLevel.LESS_ICE, item.getIceLevel());
        assertEquals(0, new BigDecimal("49000").compareTo(item.getUnitPrice()));
    }

    @Test
    void rejectsUnavailableOptionsAndAggregateStock() {
        SavedRecipe recipe = recipes.save(customer, product.getId(), null, size.getId(),
            SugarLevel.PERCENT_70, IceLevel.NORMAL, List.of(topping.getId()));
        carts.add(customer, product.getId(), 3, size.getId(), SugarLevel.PERCENT_100, IceLevel.NORMAL, List.of());
        assertThrows(IllegalArgumentException.class, () -> recipes.addToCart(customer, recipe.getId(), 2));
        topping.setActive(false);
        assertFalse(recipes.list(customer, 0).getContent().getFirst().isAvailable());
        assertThrows(IllegalArgumentException.class, () -> recipes.addToCart(customer, recipe.getId(), 1));
        topping.setActive(true);
        size.setActive(false);
        assertThrows(IllegalArgumentException.class, () -> recipes.addToCart(customer, recipe.getId(), 1));
        size.setActive(true);
        product.setStock(0);
        assertThrows(IllegalArgumentException.class, () -> recipes.addToCart(customer, recipe.getId(), 1));
    }

    @Test
    void cannotModifyAnotherUsersRecipe() {
        SavedRecipe recipe = recipes.save(customer, product.getId(), "Riêng tư", size.getId(),
            SugarLevel.PERCENT_50, IceLevel.NO_ICE, List.of());
        assertEquals(0, recipes.list(other, 0).getTotalElements());
        assertThrows(IllegalArgumentException.class, () -> recipes.addToCart(other, recipe.getId(), 1));
        assertThrows(IllegalArgumentException.class, () -> recipes.rename(other, recipe.getId(), "Đổi tên"));
        assertThrows(IllegalArgumentException.class, () -> recipes.delete(other, recipe.getId()));
        recipes.delete(customer, recipe.getId());
        assertEquals(0, recipes.list(customer, 0).getTotalElements());
    }

    @Test
    void rendersSavedRecipeList() throws Exception {
        recipes.save(customer, product.getId(), "Matcha buổi sáng", size.getId(),
            SugarLevel.PERCENT_50, IceLevel.LESS_ICE, List.of(topping.getId()));
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/user/recipes").with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("account-hero")))
            .andExpect(content().string(containsString("account-nav")))
            .andExpect(content().string(containsString("Matcha buổi sáng")));
    }

    @Test
    void productPageOffersOnlyCurrentCustomersUsableRecipes() throws Exception {
        recipes.save(customer, product.getId(), "My quick matcha", size.getId(),
            SugarLevel.PERCENT_50, IceLevel.LESS_ICE, List.of(topping.getId()));
        recipes.save(other, product.getId(), "Another customers recipe", size.getId(),
            SugarLevel.PERCENT_70, IceLevel.NORMAL, List.of());
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/products/{id}", product.getId())
                .with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("My quick matcha")))
            .andExpect(content().string(containsString("data-recipe-size-id=")))
            .andExpect(content().string(not(containsString("Another customers recipe"))));
        topping.setActive(false);
        mvc.perform(get("/products/{id}", product.getId())
                .with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("My quick matcha"))));
    }

    @Test
    void cartPreviewShowsSavedOptionsAndCombinedQuantity() throws Exception {
        SavedRecipe recipe = recipes.save(customer, product.getId(), "Matcha buổi sáng", size.getId(),
            SugarLevel.PERCENT_70, IceLevel.LESS_ICE, List.of(topping.getId()));
        recipes.addToCart(customer, recipe.getId(), 1);
        recipes.addToCart(customer, recipe.getId(), 1);

        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/cart").with(user(customer.getUsername()).roles("USER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].quantity").value(2))
            .andExpect(jsonPath("$.items[0].size").value("L"))
            .andExpect(jsonPath("$.items[0].sugarLevelLabel").value("70% đường"))
            .andExpect(jsonPath("$.items[0].iceLevelLabel").value("Ít đá"))
            .andExpect(jsonPath("$.items[0].toppings").value(topping.getName()))
            .andExpect(jsonPath("$.items[0].lineTotal").value(84000));
    }

    private User createUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("test-password-hash");
        user.setFullName(username);
        user.setRole(role);
        user.setEnabled(true);
        return users.save(user);
    }
}
