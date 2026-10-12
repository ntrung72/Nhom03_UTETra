package vn.iotstar.controller;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.entity.DomainEnums.ShopStatus;
import vn.iotstar.entity.User;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.ManagementService;

/** Step 13 only: dashboard shell, users, shops and Manager assignments. */
@Controller
@RequestMapping("/management")
@RequiredArgsConstructor
public class ManagementController {
    private final CurrentUserService currentUserService;
    private final ManagementService managementService;

    @ModelAttribute("currentUser")
    User currentUser() { return currentUserService.optional().orElse(null); }

    @GetMapping({"", "/"})
    String dashboard(Model model) {
        model.addAttribute("summary", managementService.dashboard(currentUserService.require()));
        return "management/dashboard";
    }

    @GetMapping("/users")
    String users(@RequestParam(required = false) String q,
                 @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("users", managementService.users(currentUserService.require(), q, page));
        model.addAttribute("roles", Role.values());
        model.addAttribute("q", q);
        return "management/users";
    }

    @PostMapping("/users/{id}/toggle-lock")
    String toggleUser(@PathVariable Long id, RedirectAttributes flash) {
        try {
            managementService.toggleUser(id, currentUserService.require());
            flash.addFlashAttribute("success", "Đã cập nhật trạng thái khóa tài khoản.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/users";
    }

    @PostMapping("/users/{id}/role")
    String changeRole(@PathVariable Long id, @RequestParam Role role, RedirectAttributes flash) {
        try {
            managementService.changeRole(id, role, currentUserService.require());
            flash.addFlashAttribute("success", "Đã cập nhật vai trò người dùng.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/users";
    }

    @GetMapping("/shops")
    String shops(@RequestParam(required = false) String q,
                 @RequestParam(required = false) ShopStatus status,
                 @RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("shops", managementService.shops(currentUserService.require(), q, status, page));
        model.addAttribute("statuses", ShopStatus.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("q", q);
        return "management/shops";
    }

    @PostMapping("/shops/{id}")
    String updateShop(@PathVariable Long id, @RequestParam ShopStatus status,
                      @RequestParam BigDecimal commissionRate, RedirectAttributes flash) {
        try {
            managementService.updateShop(currentUserService.require(), id, status, commissionRate);
            flash.addFlashAttribute("success", "Đã cập nhật cửa hàng.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/shops";
    }

    @GetMapping("/managers")
    String managers(Model model) {
        User actor = currentUserService.require();
        model.addAttribute("managers", managementService.managers(actor));
        model.addAttribute("shops", managementService.accessibleShops(actor));
        model.addAttribute("assignments", managementService.managerAssignments(actor));
        return "management/managers";
    }

    @PostMapping("/managers/{id}/shops")
    String assignShops(@PathVariable Long id, @RequestParam(required = false) List<Long> shopIds,
                       RedirectAttributes flash) {
        try {
            managementService.assignManagerShops(currentUserService.require(), id, shopIds);
            flash.addFlashAttribute("success", "Đã cập nhật phân công cửa hàng cho Manager.");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/management/managers";
    }
}
