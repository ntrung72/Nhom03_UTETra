package vn.iotstar.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.CarrierForm;
import vn.iotstar.dto.WebForms.CategoryForm;
import vn.iotstar.dto.WebForms.PromotionForm;
import vn.iotstar.dto.WebForms.VoucherForm;
import vn.iotstar.dto.WebForms.ToppingForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;

@Service
@RequiredArgsConstructor
public class ManagementService {
    private final UserRepository userRepository;
    private final ShopRepository shopRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final CarrierRepository carrierRepository;
    private final ToppingRepository toppingRepository;
    private final ProductToppingRepository productToppingRepository;
    private final PromotionRepository promotionRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherUsageRepository voucherUsageRepository;
    private final OrderRepository orderRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final ManagerShopRepository managerShopRepository;
    private final MediaStorageService mediaStorageService;
    private final AuditLogService auditLogService;

    public record DashboardStats(long userCount, long shopCount, long productCount,
                                 long orderCount, long voucherCount,
                                 BigDecimal platformRevenue, BigDecimal sellerRevenue) {}

    public DashboardStats dashboard(User actor) {
        if (isAdmin(actor)) {
            return new DashboardStats(userRepository.count(), shopRepository.count(), productRepository.count(),
                orderRepository.count(), voucherRepository.count(),
                zeroIfNull(orderRepository.sumPlatformRevenue(PaymentStatus.PAID, OrderStatus.DELIVERED)),
                zeroIfNull(orderRepository.sumSellerRevenue(PaymentStatus.PAID, OrderStatus.DELIVERED)));
        }
        List<Long> shopIds = assignedShopIds(actor);
        if (shopIds.isEmpty()) return new DashboardStats(0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        long users = userRepository.searchForAssignedShops(shopIds, null, PageRequest.of(0, 1)).getTotalElements();
        return new DashboardStats(users, shopIds.size(), productRepository.countByShopIdIn(shopIds),
            orderRepository.countByShopIdIn(shopIds), 0,
            zeroIfNull(orderRepository.sumPlatformRevenueByShopIds(shopIds, PaymentStatus.PAID, OrderStatus.DELIVERED)),
            zeroIfNull(orderRepository.sumSellerRevenueByShopIds(shopIds, PaymentStatus.PAID, OrderStatus.DELIVERED)));
    }

    public Page<User> users(User actor, String q, int page) {
        var pageable = PageRequest.of(Math.max(page, 0), 20, Sort.by("id").descending());
        if (isAdmin(actor)) return userRepository.search(vn.iotstar.util.SlugUtils.cleanQuery(q), pageable);
        List<Long> shopIds = assignedShopIds(actor);
        return shopIds.isEmpty() ? Page.empty(pageable)
            : userRepository.searchForAssignedShops(shopIds, vn.iotstar.util.SlugUtils.cleanQuery(q), pageable);
    }

    @Transactional
    public void toggleUser(Long id, User actor) {
        User target = userRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng."));
        if (target.getId().equals(actor.getId())) {
            throw new IllegalArgumentException("Bạn không thể tự khóa tài khoản của mình.");
        }
        if (actor.getRole() == Role.MANAGER && (target.getRole() == Role.ADMIN || target.getRole() == Role.MANAGER)) {
            throw new IllegalArgumentException("Manager không thể khóa tài khoản Admin hoặc Manager khác.");
        }
        if (!isAdmin(actor)) {
            List<Long> shopIds = assignedShopIds(actor);
            if (shopIds.isEmpty() || !userRepository.isUserInAssignedShops(target.getId(), shopIds)) {
                throw new IllegalArgumentException("Bạn không được quản lý tài khoản ngoài cửa hàng được phân công.");
            }
        }
        boolean wasLocked = target.isLocked();
        target.setLocked(!wasLocked);
        auditLogService.record(target.isLocked() ? AuditAction.USER_LOCKED : AuditAction.USER_UNLOCKED,
            actor, "USER", target.getId(), target.getUsername(),
            wasLocked ? "Đã khóa" : "Hoạt động", target.isLocked() ? "Đã khóa" : "Hoạt động", null);
    }

    @Transactional
    public void changeRole(Long id, Role role, User actor) {
        requireAdmin(actor);
        User target = userRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người dùng."));
        if (target.getId().equals(actor.getId())) throw new IllegalArgumentException("Không thể tự đổi vai trò Admin.");
        Role previousRole = target.getRole();
        target.setRole(role);
        if (role != Role.MANAGER) managerShopRepository.deleteByManagerId(target.getId());
        if (previousRole != role) {
            auditLogService.record(AuditAction.USER_ROLE_CHANGED, actor, "USER", target.getId(),
                target.getUsername(), previousRole.getLabel(), role.getLabel(), null);
        }
    }

    public Page<Shop> shops(User actor, String q, ShopStatus status, int page) {
        var pageable = PageRequest.of(Math.max(page, 0), 20, Sort.by("createdAt").descending());
        if (isAdmin(actor)) return shopRepository.search(vn.iotstar.util.SlugUtils.cleanQuery(q), status, pageable);
        List<Long> shopIds = assignedShopIds(actor);
        return shopIds.isEmpty() ? Page.empty(pageable)
            : shopRepository.searchAssigned(shopIds, vn.iotstar.util.SlugUtils.cleanQuery(q), status, pageable);
    }

    @Transactional
    public void updateShop(User actor, Long id, ShopStatus status, BigDecimal commissionRate) {
        Shop shop = shopRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cửa hàng."));
        requireShopAccess(actor, shop.getId());
        if (commissionRate == null || commissionRate.compareTo(BigDecimal.ZERO) < 0
            || commissionRate.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Chiết khấu ứng dụng phải từ 0 đến 100%.");
        }
        ShopStatus previousStatus = shop.getStatus();
        BigDecimal previousCommission = shop.getCommissionRate();
        shop.setStatus(status);
        shop.setCommissionRate(commissionRate);
        shop.setEnabled(status != ShopStatus.SUSPENDED && status != ShopStatus.REJECTED);
        if (previousStatus != status) {
            auditLogService.record(status == ShopStatus.APPROVED ? AuditAction.SHOP_APPROVED : AuditAction.SHOP_STATUS_CHANGED,
                actor, "SHOP", shop.getId(), shop.getName(), previousStatus.getLabel(), status.getLabel(), null);
        }
        if (previousCommission == null || previousCommission.compareTo(commissionRate) != 0) {
            auditLogService.record(AuditAction.SHOP_COMMISSION_CHANGED, actor, "SHOP", shop.getId(),
                shop.getName(), previousCommission == null ? null : previousCommission.toPlainString() + "%",
                commissionRate.toPlainString() + "%", null);
        }
    }

    public Page<Category> categories(String q, int page) {
        return categoryRepository.search(vn.iotstar.util.SlugUtils.cleanQuery(q),
            PageRequest.of(Math.max(page, 0), 20, Sort.by("name")));
    }

    public Category getCategory(Long id) {
        return categoryRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy danh mục."));
    }

    public List<Category> allCategories() {
        return categoryRepository.findAll();
    }

    @Transactional
    public void saveCategory(Long id, CategoryForm form, MultipartFile image) {
        Category category = id == null ? new Category() : categoryRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy danh mục."));
        String slug = vn.iotstar.util.SlugUtils.toSlug(form.getName());
        boolean duplicatedSlug = id == null ? categoryRepository.existsBySlug(slug)
            : categoryRepository.existsBySlugAndIdNot(slug, id);
        if (duplicatedSlug) throw new IllegalArgumentException("Tên danh mục đã tồn tại.");
        category.setName(form.getName().trim());
        category.setSlug(slug);
        category.setDescription(form.getDescription() == null ? null : form.getDescription().trim());
        category.setActive(form.isActive());
        String url = mediaStorageService.upload(image, "categories");
        if (url != null) {
            String previousUrl = category.getImageUrl();
            category.setImageUrl(url);
            mediaStorageService.deleteAfterCommit(previousUrl);
        }
        if (category.getImageUrl() == null) category.setImageUrl("/images/category-placeholder.svg");
        categoryRepository.save(category);
    }

    @Transactional
    public void toggleCategory(Long id) {
        Category category = categoryRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy danh mục."));
        category.setActive(!category.isActive());
    }

    public Page<Product> products(User actor, String q, Long shopId, Long categoryId, int page) {
        var pageable = PageRequest.of(Math.max(page, 0), 20, Sort.by("createdAt").descending());
        String search = vn.iotstar.util.SlugUtils.cleanQuery(q);
        String searchSlug = search == null ? null : vn.iotstar.util.SlugUtils.toSlug(search);
        if (isAdmin(actor)) {
            return productRepository.searchManagement(search, searchSlug, shopId, categoryId, pageable);
        }
        List<Long> shopIds = assignedShopIds(actor);
        return shopIds.isEmpty() ? Page.empty(pageable)
            : productRepository.searchManagementAssigned(shopIds, search, searchSlug, shopId, categoryId, pageable);
    }

    @Transactional
    public void toggleProduct(User actor, Long id) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm."));
        requireShopAccess(actor, product.getShop().getId());
        product.setStatus(product.getStatus() == ProductStatus.HIDDEN
            ? (product.getStock() > 0 ? ProductStatus.ACTIVE : ProductStatus.OUT_OF_STOCK) : ProductStatus.HIDDEN);
    }

    public Page<Carrier> carriers(String q, int page) {
        return carrierRepository.search(vn.iotstar.util.SlugUtils.cleanQuery(q),
            PageRequest.of(Math.max(page, 0), 20, Sort.by("name")));
    }

    public Carrier getCarrier(Long id) {
        return carrierRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà vận chuyển."));
    }

    @Transactional
    public void saveCarrier(Long id, CarrierForm form) {
        Carrier carrier = id == null ? new Carrier() : carrierRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà vận chuyển."));
        String name = form.getName().trim();
        boolean duplicatedName = id == null ? carrierRepository.existsByNameIgnoreCase(name)
            : carrierRepository.existsByNameIgnoreCaseAndIdNot(name, id);
        if (duplicatedName) throw new IllegalArgumentException("Tên nhà vận chuyển đã tồn tại.");
        carrier.setName(name);
        carrier.setShippingFee(form.getShippingFee());
        carrier.setActive(form.isActive());
        carrierRepository.save(carrier);
    }

    @Transactional
    public void toggleCarrier(Long id) {
        Carrier carrier = carrierRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhà vận chuyển."));
        carrier.setActive(!carrier.isActive());
    }

    public Page<Topping> toppings(String q, int page) {
        return toppingRepository.search(vn.iotstar.util.SlugUtils.cleanQuery(q),
            PageRequest.of(Math.max(page, 0), 20, Sort.by("name")));
    }

    public Topping getTopping(Long id) {
        return toppingRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy topping."));
    }

    public long toppingProductCount(Long id) {
        return productToppingRepository.countByToppingId(id);
    }

    @Transactional
    public void saveTopping(Long id, ToppingForm form) {
        Topping topping = id == null ? new Topping() : getTopping(id);
        String name = form.getName().trim();
        boolean duplicatedName = id == null ? toppingRepository.existsByNameIgnoreCase(name)
            : toppingRepository.existsByNameIgnoreCaseAndIdNot(name, id);
        if (duplicatedName) throw new IllegalArgumentException("Tên topping đã tồn tại.");
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
        var pageable = PageRequest.of(Math.max(page, 0), 20, Sort.by("startsAt").descending());
        if (isAdmin(actor)) return promotionRepository.search(vn.iotstar.util.SlugUtils.cleanQuery(q), shopId, type, pageable);
        List<Long> shopIds = assignedShopIds(actor);
        return shopIds.isEmpty() ? Page.empty(pageable)
            : promotionRepository.searchAssigned(shopIds, vn.iotstar.util.SlugUtils.cleanQuery(q), shopId, type, pageable);
    }

    @Transactional
    public void savePromotion(Long id, Long shopId, PromotionForm form, User actor) {
        if (form.getEndsAt() == null || form.getStartsAt() == null || !form.getEndsAt().isAfter(form.getStartsAt())) {
            throw new IllegalArgumentException("Thời gian kết thúc phải sau thời gian bắt đầu.");
        }
        if (form.getType() == PromotionType.PRODUCT_PERCENT
            && form.getDiscountValue().compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Mức giảm phần trăm không được vượt quá 100%.");
        }
        Promotion promotion = id == null ? new Promotion() : getPromotion(actor, id);
        Shop shop = shopId == null ? null : shopRepository.findById(shopId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cửa hàng."));
        if (shop == null && !isAdmin(actor)) {
            throw new IllegalArgumentException("Manager phải chọn một cửa hàng được phân công.");
        }
        if (shop != null) requireShopAccess(actor, shop.getId());
        Product product = form.getProductId() == null ? null : productRepository.findById(form.getProductId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm."));
        if (product != null) requireShopAccess(actor, product.getShop().getId());
        if (form.getType() == PromotionType.SHIPPING_DISCOUNT && product != null) {
            throw new IllegalArgumentException("Khuyến mãi phí vận chuyển không áp dụng riêng cho một sản phẩm.");
        }
        if (product != null && (shop == null || !product.getShop().getId().equals(shop.getId()))) {
            throw new IllegalArgumentException("Sản phẩm không thuộc cửa hàng đã chọn.");
        }
        promotion.setName(form.getName().trim());
        promotion.setType(form.getType());
        promotion.setDiscountValue(form.getDiscountValue());
        promotion.setShop(shop);
        promotion.setProduct(product);
        promotion.setStartsAt(form.getStartsAt());
        promotion.setEndsAt(form.getEndsAt());
        promotion.setActive(form.isActive());
        promotion.setCreatedBy(actor);
        promotionRepository.save(promotion);
    }

    @Transactional
    public void deletePromotion(User actor, Long id) {
        Promotion promotion = getPromotion(actor, id);
        promotionRepository.delete(promotion);
    }

    public Page<Voucher> vouchers(String q, VoucherType type, Boolean active, int page) {
        return voucherRepository.search(vn.iotstar.util.SlugUtils.cleanQuery(q), type, active,
            PageRequest.of(Math.max(page, 0), 20, Sort.by("startsAt").descending()));
    }

    public Voucher getVoucher(Long id) {
        return voucherRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã giảm giá."));
    }

    @Transactional
    public void saveVoucher(Long id, VoucherForm form) {
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
    public void toggleVoucher(Long id) {
        Voucher voucher = voucherRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã giảm giá."));
        voucher.setActive(!voucher.isActive());
    }

    @Transactional
    public void deleteVoucher(Long id) {
        Voucher voucher = voucherRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy mã giảm giá."));
        if (orderRepository.existsByVoucherId(id)) {
            throw new IllegalArgumentException("Mã đã được sử dụng trong đơn hàng nên không thể xóa. Bạn có thể tắt mã này.");
        }
        if (voucherUsageRepository.existsByVoucherId(id)) {
            throw new IllegalArgumentException("Mã đã có lịch sử sử dụng nên không thể xóa. Bạn có thể tắt mã này.");
        }
        voucherRepository.delete(voucher);
    }

    public Page<VoucherUsage> voucherUsages(Long voucherId, int page) {
        if (!voucherRepository.existsById(voucherId)) throw new IllegalArgumentException("Không tìm thấy mã giảm giá.");
        return voucherUsageRepository.findByVoucherIdOrderByUsedAtDesc(voucherId,
            PageRequest.of(Math.max(page, 0), 20));
    }

    public Page<CustomerOrder> orders(User actor, String q, Long shopId, OrderStatus status, int page) {
        return orders(actor, q, shopId, status, false, page);
    }

    public Page<CustomerOrder> orders(User actor, String q, Long shopId, OrderStatus status,
                                      boolean pending, int page) {
        var pageable = PageRequest.of(Math.max(page, 0), 20, Sort.by("createdAt").descending());
        if (isAdmin(actor)) return orderRepository.searchManagement(vn.iotstar.util.SlugUtils.cleanQuery(q),
            shopId, status, pending, OrderStatus.workQueueStatuses(), pageable);
        List<Long> shopIds = assignedShopIds(actor);
        return shopIds.isEmpty() ? Page.empty(pageable)
            : orderRepository.searchManagementAssigned(shopIds, vn.iotstar.util.SlugUtils.cleanQuery(q),
                shopId, status, pending, OrderStatus.workQueueStatuses(), pageable);
    }

    public Page<PaymentTransaction> payments(User actor, String q, PaymentTransactionStatus status, int page) {
        requireAdmin(actor);
        return paymentTransactionRepository.searchManagement(vn.iotstar.util.SlugUtils.cleanQuery(q), status,
            PageRequest.of(Math.max(page, 0), 20, Sort.by("createdAt").descending().and(Sort.by("id").descending())));
    }

    public Set<Long> paymentReconciliationOrderIds(Page<PaymentTransaction> payments) {
        Set<Long> orderIds = new LinkedHashSet<>();
        for (PaymentTransaction payment : payments.getContent()) {
            if (payment.getStatus() == PaymentTransactionStatus.SUCCESS) {
                CustomerOrder order = payment.getOrder();
                if (order.getStatus() == OrderStatus.CANCELLED || order.getPaymentStatus() == PaymentStatus.UNPAID) {
                    orderIds.add(order.getId());
                }
            }
        }
        List<Long> successfulOrderIds = payments.getContent().stream()
            .filter(payment -> payment.getStatus() == PaymentTransactionStatus.SUCCESS)
            .map(payment -> payment.getOrder().getId()).distinct().toList();
        if (!successfulOrderIds.isEmpty()) {
            orderIds.addAll(paymentTransactionRepository.findOrderIdsWithMultipleTransactionsInStatus(
                successfulOrderIds, PaymentTransactionStatus.SUCCESS));
        }
        return orderIds;
    }

    public List<User> shippers() {
        return userRepository.findByRoleAndEnabledTrueAndLockedFalseOrderByFullNameAsc(Role.SHIPPER);
    }

    public List<Shop> accessibleShops(User actor) {
        if (isAdmin(actor)) return shopRepository.findAll(Sort.by("name"));
        return managerShopRepository.findByManagerIdOrderByShopNameAsc(actor.getId()).stream()
            .map(ManagerShop::getShop).toList();
    }

    public List<Product> accessibleProducts(User actor) {
        if (isAdmin(actor)) return productRepository.findAll(Sort.by("name"));
        List<Long> shopIds = assignedShopIds(actor);
        return shopIds.isEmpty() ? List.of() : productRepository.findByShopIdInOrderByNameAsc(shopIds);
    }

    public Promotion getPromotion(User actor, Long id) {
        Promotion promotion = promotionRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khuyến mãi."));
        if (promotion.getShop() == null && !isAdmin(actor)) throw forbiddenShop();
        if (promotion.getShop() != null) requireShopAccess(actor, promotion.getShop().getId());
        return promotion;
    }

    public List<User> managers(User actor) {
        requireAdmin(actor);
        return userRepository.findByRoleOrderByFullNameAsc(Role.MANAGER);
    }

    public Map<Long, Set<Long>> managerAssignments(User actor) {
        Map<Long, Set<Long>> result = new LinkedHashMap<>();
        for (User manager : managers(actor)) {
            result.put(manager.getId(), new LinkedHashSet<>(managerShopRepository.findShopIdsByManagerId(manager.getId())));
        }
        return result;
    }

    @Transactional
    public void assignManagerShops(User actor, Long managerId, List<Long> shopIds) {
        requireAdmin(actor);
        User manager = userRepository.findById(managerId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Manager."));
        if (manager.getRole() != Role.MANAGER) {
            throw new IllegalArgumentException("Tài khoản được chọn không phải Manager.");
        }
        List<Long> uniqueIds = shopIds == null ? List.of() : shopIds.stream().distinct().toList();
        List<Shop> shops = uniqueIds.isEmpty() ? List.of() : shopRepository.findAllById(uniqueIds);
        if (shops.size() != uniqueIds.size()) throw new IllegalArgumentException("Có cửa hàng không tồn tại.");
        List<Long> previousIds = managerShopRepository.findShopIdsByManagerId(managerId);
        managerShopRepository.deleteByManagerId(managerId);
        managerShopRepository.flush();
        for (Shop shop : shops) {
            ManagerShop assignment = new ManagerShop();
            assignment.setManager(manager);
            assignment.setShop(shop);
            assignment.setAssignedBy(actor);
            managerShopRepository.save(assignment);
        }
        if (!new java.util.HashSet<>(previousIds).equals(new java.util.HashSet<>(uniqueIds))) {
            auditLogService.record(AuditAction.MANAGER_SHOPS_ASSIGNED, actor, "USER", manager.getId(),
                manager.getUsername(), previousIds.toString(), uniqueIds.toString(), "Danh sách ID cửa hàng được phân công");
        }
    }

    public void requireShopAccess(User actor, Long shopId) {
        if (isAdmin(actor)) return;
        if (actor.getRole() != Role.MANAGER
            || !managerShopRepository.existsByManagerIdAndShopId(actor.getId(), shopId)) {
            throw forbiddenShop();
        }
    }

    private List<Long> assignedShopIds(User actor) {
        return actor.getRole() == Role.MANAGER
            ? managerShopRepository.findShopIdsByManagerId(actor.getId()) : List.of();
    }

    private boolean isAdmin(User actor) { return actor.getRole() == Role.ADMIN; }

    private BigDecimal zeroIfNull(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private void requireAdmin(User actor) {
        if (!isAdmin(actor)) throw new IllegalArgumentException("Chỉ Admin được thực hiện chức năng này.");
    }

    private IllegalArgumentException forbiddenShop() {
        return new IllegalArgumentException("Bạn không được quản lý cửa hàng này.");
    }
}
