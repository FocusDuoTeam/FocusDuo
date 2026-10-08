package dev.focusduo.auth;

import dev.focusduo.api.Api;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    public record Register(@NotNull String username, @NotNull String displayName,
                           @NotNull String password) {
        @Override public String toString() { return "Register[credentials redacted]"; }
    }
    public record Login(@NotNull String username, @NotNull String password) {
        @Override public String toString() { return "Login[credentials redacted]"; }
    }

    @PostMapping("/api/v1/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Api.AuthSession register(@Valid @RequestBody Register request) {
        return auth.register(request.username(), request.displayName(), request.password());
    }

    @PostMapping("/api/v1/auth/login")
    public Api.AuthSession login(@Valid @RequestBody Login request) {
        return auth.login(request.username(), request.password());
    }

    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AuthPrincipal principal) { auth.logout(principal); }

    @GetMapping("/api/v1/me")
    public Api.User me(@AuthenticationPrincipal AuthPrincipal principal) { return auth.me(principal); }
}
