package dev.focusduo.rooms;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rooms")
class RoomEntity {
    @Id UUID id;
    @Column(nullable = false, length = 9) String code;
    @Column(nullable = false) UUID ownerId;
    @Column(nullable = false, length = 16) String status;
    @Column(nullable = false) long revision;
    @Column(nullable = false) int focusDurationSeconds;
    @Column(nullable = false) int breakDurationSeconds;
    UUID currentRoundId;
    @Column(nullable = false) Instant createdAt;

    protected RoomEntity() {}
}
