package dev.focusduo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.focusduo.api.Api;
import dev.focusduo.auth.AuthService;
import dev.focusduo.rooms.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Two independently constructed application contexts against the same PostgreSQL database. */
class RestartIT {
    private static final MutableClock TIME = new MutableClock();

    @TestConfiguration
    static class RestartClockConfiguration {
        @Bean @Primary MutableClock restartClock() { return TIME; }
    }

    record Running(UUID ownerId, String accessToken, Api.RoomSnapshot started, UUID key, long originalRevision) {}

    @Test
    void restartRecoversPersistedDeadlinePauseAndIdempotencyWithoutInMemoryRoomState() throws Exception {
        TIME.set(Instant.parse("2040-01-01T00:00:00Z"));
        Running overdue;
        Running paused;
        Api.RoomSnapshot beforeShutdown;
        ConfigurableApplicationContext first = application();
        try (first) {
            overdue = startFocus(first);
            paused = startFocus(first);
            TIME.advance(Duration.ofSeconds(90));
            beforeShutdown = first.getBean(RoomService.class).command(paused.ownerId(), UUID.randomUUID(), "POST",
                    roundPath(paused.started()) + "/pause", paused.started().revision(), null);
            assertThat(beforeShutdown.round().remainingMs()).isEqualTo(210_000);
            assertThat(beforeShutdown.round().activeElapsedMs()).isEqualTo(90_000);
        }
        assertThat(first.isActive()).isFalse();
        // No application is running during this time jump: neither scheduler nor cached countdown can help.
        TIME.advance(Duration.ofHours(4));

        try (ConfigurableApplicationContext restarted = application()) {
            RoomService rooms = restarted.getBean(RoomService.class);
            AuthService auth = restarted.getBean(AuthService.class);
            ObjectMapper json = restarted.getBean(ObjectMapper.class);
            assertThat(auth.authenticate(overdue.accessToken()).userId()).isEqualTo(overdue.ownerId());

            Api.RoomSnapshot recovered = rooms.current(overdue.ownerId());
            assertThat(recovered.round().id()).isEqualTo(overdue.started().round().id());
            assertThat(recovered.revision()).isEqualTo(overdue.started().revision() + 1);
            assertThat(recovered.round().status()).isEqualTo("COMPLETED");
            assertThat(recovered.round().completionReason()).isEqualTo("ELAPSED");
            assertThat(recovered.round().endedAt()).isEqualTo(overdue.started().round().endsAt());
            assertThat(recovered.round().activeElapsedMs()).isEqualTo(300_000);
            assertThat(recovered.round().remainingMs()).isZero();

            Api.RoomSnapshot replay = rooms.command(overdue.ownerId(), overdue.key(), "POST",
                    roomPath(overdue.started()) + "/rounds", overdue.originalRevision(), json.readTree("{\"kind\":\"FOCUS\"}"));
            assertThat(replay).isEqualTo(overdue.started());
            assertThat(rooms.get(overdue.ownerId(), recovered.roomId())).isEqualTo(recovered);
            assertThat(rooms.history(overdue.ownerId())).hasSize(1);
            assertThat(rooms.historyItem(overdue.ownerId(), recovered.round().id()).endedAt())
                    .isEqualTo(overdue.started().round().endsAt());

            Api.RoomSnapshot stillPaused = rooms.current(paused.ownerId());
            assertThat(stillPaused.round()).isEqualTo(beforeShutdown.round());
            assertThat(stillPaused.revision()).isEqualTo(beforeShutdown.revision());
            assertThat(rooms.history(paused.ownerId())).isEmpty();
            Api.RoomSnapshot resumed = rooms.command(paused.ownerId(), UUID.randomUUID(), "POST",
                    roundPath(stillPaused) + "/resume", stillPaused.revision(), null);
            assertThat(resumed.round().startedAt()).isEqualTo(paused.started().round().startedAt());
            assertThat(resumed.round().endsAt()).isEqualTo(TIME.instant().plusSeconds(210));
            TIME.advance(Duration.ofSeconds(10));
            Api.RoomSnapshot finished = rooms.command(paused.ownerId(), UUID.randomUUID(), "POST",
                    roundPath(resumed) + "/finish", resumed.revision(), null);
            assertThat(finished.round().activeElapsedMs()).isEqualTo(100_000);
            assertThat(finished.round().completionReason()).isEqualTo("MANUAL");
            assertThat(rooms.history(paused.ownerId())).hasSize(1);
            assertThat(rooms.history(overdue.ownerId())).hasSize(1);
        }
    }

    private static ConfigurableApplicationContext application() {
        return new SpringApplicationBuilder(FocusDuoApplication.class, RestartClockConfiguration.class)
                .web(WebApplicationType.SERVLET).bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                .registerShutdownHook(false)
                .run("--server.port=0", "--server.address=127.0.0.1",
                        "--spring.datasource.url=" + TestDatabase.URL,
                        "--spring.datasource.username=" + TestDatabase.USER,
                        "--spring.datasource.password=" + TestDatabase.PASSWORD,
                        "--focusduo.maintenance-initial-delay-ms=86400000",
                        "--focusduo.maintenance-delay-ms=86400000");
    }

    private static Running startFocus(ConfigurableApplicationContext context) throws Exception {
        AuthService auth = context.getBean(AuthService.class);
        RoomService rooms = context.getBean(RoomService.class);
        ObjectMapper json = context.getBean(ObjectMapper.class);
        Api.AuthSession owner = auth.register(username(), "Restart owner", "Restart-example-password");
        Api.AuthSession peer = auth.register(username(), "Restart partner", "Restart-example-password");
        Api.RoomSnapshot room = rooms.command(owner.user().id(), UUID.randomUUID(), "POST", "/api/v1/rooms", null,
                json.readTree("{\"focusDurationSeconds\":300,\"breakDurationSeconds\":60}"));
        JsonNode join = json.createObjectNode().put("code", room.code());
        room = rooms.command(peer.user().id(), UUID.randomUUID(), "POST", "/api/v1/rooms/join", null, join);
        UUID startKey = UUID.randomUUID();
        Api.RoomSnapshot running = rooms.command(owner.user().id(), startKey, "POST", roomPath(room) + "/rounds",
                room.revision(), json.readTree("{\"kind\":\"FOCUS\"}"));
        return new Running(owner.user().id(), owner.accessToken(), running, startKey, room.revision());
    }

    private static String username() { return "restart_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20); }
    private static String roomPath(Api.RoomSnapshot room) { return "/api/v1/rooms/" + room.roomId(); }
    private static String roundPath(Api.RoomSnapshot room) { return roomPath(room) + "/rounds/" + room.round().id(); }
}
