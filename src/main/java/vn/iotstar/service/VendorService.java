package vn.iotstar.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.dto.WebForms.PromotionForm;
import vn.iotstar.dto.WebForms.ShopForm;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.InventoryTransaction;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.PromotionRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.util.SlugUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VendorService {

	private final ShopRepository shopRepository;
	private final UserRepository userRepository;
	private final CategoryRepository categoryRepository;
	private final ProductRepository productRepository;
	private final ProductOptionService productOptionService;
	private final MediaStorageService mediaStorageService;
	private final ProductGalleryService productGalleryService;
	private final PromotionRepository promotionRepository;
	private final InventoryService inventoryService;

	public Shop findShop(User user) {
		return shopRepository.findByOwnerId(user.getId()).orElse(null);
	}

	public Shop requireShop(User user) {
		return shopRepository.findByOwnerId(user.getId())
				.orElseThrow(() -> new IllegalArgumentException("Bạn chưa đăng ký cửa hàng."));
	}

	@Transactional
	public Shop registerShop(User user, ShopForm form) {
		return registerShop(user, form, null);
	}

	@Transactional
	public Shop registerShop(User user, ShopForm form, MultipartFile image) {

		User owner = userRepository.findById(user.getId())
				.orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản."));

		if (owner.getRole() != Role.USER && owner.getRole() != Role.VENDOR) {
			throw new IllegalArgumentException("Tài khoản này không được đăng ký cửa hàng.");
		}

		if (shopRepository.existsByOwnerId(owner.getId())) {
			throw new IllegalArgumentException("Bạn đã có cửa hàng.");
		}

		Shop shop = new Shop();
		shop.setOwner(owner);
		shop.setSlug(uniqueSlug(form.getName()));
		shop.setStatus(ShopStatus.PENDING);
		shop.setEnabled(true);

		applyShopDetails(shop, form);

		String url = mediaStorageService.upload(image, "shops");

		shop.setImageUrl(url == null ? "/images/shop-placeholder.svg" : url);

		Shop saved = shopRepository.save(shop);

		if (owner.getRole() == Role.USER) {
			owner.setRole(Role.VENDOR);
			userRepository.save(owner);
		}

		return saved;
	}

	@Transactional
	public void updateShop(User user, ShopForm form) {
		updateShop(user, form, null);
	}

	@Transactional
	public void updateShop(User user, ShopForm form, MultipartFile image) {

		Shop shop = requireShop(user);
		String previousUrl = shop.getImageUrl();

		applyShopDetails(shop, form);

		String url = mediaStorageService.upload(image, "shops");

		if (url != null) {
			shop.setImageUrl(url);

			if (!url.equals(previousUrl)) {
				mediaStorageService.deleteAfterCommit(previousUrl);
			}
		}
	}

	public Page<Product> products(User user, String q, int page) {

		Shop shop = requireShop(user);

		Sort sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");

		return productRepository.searchByShop(shop.getId(), SlugUtils.cleanQuery(q),
				PageRequest.of(Math.max(page, 0), 20, sort));
	}

	public List<Product> inventoryProducts(User user) {
		Shop shop = requireShop(user);

		return productRepository.findByShopIdOrderByNameAsc(shop.getId());
	}

	public List<Product> lowStockProducts(User user) {
		Shop shop = requireShop(user);

		return productRepository.findByShopIdAndStockLessThanEqualOrderByStockAscIdAsc(shop.getId(),
				Product.LOW_STOCK_THRESHOLD);
	}

	public Page<InventoryTransaction> inventoryTransactions(User user, Long productId, int page) {

		Shop shop = requireShop(user);

		if (productId != null) {
			productForEdit(user, productId);
		}

		return inventoryService.forShop(shop, productId, page);
	}

	public List<Category> activeCategories() {
		return categoryRepository.findByActiveTrueOrderByNameAsc();
	}

	public Product productForEdit(User user, Long id) {
		Shop shop = requireShop(user);

		return productRepository.findByIdAndShopId(id, shop.getId())
				.orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm của cửa hàng."));
	}

	@Transactional
	public Product saveProduct(User user, Long id, ProductForm form) {

		return saveProduct(user, id, form, null, null, null, null, null);
	}

	@Transactional
	public Product saveProduct(User user, Long id, ProductForm form, MultipartFile image) {

		return saveProduct(user, id, form, image, null, null, null, null);
	}

	@Transactional
	public Product saveProduct(User user, Long id, ProductForm form, MultipartFile image, MultipartFile[] galleryImages,
			List<Long> removeImageIds, List<Long> galleryOrder, Long primaryImageId) {

		Shop shop = requireApprovedShop(user);

		if (form == null || form.getStock() == null || form.getStock() < 0 || form.getStatus() == null) {
			throw new IllegalArgumentException("Thông tin tồn kho hoặc trạng thái không hợp lệ.");
		}

		Product product = id == null ? new Product() : productForUpdate(user, id);

		if (id != null && (form.getVersion() == null || form.getVersion().longValue() != product.getVersion())) {
			throw new IllegalArgumentException("Sản phẩm đã thay đổi trong lúc bạn chỉnh sửa. "
					+ "Vui lòng mở lại trang sửa sản phẩm " + "để kiểm tra tồn kho mới nhất trước khi lưu.");
		}

		String name = form.getName().trim();
		String description = form.getDescription().trim();
		String sku = form.getSku().trim().toUpperCase(Locale.ROOT);

		if (name.length() < 3) {
			throw new IllegalArgumentException("Tên sản phẩm phải có ít nhất 3 ký tự " + "sau khi bỏ khoảng trắng.");
		}

		if (description.length() < 20) {
			throw new IllegalArgumentException("Mô tả phải có ít nhất 20 ký tự " + "sau khi bỏ khoảng trắng.");
		}

		boolean duplicatedSku = id == null ? productRepository.existsBySkuIgnoreCase(sku)
				: productRepository.existsBySkuIgnoreCaseAndIdNot(sku, id);

		if (duplicatedSku) {
			throw new IllegalArgumentException("Mã SKU đã tồn tại.");
		}

		Category category = categoryRepository.findById(form.getCategoryId()).filter(Category::isActive)
				.orElseThrow(() -> new IllegalArgumentException("Danh mục không hợp lệ."));

		ProductStatus status = form.getStatus();

		if (form.getStock() == 0 && status == ProductStatus.ACTIVE) {
			status = ProductStatus.OUT_OF_STOCK;
		}

		product.setShop(shop);
		product.setCategory(category);
		product.setName(name);
		product.setSlug(SlugUtils.toSlug(name));
		product.setSku(sku);
		product.setDescription(description);
		product.setPrice(form.getPrice());
		product.setStatus(status);

		Product saved = productRepository.save(product);

		if (id == null) {
			inventoryService.initialize(saved, form.getStock(), user);
		} else {
			inventoryService.setStock(saved, form.getStock(), user);
		}

		// Giữ trạng thái người bán đã chọn sau khi cập nhật kho.
		saved.setStatus(status);

		productOptionService.saveConfiguration(saved, form);

		productGalleryService.update(saved, image, galleryImages, removeImageIds, galleryOrder, primaryImageId);

		return saved;
	}

	@Transactional
	public void toggleProduct(User user, Long id) {
		requireApprovedShop(user);

		Product product = productForUpdate(user, id);

		if (product.getStatus() == ProductStatus.HIDDEN) {
			product.setStatus(product.getStock() > 0 ? ProductStatus.ACTIVE : ProductStatus.OUT_OF_STOCK);
		} else {
			product.setStatus(ProductStatus.HIDDEN);
		}
	}

	public List<Promotion> promotions(User user) {
		Shop shop = requireShop(user);

		return promotionRepository.findByShopIdOrderByStartsAtDesc(shop.getId());
	}

	public List<Product> promotionProducts(User user) {
		Shop shop = requireShop(user);

		return productRepository.findByShopIdOrderByNameAsc(shop.getId());
	}

	public Promotion promotionForEdit(User user, Long id) {
		Shop shop = requireShop(user);

		return promotionRepository.findById(id)
				.filter(promotion -> promotion.getShop() != null && promotion.getShop().getId().equals(shop.getId()))
				.orElseThrow(() -> new IllegalArgumentException(
						"Không tìm thấy chương trình khuyến mãi " + "của cửa hàng."));
	}

	@Transactional
	public void savePromotion(User user, Long id, PromotionForm form) {

		Shop shop = requireShop(user);

		Promotion promotion = id == null ? new Promotion() : promotionForEdit(user, id);

		validatePromotion(form);

		Product product = form.getProductId() == null ? null : productForEdit(user, form.getProductId());

		promotion.setShop(shop);
		promotion.setCreatedBy(user);
		promotion.setName(form.getName().trim());
		promotion.setType(form.getType());
		promotion.setDiscountValue(form.getDiscountValue());
		promotion.setStartsAt(form.getStartsAt());
		promotion.setEndsAt(form.getEndsAt());
		promotion.setActive(form.isActive());
		promotion.setProduct(product);

		promotionRepository.save(promotion);
	}

	@Transactional
	public void deletePromotion(User user, Long id) {
		Promotion promotion = promotionForEdit(user, id);

		promotionRepository.delete(promotion);
	}

	public void validatePromotion(PromotionForm form) {
		if (form == null) {
			throw new IllegalArgumentException("Thông tin khuyến mãi không hợp lệ.");
		}

		String name = form.getName() == null ? "" : form.getName().trim();

		if (name.length() < 3 || name.length() > 180) {
			throw new IllegalArgumentException(
					"Tên khuyến mãi phải có từ 3 đến 180 ký tự " + "sau khi bỏ khoảng trắng.");
		}

		if (form.getType() == null) {
			throw new IllegalArgumentException("Vui lòng chọn loại khuyến mãi.");
		}

		BigDecimal discountValue = form.getDiscountValue();

		if (discountValue == null || discountValue.compareTo(new BigDecimal("0.01")) < 0) {
			throw new IllegalArgumentException("Mức giảm phải lớn hơn hoặc bằng 0,01.");
		}

		if (form.getStartsAt() == null || form.getEndsAt() == null || !form.getEndsAt().isAfter(form.getStartsAt())) {
			throw new IllegalArgumentException("Thời gian kết thúc phải sau thời gian bắt đầu.");
		}

		if (form.getType() == PromotionType.PRODUCT_PERCENT && discountValue.compareTo(new BigDecimal("100")) > 0) {
			throw new IllegalArgumentException("Mức giảm phần trăm không được vượt quá 100%.");
		}

		if (form.getType() == PromotionType.SHIPPING_DISCOUNT && form.getProductId() != null) {
			throw new IllegalArgumentException("Khuyến mãi phí vận chuyển không áp dụng " + "riêng cho sản phẩm.");
		}
	}

	private Product productForUpdate(User user, Long id) {
		Shop shop = requireShop(user);

		// Kiểm tra quyền trước khi khóa sản phẩm.
		productForEdit(user, id);

		Product product = inventoryService.lockProduct(id);

		if (product.getShop() == null || !shop.getId().equals(product.getShop().getId())) {
			throw new IllegalArgumentException("Không tìm thấy sản phẩm của cửa hàng.");
		}

		return product;
	}

	private Shop requireApprovedShop(User user) {
		Shop shop = requireShop(user);

		if (!shop.isApproved()) {
			throw new IllegalArgumentException("Cửa hàng phải được duyệt và đang hoạt động " + "để quản lý sản phẩm.");
		}

		return shop;
	}

	private void applyShopDetails(Shop shop, ShopForm form) {
		shop.setName(form.getName().trim());
		shop.setDescription(form.getDescription().trim());
		shop.setPhone(form.getPhone().trim());

		shop.setEmail(form.getEmail().trim().toLowerCase(Locale.ROOT));

		shop.setAddress(form.getAddress().trim());
		shop.setProvince(form.getProvince().trim());
		shop.setDistrict(form.getDistrict().trim());
		shop.setWard(form.getWard().trim());
		shop.setLatitude(form.getLatitude());
		shop.setLongitude(form.getLongitude());
		shop.setOpeningTime(form.getOpeningTime());
		shop.setClosingTime(form.getClosingTime());
	}

	private String uniqueSlug(String name) {
		String base = SlugUtils.toSlug(name);
		String slug = base;

		for (int suffix = 2; shopRepository.existsBySlug(slug); suffix++) {
			slug = base + "-" + suffix;
		}

		return slug;
	}
}