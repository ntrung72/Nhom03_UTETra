package vn.iotstar.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.iotstar.entity.Review;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    Page<Review> findByProductIdOrderByCreatedAtDesc(Long productId, Pageable pageable);
    @Query(value = """
        select review from Review review
        where review.product.id = :productId
          and (:rating is null or review.rating = :rating)
          and (:mediaOnly = false or exists (
              select media.id from ReviewMedia media where media.review = review))
        order by review.createdAt desc, review.id desc
        """, countQuery = """
        select count(review) from Review review
        where review.product.id = :productId
          and (:rating is null or review.rating = :rating)
          and (:mediaOnly = false or exists (
              select media.id from ReviewMedia media where media.review = review))
        """)
    Page<Review> searchPublicReviews(@Param("productId") Long productId,
                                     @Param("rating") Integer rating,
                                     @Param("mediaOnly") boolean mediaOnly,
                                     Pageable pageable);
    boolean existsByOrderItemId(Long orderItemId);
    long countByProductId(Long productId);

    @Query("select avg(r.rating) from Review r where r.product.id = :productId")
    Double averageRating(@Param("productId") Long productId);

    @Query("select avg(r.rating) from Review r where r.product.shop.id = :shopId")
    Double averageRatingByShopId(@Param("shopId") Long shopId);
}
