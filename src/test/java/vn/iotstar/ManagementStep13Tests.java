package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.ManagementService;

/** Step 13 only: the User/Shop/Manager administration uses the Step 12 shop scope. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManagementStep13Tests {
    @Autowired private ManagementService management;
    @Autowired private UserRepository users;
    @Autowired private ShopRepository shops;
    @Autowired private ManagerShopRepository links;

    private User admin;
    private User manager;
    private User owner1;
    private User owner2;
    private Shop shop1;
    private Shop shop2;

    @BeforeEach
    void init() {
        admin = user(Role.ADMIN);
        manager = user(Role.MANAGER);
        owner1 = user(Role.VENDOR);
        owner2 = user(Role.VENDOR);
        shop1 = shop(owner1);
        shop2 = shop(owner2);
        management.assignManagerShops(admin, manager.getId(), List.of(shop1.getId()));
    }

    @Test
    void adminSearchesAllButManagerOnlyAssignedShopsAndOwners() {
        assertThat(management.shops(admin, null, null, 0).getContent())
            .extracting(Shop::getId).contains(shop1.getId(), shop2.getId());
        assertThat(management.shops(manager, null, null, 0).getContent())
            .extracting(Shop::getId).contains(shop1.getId()).doesNotContain(shop2.getId());
        assertThat(management.users(manager, null, 0).getContent())
            .extracting(User::getId).contains(owner1.getId()).doesNotContain(owner2.getId());
        assertThat(management.dashboard(manager).shops()).isEqualTo(1);
        assertThat(management.dashboard(manager).users()).isEqualTo(1);
        assertThatThrownBy(() -> management.users(owner1, null, 0))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void locksAreScopedCannotSelfLockOrLockAnotherManager() {
        assertThatThrownBy(() -> management.toggleUser(admin.getId(), admin))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> management.toggleUser(owner2.getId(), manager))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.toggleUser(admin.getId(), manager))
            .isInstanceOf(AccessDeniedException.class);
        management.toggleUser(owner1.getId(), manager);
        assertThat(users.findById(owner1.getId()).orElseThrow().isLocked()).isTrue();
    }

    @Test
    void roleChangesAreAdminOnlyAndClearOldManagerAssignments() {
        assertThatThrownBy(() -> management.changeRole(owner1.getId(), Role.MANAGER, manager))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.changeRole(admin.getId(), Role.USER, admin))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> management.changeRole(owner1.getId(), Role.USER, admin))
            .isInstanceOf(IllegalArgumentException.class);
        management.changeRole(manager.getId(), Role.USER, admin);
        assertThat(links.findShopIdsByManagerId(manager.getId())).isEmpty();
    }

    @Test
    void managerAssignmentValidatesRoleAndShopIds() {
        assertThatThrownBy(() -> management.assignManagerShops(manager, manager.getId(), List.of(shop2.getId())))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.assignManagerShops(admin, owner1.getId(), List.of(shop2.getId())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> management.assignManagerShops(admin, manager.getId(), List.of(999999L)))
            .isInstanceOf(IllegalArgumentException.class);
        management.assignManagerShops(admin, manager.getId(), List.of(shop2.getId(), shop2.getId()));
        assertThat(links.findShopIdsByManagerId(manager.getId())).containsExactly(shop2.getId());
        management.assignManagerShops(admin, manager.getId(), null);
        assertThat(links.findShopIdsByManagerId(manager.getId())).isEmpty();
    }

    @Test
    void shopApprovalAndCommissionRejectCrossShopAndInvalidRate() {
        assertThatThrownBy(() -> management.updateShop(manager, shop2.getId(), ShopStatus.APPROVED,
            new BigDecimal("5.00"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.updateShop(admin, shop1.getId(), ShopStatus.APPROVED,
            new BigDecimal("105"))).isInstanceOf(IllegalArgumentException.class);
        management.updateShop(manager, shop1.getId(), ShopStatus.SUSPENDED, new BigDecimal("10.00"));
        Shop reloaded = shops.findById(shop1.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ShopStatus.SUSPENDED);
        assertThat(reloaded.isEnabled()).isFalse();
        assertThat(reloaded.getCommissionRate()).isEqualByComparingTo("10.00");
    }

    private User user(Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("s13" + suffix);
        user.setEmail("s13" + suffix + "@example.invalid");
        user.setPasswordHash("test-hash");
        user.setFullName("Step 13 " + suffix);
        user.setEnabled(true);
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private Shop shop(User owner) {
        String suffix = UUID.randomUUID().toString();
        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName("Shop " + suffix);
        shop.setSlug(suffix);
        shop.setEnabled(true);
        shop.setStatus(ShopStatus.PENDING);
        return shops.saveAndFlush(shop);
    }
}
