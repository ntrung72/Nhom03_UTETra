package vn.iotstar.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.ManagerShopRepository;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ShopRepository;

@Service
@RequiredArgsConstructor
public class RevenueReportService {
	private static final DateTimeFormatter CSV_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

	private final OrderRepository orderRepository;
	private final ShopRepository shopRepository;
	private final ManagerShopRepository managerShopRepository;

	public Filter filter(LocalDate from, LocalDate to, Long shopId, String status) {
		LocalDate today = LocalDate.now();
		LocalDate start = from == null ? today.minusDays(29) : from;
		LocalDate end = to == null ? today : to;
		if (start.isAfter(end))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ngày bắt đầu không được sau ngày kết thúc.");
		if (end.isAfter(today))
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ngày kết thúc không được ở tương lai.");
		OrderStatus selectedStatus;
		if (status == null || status.isBlank())
			selectedStatus = OrderStatus.DELIVERED;
		else if (status.equalsIgnoreCase("ALL"))
			selectedStatus = null;
		else {
			try {
				selectedStatus = OrderStatus.valueOf(status);
			} catch (IllegalArgumentException exception) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trạng thái đơn không hợp lệ.");
			}
		}
		return new Filter(start, end, shopId, selectedStatus);
	}

	@Transactional(readOnly = true)
	public List<Shop> availableShops(User actor) {
		return switch (actor.getRole()) {
		case ADMIN -> shopRepository.findAll(Sort.by("name"));
		case MANAGER -> managerShopRepository.findByManagerIdOrderByShopNameAsc(actor.getId()).stream()
				.map(assignment -> assignment.getShop()).toList();
		case VENDOR -> shopRepository.findByOwnerId(actor.getId()).stream().toList();
		default -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền xem báo cáo doanh thu.");
		};
	}

	@Transactional(readOnly = true)
	public Report report(User actor, Filter filter) {
		List<Shop> allowed = availableShops(actor);
		if (filter.shopId() != null && allowed.stream().noneMatch(shop -> shop.getId().equals(filter.shopId()))) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cửa hàng không thuộc phạm vi quản lý.");
		}
		List<CustomerOrder> orders;
		if (allowed.isEmpty())
			orders = List.of();
		else if (actor.getRole() == Role.ADMIN && filter.shopId() == null) {
			orders = orderRepository.findReportOrders(filter.from().atStartOfDay(),
					filter.to().plusDays(1).atStartOfDay(), filter.status());
		} else {
			List<Long> shopIds = filter.shopId() == null ? allowed.stream().map(Shop::getId).toList()
					: List.of(filter.shopId());
			orders = orderRepository.findReportOrdersByShopIds(shopIds, filter.from().atStartOfDay(),
					filter.to().plusDays(1).atStartOfDay(), filter.status());
		}

		List<Row> rows = new ArrayList<>(orders.size());
		Amounts totals = Amounts.zero();
		for (CustomerOrder order : orders) {
			boolean recognized = order.getStatus() == OrderStatus.DELIVERED
					&& order.getPaymentStatus() == PaymentStatus.PAID;
			Amounts amount = recognized ? new Amounts(1, 1, zero(order.getSubtotal()), zero(order.getProductDiscount()),
					zero(order.getVoucherDiscount()), zero(order.getShippingFee()), zero(order.getShippingDiscount()),
					zero(order.getFinalShippingFee()), zero(order.getTotalAmount()), zero(order.getPlatformRevenue()),
					zero(order.getSellerRevenue()))
					: new Amounts(1, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
							BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
			LocalDateTime date = order.getDeliveredAt() == null ? order.getCreatedAt() : order.getDeliveredAt();
			rows.add(new Row(order.getId(), order.getCode(), order.getShop().getName(), date, order.getStatus(),
					order.getPaymentStatus(), recognized, amount));
			totals = totals.add(amount);
		}
		return new Report(filter, List.copyOf(rows), totals);
	}

	public Page<Row> tableRows(Report report, String query, int page) {
		String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
		List<Row> matching = needle.isEmpty() ? report.rows()
				: report.rows().stream()
						.filter(row -> row.code().toLowerCase(Locale.ROOT).contains(needle)
								|| row.shopName().toLowerCase(Locale.ROOT).contains(needle)
								|| row.status().getLabel().toLowerCase(Locale.ROOT).contains(needle))
						.toList();
		int pageNumber = Math.min(Math.max(0, page), Math.max(0, (matching.size() - 1) / 10));
		PageRequest request = PageRequest.of(pageNumber, 10);
		int start = Math.min(pageNumber * 10, matching.size());
		int end = Math.min(start + 10, matching.size());
		return new PageImpl<>(matching.subList(start, end), request, matching.size());
	}

	public byte[] toCsv(Report report) {
		StringBuilder csv = new StringBuilder("\uFEFF");
		line(csv, "Mã đơn", "Cửa hàng", "Ngày ghi nhận", "Trạng thái đơn", "Thanh toán", "Đã ghi nhận doanh thu",
				"Số đơn", "Tiền hàng", "Giảm sản phẩm", "Giảm voucher", "Phí vận chuyển gốc", "Giảm vận chuyển",
				"Phí vận chuyển thực thu", "Khách thanh toán", "Chiết khấu app", "Shop thực nhận");
		for (Row row : report.rows()) {
			Amounts amount = row.amounts();
			line(csv, row.code(), row.shopName(), CSV_DATE_TIME.format(row.date()), row.status().getLabel(),
					row.paymentStatus().getLabel(), row.recognized() ? "Có" : "Không", count(amount.orderCount()),
					money(amount.subtotal()), money(amount.productDiscount()), money(amount.voucherDiscount()),
					money(amount.shippingFee()), money(amount.shippingDiscount()), money(amount.finalShippingFee()),
					money(amount.grossRevenue()), money(amount.commission()), money(amount.sellerRevenue()));
		}
		Amounts total = report.totals();
		line(csv, "TỔNG", "", "", "", "", "", count(total.orderCount()), money(total.subtotal()),
				money(total.productDiscount()), money(total.voucherDiscount()), money(total.shippingFee()),
				money(total.shippingDiscount()), money(total.finalShippingFee()), money(total.grossRevenue()),
				money(total.commission()), money(total.sellerRevenue()));
		return csv.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static void line(StringBuilder csv, String... cells) {
		for (int i = 0; i < cells.length; i++) {
			if (i > 0)
				csv.append(',');
			String value = cells[i] == null ? "" : cells[i];
			if (!value.isEmpty() && "=+-@\t\r\n".indexOf(value.charAt(0)) >= 0)
				value = "'" + value;
			csv.append('"').append(value.replace("\"", "\"\"")).append('"');
		}
		csv.append("\r\n");
	}

	private static String money(BigDecimal value) {
		return value.toPlainString();
	}

	private static String count(long value) {
		return Long.toString(value);
	}

	private static BigDecimal zero(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	public record Filter(LocalDate from, LocalDate to, Long shopId, OrderStatus status) {
		public String statusValue() {
			return status == null ? "ALL" : status.name();
		}
	}

	public record Amounts(long orderCount, long recognizedCount, BigDecimal subtotal, BigDecimal productDiscount,
			BigDecimal voucherDiscount, BigDecimal shippingFee, BigDecimal shippingDiscount,
			BigDecimal finalShippingFee, BigDecimal grossRevenue, BigDecimal commission, BigDecimal sellerRevenue) {
		static Amounts zero() {
			return new Amounts(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
					BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
		}

		Amounts add(Amounts other) {
			return new Amounts(orderCount + other.orderCount, recognizedCount + other.recognizedCount,
					subtotal.add(other.subtotal), productDiscount.add(other.productDiscount),
					voucherDiscount.add(other.voucherDiscount), shippingFee.add(other.shippingFee),
					shippingDiscount.add(other.shippingDiscount), finalShippingFee.add(other.finalShippingFee),
					grossRevenue.add(other.grossRevenue), commission.add(other.commission),
					sellerRevenue.add(other.sellerRevenue));
		}
	}

	public record Row(Long orderId, String code, String shopName, LocalDateTime date, OrderStatus status,
			PaymentStatus paymentStatus, boolean recognized, Amounts amounts) {
	}

	public record Report(Filter filter, List<Row> rows, Amounts totals) {
	}
}
