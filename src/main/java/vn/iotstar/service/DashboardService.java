package vn.iotstar.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.CustomerOrder;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.entity.DomainEnums.PaymentStatus;
import vn.iotstar.entity.OrderItem;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Shop;
import vn.iotstar.repository.OrderRepository;
import vn.iotstar.repository.ProductRepository;

@Service
@RequiredArgsConstructor
public class DashboardService {
	private static final int LOW_STOCK_THRESHOLD = 10;
	private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("dd/MM");

	private final OrderRepository orderRepository;
	private final ProductRepository productRepository;

	@Transactional(readOnly = true)
	public DashboardData forVendor(Shop shop) {
		return forVendor(shop, range("30", null, null));
	}

	@Transactional(readOnly = true)
	public DashboardData forVendor(Shop shop, DashboardRange range) {
		LocalDate today = LocalDate.now();
		LocalDate fromDate = queryFrom(today, range.from());
		List<CustomerOrder> orders = orderRepository.findDashboardOrdersByShop(shop.getId(), fromDate.atStartOfDay());
		Map<OrderStatus, Long> statuses = statusMap(orderRepository.countByStatusForShop(shop.getId()));
		List<Product> lowStock = productRepository.findLowStockByShop(shop.getId(), LOW_STOCK_THRESHOLD,
				PageRequest.of(0, 8));
		return build(orders, statuses, List.of(shop), lowStock, today, range, true);
	}

	public DashboardRange range(String preset, LocalDate from, LocalDate to) {
		LocalDate today = LocalDate.now();
		String selected = preset == null || preset.isBlank() ? (from != null || to != null ? "custom" : "30") : preset;
		if (selected.equals("7") || selected.equals("30") || selected.equals("90")) {
			return new DashboardRange(today.minusDays(Integer.parseInt(selected) - 1), today, selected);
		}
		if (!selected.equals("custom") || from == null || to == null || from.isAfter(to) || to.isAfter(today)
				|| from.isBefore(today.minusDays(365))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Khoảng ngày dashboard không hợp lệ (tối đa 366 ngày và không vượt quá hôm nay).");
		}
		return new DashboardRange(from, to, "custom");
	}

	private DashboardData build(List<CustomerOrder> orders, Map<OrderStatus, Long> statuses, List<Shop> shops,
			List<Product> lowStock, LocalDate today, DashboardRange range, boolean vendorView) {
		LocalDate chartFrom = today.minusDays(29);
		Map<LocalDate, DailyAmounts> daily = new LinkedHashMap<>();
		for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
			daily.put(day, new DailyAmounts());
		}

		Map<Long, ProductAccumulator> products = new LinkedHashMap<>();
		Map<Long, ShopAccumulator> shopAmounts = new LinkedHashMap<>();
		Map<OrderStatus, Long> periodStatuses = new EnumMap<>(OrderStatus.class);
		shops.forEach(shop -> shopAmounts.put(shop.getId(), new ShopAccumulator(shop.getName())));

		BigDecimal revenue7Days = BigDecimal.ZERO;
		BigDecimal revenue30Days = BigDecimal.ZERO;
		BigDecimal monthRevenue = BigDecimal.ZERO;
		BigDecimal gross30Days = BigDecimal.ZERO;
		BigDecimal commission30Days = BigDecimal.ZERO;
		BigDecimal periodRevenue = BigDecimal.ZERO;
		BigDecimal periodGross = BigDecimal.ZERO;
		BigDecimal periodCommission = BigDecimal.ZERO;
		long periodOrderCount = 0;
		long deliveredPeriodCount = 0;
		long cancelledPeriodCount = 0;
		LocalDate sevenDayFrom = today.minusDays(6);
		LocalDate monthFrom = today.withDayOfMonth(1);

		for (CustomerOrder order : orders) {
			LocalDate date = (order.getDeliveredAt() == null ? order.getCreatedAt() : order.getDeliveredAt())
					.toLocalDate();
			boolean inPeriod = !date.isBefore(range.from()) && !date.isAfter(range.to());
			if (inPeriod) {
				periodOrderCount++;
				periodStatuses.merge(order.getStatus(), 1L, Long::sum);
				if (order.getStatus() == OrderStatus.DELIVERED)
					deliveredPeriodCount++;
				if (order.getStatus() == OrderStatus.CANCELLED)
					cancelledPeriodCount++;
			}
			if (order.getPaymentStatus() != PaymentStatus.PAID || order.getStatus() != OrderStatus.DELIVERED)
				continue;
			BigDecimal gross = zero(order.getTotalAmount());
			BigDecimal seller = zero(order.getSellerRevenue());
			BigDecimal commission = zero(order.getPlatformRevenue());
			BigDecimal primaryRevenue = vendorView ? seller : gross;

			if (!date.isBefore(chartFrom) && !date.isAfter(today)) {
				revenue30Days = revenue30Days.add(primaryRevenue);
				gross30Days = gross30Days.add(gross);
				commission30Days = commission30Days.add(commission);
				if (!date.isBefore(sevenDayFrom))
					revenue7Days = revenue7Days.add(primaryRevenue);
			}
			if (!date.isBefore(monthFrom) && !date.isAfter(today))
				monthRevenue = monthRevenue.add(primaryRevenue);

			if (inPeriod) {
				DailyAmounts amount = daily.get(date);
				amount.gross = amount.gross.add(gross);
				amount.seller = amount.seller.add(seller);
				amount.commission = amount.commission.add(commission);
				periodRevenue = periodRevenue.add(primaryRevenue);
				periodGross = periodGross.add(gross);
				periodCommission = periodCommission.add(commission);
				ShopAccumulator shop = shopAmounts.computeIfAbsent(order.getShop().getId(),
						ignored -> new ShopAccumulator(order.getShop().getName()));
				shop.gross = shop.gross.add(gross);
				shop.seller = shop.seller.add(seller);
				shop.commission = shop.commission.add(commission);

				for (OrderItem item : order.getItems()) {
					Long productId = item.getProduct().getId();
					ProductAccumulator product = products.computeIfAbsent(productId,
							ignored -> new ProductAccumulator(item.getProductName()));
					product.quantity += item.getQuantity();
					product.revenue = product.revenue.add(zero(item.getLineTotal()));
				}
			}
		}

		List<String> dateLabels = daily.keySet().stream().map(DAY_LABEL::format).toList();
		List<BigDecimal> grossSeries = daily.values().stream().map(value -> value.gross).toList();
		List<BigDecimal> sellerSeries = daily.values().stream().map(value -> value.seller).toList();
		List<BigDecimal> commissionSeries = daily.values().stream().map(value -> value.commission).toList();
		List<String> statusLabels = new ArrayList<>();
		List<Long> statusValues = new ArrayList<>();
		for (OrderStatus status : OrderStatus.values()) {
			long count = periodStatuses.getOrDefault(status, 0L);
			if (count > 0) {
				statusLabels.add(status.getLabel());
				statusValues.add(count);
			}
		}
		List<TopProduct> topProducts = products.values().stream()
				.sorted(Comparator.comparingLong((ProductAccumulator value) -> value.quantity).reversed()
						.thenComparing(value -> value.name))
				.limit(7).map(value -> new TopProduct(value.name, value.quantity, value.revenue)).toList();
		List<LowStockProduct> lowStockProducts = lowStock.stream().map(product -> new LowStockProduct(product.getId(),
				product.getName(), product.getShop().getName(), product.getStock())).toList();
		List<ShopRevenue> shopRevenues = shopAmounts.values().stream()
				.sorted(Comparator.comparing((ShopAccumulator value) -> value.gross).reversed()).limit(8)
				.map(value -> new ShopRevenue(value.name, value.gross, value.seller, value.commission)).toList();

		return new DashboardData(dateLabels, grossSeries, sellerSeries, commissionSeries, statusLabels, statusValues,
				topProducts, lowStockProducts, shopRevenues, revenue7Days, revenue30Days, monthRevenue, gross30Days,
				commission30Days, statuses.getOrDefault(OrderStatus.CANCELLED, 0L),
				statuses.getOrDefault(OrderStatus.RETURN_REQUESTED, 0L)
						+ statuses.getOrDefault(OrderStatus.RETURNED_REFUNDED, 0L),
				statuses.getOrDefault(OrderStatus.DELIVERY_FAILED, 0L), periodRevenue, periodGross, periodCommission,
				periodOrderCount, deliveredPeriodCount, cancelledPeriodCount,
				statuses.getOrDefault(OrderStatus.NEW, 0L), pendingCount(statuses),
				periodStatuses.getOrDefault(OrderStatus.DELIVERY_FAILED, 0L),
				periodStatuses.getOrDefault(OrderStatus.RETURN_REQUESTED, 0L)
						+ periodStatuses.getOrDefault(OrderStatus.RETURNED_REFUNDED, 0L));
	}

	private Map<OrderStatus, Long> statusMap(List<Object[]> rows) {
		Map<OrderStatus, Long> result = new EnumMap<>(OrderStatus.class);
		for (Object[] row : rows)
			result.put((OrderStatus) row[0], ((Number) row[1]).longValue());
		return result;
	}

	private LocalDate queryFrom(LocalDate today, LocalDate selectedFrom) {
		LocalDate thirtyDaysAgo = today.minusDays(29);
		LocalDate monthStart = today.withDayOfMonth(1);
		LocalDate legacyStart = monthStart.isBefore(thirtyDaysAgo) ? monthStart : thirtyDaysAgo;
		return selectedFrom.isBefore(legacyStart) ? selectedFrom : legacyStart;
	}

	private long pendingCount(Map<OrderStatus, Long> statuses) {
		return List
				.of(OrderStatus.NEW, OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP,
						OrderStatus.DELIVERY_FAILED, OrderStatus.RETURN_REQUESTED)
				.stream().mapToLong(status -> statuses.getOrDefault(status, 0L)).sum();
	}

	private BigDecimal zero(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private static final class DailyAmounts {
		private BigDecimal gross = BigDecimal.ZERO;
		private BigDecimal seller = BigDecimal.ZERO;
		private BigDecimal commission = BigDecimal.ZERO;
	}

	private static final class ProductAccumulator {
		private final String name;
		private long quantity;
		private BigDecimal revenue = BigDecimal.ZERO;

		private ProductAccumulator(String name) {
			this.name = name;
		}
	}

	private static final class ShopAccumulator {
		private final String name;
		private BigDecimal gross = BigDecimal.ZERO;
		private BigDecimal seller = BigDecimal.ZERO;
		private BigDecimal commission = BigDecimal.ZERO;

		private ShopAccumulator(String name) {
			this.name = name;
		}
	}

	public record TopProduct(String name, long quantity, BigDecimal revenue) {
	}

	public record LowStockProduct(Long id, String name, String shopName, int stock) {
	}

	public record ShopRevenue(String name, BigDecimal grossRevenue, BigDecimal sellerRevenue,
			BigDecimal platformRevenue) {
	}

	public record DashboardRange(LocalDate from, LocalDate to, String preset) {
	}

	public record DashboardData(List<String> dateLabels, List<BigDecimal> grossSeries, List<BigDecimal> sellerSeries,
			List<BigDecimal> commissionSeries, List<String> statusLabels, List<Long> statusValues,
			List<TopProduct> topProducts, List<LowStockProduct> lowStockProducts, List<ShopRevenue> shopRevenues,
			BigDecimal revenue7Days, BigDecimal revenue30Days, BigDecimal monthRevenue, BigDecimal gross30Days,
			BigDecimal commission30Days, long cancelledCount, long returnCount, long failedCount,
			BigDecimal periodRevenue, BigDecimal periodGross, BigDecimal periodCommission, long periodOrderCount,
			long deliveredPeriodCount, long cancelledPeriodCount, long newCount, long pendingCount,
			long failedPeriodCount, long returnPeriodCount) {
	}
}
