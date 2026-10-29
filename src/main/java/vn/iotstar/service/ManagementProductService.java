package vn.iotstar.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.InventoryTransactionType;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.util.SlugUtils;

@Service
@RequiredArgsConstructor
public class ManagementProductService {
    private final ProductRepository productRepository;
    private final ShopRepository shopRepository;
    private final CategoryRepository categoryRepository;
    private final ManagementService managementService;
    private final ProductOptionService productOptionService;
    private final ProductGalleryService productGalleryService;
    private final InventoryService inventoryService;

    @Transactional(readOnly = true)
    public Product get(User actor, Long id) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm."));
        managementService.requireShopAccess(actor, product.getShop().getId());
        return product;
    }

    public List<Category> activeCategories() {
        return categoryRepository.findByActiveTrueOrderByNameAsc();
    }

    @Transactional
    public Product save(User actor, Long id, Long shopId, ProductForm form, MultipartFile image,
                        MultipartFile[] galleryImages, List<Long> removeImageIds,
                        List<Long> galleryOrder, Long primaryImageId) {
        Shop shop = shopRepository.findById(shopId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cửa hàng."));
        managementService.requireShopAccess(actor, shop.getId());
        if (shop.getStatus() != ShopStatus.APPROVED) {
            throw new IllegalArgumentException("Chỉ có thể đăng sản phẩm cho cửa hàng đã được duyệt.");
        }

        Product product = id == null ? new Product() : get(actor, id);
        if (id != null && !product.getShop().getId().equals(shop.getId())) {
            throw new IllegalArgumentException("Không thể chuyển sản phẩm sang cửa hàng khác sau khi đã tạo.");
        }
        if (id != null && (form.getVersion() == null || form.getVersion() != product.getVersion())) {
            throw new IllegalArgumentException(
                "Sản phẩm đã được cập nhật trong lúc bạn chỉnh sửa. Vui lòng tải lại trang trước khi lưu.");
        }

        int stockBefore = id == null ? 0 : product.getStock();
        int stockAfter = form.getStock();
        String stockNote = form.getStockNote() == null ? "" : form.getStockNote().trim();
        if (id != null && stockBefore != stockAfter && stockNote.length() < 5) {
            throw new IllegalArgumentException("Vui lòng nhập lý do thay đổi tồn kho ít nhất 5 ký tự.");
        }

        String sku = form.getSku().trim();
        boolean duplicatedSku = id == null ? productRepository.existsBySkuIgnoreCase(sku)
            : productRepository.existsBySkuIgnoreCaseAndIdNot(sku, id);
        if (duplicatedSku) throw new IllegalArgumentException("Mã SKU đã tồn tại.");

        Category category = categoryRepository.findById(form.getCategoryId()).filter(Category::isActive)
            .orElseThrow(() -> new IllegalArgumentException("Danh mục không hợp lệ hoặc đã bị tắt."));
        product.setShop(shop);
        product.setCategory(category);
        product.setName(form.getName().trim());
        product.setSlug(SlugUtils.toSlug(form.getName()));
        product.setSku(sku.toUpperCase());
        product.setDescription(form.getDescription().trim());
        product.setPrice(form.getPrice());
        product.setStock(stockAfter);
        product.setStatus(stockAfter == 0 ? ProductStatus.OUT_OF_STOCK : form.getStatus());

        Product saved = productRepository.save(product);
        productOptionService.saveConfiguration(saved, form);
        productGalleryService.update(saved, image, galleryImages, removeImageIds, galleryOrder, primaryImageId);
        inventoryService.record(saved, stockBefore, stockAfter,
            id == null ? InventoryTransactionType.INITIAL
                : stockAfter > stockBefore ? InventoryTransactionType.RESTOCK : InventoryTransactionType.ADJUSTMENT,
            actor, null, id == null ? "Admin/Manager tạo sản phẩm và nhập kho ban đầu" : stockNote);
        return saved;
    }
}
