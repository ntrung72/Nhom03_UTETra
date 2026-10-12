package vn.iotstar.service;

import java.math.BigDecimal;
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
