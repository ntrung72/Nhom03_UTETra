package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.service.CatalogService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CatalogDiscountTests {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private CatalogService catalogService;

    @Test
    void filtersDiscountedProductsBeforeDatabasePagination() {
        String token = UUID.randomUUID().toString().replace("-", "");
        String prefix = "cd38" + token;
        LocalDateTime now = LocalDateTime.now();

        entityManager.createQuery("update Promotion p set p.active = false")
            .executeUpdate();

        User owner = new User();
        owner.setUsername(prefix);
        owner.setEmail(prefix + "@catalog.test");
        owner.setPasswordHash("unused-test-password");
        owner.setFullName("Catalog regression vendor");
        owner.setRole(Role.VENDOR);
        owner.setEnabled(true);
        entityManager.persist(owner);

        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName(prefix);
        shop.setSlug(prefix);
        shop.setStatus(ShopStatus.APPROVED);
        shop.setEnabled(true);
        entityManager.persist(shop);

        Category category = new Category();
        category.setName(prefix);
        category.setSlug(prefix);
        category.setActive(true);
        entityManager.persist(category);

        List<Long> discountedIds = new ArrayList<>();

        for (int index = 1; index <= 45; index++) {
            Product product = new Product();
            product.setShop(shop);
            product.setCategory(category);
            product.setName(prefix + " product " + index);
            product.setSlug(prefix + "-" + index);
            product.setSku(prefix + "-" + index);
            product.setDescription("Discount pagination regression");
            product.setPrice(new BigDecimal("40000.00"));
            product.setStock(10);
            product.setSoldCount(1000L - index);
            product.setStatus(ProductStatus.ACTIVE);
            entityManager.persist(product);

            if (index > 20) {
                discountedIds.add(product.getId());

                Promotion promotion = new Promotion();
                promotion.setName(prefix + " sale " + index);
                promotion.setType(PromotionType.PRODUCT_PERCENT);
                promotion.setShop(shop);
                promotion.setProduct(product);
                promotion.setDiscountValue(new BigDecimal("25.00"));
                promotion.setStartsAt(now.minusDays(1));
                promotion.setEndsAt(now.plusDays(1));
                promotion.setActive(true);
                entityManager.persist(promotion);
            }
        }

        entityManager.flush();
        entityManager.clear();

        var first = catalogService.search(
            prefix,
            category.getId(),
            shop.getId(),
            "bestselling",
            0,
            null,
            null,
            null,
            true,
            false
        );

        var second = catalogService.search(
            prefix,
            category.getId(),
            shop.getId(),
            "bestselling",
            1,
            null,
            null,
            null,
            true,
            false
        );

        assertThat(first.getTotalElements()).isEqualTo(25);
        assertThat(second.getTotalElements()).isEqualTo(25);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getSize()).isEqualTo(20);
        assertThat(first.getNumberOfElements()).isEqualTo(20);
        assertThat(second.getNumberOfElements()).isEqualTo(5);

        assertThat(first.getContent()).extracting(Product::getId)
            .containsExactlyElementsOf(discountedIds.subList(0, 20));

        assertThat(second.getContent()).extracting(Product::getId)
            .containsExactlyElementsOf(discountedIds.subList(20, 25));

        var beyondLast = catalogService.search(
            prefix,
            category.getId(),
            shop.getId(),
            null,
            Integer.MAX_VALUE,
            null,
            null,
            null,
            true,
            false
        );

        assertThat(beyondLast.getContent()).isEmpty();
        assertThat(beyondLast.getTotalElements()).isEqualTo(25);

        var allProducts = catalogService.search(
            prefix,
            category.getId(),
            shop.getId(),
            "bestselling",
            0,
            null,
            null,
            null,
            false,
            false
        );

        assertThat(allProducts.getTotalElements()).isEqualTo(45);
    }
}