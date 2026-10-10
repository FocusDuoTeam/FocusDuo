package dev.focusduo.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.focusduo.designsystem.*
import dev.focusduo.domain.Participant
import dev.focusduo.domain.RoomSnapshot
import dev.focusduo.domain.Task

/** A room is rendered from one repository snapshot. Scrolling belongs to the application shell. */
@Composable
fun RoomScreen(
    room: RoomSnapshot,
    currentUserId: String,
    busy: Boolean,
    onCopyCode: () -> Unit,
    onInvite: () -> Unit,
    onEditGoal: () -> Unit,
    onAddTask: () -> Unit,
    onEditTask: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onToggleTask: (Task) -> Unit,
    onEditSettings: () -> Unit,
) {
    val own = room.participants.firstOrNull { it.userId == currentUserId }
    val partner = room.participants.firstOrNull { it.userId != currentUserId }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        RoomHeader(room, onCopyCode, onInvite)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val left: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PreparedTimerCard(room, currentUserId == room.ownerId, busy, onEditSettings)
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (own != null) ParticipantCard(own, room.ownerId, true, Modifier.weight(1f).fillMaxHeight())
                        if (partner != null) ParticipantCard(partner, room.ownerId, false, Modifier.weight(1f).fillMaxHeight())
                        else WaitingParticipant(Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FocusIcon(FocusIcon.Leaf, Modifier.size(17.dp))
                        Text("Локальная демокомната · без сервера", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
                    }
                }
            }
            val right: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (own != null) TasksCard(own, true, busy, onEditGoal, onAddTask, onEditTask, onDeleteTask, onToggleTask)
                    if (partner != null) TasksCard(partner, false, busy, {}, {}, {}, {}, {})
                    else WaitingTasks(onInvite)
                }
            }
            if (maxWidth >= 800.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { left() }
                    Box(Modifier.weight(1f)) { right() }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { left(); right() }
        }
    }
}

@Composable
private fun RoomHeader(room: RoomSnapshot, onCopyCode: () -> Unit, onInvite: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val title: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Комната на двоих", style = MaterialTheme.typography.h2)
                Surface(color = FocusDuoColors.Border.copy(alpha = .6f), shape = RoundedCornerShape(50)) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FocusIcon(FocusIcon.People, Modifier.size(17.dp), FocusDuoColors.Secondary)
                        Text("${room.participants.size}/2", style = MaterialTheme.typography.body2)
                    }
                }
            }
        }
        val actions: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onCopyCode, shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, FocusDuoColors.Border), contentPadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp), modifier = Modifier.semantics { contentDescription = "Скопировать код комнаты ${room.code}" }) {
                    Text(room.code, fontWeight = FontWeight.SemiBold, color = FocusDuoColors.Text)
                    Spacer(Modifier.width(10.dp))
                    RoomGlyph(RoomGlyph.Copy, Modifier.size(19.dp))
                }
                Button(onInvite, shape = MaterialTheme.shapes.small, colors = ButtonDefaults.buttonColors(backgroundColor = FocusDuoColors.PaleGreen, contentColor = FocusDuoColors.Text), elevation = ButtonDefaults.elevation(0.dp, 0.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)) {
                    RoomGlyph(RoomGlyph.Link, Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Пригласить")
                }
            }
        }
        if (maxWidth >= 940.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { title(); actions() }
        else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { title(); actions() }
    }
}

@Composable
private fun PreparedTimerCard(room: RoomSnapshot, isOwner: Boolean, busy: Boolean, onEditSettings: () -> Unit) {
    FocusCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Фокус · ${room.focusDurationSeconds / 60} мин", Modifier.weight(1f), style = MaterialTheme.typography.subtitle1)
                if (isOwner) RoomAction("Настроить длительность", RoomGlyph.Settings, !busy, onEditSettings)
            }
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val diameter = minOf(maxWidth, 282.dp)
                Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                        drawCircle(FocusDuoColors.Border.copy(alpha = .65f), style = Stroke(width = 12.dp.toPx()))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("%02d:%02d".format(room.focusDurationSeconds / 60, room.focusDurationSeconds % 60), fontSize = if (diameter >= 260.dp) 66.sp else 54.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp, color = FocusDuoColors.Text)
                        Text("Раунд ещё не начат", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                    }
                }
            }
            Text("Перерыв · ${room.breakDurationSeconds / 60} мин", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
            Divider(Modifier.padding(top = 8.dp), color = FocusDuoColors.Border)
            Text("Управление таймером — на следующем этапе", Modifier.fillMaxWidth().padding(top = 6.dp), style = MaterialTheme.typography.caption, textAlign = TextAlign.Center, color = FocusDuoColors.Secondary)
            if (!isOwner) Text("Длительности задаёт создатель комнаты", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ParticipantCard(participant: Participant, ownerId: String, own: Boolean, modifier: Modifier) {
    FocusCard(modifier) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Avatar(participant.displayName, own)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(participant.displayName, style = MaterialTheme.typography.subtitle1)
                if (participant.userId == ownerId) Surface(color = FocusDuoColors.PaleGreen, shape = RoundedCornerShape(6.dp)) {
                    Text("Создатель", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 10.sp, color = FocusDuoColors.Green)
                }
                Text(if (own) "Ты · подготовка" else "Подготовка", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
            }
        }
    }
}

@Composable
private fun Avatar(name: String, own: Boolean) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(if (own) FocusDuoColors.PaleGreen else Color(0xFFFAEFD6)), contentAlignment = Alignment.Center) {
        Text(if (name.isBlank()) "?" else String(Character.toChars(name.codePointAt(0))).uppercase(), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WaitingParticipant(modifier: Modifier) {
    FocusCard(modifier) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(FocusDuoColors.Background), contentAlignment = Alignment.Center) {
                FocusIcon(FocusIcon.People, Modifier.size(22.dp), FocusDuoColors.Secondary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Место для друга", style = MaterialTheme.typography.body2, fontWeight = FontWeight.SemiBold)
                Text("Ожидаем участника", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
            }
        }
    }
}

@Composable
private fun TasksCard(participant: Participant, own: Boolean, busy: Boolean, onEditGoal: () -> Unit, onAddTask: () -> Unit, onEditTask: (Task) -> Unit, onDeleteTask: (Task) -> Unit, onToggleTask: (Task) -> Unit) {
    FocusCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (own) "Твои задачи" else "Задачи друга", Modifier.weight(1f), style = MaterialTheme.typography.h3)
                Surface(color = FocusDuoColors.Background, shape = RoundedCornerShape(50)) {
                    Text("${participant.tasks.count { it.completed }}/${participant.tasks.size}", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (participant.goal.isBlank()) "Цель пока не указана" else "Цель: ${participant.goal}", Modifier.weight(1f).padding(top = 6.dp), style = MaterialTheme.typography.body1, color = FocusDuoColors.Secondary)
                if (own) RoomAction("Изменить свою цель", RoomGlyph.Edit, !busy, onEditGoal)
            }
            Divider(color = FocusDuoColors.Border)
            if (participant.tasks.isEmpty()) {
                Column(Modifier.padding(vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(if (own) "Начни с небольшой задачи" else "Пока нет задач", style = MaterialTheme.typography.subtitle1)
                    Text(if (own) "Что поможет приблизиться к цели?" else "Задачи появятся, когда участник их добавит.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                participant.tasks.forEach { task -> TaskRow(task, own, busy, onEditTask, onDeleteTask, onToggleTask) }
            }
            if (own) {
                Divider(color = FocusDuoColors.Border)
                TextButton(onAddTask, enabled = !busy && participant.tasks.size < 20, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                    RoomGlyph(RoomGlyph.Plus, Modifier.size(21.dp), if (participant.tasks.size < 20) FocusDuoColors.Green else FocusDuoColors.Secondary)
                    Spacer(Modifier.width(10.dp))
                    Text(if (participant.tasks.size < 20) "Добавить задачу" else "Добавлено 20 из 20 задач")
                }
            } else Text("Задачи участника доступны только для просмотра", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
        }
    }
}

@Composable
private fun TaskRow(task: Task, own: Boolean, busy: Boolean, onEditTask: (Task) -> Unit, onDeleteTask: (Task) -> Unit, onToggleTask: (Task) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (own) Checkbox(task.completed, { onToggleTask(task) }, enabled = !busy, modifier = Modifier.size(34.dp).semantics { contentDescription = "Отметить задачу: ${task.title}" }, colors = CheckboxDefaults.colors(checkedColor = FocusDuoColors.Green, uncheckedColor = FocusDuoColors.Secondary.copy(alpha = .6f)))
        else ReadOnlyTaskMark(task.completed)
        Text(task.title, Modifier.weight(1f).padding(start = 7.dp, end = if (own) 0.dp else 5.dp, top = 6.dp, bottom = 8.dp), style = MaterialTheme.typography.body1, color = if (task.completed) FocusDuoColors.Secondary else FocusDuoColors.Text, textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None)
        if (own) {
            RoomAction("Изменить задачу: ${task.title}", RoomGlyph.Edit, !busy) { onEditTask(task) }
            RoomAction("Удалить задачу: ${task.title}", RoomGlyph.Delete, !busy) { onDeleteTask(task) }
        }
    }
}

@Composable
private fun ReadOnlyTaskMark(completed: Boolean) {
    Box(Modifier.size(34.dp).semantics { contentDescription = if (completed) "Выполнено" else "Не выполнено" }, contentAlignment = Alignment.Center) {
        Surface(Modifier.size(21.dp), color = if (completed) FocusDuoColors.Green else Color.White, shape = RoundedCornerShape(5.dp), border = if (completed) null else BorderStroke(1.dp, FocusDuoColors.Secondary.copy(alpha = .6f))) {
            if (completed) FocusIcon(FocusIcon.Check, Modifier.padding(3.dp), Color.White)
        }
    }
}

@Composable
private fun WaitingTasks(onInvite: () -> Unit) {
    FocusCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Задачи друга", style = MaterialTheme.typography.h3)
            Text("Вместе легче держать фокус", style = MaterialTheme.typography.subtitle1)
            Text("Пригласи второго участника. Здесь появятся его цель и задачи.", style = MaterialTheme.typography.body1, color = FocusDuoColors.Secondary)
            TextButton(onInvite, contentPadding = PaddingValues(0.dp)) {
                RoomGlyph(RoomGlyph.Link, Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("Показать приглашение")
            }
        }
    }
}

private enum class RoomGlyph { Copy, Link, Edit, Delete, Plus, Settings }

@Composable
private fun RoomAction(description: String, icon: RoomGlyph, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(34.dp).semantics { contentDescription = description }) {
        RoomGlyph(icon, Modifier.size(18.dp), FocusDuoColors.Secondary.copy(alpha = if (enabled) 1f else .35f))
    }
}

/** Matching 24-unit strokes keep action icons consistent across Windows display scales. */
@Composable
private fun RoomGlyph(icon: RoomGlyph, modifier: Modifier = Modifier, color: Color = FocusDuoColors.Green) {
    Canvas(modifier.size(22.dp)) {
        val s = size.width / 24f
        val stroke = Stroke(1.7f * s, cap = StrokeCap.Round)
        fun point(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, point(x1, y1), point(x2, y2), 1.7f * s, StrokeCap.Round)
        fun path(block: Path.() -> Unit) = drawPath(Path().apply(block), color, style = stroke)
        when (icon) {
            RoomGlyph.Copy -> {
                drawRoundRect(color, point(4f, 6f), Size(12f * s, 15f * s), androidx.compose.ui.geometry.CornerRadius(2f * s), style = stroke)
                path { moveTo(8f * s, 3f * s); lineTo(19f * s, 3f * s); lineTo(20f * s, 4f * s); lineTo(20f * s, 17f * s) }
            }
            RoomGlyph.Link -> {
                path { moveTo(10f * s, 7f * s); lineTo(12f * s, 5f * s); cubicTo(18f * s, -1f * s, 25f * s, 6f * s, 19f * s, 12f * s); lineTo(16f * s, 15f * s) }
                path { moveTo(14f * s, 17f * s); lineTo(12f * s, 19f * s); cubicTo(6f * s, 25f * s, -1f * s, 18f * s, 5f * s, 12f * s); lineTo(8f * s, 9f * s) }
                line(8f, 16f, 16f, 8f)
            }
            RoomGlyph.Edit -> {
                path { moveTo(4f * s, 16f * s); lineTo(4f * s, 21f * s); lineTo(9f * s, 20f * s); lineTo(21f * s, 8f * s); lineTo(16f * s, 3f * s); close() }
                line(13f, 6f, 18f, 11f)
            }
            RoomGlyph.Delete -> {
                line(4f, 6f, 20f, 6f)
                path { moveTo(6f * s, 6f * s); lineTo(7f * s, 21f * s); lineTo(17f * s, 21f * s); lineTo(18f * s, 6f * s) }
                path { moveTo(9f * s, 6f * s); lineTo(9f * s, 3f * s); lineTo(15f * s, 3f * s); lineTo(15f * s, 6f * s) }
                line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f)
            }
            RoomGlyph.Plus -> { line(12f, 3f, 12f, 21f); line(3f, 12f, 21f, 12f) }
            RoomGlyph.Settings -> {
                line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f)
                drawCircle(Color.White, 2.5f * s, point(8f, 6f)); drawCircle(color, 2.5f * s, point(8f, 6f), style = stroke)
                drawCircle(Color.White, 2.5f * s, point(16f, 12f)); drawCircle(color, 2.5f * s, point(16f, 12f), style = stroke)
                drawCircle(Color.White, 2.5f * s, point(8f, 18f)); drawCircle(color, 2.5f * s, point(8f, 18f), style = stroke)
            }
        }
    }
}
