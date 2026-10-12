package vn.iotstar.service;

import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.util.SlugUtils;

/** Management-specific product operations, with server-side ManagerShop checks. */
@Service
@RequiredArgsConstructor
public class ManagementProductService {
    private final ProductRepository productRepository;
    private final ShopRepository shopRepository;
    private final CategoryRepository categoryRepository;
    private final ManagementService managementService;
    private final ProductOptionService productOptionService;
    private final ProductGalleryService productGalleryService;

    @Transactional(readOnly = true)
    public Product get(User actor, Long id) { return managementService.getProduct(actor, id); }

    public List<Category> activeCategories() { return categoryRepository.findByActiveTrueOrderByNameAsc(); }

    @Transactional
    public Product save(User actor, Long id, Long shopId, ProductForm form, MultipartFile image,
                        MultipartFile[] galleryImages, List<Long> removeImageIds,
                        List<Long> galleryOrder, Long primaryImageId) {
        // Reject forged cross-shop ids before touching the data.
        managementService.requireShopAccess(actor, shopId);
        Shop shop = shopRepository.findById(shopId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cửa hàng."));
        if (shop.getStatus() != ShopStatus.APPROVED || !shop.isEnabled())
            throw new IllegalArgumentException("Chỉ có thể đăng sản phẩm cho cửa hàng đã được duyệt.");
        Product product = id == null ? new Product() : get(actor, id);
        if (id != null && !product.getShop().getId().equals(shopId))
            throw new IllegalArgumentException("Không thể chuyển sản phẩm sang cửa hàng khác sau khi đã tạo.");
        String name = form.getName() == null ? "" : form.getName().trim();
        String description = form.getDescription() == null ? "" : form.getDescription().trim();
        String sku = form.getSku() == null ? "" : form.getSku().trim().toUpperCase(Locale.ROOT);
        if (name.length() < 3 || description.length() < 20 || sku.length() < 2)
            throw new IllegalArgumentException("Tên, mô tả hoặc SKU không hợp lệ.");
        boolean dup = id == null ? productRepository.existsBySkuIgnoreCase(sku)
            : productRepository.existsBySkuIgnoreCaseAndIdNot(sku, id);
        if (dup) throw new IllegalArgumentException("Mã SKU đã tồn tại.");
        Category category = categoryRepository.findById(form.getCategoryId()).filter(Category::isActive)
            .orElseThrow(() -> new IllegalArgumentException("Danh mục không hợp lệ hoặc đã bị tắt."));
        if (form.getPrice() == null || form.getPrice().compareTo(new java.math.BigDecimal("1000")) < 0
            || form.getStock() == null || form.getStock() < 0 || form.getStatus() == null)
            throw new IllegalArgumentException("Giá, tồn kho hoặc trạng thái không hợp lệ.");
        product.setShop(shop);
        product.setCategory(category);
        product.setName(name);
        product.setSlug(SlugUtils.toSlug(name));
        product.setSku(sku);
        product.setDescription(description);
        product.setPrice(form.getPrice());
        product.setStock(form.getStock());
        product.setStatus(form.getStock() == 0 && form.getStatus() == ProductStatus.ACTIVE
            ? ProductStatus.OUT_OF_STOCK : form.getStatus());
        Product saved = productRepository.save(product);
        productOptionService.saveConfiguration(saved, form);
        productGalleryService.update(saved, image, galleryImages, removeImageIds, galleryOrder, primaryImageId);
        return saved;
    }
}
