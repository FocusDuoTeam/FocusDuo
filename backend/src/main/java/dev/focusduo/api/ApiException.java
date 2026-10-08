package dev.focusduo.api;

import java.util.Map;

/** An expected, safe-to-expose protocol error. Never contains credentials. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, String> fieldErrors;
    private final Api.RoomSnapshot snapshot;

    public ApiException(int status, String code, String message) {
        this(status, code, message, Map.of(), null);
    }

    public ApiException(int status, String code, String message, Map<String, String> fieldErrors,
                        Api.RoomSnapshot snapshot) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
        this.snapshot = snapshot;
    }

    public int status() { return status; }
    public String code() { return code; }
    public Map<String, String> fieldErrors() { return fieldErrors; }
    public Api.RoomSnapshot snapshot() { return snapshot; }

    public static ApiException validation(String field, String message) {
        return new ApiException(400, "VALIDATION_ERROR", "Request validation failed", Map.of(field, message), null);
    }

    public static ApiException conflict(String code, String message) { return new ApiException(409, code, message); }
    public static ApiException forbidden() { return new ApiException(403, "FORBIDDEN", "Access is not permitted"); }
    public static ApiException notFound() { return new ApiException(404, "NOT_FOUND", "Resource was not found"); }
    public static ApiException unauthorized() { return new ApiException(401, "UNAUTHORIZED", "A valid bearer token is required"); }
    public static ApiException revision(Api.RoomSnapshot snapshot) {
        return new ApiException(409, "REVISION_CONFLICT", "Room revision has changed", Map.of(), snapshot);
    }
}
