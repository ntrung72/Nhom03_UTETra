package vn.iotstar.config;

import java.security.Principal;
import java.util.Set;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class WebSocketSubscriptionInterceptor implements ChannelInterceptor {
    private static final Set<String> ALLOWED_SUBSCRIPTIONS = Set.of(
        "/user/queue/notifications", "/user/queue/order-status");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;

        StompCommand command = accessor.getCommand();
        if (command == StompCommand.DISCONNECT) return message;

        Principal principal = accessor.getUser();
        if (!(principal instanceof Authentication authentication)
            || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("WebSocket yêu cầu đăng nhập.");
        }

        if (command == StompCommand.SUBSCRIBE
            && !ALLOWED_SUBSCRIPTIONS.contains(accessor.getDestination())) {
            throw new AccessDeniedException("Không được đăng ký kênh WebSocket này.");
        }
        if (command != StompCommand.CONNECT && command != StompCommand.SUBSCRIBE
            && command != StompCommand.UNSUBSCRIBE) {
            throw new AccessDeniedException("Lệnh WebSocket này không được hỗ trợ.");
        }
        return message;
    }
}
