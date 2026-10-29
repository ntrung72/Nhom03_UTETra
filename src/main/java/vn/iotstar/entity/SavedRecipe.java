package vn.iotstar.entity;

import vn.iotstar.util.VietnamTime;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.SugarLevel;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "saved_recipes", indexes = {
    @Index(name = "idx_saved_recipe_user_time", columnList = "user_id,updated_at")
})
public class SavedRecipe {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "size_code", nullable = false, length = 10)
    private String sizeCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "sugar_level", nullable = false, length = 30)
    private SugarLevel sugarLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "ice_level", nullable = false, length = 30)
    private IceLevel iceLevel;

    @Column(name = "topping_ids", length = 500)
    private String toppingIds;

    @Column(name = "topping_names", length = 1000)
    private String toppingNames;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = VietnamTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = VietnamTime.now();

    @PreUpdate
    void touch() { updatedAt = VietnamTime.now(); }
}
