package dev.focusduo.history;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "history_participants")
public class HistoryParticipantEntity {
    @Id private UUID id;
    @Column(nullable = false) private UUID roundId;
    @Column(nullable = false) private UUID userId;

    protected HistoryParticipantEntity() {}
    public HistoryParticipantEntity(UUID roundId, UUID userId) {
        this.id = UUID.randomUUID();
        this.roundId = roundId;
        this.userId = userId;
    }
}
