package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.ShopForm;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.OrderItem;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.service.MediaStorageService;
import vn.iotstar.service.ProductGalleryService;
import vn.iotstar.service.VendorService;
import vn.iotstar.support.MediaTestFiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaStorageLifecycleTests {

	private static final String PLACEHOLDER = "/images/product-placeholder.svg";

	private static final Path TEST_ROOT = createTestRoot();
	private static final Path PUBLIC_ROOT = TEST_ROOT.resolve("public");
	private static final Path PRIVATE_ROOT = TEST_ROOT.resolve("private");

	private static final String DATABASE_NAME = "utetra_media_" + UUID.randomUUID().toString().replace("-", "");

	@Autowired
	EntityManager entityManager;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	MediaStorageService storage;

	@Autowired
	ProductGalleryService gallery;

	@Autowired
	VendorService vendor;

	private TransactionTemplate transactions;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url",
				() -> "jdbc:h2:mem:" + DATABASE_NAME + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");

		registry.add("app.upload-dir", PUBLIC_ROOT::toString);
		registry.add("app.private-upload-dir", PRIVATE_ROOT::toString);

		registry.add("cloudinary.cloud-name", () -> "");
		registry.add("cloudinary.api-key", () -> "");
		registry.add("cloudinary.api-secret", () -> "");
	}

	@BeforeEach
	void setUp() {
		transactions = new TransactionTemplate(transactionManager);
	}

	@AfterAll
	static void removeTestFiles() throws IOException {
		// Chỉ dọn thư mục tạm do lớp test này tự tạo.
		List<Path> paths;

		try (var walk = Files.walk(TEST_ROOT)) {
			paths = walk.sorted(Comparator.reverseOrder()).toList();
		}

		for (Path path : paths) {
			Files.deleteIfExists(path);
		}
	}

	@Test
	void committedUploadsKeepPublicAndPrivateFilesInSeparateRoots() {
		List<String> references = transactions
				.execute(status -> List.of(storage.upload(MediaTestFiles.image("product.png"), "products"),
						storage.upload(MediaTestFiles.image("proof.png"), "delivery-proofs")));

		assertThat(references).hasSize(2);

		String publicUrl = references.get(0);
		String privateReference = references.get(1);

		assertThat(publicUrl).startsWith("/uploads/products/");
		assertThat(privateReference).startsWith("private:delivery-proofs/");

		assertThat(filePath(publicUrl)).isRegularFile();
		assertThat(filePath(privateReference)).isRegularFile();

		assertThat(PUBLIC_ROOT.resolve(privateReference.substring("private:".length()))).doesNotExist();

		assertThat(storage.readPrivate(privateReference, "delivery-proofs").resource().exists()).isTrue();
	}

	@Test
	void rollbackRemovesEveryNewPublicAndPrivateUpload() {
		List<String> references = transactions.execute(status -> {
			String publicUrl = storage.upload(MediaTestFiles.image("product.png"), "products");

			String privateReference = storage.upload(MediaTestFiles.image("return.png"), "returns");

			assertThat(filePath(publicUrl)).isRegularFile();
			assertThat(filePath(privateReference)).isRegularFile();

			status.setRollbackOnly();

			return List.of(publicUrl, privateReference);
		});

		assertThat(references).hasSize(2);

		references.forEach(reference -> assertThat(filePath(reference)).doesNotExist());
	}

	@Test
	void deletingAnUnreferencedFileWaitsForCommitAndSurvivesRollback() {
		String url = transactions.execute(status -> storage.upload(MediaTestFiles.image("unused.png"), "products"));

		transactions.executeWithoutResult(status -> {
			storage.deleteAfterCommit(url);

			assertThat(filePath(url)).isRegularFile();

			status.setRollbackOnly();
		});

		assertThat(filePath(url)).isRegularFile();

		transactions.executeWithoutResult(status -> {
			storage.deleteAfterCommit(url);

			assertThat(filePath(url)).isRegularFile();
		});

		assertThat(filePath(url)).doesNotExist();
	}

	@Test
	void galleryRollbackRestoresRowsAndCoverAndDeletesOnlyNewFiles() {
		Fixture fixture = createFixture();
		List<ImageState> original = addGallery(fixture.productId());

		ImageState front = original.get(0);
		ImageState side = original.get(1);

		String replacement = transactions.execute(status -> {
			Product product = entityManager.find(Product.class, fixture.productId());

			gallery.update(product, MediaTestFiles.image("replacement.png"), null, List.of(front.id()), null, null);

			entityManager.flush();

			String newUrl = product.getImageUrl();

			assertThat(newUrl).isNotEqualTo(front.url());
			assertThat(filePath(newUrl)).isRegularFile();
			assertThat(filePath(front.url())).isRegularFile();

			status.setRollbackOnly();

			return newUrl;
		});

		assertThat(images(fixture.productId())).containsExactlyElementsOf(original);

		assertThat(productCover(fixture.productId())).isEqualTo(front.url());

		assertThat(filePath(front.url())).isRegularFile();
		assertThat(filePath(side.url())).isRegularFile();
		assertThat(filePath(replacement)).doesNotExist();
	}

	@Test
	void removingGalleryImagesDeletesFilesAfterCommitAndUpdatesCover() {
		Fixture fixture = createFixture();
		List<ImageState> original = addGallery(fixture.productId());

		ImageState front = original.get(0);
		ImageState side = original.get(1);

		transactions.executeWithoutResult(status -> {
			Product product = entityManager.find(Product.class, fixture.productId());

			gallery.update(product, null, null, List.of(front.id()), null, null);

			entityManager.flush();

			assertThat(product.getImageUrl()).isEqualTo(side.url());
			assertThat(filePath(front.url())).isRegularFile();
		});

		assertThat(filePath(front.url())).doesNotExist();
		assertThat(filePath(side.url())).isRegularFile();

		assertThat(images(fixture.productId())).containsExactly(side);

		assertThat(productCover(fixture.productId())).isEqualTo(side.url());

		transactions.executeWithoutResult(status -> {
			Product product = entityManager.find(Product.class, fixture.productId());

			gallery.update(product, null, null, List.of(side.id()), null, null);

			assertThat(filePath(side.url())).isRegularFile();
		});

		assertThat(filePath(side.url())).doesNotExist();
		assertThat(images(fixture.productId())).isEmpty();

		assertThat(productCover(fixture.productId())).isEqualTo(PLACEHOLDER);
	}

	@Test
	void anotherProductReferenceKeepsTheFileUntilItsLastReferenceIsRemoved() {
		Fixture fixture = createFixture();
		List<ImageState> original = addGallery(fixture.productId());

		ImageState front = original.get(0);

		Long otherProductId = transactions.execute(status -> {
			Product source = entityManager.find(Product.class, fixture.productId());

			Product otherProduct = newProduct(source.getShop(), source.getCategory());

			otherProduct.setImageUrl(front.url());
			entityManager.persist(otherProduct);

			return otherProduct.getId();
		});

		transactions.executeWithoutResult(status -> {
			Product product = entityManager.find(Product.class, fixture.productId());

			gallery.update(product, null, null, List.of(front.id()), null, null);
		});

		assertThat(filePath(front.url())).isRegularFile();

		transactions.executeWithoutResult(status -> {
			Product otherProduct = entityManager.find(Product.class, otherProductId);

			otherProduct.setImageUrl(PLACEHOLDER);

			storage.deleteAfterCommit(front.url());

			assertThat(filePath(front.url())).isRegularFile();
		});

		assertThat(filePath(front.url())).doesNotExist();
	}

	@Test
	void anOrderItemSnapshotKeepsAnImageRemovedFromTheProductGallery() {
		Fixture fixture = createFixture();
		ImageState front = addGallery(fixture.productId()).get(0);

		Long itemId = transactions.execute(status -> {
			Product product = entityManager.find(Product.class, fixture.productId());

			CustomerOrder order = newOrder(fixture);

			OrderItem item = new OrderItem();
			item.setOrder(order);
			item.setProduct(product);
			item.setProductName(product.getName());
			item.setImageUrl(front.url());
			item.setUnitPrice(product.getPrice());
			item.setQuantity(1);
			item.setLineTotal(product.getPrice());

			order.getItems().add(item);

			entityManager.persist(order);
			entityManager.flush();

			return item.getId();
		});

		transactions.executeWithoutResult(status -> {
			Product product = entityManager.find(Product.class, fixture.productId());

			gallery.update(product, null, null, List.of(front.id()), null, null);
		});

		assertThat(images(fixture.productId())).noneMatch(image -> image.id().equals(front.id()));

		assertThat(productCover(fixture.productId())).isNotEqualTo(front.url());

		String snapshotUrl = transactions.execute(status -> entityManager.find(OrderItem.class, itemId).getImageUrl());

		assertThat(snapshotUrl).isEqualTo(front.url());
		assertThat(filePath(front.url())).isRegularFile();
	}

	@Test
	void aDeliveryProofReferenceProtectsThePrivateFile() {
		Fixture fixture = createFixture();

		ProofState proof = transactions.execute(status -> {
			String reference = storage.upload(MediaTestFiles.image("delivery.png"), "delivery-proofs");

			CustomerOrder order = newOrder(fixture);
			order.setDeliveryProofUrl(reference);

			entityManager.persist(order);

			return new ProofState(order.getId(), reference);
		});

		transactions.executeWithoutResult(status -> storage.deleteAfterCommit(proof.reference()));

		assertThat(filePath(proof.reference())).isRegularFile();

		assertThat(storage.readPrivate(proof.reference(), "delivery-proofs").resource().exists()).isTrue();

		transactions.executeWithoutResult(status -> {
			entityManager.find(CustomerOrder.class, proof.orderId()).setDeliveryProofUrl(null);

			storage.deleteAfterCommit(proof.reference());

			assertThat(filePath(proof.reference())).isRegularFile();
		});

		assertThat(filePath(proof.reference())).doesNotExist();
	}

	@Test
	void shopReplacementRollsBackSafelyThenDeletesOldImageOnCommit() {
		Fixture fixture = createFixture();

		String original = transactions.execute(status -> {
			String url = storage.upload(MediaTestFiles.image("shop.png"), "shops");

			entityManager.find(Shop.class, fixture.shopId()).setImageUrl(url);

			return url;
		});

		String rolledBack = transactions.execute(status -> {
			User owner = entityManager.find(User.class, fixture.ownerId());

			vendor.updateShop(owner, shopForm(), MediaTestFiles.image("rollback.png"));

			entityManager.flush();

			String replacement = entityManager.find(Shop.class, fixture.shopId()).getImageUrl();

			assertThat(replacement).isNotEqualTo(original);
			assertThat(filePath(original)).isRegularFile();
			assertThat(filePath(replacement)).isRegularFile();

			status.setRollbackOnly();

			return replacement;
		});

		String current = transactions.execute(status -> entityManager.find(Shop.class, fixture.shopId()).getImageUrl());

		assertThat(current).isEqualTo(original);
		assertThat(filePath(original)).isRegularFile();
		assertThat(filePath(rolledBack)).doesNotExist();

		String committed = transactions.execute(status -> {
			User owner = entityManager.find(User.class, fixture.ownerId());

			vendor.updateShop(owner, shopForm(), MediaTestFiles.image("committed.png"));

			String replacement = entityManager.find(Shop.class, fixture.shopId()).getImageUrl();

			assertThat(filePath(original)).isRegularFile();

			return replacement;
		});

		String saved = transactions.execute(status -> entityManager.find(Shop.class, fixture.shopId()).getImageUrl());

		assertThat(saved).isEqualTo(committed);
		assertThat(filePath(original)).doesNotExist();
		assertThat(filePath(committed)).isRegularFile();
	}

	private Fixture createFixture() {
		return transactions.execute(status -> {
			String key = UUID.randomUUID().toString();

			User owner = new User();
			owner.setUsername("media-" + key);
			owner.setEmail(key + "@test.vn");
			owner.setFullName("Media lifecycle test");
			owner.setPasswordHash("test-password-hash");
			owner.setEnabled(true);

			entityManager.persist(owner);

			Shop shop = new Shop();
			shop.setOwner(owner);
			shop.setName("UTETra media test");
			shop.setSlug("media-shop-" + key);
			shop.setStatus(ShopStatus.APPROVED);
			shop.setEnabled(true);

			entityManager.persist(shop);

			Category category = new Category();
			category.setName("Media test");
			category.setSlug("media-category-" + key);

			entityManager.persist(category);

			Product product = newProduct(shop, category);

			entityManager.persist(product);
			entityManager.flush();

			return new Fixture(owner.getId(), shop.getId(), product.getId());
		});
	}

	private Product newProduct(Shop shop, Category category) {
		String key = UUID.randomUUID().toString();

		Product product = new Product();
		product.setShop(shop);
		product.setCategory(category);
		product.setName("Trà sữa media test");
		product.setSlug("media-product-" + key);
		product.setSku("MEDIA-" + key);
		product.setDescription("Sản phẩm dùng để kiểm tra vòng đời ảnh.");
		product.setPrice(new BigDecimal("35000"));
		product.setStock(10);
		product.setImageUrl(PLACEHOLDER);

		return product;
	}

	private CustomerOrder newOrder(Fixture fixture) {
		CustomerOrder order = new CustomerOrder();

		order.setCode("MEDIA" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));

		order.setUser(entityManager.find(User.class, fixture.ownerId()));
		order.setShop(entityManager.find(Shop.class, fixture.shopId()));
		order.setReceiverName("Người nhận test");
		order.setReceiverPhone("0901234567");
		order.setShippingAddress("01 Võ Văn Ngân, Thành phố Hồ Chí Minh");

		order.setSubtotal(new BigDecimal("35000"));
		order.setDiscountAmount(BigDecimal.ZERO);
		order.setProductDiscount(BigDecimal.ZERO);
		order.setVoucherDiscount(BigDecimal.ZERO);
		order.setShippingFee(BigDecimal.ZERO);
		order.setShippingDiscount(BigDecimal.ZERO);
		order.setFinalShippingFee(BigDecimal.ZERO);
		order.setTotalAmount(new BigDecimal("35000"));

		return order;
	}

	private ShopForm shopForm() {
		ShopForm form = new ShopForm();

		form.setName("UTETra media test");
		form.setDescription("Cửa hàng kiểm tra cập nhật ảnh và giao dịch dữ liệu.");
		form.setPhone("0901234567");
		form.setEmail("media-shop@test.vn");
		form.setAddress("01 Võ Văn Ngân");
		form.setProvince("Thành phố Hồ Chí Minh");
		form.setDistrict("Thủ Đức");
		form.setWard("Linh Chiểu");
		form.setOpeningTime(LocalTime.of(8, 0));
		form.setClosingTime(LocalTime.of(22, 0));

		return form;
	}

	private List<ImageState> addGallery(Long productId) {
		return transactions.execute(status -> {
			Product product = entityManager.find(Product.class, productId);

			gallery.update(product, MediaTestFiles.image("front.png"),
					new MultipartFile[] { MediaTestFiles.image("side.png") }, null, null, null);

			entityManager.flush();

			return imageStates(product);
		});
	}

	private List<ImageState> images(Long productId) {
		return transactions.execute(status -> imageStates(entityManager.find(Product.class, productId)));
	}

	private List<ImageState> imageStates(Product product) {
		return gallery.images(product).stream().map(image -> new ImageState(image.getId(), image.getImageUrl()))
				.toList();
	}

	private String productCover(Long productId) {
		return transactions.execute(status -> entityManager.find(Product.class, productId).getImageUrl());
	}

	private Path filePath(String reference) {
		assertThat(reference).isNotBlank();

		Path root;
		String relative;

		if (reference.startsWith("/uploads/")) {
			root = PUBLIC_ROOT;
			relative = reference.substring("/uploads/".length());
		} else if (reference.startsWith("private:")) {
			root = PRIVATE_ROOT;
			relative = reference.substring("private:".length());
		} else {
			throw new AssertionError("Đường dẫn media không hợp lệ: " + reference);
		}

		Path path = root.resolve(relative).normalize();

		assertThat(path.startsWith(root)).isTrue();

		return path;
	}

	private static Path createTestRoot() {
		try {
			return Files.createTempDirectory("utetra-media-lifecycle-").toAbsolutePath().normalize();
		} catch (IOException ex) {
			throw new UncheckedIOException("Không tạo được thư mục media test.", ex);
		}
	}

	private record Fixture(Long ownerId, Long shopId, Long productId) {
	}

	private record ImageState(Long id, String url) {
	}

	private record ProofState(Long orderId, String reference) {
	}
}