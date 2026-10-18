package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.PromotionRepository;
import vn.iotstar.service.ProductOptionService;
import vn.iotstar.service.VendorService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VendorOwnershipTests extends CustomerTestFixtures {

	private static final DateTimeFormatter FORM_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

	@Autowired
	VendorService vendors;
	@Autowired
	ProductOptionService options;
	@Autowired
	PromotionRepository promotions;

	private Product foreignProduct;
	private Promotion ownPromotion;
	private Promotion foreignPromotion;

	@BeforeEach
	void ownershipFixtures() {
		// The inherited tea belongs to 'other'; 'customer' owns a different shop.
		other.setRole(Role.VENDOR);
		customer.setRole(Role.VENDOR);
		users.saveAndFlush(other);
		users.saveAndFlush(customer);
		Shop foreignShop = new Shop();
		foreignShop.setOwner(customer);
		foreignShop.setName("Other ownership shop");
		foreignShop.setSlug(UUID.randomUUID().toString());
		foreignShop.setStatus(ShopStatus.APPROVED);
		foreignShop.setEnabled(true);
		shops.saveAndFlush(foreignShop);

		foreignProduct = new Product();
		foreignProduct.setShop(foreignShop);
		foreignProduct.setCategory(tea.getCategory());
		foreignProduct.setName("Foreign ownership product");
		foreignProduct.setSlug(UUID.randomUUID().toString());
		foreignProduct.setSku(UUID.randomUUID().toString());
		foreignProduct.setDescription("Sản phẩm thuộc cửa hàng khác để kiểm thử quyền truy cập.");
		foreignProduct.setPrice(new BigDecimal("25000"));
		foreignProduct.setStock(20);
		products.saveAndFlush(foreignProduct);
		options.saveConfiguration(tea, new ProductForm());
		options.saveConfiguration(foreignProduct, new ProductForm());
		ownPromotion = promotion(tea, other, "Owner promotion");
		foreignPromotion = promotion(foreignProduct, customer, "Foreign promotion");
	}

	@Test
	void productListAndSearchContainOnlyTheCurrentShopsProducts() throws Exception {
		assertThat(vendors.products(other, null, 0).getContent()).extracting(Product::getId)
				.containsExactly(tea.getId());
		assertThat(vendors.products(other, foreignProduct.getSku(), 0).getContent()).isEmpty();

		MvcResult result = mvc.perform(get("/vendor/products").with(user(other.getUsername()).roles("VENDOR")))
				.andExpect(status().isOk()).andExpect(view().name("vendor/products")).andReturn();
		Page<?> page = (Page<?>) result.getModelAndView().getModel().get("page");
		assertThat(page.getContent()).extracting("id").containsExactly(tea.getId());
	}

	@Test
	void promotionListAndProductChoicesContainOnlyTheCurrentShopsData() throws Exception {
		assertThat(vendors.promotions(other)).extracting(Promotion::getId).containsExactly(ownPromotion.getId());
		assertThat(vendors.promotionProducts(other)).extracting(Product::getId).containsExactly(tea.getId());

		MvcResult result = mvc.perform(get("/vendor/promotions").with(user(other.getUsername()).roles("VENDOR")))
				.andExpect(status().isOk()).andExpect(view().name("vendor/promotions")).andReturn();
		List<?> listed = (List<?>) result.getModelAndView().getModel().get("promotions");
		assertThat(listed).extracting("id").containsExactly(ownPromotion.getId());

		MvcResult form = mvc.perform(get("/vendor/promotions/new").with(user(other.getUsername()).roles("VENDOR")))
				.andExpect(status().isOk()).andExpect(view().name("vendor/promotion-form")).andReturn();
		List<?> choices = (List<?>) form.getModelAndView().getModel().get("products");
		assertThat(choices).extracting("id").containsExactly(tea.getId());
	}

	@Test
	void ownerCanOpenTheirProductAndPromotionEditForms() throws Exception {
		mvc.perform(get("/vendor/products/{id}/edit", tea.getId()).with(user(other.getUsername()).roles("VENDOR")))
				.andExpect(status().isOk()).andExpect(view().name("vendor/product-form"))
				.andExpect(model().attributeExists("product", "productForm"));

		mvc.perform(get("/vendor/promotions/{id}/edit", ownPromotion.getId())
				.with(user(other.getUsername()).roles("VENDOR"))).andExpect(status().isOk())
				.andExpect(view().name("vendor/promotion-form"))
				.andExpect(model().attributeExists("promotion", "promotionForm"));
	}

	@Test
	void otherVendorCannotOpenAProductEditFormByGuessingItsId() throws Exception {
		mvc.perform(get("/vendor/products/{id}/edit", tea.getId()).with(user(customer.getUsername()).roles("VENDOR")))
				.andExpect(redirectedUrl("/vendor/products")).andExpect(flash().attributeExists("error"));
	}

	@Test
	void forgedProductEditPostCannotChangeAnotherShopsProduct() throws Exception {
		String originalName = tea.getName();
		long originalVersion = tea.getVersion();

		mvc.perform(post("/vendor/products/{id}/edit", tea.getId()).with(user(customer.getUsername()).roles("VENDOR"))
				.with(csrf()).param("version", Long.toString(originalVersion)).param("name", "Forged replacement name")
				.param("sku", tea.getSku()).param("categoryId", tea.getCategory().getId().toString())
				.param("description", "Mô tả bị gửi sửa từ một tài khoản thuộc cửa hàng khác.").param("price", "1000")
				.param("stock", "1").param("status", "HIDDEN").param("sizeMExtraPrice", "0")
				.param("sizeLExtraPrice", "0").param("sizeXlExtraPrice", "0"))
				.andExpect(redirectedUrl("/vendor/products")).andExpect(flash().attributeExists("error"));

		Product unchanged = products.findById(tea.getId()).orElseThrow();
		assertThat(unchanged.getName()).isEqualTo(originalName);
		assertThat(unchanged.getPrice()).isEqualByComparingTo("35000");
		assertThat(unchanged.getStock()).isEqualTo(10);
		assertThat(unchanged.getStatus()).isEqualTo(ProductStatus.ACTIVE);
		assertThat(unchanged.getVersion()).isEqualTo(originalVersion);
		assertThat(options.availableSizes(unchanged))
				.extracting(size -> size.getExtraPrice().stripTrailingZeros().toPlainString())
				.containsExactly("0", "7000", "12000");
	}

	@Test
	void otherVendorCannotToggleAnotherShopsProduct() throws Exception {
		mvc.perform(post("/vendor/products/{id}/toggle", tea.getId()).with(user(customer.getUsername()).roles("VENDOR"))
				.with(csrf())).andExpect(redirectedUrl("/vendor/products")).andExpect(flash().attributeExists("error"));

		assertThat(products.findById(tea.getId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.ACTIVE);
	}

	@Test
	void otherVendorCannotOpenAPromotionEditFormByGuessingItsId() throws Exception {
		mvc.perform(get("/vendor/promotions/{id}/edit", ownPromotion.getId())
				.with(user(customer.getUsername()).roles("VENDOR"))).andExpect(redirectedUrl("/vendor/promotions"))
				.andExpect(flash().attributeExists("error"));
	}

	@Test
	void otherVendorCannotUpdateAnotherShopsPromotion() throws Exception {
		mvc.perform(promotionPost(customer, ownPromotion.getId(), foreignProduct.getId()))
				.andExpect(redirectedUrl("/vendor/promotions")).andExpect(flash().attributeExists("error"));

		assertOriginalPromotion();
	}

	@Test
	void otherVendorCannotDeleteAnotherShopsPromotion() throws Exception {
		mvc.perform(post("/vendor/promotions/{id}/delete", ownPromotion.getId())
				.with(user(customer.getUsername()).roles("VENDOR")).with(csrf()))
				.andExpect(redirectedUrl("/vendor/promotions")).andExpect(flash().attributeExists("error"));

		assertOriginalPromotion();
	}

	@Test
	void newPromotionCannotTargetAnotherShopsProduct() throws Exception {
		mvc.perform(promotionPost(other, null, foreignProduct.getId())).andExpect(status().isOk())
				.andExpect(view().name("vendor/promotion-form")).andExpect(model().attributeHasErrors("promotionForm"));

		assertThat(vendors.promotions(other)).extracting(Promotion::getId).containsExactly(ownPromotion.getId());
		assertOriginalPromotion();
	}

	@Test
	void existingPromotionCannotBeReassignedToAnotherShopsProduct() throws Exception {
		mvc.perform(promotionPost(other, ownPromotion.getId(), foreignProduct.getId())).andExpect(status().isOk())
				.andExpect(view().name("vendor/promotion-form")).andExpect(model().attributeHasErrors("promotionForm"));

		assertOriginalPromotion();
	}

	@Test
	void ownerMutationEndpointsRequireCsrf() throws Exception {
		mvc.perform(post("/vendor/products/{id}/toggle", tea.getId()).with(user(other.getUsername()).roles("VENDOR")))
				.andExpect(status().isForbidden());
		mvc.perform(post("/vendor/products/{id}/edit", tea.getId()).with(user(other.getUsername()).roles("VENDOR")))
				.andExpect(status().isForbidden());
		mvc.perform(post("/vendor/promotions/save").with(user(other.getUsername()).roles("VENDOR")))
				.andExpect(status().isForbidden());
		mvc.perform(post("/vendor/promotions/{id}/delete", ownPromotion.getId())
				.with(user(other.getUsername()).roles("VENDOR"))).andExpect(status().isForbidden());

		assertThat(tea.getStatus()).isEqualTo(ProductStatus.ACTIVE);
		assertOriginalPromotion();
	}

	@Test
	void ownerCanToggleTheirProductAndUpdateThenDeleteTheirPromotion() throws Exception {
		mvc.perform(post("/vendor/products/{id}/toggle", tea.getId()).with(user(other.getUsername()).roles("VENDOR"))
				.with(csrf())).andExpect(redirectedUrl("/vendor/products"))
				.andExpect(flash().attributeExists("success"));
		assertThat(tea.getStatus()).isEqualTo(ProductStatus.HIDDEN);

		mvc.perform(promotionPost(other, ownPromotion.getId(), tea.getId()))
				.andExpect(redirectedUrl("/vendor/promotions")).andExpect(flash().attributeExists("success"));
		assertThat(promotions.findById(ownPromotion.getId()).orElseThrow().getName())
				.isEqualTo("Updated ownership promotion");

		mvc.perform(post("/vendor/promotions/{id}/delete", ownPromotion.getId())
				.with(user(other.getUsername()).roles("VENDOR")).with(csrf()))
				.andExpect(redirectedUrl("/vendor/promotions")).andExpect(flash().attributeExists("success"));
		assertThat(promotions.existsById(ownPromotion.getId())).isFalse();
		assertThat(promotions.findById(foreignPromotion.getId()).orElseThrow().getName())
				.isEqualTo("Foreign promotion");
		assertThat(foreignProduct.getStatus()).isEqualTo(ProductStatus.ACTIVE);
	}

	private Promotion promotion(Product product, User owner, String name) {
		Promotion promotion = new Promotion();
		promotion.setShop(product.getShop());
		promotion.setProduct(product);
		promotion.setCreatedBy(owner);
		promotion.setName(name);
		promotion.setType(PromotionType.PRODUCT_PERCENT);
		promotion.setDiscountValue(new BigDecimal("10"));
		promotion.setStartsAt(LocalDateTime.now().minusDays(1));
		promotion.setEndsAt(LocalDateTime.now().plusDays(1));
		promotion.setActive(true);
		return promotions.saveAndFlush(promotion);
	}

	private MockHttpServletRequestBuilder promotionPost(User actor, Long id, Long productId) {
		MockHttpServletRequestBuilder request = post("/vendor/promotions/save")
				.with(user(actor.getUsername()).roles("VENDOR")).with(csrf())
				.param("name", "Updated ownership promotion").param("type", "PRODUCT_PERCENT")
				.param("discountValue", "25").param("productId", productId.toString())
				.param("startsAt", LocalDateTime.now().minusDays(1).format(FORM_TIME))
				.param("endsAt", LocalDateTime.now().plusDays(1).format(FORM_TIME)).param("active", "true");
		if (id != null) {
			request.param("id", id.toString());
		}
		return request;
	}

	private void assertOriginalPromotion() {
		Promotion unchanged = promotions.findById(ownPromotion.getId()).orElseThrow();
		assertThat(unchanged.getName()).isEqualTo("Owner promotion");
		assertThat(unchanged.getDiscountValue()).isEqualByComparingTo("10");
		assertThat(unchanged.getProduct().getId()).isEqualTo(tea.getId());
		assertThat(unchanged.getShop().getId()).isEqualTo(tea.getShop().getId());
		assertThat(unchanged.getCreatedBy().getId()).isEqualTo(other.getId());
	}
}
