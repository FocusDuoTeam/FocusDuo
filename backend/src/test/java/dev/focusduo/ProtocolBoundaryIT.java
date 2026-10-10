package dev.focusduo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.focusduo.rooms.Maintenance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Protocol boundaries exercised over actual HTTP and WebSocket connections to PostgreSQL-backed code. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"focusduo.maintenance-initial-delay-ms=86400000", "focusduo.maintenance-delay-ms=86400000"})
class ProtocolBoundaryIT {
    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary MutableClock protocolClock() { return new MutableClock(); }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> TestDatabase.URL);
        properties.add("spring.datasource.username", () -> TestDatabase.USER);
        properties.add("spring.datasource.password", () -> TestDatabase.PASSWORD);
    }

    private static final AtomicInteger DAY = new AtomicInteger();
    private static final String PASSWORD = "Protocol-example-password";
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired Maintenance maintenance;
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<WebSocket> sockets = new ArrayList<>();

    record Reply(int status, JsonNode body, HttpHeaders headers) {}
    record Account(String username, String token, Instant expiresAt) {}
    record Pair(Account owner, Account peer, JsonNode room) {}

    @BeforeEach
    void resetTime() {
        clock.set(Instant.parse("2035-01-01T00:00:00Z").plus(Duration.ofDays(DAY.incrementAndGet())));
    }

    @AfterEach
    void closeTransport() throws InterruptedException {
        // Also clean up when an assertion fails before a test reaches its own close handshake.
        sockets.forEach(WebSocket::abort);
        http.shutdownNow();
        assertThat(http.awaitTermination(Duration.ofSeconds(10))).as("HTTP client terminated").isTrue();
    }

    @Test
    void cookieAndQueryCredentialsCannotReplaceBearerHeaderForHttpOrWebSocket() throws Exception {
        Account owner = register();
        JsonNode room = command(owner, "POST", "/rooms", "{}", null);
        Map<String, String> cookie = Map.of("Cookie", "JSESSIONID=" + owner.token() + "; accessToken=" + owner.token());
        assertError(request("GET", "/api/v1/me?accessToken=" + owner.token(), null, null, Map.of()), 401, "UNAUTHORIZED");
        assertError(request("GET", "/api/v1/me", null, null, cookie), 401, "UNAUTHORIZED");
        assertError(request("GET", "/api/v1/me?access_token=" + owner.token(), "invalid-token", null, cookie), 401, "UNAUTHORIZED");
        assertHandshakeFailure(wsPath(room) + "?accessToken=" + owner.token(), null, Map.of(), 401);
        assertHandshakeFailure(wsPath(room), null, cookie, 401);
        JsonNode me = ok(request("GET", "/api/v1/me", owner.token(), null, Map.of()), 200);
        assertThat(me.path("username").asText()).isEqualTo(owner.username());
    }

    @Test
    void uuidAndRevisionHeadersAreStrictAndRejectedRequestsDoNotChangeTheRoom() throws Exception {
        Account owner = register();
        assertFieldError(request("POST", "/api/v1/rooms", owner.token(), "{}", Map.of()), "Idempotency-Key");
        for (String key : List.of("1-1-1-1-1", "not-a-uuid", UUID.randomUUID() + "x")) {
            assertFieldError(request("POST", "/api/v1/rooms", owner.token(), "{}", Map.of("Idempotency-Key", key)), "Idempotency-Key");
        }
        assertThat(get(owner, "/rooms/current").path("room").isNull()).isTrue();
        JsonNode room = ok(request("POST", "/api/v1/rooms", owner.token(), "{}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT))), 200);
        assertFieldError(request("GET", "/api/v1/rooms/1-1-1-1-1", owner.token(), null, Map.of()), "roomId");
        assertFieldError(request("GET", "/api/v1/history/1-1-1-1-1", owner.token(), null, Map.of()), "roundId");
        assertHandshakeFailure("/ws/rooms/1-1-1-1-1", owner.token(), Map.of(), 400);
        String base = "/api/v1" + roomPath(room);
        assertFieldError(request("PATCH", base + "/me", owner.token(), "{\"goal\":\"unchanged\"}",
                Map.of("Idempotency-Key", UUID.randomUUID().toString())), "X-Room-Revision");
        for (String revision : List.of("0", "-1", "1.5", "one", "9223372036854775808")) {
            assertFieldError(request("PATCH", base + "/me", owner.token(), "{\"goal\":\"unchanged\"}",
                    Map.of("Idempotency-Key", UUID.randomUUID().toString(), "X-Room-Revision", revision)), "X-Room-Revision");
        }
        assertFieldError(request("PATCH", base + "/tasks/1-1-1-1-1", owner.token(), "{\"completed\":true}",
                commandHeaders(room)), "taskId");
        assertThat(get(owner, roomPath(room))).isEqualTo(room);
    }

    @Test
    void jsonCoercionDuplicateKeysAndUnexpectedFieldsCannotBecomeSuccessfulCommands() throws Exception {
        for (String body : List.of(
                "{\"username\":12345,\"displayName\":\"Tester\",\"password\":\"Protocol-example-password\"}",
                "{\"username\":\"test\",\"displayName\":true,\"password\":\"Protocol-example-password\"}",
                "{\"username\":\"test\",\"displayName\":\"Tester\",\"password\":1234567890123}",
                "{\"username\":\"first\",\"username\":\"second\",\"displayName\":\"Tester\",\"password\":\"Protocol-example-password\"}",
                "{\"username\":\"test\",\"displayName\":\"Tester\",\"password\":\"Protocol-example-password\",\"role\":\"admin\"}")) {
            assertError(request("POST", "/api/v1/auth/register", null, body, Map.of()), 400, "VALIDATION_ERROR");
        }
        Account owner = register();
        JsonNode room = command(owner, "POST", "/rooms", "{}", null);
        String base = "/api/v1" + roomPath(room);
        for (String body : List.of("{\"title\":123}", "{\"title\":\"first\",\"title\":\"second\"}", "{\"title\":\"task\",\"completed\":true}")) {
            assertError(request("POST", base + "/tasks", owner.token(), body, commandHeaders(room)), 400, "VALIDATION_ERROR");
        }
        assertFieldError(request("PATCH", base + "/me", owner.token(), "{\"goal\":false}", commandHeaders(room)), "goal");
        assertFieldError(request("PATCH", base + "/settings", owner.token(),
                "{\"focusDurationSeconds\":300.0,\"breakDurationSeconds\":60}", commandHeaders(room)), "focusDurationSeconds");
        assertError(request("POST", base + "/close", owner.token(), "{}", commandHeaders(room)), 400, "VALIDATION_ERROR");
        assertThat(get(owner, roomPath(room))).isEqualTo(room);
    }

    @Test
    void outsiderWithStaleRevisionAndAnotherUsersKeyCannotReadSnapshotsOrHistory() throws Exception {
        Pair pair = pair();
        Account outsider = register();
        String privateTitle = "Private task " + UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        JsonNode room = ok(request("POST", "/api/v1" + roomPath(pair.room()) + "/tasks", pair.owner().token(),
                json.writeValueAsString(Map.of("title", privateTitle)),
                Map.of("Idempotency-Key", key, "X-Room-Revision", pair.room().path("revision").asText())), 200);
        Reply denied = request("POST", "/api/v1" + roomPath(room) + "/tasks", outsider.token(),
                json.writeValueAsString(Map.of("title", privateTitle)),
                Map.of("Idempotency-Key", key, "X-Room-Revision", "1"));
        assertError(denied, 403, "FORBIDDEN");
        assertThat(denied.body().toString()).doesNotContain(privateTitle, room.path("code").asText());
        assertError(request("GET", "/api/v1" + roomPath(room), outsider.token(), null, Map.of()), 403, "FORBIDDEN");
        assertHandshakeFailure(wsPath(room), outsider.token(), Map.of(), 403);
        assertThat(get(pair.owner(), roomPath(room))).isEqualTo(room);
        room = command(pair.owner(), "POST", roomPath(room) + "/rounds", "{\"kind\":\"FOCUS\"}", room);
        room = command(pair.owner(), "POST", roomPath(room) + "/rounds/" + room.path("round").path("id").asText() + "/finish", null, room);
        assertError(request("GET", "/api/v1/history/" + room.path("round").path("id").asText(), outsider.token(), null, Map.of()), 403, "FORBIDDEN");
        assertThat(get(outsider, "/history").path("items")).isEmpty();
    }

    @Test
    void websocketPingPongUseControlFramesAndDoNotInterruptSnapshotDelivery() throws Exception {
        Account owner = register();
        JsonNode room = command(owner, "POST", "/rooms", "{}", null);
        Frames frames = new Frames();
        WebSocket socket = socket(wsPath(room), owner.token(), Map.of(), frames);
        try {
            assertThat(snapshot(frames)).isEqualTo(room);
            maintenance.ping();
            assertThat(frames.pings.poll(10, TimeUnit.SECONDS)).as("server ping control frame").isNotNull();
            // Java's WebSocket client permits only one pending ping/pong send at a time.
            frames.pongSent.get(10, TimeUnit.SECONDS);
            byte[] payload = "client-control-probe".getBytes(StandardCharsets.UTF_8);
            socket.sendPing(ByteBuffer.wrap(payload)).get(10, TimeUnit.SECONDS);
            assertThat(frames.pongs.poll(10, TimeUnit.SECONDS)).as("server pong echoes client ping").isEqualTo(payload);
            JsonNode updated = command(owner, "PATCH", roomPath(room) + "/me", "{\"goal\":\"after ping/pong\"}", room);
            assertThat(snapshot(frames)).isEqualTo(updated);
            assertThat(frames.messages).isEmpty();
            assertThat(frames.closed.isDone()).isFalse();
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(10, TimeUnit.SECONDS);
            frames.closed.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void logoutOnlyRevokesCurrentTokenAndIndependentSessionExpiresAtItsOwnDeadline() throws Exception {
        Account owner = register();
        JsonNode room = command(owner, "POST", "/rooms", "{}", null);
        clock.advance(Duration.ofSeconds(10));
        Account secondLogin = login(owner.username());
        Frames first = new Frames(), second = new Frames();
        WebSocket firstSocket = socket(wsPath(room), owner.token(), Map.of(), first);
        WebSocket secondSocket = socket(wsPath(room), secondLogin.token(), Map.of(), second);
        try {
            snapshot(first); snapshot(second);
            ok(request("POST", "/api/v1/auth/logout", owner.token(), null, Map.of()), 204);
            assertThat(first.closed.get(10, TimeUnit.SECONDS)).isEqualTo(4401);
            assertError(request("GET", "/api/v1/me", owner.token(), null, Map.of()), 401, "UNAUTHORIZED");
            assertThat(get(secondLogin, "/me").path("username").asText()).isEqualTo(owner.username());
            room = command(secondLogin, "PATCH", roomPath(room) + "/me", "{\"goal\":\"independent login\"}", room);
            assertThat(snapshot(second)).isEqualTo(room);
            clock.set(secondLogin.expiresAt().minusMillis(1));
            maintenance.refresh();
            get(secondLogin, "/me");
            assertThat(second.closed.isDone()).isFalse();
            clock.advance(Duration.ofMillis(1));
            maintenance.refresh();
            assertThat(second.closed.get(10, TimeUnit.SECONDS)).isEqualTo(4401);
            assertError(request("GET", "/api/v1/me", secondLogin.token(), null, Map.of()), 401, "UNAUTHORIZED");
        } finally {
            firstSocket.abort();
            secondSocket.abort();
        }
    }

    private Reply request(String method, String path, String token, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        headers.forEach(builder::header);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> reply = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(reply.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(reply.headers().allValues("Set-Cookie")).isEmpty();
        return new Reply(reply.statusCode(), reply.body().isBlank() ? null : json.readTree(reply.body()), reply.headers());
    }

    private JsonNode ok(Reply reply, int status) {
        assertThat(reply.status()).withFailMessage("Expected HTTP %s, got %s: %s", status, reply.status(), reply.body()).isEqualTo(status);
        return reply.body();
    }

    private void assertError(Reply reply, int status, String code) {
        JsonNode body = ok(reply, status);
        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("code", "message", "requestId", "fieldErrors", "snapshot");
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("requestId").asText()).isEqualTo(reply.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(body.path("fieldErrors").isObject()).isTrue();
        assertThat(body.path("snapshot").isNull()).isTrue();
        assertThat(reply.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
    }

    private void assertFieldError(Reply reply, String field) {
        assertError(reply, 400, "VALIDATION_ERROR");
        assertThat(reply.body().path("fieldErrors").path(field).asText()).isNotBlank();
    }

    private Account register() throws Exception {
        String username = "protocol_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        JsonNode session = ok(request("POST", "/api/v1/auth/register", null,
                json.writeValueAsString(Map.of("username", username, "displayName", "Protocol tester", "password", PASSWORD)), Map.of()), 201);
        return new Account(username, session.path("accessToken").asText(), Instant.parse(session.path("expiresAt").asText()));
    }

    private Account login(String username) throws Exception {
        JsonNode session = ok(request("POST", "/api/v1/auth/login", null,
                json.writeValueAsString(Map.of("username", username, "password", PASSWORD)), Map.of()), 200);
        return new Account(username, session.path("accessToken").asText(), Instant.parse(session.path("expiresAt").asText()));
    }

    private Pair pair() throws Exception {
        Account owner = register(), peer = register();
        JsonNode room = command(owner, "POST", "/rooms", "{\"focusDurationSeconds\":300,\"breakDurationSeconds\":60}", null);
        room = command(peer, "POST", "/rooms/join", json.writeValueAsString(Map.of("code", room.path("code").asText())), null);
        return new Pair(owner, peer, room);
    }

    private JsonNode command(Account user, String method, String path, String body, JsonNode room) throws Exception {
        return ok(request(method, "/api/v1" + path, user.token(), body, commandHeaders(room)), 200);
    }

    private Map<String, String> commandHeaders(JsonNode room) {
        String key = UUID.randomUUID().toString();
        return room == null ? Map.of("Idempotency-Key", key)
                : Map.of("Idempotency-Key", key, "X-Room-Revision", room.path("revision").asText());
    }

    private JsonNode get(Account user, String path) throws Exception { return ok(request("GET", "/api/v1" + path, user.token(), null, Map.of()), 200); }
    private String roomPath(JsonNode room) { return "/rooms/" + room.path("roomId").asText(); }
    private String wsPath(JsonNode room) { return "/ws" + roomPath(room); }

    private WebSocket socket(String path, String token, Map<String, String> headers, Frames frames) throws Exception {
        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        headers.forEach(builder::header);
        WebSocket socket = builder.buildAsync(URI.create("ws://127.0.0.1:" + port + path), frames).get(10, TimeUnit.SECONDS);
        sockets.add(socket);
        return socket;
    }

    private void assertHandshakeFailure(String path, String token, Map<String, String> headers, int status) {
        Frames frames = new Frames();
        WebSocket unexpectedlyAccepted = null;
        Exception failure = null;
        try {
            unexpectedlyAccepted = socket(path, token, headers, frames);
        } catch (Exception exception) {
            failure = exception;
        } finally {
            if (unexpectedlyAccepted != null) unexpectedlyAccepted.abort();
        }
        assertThat(failure).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(WebSocketHandshakeException.class);
        WebSocketHandshakeException rejected = (WebSocketHandshakeException) failure.getCause();
        assertThat(rejected.getResponse().statusCode()).isEqualTo(status);
        assertThat(frames.messages).isEmpty();
    }

    private JsonNode snapshot(Frames frames) throws Exception {
        String message = frames.messages.poll(10, TimeUnit.SECONDS);
        assertThat(message).as("room snapshot frame").isNotNull();
        JsonNode event = json.readTree(message);
        assertThat(event.path("type").asText()).isEqualTo("ROOM_SNAPSHOT");
        return event.path("data");
    }

    static final class Frames implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> pings = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> pongs = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> pongSent = new CompletableFuture<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder message = new StringBuilder();

        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            message.append(data);
            if (last) { messages.add(message.toString()); message.setLength(0); }
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onPing(WebSocket socket, ByteBuffer data) {
            byte[] payload = copy(data);
            pings.add(payload);
            socket.request(1);
            CompletableFuture<WebSocket> sent = socket.sendPong(ByteBuffer.wrap(payload));
            sent.whenComplete((ignored, failure) -> {
                if (failure == null) pongSent.complete(null);
                else pongSent.completeExceptionally(failure);
            });
            return sent;
        }
        @Override public CompletionStage<?> onPong(WebSocket socket, ByteBuffer data) {
            pongs.add(copy(data));
            socket.request(1);
            return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.complete(code); return null; }
        @Override public void onError(WebSocket socket, Throwable exception) { closed.completeExceptionally(exception); }
        private static byte[] copy(ByteBuffer data) { byte[] bytes = new byte[data.remaining()]; data.get(bytes); return bytes; }
    }
}
