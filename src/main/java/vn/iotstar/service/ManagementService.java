package vn.iotstar.service;

import java.math.BigDecimal;
import java.util.Locale;
import java.time.LocalDateTime;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.CategoryForm;
import vn.iotstar.dto.WebForms.ToppingForm;
import vn.iotstar.dto.WebForms.PromotionForm;
import vn.iotstar.dto.WebForms.VoucherForm;
import vn.iotstar.entity.Category;
import vn.iotstar.entity.Topping;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.VoucherType;
import vn.iotstar.entity.Voucher;
import vn.iotstar.entity.VoucherUsage;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.repository.CategoryRepository;
import vn.iotstar.repository.ToppingRepository;
import vn.iotstar.repository.ProductToppingRepository;
import vn.iotstar.service.MediaStorageService;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.ManagerShop;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.PromotionRepository;
import vn.iotstar.repository.VoucherRepository;
import vn.iotstar.repository.VoucherUsageRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;

/**
 * Scope foundation for Step 12 only. Management CRUD/controllers belong to later steps.
 * Every management operation added later must call requireShopAccess(actor, shopId)
 * before returning or changing shop-owned records.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManagementService {

    private final UserRepository userRepository;
    private final ManagerShopRepository managerShopRepository;
    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final PromotionRepository promotionRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherUsageRepository voucherUsageRepository;
    private final CategoryRepository categoryRepository;
    private final ToppingRepository toppingRepository;
    private final ProductToppingRepository productToppingRepository;
    private final MediaStorageService mediaStorageService;

    /** Admin can see every shop; Manager can see only assigned shops. */
    public List<Shop> accessibleShops(User actor) {
        requireManagementRole(actor);
        if (isAdmin(actor)) {
            return shopRepository.findAll(Sort.by("name"));
        }
        return managerShopRepository.findByManagerIdOrderByShopNameAsc(actor.getId())
            .stream().map(ManagerShop::getShop).toList();
    }

    /** IDs for repository queries in the later Management Catalog / Order steps. */
    public List<Long> accessibleShopIds(User actor) {
        requireManagementRole(actor);
        if (isAdmin(actor)) {
            return shopRepository.findAll(Sort.by("name")).stream()
                .map(Shop::getId).toList();
        }
        return managerShopRepository.findShopIdsByManagerId(actor.getId());
    }

    /** Mandatory server-side guard: never trust a shopId received in a request. */
    public void requireShopAccess(User actor, Long shopId) {
        requireManagementRole(actor);
        if (shopId == null || shopId <= 0) {
            throw forbiddenShop();
        }
        if (isAdmin(actor)) {
            return;
        }
        if (!managerShopRepository.existsByManagerIdAndShopId(actor.getId(), shopId)) {
            throw forbiddenShop();
        }
    }

    /** Lookup helpers for later controllers; scope is checked before returning data. */
    public Shop getShop(User actor, Long shopId) {
        requireShopAccess(actor, shopId);
        return shopRepository.findById(shopId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cửa hàng."));
    }

    public Product getProduct(User actor, Long productId) {
        requireManagementRole(actor);
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm."));
        requireShopAccess(actor, product.getShop().getId());
        return product;
    }

    public Promotion getPromotion(User actor, Long promotionId) {
        requireManagementRole(actor);
        Promotion promotion = promotionRepository.findById(promotionId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khuyến mãi."));
        if (promotion.getShop() == null) {
            if (!isAdmin(actor)) {
                throw forbiddenShop();
            }
        } else {
            requireShopAccess(actor, promotion.getShop().getId());
        }
        return promotion;
    }

    /** Read-only lists already scoped at the backend; later steps can add pagination. */
    public List<Product> accessibleProducts(User actor) {
        requireManagementRole(actor);
        if (isAdmin(actor)) {
            return productRepository.findAll(Sort.by("name"));
        }
        return accessibleShopIds(actor).stream()
            .flatMap(id -> productRepository.findByShopIdOrderByNameAsc(id).stream())
            .toList();
    }

    public List<Promotion> accessiblePromotions(User actor) {
        requireManagementRole(actor);
        if (isAdmin(actor)) {
            return promotionRepository.findAll(Sort.by(Sort.Direction.DESC, "startsAt"));
        }
        return accessibleShopIds(actor).stream()
            .flatMap(id -> promotionRepository.findByShopIdOrderByStartsAtDesc(id).stream())
            .toList();
    }

    // =================== STEP 13: User, Shop, Manager administration ===================

    /** Summary only: financial/order dashboards belong to later steps. */
    public record DashboardSummary(long users, long shops, long products) {}

    public DashboardSummary dashboard(User actor) {
        requireManagementRole(actor);
        if (isAdmin(actor)) {
            return new DashboardSummary(userRepository.count(), shopRepository.count(), productRepository.count());
        }
        List<Long> ids = accessibleShopIds(actor);
        if (ids.isEmpty()) return new DashboardSummary(0, 0, 0);
        return new DashboardSummary(userRepository.countOwnersInShops(ids), ids.size(),
            productRepository.countByShopIdIn(ids));
    }

    public Page<User> users(User actor, String q, int page) {
        requireManagementRole(actor);
        var pageable = PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, "id"));
        String query = cleanQuery(q);
        if (isAdmin(actor)) return userRepository.searchManagement(query, pageable);
        List<Long> ids = accessibleShopIds(actor);
        return ids.isEmpty() ? Page.empty(pageable) : userRepository.searchShopOwners(ids, query, pageable);
    }

    @Transactional
    public void toggleUser(Long id, User actor) {
        requireManagementRole(actor);
        if (id == null) throw new IllegalArgumentException("Tài khoản không hợp lệ.");
        User target = userRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng."));
        if (target.getId().equals(actor.getId())) {
            throw new IllegalArgumentException("Bạn không thể tự khóa tài khoản của mình.");
        }
        if (!isAdmin(actor)) {
            if (target.getRole() == Role.ADMIN || target.getRole() == Role.MANAGER) {
                throw new AccessDeniedException("Manager không được khóa tài khoản Admin/Manager.");
            }
            List<Long> ids = accessibleShopIds(actor);
            if (ids.isEmpty() || !userRepository.isOwnerOfAssignedShop(target.getId(), ids)) {
                throw forbiddenShop();
            }
        }
        target.setLocked(!target.isLocked());
    }

    @Transactional
    public void changeRole(Long id, Role newRole, User actor) {
        requireAdmin(actor);
        if (newRole == null || id == null) throw new IllegalArgumentException("Vai trò/tài khoản không hợp lệ.");
        User target = userRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng."));
        if (target.getId().equals(actor.getId())) {
            throw new IllegalArgumentException("Không thể tự đổi vai trò Admin.");
        }
        if (target.getRole() == newRole) return;
        // A Vendor owning a shop must retain the Vendor role until the shop is transferred/closed.
        if (shopRepository.existsByOwnerId(target.getId()) && newRole != Role.VENDOR) {
            throw new IllegalArgumentException("Chủ cửa hàng phải giữ vai trò Vendor khi vẫn sở hữu shop.");
        }
        if (target.getRole() == Role.MANAGER && newRole != Role.MANAGER) {
            managerShopRepository.deleteByManagerId(target.getId());
            managerShopRepository.flush();
        }
        target.setRole(newRole);
    }

    public Page<Shop> shops(User actor, String q, ShopStatus status, int page) {
        requireManagementRole(actor);
        var pageable = PageRequest.of(Math.max(0, page), 20,
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        String query = cleanQuery(q);
        if (isAdmin(actor)) return shopRepository.searchManagement(query, status, pageable);
        List<Long> ids = accessibleShopIds(actor);
        return ids.isEmpty() ? Page.empty(pageable)
            : shopRepository.searchAssigned(ids, query, status, pageable);
    }

    @Transactional
    public void updateShop(User actor, Long id, ShopStatus status, BigDecimal commissionRate) {
        requireShopAccess(actor, id);
        if (status == null) throw new IllegalArgumentException("Vui lòng chọn trạng thái shop.");
        if (commissionRate == null || commissionRate.compareTo(BigDecimal.ZERO) < 0
                || commissionRate.compareTo(new BigDecimal("100")) > 0
                || commissionRate.scale() > 2) {
            throw new IllegalArgumentException("Chiết khấu phải từ 0 đến 100% và tối đa 2 số thập phân.");
        }
        Shop shop = shopRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cửa hàng."));
        shop.setStatus(status);
        shop.setEnabled(status != ShopStatus.SUSPENDED && status != ShopStatus.REJECTED);
        shop.setCommissionRate(commissionRate);
    }

    public List<User> managers(User actor) {
        requireAdmin(actor);
        return userRepository.findByRoleOrderByFullNameAsc(Role.MANAGER);
    }

    public Map<Long, Set<Long>> managerAssignments(User actor) {
        requireAdmin(actor);
        Map<Long, Set<Long>> result = new LinkedHashMap<>();
        for (User manager : managers(actor)) {
            result.put(manager.getId(), new LinkedHashSet<>(
                managerShopRepository.findShopIdsByManagerId(manager.getId())));
        }
        return result;
    }

    @Transactional
    public void assignManagerShops(User actor, Long managerId, List<Long> shopIds) {
        requireAdmin(actor);
        if (managerId == null) throw new IllegalArgumentException("Manager không hợp lệ.");
        User manager = userRepository.findById(managerId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Manager."));
        if (manager.getRole() != Role.MANAGER) {
            throw new IllegalArgumentException("Tài khoản được chọn không có vai trò Manager.");
        }
        List<Long> ids = shopIds == null ? List.of() : shopIds.stream().distinct().toList();
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Danh sách shop không hợp lệ.");
        }
        List<Shop> chosenShops = ids.isEmpty() ? List.of() : shopRepository.findAllById(ids);
        if (chosenShops.size() != ids.size()) throw new IllegalArgumentException("Có cửa hàng không tồn tại.");
        // The manager_shop row has a unique (manager_id, shop_id) constraint.
        managerShopRepository.deleteByManagerId(managerId);
        managerShopRepository.flush();
        for (Shop chosen : chosenShops) {
            ManagerShop link = new ManagerShop();
            link.setManager(manager);
            link.setShop(chosen);
            link.setAssignedBy(actor);
            managerShopRepository.save(link);
        }
    }

    // ==================== STEP 14: Management catalog ====================

    public Page<Category> categories(String q, int page) {
        return categoryRepository.search(cleanQuery(q),
            PageRequest.of(Math.max(page, 0), 20, Sort.by("name")));
    }

    public Category getCategory(Long id) {
        return categoryRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy danh mục."));
    }

    public List<Category> allCategories() { return categoryRepository.findAll(Sort.by("name")); }

    @Transactional
    public void saveCategory(Long id, CategoryForm form, MultipartFile image) {
        Category category = id == null ? new Category() : getCategory(id);
        String name = form.getName() == null ? "" : form.getName().trim();
        if (name.length() < 2 || name.length() > 120) throw new IllegalArgumentException("Tên danh mục phải có từ 2 đến 120 ký tự.");
        String slug = vn.iotstar.util.SlugUtils.toSlug(name);
        boolean duplicated = id == null ? categoryRepository.existsBySlug(slug)
            : categoryRepository.existsBySlugAndIdNot(slug, id);
        if (duplicated) throw new IllegalArgumentException("Tên danh mục đã tồn tại.");
        category.setName(name);
        category.setSlug(slug);
        category.setDescription(form.getDescription() == null ? null : form.getDescription().trim());
        category.setActive(form.isActive());
        String url = mediaStorageService.upload(image, "categories");
        if (url != null) category.setImageUrl(url);
        if (category.getImageUrl() == null) category.setImageUrl("/images/category-placeholder.svg");
        categoryRepository.save(category);
    }

    @Transactional
    public void toggleCategory(Long id) {
        Category category = getCategory(id);
        category.setActive(!category.isActive());
    }

    public Page<Product> products(User actor, String q, Long shopId, Long categoryId, int page) {
        requireManagementRole(actor);
        var pageable = PageRequest.of(Math.max(page, 0), 20, Sort.by("createdAt").descending());
        if (isAdmin(actor)) return productRepository.searchManagement(cleanQuery(q), shopId, categoryId, pageable);
        List<Long> ids = accessibleShopIds(actor);
        return ids.isEmpty() ? Page.empty(pageable)
            : productRepository.searchManagementAssigned(ids, cleanQuery(q), shopId, categoryId, pageable);
    }

    @Transactional
    public void toggleProduct(User actor, Long id) {
        Product product = getProduct(actor, id);
        product.setStatus(product.getStatus() == ProductStatus.HIDDEN
            ? (product.getStock() > 0 ? ProductStatus.ACTIVE : ProductStatus.OUT_OF_STOCK) : ProductStatus.HIDDEN);
    }

    public Page<Topping> toppings(String q, int page) {
        return toppingRepository.search(cleanQuery(q), PageRequest.of(Math.max(page, 0), 20, Sort.by("name")));
    }

    public Topping getTopping(Long id) {
        return toppingRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy topping."));
    }

    public long toppingProductCount(Long id) { return productToppingRepository.countByToppingId(id); }

    @Transactional
    public void saveTopping(Long id, ToppingForm form) {
        Topping topping = id == null ? new Topping() : getTopping(id);
        String name = form.getName() == null ? "" : form.getName().trim();
        if (name.length() < 2 || name.length() > 120) throw new IllegalArgumentException("Tên topping phải có từ 2 đến 120 ký tự.");
        if (form.getPrice() == null || form.getPrice().signum() < 0) throw new IllegalArgumentException("Giá topping không được âm.");
        boolean duplicated = id == null ? toppingRepository.existsByNameIgnoreCase(name)
            : toppingRepository.existsByNameIgnoreCaseAndIdNot(name, id);
        if (duplicated) throw new IllegalArgumentException("Tên topping đã tồn tại.");
        topping.setName(name);
        topping.setPrice(form.getPrice());
        topping.setActive(form.isActive());
        toppingRepository.save(topping);
    }

    @Transactional
    public void toggleTopping(Long id) {
        Topping topping = getTopping(id);
        topping.setActive(!topping.isActive());
    }

    @Transactional
    public void deleteTopping(Long id) {
        Topping topping = getTopping(id);
        if (productToppingRepository.existsByToppingId(id)) {
            throw new IllegalArgumentException("Topping đang được áp dụng cho sản phẩm. Hãy tắt topping thay vì xóa.");
        }
        toppingRepository.delete(topping);
    }

    public Page<Promotion> promotions(User actor, String q, Long shopId, PromotionType type, int page) {
        requireManagementRole(actor);
        var pageable = PageRequest.of(Math.max(page, 0), 20, Sort.by("startsAt").descending());
        if (isAdmin(actor)) return promotionRepository.search(cleanQuery(q), shopId, type, pageable);
        List<Long> ids = accessibleShopIds(actor);
        return ids.isEmpty() ? Page.empty(pageable)
            : promotionRepository.searchAssigned(ids, cleanQuery(q), shopId, type, pageable);
    }

    @Transactional
    public void savePromotion(Long id, Long shopId, PromotionForm form, User actor) {
        requireManagementRole(actor);
        if (form.getStartsAt() == null || form.getEndsAt() == null || !form.getEndsAt().isAfter(form.getStartsAt()))
            throw new IllegalArgumentException("Thời gian kết thúc phải sau thời gian bắt đầu.");
        if (form.getType() == null || form.getDiscountValue() == null || form.getDiscountValue().signum() <= 0)
            throw new IllegalArgumentException("Loại khuyến mãi hoặc mức giảm không hợp lệ.");
        if (form.getType() == PromotionType.PRODUCT_PERCENT && form.getDiscountValue().compareTo(new BigDecimal("100")) > 0)
            throw new IllegalArgumentException("Mức giảm phần trăm không được vượt quá 100%.");
        Promotion promotion = id == null ? new Promotion() : getPromotion(actor, id);
        Shop shop = shopId == null ? null : shopRepository.findById(shopId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cửa hàng."));
        if (shop == null && !isAdmin(actor)) throw new IllegalArgumentException("Manager phải chọn shop được phân công.");
        if (shop != null) requireShopAccess(actor, shop.getId());
        // Editing cannot move a manager-owned promotion to another shop without authorization.
        Product product = form.getProductId() == null ? null : productRepository.findById(form.getProductId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm."));
        if (product != null) requireShopAccess(actor, product.getShop().getId());
        if (form.getType() == PromotionType.SHIPPING_DISCOUNT && product != null)
            throw new IllegalArgumentException("Khuyến mãi vận chuyển không áp dụng riêng sản phẩm.");
        if (product != null && (shop == null || !product.getShop().getId().equals(shop.getId())))
            throw new IllegalArgumentException("Sản phẩm không thuộc cửa hàng đã chọn.");
        String name = form.getName() == null ? "" : form.getName().trim();
        if (name.length() < 3 || name.length() > 180) throw new IllegalArgumentException("Tên chương trình không hợp lệ.");
        promotion.setName(name);
        promotion.setType(form.getType());
        promotion.setDiscountValue(form.getDiscountValue());
        promotion.setShop(shop);
        promotion.setProduct(product);
        promotion.setStartsAt(form.getStartsAt());
        promotion.setEndsAt(form.getEndsAt());
        promotion.setActive(form.isActive());
        if (id == null) promotion.setCreatedBy(actor);
        promotionRepository.save(promotion);
    }

    @Transactional
    public void deletePromotion(User actor, Long id) {
        Promotion promotion = getPromotion(actor, id);
        promotionRepository.delete(promotion);
    }

    // ==================== STEP 15: System Vouchers ====================

    public Page<Voucher> vouchers(User actor, String q, VoucherType type, Boolean active, int page) {
        requireAdmin(actor);
        return voucherRepository.search(cleanQuery(q), type, active,
            PageRequest.of(Math.max(page, 0), 20, Sort.by("startsAt").descending()));
    }

    public Voucher getVoucher(User actor, Long id) {
        requireAdmin(actor);
        return voucherRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã giảm giá."));
    }

    @Transactional
    public void saveVoucher(User actor, Long id, VoucherForm form) {
        requireAdmin(actor);
        if (form == null || form.getCode() == null || !form.getCode().trim().matches("[A-Za-z0-9_-]{3,40}")
            || form.getName() == null || form.getName().isBlank() || form.getName().length() > 180
            || form.getType() == null || form.getDiscountValue() == null
            || form.getDiscountValue().compareTo(BigDecimal.ZERO) <= 0
            || form.getMinimumOrder() == null || form.getMinimumOrder().signum() < 0
            || form.getMaxDiscount() != null && form.getMaxDiscount().signum() <= 0
            || form.getQuantity() < 0) {
            throw new IllegalArgumentException("Dữ liệu mã giảm giá không hợp lệ.");
        }
        if (form.getEndsAt() == null || form.getStartsAt() == null
            || !form.getEndsAt().isAfter(form.getStartsAt())) {
            throw new IllegalArgumentException("Thời gian kết thúc phải sau thời gian bắt đầu.");
        }
        if (form.getType() == VoucherType.PERCENT
            && form.getDiscountValue().compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Mức giảm phần trăm không được vượt quá 100%.");
        }
        if (form.getMaxUsesPerUser() < 1) {
            throw new IllegalArgumentException("Số lượt tối đa cho mỗi User phải từ 1 trở lên.");
        }
        String code = form.getCode().trim().toUpperCase(Locale.ROOT);
        boolean duplicatedCode = id == null ? voucherRepository.existsByCodeIgnoreCase(code)
            : voucherRepository.existsByCodeIgnoreCaseAndIdNot(code, id);
        if (duplicatedCode) throw new IllegalArgumentException("Mã giảm giá đã tồn tại.");
        Voucher voucher = id == null ? new Voucher() : voucherRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã giảm giá."));
        if (id != null && voucherUsageRepository.existsByVoucherId(id)
            && !voucher.getCode().equalsIgnoreCase(code)) {
            throw new IllegalArgumentException("Mã đã có lịch sử sử dụng nên không thể đổi code.");
        }
        voucher.setCode(code);
        voucher.setName(form.getName().trim());
        voucher.setType(form.getType());
        voucher.setDiscountValue(form.getDiscountValue());
        voucher.setMinimumOrder(form.getMinimumOrder());
        voucher.setMaxDiscount(form.getMaxDiscount());
        voucher.setQuantity(form.getQuantity());
        voucher.setMaxUsesPerUser(form.getMaxUsesPerUser());
        voucher.setStartsAt(form.getStartsAt());
        voucher.setEndsAt(form.getEndsAt());
        voucher.setActive(form.isActive());
        voucherRepository.save(voucher);
    }

    @Transactional
    public void toggleVoucher(User actor, Long id) {
        requireAdmin(actor);
        Voucher voucher = voucherRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã giảm giá."));
        voucher.setActive(!voucher.isActive());
    }

    @Transactional
    public void deleteVoucher(User actor, Long id) {
        requireAdmin(actor);
        Voucher voucher = voucherRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã giảm giá."));
        if (voucherUsageRepository.existsByVoucherId(id)) {
            throw new IllegalArgumentException("Mã đã có lịch sử sử dụng nên không thể xóa. Bạn có thể tắt mã này.");
        }
        voucherRepository.delete(voucher);
    }

    public Page<VoucherUsage> voucherUsages(User actor, Long voucherId, int page) {
        requireAdmin(actor);
        if (!voucherRepository.existsById(voucherId)) throw new IllegalArgumentException("Không tìm thấy mã giảm giá.");
        return voucherUsageRepository.findByVoucherIdOrderByUsedAtDesc(voucherId,
            PageRequest.of(Math.max(page, 0), 20));
    }

    private void requireAdmin(User actor) {
        requireManagementRole(actor);
        if (!isAdmin(actor)) throw new AccessDeniedException("Chỉ Admin được sử dụng chức năng này.");
    }

    private String cleanQuery(String value) {
        if (value == null || value.isBlank()) return null;
        String query = value.trim();
        return query.length() > 120 ? query.substring(0, 120) : query;
    }

    private boolean isAdmin(User actor) {
        return actor.getRole() == Role.ADMIN;
    }

    private void requireManagementRole(User actor) {
        if (actor == null || actor.getId() == null ||
                (actor.getRole() != Role.MANAGER && actor.getRole() != Role.ADMIN)) {
            throw new AccessDeniedException("Bạn không có quyền quản lý cửa hàng.");
        }
    }

    private AccessDeniedException forbiddenShop() {
        return new AccessDeniedException("Bạn không được quản lý cửa hàng này.");
    }
}
