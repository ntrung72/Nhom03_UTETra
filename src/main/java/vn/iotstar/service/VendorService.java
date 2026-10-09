package vn.iotstar.service;

import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.ProductForm;
import vn.iotstar.dto.WebForms.ShopForm;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ProductRepository;
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

    public Shop findShop(User user) {
        return shopRepository.findByOwnerId(user.getId()).orElse(null);
    }

    public Shop requireShop(User user) {
        return shopRepository.findByOwnerId(user.getId())
            .orElseThrow(() ->
                new IllegalArgumentException("Bạn chưa đăng ký cửa hàng."));
    }

    @Transactional
    public Shop registerShop(User user, ShopForm form) {
        User owner = userRepository.findById(user.getId())
            .orElseThrow(() ->
                new IllegalArgumentException("Không tìm thấy tài khoản."));

        if (owner.getRole() != Role.USER && owner.getRole() != Role.VENDOR) {
            throw new IllegalArgumentException(
                "Tài khoản này không được đăng ký cửa hàng.");
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

        Shop saved = shopRepository.save(shop);

        if (owner.getRole() == Role.USER) {
            owner.setRole(Role.VENDOR);
            userRepository.save(owner);
        }

        return saved;
    }

    @Transactional
    public void updateShop(User user, ShopForm form) {
        Shop shop = requireShop(user);
        applyShopDetails(shop, form);
    }

    public Page<Product> products(User user, String q, int page) {
        Shop shop = requireShop(user);

        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");

        return productRepository.searchByShop(
            shop.getId(),
            SlugUtils.cleanQuery(q),
            PageRequest.of(Math.max(page, 0), 20, sort)
        );
    }

    public List<Category> activeCategories() {
        return categoryRepository.findByActiveTrueOrderByNameAsc();
    }

    public Product productForEdit(User user, Long id) {
        Shop shop = requireShop(user);

        return productRepository.findByIdAndShopId(id, shop.getId())
            .orElseThrow(() -> new IllegalArgumentException(
                "Không tìm thấy sản phẩm của cửa hàng."));
    }

    @Transactional
    public Product saveProduct(User user, Long id, ProductForm form) {
        Shop shop = requireApprovedShop(user);

        Product product = id == null
            ? new Product()
            : productForEdit(user, id);

        String name = form.getName().trim();
        String description = form.getDescription().trim();
        String sku = form.getSku().trim().toUpperCase(Locale.ROOT);

        if (name.length() < 3) {
            throw new IllegalArgumentException(
                "Tên sản phẩm phải có ít nhất 3 ký tự sau khi bỏ khoảng trắng.");
        }

        if (description.length() < 20) {
            throw new IllegalArgumentException(
                "Mô tả phải có ít nhất 20 ký tự sau khi bỏ khoảng trắng.");
        }

        boolean duplicatedSku = id == null
            ? productRepository.existsBySkuIgnoreCase(sku)
            : productRepository.existsBySkuIgnoreCaseAndIdNot(sku, id);

        if (duplicatedSku) {
            throw new IllegalArgumentException("Mã SKU đã tồn tại.");
        }

        Category category = categoryRepository.findById(form.getCategoryId())
            .filter(Category::isActive)
            .orElseThrow(() ->
                new IllegalArgumentException("Danh mục không hợp lệ."));

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
        product.setStock(form.getStock());
        product.setStatus(status);

        Product saved = productRepository.save(product);
        productOptionService.saveConfiguration(saved, form);
        return saved;
    }

    @Transactional
    public void toggleProduct(User user, Long id) {
        requireApprovedShop(user);
        Product product = productForEdit(user, id);

        if (product.getStatus() == ProductStatus.HIDDEN) {
            product.setStatus(
                product.getStock() > 0
                    ? ProductStatus.ACTIVE
                    : ProductStatus.OUT_OF_STOCK
            );
        } else {
            product.setStatus(ProductStatus.HIDDEN);
        }
    }

    private Shop requireApprovedShop(User user) {
        Shop shop = requireShop(user);

        if (!shop.isApproved()) {
            throw new IllegalArgumentException(
                "Cửa hàng phải được duyệt và đang hoạt động để quản lý sản phẩm.");
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