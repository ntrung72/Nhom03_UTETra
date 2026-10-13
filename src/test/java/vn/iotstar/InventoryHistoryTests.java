package vn.iotstar;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.InventoryTransactionType;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.InventoryTransactionRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.InventoryService;
import vn.iotstar.service.VendorService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventoryHistoryTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private InventoryTransactionRepository transactionRepository;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private VendorService vendorService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private WebApplicationContext context;

    @Test
    void recordsInitialRestockAndAdjustmentWithSnapshots()
            throws Exception {

        String suffix = suffix();
        User vendor = vendor("inv-owner-" + suffix);
        Shop shop = shop(vendor, "inv-shop-" + suffix);
        User otherVendor = vendor("inv-other-" + suffix);
        Shop otherShop = shop(
            otherVendor,
            "inv-other-shop-" + suffix
        );
        Category category = category(suffix);

        ProductForm form = form(
            category.getId(),
            "INV-" + suffix,
            10
        );

        var product = vendorService.saveProduct(
            vendor,
            null,
            form,
            null
        );

        entityManager.flush();

        String originalName = product.getName();

        form.setVersion(product.getVersion());
        form.setStock(15);

        vendorService.saveProduct(
            vendor,
            product.getId(),
            form,
            null
        );

        entityManager.flush();

        form.setVersion(product.getVersion());
        form.setStock(13);
        form.setName("Tên mới sau kiểm kê");

        vendorService.saveProduct(
            vendor,
            product.getId(),
            form,
            null
        );

        entityManager.flush();

        var movements = transactionRepository
            .findByProductIdOrderByCreatedAtAscIdAsc(
                product.getId()
            );

        assertEquals(3, movements.size());

        var initial = movements.get(0);
        assertEquals(
            InventoryTransactionType.INITIAL,
            initial.getType()
        );
        assertEquals(0, initial.getStockBefore());
        assertEquals(10, initial.getStockAfter());
        assertEquals(10, initial.getQuantityChange());
        assertEquals(originalName, initial.getProductName());

        var restock = movements.get(1);
        assertEquals(
            InventoryTransactionType.RESTOCK,
            restock.getType()
        );
        assertEquals(10, restock.getStockBefore());
        assertEquals(15, restock.getStockAfter());
        assertEquals(5, restock.getQuantityChange());
        assertEquals(vendor.getId(), restock.getActor().getId());
        assertEquals(shop.getId(), restock.getShop().getId());

        var adjustment = movements.get(2);
        assertEquals(
            InventoryTransactionType.ADJUSTMENT,
            adjustment.getType()
        );
        assertEquals(15, adjustment.getStockBefore());
        assertEquals(13, adjustment.getStockAfter());
        assertEquals(-2, adjustment.getQuantityChange());
        assertEquals(
            "Tên mới sau kiểm kê",
            adjustment.getProductName()
        );

        assertEquals(
            3L,
            vendorService.inventoryTransactions(
                vendor,
                product.getId(),
                0
            ).getTotalElements()
        );

        assertTrue(
            inventoryService.forShop(
                otherShop,
                product.getId(),
                0
            ).isEmpty()
        );

        var mvc = MockMvcBuilders
            .webAppContextSetup(context)
            .apply(springSecurity())
            .build();

        mvc.perform(
                get("/vendor/inventory")
                    .with(user(vendor.getUsername()).roles("VENDOR"))
                    .param("productId", product.getId().toString())
            )
            .andExpect(status().isOk())
            .andExpect(view().name("vendor/inventory"))
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString(
                    "Lịch sử nhập xuất kho"
                )
            ));

        mvc.perform(
                get("/vendor/inventory")
                    .with(user(otherVendor.getUsername()).roles("VENDOR"))
                    .param("productId", product.getId().toString())
            )
            .andExpect(redirectedUrl("/vendor/inventory"));
    }

    @Test
    void staleFormCannotOverwriteNewStockOrPrice() {
        String suffix = suffix();
        User vendor = vendor("inv-stale-" + suffix);
        shop(vendor, "inv-stale-shop-" + suffix);
        Category category = category(suffix);
        String sku = "STALE-" + suffix;

        var product = vendorService.saveProduct(
            vendor,
            null,
            form(category.getId(), sku, 10),
            null
        );

        entityManager.flush();

        ProductForm staleForm = form(
            category.getId(),
            sku,
            10
        );
        staleForm.setVersion(product.getVersion());
        staleForm.setPrice(new BigDecimal("12000"));

        ProductForm freshForm = form(
            category.getId(),
            sku,
            8
        );
        freshForm.setVersion(product.getVersion());

        vendorService.saveProduct(
            vendor,
            product.getId(),
            freshForm,
            null
        );

        entityManager.flush();

        assertTrue(
            product.getVersion() > staleForm.getVersion()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> vendorService.saveProduct(
                vendor,
                product.getId(),
                staleForm,
                null
            )
        );

        assertEquals(8, product.getStock());
        assertEquals(
            0,
            product.getPrice().compareTo(new BigDecimal("10000"))
        );

        assertEquals(
            2,
            transactionRepository
                .findByProductIdOrderByCreatedAtAscIdAsc(
                    product.getId()
                )
                .size()
        );
    }

    @Test
    void savingSameStockDoesNotCreateAnotherMovement() {
        String suffix = suffix();
        User vendor = vendor("inv-same-" + suffix);
        shop(vendor, "inv-same-shop-" + suffix);
        Category category = category(suffix);
        ProductForm form = form(
            category.getId(),
            "SAME-" + suffix,
            10
        );

        var product = vendorService.saveProduct(
            vendor,
            null,
            form,
            null
        );

        entityManager.flush();

        form.setVersion(product.getVersion());
        form.setPrice(new BigDecimal("12000"));

        vendorService.saveProduct(
            vendor,
            product.getId(),
            form,
            null
        );

        entityManager.flush();

        assertEquals(10, product.getStock());

        assertEquals(
            1,
            transactionRepository
                .findByProductIdOrderByCreatedAtAscIdAsc(
                    product.getId()
                )
                .size()
        );
    }

    @Test
    void lowStockIncludesZeroAndZeroInitialStockIsRecorded() {
        String suffix = suffix();
        User vendor = vendor("inv-zero-" + suffix);
        shop(vendor, "inv-zero-shop-" + suffix);
        Category category = category(suffix);

        var product = vendorService.saveProduct(
            vendor,
            null,
            form(category.getId(), "ZERO-" + suffix, 0),
            null
        );

        entityManager.flush();

        assertEquals(0, product.getStock());
        assertEquals(
            ProductStatus.OUT_OF_STOCK,
            product.getStatus()
        );

        var movements = transactionRepository
            .findByProductIdOrderByCreatedAtAscIdAsc(
                product.getId()
            );

        assertEquals(1, movements.size());
        assertEquals(
            InventoryTransactionType.INITIAL,
            movements.getFirst().getType()
        );
        assertEquals(0, movements.getFirst().getQuantityChange());

        assertTrue(
            vendorService.lowStockProducts(vendor).stream()
                .anyMatch(p -> p.getId().equals(product.getId()))
        );
    }

    @Test
    void negativeStockIsRejectedWithoutChangingInventory() {
        String suffix = suffix();
        User vendor = vendor("inv-negative-" + suffix);
        shop(vendor, "inv-negative-shop-" + suffix);
        Category category = category(suffix);
        ProductForm form = form(
            category.getId(),
            "NEG-" + suffix,
            10
        );

        var product = vendorService.saveProduct(
            vendor,
            null,
            form,
            null
        );

        entityManager.flush();

        form.setVersion(product.getVersion());
        form.setStock(-1);

        assertThrows(
            IllegalArgumentException.class,
            () -> vendorService.saveProduct(
                vendor,
                product.getId(),
                form,
                null
            )
        );

        assertEquals(10, product.getStock());

        assertEquals(
            1,
            transactionRepository
                .findByProductIdOrderByCreatedAtAscIdAsc(
                    product.getId()
                )
                .size()
        );
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private User vendor(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setFullName(username);
        user.setPasswordHash("encoded-password");
        user.setRole(Role.VENDOR);
        user.setEnabled(true);
        user.setLocked(false);

        return userRepository.saveAndFlush(user);
    }

    private Shop shop(User vendor, String slug) {
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName(slug);
        shop.setSlug(slug);
        shop.setStatus(ShopStatus.APPROVED);
        shop.setEnabled(true);

        return shopRepository.saveAndFlush(shop);
    }

    private Category category(String suffix) {
        Category category = new Category();
        category.setName("Danh mục kho " + suffix);
        category.setSlug("inventory-category-" + suffix);
        category.setActive(true);

        return categoryRepository.saveAndFlush(category);
    }

    private ProductForm form(
            Long categoryId,
            String sku,
            int stock) {

        ProductForm form = new ProductForm();
        form.setName("Sản phẩm kiểm kê " + sku);
        form.setSku(sku);
        form.setCategoryId(categoryId);
        form.setDescription(
            "Sản phẩm dùng để kiểm thử lịch sử nhập xuất kho."
        );
        form.setPrice(new BigDecimal("10000"));
        form.setStock(stock);
        form.setStatus(ProductStatus.ACTIVE);

        return form;
    }
}