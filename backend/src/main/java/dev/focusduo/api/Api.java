package dev.focusduo.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire types from the shared v1 contract. Persistence entities never leave the server. */
public final class Api {
    private Api() {}

    public record User(UUID id, String username, String displayName) {}
    public record AuthSession(User user, String accessToken, Instant expiresAt) {}
    public record Task(UUID id, String title, boolean completed) {}
    public record Participant(UUID userId, String displayName, String goal, List<Task> tasks) {}
    public record Round(UUID id, String kind, String status, int durationSeconds,
                        Instant startedAt, Instant endsAt, long remainingMs, long activeElapsedMs,
                        Instant endedAt, String completionReason) {}
    public record RoomSnapshot(UUID roomId, String code, UUID ownerId, String status,
                               long revision, Instant serverNow, int focusDurationSeconds,
                               int breakDurationSeconds, List<Participant> participants, Round round) {}
    public record CurrentRoom(RoomSnapshot room) {}
    public record HistoryParticipant(UUID userId, String displayName, String goal, List<Task> tasks,
                                     int totalTaskCount, int completedTaskCount) {}
    public record HistoryItem(UUID roundId, UUID roomId, String kind, String status,
                              String completionReason, int durationSeconds, Instant startedAt,
                              Instant endedAt, long activeElapsedMs, List<HistoryParticipant> participants) {}
    public record History(List<HistoryItem> items) {}
    public record Error(String code, String message, String requestId, Map<String, String> fieldErrors,
                        RoomSnapshot snapshot) {}
    public record RoomEvent(String type, RoomSnapshot data) {
        public RoomEvent(RoomSnapshot data) { this("ROOM_SNAPSHOT", data); }
    }
}
