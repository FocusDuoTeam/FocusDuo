package dev.focusduo.ws;

import dev.focusduo.api.ApiErrors;
import dev.focusduo.api.ApiException;
import dev.focusduo.auth.AuthPrincipal;
import dev.focusduo.rooms.RoomService;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RoomSockets sockets;
    private final RoomService rooms;
    private final ApiErrors errors;
    public WebSocketConfig(RoomSockets sockets, RoomService rooms, ApiErrors errors) {
        this.sockets = sockets; this.rooms = rooms; this.errors = errors;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sockets, "/ws/rooms/{roomId}").addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler handler, Map<String, Object> attributes) throws Exception {
                try {
                    if (!(request.getPrincipal() instanceof Authentication authentication)
                        || !(authentication.getPrincipal() instanceof AuthPrincipal user)) throw ApiException.unauthorized();
                    String path = request.getURI().getPath();
                    UUID roomId;
                    try {
                        String value = path.substring(path.lastIndexOf('/') + 1);
                        roomId = UUID.fromString(value);
                        if (!roomId.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
                    }
                    catch (IllegalArgumentException ex) { throw ApiException.validation("roomId", "Expected UUID"); }
                    rooms.get(user.userId(), roomId);
                    attributes.put("roomId", roomId); attributes.put("user", user);
                    return true;
                } catch (ApiException ex) {
                    if (request instanceof ServletServerHttpRequest servletRequest && response instanceof ServletServerHttpResponse servletResponse)
                        errors.write(servletRequest.getServletRequest(), servletResponse.getServletResponse(), ex);
                    return false;
                }
            }
            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler handler, Exception exception) {}
        });
    }
}
