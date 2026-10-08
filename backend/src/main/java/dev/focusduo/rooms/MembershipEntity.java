package dev.focusduo.rooms;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_memberships")
class MembershipEntity {
    @Id UUID id;
    @Column(nullable = false) UUID roomId;
    @Column(nullable = false) UUID userId;
    @Column(nullable = false) int slot;
    @Column(nullable = false) boolean active;
    @Column(nullable = false, length = 40) String displayName;
    @Column(nullable = false, length = 240) String goal;
    @Column(nullable = false) Instant joinedAt;

    protected MembershipEntity() {}
    MembershipEntity(UUID roomId, UUID userId, int slot, String displayName, Instant now) {
        this.id = UUID.randomUUID();
        this.roomId = roomId;
        this.userId = userId;
        this.slot = slot;
        this.active = true;
        this.displayName = displayName;
        this.goal = "";
        this.joinedAt = now;
    }
}
