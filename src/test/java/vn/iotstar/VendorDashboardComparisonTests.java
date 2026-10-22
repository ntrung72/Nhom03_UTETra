package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.ReturnReason;
import vn.iotstar.entity.DomainEnums.ReturnStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.OrderItem;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.ReturnRequest;
import vn.iotstar.entity.Shop;
import vn.iotstar.repository.ReturnRequestRepository;
import vn.iotstar.service.DashboardService;
import vn.iotstar.service.DashboardService.DashboardData;
import vn.iotstar.service.DashboardService.DashboardRange;
import vn.iotstar.service.MediaStorageService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VendorDashboardComparisonTests extends CheckoutTestFixtures {

    @Autowired DashboardService dashboards;
    @Autowired ReturnRequestRepository returnRequests;

    @MockitoBean
    MediaStorageService mediaStorage;

    @PersistenceContext
    EntityManager entityManager;

    private LocalDate today;
    private Shop shop;

    @BeforeEach
    void dashboardFixtures() {
        today = LocalDate.now();

        other.setRole(Role.VENDOR);
        other.setEnabled(true);
        other.setLocked(false);
        users.saveAndFlush(other);

        shop = tea.getShop();
        shop.setCommissionRate(money("10"));
        shops.saveAndFlush(shop);

        tea.setStock(50);
        products.saveAndFlush(tea);
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 30, 90})
    void presetsCompareWithImmediatelyPrecedingEqualLengthPeriod(int days) {
        var range = dashboards.range(Integer.toString(days), null, null);
        var previous = range.previous();

        assertThat(previous.from())
            .isEqualTo(today.minusDays(days * 2L - 1));
        assertThat(previous.to())
            .isEqualTo(range.from().minusDays(1));

        completed(shop, tea, previous.from().atStartOfDay(), "100000");
        completed(shop, tea, range.to().atTime(12, 0), "150000");
        reload();

        var data = dashboards.forVendor(shop, range);

        assertThat(data.dateLabels()).hasSize(days);
        assertMoney("90000", data.revenueComparison().previous());
        assertMoney("135000", data.revenueComparison().current());
        assertMoney("50.0", data.revenueComparison().changePercent());
        assertThat(data.revenueComparison().trend()).isEqualTo("up");
    }

    @Test
    void historicalCustomRangeIncludesBothBoundariesOfBothPeriods() {
        var range = dashboards.range(
            "custom", today.minusDays(60), today.minusDays(54)
        );
        var previous = range.previous();

        completed(
            shop, tea,
            previous.from().atStartOfDay().minusSeconds(1),
            "900000"
        );
        completed(shop, tea, previous.from().atStartOfDay(), "100000");
        completed(shop, tea, previous.to().atTime(23, 59, 59), "200000");

        var atStart = completed(
            shop, tea, range.from().atStartOfDay(), "300000"
        );
        atStart.setCreatedAt(today.minusDays(200).atTime(8, 0));
        orderRepository.saveAndFlush(atStart);

        completed(shop, tea, range.to().atTime(23, 59, 59), "400000");
        completed(
            shop, tea,
            range.to().plusDays(1).atStartOfDay(),
            "900000"
        );
        reload();

        var data = dashboards.forVendor(shop, range);

        assertThat(data.dateLabels()).hasSize(7);
        assertThat(data.periodOrderCount()).isEqualTo(2);
        assertMoney("270000", data.revenueComparison().previous());
        assertMoney("630000", data.periodRevenue());
        assertMoney("630000", data.revenueComparison().current());
        assertMoney("133.3", data.revenueComparison().changePercent());
        assertMoney("740000", data.periodGross());
        assertMoney("70000", data.periodCommission());
        assertMoney(
            "630000",
            data.sellerSeries().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
        );
        assertMoney("0", data.revenue30Days());
    }

    @Test
    void comparisonExcludesUnpaidCancelledReturnedAndForeignOrdersInBothPeriods() {
        var range = dashboards.range("7", null, null);
        var foreign = secondShopProduct();

        List<LocalDateTime> dates = List.of(
            range.from().atTime(12, 0),
            range.previous().from().atTime(12, 0)
        );

        completed(shop, tea, dates.getFirst(), "100000");
        completed(shop, tea, dates.getLast(), "200000");

        for (var date : dates) {
            order(
                shop, tea, OrderStatus.DELIVERED,
                PaymentStatus.UNPAID, date, "900000"
            );
            order(
                shop, tea, OrderStatus.NEW,
                PaymentStatus.PAID, date, "900000"
            );
            order(
                shop, tea, OrderStatus.CANCELLED,
                PaymentStatus.PAID, date, "900000"
            );
            order(
                shop, tea, OrderStatus.RETURN_REQUESTED,
                PaymentStatus.PAID, date, "900000"
            );
            order(
                shop, tea, OrderStatus.RETURNED_REFUNDED,
                PaymentStatus.REFUNDED, date, "900000"
            );
            completed(foreign.getShop(), foreign, date, "9000000");
        }

        reload();

        var data = dashboards.forVendor(shop, range);

        assertMoney("90000", data.periodRevenue());
        assertMoney("180000", data.revenueComparison().previous());
        assertMoney("-50.0", data.revenueComparison().changePercent());
        assertMoney("120000", data.periodGross());
        assertMoney("10000", data.periodCommission());
        assertThat(data.revenueComparison().trend()).isEqualTo("down");
        assertThat(data.topProducts()).hasSize(1);
        assertThat(data.topProducts().getFirst().quantity()).isEqualTo(1);
        assertThat(data.shopRevenues()).hasSize(1);
        assertThat(data.shopRevenues().getFirst().name())
            .isEqualTo(shop.getName());
    }

    @Test
    void zeroPreviousRevenueDoesNotProduceArtificialPercentage() {
        var range = dashboards.range("7", null, null);
        completed(shop, tea, today.atTime(12, 0), "100000");
        reload();

        var comparison = dashboards.forVendor(shop, range)
            .revenueComparison();

        assertMoney("0", comparison.previous());
        assertMoney("90000", comparison.current());
        assertThat(comparison.changePercent()).isNull();
        assertThat(comparison.trend()).isEqualTo("up");
    }

    @Test
    void emptyShopReturnsZeroSeriesAndFlatComparison() {
        var data = dashboards.forVendor(
            shop, dashboards.range("7", null, null)
        );

        assertThat(data.dateLabels()).hasSize(7);
        assertThat(data.sellerSeries())
            .allSatisfy(value -> assertMoney("0", value));
        assertMoney("0", data.revenueComparison().previous());
        assertMoney("0", data.revenueComparison().current());
        assertThat(data.revenueComparison().changePercent()).isNull();
        assertThat(data.revenueComparison().trend()).isEqualTo("flat");
        assertThat(data.newCount()).isZero();
        assertThat(data.pendingCount()).isZero();
        assertThat(data.pendingReturnCount()).isZero();
        assertThat(data.lowStockCount()).isZero();
    }

    @Test
    void noCurrentRevenueProducesMinusOneHundredPercent() {
        var range = dashboards.range("7", null, null);
        completed(
            shop, tea,
            range.previous().to().atTime(12, 0),
            "100000"
        );
        reload();

        var comparison = dashboards.forVendor(shop, range)
            .revenueComparison();

        assertMoney("90000", comparison.previous());
        assertMoney("0", comparison.current());
        assertMoney("-100.0", comparison.changePercent());
        assertThat(comparison.trend()).isEqualTo("down");
    }

    @Test
    void currentAndPreviousRevenueUseSavedCommissionSnapshots() {
        var range = dashboards.range("7", null, null);

        var previous = completed(
            shop, tea,
            range.previous().to().atTime(12, 0),
            "100000"
        );
        var current = completed(
            shop, tea, range.from().atTime(12, 0), "100000"
        );

        shop.setCommissionRate(money("25"));
        shops.saveAndFlush(shop);

        completed(shop, tea, range.to().atTime(12, 0), "100000");
        reload();

        var data = dashboards.forVendor(shop, range);

        assertMoney("90000", data.revenueComparison().previous());
        assertMoney("165000", data.periodRevenue());
        assertMoney("35000", data.periodCommission());
        assertMoney("240000", data.periodGross());
        assertMoney(
            "10",
            orderRepository.findById(previous.getId())
                .orElseThrow().getCommissionRate()
        );
        assertMoney(
            "10",
            orderRepository.findById(current.getId())
                .orElseThrow().getCommissionRate()
        );
    }

    @Test
    void legacyCompletedOrderWithoutDeliveryTimeUsesCreationDate() {
        var range = dashboards.range("7", null, null);

        var previous = completed(
            shop, tea,
            range.previous().from().atTime(12, 0),
            "100000"
        );
        var current = completed(
            shop, tea, range.from().atTime(12, 0), "200000"
        );

        previous.setDeliveredAt(null);
        current.setDeliveredAt(null);
        orderRepository.saveAndFlush(previous);
        orderRepository.saveAndFlush(current);
        reload();

        var comparison = dashboards.forVendor(shop, range)
            .revenueComparison();

        assertMoney("90000", comparison.previous());
        assertMoney("180000", comparison.current());
        assertMoney("100.0", comparison.changePercent());
    }

    @Test
    void lowStockTotalIncludesAllMatchesButPreviewStaysLimitedToEight() {
        for (int stock = 0; stock <= 10; stock++) {
            lowStockProduct(stock);
        }
        lowStockProduct(11);

        var foreign = secondShopProduct();
        foreign.setStock(0);
        products.saveAndFlush(foreign);
        reload();

        var data = dashboards.forVendor(shop);

        assertThat(data.lowStockCount()).isEqualTo(11);
        assertThat(data.lowStockProducts()).hasSize(8);
        assertThat(data.lowStockProducts())
            .extracting(DashboardService.LowStockProduct::stock)
            .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(data.lowStockProducts())
            .extracting(DashboardService.LowStockProduct::id)
            .doesNotContain(foreign.getId());
    }

    @Test
    void liveQueuesIncludeOldOpenRequestsAndExcludeClosedOrForeignRequests() {
        var oldDate = today.minusDays(100).atTime(12, 0);
        var requests = new ArrayList<ReturnRequest>();

        for (var state : List.of(
                ReturnStatus.REQUESTED,
                ReturnStatus.APPROVED,
                ReturnStatus.RETURNING,
                ReturnStatus.RECEIVED,
                ReturnStatus.REFUND_PENDING)) {
            requests.add(returnRequest(shop, tea, state, oldDate));
        }

        returnRequest(shop, tea, ReturnStatus.REJECTED, oldDate);
        returnRequest(shop, tea, ReturnStatus.REFUNDED, oldDate);

        for (var state : List.of(
                OrderStatus.NEW,
                OrderStatus.CONFIRMED,
                OrderStatus.PREPARING,
                OrderStatus.READY_FOR_PICKUP,
                OrderStatus.DELIVERY_FAILED)) {
            order(
                shop, tea, state,
                PaymentStatus.PAID, oldDate, "100000"
            );
        }

        var foreign = secondShopProduct();
        returnRequest(
            foreign.getShop(), foreign,
            ReturnStatus.REQUESTED, oldDate
        );
        order(
            foreign.getShop(), foreign, OrderStatus.NEW,
            PaymentStatus.PAID, oldDate, "100000"
        );
        reload();

        var range = dashboards.range("7", null, null);
        var data = dashboards.forVendor(shop, range);

        assertThat(data.periodOrderCount()).isZero();
        assertThat(data.newCount()).isEqualTo(1);
        assertThat(data.failedCount()).isEqualTo(1);
        assertThat(data.pendingCount()).isEqualTo(10);
        assertThat(data.pendingReturnCount()).isEqualTo(5);

        var rejected = returnRequests.findById(
            requests.getFirst().getId()
        ).orElseThrow();

        rejected.setStatus(ReturnStatus.REJECTED);
        rejected.getOrder().setStatus(OrderStatus.DELIVERED);

        var refunded = returnRequests.findById(
            requests.getLast().getId()
        ).orElseThrow();

        refunded.setStatus(ReturnStatus.REFUNDED);
        refunded.getOrder().setStatus(OrderStatus.RETURNED_REFUNDED);
        refunded.getOrder().setPaymentStatus(PaymentStatus.REFUNDED);
        reload();

        assertThat(dashboards.forVendor(shop, range).pendingReturnCount())
            .isEqualTo(3);
    }

    @Test
    void vendorPageRendersComparisonDatesAndOperationalLinksWithoutForeignData()
            throws Exception {

        var range = dashboards.range("7", null, null);
        completed(
            shop, tea,
            range.previous().to().atTime(12, 0),
            "100000"
        );
        completed(shop, tea, today.atTime(12, 0), "150000");

        var foreign = secondShopProduct();
        foreign.setName("FOREIGN-PRODUCT-DASHBOARD");
        products.saveAndFlush(foreign);

        var foreignOrder = completed(
            foreign.getShop(), foreign,
            today.atTime(12, 0), "9000000"
        );
        reload();

        var result = mvc.perform(
                get("/vendor")
                    .with(user(other.getUsername()).roles("VENDOR"))
                    .param("range", "custom")
                    .param("from", range.from().toString())
                    .param("to", range.to().toString()))
            .andExpect(status().isOk())
            .andExpect(view().name("vendor/dashboard"))
            .andExpect(model().attributeExists(
                "dashboard", "dashboardRange", "report"
            ))
            .andReturn();

        String html = result.getResponse().getContentAsString();

        var data = (DashboardData) result.getModelAndView()
            .getModel().get("dashboard");

        assertMoney("90000", data.revenueComparison().previous());
        assertMoney("135000", data.revenueComparison().current());

        assertThat(html).contains(
            "So sánh doanh thu shop thực nhận",
            "href=\"/vendor/returns\"",
            "href=\"/vendor/inventory\"",
            "vendorRevenueChart",
            "vendorStatusChart"
        );

        var dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        assertThat(html).contains(
            dateFormat.format(range.previous().from()),
            dateFormat.format(range.previous().to())
        );

        assertThat(html).doesNotContain(
            "FOREIGN-PRODUCT-DASHBOARD",
            foreignOrder.getCode()
        );
    }

    @Test
    void vendorPageExplainsZeroBaseInsteadOfRenderingInvalidPercentage()
            throws Exception {

        completed(shop, tea, today.atTime(12, 0), "100000");
        reload();

        String html = mvc.perform(
                get("/vendor")
                    .with(user(other.getUsername()).roles("VENDOR"))
                    .param("range", "7"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(
            "Kỳ trước bằng 0, chưa có cơ sở tính tỷ lệ phần trăm."
        );
        assertThat(html).doesNotContain("NaN", "Infinity");
    }

    @Test
    void vendorCannotSelectAnotherShopThroughDashboardRequest()
            throws Exception {

        var foreign = secondShopProduct();

        mvc.perform(
                get("/vendor")
                    .with(user(other.getUsername()).roles("VENDOR"))
                    .param(
                        "shopId",
                        foreign.getShop().getId().toString()
                    ))
            .andExpect(status().isForbidden());
    }

    @Test
    void managementRetainsGrossRevenueContract() {
        completed(shop, tea, today.atTime(12, 0), "100000");

        var admin = createUser();
        admin.setRole(Role.ADMIN);
        users.saveAndFlush(admin);
        reload();

        var range = dashboards.range("7", null, null);
        var vendor = dashboards.forVendor(shop, range);
        var management = dashboards.forManagement(
            admin, shop.getId(), range
        );

        assertMoney("90000", vendor.periodRevenue());
        assertMoney("120000", management.periodRevenue());
        assertMoney("120000", management.periodGross());
        assertMoney("10000", management.periodCommission());
    }

    @Test
    void directlySuppliedInvalidRangesAreRejected() {
        assertBadRequest(() -> dashboards.forVendor(
            shop,
            new DashboardRange(
                today, today.minusDays(1), "custom"
            )
        ));

        assertBadRequest(() -> dashboards.forVendor(
            shop,
            new DashboardRange(
                today, today.plusDays(1), "custom"
            )
        ));

        assertBadRequest(() -> dashboards.forVendor(
            shop,
            new DashboardRange(
                today.minusDays(366), today, "custom"
            )
        ));
    }

    private CustomerOrder completed(
            Shop target,
            Product product,
            LocalDateTime date,
            String subtotal) {

        return order(
            target, product, OrderStatus.DELIVERED,
            PaymentStatus.PAID, date, subtotal
        );
    }

    private CustomerOrder order(
            Shop target,
            Product product,
            OrderStatus state,
            PaymentStatus payment,
            LocalDateTime date,
            String subtotal) {

        var order = new CustomerOrder();
        order.setCode(UUID.randomUUID().toString().replace("-", ""));
        order.setUser(customer);
        order.setShop(target);
        order.setCarrier(carrier);
        order.setReceiverName("Khách kiểm thử dashboard");
        order.setReceiverPhone("0901234567");
        order.setShippingAddress("1 Võ Văn Ngân, TP. Hồ Chí Minh");
        order.setStatus(state);
        order.setPaymentStatus(payment);
        order.setCreatedAt(date);

        if (state == OrderStatus.DELIVERED) {
            order.setDeliveredAt(date);
        }

        order.setSubtotal(money(subtotal));
        order.setProductDiscount(BigDecimal.ZERO);
        order.setVoucherDiscount(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setShippingFee(money("20000"));
        order.setShippingDiscount(BigDecimal.ZERO);
        order.setFinalShippingFee(money("20000"));
        order.setTotalAmount(
            money(subtotal).add(money("20000"))
        );
        order.snapshotCommission(target.getCommissionRate());

        var item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setProductName(product.getName());
        item.setQuantity(1);
        item.setUnitPrice(money(subtotal));
        item.setLineTotal(money(subtotal));
        order.getItems().add(item);

        return orderRepository.saveAndFlush(order);
    }

    private ReturnRequest returnRequest(
            Shop target,
            Product product,
            ReturnStatus state,
            LocalDateTime date) {

        OrderStatus orderState = state == ReturnStatus.REJECTED
            ? OrderStatus.DELIVERED
            : state == ReturnStatus.REFUNDED
                ? OrderStatus.RETURNED_REFUNDED
                : OrderStatus.RETURN_REQUESTED;

        PaymentStatus payment = state == ReturnStatus.REFUNDED
            ? PaymentStatus.REFUNDED
            : PaymentStatus.PAID;

        var order = order(
            target, product, orderState,
            payment, date, "100000"
        );

        var request = new ReturnRequest();
        request.setOrder(order);
        request.setUser(customer);
        request.setShop(target);
        request.setReason(ReturnReason.DAMAGED);
        request.setDescription(
            "Yêu cầu trả hàng dùng kiểm tra số liệu dashboard."
        );
        request.setRefundAmount(order.getTotalAmount());
        request.setStatus(state);
        request.setRequestedAt(date);

        return returnRequests.saveAndFlush(request);
    }

    private void lowStockProduct(int stock) {
        var product = new Product();
        product.setShop(shop);
        product.setCategory(tea.getCategory());
        product.setName("Sản phẩm tồn kho " + stock);
        product.setSlug("dashboard-stock-" + UUID.randomUUID());
        product.setSku(UUID.randomUUID().toString());
        product.setDescription(
            "Sản phẩm kiểm thử tổng số hàng sắp hết."
        );
        product.setPrice(money("10000"));
        product.setStock(stock);
        products.saveAndFlush(product);
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertThat(actual).isEqualByComparingTo(expected);
    }

    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    private void assertBadRequest(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST)
        );
    }
}