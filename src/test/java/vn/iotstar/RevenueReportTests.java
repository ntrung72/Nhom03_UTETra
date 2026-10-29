package vn.iotstar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.ManagerShop;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.DashboardService;
import vn.iotstar.service.RevenueReportService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RevenueReportTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private ShopRepository shops;
    @Autowired private ManagerShopRepository assignments;
    @Autowired private OrderRepository orders;
    @Autowired private RevenueReportService reports;
    @Autowired private DashboardService dashboards;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void reportMatchesDashboardAndNeverLeaksOtherShops() throws Exception {
        User admin = saveUser("report-admin", Role.ADMIN);
        User manager = saveUser("report-manager", Role.MANAGER);
        User vendorA = saveUser("report-vendor-a", Role.VENDOR);
        User vendorB = saveUser("report-vendor-b", Role.VENDOR);
        User customer = saveUser("report-customer", Role.USER);
        Shop shopA = saveShop("report-shop-a", vendorA);
        Shop shopB = saveShop("=HYPERLINK(\"url\",\"shop\")", vendorB);
        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(shopA);
        assignment.setAssignedBy(admin);
        assignments.save(assignment);

        LocalDate today = LocalDate.now();
        saveOrder("REPORT-A-DELIVERED", customer, shopA, OrderStatus.DELIVERED, PaymentStatus.PAID,
            today.atTime(10, 0), "100000", "10000", "5000", "20000", "3000", "102000", "10");
        saveOrder("REPORT-A-NEW", customer, shopA, OrderStatus.NEW, PaymentStatus.PAID,
            today.atTime(11, 0), "200000", "0", "0", "0", "0", "200000", "10");
        saveOrder("REPORT-B-DELIVERED", customer, shopB, OrderStatus.DELIVERED, PaymentStatus.PAID,
            today.atTime(12, 0), "50000", "0", "0", "0", "0", "50000", "10");
        saveOrder("REPORT-A-OLD", customer, shopA, OrderStatus.DELIVERED, PaymentStatus.PAID,
            today.minusDays(40).atTime(10, 0), "40000", "0", "0", "0", "0", "40000", "10");

        var delivered = reports.filter(null, null, null, null);
        var managerReport = reports.report(manager, delivered);
        assertEquals(1, managerReport.totals().orderCount());
        assertMoney("102000", managerReport.totals().grossRevenue());
        assertMoney("8500", managerReport.totals().commission());
        assertMoney("76500", managerReport.totals().sellerRevenue());
        assertMoney("15000", managerReport.totals().productDiscount().add(managerReport.totals().voucherDiscount()));
        assertMoney("20000", managerReport.totals().shippingFee());
        assertMoney("3000", managerReport.totals().shippingDiscount());
        assertMoney("17000", managerReport.totals().finalShippingFee());
        assertMoney("102000", dashboards.forManagement(manager).gross30Days());
        assertMoney("76500", dashboards.forVendor(shopA).revenue30Days());
        assertMoney("76500", reports.report(vendorA, delivered).totals().sellerRevenue());
        var sevenDays = dashboards.range("7", null, null);
        var ninetyDays = dashboards.range("90", null, null);
        var customDates = dashboards.range("custom", today.minusDays(45), today.minusDays(35));
        assertEquals(7, dashboards.forManagement(manager, shopA.getId(), sevenDays).dateLabels().size());
        assertEquals(90, dashboards.forManagement(manager, shopA.getId(), ninetyDays).dateLabels().size());
        assertMoney("102000", dashboards.forManagement(manager, shopA.getId(), sevenDays).periodGross());
        assertMoney("142000", dashboards.forManagement(manager, shopA.getId(), ninetyDays).periodGross());
        assertMoney("40000", dashboards.forManagement(manager, shopA.getId(), customDates).periodGross());
        assertMoney("50000", dashboards.forManagement(admin, shopB.getId(), sevenDays).periodGross());
        assertEquals(1, dashboards.forManagement(manager, shopA.getId(), sevenDays).newCount());
        assertEquals(1, dashboards.forManagement(manager, shopA.getId(), sevenDays).pendingCount());
        assertEquals(1, dashboards.forManagement(manager, shopA.getId(), sevenDays).deliveredPeriodCount());
        assertEquals(2, dashboards.forManagement(manager, shopA.getId(), sevenDays).periodOrderCount());
        assertEquals(2, dashboards.forManagement(manager, shopA.getId(), sevenDays).statusValues()
            .stream().mapToLong(Long::longValue).sum());
        assertEquals(3, dashboards.forManagement(manager, shopA.getId(), ninetyDays).statusValues()
            .stream().mapToLong(Long::longValue).sum());
        assertEquals(1, reports.tableRows(managerReport, "REPORT-A", 0).getTotalElements());
        assertEquals(0, reports.tableRows(managerReport, "REPORT-B", 0).getTotalElements());
        var forbiddenDashboard = assertThrows(ResponseStatusException.class,
            () -> dashboards.forManagement(manager, shopB.getId(), sevenDays));
        assertEquals(HttpStatus.FORBIDDEN, forbiddenDashboard.getStatusCode());
        assertThrows(ResponseStatusException.class,
            () -> dashboards.range("custom", today, today.minusDays(1)));

        var adminReport = reports.report(admin, delivered);
        assertEquals(2, adminReport.totals().orderCount());
        assertMoney("152000", adminReport.totals().grossRevenue());
        assertMoney("152000", dashboards.forManagement(admin).gross30Days());
        assertEquals(1, reports.report(admin, reports.filter(null, null, shopB.getId(), null)).rows().size());
        var forbidden = assertThrows(ResponseStatusException.class,
            () -> reports.report(manager, reports.filter(null, null, shopB.getId(), null)));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        assertThrows(ResponseStatusException.class,
            () -> reports.report(vendorA, reports.filter(null, null, shopB.getId(), null)));

        var all = reports.report(manager, reports.filter(null, null, null, "ALL"));
        assertEquals(2, all.totals().orderCount());
        assertEquals(1, all.totals().recognizedCount());
        assertMoney("102000", all.totals().grossRevenue());
        var newOnly = reports.report(manager, reports.filter(null, null, null, "NEW"));
        assertEquals(1, newOnly.totals().orderCount());
        assertMoney("0", newOnly.totals().grossRevenue());
        assertEquals(0, reports.report(manager,
            reports.filter(today.minusDays(2), today.minusDays(1), null, null)).rows().size());

        String csv = new String(reports.toCsv(adminReport), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"url\"\",\"\"shop\"\")\""));
        assertTrue(csv.contains("\"TỔNG\",\"\",\"\",\"\",\"\",\"\",\"2\""));
        assertTrue(csv.contains("\"152000\""));

        var managerDownload = mvc.perform(get("/management/reports/revenue.csv")
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk()).andReturn();
        String managerCsv = managerDownload.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(managerCsv.contains("REPORT-A-DELIVERED"));
        assertTrue(!managerCsv.contains("REPORT-B-DELIVERED"));
        mvc.perform(get("/management/reports/revenue.csv").param("shopId", shopB.getId().toString())
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/vendor/reports/revenue.csv")
                .with(user(vendorA.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk());
        mvc.perform(get("/management")
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk());
        String scopedDashboard = mvc.perform(get("/management")
                .param("range", "90").param("shopId", shopA.getId().toString())
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(scopedDashboard.contains("REPORT-A-OLD"));
        assertTrue(!scopedDashboard.contains("REPORT-B-DELIVERED"));
        mvc.perform(get("/management").param("shopId", shopB.getId().toString())
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/vendor")
                .with(user(vendorA.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk());
        mvc.perform(get("/vendor").param("shopId", shopB.getId().toString())
                .with(user(vendorA.getUsername()).roles("VENDOR")))
            .andExpect(status().isForbidden());
    }

    @Test
    void comparesWithPreviousEqualLengthPeriodWithinEachRoleScope() throws Exception {
        User admin = saveUser("compare-admin", Role.ADMIN);
        User manager = saveUser("compare-manager", Role.MANAGER);
        User vendorA = saveUser("compare-vendor-a", Role.VENDOR);
        User vendorB = saveUser("compare-vendor-b", Role.VENDOR);
        User customer = saveUser("compare-customer", Role.USER);
        Shop shopA = saveShop("compare-shop-a", vendorA);
        Shop shopB = saveShop("compare-shop-b", vendorB);
        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(shopA);
        assignment.setAssignedBy(admin);
        assignments.save(assignment);

        LocalDate today = LocalDate.now();
        saveOrder("COMPARE-A-CURRENT", customer, shopA, OrderStatus.DELIVERED, PaymentStatus.PAID,
            today.atTime(10, 0), "100000", "0", "0", "0", "0", "100000", "10");
        saveOrder("COMPARE-A-PREVIOUS", customer, shopA, OrderStatus.DELIVERED, PaymentStatus.PAID,
            today.minusDays(8).atTime(10, 0), "50000", "0", "0", "0", "0", "50000", "10");
        saveOrder("COMPARE-B-CURRENT", customer, shopB, OrderStatus.DELIVERED, PaymentStatus.PAID,
            today.atTime(11, 0), "300000", "0", "0", "0", "0", "300000", "10");
        saveOrder("COMPARE-B-PREVIOUS", customer, shopB, OrderStatus.DELIVERED, PaymentStatus.PAID,
            today.minusDays(8).atTime(11, 0), "200000", "0", "0", "0", "0", "200000", "10");
        saveOrder("COMPARE-A-UNPAID", customer, shopA, OrderStatus.DELIVERED, PaymentStatus.UNPAID,
            today.minusDays(8).atTime(12, 0), "99999", "0", "0", "0", "0", "99999", "10");
        saveOrder("COMPARE-A-PENDING", customer, shopA, OrderStatus.NEW, PaymentStatus.UNPAID,
            today.atTime(12, 0), "20000", "0", "0", "0", "0", "20000", "10");
        saveOrder("COMPARE-B-PENDING", customer, shopB, OrderStatus.NEW, PaymentStatus.UNPAID,
            today.atTime(13, 0), "30000", "0", "0", "0", "0", "30000", "10");

        var range = dashboards.range("7", null, null);
        var vendorComparison = dashboards.forVendor(shopA, range).revenueComparison();
        assertMoney("90000", vendorComparison.current());
        assertMoney("45000", vendorComparison.previous());
        assertMoney("100.0", vendorComparison.changePercent());

        var managerComparison = dashboards.forManagement(manager, null, range).revenueComparison();
        assertMoney("100000", managerComparison.current());
        assertMoney("50000", managerComparison.previous());
        assertMoney("100.0", managerComparison.changePercent());

        var adminComparison = dashboards.forManagement(admin, null, range).revenueComparison();
        assertMoney("400000", adminComparison.current());
        assertMoney("250000", adminComparison.previous());
        assertMoney("60.0", adminComparison.changePercent());

        String managerPage = mvc.perform(get("/management").param("range", "7")
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(managerPage.contains("Việc cần xử lý"));
        assertTrue(managerPage.contains("SO SÁNH CÙNG SỐ NGÀY"));
        assertTrue(managerPage.contains("#revenue-report"));
        assertTrue(managerPage.contains(shopA.getName()));
        assertTrue(!managerPage.contains(shopB.getName()));
        assertEquals(1, dashboards.forManagement(manager, null, range).pendingCount());
        String managerQueue = mvc.perform(get("/management/orders").param("pending", "true")
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(managerQueue.contains("COMPARE-A-PENDING"));
        assertTrue(!managerQueue.contains("COMPARE-B-PENDING"));
        assertTrue(!managerQueue.contains("COMPARE-A-CURRENT"));
        String vendorQueue = mvc.perform(get("/vendor/orders").param("pending", "true")
                .with(user(vendorA.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(vendorQueue.contains("COMPARE-A-PENDING"));
        assertTrue(!vendorQueue.contains("COMPARE-B-PENDING"));
    }

    @Test
    void dashboardReportTableSearchesAndPaginatesWithinAllowedShop() throws Exception {
        User admin = saveUser("paged-admin", Role.ADMIN);
        User manager = saveUser("paged-manager", Role.MANAGER);
        User vendor = saveUser("paged-vendor", Role.VENDOR);
        User customer = saveUser("paged-customer", Role.USER);
        Shop shop = saveShop("paged-shop", vendor);
        ManagerShop assignment = new ManagerShop();
        assignment.setManager(manager);
        assignment.setShop(shop);
        assignment.setAssignedBy(admin);
        assignments.save(assignment);
        for (int number = 0; number < 12; number++) {
            saveOrder("PAGE-" + number, customer, shop, OrderStatus.DELIVERED, PaymentStatus.PAID,
                LocalDate.now().atTime(9, number), "10000", "0", "0", "0", "0", "10000", "5");
        }

        String firstPage = mvc.perform(get("/management").param("range", "7")
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(firstPage.contains("Trang 1/2"));
        assertTrue(firstPage.contains("Xem/Xử lý"));
        String secondPage = mvc.perform(get("/management").param("range", "7").param("page", "1")
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(secondPage.contains("Trang 2/2"));
        String searched = mvc.perform(get("/management").param("q", "PAGE-11")
                .with(user(manager.getUsername()).roles("MANAGER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(searched.contains("PAGE-11"));
        assertTrue(!searched.contains("PAGE-10"));
        String vendorOrders = mvc.perform(get("/vendor/orders").param("q", "PAGE-11")
                .with(user(vendor.getUsername()).roles("VENDOR")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(vendorOrders.contains("PAGE-11"));
        assertTrue(!vendorOrders.contains("PAGE-10"));
    }

    private User saveUser(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(username);
        user.setRole(role);
        user.setEnabled(true);
        return users.save(user);
    }

    private Shop saveShop(String name, User owner) {
        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setName(name);
        shop.setSlug("report-shop-" + owner.getId());
        return shops.save(shop);
    }

    private CustomerOrder saveOrder(String code, User customer, Shop shop, OrderStatus status,
                                    PaymentStatus paymentStatus, LocalDateTime date, String subtotal,
                                    String productDiscount, String voucherDiscount, String shippingFee,
                                    String shippingDiscount, String totalAmount, String commissionRate) {
        CustomerOrder order = new CustomerOrder();
        order.setCode(code);
        order.setUser(customer);
        order.setShop(shop);
        order.setStatus(status);
        order.setPaymentStatus(paymentStatus);
        order.setReceiverName("Test");
        order.setReceiverPhone("0900000000");
        order.setShippingAddress("Test address");
        order.setCreatedAt(date);
        if (status == OrderStatus.DELIVERED) order.setDeliveredAt(date);
        order.setSubtotal(new BigDecimal(subtotal));
        order.setProductDiscount(new BigDecimal(productDiscount));
        order.setVoucherDiscount(new BigDecimal(voucherDiscount));
        order.setShippingFee(new BigDecimal(shippingFee));
        order.setFinalShippingFee(new BigDecimal(shippingFee).subtract(new BigDecimal(shippingDiscount)));
        order.setShippingDiscount(new BigDecimal(shippingDiscount));
        order.setTotalAmount(new BigDecimal(totalAmount));
        order.snapshotCommission(new BigDecimal(commissionRate));
        return orders.save(order);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
