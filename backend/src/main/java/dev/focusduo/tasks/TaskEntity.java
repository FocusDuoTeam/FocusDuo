package dev.focusduo.tasks;

import dev.focusduo.api.Api;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_tasks")
public class TaskEntity {
    @Id private UUID id;
    @Column(nullable = false) private UUID roomId;
    @Column(nullable = false) private UUID userId;
    @Column(nullable = false, length = 160) private String title;
    @Column(nullable = false) private boolean completed;
    @Column(nullable = false) private Instant createdAt;

    protected TaskEntity() {}
    public TaskEntity(UUID roomId, UUID userId, String title, Instant now) {
        this.id = UUID.randomUUID();
        this.roomId = roomId;
        this.userId = userId;
        this.title = title;
        this.createdAt = now;
    }
    public UUID roomId() { return roomId; }
    public UUID userId() { return userId; }
    public void edit(String title, Boolean completed) {
        if (title != null) this.title = title;
        if (completed != null) this.completed = completed;
    }
    public Api.Task snapshot() { return new Api.Task(id, title, completed); }
}
