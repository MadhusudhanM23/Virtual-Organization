package io.vorg.api.platform.live;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers {@code /ws/live}. The HTTP upgrade is unauthenticated (the security chain permits the path) because
 * browsers cannot set an Authorization header on a WebSocket; the first message must carry a valid access token or the
 * socket is closed after {@code vo.live.ws.auth-timeout}. Origins are restricted to {@code vo.live.ws.allowed-origins}.
 */
@Configuration
@EnableWebSocket
public class LiveWebSocketConfig implements WebSocketConfigurer {

    public static final String PATH = "/ws/live";

    private final LiveSocketHandler handler;
    private final LiveProperties props;

    public LiveWebSocketConfig(LiveSocketHandler handler, LiveProperties props) {
        this.handler = handler;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, PATH).setAllowedOriginPatterns(props.ws().allowedOrigins().split(","));
    }
}
