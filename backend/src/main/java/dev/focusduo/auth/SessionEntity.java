package dev.focusduo.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_sessions")
public class SessionEntity {
    @Id @Column(name = "token_hash", length = 64) private String tokenHash;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;

    protected SessionEntity() {}
    SessionEntity(String tokenHash, UUID userId, Instant createdAt, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }
    String tokenHash() { return tokenHash; }
    UUID userId() { return userId; }
    Instant expiresAt() { return expiresAt; }
    boolean validAt(Instant now) { return revokedAt == null && now.isBefore(expiresAt); }
    void revoke(Instant now) { revokedAt = now; }
}
