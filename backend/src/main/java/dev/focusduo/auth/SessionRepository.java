package dev.focusduo.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {
    boolean existsByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(String tokenHash, Instant now);
}
