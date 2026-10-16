package vn.iotstar.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.iotstar.entity.DomainEnums.MediaType;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "review_media")
public class ReviewMedia {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private Review review;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MediaType type;

    @Column(nullable = false, length = 500)
    private String url;
}
