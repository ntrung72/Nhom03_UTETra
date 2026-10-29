package vn.iotstar;

import java.math.BigDecimal;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.CartRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.security.CustomUserDetailsService;
import vn.iotstar.security.JwtService;
import vn.iotstar.service.CartService;
import vn.iotstar.service.EngagementService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class CustomerJwtApiTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private CartRepository cartRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private CartService cartService;
    @Autowired private EngagementService engagementService;
    @Autowired private CustomUserDetailsService userDetailsService;
    @Autowired private JwtService jwtService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void userAndVendorCanReadTheirOwnEmptyResourcesUsingBearerToken() throws Exception {
        for (Role role : new Role[] {Role.USER, Role.VENDOR}) {
            User account = saveUser("jwt-" + role.name().toLowerCase(), role);
            String bearer = bearerFor(account);
            mockMvc.perform(get("/api/orders").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
            mockMvc.perform(get("/api/favorites").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
            mockMvc.perform(get("/api/cart").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalQuantity").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
            org.assertj.core.api.Assertions.assertThat(cartRepository.findByUserId(account.getId())).isEmpty();
        }
    }

    @Test
    void unauthenticatedAndStaffAccountsCannotReadCustomerApis() throws Exception {
        for (String path : new String[] {"/api/orders", "/api/favorites", "/api/cart"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
            User admin = saveUser("jwt-admin-" + path.substring(5), Role.ADMIN);
            mockMvc.perform(get(path).header("Authorization", bearerFor(admin)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void apiReturnsOnlyTheBearerUsersOrdersFavoritesAndCart() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User owner = saveUser("jwt-owner-" + suffix, Role.VENDOR);
        User first = saveUser("jwt-first-" + suffix, Role.USER);
        User second = saveUser("jwt-second-" + suffix, Role.USER);
        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName("JWT Demo Shop");
        shop.setSlug("jwt-shop-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shopRepository.save(shop);
        Category category = new Category();
        category.setName("JWT Demo Category");
        category.setSlug("jwt-category-" + suffix);
        category = categoryRepository.save(category);
        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName("JWT Demo Tea");
        product.setSlug("jwt-tea-" + suffix);
        product.setSku("JWT-" + suffix);
        product.setDescription("Demo API");
        product.setPrice(new BigDecimal("30000"));
        product.setStock(10);
        product = productRepository.save(product);

        cartService.add(first, product.getId(), 2);
        engagementService.toggleFavorite(first, product);
        CustomerOrder order = new CustomerOrder();
        order.setCode("JWT" + suffix);
        order.setUser(first);
        order.setShop(shop);
        order.setReceiverName(first.getFullName());
        order.setReceiverPhone("0901234567");
        order.setShippingAddress("UTETra test address");
        order.setSubtotal(new BigDecimal("60000"));
        order.setTotalAmount(new BigDecimal("60000"));
        order = orderRepository.save(order);

        String firstBearer = bearerFor(first);
        mockMvc.perform(get("/api/orders").header("Authorization", firstBearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].code").value(order.getCode()));
        mockMvc.perform(get("/api/favorites").header("Authorization", firstBearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].productId").value(product.getId()));
        mockMvc.perform(get("/api/cart").header("Authorization", firstBearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalQuantity").value(2))
            .andExpect(jsonPath("$.subtotal").value(60000));

        String secondBearer = bearerFor(second);
        mockMvc.perform(get("/api/orders").header("Authorization", secondBearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(get("/api/favorites").header("Authorization", secondBearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(get("/api/cart").header("Authorization", secondBearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalQuantity").value(0));
    }

    @Test
    void swaggerDocumentsBearerSchemeAndCustomerApis() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
            .andExpect(jsonPath("$.paths['/api/orders'].get").exists())
            .andExpect(jsonPath("$.paths['/api/favorites'].get").exists())
            .andExpect(jsonPath("$.paths['/api/cart'].get").exists());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    private User saveUser(String username, Role role) {
        User account = new User();
        account.setUsername(username);
        account.setEmail(username + "@utetra.test");
        account.setPasswordHash("encoded-password");
        account.setFullName(username);
        account.setRole(role);
        account.setEnabled(true);
        return userRepository.save(account);
    }

    private String bearerFor(User account) {
        UserDetails details = userDetailsService.loadUserByUsername(account.getUsername());
        return "Bearer " + jwtService.generateToken(details);
    }
}
