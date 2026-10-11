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
