package vn.iotstar.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.dto.WebForms.PromotionForm;
import vn.iotstar.dto.WebForms.ShopForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.util.SlugUtils;

@Service
@RequiredArgsConstructor
public class VendorService {
    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final PromotionRepository promotionRepository;
    private final UserRepository userRepository;
    private final MediaStorageService mediaStorageService;
    private final ProductOptionService productOptionService;
    private final InventoryService inventoryService;
    private final ProductGalleryService productGalleryService;

    public Shop findShop(User user) {
        return shopRepository.findByOwnerId(user.getId()).orElse(null);
    }

    public Shop requireShop(User user) {
        return shopRepository.findByOwnerId(user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Bạn chưa đăng ký cửa hàng."));
    }

    @Transactional
    public Shop registerShop(User user, ShopForm form, MultipartFile image) {
        if (shopRepository.existsByOwnerId(user.getId())) throw new IllegalArgumentException("Bạn đã có cửa hàng.");
        Shop shop = new Shop();
        shop.setOwner(user);
        shop.setName(form.getName().trim());
        shop.setSlug(uniqueSlug(form.getName(), user.getId()));
        shop.setDescription(form.getDescription().trim());
        applyShopDetails(shop, form);
        shop.setStatus(ShopStatus.PENDING);
        String url = mediaStorageService.upload(image, "shops");
        shop.setImageUrl(url == null ? "/images/shop-placeholder.svg" : url);
        shopRepository.save(shop);
        if (user.getRole() == Role.USER) {
            user.setRole(Role.VENDOR);
            userRepository.save(user);
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    auth.getPrincipal(), auth.getCredentials(), List.of(new SimpleGrantedAuthority("ROLE_VENDOR"))));
            }
        }
        return shop;
    }

    @Transactional
    public void updateShop(User user, ShopForm form, MultipartFile image) {
        Shop shop = requireShop(user);
        shop.setName(form.getName().trim());
        shop.setDescription(form.getDescription().trim());
        applyShopDetails(shop, form);
        String url = mediaStorageService.upload(image, "shops");
        if (url != null) {
            String previousUrl = shop.getImageUrl();
            shop.setImageUrl(url);
            mediaStorageService.deleteAfterCommit(previousUrl);
        }
    }

    private void applyShopDetails(Shop shop, ShopForm form) {
        shop.setPhone(form.getPhone().trim());
        shop.setEmail(form.getEmail().trim().toLowerCase(java.util.Locale.ROOT));
        shop.setAddress(form.getAddress().trim());
        shop.setProvince(form.getProvince().trim());
        shop.setDistrict(form.getDistrict().trim());
        shop.setWard(form.getWard().trim());
        shop.setLatitude(form.getLatitude());
        shop.setLongitude(form.getLongitude());
        shop.setOpeningTime(form.getOpeningTime());
        shop.setClosingTime(form.getClosingTime());
    }

    public Page<Product> products(User user, String q, int page) {
        Shop shop = requireShop(user);
        String search = SlugUtils.cleanQuery(q);
        String searchSlug = search == null ? null : SlugUtils.toSlug(search);
        return productRepository.searchByShop(shop.getId(), search, searchSlug,
            PageRequest.of(Math.max(page, 0), 20, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    public long productCount(Shop shop) {
        return productRepository.countByShopId(shop.getId());
    }

    public List<Product> inventoryProducts(User user) {
        return productRepository.findByShopIdOrderByNameAsc(requireShop(user).getId());
    }

    public List<Category> activeCategories() {
        return categoryRepository.findByActiveTrueOrderByNameAsc();
    }

    public Product productForEdit(User user, Long id) {
        Shop shop = requireShop(user);
        return productRepository.findById(id).filter(p -> p.getShop().getId().equals(shop.getId()))
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm của cửa hàng."));
    }

    @Transactional
    public Product saveProduct(User user, Long id, ProductForm form, MultipartFile image) {
        return saveProduct(user, id, form, image, null, null, null, null);
    }

    @Transactional
    public Product saveProduct(User user, Long id, ProductForm form, MultipartFile image,
                               MultipartFile[] galleryImages, List<Long> removeImageIds,
                               List<Long> galleryOrder, Long primaryImageId) {
        Shop shop = requireShop(user);
        if (shop.getStatus() != ShopStatus.APPROVED) throw new IllegalArgumentException("Cửa hàng phải được duyệt trước khi đăng sản phẩm.");
        Product product = id == null ? new Product() : productForEdit(user, id);
        if (id != null && (form.getVersion() == null || form.getVersion() != product.getVersion())) {
            throw new IllegalArgumentException("Sản phẩm đã được cập nhật trong lúc bạn chỉnh sửa. Vui lòng tải lại trang trước khi lưu.");
        }
        int stockBefore = id == null ? 0 : product.getStock();
        int stockAfter = form.getStock();
        String stockNote = form.getStockNote() == null ? "" : form.getStockNote().trim();
        if (id != null && stockBefore != stockAfter && stockNote.length() < 5) {
            throw new IllegalArgumentException("Vui lòng nhập lý do thay đổi tồn kho ít nhất 5 ký tự.");
        }
        String sku = form.getSku().trim();
        boolean duplicatedSku = id == null
            ? productRepository.existsBySkuIgnoreCase(sku)
            : productRepository.existsBySkuIgnoreCaseAndIdNot(sku, id);
        if (duplicatedSku) {
            throw new IllegalArgumentException("Mã SKU đã tồn tại.");
        }
        Category category = categoryRepository.findById(form.getCategoryId())
            .filter(Category::isActive).orElseThrow(() -> new IllegalArgumentException("Danh mục không hợp lệ."));
        product.setShop(shop);
        product.setCategory(category);
        product.setName(form.getName().trim());
        product.setSlug(SlugUtils.toSlug(form.getName()));
        product.setSku(sku.toUpperCase());
        product.setDescription(form.getDescription().trim());
        product.setPrice(form.getPrice());
        product.setStock(form.getStock());
        product.setStatus(form.getStock() == 0 ? ProductStatus.OUT_OF_STOCK : form.getStatus());
        Product saved = productRepository.save(product);
        productOptionService.saveConfiguration(saved, form);
        productGalleryService.update(saved, image, galleryImages, removeImageIds, galleryOrder, primaryImageId);
        inventoryService.record(saved, stockBefore, stockAfter,
            id == null ? InventoryTransactionType.INITIAL
                : stockAfter > stockBefore ? InventoryTransactionType.RESTOCK : InventoryTransactionType.ADJUSTMENT,
            user, null, id == null ? "Nhập tồn kho khi tạo sản phẩm" : stockNote);
        return saved;
    }

    @Transactional
    public void toggleProduct(User user, Long id) {
        Product product = productForEdit(user, id);
        product.setStatus(product.getStatus() == ProductStatus.HIDDEN
            ? (product.getStock() > 0 ? ProductStatus.ACTIVE : ProductStatus.OUT_OF_STOCK)
            : ProductStatus.HIDDEN);
    }

    public List<Promotion> promotions(User user) {
        return promotionRepository.findByShopIdOrderByStartsAtDesc(requireShop(user).getId());
    }

    public List<Product> promotionProducts(User user) {
        return productRepository.findByShopIdOrderByNameAsc(requireShop(user).getId());
    }

    public Promotion promotionForEdit(User user, Long id) {
        Shop shop = requireShop(user);
        return promotionRepository.findById(id).filter(p -> p.getShop() != null && p.getShop().getId().equals(shop.getId()))
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chương trình khuyến mãi."));
    }

    @Transactional
    public void savePromotion(User user, Long id, PromotionForm form) {
        Shop shop = requireShop(user);
        validatePromotion(form);
        Promotion promotion = id == null ? new Promotion() : promotionForEdit(user, id);
        promotion.setShop(shop);
        promotion.setCreatedBy(user);
        promotion.setName(form.getName().trim());
        promotion.setType(form.getType());
        promotion.setDiscountValue(form.getDiscountValue());
        promotion.setStartsAt(form.getStartsAt());
        promotion.setEndsAt(form.getEndsAt());
        promotion.setActive(form.isActive());
        if (form.getProductId() != null) {
            Product product = productForEdit(user, form.getProductId());
            promotion.setProduct(product);
        } else {
            promotion.setProduct(null);
        }
        promotionRepository.save(promotion);
    }

    @Transactional
    public void deletePromotion(User user, Long id) {
        promotionRepository.delete(promotionForEdit(user, id));
    }

    public void validatePromotion(PromotionForm form) {
        if (form.getStartsAt() == null || form.getEndsAt() == null || !form.getEndsAt().isAfter(form.getStartsAt())) {
            throw new IllegalArgumentException("Thời gian kết thúc phải sau thời gian bắt đầu.");
        }
        if (form.getType() == PromotionType.PRODUCT_PERCENT && form.getDiscountValue().compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Mức giảm phần trăm không được vượt quá 100%.");
        }
        if (form.getType() == PromotionType.SHIPPING_DISCOUNT && form.getProductId() != null) {
            throw new IllegalArgumentException("Khuyến mãi phí vận chuyển không áp dụng riêng cho sản phẩm.");
        }
    }

    private String uniqueSlug(String name, Long userId) {
        String base = SlugUtils.toSlug(name);
        return shopRepository.existsBySlug(base) ? base + "-" + userId : base;
    }
}
