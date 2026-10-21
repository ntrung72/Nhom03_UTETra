package vn.iotstar.controller;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.iotstar.dto.WebForms.AddressForm;
import vn.iotstar.dto.WebForms.BankForm;
import vn.iotstar.dto.WebForms.ChangePasswordForm;
import vn.iotstar.dto.WebForms.ProfileForm;
import vn.iotstar.entity.Address;
import vn.iotstar.entity.Product;
import vn.iotstar.entity.User;
import vn.iotstar.service.*;

@Controller
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {
    private final CurrentUserService currentUserService;
    private final SavedRecipeService recipes;
    private final ShopFollowService follows;
    private final ProfileService profileService;
    private final EngagementService engagementService;
    private final ProductPricingService productPricingService;
    private final NotificationService notificationService;

    @GetMapping("/recipes")
    String recipes(@RequestParam(defaultValue="0") int page, Model model) {
        model.addAttribute("recipes", recipes.list(currentUserService.require(), page)); return "user/recipes";
    }
    @PostMapping("/recipes/from-product/{productId}")
    String saveRecipe(@PathVariable Long productId, @RequestParam(required=false) String name,
        @RequestParam(required=false) Long sizeId, @RequestParam(required=false) vn.iotstar.entity.DomainEnums.SugarLevel sugarLevel,
        @RequestParam(required=false) vn.iotstar.entity.DomainEnums.IceLevel iceLevel,
        @RequestParam(required=false) List<Long> toppingIds, RedirectAttributes redirect) {
        try { recipes.save(currentUserService.require(), productId, name, sizeId, sugarLevel, iceLevel, toppingIds);
            redirect.addFlashAttribute("success", "Đã lưu công thức của bạn."); return "redirect:/user/recipes";
        } catch (IllegalArgumentException ex) { redirect.addFlashAttribute("error", ex.getMessage()); return "redirect:/products/"+productId; }
    }
    @PostMapping("/recipes/{id}/cart")
    String recipeCart(@PathVariable Long id, @RequestParam(defaultValue="1") int quantity, RedirectAttributes redirect) {
        try { recipes.addToCart(currentUserService.require(), id, quantity); redirect.addFlashAttribute("success", "Đã thêm công thức với giá hiện tại."); return "redirect:/cart"; }
        catch (IllegalArgumentException ex) { redirect.addFlashAttribute("error", ex.getMessage()); return "redirect:/user/recipes"; }
    }
    @PostMapping("/recipes/{id}/rename")
    String renameRecipe(@PathVariable Long id, @RequestParam String name, RedirectAttributes redirect) {
        try { recipes.rename(currentUserService.require(), id, name); redirect.addFlashAttribute("success", "Đã đổi tên công thức."); }
        catch (IllegalArgumentException ex) { redirect.addFlashAttribute("error", ex.getMessage()); } return "redirect:/user/recipes";
    }
    @PostMapping("/recipes/{id}/delete")
    String deleteRecipe(@PathVariable Long id, RedirectAttributes redirect) {
        try { recipes.delete(currentUserService.require(), id); redirect.addFlashAttribute("success", "Đã xóa công thức."); }
        catch (IllegalArgumentException ex) { redirect.addFlashAttribute("error", ex.getMessage()); } return "redirect:/user/recipes";
    }
    @GetMapping("/followed-shops")
    String followedShops(@RequestParam(defaultValue="0") int page, Model model) {
        model.addAttribute("followedShops", follows.followedShops(currentUserService.require(),page));return "user/followed-shops";
    }
    @PostMapping("/followed-shops/{id}/follow")
    String follow(@PathVariable Long id, RedirectAttributes redirect) {
        try { follows.follow(currentUserService.require(),id);redirect.addFlashAttribute("success","Đã theo dõi cửa hàng."); }
        catch(IllegalArgumentException ex){redirect.addFlashAttribute("error",ex.getMessage());}return "redirect:/user/followed-shops";
    }
    @PostMapping("/followed-shops/{id}/unfollow")
    String unfollow(@PathVariable Long id, RedirectAttributes redirect) {
        follows.unfollow(currentUserService.require(),id);redirect.addFlashAttribute("success","Đã bỏ theo dõi cửa hàng.");return "redirect:/user/followed-shops";
    }

    @GetMapping
    String userHome() {
        return "redirect:/user/profile";
    }

    @GetMapping("/profile")
    String profile(@RequestParam(defaultValue = "profile") String tab,
                   @RequestParam(required = false) Long editAddress, Model model) {
        User user = currentUserService.require();
        prepareAccountPage(user, model, editAddress != null ? "addresses" : normalizeTab(tab), editAddress);
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
            return "user/profile";
        }
        profileService.updateBank(currentUserService.require(), bankForm);
        redirect.addFlashAttribute("success", hasAnyBankValue ? "Đã cập nhật thông tin ngân hàng." : "Đã xóa thông tin ngân hàng.");
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
            passwordForm.setCurrentPassword(null);
            passwordForm.setNewPassword(null);
            passwordForm.setConfirmPassword(null);
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
    String favorite(@PathVariable Long productId, RedirectAttributes redirect) {
        boolean liked = engagementService.toggleFavorite(currentUserService.require(), productId);
        redirect.addFlashAttribute("success", liked ? "Đã thêm vào yêu thích." : "Đã bỏ yêu thích.");
        return "redirect:/user/favorites";
    }

    @GetMapping("/favorites")
    String favorites(@RequestParam(defaultValue = "0") int page, Model model) {
        var favorites = engagementService.favorites(currentUserService.require(), page);
        model.addAttribute("favorites", favorites);
        model.addAttribute("promotionPrices", productPricingService.prices(
            favorites.getContent().stream().map(favorite -> favorite.getProduct()).toList()));
        return "user/favorites";
    }

    @GetMapping("/viewed")
    String viewed(@RequestParam(defaultValue = "0") int page, Model model) {
        var viewed = engagementService.viewed(currentUserService.require(), page);
        model.addAttribute("viewed", viewed);
        model.addAttribute("promotionPrices", productPricingService.prices(
            viewed.getContent().stream().map(productView -> productView.getProduct()).toList()));
        return "user/viewed";
    }

    @GetMapping("/notifications")
    String notifications(@RequestParam(defaultValue = "0") int page, Model model) {
        User user = currentUserService.require();
        var notifications = notificationService.list(user, page);
        var links = new java.util.LinkedHashMap<Long, String>();
        for (var notification : notifications.getContent()) {
            String link = notificationLink(user, notification.getLink());
            if (link != null) links.put(notification.getId(), link);
        }
        model.addAttribute("notifications", notifications);
        model.addAttribute("notificationLinks", links);
        return "user/notifications";
    }

    @PostMapping("/notifications/{id}/read")
    String readNotification(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
                            RedirectAttributes redirect) {
        try {
            notificationService.markRead(currentUserService.require(), id);
        } catch (IllegalArgumentException ex) {
            // Return the same response for missing IDs and another recipient's notifications.
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Không tìm thấy thông báo.");
        }
        redirect.addFlashAttribute("success", "Đã đánh dấu thông báo là đã đọc.");
        return "redirect:/user/notifications?page=" + Math.max(0, page);
    }

    private String notificationLink(User user, String value) {
        if (value == null) return null;
        String link = value.trim();
        if (link.matches("/(orders|products)(/[1-9][0-9]*)?")
            || link.matches("/shops/[A-Za-z0-9_-]+")) return link;
        if (user.getRole() == vn.iotstar.entity.DomainEnums.Role.VENDOR
            && link.matches("/vendor/orders(/[1-9][0-9]*)?")) return link;
        return null;
    }

    private void prepareAccountPage(User user, Model model, String activeTab, Long editAddress) {
        model.addAttribute("activeTab", activeTab);
        model.addAttribute("addresses", profileService.addresses(user));
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
