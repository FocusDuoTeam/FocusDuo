package dev.focusduo.auth;

import dev.focusduo.api.Api;
import dev.focusduo.api.ApiErrors;
import dev.focusduo.api.ApiException;
import dev.focusduo.api.RequestIdFilter;
import dev.focusduo.config.SecurityConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class, excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, ApiErrors.class, RequestIdFilter.class})
class AuthSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuthService auth;
    private static final String TOKEN = "a".repeat(43);
    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(UUID.randomUUID(), "a".repeat(64),
            Instant.parse("2026-10-09T12:00:00Z"));

    @Test
    void registrationIsPublicAndNeedsNoCsrfOrIdempotencyHeaders() throws Exception {
        Api.User user = new Api.User(PRINCIPAL.userId(), "alice", "Alice");
        when(auth.register("alice", "Alice", "long-password"))
                .thenReturn(new Api.AuthSession(user, TOKEN, PRINCIPAL.expiresAt()));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"displayName\":\"Alice\",\"password\":\"long-password\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.username").value("alice"))
                .andExpect(jsonPath("$.accessToken").value(TOKEN))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-09T12:00:00Z"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void cookieAndQueryTokenCannotAuthenticate() throws Exception {
        mvc.perform(get("/api/v1/me").cookie(new Cookie("JSESSIONID", TOKEN)).param("accessToken", TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").isString())
                .andExpect(jsonPath("$.fieldErrors", aMapWithSize(0)))
                .andExpect(jsonPath("$.snapshot", nullValue()))
                .andExpect(header().exists("X-Request-Id"));
        verifyNoInteractions(auth);
    }

    @Test
    void bearerAuthenticatesAndLogoutRevokesOnlyCurrentPrincipal() throws Exception {
        when(auth.authenticate(TOKEN)).thenReturn(PRINCIPAL);
        when(auth.me(PRINCIPAL)).thenReturn(new Api.User(PRINCIPAL.userId(), "alice", "Alice"));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(PRINCIPAL.userId().toString()));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(auth).logout(PRINCIPAL);
    }

    @Test
    void invalidBearerAndUnauthenticatedWebSocketReturnProtocolErrors() throws Exception {
        when(auth.authenticate(TOKEN)).thenThrow(ApiException.unauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/ws/rooms/" + UUID.randomUUID()).header("Upgrade", "websocket"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void duplicateAuthorizationHeadersAreRejected() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TOKEN, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(auth);
    }

    @Test
    void malformedJsonAndValidationHaveExactErrorEnvelope() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.body").isString()).andExpect(jsonPath("$.snapshot", nullValue()));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"displayName\":\"Alice\",\"password\":null}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.password").isString());
        verify(auth, never()).register(any(), any(), any());
    }

    @Test
    void authenticatedUnknownRouteReturnsStandardNotFound() throws Exception {
        when(auth.authenticate(TOKEN)).thenReturn(PRINCIPAL);
        mvc.perform(get("/api/v1/does-not-exist").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unexpectedExceptionsNeverLeakSensitiveMessages() throws Exception {
        when(auth.authenticate(TOKEN)).thenReturn(PRINCIPAL);
        when(auth.me(PRINCIPAL)).thenThrow(new IllegalStateException("sensitive database details"));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected server error occurred"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void databaseAuthenticationFailureUsesStandardEnvelopeOutsideMvcAdvice() throws Exception {
        when(auth.authenticate(TOKEN)).thenThrow(new IllegalStateException("sensitive database details"));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected server error occurred"));
    }
}
