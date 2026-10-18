package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.ProductSize;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.Topping;
import vn.iotstar.repository.ProductSizeRepository;
import vn.iotstar.repository.ProductToppingRepository;
import vn.iotstar.repository.ToppingRepository;
import vn.iotstar.service.ProductOptionService;
import vn.iotstar.service.ProductPricingService;
import vn.iotstar.service.VendorService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductOptionsTests extends CustomerTestFixtures {

	@Autowired
	ProductOptionService options;
	@Autowired
	ProductPricingService pricing;
	@Autowired
	ProductSizeRepository sizes;
	@Autowired
	ProductToppingRepository links;
	@Autowired
	ToppingRepository toppings;
	@Autowired
	VendorService vendors;
	@Autowired
	EntityManager entityManager;

	@Test
	void defaultsCreateThreeSizesAndActiveToppingsOnlyOnce() {
		options.configureDefaults(tea);
		List<Long> originalSizeIds = allSizes().stream().map(ProductSize::getId).toList();
		List<Long> originalToppingIds = selectedToppingIds();

		options.configureDefaults(tea);
		entityManager.flush();

		assertThat(allSizes()).extracting(ProductSize::getCode).containsExactly("M", "L", "XL");
		assertThat(size("M").getExtraPrice()).isEqualByComparingTo("0");
		assertThat(size("L").getExtraPrice()).isEqualByComparingTo("7000");
		assertThat(size("XL").getExtraPrice()).isEqualByComparingTo("12000");
		assertThat(allSizes()).allMatch(ProductSize::isActive);
		assertThat(allSizes()).extracting(ProductSize::getId).containsExactlyElementsOf(originalSizeIds);
		assertThat(selectedToppingIds()).containsExactlyElementsOf(originalToppingIds);
		assertThat(selectedToppingIds())
				.containsExactlyInAnyOrderElementsOf(options.availableToppings().stream().map(Topping::getId).toList());
		assertThat(options.availableToppings()).extracting(Topping::getName).contains("Trân châu đen",
				"Trân châu trắng", "Pudding trứng", "Kem cheese", "Thạch dừa");
	}

	@Test
	void defaultsPreserveTheVendorsExistingConfiguration() {
		Topping selected = topping("8500", true);
		ProductForm form = new ProductForm();
		form.setSizeLActive(false);
		form.setSizeLExtraPrice(new BigDecimal("9500.50"));
		form.setSizeXlExtraPrice(new BigDecimal("16000"));
		form.setToppingIds(List.of(selected.getId()));
		options.saveConfiguration(tea, form);

		options.configureDefaults(tea);

		assertThat(size("L").isActive()).isFalse();
		assertThat(size("L").getExtraPrice()).isEqualByComparingTo("9500.50");
		assertThat(size("XL").getExtraPrice()).isEqualByComparingTo("16000");
		assertThat(selectedToppingIds()).containsExactly(selected.getId());
	}

	@Test
	void editingSizesKeepsTheirIdsAndPersistsPricesAndAvailability() {
		options.saveConfiguration(tea, new ProductForm());
		List<Long> ids = allSizes().stream().map(ProductSize::getId).toList();
		ProductForm edited = new ProductForm();
		edited.setSizeMActive(false);
		edited.setSizeMExtraPrice(new BigDecimal("1000.25"));
		edited.setSizeLExtraPrice(new BigDecimal("8000.50"));
		edited.setSizeXlExtraPrice(new BigDecimal("15000.75"));

		options.saveConfiguration(tea, edited);
		entityManager.flush();
		entityManager.clear();

		assertThat(allSizes()).extracting(ProductSize::getId).containsExactlyElementsOf(ids);
		assertThat(size("M").getExtraPrice()).isEqualByComparingTo("1000.25");
		assertThat(size("L").getExtraPrice()).isEqualByComparingTo("8000.50");
		assertThat(size("XL").getExtraPrice()).isEqualByComparingTo("15000.75");
		assertThat(options.availableSizes(tea)).extracting(ProductSize::getCode).containsExactly("L", "XL");
	}

	@Test
	void loadingConfigurationRestoresInactiveSizesAndOnlyActiveToppings() {
		Topping active = topping("8000", true);
		Topping retired = topping("9000", true);
		ProductForm saved = new ProductForm();
		saved.setSizeMActive(false);
		saved.setSizeMExtraPrice(new BigDecimal("1500.50"));
		saved.setSizeXlActive(false);
		saved.setToppingIds(List.of(active.getId(), retired.getId()));
		options.saveConfiguration(tea, saved);
		retired.setActive(false);
		entityManager.flush();
		entityManager.clear();

		ProductForm loaded = new ProductForm();
		options.loadConfiguration(tea, loaded);

		assertThat(loaded.isSizeMActive()).isFalse();
		assertThat(loaded.getSizeMExtraPrice()).isEqualByComparingTo("1500.50");
		assertThat(loaded.isSizeLActive()).isTrue();
		assertThat(loaded.getSizeLExtraPrice()).isEqualByComparingTo("7000");
		assertThat(loaded.isSizeXlActive()).isFalse();
		assertThat(loaded.getSizeXlExtraPrice()).isEqualByComparingTo("12000");
		assertThat(loaded.getToppingIds()).containsExactly(active.getId());
	}

	@Test
	void savingToppingsDeduplicatesIdsAndReplacesOldLinks() {
		Topping first = topping("8000", true);
		Topping second = topping("10000", true);
		ProductForm form = new ProductForm();
		form.setToppingIds(List.of(first.getId(), first.getId(), second.getId()));
		options.saveConfiguration(tea, form);
		assertThat(selectedToppingIds()).containsExactlyInAnyOrder(first.getId(), second.getId());

		form.setToppingIds(List.of(second.getId(), second.getId()));
		options.saveConfiguration(tea, form);
		entityManager.flush();
		entityManager.clear();

		assertThat(selectedToppingIds()).containsExactly(second.getId());
		assertThat(links.existsByProductIdAndToppingId(tea.getId(), first.getId())).isFalse();
		assertThat(toppings.existsById(first.getId())).isTrue();
	}

	@Test
	void nullToppingSelectionClearsLinksWithoutRemovingSizes() {
		Topping selected = topping("8000", true);
		ProductForm form = new ProductForm();
		form.setToppingIds(List.of(selected.getId()));
		options.saveConfiguration(tea, form);
		form.setToppingIds(null);

		options.saveConfiguration(tea, form);
		entityManager.flush();

		assertThat(links.existsByProductId(tea.getId())).isFalse();
		assertThat(allSizes()).hasSize(3);
		assertThat(toppings.existsById(selected.getId())).isTrue();
	}

	@Test
	void drinkWithoutAnyActiveSizeIsRejectedBeforeCreatingOptions() {
		ProductForm form = new ProductForm();
		form.setSizeMActive(false);
		form.setSizeLActive(false);
		form.setSizeXlActive(false);

		assertThatThrownBy(() -> options.saveConfiguration(tea, form)).isInstanceOf(IllegalArgumentException.class);

		assertThat(allSizes()).isEmpty();
		assertThat(links.existsByProductId(tea.getId())).isFalse();
	}

	@Test
	void invalidSurchargesAreRejectedForEachSizeBeforeWritingOptions() {
		for (String code : List.of("M", "L", "XL")) {
			for (BigDecimal invalid : Arrays.asList(null, new BigDecimal("-0.01"), new BigDecimal("1000000000000.00"),
					new BigDecimal("0.001"))) {
				ProductForm form = new ProductForm();
				switch (code) {
				case "M" -> form.setSizeMExtraPrice(invalid);
				case "L" -> form.setSizeLExtraPrice(invalid);
				case "XL" -> form.setSizeXlExtraPrice(invalid);
				default -> throw new AssertionError(code);
				}

				assertThatThrownBy(() -> options.saveConfiguration(tea, form))
						.as("Reject surcharge %s for size %s", invalid, code)
						.isInstanceOf(IllegalArgumentException.class);
			}
		}
		assertThat(allSizes()).isEmpty();
		assertThat(links.existsByProductId(tea.getId())).isFalse();
	}

	@Test
	void zeroAndMaximumTwoDecimalSurchargesCanBePersisted() {
		ProductForm form = new ProductForm();
		form.setSizeMExtraPrice(BigDecimal.ZERO);
		form.setSizeLExtraPrice(new BigDecimal("999999999999.99"));
		form.setSizeXlExtraPrice(new BigDecimal("0.01"));

		options.saveConfiguration(tea, form);
		entityManager.flush();
		entityManager.clear();

		assertThat(size("M").getExtraPrice()).isEqualByComparingTo("0");
		assertThat(size("L").getExtraPrice()).isEqualByComparingTo("999999999999.99");
		assertThat(size("XL").getExtraPrice()).isEqualByComparingTo("0.01");
	}

	@Test
	void nullZeroAndNegativeToppingIdsAreRejectedBeforeWritingOptions() {
		for (Long invalid : Arrays.asList(null, 0L, -1L)) {
			ProductForm form = new ProductForm();
			form.setToppingIds(Arrays.asList(invalid));

			assertThatThrownBy(() -> options.saveConfiguration(tea, form)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(allSizes()).isEmpty();
		assertThat(links.existsByProductId(tea.getId())).isFalse();
	}

	@Test
	void nonexistentToppingIsRejectedBeforeWritingOptions() {
		ProductForm form = new ProductForm();
		form.setToppingIds(List.of(Long.MAX_VALUE));

		assertThatThrownBy(() -> options.saveConfiguration(tea, form)).isInstanceOf(IllegalArgumentException.class);

		assertThat(allSizes()).isEmpty();
		assertThat(links.existsByProductId(tea.getId())).isFalse();
	}

	@Test
	void inactiveToppingCannotBeSelectedEvenAlongsideAnActiveOne() {
		Topping active = topping("8000", true);
		Topping inactive = topping("9000", false);
		ProductForm form = new ProductForm();
		form.setToppingIds(List.of(active.getId(), inactive.getId()));

		assertThatThrownBy(() -> options.saveConfiguration(tea, form)).isInstanceOf(IllegalArgumentException.class);

		assertThat(allSizes()).isEmpty();
		assertThat(links.existsByProductId(tea.getId())).isFalse();
	}

	@Test
	void switchingToStandaloneToppingRemovesDrinkOptionsAndUsesBasePrice() {
		Topping selected = topping("8000", true);
		ProductForm form = new ProductForm();
		form.setToppingIds(List.of(selected.getId()));
		options.saveConfiguration(tea, form);

		Category category = categories.findBySlugAndActiveTrue("topping").orElseGet(() -> {
			Category created = new Category();
			created.setName("Topping");
			created.setSlug("topping");
			created.setActive(true);
			return categories.saveAndFlush(created);
		});
		tea.setCategory(category);

		options.saveConfiguration(tea, new ProductForm());
		entityManager.flush();

		assertThat(allSizes()).isEmpty();
		assertThat(links.existsByProductId(tea.getId())).isFalse();
		assertThat(pricing.unitPrice(tea, null, List.of())).isEqualByComparingTo("35000");
		assertThat(toppings.existsById(selected.getId())).isTrue();
	}

	@Test
	void pricingUsesTheSavedSizeSurchargeAndSelectedToppingPrices() {
		Topping first = topping("10000.50", true);
		Topping second = topping("2000.25", true);
		ProductForm form = new ProductForm();
		form.setSizeLExtraPrice(new BigDecimal("7000.25"));
		form.setToppingIds(List.of(first.getId(), second.getId()));
		options.saveConfiguration(tea, form);
		entityManager.flush();

		assertThat(pricing.unitPrice(tea, size("L").getId(), List.of(first.getId(), second.getId(), first.getId())))
				.isEqualByComparingTo("54001.00");
	}

	@Test
	void anotherShopCannotReadOrOverwriteTheProductsOptionsThroughVendorService() {
		other.setRole(Role.VENDOR);
		customer.setRole(Role.VENDOR);
		Shop foreignShop = new Shop();
		foreignShop.setOwner(customer);
		foreignShop.setName("Other vendor shop");
		foreignShop.setSlug(UUID.randomUUID().toString());
		foreignShop.setStatus(ShopStatus.APPROVED);
		foreignShop.setEnabled(true);
		shops.saveAndFlush(foreignShop);
		Topping selected = topping("8000", true);
		ProductForm original = new ProductForm();
		original.setToppingIds(List.of(selected.getId()));
		options.saveConfiguration(tea, original);

		ProductForm forged = new ProductForm();
		forged.setName("Tên bị sửa bởi shop khác");
		forged.setSku(tea.getSku());
		forged.setCategoryId(tea.getCategory().getId());
		forged.setDescription("Mô tả sản phẩm được gửi từ một cửa hàng khác.");
		forged.setPrice(new BigDecimal("1000"));
		forged.setStock(1);
		forged.setStatus(ProductStatus.ACTIVE);
		forged.setVersion(tea.getVersion());
		forged.setSizeLExtraPrice(BigDecimal.ZERO);
		String originalName = tea.getName();

		assertThat(vendors.productForEdit(other, tea.getId()).getId()).isEqualTo(tea.getId());
		assertThatThrownBy(() -> vendors.productForEdit(customer, tea.getId()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> vendors.saveProduct(customer, tea.getId(), forged))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(tea.getName()).isEqualTo(originalName);
		assertThat(tea.getPrice()).isEqualByComparingTo("35000");
		assertThat(tea.getStock()).isEqualTo(10);
		assertThat(size("L").getExtraPrice()).isEqualByComparingTo("7000");
		assertThat(selectedToppingIds()).containsExactly(selected.getId());
	}

	private Topping topping(String price, boolean active) {
		Topping topping = new Topping();
		topping.setName("Option test " + UUID.randomUUID());
		topping.setPrice(new BigDecimal(price));
		topping.setActive(active);
		return toppings.saveAndFlush(topping);
	}

	private List<ProductSize> allSizes() {
		return sizes.findByProductIdOrderByDisplayOrderAscIdAsc(tea.getId());
	}

	private ProductSize size(String code) {
		return sizes.findByProductIdAndCodeIgnoreCase(tea.getId(), code).orElseThrow();
	}

	private List<Long> selectedToppingIds() {
		return options.availableToppings(tea).stream().map(link -> link.getTopping().getId()).toList();
	}
}
