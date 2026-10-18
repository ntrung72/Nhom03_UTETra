package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.ManagerShop;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.ManagementService;
import vn.iotstar.service.CatalogService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ManagementScopeTests {
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ManagerShopRepository managerShopRepository;
    @Autowired private ManagementService managementService;
    @Autowired private CatalogService catalogService;

    @Test
    void inactiveCategoryHidesProductsFromEveryPublicEntryPoint() {
        Shop shop = saveShop("inactive-category-shop", saveUser("inactive-category-vendor", Role.VENDOR));
        Category activeCategory = saveCategory("active-public-category", true);
        Category inactiveCategory = saveCategory("inactive-public-category", false);
        Product visibleProduct = saveProduct("CATEGORY-ACTIVE", shop, activeCategory);
        Product hiddenProduct = saveProduct("CATEGORY-INACTIVE", shop, inactiveCategory);
        visibleProduct.setSoldCount(20);
        hiddenProduct.setSoldCount(30);
        productRepository.saveAllAndFlush(List.of(visibleProduct, hiddenProduct));

        assertEquals(List.of(visibleProduct.getId()),
            catalogService.search(null, null, null, "bestselling", 0).getContent().stream()
                .map(Product::getId).toList());
        assertEquals(List.of(visibleProduct.getId()),
            catalogService.homeBestSellers().stream().map(Product::getId).toList());
        assertThrows(ResponseStatusException.class,
            () -> catalogService.getPublicProduct(hiddenProduct.getId()));
    }

    @Test
    void managerOnlyReadsAndChangesAssignedShopDataWhileAdminSeesAll() {
        User admin = saveUser("scope-admin", Role.ADMIN);
        User manager = saveUser("scope-manager", Role.MANAGER);
        Shop assignedShop = saveShop("scope-shop-a", saveUser("scope-vendor-a", Role.VENDOR));
        Shop otherShop = saveShop("scope-shop-b", saveUser("scope-vendor-b", Role.VENDOR));
        Category category = saveCategory();
        Product assignedProduct = saveProduct("SCOPE-A", assignedShop, category);
        Product otherProduct = saveProduct("SCOPE-B", otherShop, category);

        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(assignedShop);
        assignment.setAssignedBy(admin);
        managerShopRepository.save(assignment);

        assertEquals(1, managementService.shops(manager, null, null, 0).getTotalElements());
        assertEquals(1, managementService.products(manager, null, null, null, 0).getTotalElements());
        assertEquals(2, managementService.products(admin, null, null, null, 0).getTotalElements());

        managementService.toggleProduct(manager, assignedProduct.getId());
        assertEquals(ProductStatus.HIDDEN, assignedProduct.getStatus());
        assertThrows(AccessDeniedException.class,
            () -> managementService.toggleProduct(manager, otherProduct.getId()));
        assertThrows(AccessDeniedException.class,
            () -> managementService.updateShop(manager, otherShop.getId(), ShopStatus.SUSPENDED, BigDecimal.TEN));
    }

    @Test
    void onlyAdminCanReplaceManagerAssignments() {
        User admin = saveUser("assign-admin", Role.ADMIN);
        User manager = saveUser("assign-manager", Role.MANAGER);
        Shop first = saveShop("assign-shop-a", saveUser("assign-vendor-a", Role.VENDOR));
        Shop second = saveShop("assign-shop-b", saveUser("assign-vendor-b", Role.VENDOR));

        managementService.assignManagerShops(admin, manager.getId(), List.of(first.getId(), second.getId()));
        assertEquals(2, managerShopRepository.findShopIdsByManagerId(manager.getId()).size());
        managementService.assignManagerShops(admin, manager.getId(), List.of(first.getId()));
        assertEquals(List.of(first.getId()), managerShopRepository.findShopIdsByManagerId(manager.getId()));
        assertThrows(AccessDeniedException.class,
            () -> managementService.assignManagerShops(manager, manager.getId(), List.of(first.getId())));
    }

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(username);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private Shop saveShop(String slug, User owner) {
        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName(slug);
        shop.setSlug(slug);
        shop.setStatus(ShopStatus.APPROVED);
        return shopRepository.save(shop);
    }

    private Category saveCategory() {
        return saveCategory("scope-category", true);
    }

    private Category saveCategory(String prefix, boolean active) {
        Category category = new Category();
        category.setName("Scope category");
        category.setSlug(prefix + "-" + System.nanoTime());
        category.setActive(active);
        return categoryRepository.save(category);
    }

    private Product saveProduct(String sku, Shop shop, Category category) {
        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName(sku);
        product.setSlug(sku.toLowerCase());
        product.setSku(sku);
        product.setDescription("Product used to test manager shop scope.");
        product.setPrice(new BigDecimal("30000"));
        product.setStock(10);
        return productRepository.save(product);
    }
}
