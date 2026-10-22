package vn.iotstar.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {
	private final vn.iotstar.config.VnPayProperties vnPayProperties;
	private final vn.iotstar.config.MomoProperties momoProperties;

	@jakarta.persistence.PersistenceContext
	private jakarta.persistence.EntityManager entityManager;

	private final OrderRepository orderRepository;
	private final UserRepository userRepository;
	private final OrderStatusHistoryRepository historyRepository;
	private final AddressRepository addressRepository;
	private final CarrierRepository carrierRepository;
	private final VoucherRepository voucherRepository;
	private final VoucherUsageRepository usageRepository;
	private final PromotionRepository promotionRepository;
	private final CartService cartService;
	private final jakarta.validation.Validator validator;
	private final InventoryService inventoryService;
	private final ShopRepository shopRepository;
	private final ManagerShopRepository managerShopRepository;
	private final MediaStorageService mediaStorageService;
	private final MediaValidationService mediaValidationService;
	private final NotificationService notificationService;
	private final AuditLogService auditLogService;
	private final LoyaltyPointService loyaltyPointService;

	public List<Carrier> activeCarriers() {
		return carrierRepository.findByActiveTrueOrderByShippingFeeAsc();
	}

	public List<Voucher> availableVouchers(User user) {
		return voucherRepository.findAvailable(LocalDateTime.now()).stream()
				.filter(v -> usageRepository.countByUserIdAndVoucherIdAndStatus(user.getId(), v.getId(),
						VoucherUsageStatus.USED) < v.getEffectiveMaxUsesPerUser())
				.toList();
	}

	@Transactional
	public CheckoutPreview preview(User user, Long carrierId, String voucherCode, List<Long> itemIds) {

		var selection = cartService.prepareCheckoutSelection(user, itemIds);

		var carrier = carrier(carrierId);

		var voucher = voucher(user, voucherCode, selection.subtotal(), false);

		return calculate(selection.items(), carrier, voucher).preview();
	}

    @Transactional
    public List<VoucherRecommendation> recommendVouchers(User user, Long carrierId, List<Long> itemIds) {
        var selection = cartService.prepareCheckoutSelection(user, itemIds);
        var carrier = carrier(carrierId);
        var baseline = calculate(selection.items(), carrier, null).preview().totalAmount();
        var result = new ArrayList<VoucherRecommendation>();
        for (var candidate : availableVouchers(user)) {
            try {
                var eligible = voucher(user, candidate.getCode(), selection.subtotal(), false);
                var amount = calculate(selection.items(), carrier, eligible).preview().totalAmount();
                result.add(new VoucherRecommendation(candidate.getCode(), candidate.getName(),
                    baseline.subtract(amount).max(BigDecimal.ZERO), amount, true, null));
            } catch (IllegalArgumentException ex) {
                result.add(new VoucherRecommendation(candidate.getCode(), candidate.getName(), BigDecimal.ZERO,
                    baseline, false, ex.getMessage()));
            }
        }
        result.sort(Comparator.comparing(VoucherRecommendation::eligible).reversed()
            .thenComparing(VoucherRecommendation::saving, Comparator.reverseOrder())
            .thenComparing(VoucherRecommendation::code));
        return List.copyOf(result);
    }
    public record VoucherRecommendation(String code, String name, BigDecimal saving,
                                        BigDecimal totalAmount, boolean eligible, String reason) {}

	@Transactional(noRollbackFor = PriceChangedException.class)
	public List<CustomerOrder> checkout(User user, CheckoutForm form) {

		if (form == null || !validator.validate(form).isEmpty() || form.getPaymentMethod() == null) {
			throw new IllegalArgumentException("Vui lòng kiểm tra sản phẩm, địa chỉ, " + "nhà vận chuyển và ghi chú.");
		}

		// Khóa và refresh sản phẩm theo thứ tự ID,
		// kiểm tra tổng số lượng các dòng cùng sản phẩm.
		var selection = cartService.prepareCheckoutSelection(user, form.getItemIds());

		if (selection.pricesChanged()) {
			throw new PriceChangedException();
		}

		var owner = selection.cart().getUser();

		var address = addressRepository.findByIdAndUserId(form.getAddressId(), owner.getId())
				.orElseThrow(() -> new IllegalArgumentException("Địa chỉ nhận hàng không hợp lệ."));

		var carrier = carrier(form.getCarrierId());

		var voucher = voucher(owner, form.getVoucherCode(), selection.subtotal(), true);

		var calculation = calculate(selection.items(), carrier, voucher);

		var reference = "CHK-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);

		var created = new ArrayList<CustomerOrder>();

		for (var group : calculation.groups().entrySet()) {
			var lines = group.getValue();
			var shop = lines.getFirst().getProduct().getShop();
			var amount = calculation.amounts().get(group.getKey());

			var order = new CustomerOrder();

			order.setCode(
					"UTE" + UUID.randomUUID().toString().replace("-", "").substring(0, 29).toUpperCase(Locale.ROOT));

			order.setPaymentMethod(form.getPaymentMethod());
			if (form.getPaymentMethod() == PaymentMethod.VNPAY || form.getPaymentMethod() == PaymentMethod.MOMO)
				order.setPaymentExpiresAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
						.plusMinutes(form.getPaymentMethod() == PaymentMethod.MOMO ? momoProperties.getExpirationMinutes() : vnPayProperties.getExpirationMinutes()));
			order.setCheckoutReference(reference);
			order.setUser(owner);
			order.setShop(shop);
			order.setCarrier(carrier);
			order.setVoucher(voucher);
			order.setReceiverName(address.getReceiverName());
			order.setReceiverPhone(address.getPhone());
			order.setShippingAddress(address.getFullAddress());

			order.setNote(form.getNote() == null ? null : form.getNote().trim());

			order.setSubtotal(amount.subtotal());
			order.setProductDiscount(amount.productDiscount());
			order.setVoucherDiscount(amount.voucherDiscount());

			order.setDiscountAmount(amount.productDiscount().add(amount.voucherDiscount()));

			order.setShippingFee(carrier.getShippingFee());
			order.setShippingDiscount(amount.shippingDiscount());
			order.setFinalShippingFee(amount.finalShippingFee());
			order.setTotalAmount(amount.totalAmount());

			// Snapshot hoa hồng và doanh thu theo công thức trong ZIP.
			order.snapshotCommission(shop.getCommissionRate());

			for (var line : lines) {
				var product = line.getProduct();
				var item = new OrderItem();

				item.setOrder(order);
				item.setProduct(product);
				item.setProductName(product.getName());
				item.setImageUrl(product.getImageUrl());
				item.setSize(line.getSize());
				item.setSugarLevel(line.getSugarLevel());
				item.setIceLevel(line.getIceLevel());
				item.setToppings(line.getToppings());
				item.setToppingIds(line.getToppingIds());
				item.setBasePrice(product.getPrice());
				item.setOptionPrice(line.getOptionPrice());
				item.setUnitPrice(line.getUnitPrice());
				item.setQuantity(line.getQuantity());
				item.setLineTotal(line.getLineTotal());

				order.getItems().add(item);
			}

			// Có ID đơn hàng trước khi ghi lịch sử kho.
			orderRepository.saveAndFlush(order);

			for (var line : lines) {
				inventoryService.sell(line.getProduct(), line.getQuantity(), owner, order);
			}

			recordStatus(order, null, OrderStatus.NEW, owner, "Khách hàng đặt đơn " + form.getPaymentMethod().name());

			created.add(order);
		}

		if (voucher != null) {
			voucher.setQuantity(voucher.getQuantity() - 1);

			var usage = new VoucherUsage();
			usage.setUser(owner);
			usage.setVoucher(voucher);
			usage.setOrderId(created.getFirst().getId());
			usage.setCheckoutReference(reference);

			usageRepository.save(usage);
		}

		cartService.removeItems(selection.cart(), selection.items());

		return List.copyOf(created);
	}

	public Page<CustomerOrder> forUser(User user, OrderStatus status, int page) {

		return orderRepository.findForUser(user.getId(), status, orderPage(page));
	}

	/** Scope-limited evidence read (customer, owner, assigned shipper, scoped manager, admin). */
	@Transactional(readOnly = true)
	public CustomerOrder getForEvidence(User actor, Long id) {
		CustomerOrder order = orderRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bằng chứng."));
		boolean allowed = switch (actor.getRole()) {
			case USER -> order.getUser().getId().equals(actor.getId());
			case VENDOR -> order.getUser().getId().equals(actor.getId())
				|| order.getShop().getOwner().getId().equals(actor.getId());
			case SHIPPER -> order.getShipper() != null && order.getShipper().getId().equals(actor.getId());
			case ADMIN -> true;
			case MANAGER -> managerShopRepository.existsByManagerIdAndShopId(actor.getId(), order.getShop().getId());
		};
		if (!allowed) throw new IllegalArgumentException("Không tìm thấy bằng chứng.");
		return order;
	}

	public CustomerOrder getForUser(User user, Long id) {
		return orderRepository.findByIdAndUserId(id, user.getId()).orElseThrow(
				() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng của bạn."));
	}

	public List<OrderStatusHistory> statusHistory(User user, Long id) {

		getForUser(user, id);

		return historyRepository.findByOrderIdOrderByChangedAtAscIdAsc(id);
	}

	@Transactional
	public void cancelByUser(User user, Long id) {
		var owner = userRepository.findByIdForUpdate(user.getId()).orElseThrow();

		var order = orderRepository.findOwnedForUpdate(id, owner.getId()).orElseThrow(
				() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng của bạn."));

		entityManager.refresh(order, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);

		if (order.getStatus() != OrderStatus.NEW || order.getPaymentStatus() != PaymentStatus.UNPAID) {
			throw new IllegalArgumentException("Chỉ có thể hủy đơn mới chưa thanh toán.");
		}

		var quantities = order.getItems().stream().collect(Collectors.groupingBy(item -> item.getProduct().getId(),
				Collectors.summingLong(OrderItem::getQuantity)));

		var products = order.getItems().stream().map(OrderItem::getProduct).distinct()
				.sorted(Comparator.comparing(Product::getId)).toList();

		for (var product : products) {
			entityManager.refresh(product, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);

			long quantity = quantities.get(product.getId());

			if (quantity <= 0 || quantity > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("Số lượng hoàn kho không hợp lệ.");
			}

			inventoryService.restoreAfterCancellation(product, (int) quantity, owner, order);
		}

		order.setStatus(OrderStatus.CANCELLED);

		releaseVoucherWhenCheckoutIsFullyCancelled(order);

		recordStatus(order, OrderStatus.NEW, OrderStatus.CANCELLED, owner, "Khách hàng hủy đơn");
	}

	// =========================
	// Xử lý đơn hàng của Vendor
	// =========================

	public Page<CustomerOrder> forVendor(User vendor, OrderStatus status, int page) {

		var shop = requireVendorShop(vendor);

		return orderRepository.findForShop(shop.getId(), status, orderPage(page));
	}

	public CustomerOrder getForVendor(User vendor, Long id) {
		var shop = requireVendorShop(vendor);

		if (id == null || id <= 0) {
			throw vendorOrderNotFound();
		}

		return orderRepository.findByIdAndShopId(id, shop.getId()).orElseThrow(this::vendorOrderNotFound);
	}

	public List<OrderStatusHistory> vendorStatusHistory(User vendor, Long id) {

		var order = getForVendor(vendor, id);

		return historyRepository.findByOrderIdOrderByChangedAtAscIdAsc(order.getId());
	}

	public VendorOrderStats vendorOrderStats(User vendor) {
		var shop = requireVendorShop(vendor);
		var counts = new EnumMap<OrderStatus, Long>(OrderStatus.class);

		for (var row : orderRepository.countByStatusForShop(shop.getId())) {
			counts.put((OrderStatus) row[0], ((Number) row[1]).longValue());
		}

		return new VendorOrderStats(counts.getOrDefault(OrderStatus.NEW, 0L),
				counts.getOrDefault(OrderStatus.CONFIRMED, 0L), counts.getOrDefault(OrderStatus.PREPARING, 0L),
				counts.getOrDefault(OrderStatus.READY_FOR_PICKUP, 0L));
	}

	public List<OrderStatus> vendorTransitions(OrderStatus current) {
		if (current == null) {
			return List.of();
		}

		return switch (current) {
		case NEW -> List.of(OrderStatus.CONFIRMED);
		case CONFIRMED -> List.of(OrderStatus.PREPARING);
		case PREPARING -> List.of(OrderStatus.READY_FOR_PICKUP);
		default -> List.of();
		};
	}

	@Transactional
	public void vendorUpdate(User vendor, Long id, OrderStatus target) {

		vendorUpdate(vendor, id, target, null);
	}

	@Transactional
	public void vendorUpdate(User vendor, Long id, OrderStatus target, String note) {

		var shop = requireVendorShop(vendor);

		if (id == null || id <= 0) {
			throw vendorOrderNotFound();
		}

		var order = orderRepository.findShopOrderForUpdate(id, shop.getId()).orElseThrow(this::vendorOrderNotFound);

		entityManager.refresh(order, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);

		requireOnlinePayment(order);
		var oldStatus = order.getStatus();

		if (target == null || !vendorTransitions(oldStatus).contains(target)) {
			throw new IllegalArgumentException(
					"Không thể chuyển đơn sang trạng thái đã chọn. " + "Vui lòng tải lại danh sách đơn hàng.");
		}

		var historyNote = normalizeVendorNote(note);

		order.setStatus(target);

		recordStatus(order, oldStatus, target, shop.getOwner(), historyNote);

		entityManager.flush();
	}

	private Shop requireVendorShop(User vendor) {
		if (vendor == null || vendor.getId() == null) {
			throw vendorAccessDenied();
		}

		var account = userRepository.findById(vendor.getId()).orElseThrow(this::vendorAccessDenied);

		if (account.getRole() != Role.VENDOR || !account.isEnabled() || account.isLocked()) {
			throw vendorAccessDenied();
		}

		return shopRepository.findByOwnerId(account.getId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bạn chưa có cửa hàng."));
	}

	private String normalizeVendorNote(String note) {
		if (note == null || note.isBlank()) {
			return "Cập nhật bởi người bán";
		}

		var normalized = note.trim();

		if (normalized.length() > 500) {
			throw new IllegalArgumentException("Ghi chú xử lý đơn không được vượt quá 500 ký tự.");
		}

		return normalized;
	}

	private ResponseStatusException vendorAccessDenied() {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền xử lý đơn hàng của cửa hàng.");
	}

	private ResponseStatusException vendorOrderNotFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng của cửa hàng.");
	}

	private PageRequest orderPage(int page) {
		return PageRequest.of(Math.max(0, page), 15, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
	}

	private void releaseVoucherWhenCheckoutIsFullyCancelled(CustomerOrder order) {

		if (order.getVoucher() == null
				|| orderRepository.countByCheckoutReferenceAndIdNotAndStatusNot(order.getCheckoutReference(),
						order.getId(), OrderStatus.CANCELLED) > 0) {
			return;
		}

		var voucher = voucherRepository.findByIdForUpdate(order.getVoucher().getId()).orElseThrow();

		entityManager.refresh(voucher, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);

		var usage = usageRepository.findByCheckoutReferenceForUpdate(order.getCheckoutReference()).orElseThrow(
				() -> new IllegalStateException("Không tìm thấy lượt sử dụng mã giảm giá " + "của checkout."));

		if (usage.getStatus() != VoucherUsageStatus.USED) {
			return;
		}

		if (voucher.getQuantity() == Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Số lượt mã giảm giá vượt giới hạn.");
		}

		voucher.setQuantity(voucher.getQuantity() + 1);

		usage.setStatus(VoucherUsageStatus.CANCELLED);
		usage.setReleasedAt(LocalDateTime.now());
		usage.setReleaseReason("Hoàn lượt do toàn bộ đơn trong checkout đã hủy");
	}

	// Gọi từ ReturnRequestService sau khi đã khóa và kiểm tra yêu cầu.
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	void applyReturnStatus(CustomerOrder order, OrderStatus target, User actor, String note) {

		requireReturnOrderLock(order);

		var account = requireReturnAccount(actor);
		var oldStatus = order.getStatus();

		boolean requesting = target == OrderStatus.RETURN_REQUESTED && oldStatus == OrderStatus.DELIVERED;
		boolean rejecting = target == OrderStatus.DELIVERED && oldStatus == OrderStatus.RETURN_REQUESTED;
		boolean refunding = target == OrderStatus.RETURNED_REFUNDED && oldStatus == OrderStatus.RETURN_REQUESTED;

		if (!requesting && !rejecting && !refunding) {
			throw new IllegalArgumentException("Trạng thái đơn không phù hợp để xử lý trả hàng.");
		}

		if (requesting) {
			if ((account.getRole() != Role.USER && account.getRole() != Role.VENDOR)
					|| !sameUser(account, order.getUser())) {
				throw new AccessDeniedException("Chỉ người mua được tạo yêu cầu trả hàng cho đơn này.");
			}
		} else {
			boolean owner = account.getRole() == Role.VENDOR && sameUser(account, order.getShop().getOwner());
			boolean admin = account.getRole() == Role.ADMIN;
			boolean manager = account.getRole() == Role.MANAGER
					&& managerShopRepository.existsByManagerIdAndShopId(account.getId(), order.getShop().getId());

			if (!owner && !admin && !(rejecting && manager)) {
				throw new AccessDeniedException("Bạn không có quyền thực hiện thao tác trả hàng này.");
			}
		}

		if (order.getPaymentStatus() != PaymentStatus.PAID) {
			throw new IllegalArgumentException("Chỉ xử lý trả hàng và hoàn tiền cho đơn đã thanh toán.");
		}

		String historyNote = note == null || note.isBlank() ? "Xử lý yêu cầu trả hàng" : note.trim();

		if (historyNote.length() > 500) {
			throw new IllegalArgumentException("Ghi chú lịch sử đơn không được vượt quá 500 ký tự.");
		}

		if (refunding) {
			restoreRefundedOrderInventory(order, account);
			order.setPaymentStatus(PaymentStatus.REFUNDED);

			// Theo ZIP: trả hàng không hoàn lượt voucher.
			// Voucher chỉ được hoàn khi toàn bộ checkout bị hủy.
		}

		order.setStatus(target);
		if (refunding) loyaltyPointService.reverseForRefundedOrder(order);
		recordStatus(order, oldStatus, target, account, historyNote);
		entityManager.flush();
	}

	private void restoreRefundedOrderInventory(CustomerOrder order, User actor) {

		var quantities = order.getItems().stream().collect(Collectors.groupingBy(item -> item.getProduct().getId(),
				TreeMap::new, Collectors.summingLong(OrderItem::getQuantity)));

		for (var entry : quantities.entrySet()) {
			long quantity = entry.getValue();

			if (quantity <= 0 || quantity > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("Số lượng hoàn kho không hợp lệ.");
			}

			var product = inventoryService.lockProduct(entry.getKey());
			int restoredQuantity = (int) quantity;

			product.setSoldCount(Math.max(0L, product.getSoldCount() - restoredQuantity));

			inventoryService.restoreAfterRefund(product, restoredQuantity, actor, order);
		}
	}

	private User requireReturnAccount(User actor) {
		if (actor == null || actor.getId() == null) {
			throw new AccessDeniedException("Bạn không có quyền xử lý trả hàng.");
		}

		return userRepository.findById(actor.getId()).filter(account -> account.isEnabled() && !account.isLocked())
				.orElseThrow(() -> new AccessDeniedException("Tài khoản không còn quyền xử lý trả hàng."));
	}

	private void requireReturnOrderLock(CustomerOrder order) {
		if (order == null || order.getId() == null || !entityManager.contains(order)) {
			throw new IllegalStateException("Đơn hàng phải thuộc giao dịch xử lý trả hàng.");
		}

		var lockMode = entityManager.getLockMode(order);

		if (lockMode != jakarta.persistence.LockModeType.PESSIMISTIC_WRITE
				&& lockMode != jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT) {
			throw new IllegalStateException("Phải khóa đơn hàng trước khi xử lý trả hàng.");
		}
	}

	private Carrier carrier(Long id) {
		if (id == null) {
			throw new IllegalArgumentException("Vui lòng chọn nhà vận chuyển.");
		}

		return carrierRepository.findById(id).filter(Carrier::isActive)
				.orElseThrow(() -> new IllegalArgumentException("Nhà vận chuyển không còn khả dụng."));
	}

	private Voucher voucher(User user, String code, BigDecimal netSubtotal, boolean lock) {

		if (code == null || code.isBlank()) {
			return null;
		}

		if (code.trim().length() > 40) {
			throw new IllegalArgumentException("Mã giảm giá không hợp lệ.");
		}

		var found = lock ? voucherRepository.findByCodeForCheckout(code.trim())
				: voucherRepository.findByCodeIgnoreCase(code.trim());

		var voucher = found.orElseThrow(() -> new IllegalArgumentException("Mã giảm giá không tồn tại."));

		if (lock) {
			entityManager.refresh(voucher, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
		}

		if (!voucher.canApply(netSubtotal, LocalDateTime.now())) {
			throw new IllegalArgumentException(
					"Mã giảm giá hết lượt, hết hiệu lực " + "hoặc chưa đạt giá trị tối thiểu.");
		}

		if (usageRepository.countByUserIdAndVoucherIdAndStatus(user.getId(), voucher.getId(),
				VoucherUsageStatus.USED) >= voucher.getEffectiveMaxUsesPerUser()) {
			throw new IllegalArgumentException("Bạn đã dùng hết lượt của mã giảm giá này.");
		}

		return voucher;
	}

	private CheckoutCalculation calculate(List<CartItem> items, Carrier carrier, Voucher voucher) {

		var groups = items.stream().collect(Collectors.groupingBy(item -> item.getProduct().getShop().getId(),
				LinkedHashMap::new, Collectors.toList()));

		var gross = new LinkedHashMap<Long, BigDecimal>();
		var net = new LinkedHashMap<Long, BigDecimal>();
		var shipping = new LinkedHashMap<Long, BigDecimal>();

		for (var group : groups.entrySet()) {
			var lines = group.getValue();

			gross.put(group.getKey(),
					money(lines.stream()
							.map(item -> item.getProduct().getPrice().add(item.getOptionPrice())
									.multiply(BigDecimal.valueOf(item.getQuantity())))
							.reduce(BigDecimal.ZERO, BigDecimal::add)));

			net.put(group.getKey(),
					money(lines.stream().map(CartItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add)));

			var promotions = promotionRepository.findApplicable(group.getKey(),
					lines.stream().map(item -> item.getProduct().getId()).distinct().toList(), LocalDateTime.now());

			var discount = promotions.stream()
					.filter(promotion -> promotion.getType() == PromotionType.SHIPPING_DISCOUNT)
					.map(Promotion::getDiscountValue).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO)
					.max(BigDecimal.ZERO).min(carrier.getShippingFee());

			shipping.put(group.getKey(), money(carrier.getShippingFee().subtract(discount)));
		}

		var netTotal = sum(net.values());
		var shippingTotal = sum(shipping.values());

		var voucherAmount = voucher == null ? BigDecimal.ZERO
				: money(voucher.calculateDiscount(netTotal, shippingTotal));

		var productShares = allocate(
				voucher != null && voucher.getType() != VoucherType.SHIPPING ? voucherAmount : BigDecimal.ZERO, net);

		var shippingShares = allocate(
				voucher != null && voucher.getType() == VoucherType.SHIPPING ? voucherAmount : BigDecimal.ZERO,
				shipping);

		var amounts = new LinkedHashMap<Long, ShopAmounts>();

		for (Long shopId : groups.keySet()) {
			var finalShipping = money(shipping.get(shopId).subtract(shippingShares.get(shopId)));

			amounts.put(shopId, new ShopAmounts(gross.get(shopId), money(gross.get(shopId).subtract(net.get(shopId))),
					productShares.get(shopId), money(carrier.getShippingFee().subtract(finalShipping)), finalShipping,
					money(net.get(shopId).subtract(productShares.get(shopId)).add(finalShipping))));
		}

		var values = amounts.values();

		return new CheckoutCalculation(groups, amounts,
				new CheckoutPreview(sum(gross.values()),
						sum(values.stream().map(ShopAmounts::productDiscount).toList()),
						sum(values.stream().map(ShopAmounts::voucherDiscount).toList()),
						money(carrier.getShippingFee().multiply(BigDecimal.valueOf(groups.size()))),
						sum(values.stream().map(ShopAmounts::shippingDiscount).toList()),
						sum(values.stream().map(ShopAmounts::finalShippingFee).toList()),
						sum(values.stream().map(ShopAmounts::totalAmount).toList()), groups.size()));
	}

	/**
	 * Phân bổ giảm giá theo đơn vị xu, giữ phần dư để tổng các phần phân bổ bằng
	 * đúng mức giảm đã giới hạn.
	 */
	private Map<Long, BigDecimal> allocate(BigDecimal discount, Map<Long, BigDecimal> bases) {

		var result = new LinkedHashMap<Long, BigDecimal>();
		var remainingBase = sum(bases.values());

		var remaining = discount.max(BigDecimal.ZERO).min(remainingBase).setScale(2, RoundingMode.DOWN);

		for (var entry : bases.entrySet()) {
			var base = entry.getValue();

			var share = remainingBase.signum() == 0 ? new BigDecimal("0.00")
					: remaining.multiply(base).divide(remainingBase, 2, RoundingMode.DOWN).min(base);

			result.put(entry.getKey(), share);
			remaining = remaining.subtract(share);
			remainingBase = remainingBase.subtract(base);
		}

		return result;
	}

	private BigDecimal sum(Collection<BigDecimal> values) {
		return money(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
	}

	private BigDecimal money(BigDecimal value) {
		var result = value.setScale(2, RoundingMode.HALF_UP);

		if (result.signum() < 0 || result.compareTo(new BigDecimal("999999999999.99")) > 0) {
			throw new IllegalArgumentException("Giá trị thanh toán vượt giới hạn cho phép.");
		}

		return result;
	}

	private void recordStatus(CustomerOrder order, OrderStatus old, OrderStatus next, User actor, String note) {

		var history = new OrderStatusHistory();

		history.setOrder(order);
		history.setOldStatus(old);
		history.setNewStatus(next);
		history.setChangedBy(actor);
		history.setNote(note);

		historyRepository.save(history);
		if (actor != null && actor.getRole() != Role.USER && old != next) {
			auditLogService.record(
					actor.getRole() == Role.SHIPPER ? AuditAction.SHIPPER_ORDER_STATUS
							: AuditAction.ORDER_STATUS_CHANGED,
					actor, "ORDER", order.getId(), order.getCode(), old == null ? null : old.getLabel(),
					next == null ? null : next.getLabel(), note);
		}
		publishOrderEvent(order, old, next, actor, history);
	}

	/** Step 22: thông báo dùng chung cho các luồng đơn hàng. */
	private void publishOrderEvent(CustomerOrder order, OrderStatus previous, OrderStatus current, User actor,
			OrderStatusHistory history) {

		String code = order.getCode();
		String detailLink = "/orders/" + order.getId();
		User customer = order.getUser();
		User vendor = order.getShop().getOwner();
		User shipper = order.getShipper();

		boolean changed = previous != current;
		boolean created = previous == null;

		if (changed && !created && !sameUser(customer, actor)) {
			notificationService.sendOrderStatus(customer, history);
			notificationService.send(customer, "Cập nhật đơn hàng", "Đơn " + code + ": " + current.getLabel(),
					detailLink);
		}

		if (created) {
			notificationService.send(vendor, "Có đơn hàng mới", "Đơn " + code + " đang chờ xác nhận.",
					"/vendor/orders");
		} else if (changed && !sameUser(vendor, actor)) {
			notificationService.send(vendor, "Trạng thái đơn hàng", "Đơn " + code + ": " + current.getLabel(),
					"/vendor/orders");
		}

		// Phân công là một sự kiện dù trạng thái đơn không đổi.
		if (shipper != null && !sameUser(shipper, actor)
				&& (previous == current || current == OrderStatus.READY_FOR_PICKUP)) {
			notificationService.send(shipper, "Đơn hàng được phân công", "Bạn có đơn " + code + " đang chờ lấy hàng.",
					"/shipper");
		}

		if (current == OrderStatus.DELIVERY_FAILED && changed) {
			for (var managerShop : managerShopRepository.findByShopId(order.getShop().getId())) {
				User manager = managerShop.getManager();

				if (manager.isEnabled() && !manager.isLocked()) {
					notificationService.send(manager, "Giao hàng thất bại", "Đơn " + code + " cần được xử lý.",
							"/management/orders");
				}
			}

			for (User admin : userRepository.findByRoleAndEnabledTrueAndLockedFalseOrderByFullNameAsc(Role.ADMIN)) {
				notificationService.send(admin, "Giao hàng thất bại", "Đơn " + code + " cần được xử lý.",
						"/management/orders");
			}
		}
	}

	private boolean sameUser(User first, User second) {
		return first != null && second != null && first.getId() != null && first.getId().equals(second.getId());
	}

	// =========================
	// Management / Shipper
	// =========================

	public Page<CustomerOrder> forShipper(User actor, OrderStatus status, int page) {
        return forShipper(actor, status, null, page, "recent");
    }

    public Page<CustomerOrder> forShipper(User actor, OrderStatus status, String query, int page, String sort) {
        requireActiveShipper(actor);
        String q = query == null || query.isBlank() ? null : query.strip();
        if (q != null) {
            q = q.substring(0, Math.min(q.length(), 80))
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        }
        return orderRepository.findForShipper(actor.getId(), status, q,
            OrderStatus.DELIVERY_FAILED, "oldest".equals(sort),
            PageRequest.of(Math.max(page, 0), 15));
    }

	public long shipperTotal(User actor) {
		requireActiveShipper(actor);
		return orderRepository.countByShipperId(actor.getId());
	}

	public long shipperCount(User actor, OrderStatus status) {
		requireActiveShipper(actor);

		return orderRepository.countByShipperIdAndStatus(actor.getId(), status);
	}

	public List<OrderStatus> shipperTransitions(OrderStatus current) {
		return switch (current) {
		case READY_FOR_PICKUP -> List.of(OrderStatus.PICKED_UP);
		case PICKED_UP -> List.of(OrderStatus.SHIPPING);
		case SHIPPING -> List.of(OrderStatus.DELIVERED, OrderStatus.DELIVERY_FAILED);
		default -> List.of();
		};
	}

	public List<OrderStatus> managementTransitions(OrderStatus current) {
		return current == OrderStatus.DELIVERY_FAILED ? List.of(OrderStatus.CANCELLED) : vendorTransitions(current);
	}

	public boolean canAssignShipper(OrderStatus current) {
		return current == OrderStatus.CONFIRMED || current == OrderStatus.PREPARING
				|| current == OrderStatus.READY_FOR_PICKUP;
	}

	@Transactional
	public void assignShipper(User actor, Long orderId, Long shipperId) {

		CustomerOrder order = lockDeliveryOrder(orderId);
		requireManagementAccess(actor, order);

		if (!canAssignShipper(order.getStatus())) {
			throw new IllegalArgumentException(
					"Chỉ được phân công khi đơn đã xác nhận, " + "đang chuẩn bị hoặc sẵn sàng lấy hàng.");
		}

		User shipper = requireAssignableShipper(shipperId);

		if (order.getShipper() != null && order.getShipper().getId().equals(shipper.getId())) {
			return;
		}

		String previous = order.getShipper() == null ? "chưa phân công" : order.getShipper().getFullName();

		order.setShipper(shipper);
		auditLogService.record(AuditAction.SHIPPER_ASSIGNED, actor, "ORDER", order.getId(), order.getCode(), previous,
				shipper.getFullName(), "Phân công nhân viên giao hàng");

		recordStatus(order, order.getStatus(), order.getStatus(), actor,
				"Phân công shipper: " + previous + " → " + shipper.getFullName());

		entityManager.flush();
	}

	@Transactional
	public void retryFailedDelivery(User actor, Long orderId, Long shipperId) {

		CustomerOrder order = lockDeliveryOrder(orderId);
		requireManagementAccess(actor, order);

		if (order.getStatus() != OrderStatus.DELIVERY_FAILED) {
			throw new IllegalArgumentException("Chỉ đơn giao thất bại mới được điều phối giao lại.");
		}

		User shipper = requireAssignableShipper(shipperId);

		if (order.getShipper() != null && order.getShipper().getId().equals(shipper.getId())) {
			throw new IllegalArgumentException("Vui lòng chọn shipper khác cho lần giao lại.");
		}

		String previousShipper = order.getShipper() == null ? "chưa phân công" : order.getShipper().getFullName();
		order.setShipper(shipper);
		auditLogService.record(AuditAction.SHIPPER_ASSIGNED, actor, "ORDER", order.getId(), order.getCode(),
				previousShipper, shipper.getFullName(), "Điều phối giao lại");
		order.setPickedUpAt(null);
		order.setDeliveredAt(null);
		order.setFailureReason(null);
		order.setDeliveryProofUrl(null);
		order.setStatus(OrderStatus.READY_FOR_PICKUP);

		recordStatus(order, OrderStatus.DELIVERY_FAILED, OrderStatus.READY_FOR_PICKUP, actor,
				"Điều phối giao lại cho " + shipper.getFullName());

		entityManager.flush();
	}

	@Transactional
	public void managementUpdate(User actor, Long orderId, OrderStatus target) {

		CustomerOrder order = lockDeliveryOrder(orderId);
		requireManagementAccess(actor, order);
		requireOnlinePayment(order);
		OrderStatus old = order.getStatus();

		if (target == null || !managementTransitions(old).contains(target)) {
			throw new IllegalArgumentException("Không thể chuyển trạng thái đơn hàng như yêu cầu.");
		}

		if (old == OrderStatus.DELIVERY_FAILED && target == OrderStatus.CANCELLED) {
			if (order.getPaymentStatus() != PaymentStatus.UNPAID) {
				throw new IllegalArgumentException("Đơn đã thanh toán cần xử lý hoàn tiền trước khi hủy.");
			}

			restoreFailedOrderInventory(order, actor);
			order.setStatus(OrderStatus.CANCELLED);
			releaseVoucherWhenCheckoutIsFullyCancelled(order);

			recordStatus(order, old, OrderStatus.CANCELLED, actor, "Manager/Admin hủy đơn sau khi giao thất bại");
		} else {
			order.setStatus(target);

			recordStatus(order, old, target, actor, "Cập nhật bởi " + actor.getRole().getLabel());
		}

		entityManager.flush();
	}

	@Transactional
	public void shipperUpdate(User actor, Long orderId, OrderStatus target, String failureReason,
			MultipartFile deliveryProof) {

		requireActiveShipper(actor);

		CustomerOrder order = lockDeliveryOrder(orderId);

		if (order.getShipper() == null || !order.getShipper().getId().equals(actor.getId())) {
			throw new AccessDeniedException("Đơn hàng chưa được phân công cho bạn.");
		}

		requireOnlinePayment(order);
		OrderStatus old = order.getStatus();

		if (target == null || !shipperTransitions(old).contains(target)) {
			throw new IllegalArgumentException("Không thể chuyển từ " + old.getLabel() + " sang trạng thái yêu cầu.");
		}

		String note = "Cập nhật bởi nhân viên giao hàng";

		if (target == OrderStatus.PICKED_UP) {
			order.setPickedUpAt(LocalDateTime.now());
		} else if (target == OrderStatus.DELIVERED) {
			validateDeliveryProof(deliveryProof, true);

			order.setDeliveryProofUrl(mediaStorageService.upload(deliveryProof, "delivery-proofs"));

			order.setDeliveredAt(LocalDateTime.now());
			order.setFailureReason(null);

			if (order.getPaymentMethod() == PaymentMethod.COD) {
				order.setPaymentStatus(PaymentStatus.PAID);
			}

			note = "Giao thành công, đã đính kèm ảnh bằng chứng";
		} else if (target == OrderStatus.DELIVERY_FAILED) {
			String reason = failureReason == null ? "" : failureReason.trim();

			if (reason.length() < 5 || reason.length() > 500) {
				throw new IllegalArgumentException("Lý do giao thất bại phải từ 5 đến 500 ký tự.");
			}

			validateDeliveryProof(deliveryProof, false);

			if (deliveryProof != null && !deliveryProof.isEmpty()) {
				order.setDeliveryProofUrl(mediaStorageService.upload(deliveryProof, "delivery-proofs"));
			}

			order.setFailureReason(reason);
			note = reason;
		}

		order.setStatus(target);
		if (target == OrderStatus.DELIVERED) loyaltyPointService.awardForDeliveredOrder(order);
		recordStatus(order, old, target, actor, note);

		entityManager.flush();
	}

	private void validateDeliveryProof(MultipartFile proof, boolean required) {

		if (proof == null || proof.isEmpty()) {
			if (required) {
				throw new IllegalArgumentException("Vui lòng tải ảnh bằng chứng giao hàng.");
			}
			return;
		}

		mediaValidationService.validate(proof, "delivery-proofs");
	}

	private void requireOnlinePayment(CustomerOrder order) {
		if (order.getPaymentMethod() != PaymentMethod.COD && order.getPaymentStatus() != PaymentStatus.PAID)
			throw new IllegalArgumentException("Đơn VNPay cần thanh toán thành công trước khi xử lý giao hàng.");
	}

	private CustomerOrder lockDeliveryOrder(Long orderId) {
		if (orderId == null || orderId <= 0) {
			throw new IllegalArgumentException("Đơn hàng không hợp lệ.");
		}

		CustomerOrder order = orderRepository.findOrderForDeliveryUpdate(orderId)
				.orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."));

		entityManager.refresh(order, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);

		return order;
	}

	private void requireManagementAccess(User actor, CustomerOrder order) {

		if (actor == null || actor.getId() == null) {
			throw new AccessDeniedException("Không có quyền điều phối đơn.");
		}

		User account = userRepository.findById(actor.getId())
				.orElseThrow(() -> new AccessDeniedException("Không có quyền điều phối đơn."));

		if (!account.isEnabled() || account.isLocked()
				|| (account.getRole() != Role.ADMIN && (account.getRole() != Role.MANAGER || !managerShopRepository
						.existsByManagerIdAndShopId(account.getId(), order.getShop().getId())))) {
			throw new AccessDeniedException("Bạn không được điều phối đơn hàng của shop này.");
		}
	}

	private void requireActiveShipper(User actor) {
		if (actor == null || actor.getId() == null) {
			throw new AccessDeniedException("Chỉ Shipper được xử lý giao hàng.");
		}

		User account = userRepository.findById(actor.getId())
				.orElseThrow(() -> new AccessDeniedException("Chỉ Shipper được xử lý giao hàng."));

		if (account.getRole() != Role.SHIPPER || !account.isEnabled() || account.isLocked()) {
			throw new AccessDeniedException("Chỉ Shipper đang hoạt động được xử lý giao hàng.");
		}
	}

	private User requireAssignableShipper(Long shipperId) {
		return userRepository.findById(shipperId == null ? -1L : shipperId)
				.filter(u -> u.getRole() == Role.SHIPPER && u.isEnabled() && !u.isLocked())
				.orElseThrow(() -> new IllegalArgumentException("Shipper không hợp lệ hoặc đã bị khóa."));
	}

	private void restoreFailedOrderInventory(CustomerOrder order, User actor) {

		var quantities = order.getItems().stream().collect(Collectors.groupingBy(item -> item.getProduct().getId(),
				Collectors.summingLong(OrderItem::getQuantity)));

		var products = order.getItems().stream().map(OrderItem::getProduct).distinct()
				.sorted(Comparator.comparing(Product::getId)).toList();

		for (var product : products) {
			entityManager.refresh(product, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);

			long quantity = quantities.get(product.getId());

			if (quantity <= 0 || quantity > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("Số lượng hoàn kho không hợp lệ.");
			}

			inventoryService.restoreAfterCancellation(product, (int) quantity, actor, order);
		}
	}

	// =========================
	// Bước 29: Doanh thu và hoa hồng của Shop
	// =========================

	public BigDecimal revenue(Shop shop) {
		return revenueSummary(shop).sellerRevenue();
	}

	public RevenueSummary revenueSummary(Shop shop) {
		return new RevenueSummary(
				zeroIfNull(orderRepository.sumRevenue(shop.getId(), PaymentStatus.PAID, OrderStatus.DELIVERED)),
				zeroIfNull(orderRepository.sumPlatformRevenueByShop(shop.getId(), PaymentStatus.PAID,
						OrderStatus.DELIVERED)),
				zeroIfNull(orderRepository.sumSellerRevenue(shop.getId(), PaymentStatus.PAID, OrderStatus.DELIVERED)));
	}

	public long shopCount(Shop shop, OrderStatus status) {
		return orderRepository.countByShopIdAndStatus(shop.getId(), status);
	}

	private BigDecimal zeroIfNull(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	public record RevenueSummary(BigDecimal grossRevenue, BigDecimal platformRevenue, BigDecimal sellerRevenue) {
	}

	public record VendorOrderStats(long newOrders, long confirmedOrders, long preparingOrders, long readyOrders) {
	}

	public record CheckoutPreview(BigDecimal subtotal, BigDecimal productDiscount, BigDecimal voucherDiscount,
			BigDecimal shippingFee, BigDecimal shippingDiscount, BigDecimal finalShippingFee, BigDecimal totalAmount,
			int shopCount) {
	}

	private record ShopAmounts(BigDecimal subtotal, BigDecimal productDiscount, BigDecimal voucherDiscount,
			BigDecimal shippingDiscount, BigDecimal finalShippingFee, BigDecimal totalAmount) {
	}

	private record CheckoutCalculation(Map<Long, List<CartItem>> groups, Map<Long, ShopAmounts> amounts,
			CheckoutPreview preview) {
	}

	public static final class PriceChangedException extends IllegalArgumentException {

		public PriceChangedException() {
			super("Giá đã thay đổi. Vui lòng kiểm tra lại " + "tổng tiền trước khi xác nhận.");
		}
	}
}
