package dev.focusduo.history;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "round_history")
public class HistoryEntity {
    @Id private UUID roundId;
    @Column(nullable = false) private UUID roomId;
    @Column(nullable = false) private Instant endedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private JsonNode snapshot;

    protected HistoryEntity() {}
    public HistoryEntity(UUID roundId, UUID roomId, Instant endedAt, JsonNode snapshot) {
        this.roundId = roundId;
        this.roomId = roomId;
        this.endedAt = endedAt;
        this.snapshot = snapshot.deepCopy();
    }
    public JsonNode snapshot() { return snapshot; }
}
