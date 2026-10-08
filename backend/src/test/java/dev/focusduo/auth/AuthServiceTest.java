package dev.focusduo.auth;

import dev.focusduo.api.Api;
import dev.focusduo.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final PasswordEncoder passwords = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T12:00:00Z"));
    private AuthService auth;

    @BeforeEach
    void setUp() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(users.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        auth = new AuthService(users, sessions, passwords, clock, Duration.ofHours(24), events, tx);
    }

    @Test
    void registrationNormalizesNamesAndStoresOnlyHashOf32RandomBytes() {
        String password = "  Long-PassWord-Ж";
        Api.AuthSession issued = auth.register("  ALICE_123 ", "  Alice  ", password);
        assertThat(issued.user().username()).isEqualTo("alice_123");
        assertThat(issued.user().displayName()).isEqualTo("Alice");
        assertThat(Base64.getUrlDecoder().decode(issued.accessToken())).hasSize(32);
        assertThat(issued.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
        ArgumentCaptor<UserEntity> user = ArgumentCaptor.forClass(UserEntity.class);
        verify(users).saveAndFlush(user.capture());
        assertThat(user.getValue().passwordHash()).startsWith("$argon2id$").isNotEqualTo(password);
        assertThat(passwords.matches(password, user.getValue().passwordHash())).isTrue();
        assertThat(passwords.matches(password.trim(), user.getValue().passwordHash())).isFalse();
        ArgumentCaptor<SessionEntity> session = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessions).save(session.capture());
        assertThat(session.getValue().tokenHash()).hasSize(64).isEqualTo(AuthService.hashToken(issued.accessToken()));
        assertThat(session.getValue().tokenHash()).doesNotContain(issued.accessToken());
        verify(tx).commit(any());
    }

    @Test
    void all128PasswordCharactersAreSignificantEvenBeyond72Bytes() {
        String password = "Ж".repeat(127) + "a";
        Api.AuthSession issued = auth.register("unicode", "Unicode", password);
        ArgumentCaptor<UserEntity> user = ArgumentCaptor.forClass(UserEntity.class);
        verify(users).saveAndFlush(user.capture());
        when(users.findByUsername("unicode")).thenReturn(Optional.of(user.getValue()));
        assertThat(auth.login(" UNICODE ", password).user().id()).isEqualTo(issued.user().id());
        assertError(() -> auth.login("unicode", "Ж".repeat(127) + "b"), "UNAUTHORIZED");
    }

    @Test
    void supplementaryUnicodeLimitsCountCodePointsRatherThanUtf16Units() {
        String password = "🔐".repeat(127) + "a";
        String displayName = "😀".repeat(40);
        Api.AuthSession session = auth.register("supplementary", displayName, password);
        assertThat(session.user().displayName()).isEqualTo(displayName);
        ArgumentCaptor<UserEntity> user = ArgumentCaptor.forClass(UserEntity.class);
        verify(users).saveAndFlush(user.capture());
        when(users.findByUsername("supplementary")).thenReturn(Optional.of(user.getValue()));
        assertThat(auth.login("supplementary", password).user()).isEqualTo(session.user());
        assertError(() -> auth.login("supplementary", "🔐".repeat(127) + "b"), "UNAUTHORIZED");
        assertError(() -> auth.register("too_long", displayName, "🔐".repeat(129)), "VALIDATION_ERROR");
        assertError(() -> auth.register("too_long", "😀".repeat(41), password), "VALIDATION_ERROR");
        assertError(() -> auth.register("too_short", "🙂🙂", "🔐".repeat(9)), "VALIDATION_ERROR");
    }

    @Test
    void duplicateUsernameIncludingConcurrentInsertReturnsConflictAfterRollback() {
        when(users.existsByUsername("alice")).thenReturn(false, true);
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique constraint"));
        assertError(() -> auth.register("alice", "Alice", "long-password"), "USERNAME_TAKEN");
        verify(tx).rollback(any());
        verify(sessions, never()).save(any());
    }

    @Test
    void rejectsInvalidRegistrationFieldsWithoutIssuingToken() {
        assertError(() -> auth.register("a@x", "Alice", "long-password"), "VALIDATION_ERROR");
        assertError(() -> auth.register("alice", " A ", "long-password"), "VALIDATION_ERROR");
        assertError(() -> auth.register("alice", "Alice", "short"), "VALIDATION_ERROR");
        assertError(() -> auth.register("alice", "Alice", "x".repeat(129)), "VALIDATION_ERROR");
        assertError(() -> auth.register(null, "Alice", "long-password"), "VALIDATION_ERROR");
        verifyNoInteractions(sessions);
    }

    @Test
    void expiryBoundaryAndRevocationUseInjectedClock() {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = AuthService.hashToken(token);
        Instant expiry = clock.instant().plusSeconds(30);
        SessionEntity session = new SessionEntity(hash, UUID.randomUUID(), clock.instant(), expiry);
        when(sessions.findById(hash)).thenReturn(Optional.of(session));
        AuthPrincipal principal = auth.authenticate(token);
        assertThat(principal.expiresAt()).isEqualTo(expiry);
        clock.now = expiry.minusMillis(1);
        assertThat(auth.authenticate(token).userId()).isEqualTo(session.userId());
        clock.now = expiry;
        assertError(() -> auth.authenticate(token), "UNAUTHORIZED");
        clock.now = expiry.minusSeconds(10);
        auth.logout(principal);
        verify(events).publishEvent(new TokenRevoked(hash));
        assertError(() -> auth.authenticate(token), "UNAUTHORIZED");
    }

    @Test
    void malformedTokensNeverReachDatabase() {
        assertError(() -> auth.authenticate(null), "UNAUTHORIZED");
        assertError(() -> auth.authenticate("token-in-query-is-not-accepted"), "UNAUTHORIZED");
        assertError(() -> auth.authenticate("a".repeat(44)), "UNAUTHORIZED");
        verifyNoInteractions(sessions);
    }

    @Test
    void websocketValidityUsesDatabaseAndCurrentClock() {
        when(sessions.existsByTokenHashAndRevokedAtIsNullAndExpiresAtAfter("hash", clock.instant())).thenReturn(true);
        assertThat(auth.isValid("hash")).isTrue();
        clock.now = clock.now.plusSeconds(1);
        assertThat(auth.isValid("hash")).isFalse();
    }

    @Test
    void missingUsernameAndIncorrectPasswordShareUnauthorizedCode() {
        when(users.findByUsername("missing")).thenReturn(Optional.empty());
        assertError(() -> auth.login("missing", "wrong-password"), "UNAUTHORIZED");
        UserEntity user = new UserEntity(UUID.randomUUID(), "alice", "Alice", passwords.encode("right-password"));
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        assertError(() -> auth.login("alice", "wrong-password"), "UNAUTHORIZED");
        verify(sessions, never()).save(any());
    }

    private static void assertError(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
