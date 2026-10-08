package dev.focusduo.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.focusduo.auth.AuthPrincipal;
import dev.focusduo.rooms.RoomService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RoomController {
    private final RoomService rooms;
    public RoomController(RoomService rooms) { this.rooms = rooms; }

    @GetMapping("/rooms/current")
    Api.CurrentRoom current(@AuthenticationPrincipal AuthPrincipal user) {
        return new Api.CurrentRoom(rooms.current(user.userId()));
    }

    @GetMapping("/rooms/{roomId}")
    Api.RoomSnapshot room(@AuthenticationPrincipal AuthPrincipal user, @PathVariable UUID roomId) {
        return rooms.get(user.userId(), roomId);
    }

    @GetMapping("/history")
    Api.History history(@AuthenticationPrincipal AuthPrincipal user) {
        return new Api.History(rooms.history(user.userId()));
    }

    @GetMapping("/history/{roundId}")
    Api.HistoryItem historyItem(@AuthenticationPrincipal AuthPrincipal user, @PathVariable UUID roundId) {
        return rooms.historyItem(user.userId(), roundId);
    }

    @PostMapping({"/rooms", "/rooms/join"})
    Api.RoomSnapshot enter(@AuthenticationPrincipal AuthPrincipal user,
                           @RequestHeader("Idempotency-Key") UUID key,
                           @RequestBody JsonNode body, HttpServletRequest request) {
        return rooms.command(user.userId(), key, request.getMethod(), request.getRequestURI(), null, body);
    }

    @PostMapping({"/rooms/{roomId}/tasks", "/rooms/{roomId}/rounds"})
    Api.RoomSnapshot create(@AuthenticationPrincipal AuthPrincipal user,
                            @PathVariable UUID roomId,
                            @RequestHeader("Idempotency-Key") UUID key,
                            @RequestHeader("X-Room-Revision") long revision,
                            @RequestBody JsonNode body, HttpServletRequest request) {
        return rooms.command(user.userId(), key, request.getMethod(), request.getRequestURI(), revision, body);
    }

    @PatchMapping({"/rooms/{roomId}/settings", "/rooms/{roomId}/me", "/rooms/{roomId}/tasks/{taskId}"})
    Api.RoomSnapshot update(@AuthenticationPrincipal AuthPrincipal user,
                            @PathVariable UUID roomId,
                            @RequestHeader("Idempotency-Key") UUID key,
                            @RequestHeader("X-Room-Revision") long revision,
                            @RequestBody JsonNode body, HttpServletRequest request) {
        return rooms.command(user.userId(), key, request.getMethod(), request.getRequestURI(), revision, body);
    }

    @PostMapping({"/rooms/{roomId}/close", "/rooms/{roomId}/rounds/{roundId}/pause",
                  "/rooms/{roomId}/rounds/{roundId}/resume", "/rooms/{roomId}/rounds/{roundId}/finish"})
    Api.RoomSnapshot action(@AuthenticationPrincipal AuthPrincipal user, @PathVariable UUID roomId,
                            @RequestHeader("Idempotency-Key") UUID key,
                            @RequestHeader("X-Room-Revision") long revision,
                            @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        return rooms.command(user.userId(), key, request.getMethod(), request.getRequestURI(), revision, body);
    }

    @DeleteMapping("/rooms/{roomId}/tasks/{taskId}")
    Api.RoomSnapshot delete(@AuthenticationPrincipal AuthPrincipal user, @PathVariable UUID roomId,
                            @RequestHeader("Idempotency-Key") UUID key,
                            @RequestHeader("X-Room-Revision") long revision,
                            @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        return rooms.command(user.userId(), key, request.getMethod(), request.getRequestURI(), revision, body);
    }
}
