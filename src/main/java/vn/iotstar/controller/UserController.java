package vn.iotstar.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;
import vn.iotstar.dto.WebForms.AddressForm;
import vn.iotstar.dto.WebForms.BankForm;
import vn.iotstar.dto.WebForms.ChangePasswordForm;
import vn.iotstar.dto.WebForms.ProfileForm;
import vn.iotstar.entity.Address;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.IceLevel;
import vn.iotstar.entity.DomainEnums.SugarLevel;
import vn.iotstar.service.*;
import java.util.List;

@Controller
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {
    private final CurrentUserService currentUserService;
    private final ProfileService profileService;
    private final EngagementService engagementService;
    private final CatalogService catalogService;
    private final NotificationService notificationService;
    private final ProductPricingService productPricingService;
    private final ShopFollowService shopFollowService;
    private final SavedRecipeService savedRecipeService;
    private final LoyaltyPointService loyaltyPointService;

    @GetMapping
    String userHome() {
        return "redirect:/user/profile";
    }

    @GetMapping("/profile")
    String profile(@RequestParam(defaultValue = "profile") String tab,
                   @RequestParam(required = false) Long editAddress,
                   @RequestParam(required = false) Long returnOrder, Model model) {
        User user = currentUserService.require();
        prepareAccountPage(user, model, editAddress != null ? "addresses" : normalizeTab(tab), editAddress);
        model.addAttribute("returnOrder", returnOrder);
        return "user/profile";
    }

    @PostMapping("/profile")
    String updateProfile(@Valid @ModelAttribute("profileForm") ProfileForm profileForm, BindingResult result,
                         Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            prepareAccountPage(currentUserService.require(), model, "profile", null);
            return "user/profile";
        }
        profileService.updateProfile(currentUserService.require(), profileForm);
        redirect.addFlashAttribute("success", "Đã cập nhật hồ sơ.");
        return "redirect:/user/profile?tab=profile";
    }

    @PostMapping("/bank")
    String updateBank(@Valid @ModelAttribute("bankForm") BankForm bankForm, BindingResult result,
                      @RequestParam(required = false) Long returnOrder,
                      Model model, RedirectAttributes redirect) {
        boolean hasAnyBankValue = hasText(bankForm.getBankName()) || hasText(bankForm.getAccountName())
            || hasText(bankForm.getAccountNumber());
        if (hasAnyBankValue) {
            if (!hasText(bankForm.getBankName())) result.rejectValue("bankName", "required", "Vui lòng chọn ngân hàng");
            if (!hasText(bankForm.getAccountName())) result.rejectValue("accountName", "required", "Vui lòng nhập tên chủ tài khoản");
            if (!hasText(bankForm.getAccountNumber())) result.rejectValue("accountNumber", "required", "Vui lòng nhập số tài khoản");
        }
        if (result.hasErrors()) {
            prepareAccountPage(currentUserService.require(), model, "bank", null);
            model.addAttribute("returnOrder", returnOrder);
            return "user/profile";
        }
        profileService.updateBank(currentUserService.require(), bankForm);
        redirect.addFlashAttribute("success", hasAnyBankValue ? "Đã cập nhật thông tin ngân hàng." : "Đã xóa thông tin ngân hàng.");
        if (hasAnyBankValue && returnOrder != null && returnOrder > 0) {
            return "redirect:/orders/" + returnOrder + "/return";
        }
        return "redirect:/user/profile?tab=bank";
    }

    @PostMapping("/password")
    String changePassword(@Valid @ModelAttribute("passwordForm") ChangePasswordForm passwordForm,
                          BindingResult result, Model model, RedirectAttributes redirect) {
        if (hasText(passwordForm.getNewPassword())
            && !passwordForm.getNewPassword().equals(passwordForm.getConfirmPassword())) {
            result.rejectValue("confirmPassword", "mismatch", "Mật khẩu xác nhận không khớp");
        }
        if (!result.hasErrors()) {
            try {
                profileService.changePassword(currentUserService.require(), passwordForm.getCurrentPassword(), passwordForm.getNewPassword());
            } catch (IllegalArgumentException ex) {
                String field = ex.getMessage().startsWith("Mật khẩu mới") ? "newPassword" : "currentPassword";
                result.rejectValue(field, "invalid", ex.getMessage());
            }
        }
        if (result.hasErrors()) {
            prepareAccountPage(currentUserService.require(), model, "password", null);
            return "user/profile";
        }
        redirect.addFlashAttribute("success", "Đã đổi mật khẩu thành công.");
        return "redirect:/user/profile?tab=password";
    }

    @PostMapping("/avatar")
    String updateAvatar(@RequestParam(required = false) MultipartFile avatar, Model model, RedirectAttributes redirect) {
        try {
            profileService.updateAvatar(currentUserService.require(), avatar);
            redirect.addFlashAttribute("success", "Đã cập nhật ảnh đại diện.");
            return "redirect:/user/profile?tab=avatar";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            String message = ex instanceof IllegalArgumentException
                ? ex.getMessage() : "Không thể lưu ảnh đại diện lúc này. Vui lòng thử lại.";
            model.addAttribute("avatarError", message);
            prepareAccountPage(currentUserService.require(), model, "avatar", null);
            return "user/profile";
        }
    }

    @PostMapping("/avatar/remove")
    String removeAvatar(RedirectAttributes redirect) {
        profileService.removeAvatar(currentUserService.require());
        redirect.addFlashAttribute("success", "Đã xóa ảnh đại diện.");
        return "redirect:/user/profile?tab=avatar";
    }

    @PostMapping("/addresses/save")
    String saveAddress(@RequestParam(required = false) Long id,
                       @Valid @ModelAttribute("addressForm") AddressForm addressForm, BindingResult result,
                       Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            prepareAccountPage(currentUserService.require(), model, "addresses", id);
            return "user/profile";
        }
        profileService.saveAddress(currentUserService.require(), id, addressForm);
        redirect.addFlashAttribute("success", "Đã lưu địa chỉ nhận hàng.");
        return "redirect:/user/profile?tab=addresses";
    }

    @PostMapping("/addresses/{id}/default")
    String makeDefault(@PathVariable Long id, RedirectAttributes redirect) {
        profileService.makeDefault(currentUserService.require(), id);
        redirect.addFlashAttribute("success", "Đã đặt làm địa chỉ mặc định.");
        return "redirect:/user/profile?tab=addresses";
    }

    @PostMapping("/addresses/{id}/delete")
    String deleteAddress(@PathVariable Long id, RedirectAttributes redirect) {
        profileService.deleteAddress(currentUserService.require(), id);
        redirect.addFlashAttribute("success", "Đã xóa địa chỉ.");
        return "redirect:/user/profile?tab=addresses";
    }

    @PostMapping("/favorites/{productId}")
    String favorite(@PathVariable Long productId, @RequestParam(defaultValue = "/user/favorites") String returnUrl,
                    RedirectAttributes redirect) {
        Product product = catalogService.getPublicProduct(productId);
        boolean liked = engagementService.toggleFavorite(currentUserService.require(), product);
        redirect.addFlashAttribute("success", liked ? "Đã thêm vào yêu thích." : "Đã bỏ yêu thích.");
        return "redirect:" + (returnUrl.startsWith("/") && !returnUrl.startsWith("//") ? returnUrl : "/user/favorites");
    }

    @PostMapping("/favorites/{productId}/toggle")
    @ResponseBody
    FavoriteToggleResult toggleFavorite(@PathVariable Long productId) {
        Product product = catalogService.getPublicProduct(productId);
        boolean liked = engagementService.toggleFavorite(currentUserService.require(), product);
        return new FavoriteToggleResult(liked, product.getFavoriteCount());
    }

    public record FavoriteToggleResult(boolean liked, long count) {}

    @GetMapping("/favorites")
    String favorites(@RequestParam(defaultValue = "0") int page, Model model) {
        var favorites = engagementService.favorites(currentUserService.require(), page);
        model.addAttribute("favorites", favorites);
        model.addAttribute("promotionPrices", productPricingService.prices(
            favorites.getContent().stream().map(favorite -> favorite.getProduct()).toList()));
        return "user/favorites";
    }

    @GetMapping("/followed-shops")
    String followedShops(@RequestParam(defaultValue = "0") int page, Model model) {
        User user = currentUserService.require();
        prepareAccountNavigation(user, model, "followed-shops");
        model.addAttribute("followedShops", shopFollowService.followedShops(user, page));
        return "user/followed-shops";
    }

    @PostMapping("/followed-shops/{shopId}/follow")
    String followShop(@PathVariable Long shopId, RedirectAttributes redirect) {
        try {
            boolean created = shopFollowService.follow(currentUserService.require(), shopId);
            redirect.addFlashAttribute("success", created ? "Đã theo dõi cửa hàng." : "Bạn đã theo dõi cửa hàng này.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return shopFollowRedirect(shopId);
    }

    @PostMapping("/followed-shops/{shopId}/unfollow")
    String unfollowShop(@PathVariable Long shopId, @RequestParam(defaultValue = "shop") String from,
                        RedirectAttributes redirect) {
        try {
            boolean removed = shopFollowService.unfollow(currentUserService.require(), shopId);
            redirect.addFlashAttribute("success", removed ? "Đã bỏ theo dõi cửa hàng." : "Cửa hàng không còn trong danh sách theo dõi.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "list".equals(from) ? "redirect:/user/followed-shops" : shopFollowRedirect(shopId);
    }

    private String shopFollowRedirect(Long shopId) {
        return shopFollowService.publicShopSlug(shopId)
            .map(slug -> "redirect:" + UriComponentsBuilder.fromPath("/shops/{slug}")
                .buildAndExpand(slug).encode().toUriString())
            .orElse("redirect:/user/followed-shops");
    }

    @GetMapping("/viewed")
    String viewed(@RequestParam(defaultValue = "0") int page, Model model) {
        var viewed = engagementService.viewed(currentUserService.require(), page);
        model.addAttribute("viewed", viewed);
        model.addAttribute("promotionPrices", productPricingService.prices(
            viewed.getContent().stream().map(productView -> productView.getProduct()).toList()));
        return "user/viewed";
    }

    @GetMapping("/recipes")
    String recipes(@RequestParam(defaultValue = "0") int page, Model model) {
        User user = currentUserService.require();
        prepareAccountNavigation(user, model, "recipes");
        model.addAttribute("recipes", savedRecipeService.list(user, page));
        return "user/recipes";
    }

    @PostMapping("/recipes/from-product/{productId}")
    String saveRecipe(@PathVariable Long productId,
                      @RequestParam(required = false) String recipeName,
                      @RequestParam(required = false) Long sizeId,
                      @RequestParam(required = false) SugarLevel sugarLevel,
                      @RequestParam(required = false) IceLevel iceLevel,
                      @RequestParam(required = false) List<Long> toppingIds,
                      RedirectAttributes redirect) {
        try {
            savedRecipeService.save(currentUserService.require(), productId, recipeName, sizeId,
                sugarLevel, iceLevel, toppingIds);
            redirect.addFlashAttribute("success", "Đã lưu công thức của bạn.");
            return "redirect:/user/recipes";
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/products/" + productId;
        }
    }

    @PostMapping("/recipes/{id}/cart")
    String addRecipeToCart(@PathVariable Long id, @RequestParam(defaultValue = "1") int quantity,
                           RedirectAttributes redirect) {
        try {
            savedRecipeService.addToCart(currentUserService.require(), id, quantity);
            redirect.addFlashAttribute("success", "Đã thêm công thức vào giỏ với giá hiện tại. Món cùng cấu hình đã có trong giỏ sẽ được cộng số lượng.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/user/recipes";
    }

    @PostMapping("/recipes/{id}/rename")
    String renameRecipe(@PathVariable Long id, @RequestParam String name,
                        RedirectAttributes redirect) {
        try {
            savedRecipeService.rename(currentUserService.require(), id, name);
            redirect.addFlashAttribute("success", "Đã đổi tên công thức.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/user/recipes";
    }

    @PostMapping("/recipes/{id}/delete")
    String deleteRecipe(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            savedRecipeService.delete(currentUserService.require(), id);
            redirect.addFlashAttribute("success", "Đã xóa công thức.");
        } catch (IllegalArgumentException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/user/recipes";
    }

    @GetMapping("/notifications")
    String notifications(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("notifications", notificationService.list(currentUserService.require(), page));
        return "user/notifications";
    }

    @GetMapping("/loyalty")
    String loyalty(@RequestParam(defaultValue = "0") int page, Model model) {
        User user = currentUserService.require();
        prepareAccountNavigation(user, model, "loyalty");
        model.addAttribute("pointBalance", loyaltyPointService.balance(user));
        model.addAttribute("pointHistory", loyaltyPointService.history(user, page));
        model.addAttribute("vndPerPoint", loyaltyPointService.getVndPerPoint());
        return "user/loyalty";
    }

    @PostMapping("/notifications/{id}/read")
    String read(@PathVariable Long id) {
        notificationService.markRead(currentUserService.require(), id);
        return "redirect:/user/notifications";
    }

    private void prepareAccountNavigation(User user, Model model, String activeTab) {
        model.addAttribute("activeTab", activeTab);
        model.addAttribute("addresses", profileService.addresses(user));
    }

    private void prepareAccountPage(User user, Model model, String activeTab, Long editAddress) {
        prepareAccountNavigation(user, model, activeTab);
        model.addAttribute("editAddressId", editAddress);

        if (!model.containsAttribute("profileForm")) {
            ProfileForm form = new ProfileForm();
            form.setFullName(user.getFullName());
            form.setPhone(user.getPhone());
            model.addAttribute("profileForm", form);
        }
        if (!model.containsAttribute("bankForm")) {
            BankForm form = new BankForm();
            form.setBankName(user.getBankName());
            form.setAccountName(user.getBankAccountName());
            form.setAccountNumber(user.getBankAccountNumber());
            model.addAttribute("bankForm", form);
        }
        if (!model.containsAttribute("passwordForm")) model.addAttribute("passwordForm", new ChangePasswordForm());
        if (!model.containsAttribute("addressForm")) {
            AddressForm form = new AddressForm();
            if (editAddress != null) {
                Address address = profileService.addressForEdit(user, editAddress);
                form.setReceiverName(address.getReceiverName());
                form.setPhone(address.getPhone());
                form.setProvince(address.getProvince());
                form.setDistrict(address.getDistrict());
                form.setWard(address.getWard());
                form.setDetail(address.getDetail());
                form.setDefaultAddress(address.isDefaultAddress());
            }
            model.addAttribute("addressForm", form);
        }
    }

    private String normalizeTab(String tab) {
        return switch (tab) {
            case "profile", "avatar", "bank", "addresses", "password" -> tab;
            default -> "profile";
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
