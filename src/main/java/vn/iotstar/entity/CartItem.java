package vn.iotstar.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.SugarLevel;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "cart_items",
    uniqueConstraints = @UniqueConstraint(name = "uk_cart_product_options", columnNames = {"cart_id", "product_id", "option_key"}),
    indexes = @Index(name = "idx_cart_item_cart_product", columnList = "cart_id,product_id"))
public class CartItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "option_key", nullable = false, length = 500)
    private String optionKey;

    @Column(name = "size_id")
    private Long sizeId;

    @Column(name = "size_code", length = 10)
    private String size;

    @Enumerated(EnumType.STRING)
    @Column(name = "sugar_level", length = 30)
    private SugarLevel sugarLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "ice_level", length = 30)
    private IceLevel iceLevel;

    @Column(name = "topping_ids", length = 500)
    private String toppingIds;

    @Column(name = "toppings", length = 1000)
    private String toppings;

    @Column(name = "base_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "option_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal optionPrice;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    public BigDecimal getEffectiveUnitPrice() {
        if (unitPrice != null) return unitPrice;
        return product == null || product.getPrice() == null ? BigDecimal.ZERO : product.getPrice();
    }

    public BigDecimal getLineTotal() {
        return getEffectiveUnitPrice().multiply(BigDecimal.valueOf(quantity));
    }
}
