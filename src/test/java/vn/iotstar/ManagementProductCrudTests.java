package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.ManagerShop;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.InventoryTransactionRepository;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ProductSizeRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.ManagementProductService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ManagementProductCrudTests {
    @Autowired private WebApplicationContext context;
    @Autowired private ManagementProductService managementProductService;
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductSizeRepository productSizeRepository;
    @Autowired private ManagerShopRepository managerShopRepository;
    @Autowired private InventoryTransactionRepository inventoryTransactionRepository;
    private MockMvc mockMvc;
    private String suffix;
    private User admin;
    private User manager;
    private Shop assignedShop;
    private Shop otherShop;
    private Category category;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = saveUser("product-admin-" + suffix, Role.ADMIN);
        manager = saveUser("product-manager-" + suffix, Role.MANAGER);
        assignedShop = saveShop("assigned-" + suffix);
        otherShop = saveShop("other-" + suffix);
        category = new Category();
        category.setName("Trà sữa CRUD " + suffix);
        category.setSlug("tra-sua-crud-" + suffix);
        category.setActive(true);
        category = categoryRepository.save(category);
        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(assignedShop);
        assignment.setAssignedBy(admin);
        managerShopRepository.save(assignment);
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void managerCreatesReadsAndUpdatesProductInsideAssignedShop() throws Exception {
        ProductForm create = form("MGR-" + suffix, "Trà sữa quản lý " + suffix, 12, "32000");
        Product product = managementProductService.save(manager, null, assignedShop.getId(), create,
            null, null, null, null, null);

        assertThat(product.getShop().getId()).isEqualTo(assignedShop.getId());
        assertThat(product.getSku()).isEqualTo(("MGR-" + suffix).toUpperCase());
        assertThat(product.getImageUrl()).isEqualTo("/images/product-placeholder.svg");
        assertThat(productSizeRepository.findByProductIdOrderByDisplayOrderAscIdAsc(product.getId())).hasSize(3);

        ProductForm update = form(product.getSku(), "Trà sữa đã cập nhật " + suffix, 20, "39000");
        update.setVersion(product.getVersion());
        Product updated = managementProductService.save(manager, product.getId(), assignedShop.getId(), update,
            null, null, null, null, null);
        assertThat(updated.getName()).contains("đã cập nhật");
        assertThat(updated.getStock()).isEqualTo(20);
        assertThat(updated.getPrice()).isEqualByComparingTo("39000");

        mockMvc.perform(get("/management/products/{id}", product.getId())
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(product.getSku())));
        mockMvc.perform(get("/management/products/{id}/edit", product.getId())
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk());
    }

    @Test
    void managerCannotManageUnassignedShopButAdminCan() throws Exception {
        ProductForm blocked = form("BLOCK-" + suffix, "Sản phẩm ngoài phạm vi " + suffix, 5, "25000");
        assertThatThrownBy(() -> managementProductService.save(manager, null, otherShop.getId(), blocked,
            null, null, null, null, null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
            .hasMessageContaining("không được quản lý");

        Product createdByAdmin = managementProductService.save(admin, null, otherShop.getId(), blocked,
            null, null, null, null, null);
        assertThat(productRepository.findById(createdByAdmin.getId())).isPresent();
        assertThatThrownBy(() -> managementProductService.get(manager, createdByAdmin.getId()))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class).hasMessageContaining("không được quản lý");
        mockMvc.perform(get("/management/products/{id}/edit", createdByAdmin.getId())
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/management"));

        mockMvc.perform(get("/management/products/new").with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(assignedShop.getName())))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(otherShop.getName()))));
    }

    private ProductForm form(String sku, String name, int stock, String price) {
        ProductForm form = new ProductForm();
        form.setName(name);
        form.setSku(sku);
        form.setCategoryId(category.getId());
        form.setDescription("Mô tả sản phẩm đầy đủ để kiểm thử CRUD quản lý sản phẩm.");
        form.setPrice(new BigDecimal(price));
        form.setStock(stock);
        form.setStatus(ProductStatus.ACTIVE);
        form.setSizeMActive(true);
        form.setSizeMExtraPrice(BigDecimal.ZERO);
        form.setSizeLActive(true);
        form.setSizeLExtraPrice(new BigDecimal("7000"));
        form.setSizeXlActive(true);
        form.setSizeXlExtraPrice(new BigDecimal("12000"));
        return form;
    }

    private Shop saveShop(String slug) {
        Shop shop = new Shop();
        shop.setOwner(saveUser("vendor-" + slug, Role.VENDOR));
        shop.setName("UTETra " + slug);
        shop.setSlug("utetra-" + slug);
        shop.setStatus(ShopStatus.APPROVED);
        return shopRepository.save(shop);
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
}
