package vn.iotstar.service;

import vn.iotstar.util.VietnamTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.Promotion;
import vn.iotstar.repository.PromotionRepository;

@Service
@RequiredArgsConstructor
public class ProductPricingService {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final PromotionRepository promotionRepository;

    @Transactional(readOnly = true)
    public Map<Long, ProductPrice> prices(Collection<Product> products) {
        Map<Long, ProductPrice> result = new LinkedHashMap<>();
        if (products == null || products.isEmpty()) return result;

        Map<Long, List<Product>> productsByShop = products.stream()
            .filter(product -> product != null && product.getId() != null && product.getShop() != null)
            .collect(Collectors.groupingBy(product -> product.getShop().getId(), LinkedHashMap::new,
                Collectors.toList()));
        LocalDateTime now = VietnamTime.now();

        productsByShop.forEach((shopId, shopProducts) -> {
            List<Long> productIds = shopProducts.stream().map(Product::getId).toList();
            List<Promotion> promotions = promotionRepository.findApplicable(shopId, productIds, now);
            shopProducts.forEach(product -> result.put(product.getId(), price(product, promotions)));
        });
        return result;
    }

    @Transactional(readOnly = true)
    public ProductPrice price(Product product) {
        if (product == null || product.getId() == null || product.getShop() == null) {
            throw new IllegalArgumentException("Sản phẩm không hợp lệ.");
        }
        List<Promotion> promotions = promotionRepository.findApplicable(
            product.getShop().getId(), List.of(product.getId()), VietnamTime.now());
        return price(product, promotions);
    }

    public BigDecimal bestProductDiscountPercent(Product product, Collection<Promotion> promotions) {
        if (product == null || promotions == null) return BigDecimal.ZERO;
        return promotions.stream()
            .filter(promotion -> promotion.getType() == PromotionType.PRODUCT_PERCENT)
            .filter(promotion -> promotion.getProduct() == null
                || promotion.getProduct().getId().equals(product.getId()))
            .map(Promotion::getDiscountValue)
            .filter(value -> value != null && value.signum() > 0)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO)
            .min(ONE_HUNDRED);
    }

    public BigDecimal productDiscountPerUnit(Product product, BigDecimal basePrice,
                                             Collection<Promotion> promotions) {
        return basePrice.multiply(bestProductDiscountPercent(product, promotions))
            .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private ProductPrice price(Product product, Collection<Promotion> promotions) {
        BigDecimal originalPrice = product.getPrice();
        BigDecimal discountPercent = bestProductDiscountPercent(product, promotions);
        BigDecimal discountAmount = productDiscountPerUnit(product, originalPrice, promotions);
        BigDecimal salePrice = originalPrice.subtract(discountAmount).max(BigDecimal.ZERO);
        String discountLabel = discountPercent.stripTrailingZeros().toPlainString() + "%";
        return new ProductPrice(originalPrice, salePrice, discountPercent, discountLabel,
            discountPercent.signum() > 0);
    }

    public record ProductPrice(BigDecimal originalPrice, BigDecimal salePrice,
                               BigDecimal discountPercent, String discountLabel, boolean promoted) {}
}
