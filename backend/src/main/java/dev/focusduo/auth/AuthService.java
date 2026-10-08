package dev.focusduo.auth;

import dev.focusduo.api.Api;
import dev.focusduo.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final Duration sessionTtl;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, SessionRepository sessions, PasswordEncoder passwords,
                       Clock clock, @Value("${focusduo.session-ttl:${FOCUSDUO_SESSION_TTL:PT24H}}") Duration sessionTtl,
                       ApplicationEventPublisher events, PlatformTransactionManager transactionManager) {
        if (sessionTtl.isNegative() || sessionTtl.isZero()) throw new IllegalArgumentException("Session TTL must be positive");
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.clock = clock;
        this.sessionTtl = sessionTtl;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.dummyPasswordHash = passwords.encode("FocusDuo-dummy-password-not-an-account");
    }

    public Api.AuthSession register(String username, String displayName, String password) {
        String normalized = normalizeUsername(username);
        String display = normalizeDisplayName(displayName);
        validatePassword(password);
        String hash = passwords.encode(password);
        try {
            return transactions.execute(status -> {
                if (users.existsByUsername(normalized)) throw ApiException.conflict("USERNAME_TAKEN", "Username is already registered");
                UserEntity user = users.saveAndFlush(new UserEntity(UUID.randomUUID(), normalized, display, hash));
                return issue(user);
            });
        } catch (DataIntegrityViolationException exception) {
            // Resolve a concurrent unique-username insertion only after the failed transaction has rolled back.
            if (users.existsByUsername(normalized)) throw ApiException.conflict("USERNAME_TAKEN", "Username is already registered");
            throw exception;
        }
    }

    @Transactional
    public Api.AuthSession login(String username, String password) {
        String normalized = normalizeUsername(username);
        validatePassword(password);
        UserEntity user = users.findByUsername(normalized).orElse(null);
        boolean matches = passwords.matches(password, user == null ? dummyPasswordHash : user.passwordHash());
        if (user == null || !matches) throw ApiException.unauthorized();
        return issue(user);
    }

    private Api.AuthSession issue(UserEntity user) {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        Instant now = clock.instant();
        Instant expiry = now.plus(sessionTtl);
        sessions.save(new SessionEntity(hashToken(token), user.getId(), now, expiry));
        return new Api.AuthSession(user.dto(), token, expiry);
    }

    /** Accepts the raw access token, with no Authorization scheme prefix. */
    @Transactional(readOnly = true)
    public AuthPrincipal authenticate(String bearerToken) {
        if (bearerToken == null || !bearerToken.matches("[A-Za-z0-9_-]{43}")) throw ApiException.unauthorized();
        SessionEntity session = sessions.findById(hashToken(bearerToken)).orElseThrow(ApiException::unauthorized);
        if (!session.validAt(clock.instant())) throw ApiException.unauthorized();
        return new AuthPrincipal(session.userId(), session.tokenHash(), session.expiresAt());
    }

    @Transactional(readOnly = true)
    public boolean isValid(String tokenHash) {
        return sessions.existsByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(tokenHash, clock.instant());
    }

    @Transactional
    public void logout(AuthPrincipal principal) {
        SessionEntity session = sessions.findById(principal.tokenHash()).orElseThrow(ApiException::unauthorized);
        session.revoke(clock.instant());
        events.publishEvent(new TokenRevoked(principal.tokenHash()));
    }

    @Transactional(readOnly = true)
    public Api.User me(AuthPrincipal principal) {
        return users.findById(principal.userId()).orElseThrow(ApiException::unauthorized).dto();
    }

    static String normalizeUsername(String username) {
        if (username == null) throw ApiException.validation("username", "Username is required");
        String result = username.trim().toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z0-9_]{3,32}")) throw ApiException.validation("username", "Use 3–32 letters a–z, digits, or underscore");
        return result;
    }

    static String normalizeDisplayName(String displayName) {
        if (displayName == null) throw ApiException.validation("displayName", "Display name is required");
        String result = displayName.trim();
        if (characterCount(result) < 2 || characterCount(result) > 40)
            throw ApiException.validation("displayName", "Use 2–40 characters after trimming");
        return result;
    }

    static void validatePassword(String password) {
        if (password == null || characterCount(password) < 10 || characterCount(password) > 128)
            throw ApiException.validation("password", "Use 10–128 characters");
    }

    private static int characterCount(String value) { return value.codePointCount(0, value.length()); }

    static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }
}
