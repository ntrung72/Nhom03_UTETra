package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.entity.ProductImage;
import vn.iotstar.entity.ProductSize;
import vn.iotstar.entity.ProductTopping;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.Topping;
import vn.iotstar.repository.ProductImageRepository;
import vn.iotstar.repository.ProductSizeRepository;
import vn.iotstar.repository.ProductToppingRepository;
import vn.iotstar.repository.ToppingRepository;
import vn.iotstar.service.MediaStorageService;
import vn.iotstar.service.MediaValidationService;
import vn.iotstar.service.ProductGalleryService;
import vn.iotstar.service.ProductOptionService;
import vn.iotstar.service.ProductPricingService;
import vn.iotstar.service.ProductPricingService.ProductPrice;
import vn.iotstar.support.MediaTestFiles;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductGalleryRegressionTests extends CustomerTestFixtures {

	private static final String PLACEHOLDER = "/images/product-placeholder.svg";

	@Autowired
	EntityManager entityManager;

	@Autowired
	ProductImageRepository imageRepository;

	@Autowired
	ProductSizeRepository sizeRepository;

	@Autowired
	ProductToppingRepository toppingLinks;

	@Autowired
	ToppingRepository toppingRepository;

	@Autowired
	ProductOptionService options;

	@Autowired
	ProductPricingService pricing;

	private MediaStorageService storage;
	private ProductGalleryService gallery;

	@BeforeEach
	void galleryFixture() {
		tea.setImageUrl(PLACEHOLDER);

		storage = mock(MediaStorageService.class);

		gallery = new ProductGalleryService(imageRepository, storage, new MediaValidationService());
	}

	@Test
	void legacyCoverAndSevenNewImagesFillTheGalleryWithoutDuplicatingTheCover() {
		String legacyUrl = "/uploads/products/legacy.png";
		tea.setImageUrl(legacyUrl);

		gallery.update(tea, null, uploads("extra", 7), null, null, null);

		entityManager.flush();

		List<ProductImage> saved = gallery.images(tea);

		List<Long> ids = saved.stream().map(ProductImage::getId).toList();

		assertThat(saved).hasSize(8);

		assertThat(saved.stream().filter(image -> legacyUrl.equals(image.getImageUrl())).count()).isEqualTo(1);

		assertThat(saved).extracting(ProductImage::getSortOrder).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);

		assertThat(saved.stream().filter(ProductImage::isPrimary).map(ProductImage::getImageUrl).toList())
				.containsExactly(legacyUrl);

		gallery.update(tea, null, null, null, null, null);

		entityManager.flush();

		assertThat(gallery.images(tea)).extracting(ProductImage::getId).containsExactlyElementsOf(ids);

		assertThat(tea.getImageUrl()).isEqualTo(legacyUrl);

		verify(storage, never()).deleteAfterCommit(anyString());
	}

	@Test
	void legacyCoverCountsTowardTheLimitBeforeAnyUploadOrDatabaseWrite() {
		String legacyUrl = "/uploads/products/legacy.png";
		tea.setImageUrl(legacyUrl);

		MultipartFile[] incoming = uploads("too-many", 8);

		assertThatThrownBy(() -> gallery.update(tea, null, incoming, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);

		entityManager.flush();

		assertThat(gallery.images(tea)).isEmpty();
		assertThat(tea.getImageUrl()).isEqualTo(legacyUrl);

		verifyNoInteractions(storage);
	}

	@Test
	void removingOneOfTwoRowsWithTheSameUrlDoesNotScheduleItsFileForDeletion() {
		String sharedUrl = "/uploads/products/shared.png";

		ProductImage first = existing(sharedUrl, 0, true);
		ProductImage second = existing(sharedUrl, 1, false);

		gallery.update(tea, null, null, List.of(first.getId()), null, null);

		entityManager.flush();

		List<ProductImage> saved = gallery.images(tea);

		assertThat(saved).extracting(ProductImage::getId).containsExactly(second.getId());

		assertThat(saved.getFirst().isPrimary()).isTrue();
		assertThat(saved.getFirst().getSortOrder()).isZero();
		assertThat(tea.getImageUrl()).isEqualTo(sharedUrl);

		verifyNoInteractions(storage);
	}

	@Test
	void failureDuringASecondUploadDoesNotRemoveOrReorderExistingImages() {
		ProductImage first = existing("/uploads/products/old-cover.png", 0, true);

		ProductImage second = existing("/uploads/products/old-side.png", 1, false);

		MockMultipartFile newCover = upload("new-cover.png");

		MockMultipartFile failing = MediaTestFiles.image("failing.png");

		IllegalStateException failure = new IllegalStateException("Storage unavailable");

		when(storage.upload(same(failing), eq("products"))).thenThrow(failure);

		assertThatThrownBy(() -> gallery.update(tea, newCover, new MultipartFile[] { failing }, List.of(first.getId()),
				List.of(second.getId(), first.getId()), null)).isSameAs(failure);

		entityManager.flush();

		List<ProductImage> saved = gallery.images(tea);

		assertThat(saved).extracting(ProductImage::getId).containsExactly(first.getId(), second.getId());

		assertThat(saved).extracting(ProductImage::getSortOrder).containsExactly(0, 1);

		assertThat(saved).extracting(ProductImage::isPrimary).containsExactly(true, false);

		assertThat(tea.getImageUrl()).isEqualTo(first.getImageUrl());

		verify(storage).upload(same(newCover), eq("products"));
		verify(storage).upload(same(failing), eq("products"));

		verify(storage, never()).deleteAfterCommit(anyString());
	}

	@Test
	void changingTheGalleryPreservesSavedSizesToppingsAndTheirUnitPrice() {
		Topping selected = configureOptions();

		ProductImage oldCover = existing("/uploads/products/before.png", 0, true);

		Long sizeId = sizeRepository.findByProductIdAndCodeIgnoreCase(tea.getId(), "L").orElseThrow().getId();

		List<Long> sizeIds = sizeRepository.findByProductIdOrderByDisplayOrderAscIdAsc(tea.getId()).stream()
				.map(ProductSize::getId).toList();

		List<Long> linkIds = options.availableToppings(tea).stream().map(ProductTopping::getId).toList();

		BigDecimal before = pricing.unitPrice(tea, sizeId, List.of(selected.getId()));

		gallery.update(tea, upload("after.png"), null, List.of(oldCover.getId()), null, null);

		entityManager.flush();
		entityManager.clear();

		tea = products.findById(tea.getId()).orElseThrow();

		assertThat(sizeRepository.findByProductIdOrderByDisplayOrderAscIdAsc(tea.getId()))
				.extracting(ProductSize::getId).containsExactlyElementsOf(sizeIds);

		assertThat(options.availableSizes(tea)).extracting(ProductSize::getCode).containsExactly("L");

		assertThat(options.availableToppings(tea)).extracting(ProductTopping::getId).containsExactlyElementsOf(linkIds);

		assertThat(toppingLinks.existsByProductIdAndToppingId(tea.getId(), selected.getId())).isTrue();

		assertThat(before).isEqualByComparingTo("52000.75");

		assertThat(pricing.unitPrice(tea, sizeId, List.of(selected.getId()))).isEqualByComparingTo(before);

		assertThat(tea.getImageUrl()).isEqualTo("/uploads/products/after.png");
	}

	@Test
	void publicDetailReceivesEightImagesPrimaryFirstAndOnlyActiveOptionsWithTheSalePrice() throws Exception {

		Topping selected = configureOptions();

		Topping retired = new Topping();
		retired.setName("Retired " + UUID.randomUUID());
		retired.setPrice(new BigDecimal("9000"));
		retired.setActive(true);

		toppingRepository.saveAndFlush(retired);

		ProductForm form = optionForm();
		form.setToppingIds(List.of(selected.getId(), retired.getId()));

		options.saveConfiguration(tea, form);

		retired.setActive(false);

		List<ProductImage> savedImages = new ArrayList<>();

		for (int index = 0; index < 8; index++) {
			savedImages.add(existing("/uploads/products/detail-" + index + ".png", index, index == 7));
		}

		LocalDateTime now = LocalDateTime.now();

		Promotion promotion = new Promotion();
		promotion.setName("Detail media test 20%");
		promotion.setType(PromotionType.PRODUCT_PERCENT);
		promotion.setDiscountValue(new BigDecimal("20"));
		promotion.setShop(tea.getShop());
		promotion.setProduct(tea);
		promotion.setStartsAt(now.minusDays(1));
		promotion.setEndsAt(now.plusDays(1));
		promotion.setActive(true);

		entityManager.persist(promotion);
		entityManager.flush();

		var modelAndView = mvc.perform(get("/products/{id}", tea.getId())).andExpect(status().isOk())
				.andExpect(view().name("products/detail")).andReturn().getModelAndView();

		assertThat(modelAndView).isNotNull();

		var model = modelAndView.getModel();

		List<Long> expectedIds = new ArrayList<>();
		expectedIds.add(savedImages.get(7).getId());

		savedImages.subList(0, 7).forEach(image -> expectedIds.add(image.getId()));

		assertThat((List<?>) model.get("gallery")).extracting("id").containsExactlyElementsOf(expectedIds);

		assertThat((List<?>) model.get("sizes")).extracting("code").containsExactly("L");

		assertThat((List<?>) model.get("toppings")).extracting("topping.id").containsExactly(selected.getId());

		assertThat((SugarLevel[]) model.get("sugarLevels")).containsExactly(SugarLevel.values());

		assertThat((IceLevel[]) model.get("iceLevels")).containsExactly(IceLevel.values());

		assertThat(model.get("productPrice")).isInstanceOf(ProductPrice.class);

		ProductPrice displayedPrice = (ProductPrice) model.get("productPrice");

		assertThat(displayedPrice.originalPrice()).isEqualByComparingTo("35000");

		assertThat(displayedPrice.salePrice()).isEqualByComparingTo("28000");

		assertThat(displayedPrice.salePrice()).isEqualByComparingTo(pricing.price(tea).salePrice());

		Long sizeId = options.availableSizes(tea).getFirst().getId();

		BigDecimal preview = displayedPrice.salePrice().add(new BigDecimal("7000.25")).add(selected.getPrice());

		assertThat(preview).isEqualByComparingTo("45000.75");

		assertThat(pricing.unitPrice(tea, sizeId, List.of(selected.getId()))).isEqualByComparingTo(preview);
	}

	private Topping configureOptions() {
		Topping topping = new Topping();
		topping.setName("Gallery option " + UUID.randomUUID());
		topping.setPrice(new BigDecimal("10000.50"));
		topping.setActive(true);

		toppingRepository.saveAndFlush(topping);

		ProductForm form = optionForm();
		form.setToppingIds(List.of(topping.getId()));

		options.saveConfiguration(tea, form);

		entityManager.flush();

		return topping;
	}

	private ProductForm optionForm() {
		ProductForm form = new ProductForm();

		form.setSizeMActive(false);
		form.setSizeLActive(true);
		form.setSizeXlActive(false);

		form.setSizeMExtraPrice(BigDecimal.ZERO);
		form.setSizeLExtraPrice(new BigDecimal("7000.25"));
		form.setSizeXlExtraPrice(new BigDecimal("12000"));

		return form;
	}

	private ProductImage existing(String url, int sortOrder, boolean primary) {

		ProductImage image = new ProductImage();
		image.setProduct(tea);
		image.setImageUrl(url);
		image.setSortOrder(sortOrder);
		image.setPrimary(primary);

		imageRepository.saveAndFlush(image);

		if (primary) {
			tea.setImageUrl(url);
		}

		return image;
	}

	private MockMultipartFile upload(String filename) {
		MockMultipartFile file = MediaTestFiles.image(filename);

		when(storage.upload(same(file), eq("products"))).thenReturn("/uploads/products/" + filename);

		return file;
	}

	private MultipartFile[] uploads(String prefix, int count) {
		MultipartFile[] files = new MultipartFile[count];

		for (int index = 0; index < count; index++) {
			files[index] = upload(prefix + "-" + index + ".png");
		}

		return files;
	}
}