package vn.iotstar.entity;

import vn.iotstar.util.VietnamTime;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "search_history", uniqueConstraints =
    @UniqueConstraint(name = "uk_search_history_user_keyword", columnNames = {"user_id", "normalized_keyword"}),
    indexes = @Index(name = "idx_search_history_user_time", columnList = "user_id,searched_at"))
public class SearchHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 80)
    private String keyword;

    @Column(name = "normalized_keyword", nullable = false, length = 80)
    private String normalizedKeyword;

    @Column(name = "searched_at", nullable = false)
    private LocalDateTime searchedAt = VietnamTime.now();

    @Column(name = "search_count", nullable = false)
    private int searchCount = 1;
}
