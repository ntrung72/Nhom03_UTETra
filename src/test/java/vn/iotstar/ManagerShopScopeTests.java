package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.ManagementService;

/** Step 12 scope/security tests. No Management CRUD or later feature is created here. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManagerShopScopeTests {
    @Autowired private ManagementService management;
    @Autowired private ManagerShopRepository assignments;
    @Autowired private UserRepository users;
    @Autowired private ShopRepository shops;
    @Autowired private ProductRepository products;
    @Autowired private CategoryRepository categories;
    @Autowired private PromotionRepository promotions;

    private User admin;
    private User manager;
    private User outsider;
    private Shop assignedShop;
    private Shop otherShop;

    @BeforeEach
    void prepare() {
        admin = user(Role.ADMIN);
        manager = user(Role.MANAGER);
        outsider = user(Role.USER);
        assignedShop = shop();
        otherShop = shop();

        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(assignedShop);
        assignment.setAssignedBy(admin);
        assignments.saveAndFlush(assignment);
    }

    @Test
    void referenceEntityAndRepositoryStoreUniqueAssignmentAndReturnShopIds() {
        assertThat(assignments.findShopIdsByManagerId(manager.getId()))
            .containsExactly(assignedShop.getId());
        assertThat(assignments.existsByManagerIdAndShopId(manager.getId(), assignedShop.getId())).isTrue();
        assertThat(assignments.existsByManagerIdAndShopId(manager.getId(), otherShop.getId())).isFalse();
        assertThat(assignments.findByManagerIdOrderByShopNameAsc(manager.getId()))
            .extracting(a -> a.getShop().getId()).containsExactly(assignedShop.getId());
    }

    @Test
    void adminCanAccessAllShopsButManagerOnlyAssignedShops() {
        assertThat(management.accessibleShops(admin)).extracting(Shop::getId)
            .contains(assignedShop.getId(), otherShop.getId());
        assertThat(management.accessibleShops(manager)).extracting(Shop::getId)
            .containsExactly(assignedShop.getId());
        assertThat(management.accessibleShopIds(manager))
            .containsExactly(assignedShop.getId());
        management.requireShopAccess(admin, otherShop.getId());
        management.requireShopAccess(manager, assignedShop.getId());
        assertThatThrownBy(() -> management.requireShopAccess(manager, otherShop.getId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.requireShopAccess(manager, -1L))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void invalidActorAndNonManagerAreDeniedEvenForExistingShops() {
        assertThatThrownBy(() -> management.accessibleShops(outsider))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.requireShopAccess(outsider, assignedShop.getId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.requireShopAccess(null, assignedShop.getId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void serverSideProductAndPromotionQueriesNeverExposeOtherShops() {
        Category category = new Category();
        category.setName("Test Category");
        category.setSlug("scope-" + UUID.randomUUID());
        category = categories.saveAndFlush(category);

        Product ownProduct = product(assignedShop, category);
        Product otherProduct = product(otherShop, category);
        Promotion ownPromotion = promotion(assignedShop);
        Promotion otherPromotion = promotion(otherShop);
        Promotion globalPromotion = promotion(null);

        assertThat(management.accessibleProducts(manager)).extracting(Product::getId)
            .contains(ownProduct.getId()).doesNotContain(otherProduct.getId());
        assertThat(management.accessiblePromotions(manager)).extracting(Promotion::getId)
            .contains(ownPromotion.getId())
            .doesNotContain(otherPromotion.getId(), globalPromotion.getId());
        assertThat(management.getProduct(manager, ownProduct.getId()).getId())
            .isEqualTo(ownProduct.getId());
        assertThat(management.getPromotion(manager, ownPromotion.getId()).getId())
            .isEqualTo(ownPromotion.getId());
        assertThatThrownBy(() -> management.getProduct(manager, otherProduct.getId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.getPromotion(manager, otherPromotion.getId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.getPromotion(manager, globalPromotion.getId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(management.accessibleProducts(admin)).extracting(Product::getId)
            .contains(ownProduct.getId(), otherProduct.getId());
        assertThat(management.accessiblePromotions(admin)).extracting(Promotion::getId)
            .contains(ownPromotion.getId(), otherPromotion.getId(), globalPromotion.getId());
    }

    private User user(Role role) {
        String id = UUID.randomUUID().toString().substring(0, 12);
        User user = new User();
        user.setUsername("u" + id);
        user.setEmail("u" + id + "@test.invalid");
        user.setFullName("Test user");
        user.setPasswordHash("test-bcrypt-hash");
        user.setRole(role);
        user.setEnabled(true);
        return users.saveAndFlush(user);
    }

    private Shop shop() {
        String slug = UUID.randomUUID().toString();
        Shop shop = new Shop();
        shop.setOwner(user(Role.VENDOR));
        shop.setName("Shop " + slug);
        shop.setSlug(slug);
        shop.setStatus(ShopStatus.APPROVED);
        return shops.saveAndFlush(shop);
    }

    private Product product(Shop shop, Category category) {
        String slug = UUID.randomUUID().toString();
        Product p = new Product();
        p.setShop(shop);
        p.setCategory(category);
        p.setName("Product " + slug);
        p.setSlug(slug);
        p.setSku("SKU-" + slug);
        p.setDescription("Scope test product");
        p.setPrice(new BigDecimal("30000"));
        p.setStock(10);
        return products.saveAndFlush(p);
    }

    private Promotion promotion(Shop shop) {
        Promotion p = new Promotion();
        p.setName("Promo " + UUID.randomUUID());
        p.setShop(shop);
        p.setType(PromotionType.PRODUCT_PERCENT);
        p.setDiscountValue(new BigDecimal("10"));
        p.setStartsAt(LocalDateTime.now().minusDays(1));
        p.setEndsAt(LocalDateTime.now().plusDays(7));
        return promotions.saveAndFlush(p);
    }
}
