package vn.iotstar.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "product_sizes",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_product_size_code",
        columnNames = {"product_id", "code"}
    )
)
public class ProductSize {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 10)
    private String code;

    @Column(
        name = "extra_price",
        nullable = false,
        precision = 14,
        scale = 2
    )
    private BigDecimal extraPrice = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}