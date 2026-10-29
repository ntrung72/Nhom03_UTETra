package vn.iotstar.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import vn.iotstar.entity.User;
import vn.iotstar.entity.DomainEnums.Role;
import vn.iotstar.service.CartService;
import vn.iotstar.service.CatalogService;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.NotificationService;

@ControllerAdvice
@RequiredArgsConstructor
public class CommonModelAdvice {
    private final CurrentUserService currentUserService;
    private final CatalogService catalogService;
    private final CartService cartService;
    private final NotificationService notificationService;

    @ModelAttribute("currentUser")
    public User currentUser() { return currentUserService.optional().orElse(null); }

    @ModelAttribute("navCategories")
    public Object categories() { return catalogService.categories(); }

    @ModelAttribute("cartCount")
    public int cartCount() {
        return currentUserService.optional()
            .filter(user -> user.getRole() == Role.USER || user.getRole() == Role.VENDOR)
            .map(user -> cartService.findExisting(user).map(cart -> cart.getItems().size()).orElse(0))
            .orElse(0);
    }

    @ModelAttribute("unreadNotifications")
    public long unreadNotifications() {
        return currentUserService.optional().map(notificationService::unreadCount).orElse(0L);
    }

    @ModelAttribute
    public void accessDeniedMessage(HttpServletRequest request, Model model) {
        Object message = request.getSession(false) == null ? null
            : request.getSession(false).getAttribute("accessDeniedError");
        if (message != null) {
            request.getSession(false).removeAttribute("accessDeniedError");
            if (!model.containsAttribute("error")) model.addAttribute("error", message);
        }
    }
}
