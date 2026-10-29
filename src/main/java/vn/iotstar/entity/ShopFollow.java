package vn.iotstar.entity;

import vn.iotstar.util.VietnamTime;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
@Entity
@Table(name = "shop_follows", uniqueConstraints =
    @UniqueConstraint(name = "uk_shop_follow_user_shop", columnNames = {"user_id", "shop_id"}), indexes = {
    @Index(name = "idx_shop_follow_user_time", columnList = "user_id,created_at")
})
public class ShopFollow {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = VietnamTime.now();
}
