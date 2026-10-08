package dev.focusduo;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.focusduo.api.Api;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ContractFixtureTest {
    @Test void originalFixturesRoundTripWithoutLosingFields() throws Exception {
        var json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var fixtures = Map.of(
            "auth-session.json", Api.AuthSession.class, "history-item.json", Api.HistoryItem.class,
            "history.json", Api.History.class, "revision-conflict.json", Api.Error.class,
            "room-current.json", Api.CurrentRoom.class, "room-event.json", Api.RoomEvent.class,
            "room-running.json", Api.RoomSnapshot.class, "room-paused.json", Api.RoomSnapshot.class,
            "room-resumed.json", Api.RoomSnapshot.class, "room-completed.json", Api.RoomSnapshot.class);
        for (var entry : fixtures.entrySet()) {
            var source = json.readTree(Path.of("..", "docs", "contracts", "fixtures", entry.getKey()).toFile());
            var converted = json.readTree(json.writeValueAsString(json.treeToValue(source, entry.getValue())));
            assertThat(converted).as(entry.getKey()).isEqualTo(source);
        }
    }
}
