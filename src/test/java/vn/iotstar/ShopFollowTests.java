package vn.iotstar;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.entity.DomainEnums.PromotionType;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.Promotion;
import vn.iotstar.entity.Shop;
import vn.iotstar.entity.User;
import vn.iotstar.repository.PromotionRepository;
import vn.iotstar.repository.ShopRepository;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.service.CatalogService;
import vn.iotstar.service.ShopFollowService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ShopFollowTests {
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private ShopRepository shopRepository;
    @Autowired private PromotionRepository promotionRepository;
    @Autowired private ShopFollowService shopFollowService;
    @Autowired private CatalogService catalogService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void followIsScopedToUserIdempotentAndDoesNotChangeVendorOwnership() {
        String suffix = suffix();
        User vendor = user("follow-vendor-" + suffix, Role.VENDOR);
        User customer = user("follow-customer-" + suffix, Role.USER);
        User otherCustomer = user("follow-other-" + suffix, Role.USER);
        Shop shop = shop(vendor, "follow-shop-" + suffix);

        assertTrue(shopFollowService.follow(customer, shop.getId()));
        assertFalse(shopFollowService.follow(customer, shop.getId()));
        assertTrue(shopFollowService.isFollowing(customer, shop.getId()));
        assertFalse(shopFollowService.isFollowing(otherCustomer, shop.getId()));
        assertEquals(1, shopFollowService.followedShops(customer, 0).getTotalElements());
        assertTrue(shopFollowService.followedShops(otherCustomer, 0).isEmpty());
        assertEquals(vendor.getId(), shopRepository.findById(shop.getId()).orElseThrow().getOwner().getId());

        shop.setStatus(ShopStatus.SUSPENDED);
        assertThrows(IllegalArgumentException.class, () -> shopFollowService.follow(otherCustomer, shop.getId()));
        assertTrue(shopFollowService.publicShopSlug(shop.getId()).isEmpty());
        assertTrue(shopFollowService.unfollow(customer, shop.getId()));
        assertFalse(shopFollowService.unfollow(customer, shop.getId()));
        assertFalse(shopFollowService.isFollowing(customer, shop.getId()));
        assertEquals(vendor.getId(), shopRepository.findById(shop.getId()).orElseThrow().getOwner().getId());
    }

    @Test
    void vendorHasUserFollowRightsButManagerDoesNot() {
        String suffix = suffix();
        User vendor = user("follow-vendor-" + suffix, Role.VENDOR);
        User manager = user("follow-manager-" + suffix, Role.MANAGER);
        Shop shop = shop(user("follow-owner-" + suffix, Role.VENDOR), "follow-shop-" + suffix);

        assertTrue(shopFollowService.follow(vendor, shop.getId()));
        assertThrows(IllegalArgumentException.class, () -> shopFollowService.follow(manager, shop.getId()));
        assertEquals(1, shopFollowService.followedShops(vendor, 0).getTotalElements());
    }

    @Test
    void publicShopShowsOnlyItsRunningPromotions() {
        String suffix = suffix();
        Shop shop = shop(user("follow-owner-" + suffix, Role.VENDOR), "follow-shop-" + suffix);
        Shop other = shop(user("follow-other-owner-" + suffix, Role.VENDOR), "follow-other-shop-" + suffix);
        Promotion running = promotion(shop, "Đang giảm phí ship", LocalDateTime.now().plusDays(1));
        promotion(shop, "Đã hết hạn", LocalDateTime.now().minusDays(1));
        promotion(other, "Cửa hàng khác", LocalDateTime.now().plusDays(1));

        assertEquals(1, catalogService.activeShopPromotions(shop).size());
        assertEquals(running.getId(), catalogService.activeShopPromotions(shop).getFirst().getId());
    }

    @Test
    @WithMockUser(username = "shop-follow-mvc", roles = "USER")
    void userCanFollowFromShopAndOpenSavedShopPage() throws Exception {
        User customer = user("shop-follow-mvc", Role.USER);
        Shop shop = shop(user("shop-follow-mvc-owner", Role.VENDOR), "follow-mvc-" + suffix());
        promotion(shop, "Ưu đãi đang chạy", LocalDateTime.now().plusDays(1));

        mockMvc.perform(get("/shops/{slug}", shop.getSlug()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Theo dõi cửa hàng")))
            .andExpect(content().string(containsString("/user/followed-shops/" + shop.getId() + "/follow")))
            .andExpect(content().string(containsString("Ưu đãi đang chạy")));
        mockMvc.perform(post("/user/followed-shops/{id}/follow", shop.getId()).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/shops/" + shop.getSlug()));
        assertTrue(shopFollowService.isFollowing(customer, shop.getId()));
        mockMvc.perform(get("/shops/{slug}", shop.getSlug()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("/user/followed-shops/" + shop.getId() + "/unfollow")));
        mockMvc.perform(get("/user/followed-shops"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("account-hero")))
            .andExpect(content().string(containsString("account-nav")))
            .andExpect(content().string(containsString(shop.getName())))
            .andExpect(content().string(containsString("/shops/" + shop.getSlug() + "#shop-promotions")));
        mockMvc.perform(post("/user/followed-shops/{id}/unfollow", shop.getId()).with(csrf())
                .param("from", "list"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/user/followed-shops"));
        assertFalse(shopFollowService.isFollowing(customer, shop.getId()));
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private User user(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@utetra.test");
        user.setPasswordHash("encoded-password");
        user.setFullName(username);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private Shop shop(User vendor, String slug) {
        Shop shop = new Shop();
        shop.setOwner(vendor);
        shop.setName("UTETra " + slug);
        shop.setSlug(slug);
        shop.setStatus(ShopStatus.APPROVED);
        return shopRepository.save(shop);
    }

    private Promotion promotion(Shop shop, String name, LocalDateTime endsAt) {
        Promotion promotion = new Promotion();
        promotion.setName(name);
        promotion.setShop(shop);
        promotion.setType(PromotionType.SHIPPING_DISCOUNT);
        promotion.setDiscountValue(new BigDecimal("10000"));
        promotion.setStartsAt(LocalDateTime.now().minusDays(2));
        promotion.setEndsAt(endsAt);
        return promotionRepository.save(promotion);
    }
}
