package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.ManagerShop;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.DashboardService;
import vn.iotstar.service.ManagementService;

/** TV3 Bước 43: dashboard comparison, previous-period scope and action filters. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManagementDashboardComparisonTests {
    @Autowired DashboardService dashboard;
    @Autowired ManagementService management;
    @Autowired UserRepository users;
    @Autowired ShopRepository shops;
    @Autowired ManagerShopRepository links;
    @Autowired OrderRepository orders;
    @Autowired WebApplicationContext web;

    User admin, manager, customer;
    Shop assigned, outside;

    @BeforeEach void before() {
        admin = userAccount(Role.ADMIN);
        manager = userAccount(Role.MANAGER);
        customer = userAccount(Role.USER);
        assigned = shop();
        outside = shop();
        ManagerShop link = new ManagerShop();
        link.setManager(manager);
        link.setAssignedBy(admin);
        link.setShop(assigned);
        links.saveAndFlush(link);
        completed(assigned, "120000", LocalDate.now());
        completed(assigned, "60000", LocalDate.now().minusDays(7));
        completed(outside, "240000", LocalDate.now());
        completed(outside, "180000", LocalDate.now().minusDays(7));
    }

    @Test void scopedComparisonMatchesCurrentAndPreviousSevenDays() {
        var r = dashboard.range("7", null, null);
        var managerData = dashboard.forManagement(manager, null, r);
        assertThat(managerData.periodGross()).isEqualByComparingTo("120000");
        assertThat(managerData.revenueComparison().previous()).isEqualByComparingTo("60000");
        assertThat(managerData.revenueComparison().changePercent()).isEqualByComparingTo("100.0");
        assertThat(managerData.shopRevenues()).hasSize(1);
        var adminData = dashboard.forManagement(admin, null, r);
        assertThat(adminData.periodGross()).isEqualByComparingTo("360000");
        assertThat(adminData.revenueComparison().previous()).isEqualByComparingTo("240000");
        assertThat(dashboard.forManagement(admin, outside.getId(), r).periodGross()).isEqualByComparingTo("240000");
    }

    @Test void managerShopIsEnforcedForComparisonAndOrderWorkQueue() {
        var range = dashboard.range("7", null, null);
        assertThatThrownBy(() -> dashboard.forManagement(manager, outside.getId(), range))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        CustomerOrder todo = order(assigned, OrderStatus.NEW);
        CustomerOrder forbidden = order(outside, OrderStatus.NEW);
        var page = management.orders(manager, null, null, null, true, 0);
        assertThat(page.getContent()).extracting(CustomerOrder::getId)
            .contains(todo.getId()).doesNotContain(forbidden.getId());
        assertThatThrownBy(() -> management.orders(manager, null, outside.getId(), null, true, 0))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void dashboardAndPendingActionRenderForBothRoles() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        mvc.perform(get("/management?range=7").with(user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk());
        mvc.perform(get("/management?range=7&shopId=" + assigned.getId())
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk());
        mvc.perform(get("/management/orders?pending=true&shop=" + assigned.getId())
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk());
    }

    private User userAccount(Role role) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User u = new User();
        u.setUsername("s43m" + id);
        u.setEmail("s43m" + id + "@example.invalid");
        u.setPasswordHash("test-only-hash");
        u.setFullName("S43 " + id);
        u.setRole(role);
        u.setEnabled(true);
        return users.saveAndFlush(u);
    }

    private Shop shop() {
        Shop s = new Shop();
        s.setOwner(userAccount(Role.VENDOR));
        s.setName("S43 shop " + UUID.randomUUID());
        s.setSlug(UUID.randomUUID().toString());
        s.setStatus(ShopStatus.APPROVED);
        s.setCommissionRate(BigDecimal.TEN);
        return shops.saveAndFlush(s);
    }

    private CustomerOrder completed(Shop shop, String amount, LocalDate date) {
        CustomerOrder o = order(shop, OrderStatus.DELIVERED);
        o.setPaymentStatus(PaymentStatus.PAID);
        o.setDeliveredAt(date.atTime(12, 0));
        o.setTotalAmount(new BigDecimal(amount));
        o.setSellerRevenue(new BigDecimal(amount).multiply(new BigDecimal("0.9")));
        o.setPlatformRevenue(new BigDecimal(amount).multiply(new BigDecimal("0.1")));
        return orders.saveAndFlush(o);
    }

    private CustomerOrder order(Shop shop, OrderStatus status) {
        CustomerOrder o = new CustomerOrder();
        o.setUser(customer);
        o.setShop(shop);
        o.setCode("S43" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setReceiverName("Khách kiểm thử");
        o.setReceiverPhone("0900000000");
        o.setShippingAddress("Địa chỉ kiểm thử");
        o.setSubtotal(new BigDecimal("100000"));
        o.setTotalAmount(new BigDecimal("100000"));
        o.setProductDiscount(BigDecimal.ZERO);
        o.setVoucherDiscount(BigDecimal.ZERO);
        o.setShippingDiscount(BigDecimal.ZERO);
        o.setFinalShippingFee(BigDecimal.ZERO);
        o.setStatus(status);
        return orders.saveAndFlush(o);
    }
}
