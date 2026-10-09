package vn.iotstar.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.iotstar.entity.DomainEnums.ShopStatus;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "shops", uniqueConstraints = {
    @UniqueConstraint(name = "uk_shop_owner", columnNames = "owner_id"),
    @UniqueConstraint(name = "uk_shop_slug", columnNames = "slug")
})
public class Shop {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 180)
    private String slug;

    @Column(length = 1000)
    private String description;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(length = 20)
    private String phone;

    @Column(length = 160)
    private String email;

    @Column(length = 255)
    private String address;

    @Column(length = 120)
    private String province;

    @Column(length = 120)
    private String district;

    @Column(length = 120)
    private String ward;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "opening_time")
    private LocalTime openingTime;

    @Column(name = "closing_time")
    private LocalTime closingTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShopStatus status = ShopStatus.PENDING;

    @Column(name = "commission_rate", nullable = false,
            precision = 5, scale = 2)
    private BigDecimal commissionRate = new BigDecimal("5.00");

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean isApproved() {
        return enabled && status == ShopStatus.APPROVED;
    }

    public String getFullAddress() {
        return java.util.stream.Stream.of(address, ward, district, province)
            .filter(value -> value != null && !value.isBlank())
            .collect(java.util.stream.Collectors.joining(", "));
    }
}