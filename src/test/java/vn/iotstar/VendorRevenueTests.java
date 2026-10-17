package vn.iotstar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.config.CommissionSnapshotBackfill;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.OrderItem;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.service.DashboardService;
import vn.iotstar.service.RevenueReportService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VendorRevenueTests extends CheckoutTestFixtures {

	@Autowired
	DashboardService dashboards;
	@Autowired
	RevenueReportService reports;
	@Autowired
	CommissionSnapshotBackfill backfill;

	private LocalDate today;

	@BeforeEach
	void vendorRevenueFixtures() {
		other.setRole(Role.VENDOR);
		other.setEnabled(true);
		other.setLocked(false);
		users.saveAndFlush(other);
		tea.getShop().setCommissionRate(money("10"));
		shops.saveAndFlush(tea.getShop());
		today = LocalDate.now();
	}

	@Test
	void commissionExcludesShippingAndUsesBothMerchandiseDiscounts() {
		var order = saveOrder("SNAPSHOT", tea.getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID,
				today.atTime(10, 0));

		assertMoney("85000", order.getCommissionBase());
		assertMoney("10", order.getCommissionRate());
		assertMoney("8500", order.getCommissionAmount());
		assertMoney("8500", order.getPlatformRevenue());
		assertMoney("76500", order.getSellerRevenue());
		assertMoney("102000", order.getTotalAmount());
		assertMoney("17000", order.getFinalShippingFee());
	}

	@Test
	void commissionRoundsMoneyAndBoundsRateAndDiscountBase() {
		var order = new CustomerOrder();
		order.setSubtotal(money("100.05"));
		order.setProductDiscount(BigDecimal.ZERO);
		order.setVoucherDiscount(BigDecimal.ZERO);
		order.snapshotCommission(money("7.5"));
		assertMoney("7.50", order.getCommissionAmount());
		assertMoney("92.55", order.getSellerRevenue());

		order.snapshotCommission(money("150"));
		assertMoney("100", order.getCommissionRate());
		assertMoney("0", order.getSellerRevenue());
		order.snapshotCommission(money("-1"));
		assertMoney("0", order.getCommissionAmount());

		order.setProductDiscount(money("200"));
		order.snapshotCommission(money("10"));
		assertMoney("0", order.getCommissionBase());
		assertMoney("0", order.getSellerRevenue());
	}

	@Test
	void checkoutCapturesRateAndLaterShopChangeDoesNotRewriteOrderRevenue() {
		var order = orders.checkout(customer, form(add(tea, medium, 2).getId())).getFirst();
		assertMoney("70000", order.getCommissionBase());
		assertMoney("7000", order.getPlatformRevenue());
		assertMoney("63000", order.getSellerRevenue());

		tea.getShop().setCommissionRate(money("25"));
		shops.saveAndFlush(tea.getShop());
		order.setStatus(OrderStatus.DELIVERED);
		order.setPaymentStatus(PaymentStatus.PAID);
		order.setDeliveredAt(today.atTime(10, 0));
		orderRepository.saveAndFlush(order);

		assertMoney("10", order.getCommissionRate());
		assertMoney("63000", orders.revenue(tea.getShop()));
		assertMoney("7000", orders.revenueSummary(tea.getShop()).platformRevenue());
		assertMoney("90000", orders.revenueSummary(tea.getShop()).grossRevenue());
	}

	@Test
	void backfillPreservesSavedRateAndDoesNotRecalculateCompletedSnapshot() {
		var order = saveOrder("LEGACY-RATE", tea.getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID,
				today.atTime(10, 0));
		order.setCommissionRate(money("5"));
		clearSnapshotAmounts(order);
		tea.getShop().setCommissionRate(money("25"));
		shops.saveAndFlush(tea.getShop());
		orderRepository.saveAndFlush(order);

		backfill.run(null);
		assertMoney("5", order.getCommissionRate());
		assertMoney("4250", order.getPlatformRevenue());
		assertMoney("80750", order.getSellerRevenue());

		tea.getShop().setCommissionRate(money("30"));
		shops.saveAndFlush(tea.getShop());
		backfill.run(null);
		assertMoney("4250", order.getPlatformRevenue());
		assertMoney("80750", order.getSellerRevenue());
	}

	@Test
	void backfillUsesShopRateWhenLegacyOrderHasNoSavedRate() {
		var order = saveOrder("LEGACY-NO-RATE", tea.getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID,
				today.atTime(10, 0));
		order.setCommissionRate(null);
		clearSnapshotAmounts(order);
		orderRepository.saveAndFlush(order);

		backfill.run(null);
		assertMoney("10", order.getCommissionRate());
		assertMoney("8500", order.getPlatformRevenue());
		assertMoney("76500", order.getSellerRevenue());
	}

	@Test
	void dashboardRecognizesOnlyPaidDeliveredOrdersAndOnlyThisShop() {
		var paid = saveOrder("DASH-PAID", tea.getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID,
				today.atTime(10, 0));
		addLine(paid, tea, 2, "90000");
		var unpaid = saveOrder("DASH-UNPAID", tea.getShop(), OrderStatus.DELIVERED, PaymentStatus.UNPAID,
				today.atTime(11, 0));
		addLine(unpaid, tea, 50, "999999");
		saveOrder("DASH-NEW", tea.getShop(), OrderStatus.NEW, PaymentStatus.PAID, today.atTime(12, 0));
		saveOrder("DASH-CANCEL", tea.getShop(), OrderStatus.CANCELLED, PaymentStatus.PAID, today.atTime(13, 0));
		saveOrder("DASH-REFUND", tea.getShop(), OrderStatus.RETURNED_REFUNDED, PaymentStatus.REFUNDED,
				today.atTime(14, 0));
		saveOrder("DASH-FOREIGN", secondShopProduct().getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID,
				today.atTime(15, 0));

		var data = dashboards.forVendor(tea.getShop());
		assertMoney("76500", data.periodRevenue());
		assertMoney("102000", data.periodGross());
		assertMoney("8500", data.periodCommission());
		assertMoney("76500", data.sellerSeries().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
		assertThat(data.periodOrderCount()).isEqualTo(5);
		assertThat(data.deliveredPeriodCount()).isEqualTo(2);
		assertThat(data.newCount()).isEqualTo(1);
		assertThat(data.returnPeriodCount()).isEqualTo(1);
		assertThat(data.statusValues().stream().mapToLong(Long::longValue).sum()).isEqualTo(5);
		assertThat(data.topProducts()).hasSize(1);
		assertThat(data.topProducts().getFirst().quantity()).isEqualTo(2);
		assertMoney("90000", data.topProducts().getFirst().revenue());
		assertMoney("76500", orders.revenueSummary(tea.getShop()).sellerRevenue());
	}

	@Test
	void dateSelectionUsesDeliveryDateAndIncludesBothSelectedDays() {
		var range = dashboards.range("7", null, null);
		var atStart = saveOrder("DATE-START", tea.getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID,
				range.from().atStartOfDay());
		atStart.setCreatedAt(today.minusDays(60).atTime(9, 0));
		orderRepository.saveAndFlush(atStart);
		saveOrder("DATE-END", tea.getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID, today.atTime(23, 59, 59));
		saveOrder("DATE-BEFORE", tea.getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID,
				range.from().atStartOfDay().minusNanos(1_000_000_000));
		saveOrder("DATE-AFTER", tea.getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID,
				today.plusDays(1).atStartOfDay());

		var data = dashboards.forVendor(tea.getShop(), range);
		assertThat(data.dateLabels()).hasSize(7);
		assertMoney("153000", data.periodRevenue());
		var report = reports.report(other, reports.filter(range.from(), today, null, null));
		assertThat(report.rows()).extracting(RevenueReportService.Row::code).containsExactlyInAnyOrder("DATE-START",
				"DATE-END");
		assertMoney("153000", report.totals().sellerRevenue());
	}

	@Test
	void dashboardPresetsAndCustomRangeHaveInclusiveDates() {
		for (int days : List.of(7, 30, 90)) {
			var range = dashboards.range(Integer.toString(days), null, null);
			assertThat(range.from()).isEqualTo(today.minusDays(days - 1));
			assertThat(range.to()).isEqualTo(today);
			assertThat(dashboards.forVendor(tea.getShop(), range).dateLabels()).hasSize(days);
		}
		var custom = dashboards.range("custom", today.minusDays(9), today.minusDays(5));
		assertThat(dashboards.forVendor(tea.getShop(), custom).dateLabels()).hasSize(5);
		assertThat(dashboards.range(null, null, null).preset()).isEqualTo("30");
	}

	@Test
	void invalidRangesAndReportStatusesAreRejected() {
		assertBadRequest(() -> dashboards.range("custom", today, today.minusDays(1)));
		assertBadRequest(() -> dashboards.range("custom", today, today.plusDays(1)));
		assertBadRequest(() -> dashboards.range("custom", today.minusDays(366), today));
		assertBadRequest(() -> dashboards.range("custom", null, today));
		assertBadRequest(() -> reports.filter(today, today.minusDays(1), null, null));
		assertBadRequest(() -> reports.filter(today, today.plusDays(1), null, null));
		assertBadRequest(() -> reports.filter(null, null, null, "INVALID"));
	}

	@Test
	void lowStockIsSortedLimitedAndDoesNotIncludeAnotherShop() {
		tea.setStock(50);
		products.saveAndFlush(tea);
		for (int stock = 10; stock >= 1; stock--) {
			var product = new Product();
			product.setShop(tea.getShop());
			product.setCategory(tea.getCategory());
			product.setName("Low stock " + stock);
			product.setDescription("Sản phẩm kiểm thử cảnh báo tồn kho");
			product.setSlug("low-" + UUID.randomUUID());
			product.setSku(UUID.randomUUID().toString());
			product.setPrice(money("10000"));
			product.setStock(stock);
			products.saveAndFlush(product);
		}
		var foreign = secondShopProduct();
		foreign.setStock(0);
		products.saveAndFlush(foreign);

		var lowStock = dashboards.forVendor(tea.getShop()).lowStockProducts();
		assertThat(lowStock).hasSize(8);
		assertThat(lowStock).extracting(DashboardService.LowStockProduct::stock).containsExactly(1, 2, 3, 4, 5, 6, 7,
				8);
		assertThat(lowStock).extracting(DashboardService.LowStockProduct::id).doesNotContain(foreign.getId());
		assertThat(products.countByShopId(tea.getShop().getId())).isEqualTo(11);
	}

	@Test
	void reportTotalsExcludeUnrecognizedRevenueAndExposeDiscountBreakdown() {
		saveOrder("REPORT-PAID", tea.getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID, today.atTime(10, 0));
		saveOrder("REPORT-UNPAID", tea.getShop(), OrderStatus.DELIVERED, PaymentStatus.UNPAID, today.atTime(11, 0));
		saveOrder("REPORT-NEW", tea.getShop(), OrderStatus.NEW, PaymentStatus.PAID, today.atTime(12, 0));
		saveOrder("REPORT-FOREIGN", secondShopProduct().getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID,
				today.atTime(13, 0));

		var delivered = reports.report(other, reports.filter(null, null, null, null));
		assertThat(delivered.rows()).hasSize(2);
		assertThat(delivered.totals().recognizedCount()).isEqualTo(1);
		var all = reports.report(other, reports.filter(null, null, null, "ALL"));
		assertThat(all.totals().orderCount()).isEqualTo(3);
		assertMoney("100000", all.totals().subtotal());
		assertMoney("10000", all.totals().productDiscount());
		assertMoney("5000", all.totals().voucherDiscount());
		assertMoney("20000", all.totals().shippingFee());
		assertMoney("3000", all.totals().shippingDiscount());
		assertMoney("17000", all.totals().finalShippingFee());
		assertMoney("102000", all.totals().grossRevenue());
		assertMoney("8500", all.totals().commission());
		assertMoney("76500", all.totals().sellerRevenue());
		var newOnly = reports.report(other, reports.filter(null, null, null, "NEW"));
		assertMoney("0", newOnly.totals().grossRevenue());
		assertThat(newOnly.totals().recognizedCount()).isZero();
	}

	@Test
	void reportDeniesOtherShopAndCustomerRole() throws Exception {
		var foreign = secondShopProduct().getShop();
		var forbidden = assertThrows(ResponseStatusException.class,
				() -> reports.report(other, reports.filter(null, null, foreign.getId(), null)));
		assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		var customerDenied = assertThrows(ResponseStatusException.class,
				() -> reports.report(customer, reports.filter(null, null, null, null)));
		assertThat(customerDenied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		mvc.perform(get("/vendor").param("shopId", foreign.getId().toString())
				.with(user(other.getUsername()).roles("VENDOR"))).andExpect(status().isForbidden());
		mvc.perform(get("/vendor/reports/revenue.csv").param("shopId", foreign.getId().toString())
				.with(user(other.getUsername()).roles("VENDOR"))).andExpect(status().isForbidden());
	}

	@Test
	void reportSearchAndPaginationRemainInsideVendorShop() {
		for (int i = 0; i < 12; i++) {
			saveOrder(String.format("PAGE-%02d", i), tea.getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID,
					today.atTime(9, i));
		}
		saveOrder("PAGE-FOREIGN", secondShopProduct().getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID,
				today.atTime(10, 0));

		var report = reports.report(other, reports.filter(null, null, null, null));
		assertThat(reports.tableRows(report, null, 0).getContent()).hasSize(10);
		assertThat(reports.tableRows(report, null, 1).getContent()).hasSize(2);
		assertThat(reports.tableRows(report, null, 999).getNumber()).isEqualTo(1);
		assertThat(reports.tableRows(report, null, -1).getNumber()).isZero();
		assertThat(reports.tableRows(report, "page-11", 0).getContent()).extracting(RevenueReportService.Row::code)
				.containsExactly("PAGE-11");
		assertThat(reports.tableRows(report, "PAGE-FOREIGN", 0).getTotalElements()).isZero();
	}

	@Test
	void csvUsesUtf8EscapingAndOnlyAuthorizedOrders() throws Exception {
		tea.getShop().setName("=HYPERLINK(\"url\",\"shop\")");
		shops.saveAndFlush(tea.getShop());
		saveOrder("CSV-OWN", tea.getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID, today.atTime(10, 0));
		saveOrder("CSV-FOREIGN", secondShopProduct().getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID,
				today.atTime(11, 0));

		var response = mvc.perform(get("/vendor/reports/revenue.csv").with(user(other.getUsername()).roles("VENDOR")))
				.andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
				.andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
		assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=\"utetra-doanh-thu-");
		String csv = response.getContentAsString(StandardCharsets.UTF_8);
		assertThat(csv).startsWith("\uFEFF").contains("Mã đơn", "TỔNG", "CSV-OWN").doesNotContain("CSV-FOREIGN");
		assertThat(csv).contains("\"'=HYPERLINK(\"\"url\"\",\"\"shop\"\")\"");
	}

	@Test
	void dashboardRendersFragmentsChartsPaginationAndOrderDetailLinks() throws Exception {
		CustomerOrder first = null;
		for (int i = 0; i < 12; i++) {
			var order = saveOrder(String.format("WEB-%02d", i), tea.getShop(), OrderStatus.DELIVERED,
					PaymentStatus.PAID, today.atTime(9, i));
			if (i == 0)
				first = order;
		}
		saveOrder("WEB-FOREIGN", secondShopProduct().getShop(), OrderStatus.DELIVERED, PaymentStatus.PAID,
				today.atTime(10, 0));

		String firstPage = mvc
				.perform(get("/vendor").param("range", "7").with(user(other.getUsername()).roles("VENDOR")))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(firstPage).contains("vendorRevenueChart", "vendorStatusChart", "Xuất CSV", "Trang 1/2")
				.doesNotContain("WEB-FOREIGN");
		String secondPage = mvc
				.perform(get("/vendor").param("range", "7").param("page", "1")
						.with(user(other.getUsername()).roles("VENDOR")))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(secondPage).contains("Trang 2/2");
		String searched = mvc
				.perform(get("/vendor/").param("q", "WEB-00").with(user(other.getUsername()).roles("VENDOR")))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(searched).contains("WEB-00", "orderId=" + first.getId()).doesNotContain("WEB-01");
	}

	private CustomerOrder saveOrder(String code, Shop shop, OrderStatus status, PaymentStatus paymentStatus,
			LocalDateTime date) {
		var order = new CustomerOrder();
		order.setCode(code);
		order.setUser(customer);
		order.setShop(shop);
		order.setCarrier(carrier);
		order.setReceiverName("Người nhận kiểm thử");
		order.setReceiverPhone("0901234567");
		order.setShippingAddress("1 Võ Văn Ngân, TP. Hồ Chí Minh");
		order.setStatus(status);
		order.setPaymentStatus(paymentStatus);
		order.setCreatedAt(date);
		if (status == OrderStatus.DELIVERED)
			order.setDeliveredAt(date);
		order.setSubtotal(money("100000"));
		order.setProductDiscount(money("10000"));
		order.setVoucherDiscount(money("5000"));
		order.setDiscountAmount(money("15000"));
		order.setShippingFee(money("20000"));
		order.setShippingDiscount(money("3000"));
		order.setFinalShippingFee(money("17000"));
		order.setTotalAmount(money("102000"));
		order.snapshotCommission(money("10"));
		return orderRepository.saveAndFlush(order);
	}

	private void addLine(CustomerOrder order, Product product, int quantity, String total) {
		var item = new OrderItem();
		item.setOrder(order);
		item.setProduct(product);
		item.setProductName(product.getName());
		item.setQuantity(quantity);
		item.setUnitPrice(money(total).divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP));
		item.setLineTotal(money(total));
		order.getItems().add(item);
		orderRepository.saveAndFlush(order);
	}

	private void clearSnapshotAmounts(CustomerOrder order) {
		order.setCommissionBase(null);
		order.setCommissionAmount(null);
		order.setPlatformRevenue(null);
		order.setSellerRevenue(null);
	}

	private void assertBadRequest(Runnable action) {
		var exception = assertThrows(ResponseStatusException.class, action::run);
		assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	private static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	private void assertMoney(String expected, BigDecimal actual) {
		assertThat(actual).isEqualByComparingTo(money(expected));
	}
}
