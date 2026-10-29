package vn.iotstar.service;

import vn.iotstar.util.VietnamTime;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final EntityManager entityManager;
    private final OrderRepository orderRepository;
    private final AddressRepository addressRepository;
    private final CarrierRepository carrierRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherUsageRepository voucherUsageRepository;
    private final PromotionRepository promotionRepository;
    private final ProductRepository productRepository;
    private final CartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final ManagerShopRepository managerShopRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final CartService cartService;
    private final ProductPricingService productPricingService;
    private final NotificationService notificationService;
    private final MediaStorageService mediaStorageService;
    private final MediaValidationService mediaValidationService;
    private final InventoryService inventoryService;
    private final AuditLogService auditLogService;
    private final LoyaltyPointService loyaltyPointService;
    private final PayOsClient payOsClient;

    @Value("${app.shipper.assignment-reminder-minutes:10}")
    private int assignmentReminderMinutes;

    @Value("${app.shipper.assignment-timeout-minutes:30}")
    private int assignmentTimeoutMinutes;

    public List<Carrier> activeCarriers() {
        return carrierRepository.findByActiveTrueOrderByShippingFeeAsc();
    }

    @Transactional(noRollbackFor = PriceChangedException.class)
    public List<CustomerOrder> checkout(User user, CheckoutForm form) {
        Address address = addressRepository.findByIdAndUserId(form.getAddressId(), user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Địa chỉ nhận hàng không hợp lệ."));
        Carrier carrier = carrierRepository.findById(form.getCarrierId())
            .filter(Carrier::isActive).orElseThrow(() -> new IllegalArgumentException("Nhà vận chuyển không hợp lệ."));
        LinkedHashSet<Long> requestedItemIds = form.getItemIds() == null ? new LinkedHashSet<>()
            : form.getItemIds().stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Long> productIds;
        if (requestedItemIds.isEmpty()) {
            productIds = cartItemRepository.findProductIdsByUserId(user.getId());
        } else {
            if (cartItemRepository.countOwnedItems(user.getId(), requestedItemIds) != requestedItemIds.size()) {
                throw new IllegalArgumentException("Có sản phẩm đã chọn không còn nằm trong giỏ hàng của bạn.");
            }
            productIds = cartItemRepository.findProductIdsByUserIdAndItemIds(user.getId(), requestedItemIds);
        }
        if (productIds.isEmpty()) throw new IllegalArgumentException("Vui lòng chọn ít nhất một sản phẩm để thanh toán.");
        List<Product> lockedProducts = productRepository.findAllByIdForCheckout(productIds);
        if (lockedProducts.size() != productIds.size()) {
            throw new IllegalArgumentException("Có sản phẩm trong giỏ không còn tồn tại.");
        }
        Map<Long, Product> lockedById = lockedProducts.stream()
            .collect(Collectors.toMap(Product::getId, product -> product));

        Cart cart = cartService.get(user);
        List<CartItem> selectedItems = cartService.selectItems(cart, List.copyOf(requestedItemIds), true);
        CartService.CartRepriceResult repricing = cartService.reprice(selectedItems);
        if (repricing.pricesChanged()) {
            throw new PriceChangedException(repricing.changedProducts());
        }

        Map<Long, Long> quantityByProduct = new LinkedHashMap<>();
        for (CartItem item : selectedItems) {
            quantityByProduct.merge(item.getProduct().getId(), (long) item.getQuantity(), Long::sum);
        }
        for (Map.Entry<Long, Long> entry : quantityByProduct.entrySet()) {
            Product lockedProduct = lockedById.get(entry.getKey());
            if (lockedProduct == null || !lockedProduct.isAvailable()
                || entry.getValue() > lockedProduct.getStock()) {
                String productName = lockedProduct == null ? "Sản phẩm" : "Sản phẩm " + lockedProduct.getName();
                String remaining = lockedProduct == null ? "" : " Chỉ còn " + lockedProduct.getStock()
                    + " ly, bạn đã chọn " + entry.getValue() + " ly.";
                throw new IllegalArgumentException(productName + " không đủ tồn kho." + remaining);
            }
        }
        Voucher voucher = resolveVoucher(user, form.getVoucherCode());
        CheckoutCalculation calculation = calculate(selectedItems, carrier, voucher);

        List<CustomerOrder> created = new ArrayList<>();
        String checkoutReference = "CHK-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        for (Map.Entry<Shop, List<CartItem>> entry : calculation.itemsByShop().entrySet()) {
            Shop shop = entry.getKey();
            List<CartItem> lines = entry.getValue();
            ShopAmounts amounts = calculation.shopAmounts().get(shop);

            CustomerOrder order = new CustomerOrder();
            order.setCode(newOrderCode());
            order.setUser(user);
            order.setShop(shop);
            order.setCarrier(carrier);
            order.setVoucher(voucher);
            order.setCheckoutReference(checkoutReference);
            order.setReceiverName(address.getReceiverName());
            order.setReceiverPhone(address.getPhone());
            order.setShippingAddress(address.getFullAddress());
            order.setNote(form.getNote() == null ? null : form.getNote().trim());
            order.setPaymentMethod(form.getPaymentMethod());
            order.setSubtotal(amounts.subtotal());
            order.setProductDiscount(amounts.productDiscount());
            order.setVoucherDiscount(amounts.voucherDiscount());
            order.setDiscountAmount(amounts.productDiscount().add(amounts.voucherDiscount()));
            order.setShippingFee(carrier.getShippingFee());
            order.setShippingDiscount(amounts.shippingDiscount());
            order.setFinalShippingFee(amounts.finalShippingFee());
            order.setTotalAmount(amounts.totalAmount());
            order.snapshotCommission(shop.getCommissionRate());

            List<Integer> stockBefore = new ArrayList<>();
            for (CartItem line : lines) {
                Product product = lockedById.get(line.getProduct().getId());
                stockBefore.add(product.getStock());
                product.setStock(product.getStock() - line.getQuantity());
                if (product.getStock() == 0) product.setStatus(ProductStatus.OUT_OF_STOCK);
                OrderItem orderItem = new OrderItem();
                orderItem.setOrder(order);
                orderItem.setProduct(product);
                orderItem.setProductName(product.getName());
                orderItem.setImageUrl(product.getImageUrl());
                orderItem.setSize(line.getSize());
                orderItem.setSugarLevel(line.getSugarLevel());
                orderItem.setIceLevel(line.getIceLevel());
                orderItem.setToppings(line.getToppings());
                orderItem.setToppingIds(line.getToppingIds());
                orderItem.setBasePrice(line.getBasePrice() == null ? product.getPrice() : line.getBasePrice());
                orderItem.setOptionPrice(line.getOptionPrice() == null ? BigDecimal.ZERO : line.getOptionPrice());
                orderItem.setUnitPrice(line.getEffectiveUnitPrice());
                orderItem.setQuantity(line.getQuantity());
                orderItem.setLineTotal(line.getLineTotal());
                order.getItems().add(orderItem);
            }
            orderRepository.save(order);
            for (int index = 0; index < order.getItems().size(); index++) {
                OrderItem item = order.getItems().get(index);
                int before = stockBefore.get(index);
                inventoryService.record(item.getProduct(), before, before - item.getQuantity(),
                    InventoryTransactionType.SALE, user, order, "Bán theo đơn " + order.getCode());
            }
            recordStatus(order, null, OrderStatus.NEW, user, "Khách hàng đặt đơn");
            created.add(order);
            if (order.getPaymentMethod() != PaymentMethod.BANK_TRANSFER) {
                notificationService.send(shop.getOwner(), "Có đơn hàng mới", "Đơn " + order.getCode() + " đang chờ xác nhận.", "/vendor/orders");
            }
        }
        if (voucher != null) {
            voucher.setQuantity(voucher.getQuantity() - 1);
            VoucherUsage usage = new VoucherUsage();
            usage.setUser(user);
            usage.setVoucher(voucher);
            usage.setOrder(created.getFirst());
            usage.setCheckoutReference(checkoutReference);
            usage.setStatus(VoucherUsageStatus.USED);
            voucherUsageRepository.save(usage);
        }
        cartService.removeItems(cart, selectedItems);
        return created;
    }

    @Transactional
    public CheckoutPreview preview(User user, Long carrierId, String voucherCode, List<Long> itemIds) {
        Carrier carrier = carrierRepository.findById(carrierId)
            .filter(Carrier::isActive)
            .orElseThrow(() -> new IllegalArgumentException("Nhà vận chuyển không hợp lệ."));
        Cart cart = cartService.get(user);
        List<CartItem> selectedItems = cartService.selectItems(cart, itemIds, false);
        CartService.CartRepriceResult repricing = cartService.reprice(selectedItems);
        cartService.validateStock(selectedItems);
        Voucher voucher = resolveVoucherForPreview(user, voucherCode);
        CheckoutPreview amounts = calculate(selectedItems, carrier, voucher).preview();
        return new CheckoutPreview(amounts.subtotal(), amounts.productDiscount(), amounts.voucherDiscount(),
            amounts.shippingFee(), amounts.shippingDiscount(), amounts.finalShippingFee(), amounts.totalAmount(),
            amounts.shopCount(), repricing.pricesChanged(), repricing.changedProducts());
    }

    @Transactional
    public CheckoutPreview preview(User user, Long carrierId, String voucherCode) {
        Cart cart = cartService.get(user);
        List<Long> itemIds = cart.getItems().stream().map(CartItem::getId).toList();
        return preview(user, carrierId, voucherCode, itemIds);
    }

    private Voucher resolveVoucher(User user, String code) {
        if (code == null || code.isBlank()) return null;
        Voucher voucher = voucherRepository.findByCodeForCheckout(code.trim())
            .orElseThrow(() -> new IllegalArgumentException("Mã giảm giá không tồn tại."));
        long used = voucherUsageRepository.countByUserIdAndVoucherIdAndStatus(
            user.getId(), voucher.getId(), VoucherUsageStatus.USED);
        if (used >= voucher.getEffectiveMaxUsesPerUser()) {
            throw new IllegalArgumentException("Bạn đã sử dụng hết số lượt cho phép của mã giảm giá này.");
        }
        return voucher;
    }

    private Voucher resolveVoucherForPreview(User user, String code) {
        if (code == null || code.isBlank()) return null;
        Voucher voucher = voucherRepository.findByCodeIgnoreCase(code.trim())
            .orElseThrow(() -> new IllegalArgumentException("Mã giảm giá không tồn tại."));
        long used = voucherUsageRepository.countByUserIdAndVoucherIdAndStatus(
            user.getId(), voucher.getId(), VoucherUsageStatus.USED);
        if (used >= voucher.getEffectiveMaxUsesPerUser()) {
            throw new IllegalArgumentException("Bạn đã sử dụng hết số lượt cho phép của mã giảm giá này.");
        }
        return voucher;
    }

    public List<Voucher> availableVouchers(User user) {
        return voucherRepository.findAvailable(VietnamTime.now()).stream()
            .filter(voucher -> voucherUsageRepository.countByUserIdAndVoucherIdAndStatus(
                user.getId(), voucher.getId(), VoucherUsageStatus.USED)
                < voucher.getEffectiveMaxUsesPerUser())
            .toList();
    }

    @Transactional
    public VoucherRecommendations recommendVouchers(User user, Long carrierId, List<Long> itemIds) {
        Carrier carrier = carrierRepository.findById(carrierId)
            .filter(Carrier::isActive)
            .orElseThrow(() -> new IllegalArgumentException("Nhà vận chuyển không hợp lệ."));
        List<CartItem> items = cartService.selectItems(cartService.get(user), itemIds, false);
        cartService.reprice(items);
        cartService.validateStock(items);
        CheckoutPreview withoutVoucher = calculate(items, carrier, null).preview();
        BigDecimal merchandiseBase = withoutVoucher.subtotal().subtract(withoutVoucher.productDiscount())
            .max(BigDecimal.ZERO);
        List<VoucherRecommendation> candidates = new ArrayList<>();
        for (Voucher voucher : voucherRepository.findAvailable(VietnamTime.now())) {
            long used = voucherUsageRepository.countByUserIdAndVoucherIdAndStatus(
                user.getId(), voucher.getId(), VoucherUsageStatus.USED);
            if (used >= voucher.getEffectiveMaxUsesPerUser()) {
                candidates.add(new VoucherRecommendation(voucher.getCode(), voucher.getName(), voucher.getType(),
                    BigDecimal.ZERO, false, BigDecimal.ZERO,
                    "Bạn đã sử dụng hết số lượt cho phép của mã này."));
                continue;
            }
            BigDecimal minimum = voucher.getMinimumOrder() == null ? BigDecimal.ZERO : voucher.getMinimumOrder();
            BigDecimal missing = minimum.subtract(merchandiseBase).max(BigDecimal.ZERO);
            if (missing.signum() > 0) {
                candidates.add(new VoucherRecommendation(voucher.getCode(), voucher.getName(), voucher.getType(),
                    BigDecimal.ZERO, false, missing, "Cần thêm tiền hàng sau khuyến mãi để đạt mức tối thiểu."));
                continue;
            }
            try {
                CheckoutPreview withVoucher = calculate(items, carrier, voucher).preview();
                BigDecimal savings = withoutVoucher.totalAmount().subtract(withVoucher.totalAmount())
                    .max(BigDecimal.ZERO);
                candidates.add(new VoucherRecommendation(voucher.getCode(), voucher.getName(), voucher.getType(),
                    savings, savings.signum() > 0, BigDecimal.ZERO,
                    savings.signum() > 0 ? "Áp dụng được" : "Giỏ hàng hiện không còn giá trị để áp dụng mã."));
            } catch (IllegalArgumentException ex) {
                candidates.add(new VoucherRecommendation(voucher.getCode(), voucher.getName(), voucher.getType(),
                    BigDecimal.ZERO, false, BigDecimal.ZERO, ex.getMessage()));
            }
        }
        candidates.sort(Comparator.comparing(VoucherRecommendation::applicable).reversed()
            .thenComparing(VoucherRecommendation::savings, Comparator.reverseOrder())
            .thenComparing(VoucherRecommendation::code));
        String bestCode = candidates.stream().filter(VoucherRecommendation::applicable)
            .map(VoucherRecommendation::code).findFirst().orElse(null);
        return new VoucherRecommendations(candidates, bestCode);
    }

    public record VoucherRecommendation(String code, String name, VoucherType type, BigDecimal savings,
                                        boolean applicable, BigDecimal missingAmount, String condition) {}

    public record VoucherRecommendations(List<VoucherRecommendation> vouchers, String bestCode) {}

    private VoucherBenefits voucherBenefits(Voucher voucher, BigDecimal merchandiseAfterPromotion,
                                            BigDecimal shippingFee) {
        if (voucher == null) return new VoucherBenefits(BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal discount = voucher.calculateDiscount(merchandiseAfterPromotion, shippingFee);
        return voucher.getType() == VoucherType.SHIPPING
            ? new VoucherBenefits(BigDecimal.ZERO, discount)
            : new VoucherBenefits(discount, BigDecimal.ZERO);
    }

    private CheckoutCalculation calculate(List<CartItem> items, Carrier carrier, Voucher voucher) {
        Map<Shop, List<CartItem>> byShop = items.stream()
            .collect(Collectors.groupingBy(item -> item.getProduct().getShop(), LinkedHashMap::new, Collectors.toList()));
        Map<Shop, PromotionDiscount> promotions = new LinkedHashMap<>();
        Map<Shop, BigDecimal> subtotals = new LinkedHashMap<>();
        Map<Shop, BigDecimal> merchandiseAfterPromotion = new LinkedHashMap<>();
        Map<Shop, BigDecimal> shippingAfterPromotion = new LinkedHashMap<>();
        for (var entry : byShop.entrySet()) {
            BigDecimal subtotal = entry.getValue().stream().map(CartItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            PromotionDiscount promotion = promotionDiscount(entry.getKey(), entry.getValue(), carrier.getShippingFee());
            promotions.put(entry.getKey(), promotion);
            subtotals.put(entry.getKey(), subtotal);
            merchandiseAfterPromotion.put(entry.getKey(),
                subtotal.subtract(promotion.productDiscount()).max(BigDecimal.ZERO));
            shippingAfterPromotion.put(entry.getKey(), carrier.getShippingFee()
                .subtract(promotion.shippingDiscount()).max(BigDecimal.ZERO));
        }
        BigDecimal subtotal = subtotals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal voucherMerchandiseBase = merchandiseAfterPromotion.values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (voucher != null && !voucher.canApply(voucherMerchandiseBase, VietnamTime.now())) {
            throw new IllegalArgumentException("Mã giảm giá không còn hiệu lực hoặc chưa đủ giá trị tối thiểu.");
        }
        BigDecimal shippingBeforeVoucher = shippingAfterPromotion.values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        VoucherBenefits benefits = voucherBenefits(voucher, voucherMerchandiseBase, shippingBeforeVoucher);
        if (voucher != null && benefits.productDiscount().signum() == 0
            && benefits.shippingDiscount().signum() == 0) {
            throw new IllegalArgumentException("Mã giảm giá không còn giá trị áp dụng cho giỏ hàng này.");
        }
        Map<Shop, BigDecimal> productVoucherShares = allocate(benefits.productDiscount(), merchandiseAfterPromotion);
        Map<Shop, BigDecimal> shippingVoucherShares = allocate(benefits.shippingDiscount(), shippingAfterPromotion);

        Map<Shop, ShopAmounts> shopAmounts = new LinkedHashMap<>();
        BigDecimal productDiscountTotal = BigDecimal.ZERO;
        BigDecimal voucherDiscountTotal = BigDecimal.ZERO;
        BigDecimal shippingFeeTotal = BigDecimal.ZERO;
        BigDecimal shippingDiscountTotal = BigDecimal.ZERO;
        BigDecimal finalShippingTotal = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (Shop shop : byShop.keySet()) {
            BigDecimal shopSubtotal = subtotals.get(shop);
            PromotionDiscount promotion = promotions.get(shop);
            BigDecimal productDiscount = promotion.productDiscount().min(shopSubtotal);
            BigDecimal voucherDiscount = productVoucherShares.getOrDefault(shop, BigDecimal.ZERO)
                .min(merchandiseAfterPromotion.get(shop));
            BigDecimal shippingDiscount = promotion.shippingDiscount()
                .add(shippingVoucherShares.getOrDefault(shop, BigDecimal.ZERO)).min(carrier.getShippingFee());
            BigDecimal finalShipping = carrier.getShippingFee().subtract(shippingDiscount).max(BigDecimal.ZERO);
            BigDecimal shopTotal = shopSubtotal.subtract(productDiscount).subtract(voucherDiscount)
                .add(finalShipping).max(BigDecimal.ZERO);
            shopAmounts.put(shop, new ShopAmounts(shopSubtotal, productDiscount, voucherDiscount,
                shippingDiscount, finalShipping, shopTotal));
            productDiscountTotal = productDiscountTotal.add(productDiscount);
            voucherDiscountTotal = voucherDiscountTotal.add(voucherDiscount);
            shippingFeeTotal = shippingFeeTotal.add(carrier.getShippingFee());
            shippingDiscountTotal = shippingDiscountTotal.add(shippingDiscount);
            finalShippingTotal = finalShippingTotal.add(finalShipping);
            total = total.add(shopTotal);
        }
        CheckoutPreview preview = new CheckoutPreview(subtotal, productDiscountTotal, voucherDiscountTotal,
            shippingFeeTotal, shippingDiscountTotal, finalShippingTotal, total, byShop.size(), false, List.of());
        return new CheckoutCalculation(byShop, shopAmounts, preview);
    }

    private <K> Map<K, BigDecimal> allocate(BigDecimal totalDiscount, Map<K, BigDecimal> bases) {
        Map<K, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal totalBase = bases.values().stream().map(value -> value.max(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal safeTotal = totalDiscount == null ? BigDecimal.ZERO
            : totalDiscount.max(BigDecimal.ZERO).min(totalBase);
        List<Map.Entry<K, BigDecimal>> eligible = bases.entrySet().stream()
            .filter(entry -> entry.getValue().compareTo(BigDecimal.ZERO) > 0).toList();
        BigDecimal remaining = safeTotal;
        for (int i = 0; i < eligible.size(); i++) {
            var entry = eligible.get(i);
            BigDecimal share = i == eligible.size() - 1 ? remaining
                : safeTotal.multiply(entry.getValue()).divide(totalBase, 2, RoundingMode.HALF_UP);
            share = share.min(entry.getValue()).min(remaining).max(BigDecimal.ZERO);
            result.put(entry.getKey(), share);
            remaining = remaining.subtract(share);
        }
        if (remaining.signum() > 0) {
            for (var entry : eligible) {
                BigDecimal capacity = entry.getValue().subtract(result.get(entry.getKey()));
                BigDecimal extra = capacity.min(remaining);
                result.put(entry.getKey(), result.get(entry.getKey()).add(extra));
                remaining = remaining.subtract(extra);
                if (remaining.signum() == 0) break;
            }
        }
        return result;
    }

    private PromotionDiscount promotionDiscount(Shop shop, List<CartItem> lines, BigDecimal shippingFee) {
        List<Long> ids = lines.stream().map(line -> line.getProduct().getId()).toList();
        List<Promotion> promotions = promotionRepository.findApplicable(shop.getId(), ids, VietnamTime.now());
        BigDecimal productDiscount = BigDecimal.ZERO;
        for (CartItem line : lines) {
            BigDecimal discountPerUnit = productPricingService.productDiscountPerUnit(
                line.getProduct(), line.getBasePrice(), promotions);
            productDiscount = productDiscount.add(discountPerUnit.multiply(BigDecimal.valueOf(line.getQuantity())));
        }
        BigDecimal shippingDiscount = promotions.stream().filter(p -> p.getType() == PromotionType.SHIPPING_DISCOUNT)
            .map(Promotion::getDiscountValue).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO).min(shippingFee);
        return new PromotionDiscount(productDiscount, shippingDiscount);
    }

    public Page<CustomerOrder> forUser(User user, OrderStatus status, int page) {
        return orderRepository.findForUser(user.getId(), status, PageRequest.of(Math.max(page, 0), 15, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    public Page<CustomerOrder> forShop(Shop shop, OrderStatus status, int page) {
        return forShop(shop, status, null, page);
    }

    public Page<CustomerOrder> forShop(Shop shop, OrderStatus status, String query, int page) {
        return forShop(shop, status, query, false, page);
    }

    public Page<CustomerOrder> forShop(Shop shop, OrderStatus status, String query, boolean pending, int page) {
        String q = query == null || query.isBlank() ? null : query.strip();
        return orderRepository.findForShopSearch(shop.getId(), status, pending,
            OrderStatus.workQueueStatuses(), q,
            PageRequest.of(Math.max(page, 0), 15, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    public Page<CustomerOrder> forShipper(User shipper, OrderStatus status, int page) {
        return forShipper(shipper, status, null, page);
    }

    public Page<CustomerOrder> forShipper(User shipper, OrderStatus status, String query, int page) {
        return forShipper(shipper, status, query, page, "recent");
    }

    public Page<CustomerOrder> forShipper(User shipper, OrderStatus status, String query, int page, String sort) {
        String q = query == null || query.isBlank() ? null : query.strip();
        if (q != null) {
            q = q.substring(0, Math.min(q.length(), 80)).replace("!", "!!").replace("%", "!%")
                .replace("_", "!_");
        }
        return orderRepository.findForShipper(shipper.getId(), status, q, OrderStatus.DELIVERY_FAILED,
            "oldest".equals(sort),
            PageRequest.of(Math.max(page, 0), 15));
    }

    public CustomerOrder getForUser(User user, Long id) {
        CustomerOrder order = orderRepository.findDetailedById(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."));
        boolean managerAllowed = user.getRole() == Role.MANAGER
            && managerShopRepository.existsByManagerIdAndShopId(user.getId(), order.getShop().getId());
        boolean allowed = order.getUser().getId().equals(user.getId())
            || order.getShop().getOwner().getId().equals(user.getId())
            || (order.getShipper() != null && order.getShipper().getId().equals(user.getId()))
            || user.getRole() == Role.ADMIN || managerAllowed;
        if (!allowed) throw new IllegalArgumentException("Bạn không có quyền xem đơn hàng này.");
        return order;
    }

    @Transactional(readOnly = true)
    public CustomerOrder getForShop(User vendor, Long id) {
        CustomerOrder order = orderRepository.findDetailedById(id)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng."));
        if (vendor.getRole() != Role.VENDOR || !order.getShop().getOwner().getId().equals(vendor.getId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng.");
        }
        return order;
    }

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

    @Transactional(readOnly = true)
    public PaymentTransaction displayTransactionForOrder(Long orderId) {
        return paymentTransactionRepository.findFirstByOrderIdAndStatusOrderByPaidAtDescIdDesc(
                orderId, PaymentTransactionStatus.SUCCESS)
            .or(() -> paymentTransactionRepository.findFirstByOrderIdOrderByCreatedAtDescIdDesc(orderId))
            .orElse(null);
    }

    private static boolean isOnlinePayment(CustomerOrder order) {
        return order.getPaymentMethod() == PaymentMethod.VNPAY || order.getPaymentMethod() == PaymentMethod.MOMO
            || order.getPaymentMethod() == PaymentMethod.BANK_TRANSFER;
    }

    public List<OrderStatusHistory> statusHistory(CustomerOrder order) {
        return orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAscIdAsc(order.getId());
    }

    public String cancellationBlockReason(CustomerOrder order) {
        String reason = cancellationBlockReason(order, VietnamTime.now());
        if (reason != null) return reason;
        if (order.getPaymentMethod() == PaymentMethod.BANK_TRANSFER
            && paymentTransactionRepository.findFirstByOrderIdOrderByCreatedAtDescIdDesc(order.getId())
                .filter(t -> t.getStatus() == PaymentTransactionStatus.AWAITING_REVIEW).isPresent()) {
            return "Đơn đang chờ Admin kiểm tra tiền chuyển khoản. Vui lòng liên hệ Admin nếu cần hủy.";
        }
        return null;
    }

    public static String cancellationBlockReason(CustomerOrder order, LocalDateTime now) {
        if (order.getStatus() != OrderStatus.NEW) {
            return "Đơn hàng đã chuyển sang trạng thái " + order.getStatus().getLabel()
                + ", chỉ có thể hủy trước khi cửa hàng xác nhận.";
        }
        if (isOnlinePayment(order) && order.getPaymentStatus() == PaymentStatus.PAID) {
            return "Đơn đã thanh toán trực tuyến không thể hủy trực tiếp. Vui lòng yêu cầu hoàn tiền.";
        }
        if (order.getCreatedAt() == null || !now.isBefore(order.getCreatedAt().plusHours(1))) {
            return "Đã quá 1 giờ kể từ khi đặt hàng nên bạn không thể hủy đơn này.";
        }
        return null;
    }

    @Transactional
    public void cancelByUser(User user, Long id) {
        CustomerOrder currentOrder = orderRepository.findByIdAndUserId(id, user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."));
        if (currentOrder.getPaymentMethod() == PaymentMethod.BANK_TRANSFER) {
            cancelBankTransferGroup(user, currentOrder);
            return;
        }
        CustomerOrder order = orderRepository.findDetailedByIdForUpdate(id)
            .filter(candidate -> candidate.getUser().getId().equals(user.getId()))
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."));
        entityManager.refresh(order);
        String blockReason = cancellationBlockReason(order);
        if (blockReason != null) throw new IllegalArgumentException(blockReason);
        paymentTransactionRepository.findFirstByOrderIdOrderByCreatedAtDescIdDesc(id)
            .filter(transaction -> transaction.getStatus() == PaymentTransactionStatus.PENDING)
            .ifPresent(transaction -> transaction.setStatus(PaymentTransactionStatus.CANCELLED));
        applyStatus(order, OrderStatus.CANCELLED, user, "Khách hàng hủy đơn");
    }

    private void cancelBankTransferGroup(User user, CustomerOrder candidate) {
        PaymentTransaction selected = paymentTransactionRepository.findFirstByOrderIdOrderByCreatedAtDescIdDesc(candidate.getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy giao dịch chuyển khoản."));
        List<PaymentTransaction> payments = paymentTransactionRepository.findByCodeForUpdate(selected.getTransactionCode());
        if (payments.isEmpty() || payments.stream().anyMatch(payment ->
            !payment.getOrder().getUser().getId().equals(user.getId())
                || payment.getOrder().getPaymentMethod() != PaymentMethod.BANK_TRANSFER
                || payment.getStatus() != PaymentTransactionStatus.PENDING)) {
            throw new IllegalArgumentException("Lần chuyển khoản đang chờ kiểm tra hoặc đã kết thúc; không thể tự hủy.");
        }
        List<CustomerOrder> groupedOrders = payments.stream()
            .map(payment -> orderRepository.findDetailedByIdForUpdate(payment.getOrder().getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."))).toList();
        for (CustomerOrder order : groupedOrders) {
            String reason = cancellationBlockReason(order, VietnamTime.now());
            if (reason != null) throw new IllegalArgumentException(reason);
        }
        for (PaymentTransaction payment : payments) {
            if (!payOsClient.isPayOs(payment)) continue;
            try {
                payOsClient.cancel(payment);
            } catch (IllegalStateException ex) {
                throw new IllegalArgumentException("Không thể hủy link thanh toán payOS lúc này. Vui lòng thử lại sau.", ex);
            }
        }
        payments.forEach(payment -> payment.setStatus(PaymentTransactionStatus.CANCELLED));
        groupedOrders.forEach(order -> applyStatus(order, OrderStatus.CANCELLED, user, "Khách hàng hủy lần chuyển khoản"));
    }

    @Transactional
    public boolean expireUnpaidOnlineOrder(Long orderId) {
        CustomerOrder order = orderRepository.findDetailedByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.NEW
            || !isOnlinePayment(order)
            || order.getPaymentStatus() != PaymentStatus.UNPAID) {
            return false;
        }
        applyStatus(order, OrderStatus.CANCELLED, order.getUser(),
            "Hệ thống tự động hủy do thanh toán trực tuyến đã hết hạn");
        return true;
    }

    @Transactional
    public void vendorUpdate(User vendor, Long id, OrderStatus target) {
        if (vendor.getRole() != Role.VENDOR) {
            throw new IllegalArgumentException("Chỉ chủ shop được cập nhật các bước xử lý hàng hóa.");
        }
        CustomerOrder order = orderRepository.findDetailedByIdForUpdate(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."));
        entityManager.refresh(order);
        applyVendorUpdate(vendor, order, target);
    }

    private void applyVendorUpdate(User vendor, CustomerOrder order, OrderStatus target) {
        Shop shop = order.getShop();
        if (vendor.getRole() != Role.VENDOR || !shop.getOwner().getId().equals(vendor.getId())) {
            throw new IllegalArgumentException("Đơn hàng không thuộc cửa hàng của bạn.");
        }
        if (isOnlinePayment(order)
            && order.getPaymentStatus() != PaymentStatus.PAID
            && (target != OrderStatus.CANCELLED
                || order.getPaymentMethod() == PaymentMethod.BANK_TRANSFER && vendor.getRole() != Role.ADMIN)) {
            throw new IllegalArgumentException("Đơn thanh toán trực tuyến chưa được thanh toán nên chưa thể xử lý.");
        }
        if (isOnlinePayment(order)
            && order.getPaymentStatus() == PaymentStatus.PAID && target == OrderStatus.CANCELLED) {
            throw new IllegalArgumentException("Đơn đã thanh toán trực tuyến phải đi qua quy trình hoàn tiền.");
        }
        ensureTransition(order.getStatus(), target, false);
        applyStatus(order, target, vendor, "Cập nhật bởi " + vendor.getRole().getLabel());
        if (target == OrderStatus.READY_FOR_PICKUP && order.getShipper() != null) {
            notificationService.send(order.getShipper(), "Đơn hàng đã sẵn sàng",
                "Đơn " + order.getCode() + " đã sẵn sàng để lấy tại " + shop.getName() + ".", "/shipper");
        }
    }

    @Transactional
    public void shipperUpdate(User shipper, Long id, OrderStatus target) {
        shipperUpdate(shipper, id, target, null, null);
    }

    @Transactional
    public void respondToShipperAssignment(User shipper, Long id, boolean accept, String rejectionReason) {
        if (shipper.getRole() != Role.SHIPPER) {
            throw new IllegalArgumentException("Chỉ shipper được phản hồi phân công giao hàng.");
        }
        CustomerOrder order = orderRepository.findDetailedByIdForUpdate(id)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng được phân công."));
        if (order.getShipper() == null || !order.getShipper().getId().equals(shipper.getId())
            || !isShipperAcceptancePending(order)) {
            throw new IllegalArgumentException("Phân công này không còn chờ phản hồi của bạn.");
        }
        if (accept) {
            order.setShipperAssignmentStatus(ShipperAssignmentStatus.ACCEPTED);
            order.setShipperRejectionReason(null);
            auditLogService.record(AuditAction.SHIPPER_ORDER_STATUS, shipper, "ORDER", order.getId(),
                order.getCode(), "Chờ chấp nhận", "Đã chấp nhận", "Shipper chấp nhận phân công");
            return;
        }
        if (order.getStatus() == OrderStatus.PICKED_UP && order.getReturnedToShopAt() == null) {
            throw new IllegalArgumentException("Bạn đang giữ hàng. Vui lòng xác nhận đã trả hàng về shop trước khi từ chối giao lại.");
        }
        String reason = rejectionReason == null ? "" : rejectionReason.trim();
        if (reason.length() < 5 || reason.length() > 500) {
            throw new IllegalArgumentException("Lý do từ chối phải từ 5 đến 500 ký tự.");
        }
        order.setShipper(null);
        order.setShipperAssignmentStatus(ShipperAssignmentStatus.REJECTED);
        order.setShipperRejectionReason(reason);
        auditLogService.record(AuditAction.SHIPPER_ORDER_STATUS, shipper, "ORDER", order.getId(),
            order.getCode(), "Chờ chấp nhận", "Đã từ chối", reason);
        notifyDispatchers(order, "Shipper từ chối đơn giao", "Shipper " + shipper.getFullName()
            + " từ chối đơn " + order.getCode() + ". Lý do: " + reason + ". Vui lòng phân công lại.");
    }

    @Transactional
    public void checkPendingShipperAssignments() {
        LocalDateTime now = VietnamTime.now();
        int reminderMinutes = Math.max(1, assignmentReminderMinutes);
        int timeoutMinutes = Math.max(reminderMinutes + 1, assignmentTimeoutMinutes);
        List<OrderStatus> activeStatuses = List.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING,
            OrderStatus.READY_FOR_PICKUP, OrderStatus.PICKED_UP);
        for (Long id : orderRepository.findPendingShipperAssignmentIds(activeStatuses, ShipperAssignmentStatus.PENDING)) {
            CustomerOrder order = orderRepository.findDetailedByIdForUpdate(id).orElse(null);
            if (order == null || order.getShipper() == null || !isShipperAcceptancePending(order)) continue;
            if (order.getShipperAssignedAt() == null) {
                order.setShipperAssignedAt(now);
                order.setShipperAssignmentStatus(ShipperAssignmentStatus.PENDING);
                continue;
            }
            if (!now.isBefore(order.getShipperAssignedAt().plusMinutes(timeoutMinutes))) {
                User shipper = order.getShipper();
                order.setShipperAssignmentStatus(ShipperAssignmentStatus.EXPIRED);
                order.setShipperRejectionReason("Shipper không phản hồi trong " + timeoutMinutes + " phút.");
                if (order.getStatus() == OrderStatus.PICKED_UP) {
                    notificationService.send(shipper, "Phân công giao lại đã hết hạn",
                        "Đơn " + order.getCode() + " đã hết hạn phản hồi. Vui lòng trả hàng về shop.", "/shipper");
                    notifyDispatchers(order, "Shipper chưa phản hồi phân công",
                        "Đơn " + order.getCode() + " quá hạn khi shipper đang giữ hàng. Cần xác nhận hàng về shop trước khi phân công lại.");
                } else {
                    order.setShipper(null);
                    notificationService.send(shipper, "Phân công giao hàng đã hết hạn",
                        "Đơn " + order.getCode() + " đã được đưa về danh sách chờ phân công.", "/shipper");
                    notifyDispatchers(order, "Shipper chưa phản hồi phân công",
                        "Đơn " + order.getCode() + " đã quá " + timeoutMinutes + " phút chờ shipper chấp nhận. Vui lòng phân công lại.");
                }
            } else if (order.getShipperReminderSentAt() == null
                && !now.isBefore(order.getShipperAssignedAt().plusMinutes(reminderMinutes))) {
                order.setShipperReminderSentAt(now);
                notificationService.send(order.getShipper(), "Nhắc phản hồi đơn giao",
                    "Vui lòng chấp nhận hoặc từ chối đơn " + order.getCode() + " trước khi phân công hết hạn.", "/shipper");
            }
        }
    }

    private void notifyDispatchers(CustomerOrder order, String title, String message) {
        Map<Long, User> recipients = new LinkedHashMap<>();
        userRepository.findByRoleAndEnabledTrueAndLockedFalseOrderByFullNameAsc(Role.ADMIN)
            .forEach(admin -> recipients.put(admin.getId(), admin));
        managerShopRepository.findByShopId(order.getShop().getId()).stream()
            .map(ManagerShop::getManager)
            .filter(manager -> manager.isEnabled() && !manager.isLocked() && manager.getRole() == Role.MANAGER)
            .forEach(manager -> recipients.put(manager.getId(), manager));
        recipients.values().forEach(recipient -> notificationService.send(recipient, title, message, "/management/orders"));
    }

    @Transactional
    public void shipperUpdate(User shipper, Long id, OrderStatus target, String failureReason,
                              MultipartFile deliveryProof) {
        shipperUpdate(shipper, id, target, failureReason, deliveryProof, false);
    }

    @Transactional
    public void shipperUpdate(User shipper, Long id, OrderStatus target, String failureReason,
                              MultipartFile deliveryProof, boolean codCollected) {
        if (shipper.getRole() != Role.SHIPPER) {
            throw new IllegalArgumentException("Chỉ nhân viên giao hàng mới được cập nhật đơn giao.");
        }
        CustomerOrder order = orderRepository.findDetailedByIdForUpdate(id)
            .orElseThrow(() -> new IllegalArgumentException("Đơn hàng chưa được phân công cho bạn."));
        if (order.getShipper() == null || !order.getShipper().getId().equals(shipper.getId())) {
            throw new IllegalArgumentException("Đơn hàng chưa được phân công cho bạn.");
        }
        if (isShipperAcceptancePending(order)) {
            throw new IllegalArgumentException("Vui lòng chấp nhận đơn trước khi cập nhật giao hàng.");
        }
        if (order.getShipperAssignmentStatus() == ShipperAssignmentStatus.EXPIRED) {
            throw new IllegalArgumentException("Phân công đã hết hạn. Vui lòng trả hàng về shop.");
        }
        ensureTransition(order.getStatus(), target, true);

        String note = "Cập nhật bởi nhân viên giao hàng";
        if (target == OrderStatus.PICKED_UP) {
            order.setPickedUpAt(VietnamTime.now());
            order.setReturnedToShopAt(null);
        } else if (target == OrderStatus.DELIVERED) {
            validateDeliveryProof(deliveryProof, true);
            if (order.getPaymentMethod() == PaymentMethod.COD) {
                if (!codCollected) throw new IllegalArgumentException("Vui lòng xác nhận đã thu tiền COD trước khi báo giao thành công.");
                order.setCodCollectedAt(VietnamTime.now());
            }
            String previousProof = order.getDeliveryProofUrl();
            order.setDeliveryProofUrl(mediaStorageService.upload(deliveryProof, "delivery-proofs"));
            mediaStorageService.deleteAfterCommit(previousProof);
            order.setDeliveredAt(VietnamTime.now());
            order.setFailureReason(null);
            note = "Shipper xác nhận giao thành công và đã tải bằng chứng giao hàng";
        } else if (target == OrderStatus.DELIVERY_FAILED) {
            order.setReturnedToShopAt(null);
            String reason = failureReason == null ? "" : failureReason.trim();
            if (reason.length() < 5 || reason.length() > 500) {
                throw new IllegalArgumentException("Lý do giao thất bại phải từ 5 đến 500 ký tự.");
            }
            validateDeliveryProof(deliveryProof, false);
            if (deliveryProof != null && !deliveryProof.isEmpty()) {
                String previousProof = order.getDeliveryProofUrl();
                order.setDeliveryProofUrl(mediaStorageService.upload(deliveryProof, "delivery-proofs"));
                mediaStorageService.deleteAfterCommit(previousProof);
            }
            order.setFailureReason(reason);
            note = reason;
        }
        applyStatus(order, target, shipper, note);
    }

    private void validateDeliveryProof(MultipartFile proof, boolean required) {
        if (proof == null || proof.isEmpty()) {
            if (required) throw new IllegalArgumentException("Vui lòng tải ảnh bằng chứng giao hàng.");
            return;
        }
        mediaValidationService.validate(proof, "delivery-proofs");
    }

    @Transactional
    public void assignShipper(User actor, Long orderId, Long shipperId) {
        CustomerOrder order = orderRepository.findDetailedByIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."));
        requireManagementAccess(actor, order);
        if (!canAssignShipper(order.getStatus())) {
            throw new IllegalArgumentException("Chỉ được phân công shipper khi đơn đã xác nhận, đang chuẩn bị hoặc sẵn sàng lấy hàng.");
        }
        if (order.getShipper() != null && order.getShipperAssignmentStatus() == ShipperAssignmentStatus.ACCEPTED) {
            throw new IllegalArgumentException("Shipper đã chấp nhận đơn, không thể đổi shipper.");
        }
        User shipper = userRepository.findById(shipperId)
            .filter(user -> user.getRole() == Role.SHIPPER && user.isEnabled() && !user.isLocked())
            .orElseThrow(() -> new IllegalArgumentException("Shipper không hợp lệ."));
        User previousShipper = order.getShipper();
        if (previousShipper != null && previousShipper.getId().equals(shipper.getId())) {
            throw new IllegalArgumentException("Shipper này đã được phân công cho đơn hàng.");
        }
        order.setShipper(shipper);
        order.setShipperAssignmentStatus(ShipperAssignmentStatus.PENDING);
        order.setShipperRejectionReason(null);
        order.setShipperAssignedAt(VietnamTime.now());
        order.setShipperReminderSentAt(null);
        auditLogService.record(AuditAction.SHIPPER_ASSIGNED, actor, "ORDER", order.getId(), order.getCode(),
            previousShipper == null ? null : previousShipper.getUsername(), shipper.getUsername(), null);
        if (previousShipper != null) {
            notificationService.send(previousShipper, "Phân công giao hàng đã thay đổi",
                "Đơn " + order.getCode() + " đã được chuyển cho shipper khác. Bạn không cần giao đơn này.", "/shipper");
        }
        notificationService.send(shipper, "Đơn hàng được phân công", "Bạn được phân công giao đơn " + order.getCode(), "/shipper");
    }

    @Transactional
    public void retryFailedDelivery(User actor, Long orderId, Long shipperId) {
        CustomerOrder order = orderRepository.findDetailedByIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."));
        requireManagementAccess(actor, order);
        if (order.getStatus() != OrderStatus.DELIVERY_FAILED) {
            throw new IllegalArgumentException("Chỉ đơn giao thất bại mới có thể điều phối giao lại.");
        }
        User shipper = userRepository.findById(shipperId)
            .filter(user -> user.getRole() == Role.SHIPPER && user.isEnabled() && !user.isLocked())
            .orElseThrow(() -> new IllegalArgumentException("Shipper không hợp lệ."));
        User previousShipper = order.getShipper();
        boolean sameShipper = previousShipper != null && previousShipper.getId().equals(shipper.getId());
        boolean returnedToShop = order.getReturnedToShopAt() != null;
        if (!sameShipper && !returnedToShop) {
            throw new IllegalArgumentException("Hàng chưa được xác nhận trả về shop, chưa thể giao cho shipper khác.");
        }

        archiveFailedDeliveryProof(order, actor);
        order.setFailureReason(null);
        order.setDeliveryProofUrl(null);

        order.setShipper(shipper);
        order.setShipperAssignmentStatus(ShipperAssignmentStatus.PENDING);
        order.setShipperRejectionReason(null);
        order.setShipperAssignedAt(VietnamTime.now());
        order.setShipperReminderSentAt(null);
        if (returnedToShop) order.setPickedUpAt(null);
        order.setDeliveredAt(null);
        order.setReturnedToShopAt(null);
        auditLogService.record(AuditAction.SHIPPER_ASSIGNED, actor, "ORDER", order.getId(), order.getCode(),
            previousShipper == null ? null : previousShipper.getUsername(), shipper.getUsername(), "Điều phối giao lại");
        OrderStatus nextStatus = returnedToShop ? OrderStatus.READY_FOR_PICKUP : OrderStatus.PICKED_UP;
        applyStatus(order, nextStatus, actor,
            "Manager/Admin điều phối giao lại cho " + shipper.getFullName()
                + (returnedToShop ? "; hàng đã về shop" : "; shipper đang giữ hàng"));
        if (previousShipper != null && !previousShipper.getId().equals(shipper.getId())) {
            notificationService.send(previousShipper, "Phân công giao hàng đã thay đổi",
                "Đơn " + order.getCode() + " đã được chuyển cho shipper khác để giao lại.", "/shipper");
        }
        notificationService.send(shipper, "Đơn giao lại được phân công",
            "Bạn được phân công giao lại đơn " + order.getCode() + ".", "/shipper");
    }

    @Transactional
    public void confirmReturnedToShop(User actor, Long orderId) {
        CustomerOrder order = orderRepository.findDetailedByIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."));
        boolean assignedShipper = actor.getRole() == Role.SHIPPER && order.getShipper() != null
            && order.getShipper().getId().equals(actor.getId());
        if (!assignedShipper) requireManagementAccess(actor, order);
        boolean failedDelivery = order.getStatus() == OrderStatus.DELIVERY_FAILED;
        boolean directRetry = order.getStatus() == OrderStatus.PICKED_UP
            && (order.getShipperAssignmentStatus() == ShipperAssignmentStatus.PENDING
                || order.getShipperAssignmentStatus() == ShipperAssignmentStatus.EXPIRED);
        if ((!failedDelivery && !directRetry) || order.getReturnedToShopAt() != null) {
            throw new IllegalArgumentException("Chỉ xác nhận hàng về shop một lần sau khi giao thất bại.");
        }
        order.setReturnedToShopAt(VietnamTime.now());
        auditLogService.record(AuditAction.ORDER_STATUS_CHANGED, actor, "ORDER", order.getId(), order.getCode(),
            "Hàng chưa về shop", "Hàng đã về shop", "Xác nhận kiện hàng đã trả về shop");
        if (directRetry) {
            order.setPickedUpAt(null);
            if (order.getShipperAssignmentStatus() == ShipperAssignmentStatus.EXPIRED) order.setShipper(null);
            applyStatus(order, OrderStatus.READY_FOR_PICKUP, actor,
                "Hàng giao lại đã về shop; chờ shipper chấp nhận hoặc phân công lại");
        }
        if (assignedShipper) {
            notifyDispatchers(order, "Hàng giao thất bại đã về shop",
                "Đơn " + order.getCode() + " đã được shipper xác nhận trả về shop; có thể phân công giao lại.");
        }
    }

    private void archiveFailedDeliveryProof(CustomerOrder order, User actor) {
        if (order.getDeliveryProofUrl() == null) return;
        List<OrderStatusHistory> failures = orderStatusHistoryRepository
            .findByOrderIdOrderByChangedAtAscIdAsc(order.getId()).stream()
            .filter(history -> history.getNewStatus() == OrderStatus.DELIVERY_FAILED).toList();
        OrderStatusHistory history;
        if (failures.isEmpty()) {
            history = recordStatus(order, OrderStatus.SHIPPING, OrderStatus.DELIVERY_FAILED, actor,
                order.getFailureReason() == null ? "Lần giao thất bại trước khi lưu lịch sử" : order.getFailureReason());
            history.setChangedAt(order.getUpdatedAt());
        } else {
            history = failures.getLast();
        }
        if (history.getDeliveryProofUrl() == null) history.setDeliveryProofUrl(order.getDeliveryProofUrl());
    }

    @Transactional
    public void managementUpdate(User actor, Long orderId, OrderStatus target) {
        CustomerOrder order = orderRepository.findDetailedByIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng."));
        requireManagementAccess(actor, order);
        if (!managementTransitions(order.getStatus()).contains(target)) {
            throw new IllegalArgumentException("Không thể chuyển từ " + order.getStatus().getLabel()
                + " sang " + target.getLabel() + ".");
        }
        if (order.getStatus() != OrderStatus.DELIVERY_FAILED) {
            if (isOnlinePayment(order) && order.getPaymentMethod() == PaymentMethod.BANK_TRANSFER
                && order.getPaymentStatus() != PaymentStatus.PAID && actor.getRole() != Role.ADMIN) {
                throw new IllegalArgumentException("Đơn thanh toán trực tuyến chưa được thanh toán nên chưa thể xử lý.");
            }
            if (isOnlinePayment(order) && order.getPaymentStatus() == PaymentStatus.PAID) {
                throw new IllegalArgumentException("Đơn đã thanh toán trực tuyến phải đi qua quy trình hoàn tiền.");
            }
            applyStatus(order, OrderStatus.CANCELLED, actor, "Hủy đơn bởi " + actor.getRole().getLabel());
            return;
        }
        if (target == OrderStatus.CANCELLED && order.getReturnedToShopAt() == null) {
            throw new IllegalArgumentException("Cần xác nhận kiện hàng đã về shop trước khi hủy và hoàn tồn kho.");
        }
        if (target == OrderStatus.CANCELLED && isOnlinePayment(order)
            && order.getPaymentStatus() == PaymentStatus.PAID) {
            throw new IllegalArgumentException("Đơn đã thanh toán trực tuyến phải hoàn tiền trước khi hủy.");
        }
        applyStatus(order, target, actor, target == OrderStatus.CANCELLED
            ? "Manager/Admin hủy đơn sau khi giao thất bại"
            : "Cập nhật bởi " + actor.getRole().getLabel());
    }

    private void requireManagementAccess(User actor, CustomerOrder order) {
        boolean managerAllowed = actor.getRole() == Role.MANAGER
            && managerShopRepository.existsByManagerIdAndShopId(actor.getId(), order.getShop().getId());
        if (actor.getRole() != Role.ADMIN && !managerAllowed) {
            throw new IllegalArgumentException("Bạn không được điều phối đơn hàng của cửa hàng này.");
        }
    }

    private void ensureTransition(OrderStatus current, OrderStatus target, boolean shipper) {
        boolean allowed = shipper
            ? shipperTransitions(current).contains(target)
            : vendorTransitions(current).contains(target);
        if (!allowed) throw new IllegalArgumentException("Không thể chuyển từ " + current.getLabel() + " sang " + target.getLabel() + ".");
    }

    public List<OrderStatus> vendorTransitions(OrderStatus current) {
        return switch (current) {
            case NEW -> List.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED);
            case CONFIRMED -> List.of(OrderStatus.PREPARING, OrderStatus.CANCELLED);
            case PREPARING -> List.of(OrderStatus.READY_FOR_PICKUP, OrderStatus.CANCELLED);
            case RETURN_REQUESTED -> List.of();
            default -> List.of();
        };
    }

    public List<OrderStatus> managementTransitions(OrderStatus current) {
        return switch (current) {
            case NEW, CONFIRMED, PREPARING, DELIVERY_FAILED -> List.of(OrderStatus.CANCELLED);
            default -> List.of();
        };
    }

    public List<OrderStatus> shipperTransitions(OrderStatus current) {
        return switch (current) {
            case READY_FOR_PICKUP -> List.of(OrderStatus.PICKED_UP);
            case PICKED_UP -> List.of(OrderStatus.SHIPPING);
            case SHIPPING -> List.of(OrderStatus.DELIVERED, OrderStatus.DELIVERY_FAILED);
            default -> List.of();
        };
    }

    public boolean canAssignShipper(OrderStatus status) {
        return status == OrderStatus.CONFIRMED || status == OrderStatus.PREPARING
            || status == OrderStatus.READY_FOR_PICKUP;
    }

    private boolean isShipperAcceptancePending(CustomerOrder order) {
        ShipperAssignmentStatus assignmentStatus = order.getShipperAssignmentStatus();
        return (canAssignShipper(order.getStatus())
            && (assignmentStatus == null || assignmentStatus == ShipperAssignmentStatus.PENDING))
            || (order.getStatus() == OrderStatus.PICKED_UP && assignmentStatus == ShipperAssignmentStatus.PENDING);
    }

    void applyStatus(CustomerOrder order, OrderStatus target, User changedBy, String note) {
        applyStatus(order, target, changedBy, note, true);
    }

    void applyStatus(CustomerOrder order, OrderStatus target, User changedBy, String note,
                     boolean restoreReturnedGoods) {
        OrderStatus old = order.getStatus();
        order.setStatus(target);
        if (target == OrderStatus.DELIVERED && old == OrderStatus.SHIPPING) {
            if (order.getPaymentMethod() == PaymentMethod.COD) {
                if (order.getCodCollectedAt() == null) {
                    throw new IllegalArgumentException("Chưa xác nhận đã thu tiền COD.");
                }
                order.setPaymentStatus(PaymentStatus.PAID);
            }
            order.getItems().forEach(item -> item.getProduct().setSoldCount(item.getProduct().getSoldCount() + item.getQuantity()));
            loyaltyPointService.awardForDeliveredOrder(order);
        }
        if ((target == OrderStatus.CANCELLED || target == OrderStatus.RETURNED_REFUNDED)
            && old != OrderStatus.CANCELLED && old != OrderStatus.RETURNED_REFUNDED) {
            order.getItems().forEach(item -> {
                Product product = item.getProduct();
                if (target == OrderStatus.CANCELLED || restoreReturnedGoods) {
                    int before = product.getStock();
                    product.setStock(product.getStock() + item.getQuantity());
                    if (product.getStatus() == ProductStatus.OUT_OF_STOCK) product.setStatus(ProductStatus.ACTIVE);
                    inventoryService.record(product, before, product.getStock(),
                        target == OrderStatus.CANCELLED ? InventoryTransactionType.CANCELLATION : InventoryTransactionType.RETURN_REFUND,
                        changedBy, order, note);
                }
                if (target == OrderStatus.RETURNED_REFUNDED) product.setSoldCount(Math.max(0, product.getSoldCount() - item.getQuantity()));
            });
            if (target == OrderStatus.RETURNED_REFUNDED) {
                order.setPaymentStatus(PaymentStatus.REFUNDED);
                loyaltyPointService.reverseForRefundedOrder(order);
            }
        }
        if (target == OrderStatus.CANCELLED && old != OrderStatus.CANCELLED) {
            releaseVoucherWhenCheckoutIsFullyCancelled(order);
            if (order.getShipper() != null) {
                notificationService.send(order.getShipper(), "Đơn giao hàng đã bị hủy",
                    "Đơn " + order.getCode() + " đã bị hủy. Bạn không cần lấy hoặc giao đơn này.", "/shipper");
                order.setShipperAssignmentStatus(ShipperAssignmentStatus.CANCELLED);
                order.setShipperReminderSentAt(null);
            }
        }
        OrderStatusHistory history = recordStatus(order, old, target, changedBy, note);
        if (changedBy.getRole() != Role.USER && old != target) {
            auditLogService.record(changedBy.getRole() == Role.SHIPPER
                    ? AuditAction.SHIPPER_ORDER_STATUS : AuditAction.ORDER_STATUS_CHANGED,
                changedBy, "ORDER", order.getId(), order.getCode(), old.getLabel(), target.getLabel(), note);
        }
        notificationService.sendOrderStatus(order.getUser(), history);
        notificationService.send(order.getUser(), "Cập nhật đơn hàng", "Đơn " + order.getCode() + ": " + target.getLabel(), "/orders/" + order.getId());
    }

    private void releaseVoucherWhenCheckoutIsFullyCancelled(CustomerOrder order) {
        if (order.getVoucher() == null) return;

        String checkoutReference = order.getCheckoutReference();
        Optional<VoucherUsage> usageResult = checkoutReference == null || checkoutReference.isBlank()
            ? voucherUsageRepository.findByOrderIdForUpdate(order.getId())
            : voucherUsageRepository.findByCheckoutReferenceForUpdate(checkoutReference);
        if (usageResult.isEmpty()) return;

        VoucherUsage usage = usageResult.get();
        if (usage.getStatus() != VoucherUsageStatus.USED) return;
        if (checkoutReference != null && !checkoutReference.isBlank()) {
            long remainingOrders = orderRepository.countByCheckoutReferenceAndIdNotAndStatusNot(
                checkoutReference, order.getId(), OrderStatus.CANCELLED);
            if (remainingOrders > 0) return;
        }

        Voucher voucher = voucherRepository.findByIdForUpdate(usage.getVoucher().getId())
            .orElseThrow(() -> new IllegalStateException("Không tìm thấy voucher cần hoàn lại."));
        voucher.setQuantity(voucher.getQuantity() + 1);
        usage.setStatus(VoucherUsageStatus.CANCELLED);
        usage.setReleasedAt(VietnamTime.now());
        usage.setReleaseReason("Hoàn lượt sử dụng do toàn bộ đơn trong checkout đã hủy");
    }

    private OrderStatusHistory recordStatus(CustomerOrder order, OrderStatus oldStatus, OrderStatus newStatus,
                                            User changedBy, String note) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setOldStatus(oldStatus);
        history.setNewStatus(newStatus);
        history.setChangedBy(changedBy);
        history.setNote(note);
        if (newStatus == OrderStatus.DELIVERY_FAILED) history.setDeliveryProofUrl(order.getDeliveryProofUrl());
        return orderStatusHistoryRepository.save(history);
    }

    public BigDecimal revenue(Shop shop) {
        return revenueSummary(shop).sellerRevenue();
    }

    public RevenueSummary revenueSummary(Shop shop) {
        return new RevenueSummary(
            zeroIfNull(orderRepository.sumRevenue(shop.getId(), PaymentStatus.PAID, OrderStatus.DELIVERED)),
            zeroIfNull(orderRepository.sumPlatformRevenueByShop(shop.getId(), PaymentStatus.PAID, OrderStatus.DELIVERED)),
            zeroIfNull(orderRepository.sumSellerRevenue(shop.getId(), PaymentStatus.PAID, OrderStatus.DELIVERED))
        );
    }

    private BigDecimal zeroIfNull(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    public long shopCount(Shop shop, OrderStatus status) {
        return status == OrderStatus.NEW ? orderRepository.countActionableNewOrdersForShop(shop.getId())
            : orderRepository.countByShopIdAndStatus(shop.getId(), status);
    }
    public long shipperTotal(User shipper) { return orderRepository.countByShipperId(shipper.getId()); }
    public long shipperCount(User shipper, OrderStatus status) { return orderRepository.countByShipperIdAndStatus(shipper.getId(), status); }
    private String newOrderCode() { return "UTE" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 4).toUpperCase(); }
    private record PromotionDiscount(BigDecimal productDiscount, BigDecimal shippingDiscount) {}
    private record VoucherBenefits(BigDecimal productDiscount, BigDecimal shippingDiscount) {}
    private record ShopAmounts(BigDecimal subtotal, BigDecimal productDiscount, BigDecimal voucherDiscount,
                               BigDecimal shippingDiscount, BigDecimal finalShippingFee, BigDecimal totalAmount) {}
    private record CheckoutCalculation(Map<Shop, List<CartItem>> itemsByShop,
                                       Map<Shop, ShopAmounts> shopAmounts,
                                       CheckoutPreview preview) {}
    public record CheckoutPreview(BigDecimal subtotal, BigDecimal productDiscount, BigDecimal voucherDiscount,
                                  BigDecimal shippingFee, BigDecimal shippingDiscount,
                                  BigDecimal finalShippingFee, BigDecimal totalAmount, int shopCount,
                                  boolean pricesChanged, List<String> changedProducts) {}
    public static final class PriceChangedException extends IllegalArgumentException {
        public PriceChangedException(List<String> products) {
            super("Giá sản phẩm đã thay đổi: " + String.join(", ", products)
                + ". Vui lòng kiểm tra lại tổng thanh toán trước khi đặt hàng.");
        }
    }
    public record RevenueSummary(BigDecimal grossRevenue, BigDecimal platformRevenue, BigDecimal sellerRevenue) {}
}
