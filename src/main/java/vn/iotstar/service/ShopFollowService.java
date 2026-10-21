package vn.iotstar.service;

import lombok.RequiredArgsConstructor;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.ShopFollow;
import vn.iotstar.entity.User;
import vn.iotstar.repository.ShopFollowRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class ShopFollowService {
    private final ShopFollowRepository followRepository;
    private final ShopRepository shopRepository;
    private final UserRepository userRepository;

    @Transactional
    public boolean follow(User user, Long shopId) {
        User follower = lockCustomer(user);
        if (followRepository.existsByUserIdAndShopId(follower.getId(), shopId)) return false;
        var shop = shopRepository.findById(shopId)
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy cửa hàng."));
        if (!shop.isApproved()) throw new IllegalArgumentException("Cửa hàng hiện không thể theo dõi.");
        ShopFollow follow = new ShopFollow();
        follow.setUser(follower);
        follow.setShop(shop);
        followRepository.save(follow);
        return true;
    }

    @Transactional
    public boolean unfollow(User user, Long shopId) {
        User follower = lockCustomer(user);
        var existing = followRepository.findByUserIdAndShopId(follower.getId(), shopId);
        if (existing.isEmpty()) return false;
        followRepository.delete(existing.get());
        return true;
    }

    @Transactional(readOnly = true)
    public boolean isFollowing(User user, Long shopId) {
        return user != null && isCustomer(user)
            && followRepository.existsByUserIdAndShopId(user.getId(), shopId);
    }

    @Transactional(readOnly = true)
    public Page<ShopFollow> followedShops(User user, int page) {
        requireCustomer(user);
        return followRepository.findByUserIdOrderByCreatedAtDescIdDesc(user.getId(),
            PageRequest.of(Math.max(0, page), 12));
    }

    @Transactional(readOnly = true)
    public Optional<String> publicShopSlug(Long shopId) {
        return shopRepository.findById(shopId).filter(shop -> shop.isApproved())
            .map(shop -> shop.getSlug());
    }

    private User lockCustomer(User user) {
        requireCustomer(user);
        return userRepository.findByIdForUpdate(user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy tài khoản."));
    }

    private void requireCustomer(User user) {
        if (!isCustomer(user)) throw new IllegalArgumentException("Tài khoản không có quyền theo dõi cửa hàng.");
    }

    private boolean isCustomer(User user) {
        return user != null && (user.getRole() == Role.USER || user.getRole() == Role.VENDOR);
    }
}
