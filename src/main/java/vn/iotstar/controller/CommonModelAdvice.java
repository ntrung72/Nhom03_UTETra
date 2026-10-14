package vn.iotstar.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import vn.iotstar.entity.User;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.NotificationService;

@ControllerAdvice(assignableTypes = {AuthController.class, HomeController.class, ProductController.class, UserController.class, CartController.class, OrderController.class, VendorController.class, ManagementController.class, ShipperController.class})
@RequiredArgsConstructor
public class CommonModelAdvice {
    private final CurrentUserService currentUserService;
    private final NotificationService notificationService;

    @ModelAttribute("unreadNotifications")
    long unreadNotifications() {
        return currentUserService.optional().map(notificationService::unreadCount).orElse(0L);
    }

    @ModelAttribute("currentUser")
    User currentUser() {
        return currentUserService.optional().orElse(null);
    }
}
