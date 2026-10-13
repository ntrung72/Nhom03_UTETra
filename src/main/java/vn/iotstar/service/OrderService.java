package vn.iotstar.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.CheckoutForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.*;
import vn.iotstar.repository.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final AddressRepository addressRepository;
    private final CarrierRepository carrierRepository;
    private final VoucherRepository voucherRepository;
    private final VoucherUsageRepository usageRepository;
    private final PromotionRepository promotionRepository;
    private final CartService cartService;
    private final jakarta.validation.Validator validator;

    public List<Carrier> activeCarriers() {
        return carrierRepository.findByActiveTrueOrderByShippingFeeAsc();
    }

    public List<Voucher> availableVouchers(User user) {
        return voucherRepository.findAvailable(LocalDateTime.now()).stream()
            .filter(v -> usageRepository.countByUserIdAndVoucherIdAndStatus(user.getId(), v.getId(),
                VoucherUsageStatus.USED) < v.getEffectiveMaxUsesPerUser()).toList();
    }

    @Transactional
    public CheckoutPreview preview(User user, Long carrierId, String voucherCode, List<Long> itemIds) {
        var selection = cartService.prepareCheckoutSelection(user, itemIds);
        var carrier = carrier(carrierId);
        var voucher = voucher(user, voucherCode, selection.subtotal(), false);
        return calculate(selection.items(), carrier, voucher).preview();
    }

    @Transactional(noRollbackFor = PriceChangedException.class)
    public List<CustomerOrder> checkout(User user, CheckoutForm form) {
        if (form == null || !validator.validate(form).isEmpty() || form.getPaymentMethod() != PaymentMethod.COD)
            throw new IllegalArgumentException("Vui lòng kiểm tra sản phẩm, địa chỉ, nhà vận chuyển và ghi chú.");
        var selection = cartService.prepareCheckoutSelection(user, form.getItemIds());
        if (selection.pricesChanged()) throw new PriceChangedException();
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
            order.setCode("UTE" + UUID.randomUUID().toString().replace("-", "").substring(0, 29).toUpperCase(Locale.ROOT));
            order.setCheckoutReference(reference); order.setUser(owner); order.setShop(shop);
            order.setCarrier(carrier); order.setVoucher(voucher);
            order.setReceiverName(address.getReceiverName()); order.setReceiverPhone(address.getPhone());
            order.setShippingAddress(address.getFullAddress());
            order.setNote(form.getNote() == null ? null : form.getNote().trim());
            order.setSubtotal(amount.subtotal()); order.setProductDiscount(amount.productDiscount());
            order.setVoucherDiscount(amount.voucherDiscount());
            order.setDiscountAmount(amount.productDiscount().add(amount.voucherDiscount()));
            order.setShippingFee(carrier.getShippingFee()); order.setShippingDiscount(amount.shippingDiscount());
            order.setFinalShippingFee(amount.finalShippingFee()); order.setTotalAmount(amount.totalAmount());
            // Preserve the rate now; revenue/commission amount reporting is added by the vendor module.
            order.setCommissionRate(shop.getCommissionRate());
            for (var line : lines) {
                var product = line.getProduct();
                product.setStock(product.getStock() - line.getQuantity());
                if (product.getStock() == 0) product.setStatus(ProductStatus.OUT_OF_STOCK);
                var item = new OrderItem(); item.setOrder(order); item.setProduct(product);
                item.setProductName(product.getName()); item.setImageUrl(product.getImageUrl());
                item.setSize(line.getSize()); item.setSugarLevel(line.getSugarLevel()); item.setIceLevel(line.getIceLevel());
                item.setToppings(line.getToppings()); item.setBasePrice(product.getPrice());
                item.setOptionPrice(line.getOptionPrice()); item.setUnitPrice(line.getUnitPrice());
                item.setQuantity(line.getQuantity()); item.setLineTotal(line.getLineTotal()); order.getItems().add(item);
            }
            orderRepository.saveAndFlush(order);
            recordStatus(order, null, OrderStatus.NEW, owner, "Khách hàng đặt đơn COD");
            created.add(order);
        }
        if (voucher != null) {
            voucher.setQuantity(voucher.getQuantity() - 1);
            var usage = new VoucherUsage(); usage.setUser(owner); usage.setVoucher(voucher);
            usage.setOrderId(created.getFirst().getId()); usage.setCheckoutReference(reference);
            usageRepository.save(usage);
        }
        cartService.removeItems(selection.cart(), selection.items());
        return List.copyOf(created);
    }

    private Carrier carrier(Long id) {
        if (id == null) throw new IllegalArgumentException("Vui lòng chọn nhà vận chuyển.");
        return carrierRepository.findById(id).filter(Carrier::isActive)
            .orElseThrow(() -> new IllegalArgumentException("Nhà vận chuyển không còn khả dụng."));
    }

    private Voucher voucher(User user, String code, BigDecimal netSubtotal, boolean lock) {
        if (code == null || code.isBlank()) return null;
        if (code.trim().length() > 40) throw new IllegalArgumentException("Mã giảm giá không hợp lệ.");
        var found = lock ? voucherRepository.findByCodeForCheckout(code.trim())
            : voucherRepository.findByCodeIgnoreCase(code.trim());
        var voucher = found.orElseThrow(() -> new IllegalArgumentException("Mã giảm giá không tồn tại."));
        if (lock) entityManager.refresh(voucher, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!voucher.canApply(netSubtotal, LocalDateTime.now()))
            throw new IllegalArgumentException("Mã giảm giá hết lượt, hết hiệu lực hoặc chưa đạt giá trị tối thiểu.");
        if (usageRepository.countByUserIdAndVoucherIdAndStatus(user.getId(), voucher.getId(), VoucherUsageStatus.USED)
                >= voucher.getEffectiveMaxUsesPerUser())
            throw new IllegalArgumentException("Bạn đã dùng hết lượt của mã giảm giá này.");
        return voucher;
    }

    private CheckoutCalculation calculate(List<CartItem> items, Carrier carrier, Voucher voucher) {
        var groups = items.stream().collect(Collectors.groupingBy(i -> i.getProduct().getShop().getId(),
            LinkedHashMap::new, Collectors.toList()));
        var gross = new LinkedHashMap<Long, BigDecimal>();
        var net = new LinkedHashMap<Long, BigDecimal>();
        var shipping = new LinkedHashMap<Long, BigDecimal>();
        for (var group : groups.entrySet()) {
            var lines = group.getValue();
            gross.put(group.getKey(), money(lines.stream().map(i -> i.getProduct().getPrice().add(i.getOptionPrice())
                .multiply(BigDecimal.valueOf(i.getQuantity()))).reduce(BigDecimal.ZERO, BigDecimal::add)));
            net.put(group.getKey(), money(lines.stream().map(CartItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add)));
            var promotions = promotionRepository.findApplicable(group.getKey(),
                lines.stream().map(i -> i.getProduct().getId()).distinct().toList(), LocalDateTime.now());
            var discount = promotions.stream().filter(p -> p.getType() == PromotionType.SHIPPING_DISCOUNT)
                .map(Promotion::getDiscountValue).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO)
                .max(BigDecimal.ZERO).min(carrier.getShippingFee());
            shipping.put(group.getKey(), money(carrier.getShippingFee().subtract(discount)));
        }
        var netTotal = sum(net.values()); var shippingTotal = sum(shipping.values());
        var voucherAmount = voucher == null ? BigDecimal.ZERO : money(voucher.calculateDiscount(netTotal, shippingTotal));
        var productShares = allocate(voucher != null && voucher.getType() != VoucherType.SHIPPING ? voucherAmount : BigDecimal.ZERO, net);
        var shippingShares = allocate(voucher != null && voucher.getType() == VoucherType.SHIPPING ? voucherAmount : BigDecimal.ZERO, shipping);
        var amounts = new LinkedHashMap<Long, ShopAmounts>();
        for (Long shopId : groups.keySet()) {
            var finalShipping = money(shipping.get(shopId).subtract(shippingShares.get(shopId)));
            amounts.put(shopId, new ShopAmounts(gross.get(shopId), money(gross.get(shopId).subtract(net.get(shopId))),
                productShares.get(shopId), money(carrier.getShippingFee().subtract(finalShipping)), finalShipping,
                money(net.get(shopId).subtract(productShares.get(shopId)).add(finalShipping))));
        }
        var values = amounts.values();
        return new CheckoutCalculation(groups, amounts, new CheckoutPreview(sum(gross.values()),
            sum(values.stream().map(ShopAmounts::productDiscount).toList()),
            sum(values.stream().map(ShopAmounts::voucherDiscount).toList()),
            money(carrier.getShippingFee().multiply(BigDecimal.valueOf(groups.size()))),
            sum(values.stream().map(ShopAmounts::shippingDiscount).toList()),
            sum(values.stream().map(ShopAmounts::finalShippingFee).toList()),
            sum(values.stream().map(ShopAmounts::totalAmount).toList()), groups.size()));
    }

    /** Allocate in cents with a carried remainder; the shares sum to the capped discount. */
    private Map<Long, BigDecimal> allocate(BigDecimal discount, Map<Long, BigDecimal> bases) {
        var result = new LinkedHashMap<Long, BigDecimal>();
        var remainingBase = sum(bases.values());
        var remaining = discount.max(BigDecimal.ZERO).min(remainingBase).setScale(2, RoundingMode.DOWN);
        for (var entry : bases.entrySet()) {
            var base = entry.getValue();
            var share = remainingBase.signum() == 0 ? new BigDecimal("0.00")
                : remaining.multiply(base).divide(remainingBase, 2, RoundingMode.DOWN).min(base);
            result.put(entry.getKey(), share); remaining = remaining.subtract(share);
            remainingBase = remainingBase.subtract(base);
        }
        return result;
    }

    private BigDecimal sum(Collection<BigDecimal> values) {
        return money(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private BigDecimal money(BigDecimal value) {
        var result = value.setScale(2, RoundingMode.HALF_UP);
        if (result.signum() < 0 || result.compareTo(new BigDecimal("999999999999.99")) > 0)
            throw new IllegalArgumentException("Giá trị thanh toán vượt giới hạn cho phép.");
        return result;
    }

    private void recordStatus(CustomerOrder order, OrderStatus old, OrderStatus next, User actor, String note) {
        var history = new OrderStatusHistory(); history.setOrder(order); history.setOldStatus(old);
        history.setNewStatus(next); history.setChangedBy(actor); history.setNote(note); historyRepository.save(history);
    }

    public record CheckoutPreview(BigDecimal subtotal, BigDecimal productDiscount, BigDecimal voucherDiscount,
        BigDecimal shippingFee, BigDecimal shippingDiscount, BigDecimal finalShippingFee, BigDecimal totalAmount, int shopCount) {}
    private record ShopAmounts(BigDecimal subtotal, BigDecimal productDiscount, BigDecimal voucherDiscount,
        BigDecimal shippingDiscount, BigDecimal finalShippingFee, BigDecimal totalAmount) {}
    private record CheckoutCalculation(Map<Long, List<CartItem>> groups, Map<Long, ShopAmounts> amounts, CheckoutPreview preview) {}
    public static final class PriceChangedException extends IllegalArgumentException {
        public PriceChangedException() { super("Giá đã thay đổi. Vui lòng kiểm tra lại tổng tiền trước khi xác nhận."); }
    }
}
