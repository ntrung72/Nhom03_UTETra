package vn.iotstar.controller;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.iotstar.service.CurrentUserService;
import vn.iotstar.service.NotificationService;

/** Authenticated, session-backed browser notification preview endpoints. */
@RestController
@RequiredArgsConstructor
public class NotificationController {
    private final CurrentUserService currentUserService;
    private final NotificationService notificationService;

    @GetMapping("/api/notifications/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notificationService.unreadCount(currentUserService.require()));
    }

    @GetMapping("/api/notifications/recent")
    public List<NotificationService.NotificationPreview> recent() {
        return notificationService.recent(currentUserService.require());
    }
}
