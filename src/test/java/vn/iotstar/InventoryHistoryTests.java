package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class InventoryHistoryTests {
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private InventoryTransactionRepository transactionRepository;
    @Autowired private InventoryService inventoryService;
    @Autowired private VendorService vendorService;
    @Autowired private EntityManager entityManager;
    @Autowired private WebApplicationContext context;

    @Test
    void vendorStockChangesAreRecordedAndOtherShopCannotSeeThem() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User vendor = vendor("inventory-vendor-" + suffix);
        Shop shop = shop(vendor, "inventory-shop-" + suffix);
        Shop otherShop = shop(vendor("inventory-other-" + suffix), "inventory-other-shop-" + suffix);
        Category category = new Category();
        category.setName("Topping " + suffix);
        category.setSlug("topping");
        category = categoryRepository.save(category);

        ProductForm form = form(category.getId(), "INV-" + suffix, 10);
        var product = vendorService.saveProduct(vendor, null, form, null);
        entityManager.flush();
        assertEquals(InventoryTransactionType.INITIAL,
            transactionRepository.findByProductIdOrderByCreatedAtAscIdAsc(product.getId()).getFirst().getType());

        form.setVersion(product.getVersion());
        form.setStock(15);
        form.setStockNote("Nhập thêm hàng từ kho tổng");
        vendorService.saveProduct(vendor, product.getId(), form, null);
        entityManager.flush();

        form.setVersion(product.getVersion());
        form.setStock(13);
        form.setStockNote("Kiểm kê thiếu hai sản phẩm");
        vendorService.saveProduct(vendor, product.getId(), form, null);
        entityManager.flush();

        var movements = transactionRepository.findByProductIdOrderByCreatedAtAscIdAsc(product.getId());
        assertEquals(3, movements.size());
        assertEquals(InventoryTransactionType.RESTOCK, movements.get(1).getType());
        assertEquals(5, movements.get(1).getQuantityChange());
        assertEquals(10, movements.get(1).getStockBefore());
        assertEquals(15, movements.get(1).getStockAfter());
        assertEquals(vendor.getId(), movements.get(1).getActor().getId());
        assertEquals(InventoryTransactionType.ADJUSTMENT, movements.get(2).getType());
        assertEquals(-2, movements.get(2).getQuantityChange());
        assertEquals(15, movements.get(2).getStockBefore());
        assertEquals(13, movements.get(2).getStockAfter());
        assertEquals(3, inventoryService.forShop(shop, null, 0).getTotalElements());
        assertTrue(inventoryService.forShop(otherShop, product.getId(), 0).isEmpty());

        MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
            .perform(get("/vendor/inventory").with(user(vendor.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk());
    }

    @Test
    void stockChangeRequiresReasonAndRejectsStaleForm() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User vendor = vendor("inventory-check-" + suffix);
        shop(vendor, "inventory-check-shop-" + suffix);
        Category category = new Category();
        category.setName("Topping " + suffix);
        category.setSlug("topping");
        category = categoryRepository.save(category);
        ProductForm form = form(category.getId(), "INV-CHECK-" + suffix, 10);
        var product = vendorService.saveProduct(vendor, null, form, null);
        entityManager.flush();

        form.setVersion(product.getVersion());
        form.setStock(9);
        assertThrows(IllegalArgumentException.class,
            () -> vendorService.saveProduct(vendor, product.getId(), form, null));
        assertEquals(1, transactionRepository.findByProductIdOrderByCreatedAtAscIdAsc(product.getId()).size());

        form.setStockNote("Kiểm kê thiếu một sản phẩm");
        form.setVersion(product.getVersion() + 1);
        assertThrows(IllegalArgumentException.class,
            () -> vendorService.saveProduct(vendor, product.getId(), form, null));
        assertEquals(10, product.getStock());
    }

    private User vendor(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setFullName(username);
        user.setPasswordHash("encoded-password");
        user.setRole(Role.VENDOR);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private Shop shop(User vendor, String slug) {
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName(slug);
        shop.setSlug(slug);
        shop.setStatus(ShopStatus.APPROVED);
        return shopRepository.save(shop);
    }

    private ProductForm form(Long categoryId, String sku, int stock) {
        ProductForm form = new ProductForm();
        form.setName("Topping kiểm kê " + sku);
        form.setSku(sku);
        form.setCategoryId(categoryId);
        form.setDescription("Sản phẩm dùng để kiểm thử lịch sử nhập xuất kho.");
        form.setPrice(new BigDecimal("10000"));
        form.setStock(stock);
        return form;
    }
}
