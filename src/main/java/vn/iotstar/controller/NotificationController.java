package vn.iotstar.controller;

import java.util.Map;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.NotificationService;

@Controller
@RequiredArgsConstructor
public class NotificationController {
    private final CurrentUserService currentUserService;
    private final NotificationService notificationService;

    @GetMapping("/notifications")
    String notifications(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("notifications", notificationService.list(currentUserService.require(), page));
        return "user/notifications";
    }

    @PostMapping("/notifications/{id}/read")
    String markRead(@PathVariable Long id) {
        notificationService.markRead(currentUserService.require(), id);
        return "redirect:/notifications";
    }

    @GetMapping("/api/notifications/unread-count")
    @ResponseBody
    Map<String, Long> unreadCount() {
        return Map.of("count", notificationService.unreadCount(currentUserService.require()));
    }

    @GetMapping("/api/notifications/recent")
    @ResponseBody
    List<NotificationService.NotificationPreview> recent() {
        return notificationService.recent(currentUserService.require());
    }
}
