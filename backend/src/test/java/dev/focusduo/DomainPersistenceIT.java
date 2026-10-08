package dev.focusduo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.focusduo.api.Api;
import dev.focusduo.api.ApiException;
import dev.focusduo.rooms.RoomService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL lock, constraint, and transactional failure tests, without HTTP transport concerns. */
@SpringBootTest(properties = {"focusduo.maintenance-initial-delay-ms=86400000", "focusduo.maintenance-delay-ms=86400000"})
class DomainPersistenceIT {
    @TestConfiguration static class TimeConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> TestDatabase.URL);
        properties.add("spring.datasource.username", () -> TestDatabase.USER);
        properties.add("spring.datasource.password", () -> TestDatabase.PASSWORD);
    }
    @Autowired RoomService rooms;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void time() { clock.set(Instant.parse("2040-01-01T00:00:00Z")); }
    record Pair(UUID owner, UUID peer, Api.RoomSnapshot room) {}
    record Outcome(Api.RoomSnapshot snapshot, ApiException error) {}

    private UUID user() {
        UUID id = UUID.randomUUID();
        db.update("insert into app_users(id,username,display_name,password_hash) values (?,?,?,?)",
                id, "d_" + id.toString().replace("-", "").substring(0, 25), "Domain test", "not-an-authenticated-account");
        return id;
    }
    private JsonNode body(Object value) { return value == null ? null : json.valueToTree(value); }
    private Api.RoomSnapshot command(UUID user, String method, String path, Object body, Long revision) {
        return rooms.command(user, UUID.randomUUID(), method, "/api/v1" + path, revision, body(body));
    }
    private Pair pair() {
        UUID owner = user(), peer = user();
        Api.RoomSnapshot room = command(owner, "POST", "/rooms", Map.of("focusDurationSeconds", 300, "breakDurationSeconds", 60), null);
        room = command(peer, "POST", "/rooms/join", Map.of("code", room.code()), null);
        return new Pair(owner, peer, room);
    }
    private Api.RoomSnapshot focus(Pair pair) {
        return command(pair.owner(), "POST", "/rooms/" + pair.room().roomId() + "/rounds", Map.of("kind", "FOCUS"), pair.room().revision());
    }
    private String roundPath(Api.RoomSnapshot room) { return "/rooms/" + room.roomId() + "/rounds/" + room.round().id(); }
    private long historyCount(UUID roundId) {
        return db.queryForObject("select count(*) from round_history where round_id = ?", Long.class, roundId);
    }
    private Outcome attempt(UUID user, String method, String path, Object body, Long revision) {
        try { return new Outcome(command(user, method, path, body, revision), null); }
        catch (ApiException ex) { return new Outcome(null, ex); }
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).as("concurrent test coordination").isTrue(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }

    @Test
    void simultaneousSameTaskKeyCreatesOneTaskAndReturnsIdenticalSavedResult() throws Exception {
        Pair pair = pair();
        Api.RoomSnapshot room = pair.room();
        UUID key = UUID.randomUUID();
        JsonNode request = body(Map.of("title", "Exactly once"));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Api.RoomSnapshot>> replies = new ArrayList<>();
            for (int i = 0; i < 8; i++) replies.add(pool.submit(() -> {
                await(start);
                return rooms.command(pair.peer(), key, "POST", "/api/v1/rooms/" + room.roomId() + "/tasks", room.revision(), request);
            }));
            start.countDown();
            Api.RoomSnapshot first = replies.getFirst().get(20, TimeUnit.SECONDS);
            for (Future<Api.RoomSnapshot> reply : replies) assertThat(reply.get(20, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(first.revision()).isEqualTo(room.revision() + 1);
            assertThat(first.participants().get(1).tasks()).hasSize(1);
            assertThat(db.queryForObject("select count(*) from room_tasks where room_id = ?", Long.class, room.roomId())).isEqualTo(1);
            assertThat(db.queryForObject("select count(*) from idempotency_results where user_id = ? and request_key = ?", Long.class, pair.peer(), key)).isEqualTo(1);
        }
    }

    @Test
    void pauseAndCloseRaceSerializesAndProducesOneCancellation() throws Exception {
        Pair pair = pair(); Api.RoomSnapshot running = focus(pair);
        clock.advance(Duration.ofSeconds(100));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Outcome> pause = pool.submit(() -> { await(start); return attempt(pair.owner(), "POST", roundPath(running) + "/pause", null, running.revision()); });
            Future<Outcome> close = pool.submit(() -> { await(start); return attempt(pair.peer(), "POST", "/rooms/" + running.roomId() + "/close", null, running.revision()); });
            start.countDown();
            List<Outcome> outcomes = List.of(pause.get(20, TimeUnit.SECONDS), close.get(20, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(o -> o.error() == null)).hasSize(1);
            assertThat(outcomes.stream().filter(o -> o.error() != null).findFirst().orElseThrow().error().code()).isEqualTo("REVISION_CONFLICT");
        }
        Api.RoomSnapshot finalState = rooms.get(pair.owner(), running.roomId());
        if (finalState.status().equals("OPEN")) {
            finalState = command(pair.peer(), "POST", "/rooms/" + running.roomId() + "/close", null, finalState.revision());
        }
        assertThat(finalState.round().status()).isEqualTo("CANCELLED");
        assertThat(finalState.round().activeElapsedMs()).isEqualTo(100_000);
        assertThat(historyCount(running.round().id())).isEqualTo(1);
        assertThat(rooms.current(pair.owner())).isNull();
        assertThat(rooms.current(pair.peer())).isNull();
    }

    @Test
    void expiryPauseAndCloseRaceCommitsExactlyOneElapsedResultBeforeConflicts() throws Exception {
        Pair pair = pair(); Api.RoomSnapshot running = focus(pair);
        clock.advance(Duration.ofSeconds(301));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            Future<Outcome> pause = pool.submit(() -> { await(start); return attempt(pair.owner(), "POST", roundPath(running) + "/pause", null, running.revision()); });
            Future<Outcome> close = pool.submit(() -> { await(start); return attempt(pair.peer(), "POST", "/rooms/" + running.roomId() + "/close", null, running.revision()); });
            Future<?> maintenance = pool.submit(() -> { await(start); rooms.refreshDueRooms(); });
            start.countDown();
            for (Outcome result : List.of(pause.get(20, TimeUnit.SECONDS), close.get(20, TimeUnit.SECONDS))) {
                assertThat(result.error()).isNotNull();
                assertThat(result.error().code()).isEqualTo("REVISION_CONFLICT");
                assertThat(result.error().snapshot().round().completionReason()).isEqualTo("ELAPSED");
            }
            maintenance.get(20, TimeUnit.SECONDS);
        }
        Api.RoomSnapshot finalState = rooms.get(pair.owner(), running.roomId());
        assertThat(finalState.revision()).isEqualTo(running.revision() + 1);
        assertThat(finalState.round().endedAt()).isEqualTo(running.round().endsAt());
        assertThat(finalState.round().activeElapsedMs()).isEqualTo(300_000);
        assertThat(historyCount(running.round().id())).isEqualTo(1);
        Api.HistoryItem history = rooms.historyItem(pair.peer(), running.round().id());
        command(pair.peer(), "POST", "/rooms/" + running.roomId() + "/close", null, finalState.revision());
        assertThat(rooms.historyItem(pair.owner(), running.round().id())).isEqualTo(history);
    }

    @Test
    void waitingPeerCommandDoesNotDeadlockHistoryForeignKeyInsertion() throws Exception {
        Pair pair = pair(); Api.RoomSnapshot running = focus(pair);
        CountDownLatch roomLocked = new CountDownLatch(1), peerLocked = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Api.RoomSnapshot> finish = pool.submit(() -> tx.execute(status -> {
                db.queryForObject("select id from rooms where id = ? for update", UUID.class, running.roomId());
                roomLocked.countDown();
                await(peerLocked);
                return command(pair.owner(), "POST", roundPath(running) + "/finish", null, running.revision());
            }));
            Future<Outcome> waitingPeer = pool.submit(() -> tx.execute(status -> {
                await(roomLocked);
                db.queryForObject("select id from app_users where id = ? for no key update", UUID.class, pair.peer());
                peerLocked.countDown();
                return attempt(pair.peer(), "PATCH", "/rooms/" + running.roomId() + "/me", Map.of("goal", "Concurrent edit"), running.revision());
            }));
            assertThat(finish.get(20, TimeUnit.SECONDS).round().status()).isEqualTo("COMPLETED");
            assertThat(waitingPeer.get(20, TimeUnit.SECONDS).error().code()).isEqualTo("REVISION_CONFLICT");
        }
        assertThat(historyCount(running.round().id())).isEqualTo(1);
    }

    @Test
    void databaseGuardsMembershipCapacityActiveMembershipActiveRoundAndImmutableHistory() {
        Pair pair = pair();
        UUID third = user();
        assertThatThrownBy(() -> insertMembership(pair.room().roomId(), third, 3))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertMembership(pair.room().roomId(), third, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        Api.RoomSnapshot other = command(third, "POST", "/rooms", Map.of(), null);
        assertThatThrownBy(() -> insertMembership(other.roomId(), pair.peer(), 2))
                .isInstanceOf(DataIntegrityViolationException.class);

        Api.RoomSnapshot running = focus(pair);
        assertThatThrownBy(() -> db.update("""
                insert into rounds(id, room_id, kind, status, duration_seconds, started_at, ends_at, segment_started_at, active_elapsed_ms)
                select ?, room_id, kind, status, duration_seconds, started_at, ends_at, segment_started_at, active_elapsed_ms
                from rounds where id = ?
                """, UUID.randomUUID(), running.round().id())).isInstanceOf(DataIntegrityViolationException.class);
        command(pair.owner(), "POST", roundPath(running) + "/finish", null, running.revision());
        assertThatThrownBy(() -> db.update("insert into round_history select * from round_history where round_id = ?", running.round().id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("update round_history set snapshot = '{}'::jsonb where round_id = ?", running.round().id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.update("delete from history_participants where round_id = ?", running.round().id()))
                .isInstanceOf(DataAccessException.class);
        assertThat(rooms.historyItem(pair.peer(), running.round().id()).participants()).hasSize(2);
    }

    @Test
    void validationFailureDoesNotPartiallyMutateACommandThatCommitsExpectedErrors() {
        Pair pair = pair();
        String base = "/rooms/" + pair.room().roomId();
        Api.RoomSnapshot withTask = command(pair.peer(), "POST", base + "/tasks", Map.of("title", "Original"), pair.room().revision());
        UUID task = withTask.participants().get(1).tasks().getFirst().id();
        Outcome badTask = attempt(pair.peer(), "PATCH", base + "/tasks/" + task,
                Map.of("title", "Should not persist", "completed", "invalid boolean"), withTask.revision());
        assertThat(badTask.error().code()).isEqualTo("VALIDATION_ERROR");
        Outcome badSettings = attempt(pair.owner(), "PATCH", base + "/settings",
                Map.of("focusDurationSeconds", 600, "breakDurationSeconds", 61), withTask.revision());
        assertThat(badSettings.error().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(rooms.get(pair.owner(), withTask.roomId())).isEqualTo(withTask);
    }

    private void insertMembership(UUID roomId, UUID userId, int slot) {
        db.update("""
                insert into room_memberships(id, room_id, user_id, slot, active, display_name, goal, joined_at)
                values (?, ?, ?, ?, true, 'Constraint test', '', now())
                """, UUID.randomUUID(), roomId, userId, slot);
    }
}
