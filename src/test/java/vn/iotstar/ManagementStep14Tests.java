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
import vn.iotstar.dto.WebForms.CategoryForm;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.dto.WebForms.PromotionForm;
import vn.iotstar.dto.WebForms.ToppingForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.ManagementProductService;
import vn.iotstar.service.ManagementService;

/** Regression tests limited to Step 14 Management Catalog and ManagerShop scoping. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManagementStep14Tests {
    @Autowired ManagementService management;
    @Autowired ManagementProductService managementProducts;
    @Autowired UserRepository users;
    @Autowired ShopRepository shops;
    @Autowired CategoryRepository categories;
    @Autowired ToppingRepository toppings;
    @Autowired ProductRepository products;
    @Autowired PromotionRepository promotions;

    User admin, manager;
    Shop assigned, other;
    Category category;

    @BeforeEach
    void setup() {
        admin = user(Role.ADMIN);
        manager = user(Role.MANAGER);
        assigned = shop(user(Role.VENDOR));
        other = shop(user(Role.VENDOR));
        management.assignManagerShops(admin, manager.getId(), List.of(assigned.getId()));
        CategoryForm form = new CategoryForm();
        form.setName("S14-cat-" + suffix());
        form.setDescription("Test category");
        management.saveCategory(null, form, null);
        category = categories.findAll().stream().filter(c -> c.getName().equals(form.getName()))
            .findFirst().orElseThrow();
    }

    @Test
    void categoryCrudAndDuplicateProtection() {
        CategoryForm form = new CategoryForm();
        form.setName("S14-new-" + suffix());
        management.saveCategory(null, form, null);
        assertThat(management.categories(form.getName(), 0).getTotalElements()).isEqualTo(1);
        Category saved = categories.findAll().stream().filter(c -> c.getName().equals(form.getName()))
            .findFirst().orElseThrow();
        assertThatThrownBy(() -> management.saveCategory(null, form, null))
            .isInstanceOf(IllegalArgumentException.class);
        management.toggleCategory(saved.getId());
        assertThat(saved.isActive()).isFalse();
    }

    @Test
    void toppingCrudAndCannotDeleteInUse() {
        ToppingForm form = new ToppingForm();
        form.setName("S14-topping-" + suffix());
        form.setPrice(new BigDecimal("5000"));
        management.saveTopping(null, form);
        Topping topping = toppings.findByNameIgnoreCase(form.getName()).orElseThrow();
        management.toggleTopping(topping.getId());
        assertThat(topping.isActive()).isFalse();
        management.deleteTopping(topping.getId());
        assertThat(toppings.findById(topping.getId())).isEmpty();
    }

    @Test
    void productCreationAndUpdateAreScoped() {
        ProductForm form = productForm("ONE");
        Product item = managementProducts.save(manager, null, assigned.getId(), form,
            null, null, null, null, null);
        assertThat(item.getShop().getId()).isEqualTo(assigned.getId());
        assertThat(management.products(manager, null, null, null, 0).getContent())
            .extracting(Product::getId).contains(item.getId());
        assertThatThrownBy(() -> managementProducts.save(manager, null, other.getId(), productForm("TWO"),
            null, null, null, null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> managementProducts.save(manager, item.getId(), other.getId(), form,
            null, null, null, null, null)).isInstanceOf(AccessDeniedException.class);
        management.toggleProduct(manager, item.getId());
        assertThat(item.getStatus()).isEqualTo(ProductStatus.HIDDEN);
        form.setStock(0);
        form.setStatus(ProductStatus.ACTIVE);
        Product edited = managementProducts.save(manager, item.getId(), assigned.getId(), form,
            null, null, null, null, null);
        assertThat(edited.getStatus()).isEqualTo(ProductStatus.OUT_OF_STOCK);
    }

    @Test
    void adminSeesAllProductsButManagerCannotAccessOtherShop() {
        Product outside = managementProducts.save(admin, null, other.getId(), productForm("OTHER"),
            null, null, null, null, null);
        assertThat(management.products(manager, null, null, null, 0).getContent())
            .extracting(Product::getId).doesNotContain(outside.getId());
        assertThat(management.products(admin, null, null, null, 0).getContent())
            .extracting(Product::getId).contains(outside.getId());
        assertThatThrownBy(() -> managementProducts.get(manager, outside.getId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.toggleProduct(manager, outside.getId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void promotionValidationsAndShopScope() {
        PromotionForm form = promotionForm();
        management.savePromotion(null, assigned.getId(), form, manager);
        Promotion promo = management.promotions(manager, null, null, null, 0).getContent().get(0);
        assertThat(promo.getShop().getId()).isEqualTo(assigned.getId());
        assertThatThrownBy(() -> management.savePromotion(null, other.getId(), form, manager))
            .isInstanceOf(AccessDeniedException.class);
        form.setDiscountValue(new BigDecimal("101"));
        assertThatThrownBy(() -> management.savePromotion(promo.getId(), assigned.getId(), form, manager))
            .isInstanceOf(IllegalArgumentException.class);
        form.setDiscountValue(new BigDecimal("5"));
        form.setEndsAt(form.getStartsAt().minusDays(1));
        assertThatThrownBy(() -> management.savePromotion(promo.getId(), assigned.getId(), form, manager))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> management.getPromotion(manager, promo.getId()))
            .doesNotThrowAnyException();
    }

    private User user(Role role) {
        String id = suffix();
        User u = new User();
        u.setUsername("s14" + id);
        u.setEmail("s14" + id + "@example.invalid");
        u.setFullName("Step14 " + id);
        u.setPasswordHash("test-hash");
        u.setEnabled(true);
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private Shop shop(User owner) {
        String id = suffix();
        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName("Shop " + id);
        shop.setSlug("shop-s14-" + id);
        shop.setStatus(ShopStatus.APPROVED);
        shop.setEnabled(true);
        return shops.saveAndFlush(shop);
    }

    private ProductForm productForm(String skuSuffix) {
        ProductForm form = new ProductForm();
        form.setName("Trà sữa kiểm thử");
        form.setSku("S14-" + skuSuffix + "-" + suffix());
        form.setCategoryId(category.getId());
        form.setDescription("Mô tả sản phẩm Bước 14 đủ dài để kiểm tra dữ liệu.");
        form.setPrice(new BigDecimal("25000"));
        form.setStock(10);
        form.setStatus(ProductStatus.ACTIVE);
        return form;
    }

    private PromotionForm promotionForm() {
        PromotionForm form = new PromotionForm();
        form.setName("Khuyến mãi Bước 14");
        form.setType(PromotionType.PRODUCT_PERCENT);
        form.setDiscountValue(new BigDecimal("10"));
        form.setStartsAt(LocalDateTime.now().minusDays(1));
        form.setEndsAt(LocalDateTime.now().plusDays(2));
        form.setActive(true);
        return form;
    }

    private String suffix() { return UUID.randomUUID().toString().substring(0, 8); }
}
