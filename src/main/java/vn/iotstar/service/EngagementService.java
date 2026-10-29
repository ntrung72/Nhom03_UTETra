package vn.iotstar.service;

import vn.iotstar.util.VietnamTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.iotstar.dto.WebForms.ReviewForm;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.MediaType;
import vn.iotstar.entity.DomainEnums.OrderStatus;
import vn.iotstar.repository.*;

@Service
@RequiredArgsConstructor
public class EngagementService {
    private final FavoriteRepository favoriteRepository;
    private final ProductViewRepository viewRepository;
    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewMediaRepository reviewMediaRepository;
    private final OrderItemRepository orderItemRepository;
    private final MediaStorageService mediaStorageService;
    private final MediaValidationService mediaValidationService;

    @Transactional
    public void recordView(Product product, User user) {
        product.setViewCount(product.getViewCount() + 1);
        if (user == null) return;
        ProductView view = viewRepository.findByUserIdAndProductId(user.getId(), product.getId()).orElseGet(() -> {
            ProductView created = new ProductView();
            created.setUser(user);
            created.setProduct(product);
            return created;
        });
        view.setViewedAt(VietnamTime.now());
        viewRepository.save(view);
    }

    @Transactional
    public boolean toggleFavorite(User user, Product product) {
        var existing = favoriteRepository.findByUserIdAndProductId(user.getId(), product.getId());
        if (existing.isPresent()) {
            favoriteRepository.delete(existing.get());
            product.setFavoriteCount(Math.max(0, product.getFavoriteCount() - 1));
            return false;
        }
        Favorite favorite = new Favorite();
        favorite.setUser(user);
        favorite.setProduct(product);
        favoriteRepository.save(favorite);
        product.setFavoriteCount(product.getFavoriteCount() + 1);
        return true;
    }

    public boolean isFavorite(User user, Product product) {
        return user != null && favoriteRepository.existsByUserIdAndProductId(user.getId(), product.getId());
    }

    public Page<Favorite> favorites(User user, int page) {
        return favoriteRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(Math.max(page, 0), 20));
    }

    public Page<ProductView> viewed(User user, int page) {
        return viewRepository.findByUserIdOrderByViewedAtDesc(user.getId(), PageRequest.of(Math.max(page, 0), 20));
    }

    public Page<Review> reviews(Product product, int page) {
        return reviews(product, page, null, false);
    }

    @Transactional(readOnly = true)
    public Page<Review> reviews(Product product, int page, Integer rating, boolean mediaOnly) {
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("Số sao cần lọc phải từ 1 đến 5.");
        }
        return reviewRepository.searchPublicReviews(product.getId(), rating, mediaOnly,
            PageRequest.of(Math.max(page, 0), 10));
    }

    @Transactional
    public void addReview(User user, Long orderItemId, ReviewForm form, MultipartFile[] files) {
        OrderItem item = orderItemRepository.findByIdAndOrder_User_Id(orderItemId, user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm đã mua."));
        if (item.getOrder().getStatus() != OrderStatus.DELIVERED) throw new IllegalArgumentException("Chỉ được đánh giá đơn đã giao.");
        if (item.isReviewed() || reviewRepository.existsByOrderItemId(item.getId())) throw new IllegalArgumentException("Sản phẩm này đã được đánh giá.");
        String content = form.getContent() == null ? "" : form.getContent().trim();
        if (content.length() < 50) throw new IllegalArgumentException("Bình luận phải có ít nhất 50 ký tự.");
        if (form.getRating() < 1 || form.getRating() > 5) throw new IllegalArgumentException("Số sao không hợp lệ.");
        List<MultipartFile> mediaFiles = mediaValidationService.validateFiles(files, "reviews");

        Review review = new Review();
        review.setOrderItem(item);
        review.setUser(user);
        review.setProduct(item.getProduct());
        review.setRating(form.getRating());
        review.setContent(content);
        reviewRepository.save(review);

        if (!mediaFiles.isEmpty()) {
            mediaFiles.forEach(file -> {
                MediaType type = mediaValidationService.validate(file, "reviews");
                ReviewMedia media = new ReviewMedia();
                media.setReview(review);
                media.setType(type);
                media.setUrl(mediaStorageService.upload(file, "reviews"));
                reviewMediaRepository.save(media);
            });
        }
        item.setReviewed(true);
        Product product = item.getProduct();
        Double average = reviewRepository.averageRating(product.getId());
        product.setAverageRating(BigDecimal.valueOf(average == null ? 0 : average).setScale(2, RoundingMode.HALF_UP));
        product.setReviewCount(reviewRepository.countByProductId(product.getId()));
    }
}
