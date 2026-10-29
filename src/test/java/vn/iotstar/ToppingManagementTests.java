package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.dto.WebForms.ToppingForm;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.ProductTopping;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.Topping;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ProductToppingRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.ToppingRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.ManagementService;
import vn.iotstar.service.ProductOptionService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ToppingManagementTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private ToppingRepository toppingRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductToppingRepository productToppingRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private ManagementService managementService;
    @Autowired private ProductOptionService productOptionService;
    @Autowired private PasswordEncoder passwordEncoder;
    private MockMvc mockMvc;
    private String suffix;
    private User manager;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        manager = saveUser("topping-manager-" + suffix, Role.MANAGER);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void managerCanOpenCreateEditAndToggleToppings() throws Exception {
        mockMvc.perform(get("/management/toppings").with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk());

        String name = "Thạch đào " + suffix;
        mockMvc.perform(post("/management/toppings/save")
                .with(user(manager.getUsername()).roles("MANAGER")).with(csrf())
                .param("name", name).param("price", "9000").param("active", "true"))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/management/toppings"));
        Topping topping = toppingRepository.findByNameIgnoreCase(name).orElseThrow();
        assertThat(topping.getPrice()).isEqualByComparingTo("9000");
        assertThat(topping.isActive()).isTrue();

        mockMvc.perform(post("/management/toppings/save")
                .with(user(manager.getUsername()).roles("MANAGER")).with(csrf())
                .param("id", topping.getId().toString()).param("name", name)
                .param("price", "12000").param("active", "true"))
            .andExpect(status().is3xxRedirection());
        assertThat(toppingRepository.findById(topping.getId()).orElseThrow().getPrice())
            .isEqualByComparingTo("12000");

        mockMvc.perform(post("/management/toppings/{id}/toggle", topping.getId())
                .with(user(manager.getUsername()).roles("MANAGER")).with(csrf()))
            .andExpect(status().is3xxRedirection());
        assertThat(toppingRepository.findById(topping.getId()).orElseThrow().isActive()).isFalse();
        assertThat(productOptionService.availableToppings()).noneMatch(item -> item.getId().equals(topping.getId()));
    }

    @Test
    void vendorCannotEnterToppingManagement() throws Exception {
        User vendor = saveUser("topping-vendor-" + suffix, Role.VENDOR);
        mockMvc.perform(get("/management/toppings").with(user(vendor.getUsername()).roles("VENDOR")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/vendor"));
    }

    @Test
    void validatesDuplicatesDeletesUnusedAndPreservesManagedDefaultValues() {
        String name = "Pudding quản lý " + suffix;
        managementService.saveTopping(null, form(name, "11000", true));
        assertThatThrownBy(() -> managementService.saveTopping(null, form(name.toUpperCase(), "13000", true)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("đã tồn tại");

        Topping unused = toppingRepository.findByNameIgnoreCase(name).orElseThrow();
        managementService.deleteTopping(unused.getId());
        assertThat(toppingRepository.findById(unused.getId())).isEmpty();

        Topping kemCheese = new Topping();
        kemCheese.setName("Kem cheese");
        kemCheese.setPrice(new BigDecimal("19000"));
        kemCheese.setActive(false);
        toppingRepository.save(kemCheese);
        productOptionService.configureAllProducts();
        Topping preserved = toppingRepository.findByNameIgnoreCase("Kem cheese").orElseThrow();
        assertThat(preserved.getPrice()).isEqualByComparingTo("19000");
        assertThat(preserved.isActive()).isFalse();
    }

    @Test
    void reconcilesDemoToppingsWithoutReplacingManagedPricesOrDuplicateLinks() {
        toppingRepository.findByNameIgnoreCase("Trân châu đường đen").ifPresent(existing -> {
            existing.setName("Topping khác " + suffix);
            toppingRepository.save(existing);
        });
        Shop shop = new Shop();
        shop.setOwner(manager);
        shop.setName("UTETra thử " + suffix);
        shop.setSlug("utetra-test-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shopRepository.save(shop);

        Category toppingCategory = new Category();
        toppingCategory.setName("Topping thử " + suffix);
        toppingCategory.setSlug("topping");
        toppingCategory = categoryRepository.save(toppingCategory);
        Category teaCategory = new Category();
        teaCategory.setName("Trà thử " + suffix);
        teaCategory.setSlug("tra-sua");
        teaCategory = categoryRepository.save(teaCategory);

        Product lotusProduct = product(shop, toppingCategory, "Hạt sen", "UTE-030", "14000");
        Product lotusTea = product(shop, teaCategory, "Trà nhãn sen", "UTE-022", "35000");
        Topping blackPearl = null;
        for (String name : new String[]{"Trân châu đen", "Trân châu trắng", "Pudding trứng", "Kem cheese", "Thạch dừa"}) {
            Topping topping = new Topping();
            topping.setName(name);
            topping.setPrice(new BigDecimal(name.equals("Trân châu đen") ? "13000" : "10000"));
            topping = toppingRepository.save(topping);
            if (name.equals("Trân châu đen")) blackPearl = topping;
            ProductTopping link = new ProductTopping();
            link.setProduct(lotusTea);
            link.setTopping(topping);
            productToppingRepository.save(link);
        }

        productOptionService.reconcileDemoToppingCatalog();
        productOptionService.reconcileDemoToppingCatalog();

        Topping renamed = toppingRepository.findByNameIgnoreCase("Trân châu đường đen").orElseThrow();
        assertThat(renamed.getId()).isEqualTo(blackPearl.getId());
        assertThat(renamed.getPrice()).isEqualByComparingTo("13000");
        assertThat(toppingRepository.findByNameIgnoreCase("Trân châu đen")).isEmpty();
        Topping lotus = toppingRepository.findByNameIgnoreCase("Hạt sen").orElseThrow();
        assertThat(lotus.getPrice()).isEqualByComparingTo(lotusProduct.getPrice());
        assertThat(productToppingRepository.countByToppingId(lotus.getId())).isEqualTo(1);
        assertThat(productToppingRepository.existsByProductIdAndToppingId(lotusTea.getId(), lotus.getId())).isTrue();
        assertThat(productToppingRepository.existsByProductId(lotusProduct.getId())).isFalse();

        ProductTopping selectedLotus = productToppingRepository
            .findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(lotusTea.getId()).stream()
            .filter(link -> link.getTopping().getId().equals(lotus.getId())).findFirst().orElseThrow();
        productToppingRepository.delete(selectedLotus);
        productToppingRepository.flush();
        productOptionService.reconcileDemoToppingCatalog();
        assertThat(productToppingRepository.existsByProductIdAndToppingId(lotusTea.getId(), lotus.getId())).isFalse();
    }

    private Product product(Shop shop, Category category, String name, String sku, String price) {
        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName(name);
        product.setSlug(name.toLowerCase().replace(' ', '-'));
        product.setSku(sku);
        product.setDescription(name);
        product.setPrice(new BigDecimal(price));
        product.setStock(10);
        return productRepository.save(product);
    }

    private ToppingForm form(String name, String price, boolean active) {
        ToppingForm form = new ToppingForm();
        form.setName(name);
        form.setPrice(new BigDecimal(price));
        form.setActive(active);
        return form;
    }

    private User saveUser(String username, Role role) {
        User account = new User();
        account.setUsername(username);
        account.setEmail(username + "@utetra.test");
        account.setPasswordHash(passwordEncoder.encode("Password@123"));
        account.setFullName(username);
        account.setRole(role);
        account.setEnabled(true);
        return userRepository.save(account);
    }
}
