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
@Table(name = "order_items")
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CustomerOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_name", nullable = false, length = 180)
    private String productName;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "size_code", length = 10)
    private String size;

    @Enumerated(EnumType.STRING)
    @Column(name = "sugar_level", length = 30)
    private SugarLevel sugarLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "ice_level", length = 30)
    private IceLevel iceLevel;

    @Column(name = "toppings", length = 1000)
    private String toppings;

    @Column(name = "topping_ids", length = 1000)
    private String toppingIds;

    @Column(name = "base_price", precision = 14, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "option_price", precision = 14, scale = 2)
    private BigDecimal optionPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal;

    @Column(nullable = false)
    private boolean reviewed;
}
