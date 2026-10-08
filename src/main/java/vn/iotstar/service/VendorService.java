package vn.iotstar.service;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.iotstar.dto.WebForms.ShopForm;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.util.SlugUtils;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VendorService {
    private final ShopRepository shopRepository;
    private final UserRepository userRepository;

    public Shop findShop(User user) {
        return shopRepository.findByOwnerId(user.getId()).orElse(null);
    }

    public Shop requireShop(User user) {
        return shopRepository.findByOwnerId(user.getId())
            .orElseThrow(() ->
                new IllegalArgumentException("Bạn chưa đăng ký cửa hàng."));
    }

    @Transactional
    public Shop registerShop(User user, ShopForm form) {
        User owner = userRepository.findById(user.getId())
            .orElseThrow(() ->
                new IllegalArgumentException("Không tìm thấy tài khoản."));

        if (owner.getRole() != Role.USER && owner.getRole() != Role.VENDOR) {
            throw new IllegalArgumentException(
                "Tài khoản này không được đăng ký cửa hàng.");
        }

        if (shopRepository.existsByOwnerId(owner.getId())) {
            throw new IllegalArgumentException("Bạn đã có cửa hàng.");
        }

        Shop shop = new Shop();
        shop.setOwner(owner);
        shop.setSlug(uniqueSlug(form.getName()));
        shop.setStatus(ShopStatus.PENDING);
        shop.setEnabled(true);
        applyShopDetails(shop, form);

        Shop saved = shopRepository.save(shop);

        if (owner.getRole() == Role.USER) {
            owner.setRole(Role.VENDOR);
            userRepository.save(owner);
        }

        return saved;
    }

    @Transactional
    public void updateShop(User user, ShopForm form) {
        Shop shop = requireShop(user);
        applyShopDetails(shop, form);
    }

    private void applyShopDetails(Shop shop, ShopForm form) {
        shop.setName(form.getName().trim());
        shop.setDescription(form.getDescription().trim());
        shop.setPhone(form.getPhone().trim());
        shop.setEmail(form.getEmail().trim().toLowerCase(Locale.ROOT));
        shop.setAddress(form.getAddress().trim());
        shop.setProvince(form.getProvince().trim());
        shop.setDistrict(form.getDistrict().trim());
        shop.setWard(form.getWard().trim());
        shop.setLatitude(form.getLatitude());
        shop.setLongitude(form.getLongitude());
        shop.setOpeningTime(form.getOpeningTime());
        shop.setClosingTime(form.getClosingTime());
    }

    private String uniqueSlug(String name) {
        String base = SlugUtils.toSlug(name);
        String slug = base;

        for (int suffix = 2; shopRepository.existsBySlug(slug); suffix++) {
            slug = base + "-" + suffix;
        }

        return slug;
    }
}