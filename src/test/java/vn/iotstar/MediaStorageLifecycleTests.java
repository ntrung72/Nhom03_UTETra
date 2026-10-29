package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import vn.iotstar.service.MediaStorageService;
import vn.iotstar.service.ProductGalleryService;
import vn.iotstar.service.ProfileService;

@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:utetra-media-lifecycle;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "app.upload-dir=target/media-lifecycle-test-uploads",
    "app.private-upload-dir=target/media-lifecycle-test-private-uploads",
    "cloudinary.cloud-name=", "cloudinary.api-key=", "cloudinary.api-secret="
})
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaStorageLifecycleTests {
    private static final Path UPLOAD_ROOT = Path.of("target/media-lifecycle-test-uploads").toAbsolutePath();
    private static final Path PRIVATE_ROOT = Path.of("target/media-lifecycle-test-private-uploads").toAbsolutePath();

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MediaStorageService storageService;
    @Autowired private ProfileService profileService;
    @Autowired private ProductGalleryService galleryService;
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductImageRepository imageRepository;

    @Test
    void quarantinesOldProofFilesOutsideThePublicDirectory() throws Exception {
        Path oldFile = UPLOAD_ROOT.resolve("returns").resolve(UUID.randomUUID() + ".png");
        Files.createDirectories(oldFile.getParent());
        Files.write(oldFile, new byte[] {1, 2, 3});
        String legacyReference = "/uploads/returns/" + oldFile.getFileName();

        storageService.quarantineUnreferencedLegacyFiles();

        Path privateFile = PRIVATE_ROOT.resolve("returns").resolve(oldFile.getFileName());
        assertFalse(Files.exists(oldFile));
        assertTrue(Files.exists(privateFile));
        assertTrue(storageService.readPrivate(legacyReference, "returns").resource().exists());
        storageService.deleteAfterCommit(legacyReference);
        assertFalse(Files.exists(privateFile));
    }

    @Test
    void legacyProofMovesOnlyAfterSuccessfulTransaction() throws Exception {
        Path oldFile = UPLOAD_ROOT.resolve("delivery-proofs").resolve(UUID.randomUUID() + ".png");
        Files.createDirectories(oldFile.getParent());
        Files.write(oldFile, new byte[] {1, 2, 3});
        String oldReference = "/uploads/delivery-proofs/" + oldFile.getFileName();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        try {
            String rolledBack = transactions.execute(status -> {
                String reference = storageService.migrateLegacyPrivateMedia(oldReference, "delivery-proofs");
                assertTrue(Files.exists(oldFile));
                status.setRollbackOnly();
                return reference;
            });
            assertTrue(Files.exists(oldFile));
            assertFalse(Files.exists(PRIVATE_ROOT.resolve(rolledBack.substring("private:".length()))));

            String migrated = transactions.execute(status ->
                storageService.migrateLegacyPrivateMedia(oldReference, "delivery-proofs"));
            assertFalse(Files.exists(oldFile));
            assertTrue(Files.exists(PRIVATE_ROOT.resolve(migrated.substring("private:".length()))));
            assertTrue(storageService.readPrivate(migrated, "delivery-proofs").resource().exists());
            storageService.deleteAfterCommit(migrated);
        } finally {
            Files.deleteIfExists(oldFile);
        }
    }

    @Test
    void uploadRollbackDeletesNewFileButDeletingOldFileWaitsForCommit() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        String rolledBack = transactions.execute(status -> {
            String url = storageService.upload(image("rolled-back.png"), "avatars");
            assertTrue(Files.exists(localPath(url)));
            status.setRollbackOnly();
            return url;
        });
        assertFalse(Files.exists(localPath(rolledBack)));

        String retained = transactions.execute(status -> storageService.upload(image("retained.png"), "avatars"));
        assertTrue(Files.exists(localPath(retained)));
        transactions.execute(status -> {
            storageService.deleteAfterCommit(retained);
            assertTrue(Files.exists(localPath(retained)));
            status.setRollbackOnly();
            return null;
        });
        assertTrue(Files.exists(localPath(retained)));
        transactions.execute(status -> {
            storageService.deleteAfterCommit(retained);
            assertTrue(Files.exists(localPath(retained)));
            return null;
        });
        assertFalse(Files.exists(localPath(retained)));
    }

    @Test
    void replacingAndRemovingAvatarDeletesPreviousFiles() {
        User user = user("avatar-cleanup-" + UUID.randomUUID().toString().substring(0, 8));
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        transactions.execute(status -> {
            profileService.updateAvatar(userRepository.findById(user.getId()).orElseThrow(), image("first.png"));
            return null;
        });
        String first = userRepository.findById(user.getId()).orElseThrow().getAvatarUrl();
        assertTrue(Files.exists(localPath(first)));

        transactions.execute(status -> {
            profileService.updateAvatar(userRepository.findById(user.getId()).orElseThrow(), image("second.png"));
            assertTrue(Files.exists(localPath(first)));
            return null;
        });
        String second = userRepository.findById(user.getId()).orElseThrow().getAvatarUrl();
        assertFalse(Files.exists(localPath(first)));
        assertTrue(Files.exists(localPath(second)));

        transactions.execute(status -> {
            profileService.removeAvatar(userRepository.findById(user.getId()).orElseThrow());
            return null;
        });
        assertFalse(Files.exists(localPath(second)));
    }

    @Test
    void galleryRemovalDeletesOnlyAfterCommitAndRollbackKeepsOldImage() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User vendor = user("gallery-cleanup-" + suffix);
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("Gallery cleanup " + suffix);
        shop.setSlug("gallery-cleanup-" + suffix);
        shop.setStatus(ShopStatus.APPROVED);
        shop = shopRepository.save(shop);
        Category category = new Category();
        category.setName("Gallery cleanup category");
        category.setSlug("gallery-cleanup-category-" + suffix);
        category = categoryRepository.save(category);
        Product product = new Product();
        product.setShop(shop);
        product.setCategory(category);
        product.setName("Trà sữa gallery cleanup");
        product.setSlug("gallery-cleanup-product-" + suffix);
        product.setSku("GAL-CLEAN-" + suffix);
        product.setDescription("Kiểm thử vòng đời file ảnh sản phẩm.");
        product.setPrice(new BigDecimal("30000"));
        product.setStock(5);
        product = productRepository.save(product);
        Long productId = product.getId();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        transactions.execute(status -> {
            galleryService.update(productRepository.findById(productId).orElseThrow(), image("front.png"),
                new MockMultipartFile[] {image("side.png")}, null, null, null);
            return null;
        });
        var originalImages = imageRepository.findByProductIdOrderBySortOrderAscIdAsc(productId);
        Long removedId = originalImages.getFirst().getId();
        String removedUrl = originalImages.getFirst().getImageUrl();
        String retainedUrl = originalImages.get(1).getImageUrl();
        assertTrue(Files.exists(localPath(removedUrl)));

        String rolledBackCover = transactions.execute(status -> {
            Product current = productRepository.findById(productId).orElseThrow();
            galleryService.update(current, image("rollback.png"), null, List.of(removedId), null, null);
            String newUrl = current.getImageUrl();
            assertTrue(Files.exists(localPath(removedUrl)));
            status.setRollbackOnly();
            return newUrl;
        });
        assertTrue(Files.exists(localPath(removedUrl)));
        assertFalse(Files.exists(localPath(rolledBackCover)));

        transactions.execute(status -> {
            galleryService.update(productRepository.findById(productId).orElseThrow(), null, null,
                List.of(removedId), null, null);
            assertTrue(Files.exists(localPath(removedUrl)));
            return null;
        });
        assertFalse(Files.exists(localPath(removedUrl)));
        assertTrue(Files.exists(localPath(retainedUrl)));

        Long retainedId = imageRepository.findByProductIdOrderBySortOrderAscIdAsc(productId).getFirst().getId();
        transactions.execute(status -> {
            galleryService.update(productRepository.findById(productId).orElseThrow(), null, null,
                List.of(retainedId), null, null);
            return null;
        });
        assertFalse(Files.exists(localPath(retainedUrl)));
    }

    private User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(username);
        user.setRole(Role.VENDOR);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private MockMultipartFile image(String name) {
        return new MockMultipartFile("image", name, "image/png", new byte[] {1, 2, 3});
    }

    private Path localPath(String url) {
        return UPLOAD_ROOT.resolve(url.substring("/uploads/".length()));
    }
}
