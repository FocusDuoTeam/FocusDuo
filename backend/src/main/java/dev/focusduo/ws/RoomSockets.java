package dev.focusduo.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.focusduo.api.Api;
import dev.focusduo.api.ApiException;
import dev.focusduo.auth.AuthPrincipal;
import dev.focusduo.auth.AuthService;
import dev.focusduo.auth.TokenRevoked;
import dev.focusduo.rooms.RoomChanged;
import dev.focusduo.rooms.RoomService;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Only subscriptions are in memory. Every initial/reconnected snapshot comes from PostgreSQL. */
@Component
public class RoomSockets extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RoomSockets.class);
    private static final CloseStatus UNAUTHORIZED = new CloseStatus(4401, "Session expired or revoked");
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final RoomService rooms;
    private final AuthService auth;
    private final ObjectMapper json;

    public RoomSockets(RoomService rooms, AuthService auth, ObjectMapper json) {
        this.rooms = rooms; this.auth = auth; this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        var user = (AuthPrincipal) session.getAttributes().get("user");
        var roomId = (UUID) session.getAttributes().get("roomId");
        var connection = new Connection(session, roomId, user);
        // Subscribe before reading: a commit between handshake and this read cannot be lost.
        connections.put(session.getId(), connection);
        try {
            if (!auth.isValid(user.tokenHash())) { connection.close(UNAUTHORIZED); return; }
            connection.send(rooms.get(user.userId(), roomId));
        } catch (ApiException ex) {
            connection.close(ex.status() == 401 ? UNAUTHORIZED : CloseStatus.POLICY_VIOLATION);
        } catch (RuntimeException ex) {
            connection.close(CloseStatus.SERVER_ERROR);
            throw ex;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void changed(RoomChanged event) {
        for (Connection connection : connections.values()) {
            if (connection.roomId.equals(event.snapshot().roomId())) {
                try { connection.send(event.snapshot()); }
                catch (Exception ex) { connection.close(CloseStatus.SERVER_ERROR); }
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void revoked(TokenRevoked event) {
        connections.values().stream().filter(c -> c.user.tokenHash().equals(event.tokenHash()))
            .forEach(c -> c.close(UNAUTHORIZED));
    }

    public void checkSessions() {
        for (Connection connection : connections.values()) {
            if (!auth.isValid(connection.user.tokenHash())) connection.close(UNAUTHORIZED);
        }
    }

    public void ping() {
        for (Connection connection : connections.values()) {
            try { connection.ping(); }
            catch (Exception ex) { connection.close(CloseStatus.SERVER_ERROR); }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        var connection = connections.remove(session.getId());
        if (connection != null) connection.close(CloseStatus.SERVER_ERROR);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // V1 is server-to-client snapshots; mutations go through authenticated REST.
        var connection = connections.get(session.getId());
        if (connection != null) connection.close(CloseStatus.POLICY_VIOLATION);
    }

    private final class Connection {
        private final WebSocketSession session;
        private final UUID roomId;
        private final AuthPrincipal user;
        private long revision = -1;
        private Instant serverNow = Instant.MIN;

        Connection(WebSocketSession session, UUID roomId, AuthPrincipal user) {
            this.session = new ConcurrentWebSocketSessionDecorator(session, 5000, 256 * 1024);
            this.roomId = roomId; this.user = user;
        }

        synchronized void send(Api.RoomSnapshot snapshot) throws IOException {
            if (!session.isOpen()) return;
            if (!auth.isValid(user.tokenHash())) { close(UNAUTHORIZED); return; }
            if (snapshot.revision() < revision ||
                (snapshot.revision() == revision && snapshot.serverNow().isBefore(serverNow))) return;
            session.sendMessage(new TextMessage(json.writeValueAsString(new Api.RoomEvent(snapshot))));
            revision = snapshot.revision(); serverNow = snapshot.serverNow();
            if (snapshot.status().equals("CLOSED")) close(CloseStatus.NORMAL);
        }

        synchronized void close(CloseStatus status) {
            connections.remove(session.getId());
            try { if (session.isOpen()) session.close(status); }
            catch (IOException ex) { log.debug("WebSocket close failed for room {}", roomId); }
        }

        synchronized void ping() throws IOException {
            if (!auth.isValid(user.tokenHash())) { close(UNAUTHORIZED); return; }
            if (session.isOpen()) session.sendMessage(new PingMessage());
        }
    }
}
