package dev.focusduo.data

import dev.focusduo.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.Collections
import java.util.Locale
import java.util.UUID

/** Explicit, in-memory demo. This class has no network connection or fallback behavior. */
class FakeRepository(
    initialDisplayName: String = "Ты",
    private val clock: Clock = Clock.systemUTC(),
) : FocusDuoRepository {
    private var localUser = User(newId(), "demo_user", validateDisplayName(initialDisplayName))
    private var friendUser = User(newId(), "demo_friend", "Друг")
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(RepositoryState(localUser))
    override val state: StateFlow<RepositoryState> = mutableState.asStateFlow()

    override suspend fun createRoom(focusDurationSeconds: Int, breakDurationSeconds: Int): RoomSnapshot =
        mutex.withLock {
            ensureNoOpenRoom()
            validateDurations(focusDurationSeconds, breakDurationSeconds)
            val user = state.value.currentUser
            publish(
                RoomSnapshot(
                    roomId = newId(), code = newCode(), ownerId = user.id, status = RoomStatus.OPEN,
                    revision = 1, serverNow = clock.instant(),
                    focusDurationSeconds = focusDurationSeconds, breakDurationSeconds = breakDurationSeconds,
                    participants = listOf(Participant(user.id, user.displayName, "", emptyList())).frozen(),
                    round = null,
                ),
            )
        }

    override suspend fun joinRoom(code: String): RoomSnapshot = mutex.withLock {
        val normalized = code.trim().uppercase(Locale.ROOT)
        if (normalized.isEmpty()) invalid("code", "Введите код комнаты.")
        val current = state.value.room
        if (current?.status == RoomStatus.OPEN && current.code == normalized) return@withLock current
        ensureNoOpenRoom()
        // A known code is the only simulated remote room. Real codes are opaque to the UI.
        if (normalized != SAMPLE_ROOM_CODE) {
            fail("NOT_FOUND", "Комната не найдена. В деморежиме используйте код $SAMPLE_ROOM_CODE.")
        }
        val user = state.value.currentUser
        publish(
            RoomSnapshot(
                roomId = newId(), code = SAMPLE_ROOM_CODE, ownerId = friendUser.id, status = RoomStatus.OPEN,
                revision = 2, serverNow = clock.instant(), focusDurationSeconds = 1500, breakDurationSeconds = 300,
                participants = listOf(
                    friendParticipant(),
                    Participant(
                        user.id, user.displayName, "закончить API",
                        listOf(
                            Task(newId(), "Создать комнату", true),
                            Task(newId(), "Добавить подключение", false),
                            Task(newId(), "Проверить ошибки", false),
                        ).frozen(),
                    ),
                ).frozen(),
                round = null,
            ),
        )
    }

    override suspend fun updateGoal(goal: String): RoomSnapshot = edit { room ->
        if (goal.length > 240) invalid("goal", "Цель — не более 240 символов.")
        room.updateCurrentParticipant { it.copy(goal = goal) }
    }

    override suspend fun addTask(title: String): RoomSnapshot = edit { room ->
        val normalized = validateTitle(title)
        room.updateCurrentParticipant { participant ->
            if (participant.tasks.size >= 20) invalid("title", "Можно добавить не более 20 задач.")
            participant.copy(tasks = (participant.tasks + Task(newId(), normalized, false)).frozen())
        }
    }

    override suspend fun updateTask(taskId: String, title: String?, completed: Boolean?): RoomSnapshot = edit { room ->
        if (title == null && completed == null) invalid("task", "Измените название или статус задачи.")
        val normalized = title?.let(::validateTitle)
        requireOwnTask(room, taskId)
        room.updateCurrentParticipant { participant ->
            participant.copy(tasks = participant.tasks.map { task ->
                if (task.id == taskId) task.copy(title = normalized ?: task.title, completed = completed ?: task.completed)
                else task
            }.frozen())
        }
    }

    override suspend fun deleteTask(taskId: String): RoomSnapshot = edit { room ->
        requireOwnTask(room, taskId)
        room.updateCurrentParticipant { participant ->
            participant.copy(tasks = participant.tasks.filterNot { it.id == taskId }.frozen())
        }
    }

    override suspend fun updateSettings(focusDurationSeconds: Int, breakDurationSeconds: Int): RoomSnapshot = edit { room ->
        if (room.ownerId != state.value.currentUser.id) fail("FORBIDDEN", "Длительности меняет только создатель комнаты.")
        if (room.round?.status in listOf(RoundStatus.RUNNING, RoundStatus.PAUSED)) {
            fail("INVALID_STATE", "Нельзя менять длительности во время активного раунда.")
        }
        validateDurations(focusDurationSeconds, breakDurationSeconds)
        room.copy(focusDurationSeconds = focusDurationSeconds, breakDurationSeconds = breakDurationSeconds)
    }

    /** Demo control, not an API endpoint: simulate one partner accepting an invitation. */
    suspend fun simulatePartnerJoin(): RoomSnapshot = edit { room ->
        if (room.participants.size >= 2) fail("ROOM_FULL", "В комнате уже два участника.")
        room.copy(participants = (room.participants + friendParticipant()).frozen())
    }

    /** Demo control: inspect the same room as its other participant; no ownership changes. */
    suspend fun switchPerspective() = mutex.withLock {
        val room = requireRoom()
        val other = room.participants.firstOrNull { it.userId != state.value.currentUser.id }
            ?: fail("INVALID_STATE", "Сначала добавьте второго участника в демокомнату.")
        val nextUser = if (other.userId == localUser.id) localUser else friendUser
        mutableState.value = state.value.copy(currentUser = nextUser)
    }

    /** Demo profile is local only; the server contract does not expose a profile edit endpoint. */
    suspend fun updateDemoProfile(displayName: String) = mutex.withLock {
        val normalized = validateDisplayName(displayName)
        val user = state.value.currentUser.copy(displayName = normalized)
        if (user.id == localUser.id) localUser = user else friendUser = user
        val room = state.value.room?.let { room ->
            room.copy(
                participants = room.participants.map { participant ->
                    if (participant.userId == user.id) participant.copy(displayName = normalized) else participant
                }.frozen(),
                revision = room.revision + 1, serverNow = clock.instant(),
            )
        }
        mutableState.value = RepositoryState(user, room)
    }

    /** Discard this local demo session; this is neither leave nor close on a real server. */
    suspend fun resetDemo() = mutex.withLock {
        friendUser = friendUser.copy(displayName = "Друг")
        mutableState.value = RepositoryState(localUser)
    }

    private suspend fun edit(change: (RoomSnapshot) -> RoomSnapshot): RoomSnapshot = mutex.withLock {
        val current = requireRoom()
        val changed = change(current)
        publish(changed.copy(revision = current.revision + 1, serverNow = clock.instant()))
    }

    private fun requireRoom(): RoomSnapshot {
        val room = state.value.room ?: fail("NOT_FOUND", "Сначала создайте комнату или войдите по коду.")
        if (room.status == RoomStatus.CLOSED) fail("ROOM_CLOSED", "Комната закрыта.")
        if (room.participants.none { it.userId == state.value.currentUser.id }) fail("FORBIDDEN", "Вы не участник этой комнаты.")
        return room
    }

    private fun ensureNoOpenRoom() {
        if (state.value.room?.status == RoomStatus.OPEN) fail("ALREADY_IN_ROOM", "Вы уже состоите в открытой комнате. Сначала сбросьте демосессию.")
    }

    private fun publish(room: RoomSnapshot): RoomSnapshot {
        mutableState.value = state.value.copy(room = room)
        return room
    }

    private fun RoomSnapshot.updateCurrentParticipant(change: (Participant) -> Participant): RoomSnapshot =
        copy(participants = participants.map { participant ->
            if (participant.userId == state.value.currentUser.id) change(participant) else participant
        }.frozen())

    private fun requireOwnTask(room: RoomSnapshot, taskId: String) {
        val owner = room.participants.firstOrNull { participant -> participant.tasks.any { it.id == taskId } }
            ?: fail("NOT_FOUND", "Задача не найдена.")
        if (owner.userId != state.value.currentUser.id) fail("FORBIDDEN", "Можно менять только свои задачи.")
    }

    private fun friendParticipant() = Participant(
        friendUser.id, friendUser.displayName, "собрать интерфейс",
        listOf(Task(newId(), "Сделать экран комнаты", true), Task(newId(), "Добавить таймер", false)).frozen(),
    )

    companion object {
        const val SAMPLE_ROOM_CODE = "FD-DEMO42"

        private fun newId() = UUID.randomUUID().toString()
        private fun newCode(): String {
            val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
            return "FD-" + (1..6).map { alphabet.random() }.joinToString("")
        }

        private fun validateDisplayName(name: String): String = name.trim().also {
            if (it.length !in 2..40) invalid("displayName", "Имя — от 2 до 40 символов.")
        }

        private fun validateTitle(title: String): String = title.trim().also {
            if (it.length !in 1..160) invalid("title", "Название задачи — от 1 до 160 символов.")
        }

        private fun validateDurations(focus: Int, pause: Int) {
            val errors = buildMap {
                if (focus !in 300..10800 || focus % 60 != 0) put("focusDurationSeconds", "Фокус — от 5 до 180 целых минут.")
                if (pause !in 60..3600 || pause % 60 != 0) put("breakDurationSeconds", "Перерыв — от 1 до 60 целых минут.")
            }
            if (errors.isNotEmpty()) throw RepositoryException("VALIDATION_ERROR", "Проверьте длительности.", errors)
        }

        private fun invalid(field: String, message: String): Nothing =
            throw RepositoryException("VALIDATION_ERROR", message, mapOf(field to message))

        private fun fail(code: String, message: String): Nothing = throw RepositoryException(code, message)

        private fun <T> List<T>.frozen(): List<T> = Collections.unmodifiableList(ArrayList(this))
    }
}
