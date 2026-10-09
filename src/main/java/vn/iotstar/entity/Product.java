package vn.iotstar.entity;

import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.iotstar.entity.DomainEnums.ProductStatus;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "products", indexes = { @Index(name = "idx_product_shop", columnList = "shop_id"),
		@Index(name = "idx_product_category", columnList = "category_id"),
		@Index(name = "idx_product_status_sold", columnList = "status,sold_count") })
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "shop_id", nullable = false)
	private Shop shop;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id", nullable = false)
	private Category category;

	@Column(nullable = false, length = 180)
	private String name;

	@Column(nullable = false, length = 200)
	private String slug;

	@Column(nullable = false, length = 60, unique = true)
	private String sku;

	@Lob
	@Column(nullable = false)
	private String description;

	@Column(name = "image_url", length = 500)
	private String imageUrl;

	@Column(nullable = false, precision = 14, scale = 2)
	private BigDecimal price;

	@Column(nullable = false)
	private int stock;

	@Column(name = "sold_count", nullable = false)
	private long soldCount;

	@Column(name = "average_rating", nullable = false, precision = 3, scale = 2)
	private BigDecimal averageRating = BigDecimal.ZERO;

	@Column(name = "review_count", nullable = false)
	private long reviewCount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ProductStatus status = ProductStatus.ACTIVE;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt = LocalDateTime.now();

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt = LocalDateTime.now();
	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("displayOrder ASC, id ASC")
	private List<ProductSize> sizes = new ArrayList<>();

	@OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id ASC")
	private List<ProductTopping> availableToppings = new ArrayList<>();

	@PreUpdate
	void touch() {
		updatedAt = LocalDateTime.now();
	}

	public boolean isAvailable() {
		return stock > 0 && status == ProductStatus.ACTIVE && shop != null && shop.isApproved() && category != null
				&& category.isActive();
	}
}