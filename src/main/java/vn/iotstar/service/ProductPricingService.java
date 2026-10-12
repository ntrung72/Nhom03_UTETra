package vn.iotstar.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.ProductSize;
import vn.iotstar.entity.ProductTopping;
import vn.iotstar.entity.Promotion;
import vn.iotstar.repository.ProductSizeRepository;
import vn.iotstar.repository.ProductToppingRepository;
import vn.iotstar.repository.PromotionRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductPricingService {

    private static final BigDecimal ONE_HUNDRED =
        new BigDecimal("100");

    private final ProductSizeRepository productSizeRepository;
    private final ProductToppingRepository productToppingRepository;
    private final PromotionRepository promotionRepository;

    public ProductPrice price(Product product) {
        validateProduct(product);

        List<Promotion> promotions =
            promotionRepository.findApplicable(
                product.getShop().getId(),
                List.of(product.getId()),
                LocalDateTime.now()
            );

        return price(product, promotions);
    }

    public Map<Long, ProductPrice> prices(
            Collection<Product> products) {

        Map<Long, ProductPrice> result = new LinkedHashMap<>();

        if (products == null || products.isEmpty()) {
            return result;
        }

        Map<Long, List<Product>> productsByShop =
            products.stream()
                .filter(product ->
                    product != null
                        && product.getId() != null
                        && product.getShop() != null
                )
                .collect(Collectors.groupingBy(
                    product -> product.getShop().getId(),
                    LinkedHashMap::new,
                    Collectors.toList()
                ));

        LocalDateTime now = LocalDateTime.now();

        productsByShop.forEach((shopId, shopProducts) -> {
            List<Long> productIds = shopProducts.stream()
                .map(Product::getId)
                .toList();

            List<Promotion> promotions =
                promotionRepository.findApplicable(
                    shopId,
                    productIds,
                    now
                );

            for (Product product : shopProducts) {
                validateProduct(product);
                result.put(
                    product.getId(),
                    price(product, promotions)
                );
            }
        });

        return result;
    }

    public BigDecimal bestProductDiscountPercent(
            Product product,
            Collection<Promotion> promotions) {

        if (product == null
                || product.getId() == null
                || product.getShop() == null
                || promotions == null) {
            return BigDecimal.ZERO;
        }

        return promotions.stream()
            .filter(Objects::nonNull)
            .filter(promotion ->
                promotion.getType()
                    == PromotionType.PRODUCT_PERCENT
            )
            .filter(promotion ->
                promotion.getShop() == null
                    || Objects.equals(
                        promotion.getShop().getId(),
                        product.getShop().getId()
                    )
            )
            .filter(promotion ->
                promotion.getProduct() == null
                    || Objects.equals(
                        promotion.getProduct().getId(),
                        product.getId()
                    )
            )
            .map(Promotion::getDiscountValue)
            .filter(value ->
                value != null && value.signum() > 0
            )
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO)
            .min(ONE_HUNDRED);
    }

    public BigDecimal unitPrice(
            Product product,
            Long sizeId,
            Collection<Long> toppingIds) {

        validateProduct(product);

        LinkedHashSet<Long> selectedIds = new LinkedHashSet<>(
            toppingIds == null ? List.of() : toppingIds
        );

        if (selectedIds.stream().anyMatch(
                id -> id == null || id <= 0)) {
            throw new IllegalArgumentException(
                "Danh sách topping có mã không hợp lệ."
            );
        }

        boolean toppingProduct = product.getCategory() != null
            && "topping".equals(product.getCategory().getSlug());

        if (toppingProduct) {
            if (sizeId != null || !selectedIds.isEmpty()) {
                throw new IllegalArgumentException(
                    "Sản phẩm topping bán riêng không có "
                        + "tùy chọn size/topping."
                );
            }

            return price(product).salePrice();
        }

        if (sizeId == null) {
            throw new IllegalArgumentException(
                "Vui lòng chọn size sản phẩm."
            );
        }

        ProductSize size = productSizeRepository
            .findByIdAndProductIdAndActiveTrue(
                sizeId,
                product.getId()
            )
            .orElseThrow(() ->
                new IllegalArgumentException(
                    "Size không thuộc sản phẩm "
                        + "hoặc đã ngừng bán."
                )
            );

        BigDecimal extraPrice = size.getExtraPrice();

        if (extraPrice == null || extraPrice.signum() < 0) {
            throw new IllegalArgumentException(
                "Phụ thu size không hợp lệ."
            );
        }

        BigDecimal toppingTotal = BigDecimal.ZERO;

        if (!selectedIds.isEmpty()) {
            List<ProductTopping> allowed =
                productToppingRepository
                    .findByProductIdAndToppingActiveTrueOrderByToppingNameAsc(
                        product.getId()
                    );

            Map<Long, BigDecimal> allowedPrices =
                new LinkedHashMap<>();

            for (ProductTopping link : allowed) {
                allowedPrices.put(
                    link.getTopping().getId(),
                    link.getTopping().getPrice()
                );
            }

            for (Long toppingId : selectedIds) {
                BigDecimal toppingPrice =
                    allowedPrices.get(toppingId);

                if (toppingPrice == null
                        || toppingPrice.signum() < 0) {
                    throw new IllegalArgumentException(
                        "Topping không áp dụng cho sản phẩm"
                            + " hoặc đã ngừng bán."
                    );
                }

                toppingTotal = toppingTotal.add(toppingPrice);
            }
        }

        return money(
            price(product).salePrice()
                .add(extraPrice)
                .add(toppingTotal)
        );
    }

    private ProductPrice price(
            Product product,
            Collection<Promotion> promotions) {

        BigDecimal originalPrice = money(product.getPrice());

        BigDecimal discountPercent =
            bestProductDiscountPercent(product, promotions);

        BigDecimal discountAmount = originalPrice
            .multiply(discountPercent)
            .divide(
                ONE_HUNDRED,
                2,
                RoundingMode.HALF_UP
            );

        BigDecimal salePrice = money(
            originalPrice.subtract(discountAmount)
                .max(BigDecimal.ZERO)
        );

        String discountLabel = discountPercent
            .stripTrailingZeros()
            .toPlainString() + "%";

        return new ProductPrice(
            originalPrice,
            salePrice,
            discountPercent,
            discountLabel,
            discountPercent.signum() > 0
        );
    }

    private void validateProduct(Product product) {
        if (product == null
                || product.getId() == null
                || product.getShop() == null
                || product.getPrice() == null
                || product.getPrice().signum() < 0) {
            throw new IllegalArgumentException(
                "Sản phẩm hoặc giá sản phẩm không hợp lệ."
            );
        }
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record ProductPrice(
        BigDecimal originalPrice,
        BigDecimal salePrice,
        BigDecimal discountPercent,
        String discountLabel,
        boolean promoted
    ) {
    }
}