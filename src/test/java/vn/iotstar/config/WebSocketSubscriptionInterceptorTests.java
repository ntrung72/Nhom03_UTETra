package vn.iotstar.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class WebSocketSubscriptionInterceptorTests {
    private final WebSocketSubscriptionInterceptor interceptor = new WebSocketSubscriptionInterceptor();

    @Test
    void authenticatedUserCanSubscribeOnlyToOwnNotificationDestinations() {
        assertDoesNotThrow(() -> send(StompCommand.CONNECT, null, true));
        assertDoesNotThrow(() -> send(StompCommand.UNSUBSCRIBE, null, true));
        assertDoesNotThrow(() -> send(StompCommand.DISCONNECT, null, false));
        assertDoesNotThrow(() -> send(StompCommand.SUBSCRIBE, "/user/queue/notifications", true));
        assertDoesNotThrow(() -> send(StompCommand.SUBSCRIBE, "/user/queue/order-status", true));
        assertThrows(AccessDeniedException.class,
            () -> send(StompCommand.SUBSCRIBE, "/topic/orders", true));
        assertThrows(AccessDeniedException.class,
            () -> send(StompCommand.SUBSCRIBE, "/queue/notifications-user123", true));
        assertThrows(AccessDeniedException.class,
            () -> send(StompCommand.SUBSCRIBE, "/user/other/queue/notifications", true));
    }

    @Test
    void anonymousUserCannotConnectOrSubscribe() {
        assertThrows(AccessDeniedException.class, () -> send(StompCommand.CONNECT, null, false));
        assertThrows(AccessDeniedException.class,
            () -> send(StompCommand.SUBSCRIBE, "/user/queue/notifications", false));
    }

    @Test
    void clientCannotPublishToBrokerDestinations() {
        assertThrows(AccessDeniedException.class,
            () -> send(StompCommand.SEND, "/topic/orders", true));
        assertThrows(AccessDeniedException.class,
            () -> send(StompCommand.SEND, "/queue/order-status", true));
    }

    private void send(StompCommand command, String destination, boolean authenticated) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) accessor.setDestination(destination);
        if (authenticated) {
            accessor.setUser(new UsernamePasswordAuthenticationToken("customer", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        }
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        interceptor.preSend(message, new ExecutorSubscribableChannel());
    }
}
