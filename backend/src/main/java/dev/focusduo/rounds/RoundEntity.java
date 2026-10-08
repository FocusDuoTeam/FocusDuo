package dev.focusduo.rounds;

import dev.focusduo.api.Api;
import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Persisted temporal anchors, with no dependence on a process-local countdown. */
@Entity
@Table(name = "rounds")
public class RoundEntity {
    @Id private UUID id;
    @Column(nullable = false) private UUID roomId;
    @Column(nullable = false, length = 16) private String kind;
    @Column(nullable = false, length = 16) private String status;
    @Column(nullable = false) private int durationSeconds;
    @Column(nullable = false) private Instant startedAt;
    private Instant endsAt;
    private Instant segmentStartedAt;
    @Column(nullable = false) private long activeElapsedMs;
    private Instant endedAt;
    @Column(length = 24) private String completionReason;

    protected RoundEntity() {}
    public RoundEntity(UUID roomId, String kind, int durationSeconds, Instant now) {
        this.id = UUID.randomUUID();
        this.roomId = roomId;
        this.kind = kind;
        this.status = "RUNNING";
        this.durationSeconds = durationSeconds;
        this.startedAt = now;
        this.segmentStartedAt = now;
        this.endsAt = now.plusSeconds(durationSeconds);
    }
    public UUID id() { return id; }
    public UUID roomId() { return roomId; }
    public String kind() { return kind; }
    public String status() { return status; }
    public boolean active() { return "RUNNING".equals(status) || "PAUSED".equals(status); }
    public boolean due(Instant now) { return "RUNNING".equals(status) && !now.isBefore(endsAt); }

    public long activeAt(Instant now) {
        long segment = "RUNNING".equals(status) ? Math.max(0, Duration.between(segmentStartedAt, now).toMillis()) : 0;
        return Math.min(durationSeconds * 1000L, activeElapsedMs + segment);
    }
    public void pause(Instant now) {
        requireStatus("RUNNING");
        activeElapsedMs = activeAt(now);
        status = "PAUSED";
        endsAt = null;
        segmentStartedAt = null;
    }
    public void resume(Instant now) {
        requireStatus("PAUSED");
        status = "RUNNING";
        segmentStartedAt = now;
        endsAt = now.plusMillis(durationSeconds * 1000L - activeElapsedMs);
    }
    public void completeElapsed() {
        requireStatus("RUNNING");
        terminate("COMPLETED", "ELAPSED", endsAt);
    }
    public void finish(Instant now) { terminate("COMPLETED", "MANUAL", now); }
    public void cancel(Instant now) { terminate("CANCELLED", "ROOM_CLOSED", now); }
    private void terminate(String finalStatus, String reason, Instant now) {
        if (!active()) throw new IllegalStateException("Cannot terminate an inactive round");
        activeElapsedMs = activeAt(now);
        endedAt = now;
        status = finalStatus;
        completionReason = reason;
        endsAt = null;
        segmentStartedAt = null;
    }
    private void requireStatus(String expected) {
        if (!expected.equals(status)) throw new IllegalStateException("Unexpected round state");
    }
    public Api.Round snapshot(Instant now) {
        long elapsed = activeAt(now);
        return new Api.Round(id, kind, status, durationSeconds, startedAt, endsAt,
                active() ? durationSeconds * 1000L - elapsed : 0, elapsed, endedAt, completionReason);
    }
}
