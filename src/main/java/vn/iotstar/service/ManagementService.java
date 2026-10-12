package vn.iotstar.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.ManagerShop;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.ProductRepository;
import vn.iotstar.repository.PromotionRepository;
import vn.iotstar.repository.ShopRepository;

/**
 * Scope foundation for Step 12 only. Management CRUD/controllers belong to later steps.
 * Every management operation added later must call requireShopAccess(actor, shopId)
 * before returning or changing shop-owned records.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManagementService {

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
