package dev.focusduo;

import com.fasterxml.jackson.databind.*;
import dev.focusduo.rooms.Maintenance;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"focusduo.maintenance-initial-delay-ms=86400000", "focusduo.maintenance-delay-ms=86400000"})
class BackendIT {
    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> TestDatabase.URL);
        properties.add("spring.datasource.username", () -> TestDatabase.USER);
        properties.add("spring.datasource.password", () -> TestDatabase.PASSWORD);
    }
    private static final AtomicInteger day = new AtomicInteger();
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired Maintenance maintenance;
    final HttpClient client = HttpClient.newHttpClient();
    @BeforeEach void resetTime() { clock.set(Instant.parse("2030-01-01T00:00:00Z").plus(Duration.ofDays(day.incrementAndGet()))); }

    record Reply(int status, JsonNode body) {}
    record Account(String token, String id, String username) {}
    record Pair(Account owner, Account peer, JsonNode room) {}

    Reply request(String method, String path, String token, Object body, String key, Long revision) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
            .timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (revision != null) builder.header("X-Room-Revision", revision.toString());
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        return new Reply(response.statusCode(), response.body().isBlank() ? null : json.readTree(response.body()));
    }
    JsonNode ok(Reply reply, int status) {
        assertThat(reply.status()).withFailMessage("Expected %s, got %s: %s", status, reply.status(), reply.body()).isEqualTo(status);
        return reply.body();
    }
    void error(Reply reply, int status, String code) {
        JsonNode body = ok(reply, status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("requestId").asText()).isNotBlank();
        assertThat(body.has("fieldErrors")).isTrue();
        if (!code.equals("REVISION_CONFLICT")) assertThat(body.path("snapshot").isNull()).isTrue();
    }
    Account register() throws Exception {
        String username = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        JsonNode session = ok(request("POST", "/auth/register", null,
            Map.of("username", " " + username.toUpperCase(Locale.ROOT) + " ", "displayName", " Tester ", "password", "Example-password-123"), null, null), 201);
        assertThat(session.path("user").path("username").asText()).isEqualTo(username);
        assertThat(session.path("user").path("displayName").asText()).isEqualTo("Tester");
        assertThat(Base64.getUrlDecoder().decode(session.path("accessToken").asText())).hasSize(32);
        return new Account(session.path("accessToken").asText(), session.path("user").path("id").asText(), username);
    }
    JsonNode command(Account user, String method, String path, Object body, JsonNode room) throws Exception {
        return ok(request(method, path, user.token(), body, UUID.randomUUID().toString(), room == null ? null : room.path("revision").asLong()), 200);
    }
    Pair pair() throws Exception {
        Account owner = register(), peer = register();
        JsonNode room = command(owner, "POST", "/rooms", Map.of("focusDurationSeconds", 300, "breakDurationSeconds", 60), null);
        room = command(peer, "POST", "/rooms/join", Map.of("code", " " + room.path("code").asText().toLowerCase(Locale.ROOT) + " "), null);
        return new Pair(owner, peer, room);
    }
    String path(JsonNode room) { return "/rooms/" + room.path("roomId").asText(); }
    String roundPath(JsonNode room) { return path(room) + "/rounds/" + room.path("round").path("id").asText(); }
    JsonNode get(Account user, String path) throws Exception { return ok(request("GET", path, user.token(), null, null, null), 200); }

    @Test void fullScenarioPauseResumeHistoryBreakAndClose() throws Exception {
        Pair p = pair(); JsonNode room = p.room(); String base = path(room);
        room = command(p.peer(), "PATCH", base + "/me", Map.of("goal", "Ship Kotlin client"), room);
        room = command(p.peer(), "POST", base + "/tasks", Map.of("title", "  Build desktop  "), room);
        String task = room.path("participants").get(1).path("tasks").get(0).path("id").asText();
        room = command(p.owner(), "POST", base + "/rounds", Map.of("kind", "FOCUS"), room);
        Instant started = Instant.parse(room.path("round").path("startedAt").asText());
        String roundId = room.path("round").path("id").asText();
        clock.advance(Duration.ofSeconds(100));
        room = command(p.peer(), "PATCH", base + "/tasks/" + task, Map.of("completed", true), room);
        room = command(p.owner(), "POST", roundPath(room) + "/pause", null, room);
        assertThat(room.path("round").path("activeElapsedMs").asLong()).isEqualTo(100000);
        assertThat(room.path("round").path("remainingMs").asLong()).isEqualTo(200000);
        clock.advance(Duration.ofMinutes(4));
        JsonNode paused = get(p.peer(), base);
        assertThat(paused.path("round")).isEqualTo(room.path("round"));
        room = command(p.owner(), "POST", roundPath(room) + "/resume", null, room);
        assertThat(Instant.parse(room.path("round").path("startedAt").asText())).isEqualTo(started);
        clock.advance(Duration.ofSeconds(15));
        room = command(p.owner(), "POST", roundPath(room) + "/finish", null, room);
        assertThat(room.path("round").path("completionReason").asText()).isEqualTo("MANUAL");
        assertThat(room.path("round").path("activeElapsedMs").asLong()).isEqualTo(115000);
        assertThat(room.path("round").path("remainingMs").asLong()).isZero();
        JsonNode history = get(p.peer(), "/history/" + roundId);
        assertThat(history.path("participants").get(1).path("completedTaskCount").asInt()).isEqualTo(1);
        room = command(p.peer(), "PATCH", base + "/tasks/" + task, Map.of("title", "Changed afterwards", "completed", false), room);
        assertThat(get(p.peer(), "/history/" + roundId)).isEqualTo(history);
        room = command(p.owner(), "POST", base + "/rounds", Map.of("kind", "BREAK"), room);
        clock.advance(Duration.ofSeconds(7));
        room = command(p.peer(), "POST", base + "/close", null, room);
        assertThat(room.path("status").asText()).isEqualTo("CLOSED");
        assertThat(room.path("round").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(room.path("round").path("completionReason").asText()).isEqualTo("ROOM_CLOSED");
        assertThat(room.path("round").path("activeElapsedMs").asLong()).isEqualTo(7000);
        assertThat(get(p.owner(), "/rooms/current").path("room").isNull()).isTrue();
        assertThat(get(p.peer(), "/rooms/current").path("room").isNull()).isTrue();
        assertThat(get(p.owner(), base).path("participants")).hasSize(2);
        assertThat(get(p.peer(), "/history").path("items")).hasSize(2);
        error(request("PATCH", base + "/me", p.peer().token(), Map.of("goal", "closed"), UUID.randomUUID().toString(), room.path("revision").asLong()), 409, "ROOM_CLOSED");
        command(p.owner(), "POST", "/rooms", Map.of(), null);
        command(p.peer(), "POST", "/rooms", Map.of(), null);
    }

    @Test void expiredRoundCommitsEvenWhenCommandConflicts() throws Exception {
        Pair p = pair(); JsonNode room = command(p.owner(), "POST", path(p.room()) + "/rounds", Map.of("kind", "FOCUS"), p.room());
        String deadline = room.path("round").path("endsAt").asText();
        clock.advance(Duration.ofSeconds(310));
        Reply conflict = request("POST", roundPath(room) + "/pause", p.owner().token(), null, UUID.randomUUID().toString(), room.path("revision").asLong());
        error(conflict, 409, "REVISION_CONFLICT");
        JsonNode committed = get(p.owner(), path(room));
        assertThat(committed).isEqualTo(conflict.body().path("snapshot"));
        assertThat(committed.path("round").path("completionReason").asText()).isEqualTo("ELAPSED");
        assertThat(committed.path("round").path("endedAt").asText()).isEqualTo(deadline);
        assertThat(committed.path("round").path("activeElapsedMs").asLong()).isEqualTo(300000);
        maintenance.refresh(); maintenance.refresh();
        assertThat(get(p.peer(), "/history").path("items")).hasSize(1);
        error(request("POST", roundPath(committed) + "/finish", p.owner().token(), null, UUID.randomUUID().toString(), committed.path("revision").asLong()), 409, "INVALID_STATE");
        JsonNode closed = command(p.peer(), "POST", path(room) + "/close", null, committed);
        assertThat(closed.path("round")).isEqualTo(committed.path("round"));
    }

    @Test void idempotencyReplayIsAtomicConcurrentAndPrecedesRevision() throws Exception {
        Account owner = register(); String key = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(5)) {
            var start = new CountDownLatch(1);
            List<Future<Reply>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) futures.add(pool.submit(() -> { start.await(); return request("POST", "/rooms", owner.token(), Map.of(), key, null); }));
            start.countDown(); JsonNode first = ok(futures.getFirst().get(), 200);
            for (Future<Reply> result : futures) assertThat(ok(result.get(), 200)).isEqualTo(first);
            error(request("POST", "/rooms", owner.token(), Map.of("focusDurationSeconds", 600), key, null), 409, "IDEMPOTENCY_KEY_REUSED");
            String taskKey = UUID.randomUUID().toString(); long originalRevision = first.path("revision").asLong();
            JsonNode taskResult = ok(request("POST", path(first) + "/tasks", owner.token(), Map.of("title", "Saved once"), taskKey, originalRevision), 200);
            JsonNode latest = command(owner, "PATCH", path(first) + "/me", Map.of("goal", "Newer"), taskResult);
            assertThat(ok(request("POST", path(first) + "/tasks", owner.token(), Map.of("title", "Saved once"), taskKey, originalRevision), 200)).isEqualTo(taskResult);
            error(request("POST", path(first) + "/tasks", owner.token(), Map.of("title", "Saved once"), taskKey, latest.path("revision").asLong()), 409, "IDEMPOTENCY_KEY_REUSED");
            assertThat(get(owner, path(first)).path("participants").get(0).path("tasks")).hasSize(1);
        }
    }

    @Test void twoContendersCannotTakeTheSameSeatAndOneUserCannotJoinTwoRooms() throws Exception {
        Account owner = register(), a = register(), b = register();
        JsonNode room = command(owner, "POST", "/rooms", Map.of(), null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var fa = pool.submit(() -> { start.await(); return request("POST", "/rooms/join", a.token(), Map.of("code", room.path("code").asText()), UUID.randomUUID().toString(), null); });
            var fb = pool.submit(() -> { start.await(); return request("POST", "/rooms/join", b.token(), Map.of("code", room.path("code").asText()), UUID.randomUUID().toString(), null); });
            start.countDown(); Reply ra = fa.get(), rb = fb.get();
            assertThat(List.of(ra.status(), rb.status())).containsExactlyInAnyOrder(200, 409);
            error(ra.status() == 409 ? ra : rb, 409, "ROOM_FULL");
            Account winner = ra.status() == 200 ? a : b;
            error(request("POST", "/rooms", winner.token(), Map.of(), UUID.randomUUID().toString(), null), 409, "ALREADY_IN_ROOM");
            JsonNode again = command(winner, "POST", "/rooms/join", Map.of("code", room.path("code").asText()), null);
            assertThat(again.path("participants")).hasSize(2);
        }
        Account solo = register(), otherOwner = register();
        JsonNode other = command(otherOwner, "POST", "/rooms", Map.of(), null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var create = pool.submit(() -> { start.await(); return request("POST", "/rooms", solo.token(), Map.of(), UUID.randomUUID().toString(), null); });
            var join = pool.submit(() -> { start.await(); return request("POST", "/rooms/join", solo.token(), Map.of("code", other.path("code").asText()), UUID.randomUUID().toString(), null); });
            start.countDown(); Reply c = create.get(), j = join.get();
            assertThat(List.of(c.status(), j.status())).containsExactlyInAnyOrder(200, 409);
            error(c.status() == 409 ? c : j, 409, "ALREADY_IN_ROOM");
        }
    }

    @Test void authorizationValidationAndTokenLifecycle() throws Exception {
        Pair p = pair(); Account outsider = register(); JsonNode room = p.room(); String base = path(room);
        error(request("GET", "/me", null, null, null, null), 401, "UNAUTHORIZED");
        assertThat(get(p.owner(), "/me").path("id").asText()).isEqualTo(p.owner().id());
        error(request("POST", "/auth/register", null, Map.of("username", p.owner().username(), "displayName", "Test", "password", "Example-password-123"), null, null), 409, "USERNAME_TAKEN");
        error(request("POST", "/auth/login", null, Map.of("username", p.owner().username(), "password", "wrong-password"), null, null), 401, "UNAUTHORIZED");
        error(request("GET", base, outsider.token(), null, null, null), 403, "FORBIDDEN");
        error(request("PATCH", base + "/me", outsider.token(), Map.of("goal", "intruder"), UUID.randomUUID().toString(), 0L), 403, "FORBIDDEN");
        error(request("POST", base + "/tasks", p.owner().token(), Map.of("title", "task"), null, room.path("revision").asLong()), 400, "VALIDATION_ERROR");
        room = command(p.owner(), "POST", base + "/tasks", Map.of("title", "Owned"), room);
        String task = room.path("participants").get(0).path("tasks").get(0).path("id").asText();
        error(request("PATCH", base + "/tasks/" + task, p.peer().token(), Map.of("completed", true), UUID.randomUUID().toString(), room.path("revision").asLong()), 403, "FORBIDDEN");
        error(request("POST", base + "/rounds", p.peer().token(), Map.of("kind", "FOCUS"), UUID.randomUUID().toString(), room.path("revision").asLong()), 403, "FORBIDDEN");
        error(request("PATCH", base + "/settings", p.owner().token(), Map.of("focusDurationSeconds", 301, "breakDurationSeconds", 60), UUID.randomUUID().toString(), room.path("revision").asLong()), 400, "VALIDATION_ERROR");
        room = command(p.owner(), "POST", base + "/rounds", Map.of("kind", "FOCUS"), room);
        error(request("POST", base + "/rounds/" + UUID.randomUUID() + "/pause", p.owner().token(), null, UUID.randomUUID().toString(), room.path("revision").asLong()), 409, "ROUND_NOT_CURRENT");
        room = command(p.owner(), "POST", roundPath(room) + "/finish", null, room);
        error(request("GET", "/history/" + room.path("round").path("id").asText(), outsider.token(), null, null, null), 403, "FORBIDDEN");
        ok(request("POST", "/auth/logout", p.owner().token(), null, null, null), 204);
        error(request("GET", "/me", p.owner().token(), null, null, null), 401, "UNAUTHORIZED");
        JsonNode login = ok(request("POST", "/auth/login", null, Map.of("username", p.owner().username(), "password", "Example-password-123"), null, null), 200);
        assertThat(get(new Account(login.path("accessToken").asText(), p.owner().id(), p.owner().username()), "/rooms/current").path("room").path("roomId").asText()).isEqualTo(room.path("roomId").asText());
        clock.advance(Duration.ofHours(24));
        error(request("GET", "/me", login.path("accessToken").asText(), null, null, null), 401, "UNAUTHORIZED");
    }

    static final class Frames implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data); if (last) { messages.add(text.toString()); text.setLength(0); } ws.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { closed.complete(code); return null; }
        @Override public void onError(WebSocket ws, Throwable ex) { closed.completeExceptionally(ex); }
    }
    WebSocket socket(Account account, JsonNode room, Frames frames) throws Exception {
        return client.newWebSocketBuilder().header("Authorization", "Bearer " + account.token())
            .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/rooms/" + room.path("roomId").asText()), frames).get(10, TimeUnit.SECONDS);
    }
    JsonNode frame(Frames frames) throws Exception {
        String text = frames.messages.poll(10, TimeUnit.SECONDS);
        assertThat(text).as("WebSocket snapshot frame").isNotNull();
        JsonNode event = json.readTree(text); assertThat(event.path("type").asText()).isEqualTo("ROOM_SNAPSHOT"); return event.path("data");
    }
    @Test void websocketSnapshotsReconnectAuthorizationLogoutExpiryAndClose() throws Exception {
        Pair p = pair(); Account outsider = register(); JsonNode room = p.room();
        assertThatThrownBy(() -> socket(outsider, p.room(), new Frames())).hasCauseInstanceOf(WebSocketHandshakeException.class);
        assertThatThrownBy(() -> client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/rooms/" + p.room().path("roomId").asText()), new Frames()).get()).hasCauseInstanceOf(WebSocketHandshakeException.class);
        Frames first = new Frames(), second = new Frames();
        WebSocket ws1 = socket(p.owner(), room, first), ws2 = socket(p.owner(), room, second);
        assertThat(frame(first).path("revision")).isEqualTo(room.path("revision")); frame(second);
        room = command(p.peer(), "PATCH", path(room) + "/me", Map.of("goal", "via REST"), room);
        assertThat(frame(first)).isEqualTo(room); assertThat(frame(second)).isEqualTo(room);
        ws1.sendClose(1000, "reconnect").join(); first.closed.get(10, TimeUnit.SECONDS);
        Frames reconnected = new Frames(); WebSocket ws3 = socket(p.owner(), room, reconnected);
        assertThat(frame(reconnected)).isEqualTo(room);
        ok(request("POST", "/auth/logout", p.owner().token(), null, null, null), 204);
        assertThat(second.closed.get(10, TimeUnit.SECONDS)).isEqualTo(4401);
        assertThat(reconnected.closed.get(10, TimeUnit.SECONDS)).isEqualTo(4401);
        Frames peer = new Frames(); WebSocket ws4 = socket(p.peer(), room, peer); frame(peer);
        clock.advance(Duration.ofHours(24)); maintenance.refresh();
        assertThat(peer.closed.get(10, TimeUnit.SECONDS)).isEqualTo(4401);
        Pair closing = pair(); Frames closed = new Frames(); WebSocket ws5 = socket(closing.owner(), closing.room(), closed); frame(closed);
        JsonNode result = command(closing.peer(), "POST", path(closing.room()) + "/close", null, closing.room());
        assertThat(frame(closed)).isEqualTo(result); assertThat(closed.closed.get(10, TimeUnit.SECONDS)).isEqualTo(1000);
    }
}
