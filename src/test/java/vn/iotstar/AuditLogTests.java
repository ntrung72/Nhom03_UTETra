package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.DomainEnums.AuditAction;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.AuditLogRepository;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.AuditLogService;
import vn.iotstar.service.ManagementService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class AuditLogTests {
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private ManagerShopRepository managerShopRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ManagementService managementService;
    @Autowired private AuditLogService auditLogService;
    @Autowired private WebApplicationContext context;

    @Test
    void recordsAdminAndManagerChangesWithBeforeAfterAndRestrictsViewer() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User admin = account("audit-admin-" + suffix, Role.ADMIN);
        User manager = account("audit-manager-" + suffix, Role.MANAGER);
        User customer = account("audit-customer-" + suffix, Role.USER);
        Shop shop = new Shop();
        shop.setOwner(account("audit-vendor-" + suffix, Role.VENDOR));
        shop.setName("Audit shop " + suffix);
        shop.setSlug("audit-shop-" + suffix);
        shop = shopRepository.save(shop);

        managementService.toggleUser(customer.getId(), admin);
        managementService.changeRole(customer.getId(), Role.VENDOR, admin);
        managementService.updateShop(admin, shop.getId(), ShopStatus.APPROVED, new BigDecimal("6.00"));
        managementService.assignManagerShops(admin, manager.getId(), List.of(shop.getId()));
        managementService.updateShop(manager, shop.getId(), ShopStatus.APPROVED, new BigDecimal("7.00"));

        var logs = auditLogRepository.findAll();
        assertEquals(6, logs.size());
        assertTrue(logs.stream().anyMatch(log -> log.getAction() == AuditAction.USER_LOCKED
            && log.getTargetId().equals(customer.getId()) && "Hoạt động".equals(log.getOldValue())
            && "Đã khóa".equals(log.getNewValue())));
        assertTrue(logs.stream().anyMatch(log -> log.getAction() == AuditAction.SHOP_APPROVED
            && "Đã duyệt".equals(log.getNewValue())));
        assertTrue(logs.stream().anyMatch(log -> log.getAction() == AuditAction.SHOP_COMMISSION_CHANGED
            && manager.getUsername().equals(log.getActorUsername())
            && "6.00%".equals(log.getOldValue()) && "7.00%".equals(log.getNewValue())));
        assertEquals(2, auditLogService.search(admin, shop.getName(), AuditAction.SHOP_COMMISSION_CHANGED, 0)
            .getTotalElements());
        assertThrows(IllegalArgumentException.class,
            () -> auditLogService.search(manager, null, null, 0));

        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/management/audit-logs").with(user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk());
        mvc.perform(get("/management/audit-logs").with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/management"));
    }

    @Test
    void deniedManagerActionDoesNotCreateAuditEntry() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User manager = account("audit-manager-denied-" + suffix, Role.MANAGER);
        User customer = account("audit-customer-denied-" + suffix, Role.USER);
        Shop shop = new Shop();
        shop.setOwner(account("audit-vendor-denied-" + suffix, Role.VENDOR));
        shop.setName("Audit denied shop " + suffix);
        shop.setSlug("audit-denied-shop-" + suffix);
        shop = shopRepository.save(shop);
        Long shopId = shop.getId();

        assertThrows(IllegalArgumentException.class,
            () -> managementService.updateShop(manager, shopId, ShopStatus.APPROVED, BigDecimal.TEN));
        assertThrows(IllegalArgumentException.class,
            () -> managementService.changeRole(customer.getId(), Role.ADMIN, manager));
        assertEquals(0, auditLogRepository.count());
        assertEquals(0, managerShopRepository.findShopIdsByManagerId(manager.getId()).size());
    }

    private User account(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(username);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }
}
