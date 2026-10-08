package dev.focusduo.auth;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

public record AuthPrincipal(UUID userId, String tokenHash, Instant expiresAt) implements Principal {
    @Override public String getName() { return userId.toString(); }
}
