package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.ProductImage;
import vn.iotstar.entity.Shop;
import vn.iotstar.repository.ProductImageRepository;
import vn.iotstar.service.MediaStorageService;
import vn.iotstar.service.MediaValidationService;
import vn.iotstar.service.ProductGalleryService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductGalleryTests extends CustomerTestFixtures {

	private static final String PLACEHOLDER = "/images/product-placeholder.svg";

	@Autowired
	ProductImageRepository imageRepository;
	@Autowired
	EntityManager entityManager;

	private MediaStorageService storage;
	private ProductGalleryService gallery;

	@BeforeEach
	void galleryFixture() {
		// Use the real validation and database, with simulated uploads only.
		storage = mock(MediaStorageService.class);
		gallery = new ProductGalleryService(imageRepository, storage, new MediaValidationService());
	}

	@Test
	void emptyGalleryUsesPlaceholderAndDoesNotUploadAnything() {
		gallery.update(tea, null, null, null, null, null);

		assertThat(gallery.images(tea)).isEmpty();
		assertThat(tea.getImageUrl()).isEqualTo(PLACEHOLDER);
		verifyNoInteractions(storage);
	}

	@Test
	void legacyCoverIsMigratedOnceAndRemainsPrimary() {
		tea.setImageUrl("/uploads/products/legacy.png");

		gallery.update(tea, null, null, null, null, null);
		Long originalId = gallery.images(tea).getFirst().getId();
		gallery.update(tea, null, null, null, null, null);
		entityManager.flush();
		entityManager.clear();

		List<ProductImage> saved = gallery.images(tea);
		assertThat(saved).hasSize(1);
		assertThat(saved.getFirst().getId()).isEqualTo(originalId);
		assertThat(saved.getFirst().getImageUrl()).isEqualTo("/uploads/products/legacy.png");
		assertThat(saved.getFirst().isPrimary()).isTrue();
		assertThat(saved.getFirst().getSortOrder()).isZero();
		verifyNoInteractions(storage);
	}

	@Test
	void displayOrderPlacesPrimaryFirstThenSortOrderAndId() {
		ProductImage first = existing("first.png", 0, false);
		ProductImage second = existing("second.png", 0, false);
		ProductImage third = existing("third.png", 1, false);
		ProductImage primary = existing("primary.png", 9, true);

		assertThat(gallery.displayImages(tea)).extracting(ProductImage::getId).containsExactly(primary.getId(),
				first.getId(), second.getId(), third.getId());
	}

	@Test
	void partialOrderIgnoresDuplicateIdsAndKeepsUnlistedImages() {
		ProductImage first = existing("first.png", 0, true);
		ProductImage second = existing("second.png", 1, false);
		ProductImage third = existing("third.png", 2, false);

		gallery.update(tea, null, null, null, List.of(third.getId(), third.getId(), first.getId()), second.getId());
		entityManager.flush();

		List<ProductImage> saved = gallery.images(tea);
		assertThat(saved).extracting(ProductImage::getId).containsExactly(third.getId(), first.getId(), second.getId());
		assertThat(saved).extracting(ProductImage::getSortOrder).containsExactly(0, 1, 2);
		assertPrimary(saved, second.getImageUrl());
		verifyNoInteractions(storage);
	}

	@Test
	void removingPrimaryChoosesFirstRemainingImageEvenIfRemovedIdWasSelected() {
		ProductImage first = existing("first.png", 0, true);
		ProductImage second = existing("second.png", 1, false);
		ProductImage third = existing("third.png", 2, false);

		gallery.update(tea, null, null, List.of(first.getId()), null, first.getId());

		List<ProductImage> saved = gallery.images(tea);
		assertThat(saved).extracting(ProductImage::getId).containsExactly(second.getId(), third.getId());
		assertThat(saved).extracting(ProductImage::getSortOrder).containsExactly(0, 1);
		assertThat(imageRepository.existsById(first.getId())).isFalse();
		assertPrimary(saved, second.getImageUrl());
	}

	@Test
	void removingAllImagesDeletesRowsAndRestoresPlaceholder() {
		ProductImage first = existing("first.png", 0, true);
		ProductImage second = existing("second.png", 1, false);

		gallery.update(tea, null, null, List.of(first.getId(), second.getId()), null, null);
		entityManager.flush();

		assertThat(gallery.images(tea)).isEmpty();
		assertThat(imageRepository.existsById(first.getId())).isFalse();
		assertThat(imageRepository.existsById(second.getId())).isFalse();
		assertThat(tea.getImageUrl()).isEqualTo(PLACEHOLDER);
		verifyNoInteractions(storage);
	}

	@Test
	void newCoverTakesPriorityOverPreviouslySelectedPrimary() {
		existing("old-cover.png", 0, true);
		ProductImage selected = existing("selected.png", 1, false);
		MockMultipartFile cover = upload("new-cover.png");
		MockMultipartFile extra = upload("extra.png");

		gallery.update(tea, cover, new MultipartFile[] { extra }, null, null, selected.getId());

		List<ProductImage> saved = gallery.images(tea);
		assertThat(saved).hasSize(4);
		assertPrimary(saved, "/uploads/products/new-cover.png");
		assertThat(saved).extracting(ProductImage::getSortOrder).containsExactly(0, 1, 2, 3);
		assertThat(selected.isPrimary()).isFalse();
		verify(storage).upload(cover, "products");
		verify(storage).upload(extra, "products");
	}

	@Test
	void firstGalleryUploadBecomesPrimaryWhenThereIsNoCover() {
		MockMultipartFile first = upload("first.png");
		MockMultipartFile second = upload("second.png");

		gallery.update(tea, null, new MultipartFile[] { first, second }, null, null, null);
		entityManager.flush();
		entityManager.clear();

		List<ProductImage> saved = gallery.images(tea);
		assertThat(saved).extracting(ProductImage::getImageUrl).containsExactly("/uploads/products/first.png",
				"/uploads/products/second.png");
		assertPrimary(saved, "/uploads/products/first.png");
	}

	@Test
	void exactlyEightImagesIncludingTheCoverAreAccepted() {
		MockMultipartFile cover = upload("cover.png");
		MultipartFile[] uploads = new MultipartFile[7];
		for (int i = 0; i < uploads.length; i++) {
			uploads[i] = upload("gallery-" + i + ".png");
		}

		gallery.update(tea, cover, uploads, null, null, null);
		entityManager.flush();

		List<ProductImage> saved = gallery.images(tea);
		assertThat(saved).hasSize(8);
		assertThat(saved).extracting(ProductImage::getSortOrder).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
		assertPrimary(saved, "/uploads/products/cover.png");
		verify(storage).upload(cover, "products");
		for (MultipartFile file : uploads) {
			verify(storage).upload(file, "products");
		}
	}

	@Test
	void coverAndGalleryUploadsBothCountTowardLimitBeforeAnythingIsUploaded() {
		List<ProductImage> original = existingImages(7);
		String originalCover = tea.getImageUrl();
		MockMultipartFile cover = imageFile("cover.png");
		MockMultipartFile extra = imageFile("extra.png");

		assertThatThrownBy(() -> gallery.update(tea, cover, new MultipartFile[] { extra }, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(gallery.images(tea)).extracting(ProductImage::getId)
				.containsExactlyElementsOf(original.stream().map(ProductImage::getId).toList());
		assertThat(tea.getImageUrl()).isEqualTo(originalCover);
		verifyNoInteractions(storage);
	}

	@Test
	void removingOneImageMakesRoomForAReplacementAtTheLimit() {
		List<ProductImage> original = existingImages(8);
		MockMultipartFile replacement = upload("replacement.png");

		gallery.update(tea, null, new MultipartFile[] { replacement }, List.of(original.getFirst().getId()), null,
				null);

		List<ProductImage> saved = gallery.images(tea);
		assertThat(saved).hasSize(8);
		assertThat(imageRepository.existsById(original.getFirst().getId())).isFalse();
		assertThat(saved.getLast().getImageUrl()).isEqualTo("/uploads/products/replacement.png");
		assertThat(saved).extracting(ProductImage::getSortOrder).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
		assertPrimary(saved, original.get(1).getImageUrl());
	}

	@Test
	void cannotRemoveAnImageFromAnotherShopsProduct() {
		ProductImage own = existing("own.png", 0, true);
		ProductImage foreign = foreignImage();

		assertThatThrownBy(() -> gallery.update(tea, null, null, List.of(foreign.getId()), null, null))
				.isInstanceOf(IllegalArgumentException.class);

		assertForeignOperationChangedNothing(own, foreign);
	}

	@Test
	void cannotIncludeAnImageFromAnotherShopInTheOrder() {
		ProductImage own = existing("own.png", 0, true);
		ProductImage foreign = foreignImage();

		assertThatThrownBy(() -> gallery.update(tea, null, null, null, List.of(own.getId(), foreign.getId()), null))
				.isInstanceOf(IllegalArgumentException.class);

		assertForeignOperationChangedNothing(own, foreign);
	}

	@Test
	void cannotChooseAnotherShopsImageAsPrimary() {
		ProductImage own = existing("own.png", 0, true);
		ProductImage foreign = foreignImage();

		assertThatThrownBy(() -> gallery.update(tea, null, null, null, null, foreign.getId()))
				.isInstanceOf(IllegalArgumentException.class);

		assertForeignOperationChangedNothing(own, foreign);
	}

	@Test
	void invalidCoverOrGalleryMediaIsRejectedBeforeRemovingExistingImages() {
		ProductImage own = existing("own.png", 0, true);
		MockMultipartFile invalidCover = new MockMultipartFile("file", "program.exe", "application/octet-stream",
				new byte[] { 1, 2 });
		MockMultipartFile video = new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[] { 1, 2 });

		assertThatThrownBy(() -> gallery.update(tea, invalidCover, null, List.of(own.getId()), null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> gallery.update(tea, imageFile("valid.png"), new MultipartFile[] { video },
				List.of(own.getId()), null, null)).isInstanceOf(IllegalArgumentException.class);

		assertThat(gallery.images(tea)).extracting(ProductImage::getId).containsExactly(own.getId());
		assertPrimary(gallery.images(tea), own.getImageUrl());
		verifyNoInteractions(storage);
	}

	@Test
	void emptyCoverAndNullOrEmptyGalleryEntriesKeepTheExistingImage() {
		ProductImage own = existing("own.png", 0, true);
		MockMultipartFile empty = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

		gallery.update(tea, empty, new MultipartFile[] { null, empty }, null, null, null);

		assertThat(gallery.images(tea)).extracting(ProductImage::getId).containsExactly(own.getId());
		assertPrimary(gallery.images(tea), own.getImageUrl());
		verifyNoInteractions(storage);
	}

	private MockMultipartFile imageFile(String filename) {
		return new MockMultipartFile("file", filename, "image/png", new byte[] { 1, 2, 3 });
	}

	private MockMultipartFile upload(String filename) {
		MockMultipartFile file = imageFile(filename);
		when(storage.upload(same(file), eq("products"))).thenReturn("/uploads/products/" + filename);
		return file;
	}

	private ProductImage existing(String filename, int sortOrder, boolean primary) {
		ProductImage image = new ProductImage();
		image.setProduct(tea);
		image.setImageUrl("/uploads/products/" + filename);
		image.setSortOrder(sortOrder);
		image.setPrimary(primary);
		imageRepository.saveAndFlush(image);
		if (primary) {
			tea.setImageUrl(image.getImageUrl());
		}
		return image;
	}

	private List<ProductImage> existingImages(int count) {
		List<ProductImage> result = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			result.add(existing("old-" + i + ".png", i, i == 0));
		}
		return result;
	}

	private ProductImage foreignImage() {
		Shop shop = new Shop();
		shop.setOwner(customer);
		shop.setName("Foreign gallery shop");
		shop.setSlug(UUID.randomUUID().toString());
		shop.setStatus(ShopStatus.APPROVED);
		shops.saveAndFlush(shop);
		Product product = new Product();
		product.setShop(shop);
		product.setCategory(tea.getCategory());
		product.setName("Foreign gallery product");
		product.setSlug(UUID.randomUUID().toString());
		product.setSku(UUID.randomUUID().toString());
		product.setDescription("Sản phẩm kiểm thử ảnh thuộc một cửa hàng khác.");
		product.setPrice(new BigDecimal("25000"));
		product.setStock(10);
		products.saveAndFlush(product);
		ProductImage image = new ProductImage();
		image.setProduct(product);
		image.setImageUrl("/uploads/products/foreign.png");
		image.setPrimary(true);
		return imageRepository.saveAndFlush(image);
	}

	private void assertPrimary(List<ProductImage> saved, String url) {
		assertThat(saved.stream().filter(ProductImage::isPrimary).map(ProductImage::getImageUrl).toList())
				.containsExactly(url);
		assertThat(tea.getImageUrl()).isEqualTo(url);
	}

	private void assertForeignOperationChangedNothing(ProductImage own, ProductImage foreign) {
		entityManager.flush();
		assertThat(gallery.images(tea)).extracting(ProductImage::getId).containsExactly(own.getId());
		assertPrimary(gallery.images(tea), own.getImageUrl());
		ProductImage untouched = imageRepository.findById(foreign.getId()).orElseThrow();
		assertThat(untouched.getImageUrl()).isEqualTo("/uploads/products/foreign.png");
		assertThat(untouched.isPrimary()).isTrue();
		assertThat(untouched.getSortOrder()).isZero();
		verifyNoInteractions(storage);
	}
}
