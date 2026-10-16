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
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;
import vn.iotstar.service.AuditLogService;
import vn.iotstar.service.ManagementService;
import vn.iotstar.service.OrderService;

/** Step 28 only: read-only payment monitoring and audit access boundaries. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManagementStep28Tests {
    @Autowired ManagementService management;
    @Autowired AuditLogService audit;
    @Autowired OrderService ordersService;
    @Autowired PaymentTransactionRepository transactions;
    @Autowired AuditLogRepository logs;
    @Autowired ShopRepository shops;
    @Autowired UserRepository users;
    @Autowired ManagerShopRepository managerShops;
    @Autowired OrderRepository orders;
    @Autowired WebApplicationContext webContext;

    User admin, manager, customer, shipper;
    Shop accessible, other;

    @BeforeEach
    void setup() {
        admin = user(Role.ADMIN);
        manager = user(Role.MANAGER);
        customer = user(Role.USER);
        shipper = user(Role.SHIPPER);
        accessible = shop();
        other = shop();
        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(accessible);
        assignment.setAssignedBy(admin);
        managerShops.saveAndFlush(assignment);
    }

    @Test
    void paymentSearchRespectsManagerScopeAndTransactionStatus() {
        PaymentTransaction allowed = payment(order(accessible), PaymentTransactionStatus.SUCCESS);
        PaymentTransaction denied = payment(order(other), PaymentTransactionStatus.PENDING);
        assertThat(management.payments(admin, null, null, null, 0).getContent())
            .extracting(PaymentTransaction::getId).contains(allowed.getId(), denied.getId());
        assertThat(management.payments(manager, null, null, null, 0).getContent())
            .extracting(PaymentTransaction::getId).contains(allowed.getId()).doesNotContain(denied.getId());
        assertThat(management.payments(manager, allowed.getTransactionCode(), PaymentTransactionStatus.SUCCESS, accessible.getId(), 0)
            .getContent()).extracting(PaymentTransaction::getId).contains(allowed.getId());
        assertThat(management.payments(manager, null, PaymentTransactionStatus.FAILED, null, 0)).isEmpty();
        assertThatThrownBy(() -> management.payments(manager, null, null, other.getId(), 0))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> management.payments(customer, null, null, null, 0))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void auditRecordsAdministrationAndManagerSeesOnlyAssignedShops() {
        management.updateShop(admin, accessible.getId(), ShopStatus.APPROVED, new BigDecimal("8.50"));
        management.updateShop(admin, other.getId(), ShopStatus.SUSPENDED, new BigDecimal("7.00"));
        assertThat(audit.search(admin, null, AuditAction.SHOP_COMMISSION_CHANGED, 0).getTotalElements()).isGreaterThanOrEqualTo(2);
        assertThat(audit.search(manager, null, AuditAction.SHOP_COMMISSION_CHANGED, 0).getContent())
            .extracting(AuditLog::getTargetId).contains(accessible.getId()).doesNotContain(other.getId());
        assertThat(audit.search(manager, other.getName(), null, 0)).isEmpty();
        assertThatThrownBy(() -> audit.search(customer, null, null, 0))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void lockRoleAndManagerAssignmentAreAuditedAndPrivate() {
        management.toggleUser(customer.getId(), admin);
        management.changeRole(customer.getId(), Role.SHIPPER, admin);
        management.assignManagerShops(admin, manager.getId(), List.of(accessible.getId(), other.getId()));
        assertThat(logs.findAll()).extracting(AuditLog::getAction).contains(
            AuditAction.USER_LOCKED, AuditAction.USER_ROLE_CHANGED, AuditAction.MANAGER_SHOPS_ASSIGNED);
        assertThat(audit.search(manager, null, AuditAction.MANAGER_SHOPS_ASSIGNED, 0)).isEmpty();
        assertThatThrownBy(() -> management.changeRole(customer.getId(), Role.ADMIN, manager))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void shipperAssignmentAndOrderStatusHaveAuditTrail() {
        CustomerOrder order = order(accessible);
        order.setStatus(OrderStatus.READY_FOR_PICKUP);
        orders.saveAndFlush(order);
        ordersService.assignShipper(manager, order.getId(), shipper.getId());
        assertThat(logs.findAll()).extracting(AuditLog::getAction).contains(AuditAction.SHIPPER_ASSIGNED);
        assertThat(audit.search(manager, order.getCode(), null, 0).getContent())
            .extracting(AuditLog::getTargetId).contains(order.getId());
    }

    @Test
    void paymentAndAuditViewsRenderAndRequireManagementRole() throws Exception {
        payment(order(accessible), PaymentTransactionStatus.SUCCESS);
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        mvc.perform(get("/management/payments").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk());
        mvc.perform(get("/management/payments").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk());
        mvc.perform(get("/management/audit-logs").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk());
        mvc.perform(get("/management/audit-logs").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk());
        mvc.perform(get("/management/payments").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(customer.getUsername()).roles("USER")))
            .andExpect(status().is3xxRedirection());
    }

    private User user(Role role) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User u = new User();
        u.setUsername("s28" + suffix);
        u.setEmail("s28" + suffix + "@example.invalid");
        u.setPasswordHash("test-hash");
        u.setFullName("Step 28 " + suffix);
        u.setRole(role);
        u.setEnabled(true);
        return users.saveAndFlush(u);
    }

    private Shop shop() {
        Shop s = new Shop();
        s.setOwner(user(Role.VENDOR));
        s.setName("Shop " + UUID.randomUUID());
        s.setSlug(UUID.randomUUID().toString());
        s.setStatus(ShopStatus.PENDING);
        s.setCommissionRate(BigDecimal.ZERO);
        return shops.saveAndFlush(s);
    }

    private CustomerOrder order(Shop shop) {
        CustomerOrder o = new CustomerOrder();
        o.setCode("S28" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setUser(customer);
        o.setShop(shop);
        o.setReceiverName("Người nhận");
        o.setReceiverPhone("0900000000");
        o.setShippingAddress("123 Đường Test");
        o.setSubtotal(new BigDecimal("100000.00"));
        o.setTotalAmount(new BigDecimal("100000.00"));
        o.setProductDiscount(BigDecimal.ZERO);
        o.setVoucherDiscount(BigDecimal.ZERO);
        o.setShippingDiscount(BigDecimal.ZERO);
        o.setFinalShippingFee(BigDecimal.ZERO);
        return orders.saveAndFlush(o);
    }

    private PaymentTransaction payment(CustomerOrder order, PaymentTransactionStatus status) {
        PaymentTransaction t = new PaymentTransaction();
        t.setOrder(order);
        t.setTransactionCode("S28" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        t.setCheckoutReference("S28REF" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        t.setAmount(new BigDecimal("100000.00"));
        t.setClientIp("127.0.0.1");
        t.setStatus(status);
        return transactions.saveAndFlush(t);
    }
}
