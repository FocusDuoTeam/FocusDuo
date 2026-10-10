import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * External raw WebSocket acceptance check for an already running packaged server.
 * Java 21 source-file launcher + the backend's existing Jackson dependencies; no Spring context.
 */
public class WebSocketSmoke {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration IO_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(3);
    private final URI origin;
    private final HttpClient http;
    private final List<Account> activeSessions = new ArrayList<>();
    private final List<SocketProbe> sockets = new ArrayList<>();

    private record Account(String username, String password, String userId, String token) {}
    private record Options(URI origin, Duration waitTime, boolean help) {}
    private record Event(JsonNode snapshot, Integer closeCode, String failure) {}

    /** All messages are authored diagnostics; never wrap HTTP bodies or raw exception messages. */
    private static final class SmokeFailure extends RuntimeException {
        SmokeFailure(String message) { super(message); }
    }

    private WebSocketSmoke(URI origin) {
        this.origin = origin;
        this.http = HttpClient.newBuilder().connectTimeout(IO_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public static void main(String[] args) {
        Options options;
        try { options = options(args); }
        catch (IllegalArgumentException ex) {
            System.err.println("FAIL arguments: use --base-url HTTP(S)-origin and --wait-seconds 0..3600; see --help");
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.println("Java 21 + existing Jackson classpath: WebSocketSmoke.java [--base-url http://127.0.0.1:8090] [--wait-seconds 60]");
            System.out.println("Creates two test accounts and an own room in the selected server; credentials stay in memory.");
            return;
        }
        int exit = 0;
        WebSocketSmoke smoke = null;
        try {
            smoke = new WebSocketSmoke(options.origin());
            smoke.waitUntilReady(options.waitTime());
            smoke.run();
        } catch (SmokeFailure failure) {
            System.err.println("FAIL " + failure.getMessage());
            exit = 1;
        } catch (InterruptedException ex) {
            // Let the bounded cleanup attempt run before leaving the process.
            Thread.interrupted();
            System.err.println("FAIL interrupted");
            exit = 130;
        } catch (Exception ex) {
            System.err.println("FAIL unexpected " + ex.getClass().getSimpleName());
            exit = 1;
        } finally {
            if (smoke != null) smoke.cleanup();
        }
        if (exit == 0) System.out.println("PASS FocusDuo packaged-server WebSocket smoke");
        System.exit(exit);
    }

    private void run() throws Exception {
        Account owner = register("a"), peer = register("b");
        JsonNode room = command(owner, "POST", "/rooms", null,
                JSON.createObjectNode().put("focusDurationSeconds", 300).put("breakDurationSeconds", 60));
        String roomId = uuid(room, "roomId");
        String base = "/rooms/" + roomId;
        check(uuid(room, "ownerId").equals(owner.userId()), "Created room has a different owner");
        check(revision(room) == 1 && room.path("round").isNull(), "Unexpected initial room state");
        room = command(peer, "POST", "/rooms/join", null,
                JSON.createObjectNode().put("code", text(room, "code")));
        check(room.path("participants").isArray() && room.path("participants").size() == 2,
                "Join did not produce exactly two participants");
        participant(room, owner.userId());
        participant(room, peer.userId());
        System.out.println("PASS room: two registered users create and join their own room");

        SocketProbe ownerSocket = connect(owner, roomId);
        SocketProbe peerSocket = connect(peer, roomId);
        ownerSocket.awaitSnapshot(room);
        peerSocket.awaitSnapshot(room);
        System.out.println("PASS WebSocket: two bearer handshakes receive full ROOM_SNAPSHOT frames");

        long staleRevision = revision(room);
        room = command(peer, "POST", base + "/tasks", room,
                JSON.createObjectNode().put("title", "  WebSocket smoke task  "));
        check(revision(room) == staleRevision + 1, "Task creation must advance room revision once");
        ownerSocket.awaitSnapshot(room);
        peerSocket.awaitSnapshot(room);
        JsonNode tasks = participant(room, peer.userId()).path("tasks");
        check(tasks.isArray() && tasks.size() == 1, "Peer must have exactly one task");
        JsonNode task = tasks.get(0);
        String taskPath = base + "/tasks/" + uuid(task, "id");
        check(text(task, "title").equals("WebSocket smoke task") && task.path("completed").isBoolean()
                && !task.path("completed").booleanValue(), "Task snapshot fields are incorrect");
        JsonNode conflict = request("PATCH", base + "/me", owner.token(),
                JSON.createObjectNode().put("goal", "Must not be saved"), UUID.randomUUID().toString(), staleRevision, 409, IO_TIMEOUT);
        check(text(conflict, "code").equals("REVISION_CONFLICT"), "Stale revision must return REVISION_CONFLICT");
        sameRoom(conflict.path("snapshot"), room);
        sameRoom(request("GET", base, owner.token(), null, null, null, 200, IO_TIMEOUT), room);
        System.out.println("PASS revision: task change reaches both sockets and stale command returns 409 without mutation");

        ownerSocket.abort();
        room = command(peer, "PATCH", taskPath, room,
                JSON.createObjectNode().put("title", "Edited while partner disconnected").put("completed", true));
        peerSocket.awaitSnapshot(room);
        JsonNode current = request("GET", "/rooms/current", owner.token(), null, null, null, 200, IO_TIMEOUT).path("room");
        sameRoom(current, room);
        ownerSocket = connect(owner, roomId);
        ownerSocket.awaitSnapshot(room);
        check(participant(room, peer.userId()).path("tasks").get(0).path("completed").asBoolean(),
                "Reconnect scenario did not preserve the offline edit");
        System.out.println("PASS reconnect: disconnected participant recovers the edit from a new full snapshot");

        logout(owner);
        ownerSocket.awaitClose(4401);
        JsonNode unauthorized = request("GET", "/me", owner.token(), null, null, null, 401, IO_TIMEOUT);
        check(text(unauthorized, "code").equals("UNAUTHORIZED"), "Logged-out token remained authorized");
        room = command(peer, "PATCH", base + "/me", room,
                JSON.createObjectNode().put("goal", "Peer session survives partner logout"));
        peerSocket.awaitSnapshot(room);
        check(!peerSocket.socket.isInputClosed(), "Partner logout closed the unrelated peer socket");
        owner = login(owner);
        sameRoom(request("GET", "/rooms/current", owner.token(), null, null, null, 200, IO_TIMEOUT).path("room"), room);
        ownerSocket = connect(owner, roomId);
        ownerSocket.awaitSnapshot(room);
        System.out.println("PASS logout: revoked socket receives 4401, peer remains live, new login restores the room");

        room = command(peer, "POST", base + "/close", room, null);
        check(text(room, "status").equals("CLOSED"), "Close did not close the room");
        ownerSocket.awaitSnapshot(room);
        peerSocket.awaitSnapshot(room);
        ownerSocket.awaitClose(1000);
        peerSocket.awaitClose(1000);
        for (Account account : List.of(owner, peer)) {
            check(request("GET", "/rooms/current", account.token(), null, null, null, 200, IO_TIMEOUT).path("room").isNull(),
                    "Closed room did not release active membership");
            sameRoom(request("GET", base, account.token(), null, null, null, 200, IO_TIMEOUT), room);
            logout(account);
        }
        System.out.println("PASS close: both sockets receive CLOSED before code 1000; memberships released and sessions revoked");
    }

    private void waitUntilReady(Duration waitTime) throws InterruptedException {
        long deadline = System.nanoTime() + waitTime.toNanos();
        while (true) {
            try {
                JsonNode reply = request("GET", "/me", null, null, null, null, 401, Duration.ofSeconds(2));
                check(text(reply, "code").equals("UNAUTHORIZED"), "Unexpected readiness response");
                System.out.println("PASS readiness: unauthenticated /api/v1/me returns 401");
                return;
            } catch (SmokeFailure ex) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new SmokeFailure("Server did not become ready within the requested wait");
                Thread.sleep(Math.min(250, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))));
            }
        }
    }

    private Account register(String label) throws InterruptedException {
        String username = "ws_smoke_" + label + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        byte[] entropy = new byte[24];
        new SecureRandom().nextBytes(entropy);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        JsonNode reply = request("POST", "/auth/register", null,
                JSON.createObjectNode().put("username", username).put("displayName", "WS smoke " + label).put("password", password),
                null, null, 201, IO_TIMEOUT);
        String token = text(reply, "accessToken");
        // Track issued credentials before validating the rest of a potentially malformed response.
        Account account = new Account(username, password, reply.path("user").path("id").asText(), token);
        activeSessions.add(account);
        uuid(reply.path("user"), "id");
        check(text(request("GET", "/me", token, null, null, null, 200, IO_TIMEOUT), "id").equals(account.userId()),
                "GET /me returned a different user");
        return account;
    }

    private Account login(Account previous) throws InterruptedException {
        JsonNode reply = request("POST", "/auth/login", null,
                JSON.createObjectNode().put("username", previous.username()).put("password", previous.password()),
                null, null, 200, IO_TIMEOUT);
        Account account = new Account(previous.username(), previous.password(), reply.path("user").path("id").asText(), text(reply, "accessToken"));
        activeSessions.add(account);
        uuid(reply.path("user"), "id");
        check(account.userId().equals(previous.userId()), "Login returned a different user");
        return account;
    }

    private void logout(Account account) throws InterruptedException {
        request("POST", "/auth/logout", account.token(), null, null, null, 204, IO_TIMEOUT);
        activeSessions.remove(account);
    }

    private JsonNode command(Account account, String method, String path, JsonNode room, JsonNode body) throws InterruptedException {
        return request(method, path, account.token(), body, UUID.randomUUID().toString(),
                room == null ? null : revision(room), 200, IO_TIMEOUT);
    }

    private JsonNode request(String method, String path, String token, JsonNode body, String key,
                             Long revision, int expectedStatus, Duration timeout) throws InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve("/api/v1" + path)).timeout(timeout)
                .header("Accept", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (revision != null) request.header("X-Room-Revision", revision.toString());
        if (body != null) request.header("Content-Type", "application/json; charset=utf-8");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
        HttpResponse<byte[]> response;
        try { response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray()); }
        catch (java.io.IOException | IllegalArgumentException ex) {
            throw new SmokeFailure("HTTP transport failed during " + method + " " + path);
        }
        check(response.statusCode() == expectedStatus, method + " " + path + ": expected HTTP " + expectedStatus + ", got " + response.statusCode());
        if (expectedStatus == 204) {
            check(response.body().length == 0, "Logout response must have no body");
            return null;
        }
        try {
            JsonNode result = JSON.readTree(response.body());
            check(result != null && result.isObject(), "HTTP response must be a JSON object");
            return result;
        } catch (java.io.IOException ex) {
            throw new SmokeFailure("HTTP response is not valid JSON");
        }
    }

    private SocketProbe connect(Account account, String roomId) throws Exception {
        URI uri = new URI(origin.getScheme().equals("https") ? "wss" : "ws", null,
                origin.getHost(), origin.getPort(), "/ws/rooms/" + roomId, null, null);
        SocketProbe probe = new SocketProbe(roomId);
        sockets.add(probe);
        // Track even a late completion so a timed-out handshake cannot leak a subsequently opened socket.
        var connection = http.newWebSocketBuilder().connectTimeout(IO_TIMEOUT)
                .header("Authorization", "Bearer " + account.token()).buildAsync(uri, probe);
        try { probe.socket = connection.get(IO_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
            probe.abort();
            connection.cancel(true);
            throw new SmokeFailure("WebSocket bearer handshake failed");
        }
        return probe;
    }

    private static final class SocketProbe implements WebSocket.Listener {
        private final String roomId;
        private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        private final StringBuilder fragment = new StringBuilder();
        private volatile WebSocket socket;
        private volatile boolean aborted;
        private long appliedRevision = -1;
        private boolean receivedClosed;

        SocketProbe(String roomId) { this.roomId = roomId; }
        @Override public void onOpen(WebSocket socket) {
            this.socket = socket;
            if (aborted) socket.abort();
            else socket.request(1);
        }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if (aborted) return null;
            if (fragment.length() + data.length() > 256 * 1024) {
                events.add(new Event(null, null, "WebSocket text frame exceeds the smoke limit"));
                abort();
                return null;
            }
            fragment.append(data);
            if (last) {
                try {
                    JsonNode event = JSON.readTree(fragment.toString());
                    check(event != null && event.isObject() && text(event, "type").equals("ROOM_SNAPSHOT")
                                    && event.path("data").isObject(), "Unexpected WebSocket JSON envelope");
                    events.add(new Event(event.get("data"), null, null));
                } catch (Exception ex) {
                    events.add(new Event(null, null, "Invalid WebSocket snapshot frame"));
                }
                fragment.setLength(0);
            }
            socket.request(1);
            return null;
        }
        // Keep the JDK's standard ping/pong handling; no simultaneous application-level pong sends.
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            events.add(new Event(null, code, null));
            return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) {
            if (!aborted) events.add(new Event(null, null, "WebSocket transport failed"));
        }
        void awaitSnapshot(JsonNode expected) throws InterruptedException {
            long deadline = System.nanoTime() + IO_TIMEOUT.toNanos();
            while (true) {
                Event event = event(deadline);
                check(event.closeCode() == null, "WebSocket closed before the expected snapshot");
                JsonNode snapshot = event.snapshot();
                check(uuid(snapshot, "roomId").equals(roomId), "WebSocket delivered another room");
                long next = revision(snapshot);
                if (next < appliedRevision || next < revision(expected)) continue;
                sameRoom(snapshot, expected);
                appliedRevision = next;
                if (text(snapshot, "status").equals("CLOSED")) receivedClosed = true;
                return;
            }
        }
        void awaitClose(int expectedCode) throws InterruptedException {
            long deadline = System.nanoTime() + IO_TIMEOUT.toNanos();
            while (true) {
                Event event = event(deadline);
                if (event.closeCode() != null) {
                    check(event.closeCode() == expectedCode, "Unexpected WebSocket close code");
                    if (expectedCode == 1000) check(receivedClosed, "Normal closure arrived before the CLOSED snapshot");
                    return;
                }
                check(uuid(event.snapshot(), "roomId").equals(roomId), "WebSocket delivered another room before closing");
            }
        }
        private Event event(long deadline) throws InterruptedException {
            long remaining = deadline - System.nanoTime();
            Event event = remaining <= 0 ? null : events.poll(remaining, TimeUnit.NANOSECONDS);
            check(event != null, "Timed out waiting for a WebSocket event");
            if (event.failure() != null) throw new SmokeFailure(event.failure());
            return event;
        }
        void abort() {
            aborted = true;
            WebSocket current = socket;
            if (current != null) current.abort();
        }
    }

    private void cleanup() {
        for (Account account : List.copyOf(activeSessions)) {
            try {
                JsonNode room = request("GET", "/rooms/current", account.token(), null, null, null, 200, CLEANUP_TIMEOUT).path("room");
                if (room.isObject() && text(room, "status").equals("OPEN")) {
                    request("POST", "/rooms/" + uuid(room, "roomId") + "/close", account.token(), null,
                            UUID.randomUUID().toString(), revision(room), 200, CLEANUP_TIMEOUT);
                }
            } catch (Exception ex) {
                System.err.println("WARN cleanup: could not confirm closure of this smoke account's room");
            } finally {
                try { request("POST", "/auth/logout", account.token(), null, null, null, 204, CLEANUP_TIMEOUT); }
                catch (Exception ex) { System.err.println("WARN cleanup: could not revoke one smoke session"); }
                activeSessions.remove(account);
            }
        }
        for (SocketProbe socket : sockets) socket.abort();
        http.shutdownNow();
        try {
            if (!http.awaitTermination(CLEANUP_TIMEOUT)) System.err.println("WARN cleanup: HTTP client shutdown timed out");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private static void sameRoom(JsonNode actual, JsonNode expected) {
        check(actual.isObject() && expected.isObject(), "Expected a full room snapshot");
        utc(text(actual, "serverNow"));
        utc(text(expected, "serverNow"));
        ObjectNode first = actual.deepCopy(), second = expected.deepCopy();
        first.remove("serverNow");
        second.remove("serverNow");
        // This scenario deliberately has no active timer. A real clock may still advance serverNow.
        check(first.path("round").isNull() && second.path("round").isNull(), "Unexpected round in task-only WebSocket smoke");
        check(first.equals(second), "Room snapshot differs in stable state or revision");
    }
    private static JsonNode participant(JsonNode room, String userId) {
        check(room.path("participants").isArray(), "Snapshot participants must be an array");
        for (JsonNode participant : room.path("participants")) {
            if (text(participant, "userId").equals(userId)) return participant;
        }
        throw new SmokeFailure("Expected participant missing from snapshot");
    }
    private static long revision(JsonNode room) {
        JsonNode value = room.path("revision");
        check(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 1, "Room revision must be a positive integer");
        return value.longValue();
    }
    private static String text(JsonNode object, String field) {
        JsonNode value = object.path(field);
        check(value.isTextual() && !value.textValue().isEmpty(), "Expected nonempty string field: " + field);
        return value.textValue();
    }
    private static String uuid(JsonNode object, String field) {
        String value = text(object, field);
        try { check(UUID.fromString(value).toString().equalsIgnoreCase(value), "Expected canonical UUID field: " + field); }
        catch (IllegalArgumentException ex) { throw new SmokeFailure("Invalid UUID field: " + field); }
        return value;
    }
    private static void utc(String value) {
        try {
            check(value.endsWith("Z"), "serverNow must use UTC Z");
            Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ex) { throw new SmokeFailure("Invalid serverNow timestamp"); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new SmokeFailure(message);
    }

    private static Options options(String[] args) {
        URI origin = origin("http://127.0.0.1:8090");
        Duration wait = Duration.ofSeconds(60);
        boolean help = false;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--help", "-h" -> help = true;
                case "--base-url" -> {
                    if (++index >= args.length) throw new IllegalArgumentException();
                    origin = origin(args[index]);
                }
                case "--wait-seconds" -> {
                    if (++index >= args.length) throw new IllegalArgumentException();
                    double seconds = Double.parseDouble(args[index]);
                    if (!Double.isFinite(seconds) || seconds < 0 || seconds > 3600) throw new IllegalArgumentException();
                    wait = Duration.ofMillis((long) (seconds * 1000));
                }
                default -> throw new IllegalArgumentException();
            }
        }
        return new Options(origin, wait, help);
    }
    private static URI origin(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            return new URI(scheme, null, uri.getHost(), uri.getPort(), "/", null, null);
        } catch (URISyntaxException ex) { throw new IllegalArgumentException(); }
    }
}
