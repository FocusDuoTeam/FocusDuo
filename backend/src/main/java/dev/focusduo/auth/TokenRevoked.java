package dev.focusduo.auth;

/** Published inside the logout transaction; WebSocket listeners run AFTER_COMMIT. */
public record TokenRevoked(String tokenHash) {}
