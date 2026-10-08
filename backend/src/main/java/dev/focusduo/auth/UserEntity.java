package dev.focusduo.auth;

import dev.focusduo.api.Api;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "app_users")
public class UserEntity {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 32) private String username;
    @Column(name = "display_name", nullable = false, length = 40) private String displayName;
    @Column(name = "password_hash", nullable = false, length = 255) private String passwordHash;

    protected UserEntity() {}
    UserEntity(UUID id, String username, String displayName, String passwordHash) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }
    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    String passwordHash() { return passwordHash; }
    Api.User dto() { return new Api.User(id, username, displayName); }
}
