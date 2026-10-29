package vn.iotstar;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductImageRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.ProductGalleryService;
import vn.iotstar.service.VendorService;

@ActiveProfiles("test")
@TestPropertySource(properties = "app.upload-dir=target/gallery-test-uploads")
@SpringBootTest
@Transactional
class ProductGalleryTests {
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductImageRepository imageRepository;
    @Autowired private ProductGalleryService galleryService;
    @Autowired private VendorService vendorService;
    @Autowired private EntityManager entityManager;
    @Autowired private WebApplicationContext context;

    @Test
    void vendorCanAddReorderChoosePrimaryAndRemoveImages() throws Exception {
        Fixture fixture = fixture();
        ProductForm form = form(fixture.category().getId(), fixture.suffix());
        Product product = vendorService.saveProduct(fixture.vendor(), null, form,
            image("front.png"), new MockMultipartFile[] {image("side.png"), image("top.png")},
            null, null, null);
        entityManager.flush();

        var images = imageRepository.findByProductIdOrderBySortOrderAscIdAsc(product.getId());
        assertEquals(3, images.size());
        assertTrue(images.getFirst().isPrimary());
        assertEquals(images.getFirst().getImageUrl(), product.getImageUrl());

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/products/" + product.getId()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("product-gallery-thumbs")));

        form.setVersion(product.getVersion());
        vendorService.saveProduct(fixture.vendor(), product.getId(), form, null, null,
            List.of(images.getFirst().getId()),
            List.of(images.get(2).getId(), images.get(1).getId(), images.getFirst().getId()),
            images.get(2).getId());
        entityManager.flush();

        var remaining = imageRepository.findByProductIdOrderBySortOrderAscIdAsc(product.getId());
        assertEquals(2, remaining.size());
        assertEquals(images.get(2).getId(), remaining.getFirst().getId());
        assertTrue(remaining.getFirst().isPrimary());
        assertEquals(remaining.getFirst().getImageUrl(), product.getImageUrl());
        assertEquals(0, remaining.getFirst().getSortOrder());
        assertEquals(1, remaining.get(1).getSortOrder());
    }

    @Test
    void existingSingleImageIsMigratedOnNextSaveAndForeignImageIsRejected() {
        Fixture fixture = fixture();
        Product product = new Product();
        product.setShop(fixture.shop());
        product.setCategory(fixture.category());
        product.setName("Trà sữa gallery " + fixture.suffix());
        product.setSlug("gallery-" + fixture.suffix());
        product.setSku("GAL-" + fixture.suffix());
        product.setDescription("Sản phẩm cũ chỉ có một ảnh đại diện trong cơ sở dữ liệu.");
        product.setPrice(new BigDecimal("30000"));
        product.setStock(10);
        product.setImageUrl("/uploads/products/legacy.png");
        product = productRepository.save(product);
        entityManager.flush();

        ProductForm form = form(fixture.category().getId(), fixture.suffix());
        form.setVersion(product.getVersion());
        vendorService.saveProduct(fixture.vendor(), product.getId(), form, null);
        entityManager.flush();
        assertEquals(1, galleryService.images(product).size());
        assertTrue(galleryService.images(product).getFirst().isPrimary());
        assertEquals("/uploads/products/legacy.png", product.getImageUrl());

        form.setVersion(product.getVersion());
        Long productId = product.getId();
        assertThrows(IllegalArgumentException.class,
            () -> vendorService.saveProduct(fixture.vendor(), productId, form, null, null,
                List.of(-1L), null, null));
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("gallery-vendor-" + suffix);
        user.setEmail(user.getUsername() + "@utetra.test");
        user.setFullName("Gallery Vendor");
        user.setPasswordHash("encoded-password");
        user.setRole(Role.VENDOR);
        user.setEnabled(true);
        user = userRepository.save(user);
        Shop shop = new Shop();
        shop.setOwner(user);
        shop.setName("Gallery shop " + suffix);
        shop.setSlug("gallery-shop-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shopRepository.save(shop);
        Category category = new Category();
        category.setName("Gallery topping " + suffix);
        category.setSlug("topping");
        category = categoryRepository.save(category);
        return new Fixture(user, shop, category, suffix);
    }

    private ProductForm form(Long categoryId, String suffix) {
        ProductForm form = new ProductForm();
        form.setName("Trà sữa gallery " + suffix);
        form.setSku("GAL-" + suffix);
        form.setCategoryId(categoryId);
        form.setDescription("Sản phẩm kiểm thử gallery nhiều ảnh trên trang chi tiết.");
        form.setPrice(new BigDecimal("30000"));
        form.setStock(10);
        return form;
    }

    private MockMultipartFile image(String name) {
        return new MockMultipartFile("image", name, "image/png", new byte[] {1, 2, 3});
    }

    private record Fixture(User vendor, Shop shop, Category category, String suffix) {}
}
