package dev.focusduo.presentation

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.focusduo.designsystem.DemoBadge
import dev.focusduo.designsystem.FocusCard
import dev.focusduo.designsystem.FocusDuoColors
import dev.focusduo.designsystem.FocusDuoLogo
import dev.focusduo.designsystem.FocusIcon
import dev.focusduo.designsystem.PrimaryButton

/** First deliverable: a real Compose shell and local demo setup, without pretending to connect. */
@Composable
fun StartupScreen(demo: Boolean, onEnterDemo: () -> Unit, onExit: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = FocusDuoColors.Background) {
        if (demo) DemoHome(onExit) else Welcome(onEnterDemo, onExit)
    }
}

@Composable
private fun Welcome(onEnterDemo: () -> Unit, onExit: () -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FocusDuoLogo()
                Text("Работать вместе. Двигаться вперёд.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
            }
            Spacer(Modifier.height(64.dp))
            FocusCard(Modifier.widthIn(max = 660.dp).fillMaxWidth()) {
                Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Surface(color = FocusDuoColors.PaleGreen, shape = RoundedCornerShape(18.dp)) {
                        Box(Modifier.padding(16.dp)) { FocusIcon(FocusIcon.Leaf, Modifier.size(32.dp)) }
                    }
                    Text("Совместная работа\nначинается с фокуса", style = MaterialTheme.typography.h1)
                    Text("Меньше отвлечений. Больше внимания тому, что важно. Подготовь свою цель и познакомься с FocusDuo.", color = FocusDuoColors.Secondary)
                    Divider(color = FocusDuoColors.Border)
                    Text("Открой локальный просмотр: выбери имя и подготовь цель для будущей совместной сессии.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                    PrimaryButton(onEnterDemo, Modifier.fillMaxWidth()) {
                        Text("Открыть деморежим")
                        Spacer(Modifier.width(10.dp))
                        FocusIcon(FocusIcon.Arrow, color = Color.White)
                    }
                    Text("Без регистрации и подключения к серверу", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
                }
            }
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onExit) { Text("Закрыть приложение", color = FocusDuoColors.Secondary) }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun DemoHome(onExit: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val showSidebar = maxWidth >= 780.dp
        Row(Modifier.fillMaxSize()) {
            if (showSidebar) Sidebar(onExit)
            val scroll = rememberScrollState()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (showSidebar) 36.dp else 24.dp, vertical = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    if (!showSidebar) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            FocusDuoLogo(compact = true)
                            TextButton(onExit) { Text("Закрыть") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("ГЛАВНАЯ", style = MaterialTheme.typography.overline, color = FocusDuoColors.Secondary)
                        DemoBadge()
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Готовы поработать вместе?", style = MaterialTheme.typography.h2)
                        Text("Начни с простого: как тебя зовут и на чём хочется сосредоточиться?", color = FocusDuoColors.Secondary)
                    }
                    ProfileSetup()
                    Surface(Modifier.fillMaxWidth(), color = FocusDuoColors.PaleGreen.copy(alpha = 0.55f), shape = MaterialTheme.shapes.small) {
                        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            FocusIcon(FocusIcon.Leaf)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Маленький шаг — уже начало", style = MaterialTheme.typography.subtitle1)
                                Text("Демо — это локальный просмотр без подключения к серверу. Имя и цель хранятся только до закрытия приложения.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                            }
                        }
                    }
                    Text("FocusDuo  /  Вместе легче сосредоточиться", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun Sidebar(onExit: () -> Unit) {
    Surface(Modifier.width(214.dp).fillMaxHeight(), color = FocusDuoColors.Sidebar) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 30.dp)) {
            FocusDuoLogo(Modifier.padding(horizontal = 8.dp), compact = true)
            Spacer(Modifier.height(42.dp))
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = FocusDuoColors.PaleGreen) {
                Row(Modifier.padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    FocusIcon(FocusIcon.Home)
                    Text("Главная", fontWeight = FontWeight.SemiBold, color = FocusDuoColors.Green)
                }
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.padding(horizontal = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("ТВОЁ ПРОСТРАНСТВО", style = MaterialTheme.typography.overline, color = FocusDuoColors.Secondary)
                Text("Один шаг за раз.\nОдна цель в фокусе.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                Spacer(Modifier.height(12.dp))
                Divider(color = FocusDuoColors.Border)
                Spacer(Modifier.height(4.dp))
                Text("Демо · без сервера", style = MaterialTheme.typography.caption, color = FocusDuoColors.Green)
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onExit, Modifier.fillMaxWidth()) { Text("Закрыть приложение", color = FocusDuoColors.Secondary, style = MaterialTheme.typography.body2) }
        }
    }
    Divider(Modifier.fillMaxHeight().width(1.dp), color = FocusDuoColors.Border)
}

@Composable
private fun ProfileSetup() {
    var name by rememberSaveable { mutableStateOf("Ты") }
    var goal by rememberSaveable { mutableStateOf("") }
    var savedName by rememberSaveable { mutableStateOf("Ты") }
    var savedGoal by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var success by rememberSaveable { mutableStateOf(false) }
    val nameError = if (attempted && name.trim().length !in 2..40) "Имя должно содержать от 2 до 40 символов." else null
    val goalError = if (attempted && goal.length > 240) "Цель должна содержать не более 240 символов." else null
    val dirty = name != savedName || goal != savedGoal

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= 760.dp
        val form: @Composable () -> Unit = {
            FocusCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Твой фокус на сегодня", style = MaterialTheme.typography.h4)
                        Text("Можно начать с одной небольшой цели.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                    }
                    DemoTextField("Как тебя называть", name, { name = it; success = false }, "Например, Саша", nameError, singleLine = true, helper = "От 2 до 40 символов")
                    DemoTextField("Цель", goal, { goal = it; success = false }, "Что ты хочешь сделать?", goalError, singleLine = false, helper = "${goal.length} / 240 · можно оставить пустой")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton(onClick = {
                            attempted = true
                            if (name.trim().length in 2..40 && goal.length <= 240) {
                                name = name.trim(); savedName = name; savedGoal = goal
                                success = true
                            }
                        }) { Text("Сохранить") }
                        if (dirty) TextButton(onClick = { name = savedName; goal = savedGoal; attempted = false; success = false }) {
                            Text("Отменить изменения", color = FocusDuoColors.Secondary)
                        }
                    }
                    if (success) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FocusIcon(FocusIcon.Check, Modifier.size(18.dp))
                        Text("Профиль сохранён в деморежиме", color = FocusDuoColors.Green, style = MaterialTheme.typography.body2)
                    }
                }
            }
        }
        val preview: @Composable () -> Unit = { ProfilePreview(name.trim(), goal, dirty, success) }
        if (twoColumns) Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1.35f)) { form() }
            Box(Modifier.weight(1f)) { preview() }
        } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { form(); preview() }
    }
}

@Composable
private fun DemoTextField(label: String, value: String, onChange: (String) -> Unit, placeholder: String, error: String?, singleLine: Boolean, helper: String) {
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = MaterialTheme.typography.body2, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = value, onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                        focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                        true
                    } else false
                }
                .then(if (singleLine) Modifier else Modifier.heightIn(min = 116.dp)),
            singleLine = singleLine, maxLines = if (singleLine) 1 else 5,
            placeholder = { Text(placeholder, color = FocusDuoColors.Secondary.copy(alpha = 0.8f), style = MaterialTheme.typography.body1) },
            textStyle = MaterialTheme.typography.body1,
            isError = error != null, shape = MaterialTheme.shapes.small,
            colors = TextFieldDefaults.outlinedTextFieldColors(unfocusedBorderColor = FocusDuoColors.Border, focusedBorderColor = FocusDuoColors.Green, backgroundColor = Color.White, cursorColor = FocusDuoColors.Green),
        )
        Text(error ?: helper, style = MaterialTheme.typography.caption, color = if (error != null) FocusDuoColors.Error else FocusDuoColors.Secondary)
    }
}

@Composable
private fun ProfilePreview(name: String, goal: String, dirty: Boolean, success: Boolean) {
    FocusCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(25.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Text("ТАК БУДЕТ ВЫГЛЯДЕТЬ ПРОФИЛЬ", style = MaterialTheme.typography.overline, color = FocusDuoColors.Secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).clip(CircleShape).background(FocusDuoColors.PaleGreen), contentAlignment = Alignment.Center) {
                    Text(if (name.isEmpty()) "?" else String(Character.toChars(name.codePointAt(0))).uppercase(), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = FocusDuoColors.Green)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name.ifBlank { "Твоё имя" }, style = MaterialTheme.typography.subtitle1, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("Личный профиль", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
                }
            }
            Divider(color = FocusDuoColors.Border)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Цель", style = MaterialTheme.typography.body2, fontWeight = FontWeight.SemiBold)
                Text(goal.ifBlank { "Здесь появится твоя цель на сессию." }, style = MaterialTheme.typography.body1, color = if (goal.isBlank()) FocusDuoColors.Secondary else FocusDuoColors.Text)
            }
            Surface(color = FocusDuoColors.Background, shape = MaterialTheme.shapes.small) {
                Text(if (dirty) "Предпросмотр · изменения не сохранены" else if (success) "Сохранено до закрытия приложения" else "Предпросмотр обновляется при вводе", Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
            }
        }
    }
}
