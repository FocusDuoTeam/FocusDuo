package dev.focusduo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.focusduo.api.Api;
import dev.focusduo.api.ApiException;
import dev.focusduo.rooms.RoomService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

/** V1 limits and state boundaries against actual PostgreSQL; each command commits separately. */
@SpringBootTest(properties = {"focusduo.maintenance-initial-delay-ms=86400000", "focusduo.maintenance-delay-ms=86400000"})
class ContractBoundaryIT {
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

    record Pair(UUID owner, UUID peer, Api.RoomSnapshot room) {}
    record EndedRound(UUID id, Instant endedAt) {}

    @BeforeEach void time() { clock.set(Instant.parse("2042-01-01T00:00:00Z")); }

    @Test
    void historyReturnsLatestFiftyWithStableOrderingWhenSeveralRoundsEndAtTheSameInstant() {
        Pair pair = pair();
        Api.RoomSnapshot room = pair.room();
        List<EndedRound> completed = new ArrayList<>();
        for (int index = 0; index < 53; index++) {
            if (index % 4 == 0) clock.advance(Duration.ofSeconds(1));
            room = command(pair.owner(), "POST", path(room) + "/rounds", Map.of("kind", "FOCUS"), room);
            room = command(pair.owner(), "POST", roundPath(room) + "/finish", null, room);
            completed.add(new EndedRound(room.round().id(), room.round().endedAt()));
        }
        // V1 leaves the UUID direction unspecified; either consistent direction is valid.
        // Canonical strings sort like PostgreSQL UUIDs, unlike Java's signed UUID.compareTo.
        List<UUID> descendingTies = completed.stream()
                .sorted(Comparator.comparing(EndedRound::endedAt).reversed()
                        .thenComparing(item -> item.id().toString(), Comparator.reverseOrder()))
                .limit(50).map(EndedRound::id).toList();
        List<UUID> ascendingTies = completed.stream()
                .sorted(Comparator.comparing(EndedRound::endedAt).reversed()
                        .thenComparing(item -> item.id().toString()))
                .limit(50).map(EndedRound::id).toList();
        List<Api.HistoryItem> ownerHistory = rooms.history(pair.owner());
        assertThat(ownerHistory).hasSize(50);
        assertThat(ownerHistory.stream().map(Api.HistoryItem::roundId).toList()).isIn(descendingTies, ascendingTies);
        assertThat(rooms.history(pair.owner())).isEqualTo(ownerHistory);
        assertThat(rooms.history(pair.peer())).isEqualTo(ownerHistory);
        assertThat(rooms.history(user())).isEmpty();
        assertThat(db.queryForObject("select count(*) from round_history where room_id = ?", Long.class, room.roomId()))
                .isEqualTo(53L);
    }

    @Test
    void taskLimitIsPerParticipantAndOnlyAnAuthorsDeletionReleasesTheirCapacity() {
        Pair pair = pair();
        Api.RoomSnapshot room = pair.room();
        for (int index = 0; index < 20; index++) {
            room = command(pair.owner(), "POST", path(room) + "/tasks", Map.of("title", "Task " + index), room);
        }
        room = command(pair.peer(), "POST", path(room) + "/tasks", Map.of("title", "Independent peer task"), room);
        Api.RoomSnapshot full = room;
        UUID taskId = full.participants().getFirst().tasks().getFirst().id();
        error("VALIDATION_ERROR", () -> command(pair.owner(), "POST", path(full) + "/tasks", Map.of("title", "Twenty-first"), full));
        error("FORBIDDEN", () -> command(pair.peer(), "DELETE", path(full) + "/tasks/" + taskId, null, full));
        error("NOT_FOUND", () -> command(pair.owner(), "DELETE", path(full) + "/tasks/" + UUID.randomUUID(), null, full));
        assertThat(rooms.get(pair.owner(), full.roomId())).isEqualTo(full);

        room = command(pair.owner(), "DELETE", path(full) + "/tasks/" + taskId, null, full);
        assertThat(room.participants().getFirst().tasks()).hasSize(19);
        assertThat(room.participants().getFirst().tasks()).extracting(Api.Task::id).doesNotContain(taskId);
        room = command(pair.owner(), "POST", path(room) + "/tasks", Map.of("title", "Replacement"), room);
        assertThat(room.participants().getFirst().tasks()).hasSize(20);
        assertThat(room.participants().get(1).tasks()).hasSize(1);
    }

    @Test
    void durationsAcceptTheirInclusiveBoundsAndDefaultsButRejectOutOfRangeFractionalAndNonNumericValues() throws Exception {
        UUID candidate = user();
        for (String invalid : List.of(
                "{\"focusDurationSeconds\":240}", "{\"focusDurationSeconds\":301}", "{\"focusDurationSeconds\":10860}",
                "{\"breakDurationSeconds\":0}", "{\"breakDurationSeconds\":61}", "{\"breakDurationSeconds\":3660}",
                "{\"focusDurationSeconds\":300.0}", "{\"focusDurationSeconds\":\"300\"}",
                "{\"focusDurationSeconds\":null}", "{\"focusDurationSeconds\":9223372036854775807}",
                "{\"breakDurationSeconds\":true}")) {
            JsonNode body = json.readTree(invalid);
            error("VALIDATION_ERROR", () -> command(candidate, "POST", "/rooms", body, null));
        }
        assertThat(rooms.current(candidate)).isNull();
        Api.RoomSnapshot defaults = command(candidate, "POST", "/rooms", Map.of(), null);
        assertThat(defaults.focusDurationSeconds()).isEqualTo(1500);
        assertThat(defaults.breakDurationSeconds()).isEqualTo(300);

        UUID owner = user();
        Api.RoomSnapshot room = command(owner, "POST", "/rooms", settings(300, 60), null);
        assertThat(room.focusDurationSeconds()).isEqualTo(300);
        assertThat(room.breakDurationSeconds()).isEqualTo(60);
        room = command(owner, "PATCH", path(room) + "/settings", settings(10800, 3600), room);
        assertThat(room.focusDurationSeconds()).isEqualTo(10800);
        assertThat(room.breakDurationSeconds()).isEqualTo(3600);
        Api.RoomSnapshot maximum = room;
        error("VALIDATION_ERROR", () -> command(owner, "PATCH", path(maximum) + "/settings", Map.of("focusDurationSeconds", 300), maximum));
        error("VALIDATION_ERROR", () -> command(owner, "PATCH", path(maximum) + "/settings", settings(10860, 3600), maximum));
        assertThat(rooms.get(owner, maximum.roomId())).isEqualTo(maximum);
        room = command(owner, "PATCH", path(maximum) + "/settings", settings(300, 60), maximum);
        assertThat(room.focusDurationSeconds()).isEqualTo(300);
        assertThat(room.breakDurationSeconds()).isEqualTo(60);
    }

    @Test
    void settingsRequireOwnerAndRemainFrozenForRunningAndPausedRounds() {
        Pair pair = pair();
        Api.RoomSnapshot initial = pair.room();
        error("FORBIDDEN", () -> command(pair.peer(), "PATCH", path(initial) + "/settings", settings(600, 120), initial));
        Api.RoomSnapshot running = command(pair.owner(), "POST", path(initial) + "/rounds", Map.of("kind", "FOCUS"), initial);
        error("INVALID_STATE", () -> command(pair.owner(), "PATCH", path(running) + "/settings", settings(600, 120), running));
        assertThat(rooms.get(pair.owner(), running.roomId())).isEqualTo(running);
        Api.RoomSnapshot paused = command(pair.owner(), "POST", roundPath(running) + "/pause", null, running);
        error("INVALID_STATE", () -> command(pair.owner(), "PATCH", path(paused) + "/settings", settings(600, 120), paused));
        assertThat(rooms.get(pair.owner(), paused.roomId())).isEqualTo(paused);
        Api.RoomSnapshot finished = command(pair.owner(), "POST", roundPath(paused) + "/finish", null, paused);
        Api.RoomSnapshot settings = command(pair.owner(), "PATCH", path(finished) + "/settings", settings(600, 120), finished);
        assertThat(settings.round().durationSeconds()).isEqualTo(300);
        Api.RoomSnapshot next = command(pair.owner(), "POST", path(settings) + "/rounds", Map.of("kind", "FOCUS"), settings);
        assertThat(next.round().durationSeconds()).isEqualTo(600);
    }

    @Test
    void breakRequiresACompletedFocusAndCannotBeChainedAfterAnotherBreak() {
        Pair pair = pair();
        Api.RoomSnapshot initial = pair.room();
        error("INVALID_STATE", () -> command(pair.owner(), "POST", path(initial) + "/rounds", Map.of("kind", "BREAK"), initial));
        Api.RoomSnapshot running = command(pair.owner(), "POST", path(initial) + "/rounds", Map.of("kind", "FOCUS"), initial);
        error("INVALID_STATE", () -> command(pair.owner(), "POST", path(running) + "/rounds", Map.of("kind", "BREAK"), running));
        Api.RoomSnapshot completed = command(pair.owner(), "POST", roundPath(running) + "/finish", null, running);
        error("FORBIDDEN", () -> command(pair.peer(), "POST", path(completed) + "/rounds", Map.of("kind", "BREAK"), completed));
        Api.RoomSnapshot rest = command(pair.owner(), "POST", path(completed) + "/rounds", Map.of("kind", "BREAK"), completed);
        assertThat(rest.round().kind()).isEqualTo("BREAK");
        assertThat(rest.round().durationSeconds()).isEqualTo(60);
        clock.advance(Duration.ofSeconds(60));
        Api.RoomSnapshot ended = rooms.get(pair.owner(), rest.roomId());
        assertThat(ended.round().completionReason()).isEqualTo("ELAPSED");
        error("INVALID_STATE", () -> command(pair.owner(), "POST", path(ended) + "/rounds", Map.of("kind", "BREAK"), ended));
        Api.RoomSnapshot next = command(pair.owner(), "POST", path(ended) + "/rounds", Map.of("kind", "FOCUS"), ended);
        assertThat(next.round().kind()).isEqualTo("FOCUS");
        assertThat(rooms.history(pair.owner())).extracting(Api.HistoryItem::kind).containsExactly("BREAK", "FOCUS");
    }

    @Test
    void textLimitsCountUnicodeCharactersTrimTaskTitlesAndAllowAnEmptyGoal() {
        Pair pair = pair();
        String title = "\uD83D\uDCDA".repeat(160);
        String goal = "\uD83D\uDCDA".repeat(240);
        Api.RoomSnapshot room = command(pair.peer(), "POST", path(pair.room()) + "/tasks", Map.of("title", "  " + title + "  "), pair.room());
        room = command(pair.peer(), "PATCH", path(room) + "/me", Map.of("goal", goal), room);
        assertThat(room.participants().get(1).tasks().getFirst().title()).isEqualTo(title);
        assertThat(room.participants().get(1).goal()).isEqualTo(goal);
        Api.RoomSnapshot boundary = room;
        for (String invalidTitle : List.of("", "   ", title + "x")) {
            error("VALIDATION_ERROR", () -> command(pair.peer(), "POST", path(boundary) + "/tasks", Map.of("title", invalidTitle), boundary));
        }
        error("VALIDATION_ERROR", () -> command(pair.peer(), "PATCH", path(boundary) + "/me", Map.of("goal", goal + "x"), boundary));
        assertThat(rooms.get(pair.peer(), room.roomId())).isEqualTo(boundary);
        room = command(pair.peer(), "PATCH", path(boundary) + "/me", Map.of("goal", ""), boundary);
        assertThat(room.participants().get(1).goal()).isEmpty();
    }

    @Test
    void rejectedShapesAndNonCanonicalUuidsLeaveStateUnchangedAndDoNotConsumeAnIdempotencyKey() throws Exception {
        Pair pair = pair();
        Api.RoomSnapshot initial = pair.room();
        String path = path(initial) + "/tasks";
        UUID key = UUID.randomUUID();
        for (String invalid : List.of("null", "[]", "\"task\"", "{}", "{\"title\":true}", "{\"title\":null}",
                "{\"title\":\"Task\",\"completed\":true}", "{\"title\":\"Task\",\"expectedRevision\":2}")) {
            JsonNode body = json.readTree(invalid);
            error("VALIDATION_ERROR", () -> rooms.command(pair.owner(), key, "POST", "/api/v1" + path, initial.revision(), body));
        }
        for (String malformedId : List.of("not-a-uuid", "1-1-1-1-1")) {
            error("VALIDATION_ERROR", () -> command(pair.owner(), "DELETE", path + "/" + malformedId, null, initial));
        }
        error("VALIDATION_ERROR", () -> command(pair.owner(), "POST", path(initial) + "/close", Map.of(), initial));
        assertThat(rooms.get(pair.owner(), initial.roomId())).isEqualTo(initial);
        Api.RoomSnapshot created = rooms.command(pair.owner(), key, "POST", "/api/v1" + path,
                initial.revision(), json.valueToTree(Map.of("title", "Accepted after validation failures")));
        assertThat(created.revision()).isEqualTo(initial.revision() + 1);
        assertThat(created.participants().getFirst().tasks()).hasSize(1);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        db.update("insert into app_users(id,username,display_name,password_hash) values (?,?,?,?)",
                id, "b_" + id.toString().replace("-", "").substring(0, 25), "Boundary test", "not-an-authenticated-account");
        return id;
    }
    private Pair pair() {
        UUID owner = user(), peer = user();
        Api.RoomSnapshot room = command(owner, "POST", "/rooms", settings(300, 60), null);
        room = command(peer, "POST", "/rooms/join", Map.of("code", room.code()), null);
        return new Pair(owner, peer, room);
    }
    private Api.RoomSnapshot command(UUID user, String method, String path, Object body, Api.RoomSnapshot previous) {
        JsonNode node = body == null ? null : json.valueToTree(body);
        return rooms.command(user, UUID.randomUUID(), method, "/api/v1" + path,
                previous == null ? null : previous.revision(), node);
    }
    private static Map<String, Integer> settings(int focus, int rest) {
        return Map.of("focusDurationSeconds", focus, "breakDurationSeconds", rest);
    }
    private static String path(Api.RoomSnapshot room) { return "/rooms/" + room.roomId(); }
    private static String roundPath(Api.RoomSnapshot room) { return path(room) + "/rounds/" + room.round().id(); }
    private static void error(String code, Runnable action) {
        ApiException error = catchThrowableOfType(ApiException.class, action::run);
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo(code);
        assertThat(error.snapshot()).isNull();
    }
}
