package vn.iotstar.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.iotstar.entity.*;
import vn.iotstar.entity.DomainEnums.ProductStatus;
import vn.iotstar.repository.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EngagementService {
    private final FavoriteRepository favoriteRepository;
    private final ProductViewRepository viewRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;
    private final MediaStorageService mediaStorageService;
    private final MediaValidationService mediaValidationService;
    private final jakarta.validation.Validator validator;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    @Transactional
    public void recordView(Product product, User user) {
        if (user == null) return;
        userRepository.findByIdForUpdate(user.getId()).orElseThrow();
        var view = viewRepository.findByUserIdAndProductId(user.getId(), product.getId()).orElseGet(() -> {
            var created = new ProductView(); created.setUser(user); created.setProduct(product); return created;
        });
        view.setViewedAt(LocalDateTime.now());
        viewRepository.save(view);
    }

    @Transactional
    public boolean toggleFavorite(User user, Long productId) {
        userRepository.findByIdForUpdate(user.getId()).orElseThrow();
        var product = productRepository.findByIdForUpdate(productId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND));
        var existing = favoriteRepository.findByUserIdAndProductId(user.getId(), productId);
        if (existing.isPresent()) {
            favoriteRepository.delete(existing.get()); favoriteRepository.flush();
            product.setFavoriteCount(favoriteRepository.countByProductId(productId));
            return false;
        }
        if (product.getStatus() != ProductStatus.ACTIVE || !product.getShop().isApproved()
                || !product.getCategory().isActive()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var favorite = new Favorite(); favorite.setUser(user); favorite.setProduct(product);
        favoriteRepository.saveAndFlush(favorite);
        product.setFavoriteCount(favoriteRepository.countByProductId(productId));
        return true;
    }

    @Transactional
    public Review addReview(User user, Long orderId, Long itemId, vn.iotstar.dto.WebForms.ReviewForm form,
            org.springframework.web.multipart.MultipartFile[] media) {
        if (form == null) throw new IllegalArgumentException("Thông tin đánh giá không hợp lệ.");
        var safe = new vn.iotstar.dto.WebForms.ReviewForm(); safe.setRating(form.getRating());
        safe.setContent(form.getContent() == null ? null : form.getContent().trim());
        if (!validator.validate(safe).isEmpty()) throw new IllegalArgumentException("Cần 1–5 sao và nhận xét từ 50 đến 3.000 ký tự.");
        userRepository.findByIdForUpdate(user.getId()).orElseThrow();
        var order = orderRepository.findOwnedForUpdate(orderId, user.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        entityManager.refresh(order, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var item = order.getItems().stream().filter(i -> i.getId().equals(itemId)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (order.getStatus() != vn.iotstar.entity.DomainEnums.OrderStatus.DELIVERED)
            throw new IllegalArgumentException("Chỉ được đánh giá sản phẩm của đơn đã giao.");
        if (reviewRepository.existsByOrderItemId(itemId)) throw new IllegalArgumentException("Sản phẩm trong đơn đã được đánh giá.");
        var files = mediaValidationService.validateFiles(media, "reviews");
        var product = productRepository.findByIdForUpdate(item.getProduct().getId()).orElseThrow();
        entityManager.refresh(product, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var review = new Review(); review.setOrderItem(item); review.setProduct(product); review.setUser(order.getUser());
        review.setRating(safe.getRating()); review.setContent(safe.getContent());
        for (var file : files) {
            var type = mediaValidationService.validate(file, "reviews");
            String url = mediaStorageService.upload(file, "reviews");
            if (url == null || url.isBlank() || url.length() > 500) throw new IllegalStateException("Không thể lưu media đánh giá.");
            var attached = new ReviewMedia(); attached.setReview(review); attached.setType(type); attached.setUrl(url); review.getMedia().add(attached);
        }
        reviewRepository.saveAndFlush(review);
        product.setReviewCount(reviewRepository.countByProductId(product.getId()));
        var average = reviewRepository.averageRating(product.getId());
        product.setAverageRating(java.math.BigDecimal.valueOf(average == null ? 0 : average).setScale(2, java.math.RoundingMode.HALF_UP));
        return review;
    }

    public java.util.Set<Long> reviewedItemIds(User user, Long orderId) {
        return reviewRepository.reviewedItemIds(user.getId(), orderId);
    }

    public Page<Review> reviews(Product product, int page) {
        var reviews = reviewRepository.findByProductIdOrderByCreatedAtDesc(product.getId(),
            PageRequest.of(Math.max(0, page), 20, Sort.by(Sort.Direction.DESC, "id")));
        reviews.getContent().forEach(review -> review.getMedia().size());
        return reviews;
    }

    public boolean isFavorite(User user, Product product) {
        return user != null && favoriteRepository.existsByUserIdAndProductId(user.getId(), product.getId());
    }

    public Page<Favorite> favorites(User user, int page) {
        return favoriteRepository.findPublicByUserId(user.getId(), PageRequest.of(Math.max(page, 0), 20,
            Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }

    public Page<ProductView> viewed(User user, int page) {
        return viewRepository.findPublicByUserId(user.getId(), PageRequest.of(Math.max(page, 0), 20,
            Sort.by(Sort.Direction.DESC, "viewedAt", "id")));
    }
}
