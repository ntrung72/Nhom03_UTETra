package vn.iotstar.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.ChannelRegistration;
import lombok.RequiredArgsConstructor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** STOMP server push only. Browser users subscribe to their own /user queues. */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final WebSocketSubscriptionInterceptor subscriptionInterceptor;

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(subscriptionInterceptor);
    }
    @Value("${app.websocket.allowed-origins:http://localhost:8090,http://127.0.0.1:8090}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setUserDestinationPrefix("/user");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] patterns = java.util.Arrays.stream(allowedOrigins.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        if (patterns.length == 0 || java.util.Arrays.stream(patterns).anyMatch("*"::equals)) {
            throw new IllegalStateException("Configure trusted app.websocket.allowed-origins (no wildcard).");
        }
        registry.addEndpoint("/ws").setAllowedOriginPatterns(patterns).withSockJS();
    }
}
