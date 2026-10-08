package dev.focusduo.rooms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.focusduo.api.Api;
import dev.focusduo.api.ApiException;
import dev.focusduo.history.HistoryEntity;
import dev.focusduo.history.HistoryParticipantEntity;
import dev.focusduo.rounds.RoundEntity;
import dev.focusduo.tasks.TaskEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One database transaction owns each room change, its history, revision, and replay result.
 * Expected API errors deliberately commit an expiration reconciled before the failing command.
 * Consequently every command validates all of its input and rules before starting its own mutation.
 */
@Service
@Transactional(noRollbackFor = ApiException.class)
public class RoomService {
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final SecureRandom random = new SecureRandom();

    public RoomService(EntityManager em, ObjectMapper mapper, Clock clock, ApplicationEventPublisher events) {
        this.em = em;
        this.mapper = mapper;
        this.clock = clock;
        this.events = events;
    }

    public Api.RoomSnapshot command(UUID userId, UUID key, String method, String path, Long revision, JsonNode body) {
        if (key == null) throw ApiException.validation("Idempotency-Key", "A UUID idempotency key is required");
        // The row lock serializes even concurrent first uses of the same key across application instances.
        // Lock only the issuing user, then the room. Closing never locks both users and cannot invert this order.
        lockUser(userId);
        IdempotencyResultEntity saved = em.createQuery(
                        "select i from IdempotencyResultEntity i where i.userId = :user and i.requestKey = :key",
                        IdempotencyResultEntity.class)
                .setParameter("user", userId).setParameter("key", key).getResultStream().findFirst().orElse(null);
        if (saved != null) {
            if (!saved.method.equals(method) || !saved.path.equals(path)
                    || !Objects.equals(saved.originalRevision, revision) || !Objects.equals(saved.requestBody, body)) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "This key was already used for a different request");
            }
            return mapper.convertValue(saved.responseSnapshot, Api.RoomSnapshot.class);
        }

        String route = path.startsWith("/api/v1/") ? path.substring("/api/v1".length()) : path;
        Instant now = now();
        RoomEntity room;
        boolean changed;
        if ("POST".equals(method) && "/rooms".equals(route)) {
            room = create(userId, body, now);
            changed = true;
        } else if ("POST".equals(method) && "/rooms/join".equals(route)) {
            room = join(userId, body, now);
            now = now();
            // join publishes itself only when it adds a participant; same-room rejoin is a read.
            changed = false;
        } else {
            String[] parts = route.split("/");
            if (parts.length < 4 || !"rooms".equals(parts[1])) throw ApiException.notFound();
            room = lockedRoom(uuid(parts[2], "roomId"));
            now = now();
            MembershipEntity member = member(room.id, userId);
            reconcile(room, now);
            if (revision == null || revision < 1) {
                throw ApiException.validation("X-Room-Revision", "A positive integer room revision is required");
            }
            if (room.revision != revision) throw ApiException.revision(snapshot(room, now));
            requireOpen(room);
            mutate(room, member, userId, method, parts, body, now);
            room.revision++;
            changed = true;
        }
        Api.RoomSnapshot result = snapshot(room, now);
        IdempotencyResultEntity record = new IdempotencyResultEntity();
        record.id = UUID.randomUUID();
        record.userId = userId;
        record.requestKey = key;
        record.method = method;
        record.path = path;
        record.originalRevision = revision;
        record.requestBody = body == null ? null : body.deepCopy();
        record.responseSnapshot = mapper.valueToTree(result);
        record.createdAt = now;
        em.persist(record);
        if (changed) events.publishEvent(new RoomChanged(result));
        return result;
    }

    public Api.RoomSnapshot get(UUID userId, UUID roomId) {
        RoomEntity room = lockedRoom(roomId);
        member(room.id, userId);
        Instant now = now();
        reconcile(room, now);
        return snapshot(room, now);
    }

    public Api.RoomSnapshot current(UUID userId) {
        UUID id = activeRoomId(userId);
        if (id == null) return null;
        RoomEntity room = lockedRoom(id);
        // A close may have committed between the membership lookup and the room lock.
        if (!"OPEN".equals(room.status)) return null;
        Instant now = now();
        reconcile(room, now);
        return snapshot(room, now);
    }

    public List<Api.HistoryItem> history(UUID userId) {
        current(userId); // Expiry uses precisely the same mechanism as room reads and commands.
        return em.createQuery("""
                        select h from HistoryEntity h where exists
                          (select p.id from HistoryParticipantEntity p where p.roundId = h.roundId and p.userId = :user)
                        order by h.endedAt desc, h.roundId desc
                        """, HistoryEntity.class)
                .setParameter("user", userId).setMaxResults(50).getResultList().stream()
                .map(h -> mapper.convertValue(h.snapshot(), Api.HistoryItem.class)).toList();
    }

    public Api.HistoryItem historyItem(UUID userId, UUID roundId) {
        HistoryEntity history = em.find(HistoryEntity.class, roundId);
        if (history == null) {
            RoundEntity round = em.find(RoundEntity.class, roundId);
            if (round == null) throw ApiException.notFound();
            RoomEntity room = lockedRoom(round.roomId());
            member(room.id, userId);
            // Refresh because a competing room transaction could have changed the previously read round.
            em.refresh(round);
            reconcile(room, now());
            history = em.find(HistoryEntity.class, roundId);
            if (history == null) throw ApiException.notFound();
        }
        Long count = em.createQuery("""
                        select count(p) from HistoryParticipantEntity p where p.roundId = :round and p.userId = :user
                        """, Long.class)
                .setParameter("round", roundId).setParameter("user", userId).getSingleResult();
        if (count == 0) throw ApiException.forbidden();
        return mapper.convertValue(history.snapshot(), Api.HistoryItem.class);
    }

    /** Persisted deadlines make a fresh process recover overdue work without any in-memory registration. */
    public void refreshDueRooms() {
        Instant now = now();
        List<UUID> ids = em.createQuery("""
                        select r.roomId from RoundEntity r where r.status = 'RUNNING' and r.endsAt <= :now
                        order by r.roomId
                        """, UUID.class)
                .setParameter("now", now).setMaxResults(200).getResultList();
        for (UUID id : ids) reconcile(lockedRoom(id), now);
    }

    private RoomEntity create(UUID userId, JsonNode body, Instant now) {
        object(body, "focusDurationSeconds", "breakDurationSeconds");
        int focus = duration(body, "focusDurationSeconds", 1500, 300, 10800);
        int rest = duration(body, "breakDurationSeconds", 300, 60, 3600);
        if (activeRoomId(userId) != null) throw ApiException.conflict("ALREADY_IN_ROOM", "You already have an open room");
        UUID id = UUID.randomUUID();
        // ON CONFLICT handles even the rare simultaneous generation of the same invite code without
        // poisoning a transaction. The schema remains the final arbiter of code uniqueness.
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = code();
            int inserted = em.createNativeQuery("""
                            insert into rooms(id, code, owner_id, status, revision, focus_duration_seconds,
                                              break_duration_seconds, created_at)
                            values (:id, :code, :owner, 'OPEN', 1, :focus, :rest, :now)
                            on conflict (code) do nothing
                            """)
                    .setParameter("id", id).setParameter("code", code).setParameter("owner", userId)
                    .setParameter("focus", focus).setParameter("rest", rest).setParameter("now", now).executeUpdate();
            if (inserted == 1) {
                RoomEntity room = em.find(RoomEntity.class, id);
                em.persist(new MembershipEntity(room.id, userId, 1, displayName(userId), now));
                return room;
            }
        }
        throw new IllegalStateException("Unable to allocate a room code");
    }

    private RoomEntity join(UUID userId, JsonNode body, Instant now) {
        object(body, "code");
        String code = text(body, "code", true).trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty() || code.length() > 100) throw ApiException.validation("code", "A room code is required");
        RoomEntity room = em.createQuery("select r from RoomEntity r where r.code = :code", RoomEntity.class)
                .setParameter("code", code).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst().orElseThrow(ApiException::notFound);
        now = now();
        reconcile(room, now);
        requireOpen(room);
        List<MembershipEntity> members = members(room.id);
        if (members.stream().anyMatch(m -> m.userId.equals(userId))) return room;
        if (activeRoomId(userId) != null) throw ApiException.conflict("ALREADY_IN_ROOM", "You already have an open room");
        if (members.size() >= 2) throw ApiException.conflict("ROOM_FULL", "The room already has two participants");
        em.persist(new MembershipEntity(room.id, userId, 2, displayName(userId), now));
        room.revision++;
        events.publishEvent(new RoomChanged(snapshot(room, now)));
        return room;
    }

    private void mutate(RoomEntity room, MembershipEntity member, UUID userId, String method,
                        String[] parts, JsonNode body, Instant now) {
        String action = parts[3];
        if (parts.length == 4 && "PATCH".equals(method) && "settings".equals(action)) {
            owner(room, userId);
            object(body, "focusDurationSeconds", "breakDurationSeconds");
            int focus = duration(body, "focusDurationSeconds", null, 300, 10800);
            int rest = duration(body, "breakDurationSeconds", null, 60, 3600);
            RoundEntity current = round(room);
            if (current != null && current.active()) throw invalidState("Settings cannot change during an active round");
            room.focusDurationSeconds = focus;
            room.breakDurationSeconds = rest;
        } else if (parts.length == 4 && "PATCH".equals(method) && "me".equals(action)) {
            object(body, "goal");
            String goal = text(body, "goal", true);
            if (length(goal) > 240) throw ApiException.validation("goal", "Goal must contain at most 240 characters");
            member.goal = goal;
        } else if (parts.length == 4 && "POST".equals(method) && "tasks".equals(action)) {
            object(body, "title");
            String title = title(body);
            Long count = em.createQuery("select count(t) from TaskEntity t where t.roomId = :room and t.userId = :user", Long.class)
                    .setParameter("room", room.id).setParameter("user", userId).getSingleResult();
            if (count >= 20) throw ApiException.validation("tasks", "A participant can have at most 20 tasks");
            em.persist(new TaskEntity(room.id, userId, title, now));
        } else if (parts.length == 5 && "tasks".equals(action) && Set.of("PATCH", "DELETE").contains(method)) {
            TaskEntity task = em.find(TaskEntity.class, uuid(parts[4], "taskId"));
            if (task == null || !task.roomId().equals(room.id)) throw ApiException.notFound();
            if (!task.userId().equals(userId)) throw ApiException.forbidden();
            if ("DELETE".equals(method)) {
                noBody(body);
                em.remove(task);
            } else {
                object(body, "title", "completed");
                if (!body.has("title") && !body.has("completed")) {
                    throw ApiException.validation("body", "At least one of title or completed is required");
                }
                String title = body.has("title") ? title(body) : null;
                Boolean completed = null;
                if (body.has("completed")) {
                    if (!body.get("completed").isBoolean()) throw ApiException.validation("completed", "Must be a boolean");
                    completed = body.get("completed").booleanValue();
                }
                task.edit(title, completed);
            }
        } else if (parts.length == 4 && "POST".equals(method) && "rounds".equals(action)) {
            owner(room, userId);
            object(body, "kind");
            String kind = text(body, "kind", true);
            if (!Set.of("FOCUS", "BREAK").contains(kind)) throw ApiException.validation("kind", "Must be FOCUS or BREAK");
            RoundEntity current = round(room);
            if (current != null && current.active()) throw invalidState("A round is already running or paused");
            if (members(room.id).size() != 2) throw invalidState("Two participants are required to start a round");
            if ("BREAK".equals(kind) && (current == null || !"FOCUS".equals(current.kind()) || !"COMPLETED".equals(current.status()))) {
                throw invalidState("A break requires the last round to be a completed focus round");
            }
            RoundEntity next = new RoundEntity(room.id, kind,
                    "FOCUS".equals(kind) ? room.focusDurationSeconds : room.breakDurationSeconds, now);
            em.persist(next);
            em.flush(); // The current-round foreign key must point to an already inserted round.
            room.currentRoundId = next.id();
        } else if (parts.length == 6 && "POST".equals(method) && "rounds".equals(action)
                && Set.of("pause", "resume", "finish").contains(parts[5])) {
            owner(room, userId);
            noBody(body);
            UUID roundId = uuid(parts[4], "roundId");
            if (!roundId.equals(room.currentRoundId)) throw ApiException.conflict("ROUND_NOT_CURRENT", "The round is not the current round");
            RoundEntity current = round(room);
            switch (parts[5]) {
                case "pause" -> {
                    if (!"RUNNING".equals(current.status())) throw invalidState("Only a running round can be paused");
                    current.pause(now);
                }
                case "resume" -> {
                    if (!"PAUSED".equals(current.status())) throw invalidState("Only a paused round can be resumed");
                    current.resume(now);
                }
                case "finish" -> {
                    if (!current.active()) throw invalidState("Only an active round can be finished");
                    current.finish(now);
                    writeHistory(room, current, now);
                }
                default -> throw new IllegalStateException("Unrecognized round action");
            }
        } else if (parts.length == 4 && "POST".equals(method) && "close".equals(action)) {
            noBody(body);
            RoundEntity current = round(room);
            if (current != null && current.active()) {
                current.cancel(now);
                writeHistory(room, current, now);
            }
            room.status = "CLOSED";
            for (MembershipEntity participant : members(room.id)) participant.active = false;
        } else {
            throw ApiException.notFound();
        }
    }

    private void reconcile(RoomEntity room, Instant now) {
        RoundEntity round = round(room);
        if (round == null || !round.due(now)) return;
        round.completeElapsed();
        writeHistory(room, round, now);
        room.revision++;
        events.publishEvent(new RoomChanged(snapshot(room, now)));
    }

    private void writeHistory(RoomEntity room, RoundEntity round, Instant now) {
        // Called only under the room's pessimistic write lock; round_id PK adds a final DB guarantee.
        Api.Round state = round.snapshot(now);
        List<Api.HistoryParticipant> participants = participants(room.id).stream().map(p ->
                new Api.HistoryParticipant(p.userId(), p.displayName(), p.goal(), p.tasks(), p.tasks().size(),
                        (int) p.tasks().stream().filter(Api.Task::completed).count())).toList();
        Api.HistoryItem history = new Api.HistoryItem(round.id(), room.id, state.kind(), state.status(),
                state.completionReason(), state.durationSeconds(), state.startedAt(), state.endedAt(),
                state.activeElapsedMs(), participants);
        em.flush();
        em.persist(new HistoryEntity(round.id(), room.id, state.endedAt(), mapper.valueToTree(history)));
        em.flush();
        for (Api.HistoryParticipant participant : participants) {
            em.persist(new HistoryParticipantEntity(round.id(), participant.userId()));
        }
    }

    private Api.RoomSnapshot snapshot(RoomEntity room, Instant now) {
        RoundEntity round = round(room);
        return new Api.RoomSnapshot(room.id, room.code, room.ownerId, room.status, room.revision, now,
                room.focusDurationSeconds, room.breakDurationSeconds, participants(room.id),
                round == null ? null : round.snapshot(now));
    }

    private List<Api.Participant> participants(UUID roomId) {
        return members(roomId).stream().map(m -> new Api.Participant(m.userId, m.displayName, m.goal,
                em.createQuery("""
                                select t from TaskEntity t where t.roomId = :room and t.userId = :user
                                order by t.createdAt, t.id
                                """, TaskEntity.class)
                        .setParameter("room", roomId).setParameter("user", m.userId).getResultList().stream()
                        .map(TaskEntity::snapshot).toList())).toList();
    }
    private List<MembershipEntity> members(UUID roomId) {
        return em.createQuery("select m from MembershipEntity m where m.roomId = :room order by m.slot", MembershipEntity.class)
                .setParameter("room", roomId).getResultList();
    }
    private MembershipEntity member(UUID roomId, UUID userId) {
        return em.createQuery("select m from MembershipEntity m where m.roomId = :room and m.userId = :user", MembershipEntity.class)
                .setParameter("room", roomId).setParameter("user", userId)
                .getResultStream().findFirst().orElseThrow(ApiException::forbidden);
    }
    private UUID activeRoomId(UUID userId) {
        return em.createQuery("select m.roomId from MembershipEntity m where m.userId = :user and m.active = true", UUID.class)
                .setParameter("user", userId).getResultStream().findFirst().orElse(null);
    }
    private RoomEntity lockedRoom(UUID id) {
        RoomEntity room = em.find(RoomEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (room == null) throw ApiException.notFound();
        return room;
    }
    private RoundEntity round(RoomEntity room) {
        return room.currentRoundId == null ? null : em.find(RoundEntity.class, room.currentRoundId);
    }
    private void lockUser(UUID userId) {
        // NO KEY UPDATE still serializes commands from this user, but permits foreign-key KEY SHARE
        // locks when another participant's transaction appends history under the room lock.
        if (em.createNativeQuery("select id from app_users where id = :id for no key update")
                .setParameter("id", userId).getResultList().isEmpty()) throw ApiException.unauthorized();
    }
    private String displayName(UUID userId) {
        return (String) em.createNativeQuery("select display_name from app_users where id = :id")
                .setParameter("id", userId).getSingleResult();
    }
    private static void requireOpen(RoomEntity room) {
        if (!"OPEN".equals(room.status)) throw ApiException.conflict("ROOM_CLOSED", "The room is closed");
    }
    private static void owner(RoomEntity room, UUID userId) {
        if (!room.ownerId.equals(userId)) throw ApiException.forbidden();
    }
    private static ApiException invalidState(String message) { return ApiException.conflict("INVALID_STATE", message); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
    private String code() {
        StringBuilder code = new StringBuilder("FD-");
        for (int i = 0; i < 6; i++) code.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        return code.toString();
    }
    private static UUID uuid(String value, String field) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException ex) {
            throw ApiException.validation(field, "Must be a UUID");
        }
    }
    private static void object(JsonNode body, String... fields) {
        if (body == null || !body.isObject()) throw ApiException.validation("body", "A JSON object is required");
        Set<String> accepted = Set.of(fields);
        body.fieldNames().forEachRemaining(field -> {
            if (!accepted.contains(field)) throw ApiException.validation(field, "Unknown field");
        });
    }
    private static String text(JsonNode body, String field, boolean required) {
        JsonNode value = body.get(field);
        if (value == null && !required) return null;
        if (value == null || !value.isTextual()) throw ApiException.validation(field, "Must be a string");
        return value.textValue();
    }
    private static int duration(JsonNode body, String field, Integer fallback, int min, int max) {
        JsonNode value = body.get(field);
        if (value == null && fallback != null) return fallback;
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw ApiException.validation(field, "Must be an integer number of seconds");
        }
        int seconds = value.intValue();
        if (seconds < min || seconds > max || seconds % 60 != 0) {
            throw ApiException.validation(field, "Must be between " + min + " and " + max + " seconds, in multiples of 60");
        }
        return seconds;
    }
    private static String title(JsonNode body) {
        String title = text(body, "title", true).trim();
        if (length(title) < 1 || length(title) > 160) throw ApiException.validation("title", "Title must contain 1 to 160 characters after trim");
        return title;
    }
    private static int length(String value) { return value.codePointCount(0, value.length()); }
    private static void noBody(JsonNode body) {
        if (body != null) throw ApiException.validation("body", "This command does not accept a request body");
    }
}
