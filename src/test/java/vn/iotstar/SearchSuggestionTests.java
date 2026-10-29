package vn.iotstar;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.SearchHistoryRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.SearchSuggestionService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class SearchSuggestionTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private SearchHistoryRepository historyRepository;
    @Autowired private SearchSuggestionService searchSuggestionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void guestSuggestionsAreLimitedAndExcludeHiddenCatalog() throws Exception {
        String token = "suggest" + UUID.randomUUID().toString().substring(0, 8);
        Category category = category(token, true);
        Shop mainShop = shop(token + "-main", ShopStatus.APPROVED);
        for (int index = 0; index < 7; index++) {
            Product product = product(mainShop, category, token + " public " + index);
            product.setSoldCount(20 - index);
            productRepository.save(product);
        }
        Product hidden = product(mainShop, category, token + " hidden");
        hidden.setStatus(ProductStatus.HIDDEN);
        productRepository.save(hidden);
        Product inactiveCategoryProduct = product(mainShop, category(token + "-inactive", false), token + " inactive");
        productRepository.save(inactiveCategoryProduct);
        for (int index = 0; index < 4; index++) shop(token + "-shop-" + index, ShopStatus.APPROVED);
        shop(token + "-pending", ShopStatus.PENDING);

        mockMvc.perform(get("/api/search/suggestions").param("q", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.products.length()").value(5))
            .andExpect(jsonPath("$.shops.length()").value(3))
            .andExpect(jsonPath("$.history.length()").value(0))
            .andExpect(content().string(not(containsString(token + " hidden"))))
            .andExpect(content().string(not(containsString(token + " inactive"))))
            .andExpect(content().string(not(containsString(token + "-pending"))));
    }

    @Test
    void submittedSearchIsRecordedOnlyForItsAccount() throws Exception {
        String token = UUID.randomUUID().toString().substring(0, 8);
        User first = saveUser("search-first-" + token);
        User second = saveUser("search-second-" + token);
        String keyword = "Trà sữa " + token;

        mockMvc.perform(get("/products").param("q", keyword)
                .with(user(first.getUsername()).roles("USER")))
            .andExpect(status().isOk());
        searchSuggestionService.recordSearch(first, "TRÀ SỮA " + token);
        assertEquals(1, historyRepository.findByUserIdOrderBySearchedAtDescIdDesc(first.getId(),
            org.springframework.data.domain.PageRequest.of(0, 10)).size());
        assertEquals(2, historyRepository.findByUserIdOrderBySearchedAtDescIdDesc(first.getId(),
            org.springframework.data.domain.PageRequest.of(0, 10)).getFirst().getSearchCount());

        mockMvc.perform(get("/api/search/suggestions").with(user(first.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.history[0]").value("TRÀ SỮA " + token));
        mockMvc.perform(get("/api/search/suggestions").with(user(second.getUsername()).roles("USER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.history.length()").value(0));
        mockMvc.perform(get("/api/search/suggestions"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.history.length()").value(0));
    }

    @Test
    void recentSearchCanBeDeletedOnlyFromItsOwnAccount() throws Exception {
        String token = UUID.randomUUID().toString().substring(0, 8);
        User first = saveUser("history-first-" + token);
        User second = saveUser("history-second-" + token);
        String keyword = "Trà sữa " + token;
        searchSuggestionService.recordSearch(first, keyword);
        searchSuggestionService.recordSearch(second, keyword);

        mockMvc.perform(delete("/api/search/history").param("keyword", keyword)
                .with(user(first.getUsername()).roles("USER")))
            .andExpect(status().isNoContent());
        assertTrue(historyRepository.findByUserIdAndNormalizedKeyword(first.getId(), "tra sua " + token).isEmpty());
        assertTrue(historyRepository.findByUserIdAndNormalizedKeyword(second.getId(), "tra sua " + token).isPresent());
        mockMvc.perform(get("/api/search/suggestions").with(user(first.getUsername()).roles("USER")))
            .andExpect(jsonPath("$.history.length()").value(0));
        mockMvc.perform(get("/api/search/suggestions").with(user(second.getUsername()).roles("USER")))
            .andExpect(jsonPath("$.history[0]").value(keyword));
        mockMvc.perform(delete("/api/search/history").param("keyword", keyword))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void focusWithoutQueryOffersBestSellingProduct() {
        String token = "popular" + UUID.randomUUID().toString().substring(0, 8);
        Product popular = product(shop(token, ShopStatus.APPROVED), category(token, true), token);
        popular.setSoldCount(1_000_000_000);
        productRepository.save(popular);

        var suggestions = searchSuggestionService.suggest(null, null);
        assertTrue(suggestions.popular());
        assertTrue(suggestions.products().stream().anyMatch(item -> item.id().equals(popular.getId())));
        assertTrue(suggestions.history().isEmpty());
    }

    @Test
    void shortPhraseSuggestsMatchingProductAndRelatedCategory() {
        String token = UUID.randomUUID().toString().substring(0, 8);
        Category category = category("tra-sua-" + token, true);
        category.setName("Trà sữa " + token);
        categoryRepository.save(category);
        Product product = product(shop("milk-tea-" + token, ShopStatus.APPROVED), category,
            "Trà sữa Matcha " + token);
        product.setSoldCount(1_000_000_000);
        productRepository.save(product);

        var suggestions = searchSuggestionService.suggest("Trà s", null);
        assertTrue(suggestions.products().stream().anyMatch(item -> item.id().equals(product.getId())));
        assertTrue(suggestions.keywords().contains(category.getName()));
    }

    private User saveUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(username);
        user.setRole(Role.USER);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private Shop shop(String slug, ShopStatus status) {
        User owner = saveUser("vendor-" + slug);
        owner.setRole(Role.VENDOR);
        userRepository.save(owner);
        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName("UTETra " + slug);
        shop.setSlug(slug);
        shop.setStatus(status);
        return shopRepository.save(shop);
    }

    private Category category(String slug, boolean active) {
        Category category = new Category();
        category.setName(slug);
        category.setSlug(slug);
        category.setActive(active);
        return categoryRepository.save(category);
    }

    private Product product(Shop shop, Category category, String name) {
        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName(name);
        product.setSlug(name.replace(' ', '-').toLowerCase());
        product.setSku(name.replace(' ', '-'));
        product.setDescription("Sản phẩm kiểm thử gợi ý tìm kiếm.");
        product.setPrice(new BigDecimal("30000"));
        product.setStock(20);
        return productRepository.save(product);
    }
}
