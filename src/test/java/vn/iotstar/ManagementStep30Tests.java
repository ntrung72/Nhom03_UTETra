package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
import vn.iotstar.entity.ManagerShop;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.DashboardService;

/** Scope and rendering regression tests for TV3 Step 30 only. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ManagementStep30Tests {
    @Autowired DashboardService dashboards;
    @Autowired ShopRepository shops;
    @Autowired UserRepository users;
    @Autowired ManagerShopRepository assignments;
    @Autowired OrderRepository orders;
    @Autowired WebApplicationContext webContext;
    User admin, manager, customer;
    Shop assigned, outside;

    @BeforeEach
    void setUp() {
        admin = createUser(Role.ADMIN);
        manager = createUser(Role.MANAGER);
        customer = createUser(Role.USER);
        assigned = createShop();
        outside = createShop();
        ManagerShop link = new ManagerShop();
        link.setManager(manager);
        link.setShop(assigned);
        link.setAssignedBy(admin);
        assignments.saveAndFlush(link);
        createPaidOrder(assigned, "120000", "110000", "10000");
        createPaidOrder(outside, "250000", "220000", "30000");
    }

    @Test
    void managerSeesOnlyAssignedShopsAndRevenueSnapshots() {
        var range = dashboards.range("7", null, null);
        var scoped = dashboards.forManagement(manager, null, range);
        assertThat(scoped.periodGross()).isEqualByComparingTo("120000");
        assertThat(scoped.periodCommission()).isEqualByComparingTo("10000");
        assertThat(scoped.periodRevenue()).isEqualByComparingTo("120000");
        assertThat(scoped.periodOrderCount()).isEqualTo(1);
        assertThat(scoped.shopRevenues()).hasSize(1);
        assertThat(scoped.shopRevenues().getFirst().name()).isEqualTo(assigned.getName());
        var global = dashboards.forManagement(admin, null, range);
        assertThat(global.periodGross()).isEqualByComparingTo("370000");
        assertThat(global.periodCommission()).isEqualByComparingTo("40000");
        assertThat(global.shopRevenues()).hasSize(2);
    }

    @Test
    void managerCannotForgeShopFilterOrAccessAsCustomer() {
        var range = dashboards.range("30", null, null);
        assertThatThrownBy(() -> dashboards.forManagement(manager, outside.getId(), range))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> dashboards.forManagement(customer, null, range))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(dashboards.forManagement(admin, outside.getId(), range).periodGross())
            .isEqualByComparingTo("250000");
    }

    @Test
    void rangeRejectsInvalidDates() {
        LocalDate today = LocalDate.now();
        assertThatThrownBy(() -> dashboards.range("custom", today, today.minusDays(1)))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> dashboards.range("custom", today, today.plusDays(1)))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void adminAndManagerDashboardRenderChartsAndReports() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        mvc.perform(get("/management").with(user(admin.getUsername()).roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(view().name("management/dashboard"));
        mvc.perform(get("/management?range=7&shopId=" + assigned.getId())
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk()).andExpect(view().name("management/dashboard"));
        mvc.perform(get("/management?shopId=" + outside.getId())
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isForbidden());
    }

    private User createUser(Role role) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User value = new User();
        value.setUsername("d30" + suffix);
        value.setEmail("d30" + suffix + "@example.invalid");
        value.setFullName("Dashboard " + suffix);
        value.setPasswordHash("test-hash");
        value.setRole(role);
        value.setEnabled(true);
        return users.saveAndFlush(value);
    }

    private Shop createShop() {
        Shop shop = new Shop();
        shop.setOwner(createUser(Role.VENDOR));
        shop.setName("Dashboard Shop " + UUID.randomUUID());
        shop.setSlug(UUID.randomUUID().toString());
        shop.setStatus(ShopStatus.APPROVED);
        shop.setCommissionRate(BigDecimal.TEN);
        return shops.saveAndFlush(shop);
    }

    private CustomerOrder createPaidOrder(Shop shop, String gross, String seller, String platform) {
        CustomerOrder order = new CustomerOrder();
        order.setUser(customer);
        order.setShop(shop);
        order.setCode("D30" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        order.setReceiverName("Khách kiểm thử");
        order.setReceiverPhone("0900000000");
        order.setShippingAddress("Địa chỉ kiểm thử");
        order.setSubtotal(new BigDecimal(gross));
        order.setTotalAmount(new BigDecimal(gross));
        order.setProductDiscount(BigDecimal.ZERO);
        order.setVoucherDiscount(BigDecimal.ZERO);
        order.setShippingDiscount(BigDecimal.ZERO);
        order.setFinalShippingFee(BigDecimal.ZERO);
        order.setSellerRevenue(new BigDecimal(seller));
        order.setPlatformRevenue(new BigDecimal(platform));
        order.setStatus(OrderStatus.DELIVERED);
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setDeliveredAt(LocalDate.now().atTime(10, 0));
        return orders.saveAndFlush(order);
    }
}
