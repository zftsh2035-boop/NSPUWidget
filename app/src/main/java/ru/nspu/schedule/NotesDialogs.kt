package ru.nspu.schedule

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val NoteEditorRequestSaver = listSaver<NoteEditorRequest?, String>(
    save = { request -> request?.let {
        val target = it.target
        val options = it.note?.options ?: NoteOptions()
        listOf(target.groupId.toString(), target.date.toString(), target.subject, target.time,
            target.kind, target.subgroup, it.note?.id.orEmpty(), it.note?.text.orEmpty(),
            (it.note?.updatedAt ?: 0L).toString(), target.uniqueOnDay.toString(), target.scope,
            options.visibleFrom?.toString().orEmpty(), options.visibleUntil?.toString().orEmpty(),
            options.reminders.joinToString(","))
    }.orEmpty() },
    restore = { values -> if (values.isEmpty()) null else {
        val target = NoteTarget(values[0].toInt(), LocalDate.parse(values[1]), values[2],
            values[3], values[4], values[5], values.getOrNull(9) == "true", values.getOrNull(10) ?: "lesson")
        NoteEditorRequest(target, values[6].takeIf { it.isNotEmpty() }?.let {
            PersonalNote(it, target, values[7], values[8].toLong(), NoteOptions(
                values.getOrNull(11)?.toLongOrNull(), values.getOrNull(12)?.toLongOrNull(),
                values.getOrNull(13).orEmpty().split(',').mapNotNull(String::toLongOrNull)))
        })
    } }
)

private val noteLocale = Locale.forLanguageTag("ru-RU")
private val noteDate = DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", noteLocale)
private val noteMoment = DateTimeFormatter.ofPattern("d MMM, HH:mm", noteLocale)
internal fun noteMoment(value: Long): String = Instant.ofEpochMilli(value)
    .atZone(ZoneId.systemDefault()).format(noteMoment)

internal fun pickNoteDate(c: Context, initial: LocalDate, onValue: (LocalDate) -> Unit) {
    DatePickerDialog(c, { _, year, month, day -> onValue(LocalDate.of(year, month + 1, day)) },
        initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

private fun pickNoteMoment(c: Context, initial: Long, onValue: (Long) -> Unit) {
    val start = Instant.ofEpochMilli(initial).atZone(ZoneId.systemDefault())
    pickNoteDate(c, start.toLocalDate()) { date ->
        TimePickerDialog(c, { _, hour, minute ->
            onValue(date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        }, start.hour, start.minute, true).show()
    }
}

@Composable
private fun NoteWindow(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Compose 1.7 dialogs can report zero navigation-bar insets. Keep the
    // activity's live inset so long notes never put actions under the gesture bar.
    val navigationBars = WindowInsets.navigationBars
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().systemBarsPadding().windowInsetsPadding(navigationBars)
            .imePadding().padding(12.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp), content = content)
            }
        }
    }
}

@Composable
internal fun NoteEditor(request: NoteEditorRequest, groupName: String, saving: Boolean,
    error: String, permissionRevision: Int, onDismiss: () -> Unit,
    onSave: (NoteDraft) -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val key = request.note?.id ?: request.target.toString()
    var text by rememberSaveable(key) { mutableStateOf(request.note?.text.orEmpty()) }
    var title by rememberSaveable(key) { mutableStateOf(if (request.target.isDay && request.note == null) "" else request.target.subject) }
    var day by rememberSaveable(key) { mutableStateOf(request.target.date.toString()) }
    val defaults = request.note?.options ?: if (request.target.isDay) NoteOptions.forDay(request.target.date) else NoteOptions()
    var from by rememberSaveable(key) { mutableStateOf(defaults.visibleFrom) }
    var until by rememberSaveable(key) { mutableStateOf(defaults.visibleUntil) }
    var reminders by rememberSaveable(key) { mutableStateOf(defaults.reminders.sorted()) }
    var section by rememberSaveable(key) { mutableIntStateOf(0) }
    var pickerError by rememberSaveable(key) { mutableStateOf("") }
    var confirmDelete by rememberSaveable(key) { mutableStateOf(false) }
    val options = NoteOptions(from, until, reminders)
    val tooLong = text.length > PersonalNote.MAX_LENGTH
    val exact = remember(permissionRevision) { NoteReminders.exactAllowed(context) }
    val notifications = remember(permissionRevision) { NoteReminders.notificationsAllowed(context) }
    fun addReminder(at: Long) {
        pickerError = when {
            at <= System.currentTimeMillis() -> "Выбери будущее время напоминания."
            at in reminders -> "Это время уже добавлено."
            reminders.size >= 32 -> "В одной заметке можно выбрать до 32 напоминаний."
            else -> ""
        }
        if (pickerError.isBlank()) reminders = (reminders + at).sorted()
    }
    NoteWindow(onDismiss = { if (!saving) onDismiss() }) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (request.target.isDay) "Заметка на день" else if (request.note == null)
                "Заметка к паре" else "Личная заметка", style = MaterialTheme.typography.headlineSmall)
            if (request.target.isDay) {
                OutlinedTextField(title, { title = it.take(120) }, label = { Text("Название") },
                    placeholder = { Text("Например, тренинг") }, singleLine = true,
                    enabled = !saving, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Название заметки" })
                TextButton(onClick = {
                    pickNoteDate(context, LocalDate.parse(day)) { date ->
                        if (from == NoteOptions.forDay(LocalDate.parse(day)).visibleFrom &&
                            until == NoteOptions.forDay(LocalDate.parse(day)).visibleUntil) {
                            val next = NoteOptions.forDay(date); from = next.visibleFrom; until = next.visibleUntil
                        }
                        day = date.toString()
                    }
                }, enabled = !saving) { Text(LocalDate.parse(day).format(noteDate) + "  ▾") }
            } else {
                Text(request.target.subject, fontWeight = FontWeight.Bold)
                Text(request.target.date.format(noteDate) + " · " + request.target.time,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(groupName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(text, { text = it }, enabled = !saving, label = { Text("Заметка") },
                placeholder = { Text("Что нужно сделать или взять с собой?") },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Текст заметки" }, minLines = 3, maxLines = 7,
                isError = tooLong || error.isNotBlank(), supportingText = {
                    Text(when {
                        error.isNotBlank() -> error
                        tooLong -> "Максимум ${PersonalNote.MAX_LENGTH} символов"
                        else -> "${text.length} / ${PersonalNote.MAX_LENGTH} · только для тебя"
                    })
                })
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionTile("Показывать", if (from == null && until == null) "В день пары"
                    else (from?.let(::noteMoment) ?: "С начала дня") + " → " +
                        (until?.let(::noteMoment) ?: "Без окончания"), section == 1,
                    Modifier.weight(1f).fillMaxHeight(), enabled = !saving) { section = if (section == 1) 0 else 1 }
                OptionTile("Напомнить", if (reminders.isEmpty()) "Без уведомлений" else "Выбрано: ${reminders.size}",
                    section == 2, Modifier.weight(1f).fillMaxHeight(), enabled = !saving) { section = if (section == 2) 0 else 2 }
            }
            if (section == 1) {
                if (!request.target.isDay) Text("Заметка к паре показывается в день этой пары.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Период показа в расписании и виджете. В «Моих заметках» запись остаётся доступна всегда.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    pickNoteMoment(context, from ?: NoteOptions.forDay(LocalDate.parse(day)).visibleFrom!!) { from = it }
                }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Text("С: " + (from?.let(::noteMoment) ?: "начала дня пары"))
                }
                OutlinedButton(onClick = {
                    pickNoteMoment(context, until ?: NoteOptions.forDay(LocalDate.parse(day)).visibleUntil!!) { until = it }
                }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Text("До: " + (until?.let(::noteMoment) ?: "конца дня пары"))
                }
                TextButton(onClick = {
                    val reset = if (request.target.isDay) NoteOptions.forDay(LocalDate.parse(day)) else NoteOptions()
                    from = reset.visibleFrom; until = reset.visibleUntil
                }, enabled = !saving) { Text("Весь выбранный день") }
            }
            if (section == 2) {
                Text("Можно добавить несколько дат и времени. Время — как на твоём телефоне.",
                    style = MaterialTheme.typography.bodySmall)
                reminders.forEach { at ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(noteMoment(at) + if (at < System.currentTimeMillis()) " · прошло" else "",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { reminders = reminders - at }, enabled = !saving) { Text("Убрать") }
                    }
                }
                OutlinedButton(onClick = {
                    pickNoteMoment(context, System.currentTimeMillis() + 60 * 60 * 1000L, ::addReminder)
                }, enabled = !saving && reminders.size < 32, modifier = Modifier.fillMaxWidth()) { Text("+ Дата и время") }
                if (!notifications) {
                    Text("Уведомления отключены. Включи их, чтобы получать напоминания.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Разрешить уведомления") }
                }
                if (!exact && Build.VERSION.SDK_INT >= 31) {
                    Text("Без разрешения на точное время Android может задерживать уведомления.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}"))) } }) { Text("Разрешить точное время") }
                }
                if (pickerError.isNotBlank()) Text(pickerError, color = MaterialTheme.colorScheme.error)
            }
            if (!options.valid()) Text("Окончание показа должно быть позже начала.", color = MaterialTheme.colorScheme.error)
            if (request.note != null) TextButton(onClick = { confirmDelete = true }, enabled = !saving,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Удалить заметку") }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.weight(1f)) { Text("Отмена") }
            Button(onClick = { onSave(NoteDraft(request.target.copy(
                subject = if (request.target.isDay) title.ifBlank { "Заметка на день" } else request.target.subject,
                date = LocalDate.parse(day)), text, options)) }, modifier = Modifier.weight(1f),
                enabled = !saving && !tooLong && options.valid() && (request.note != null || text.isNotBlank())) {
                Text(if (saving) "Сохраняю…" else "Сохранить")
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить заметку?") }, text = { Text("Её напоминания тоже будут отменены.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } })
}

@Composable
private fun OptionTile(title: String, value: String, selected: Boolean, modifier: Modifier,
    enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun NoteViewer(note: PersonalNote, groupName: String, onDismiss: () -> Unit, onEdit: () -> Unit) {
    NoteWindow(onDismiss) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(note.target.subject, style = MaterialTheme.typography.headlineSmall)
            Text(note.target.date.format(noteDate) + (if (note.target.isDay) " · на день" else " · ${note.target.time}") +
                "\n" + groupName, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(note.text, style = MaterialTheme.typography.bodyLarge) }
            if (note.options.reminders.isNotEmpty()) Text("Напоминания: " + note.options.reminders.joinToString { noteMoment(it) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
            FilledTonalButton(onClick = onEdit) { Text("Изменить") }
        }
    }
}

@Composable
internal fun DayNotesTiles(notes: List<PersonalNote>, onOpen: (PersonalNote) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(notes, key = { it.id }) { note ->
            Surface(onClick = { onOpen(note) }, shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.width(240.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(note.target.subject, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(note.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(if (note.options.reminders.any { it > System.currentTimeMillis() }) "◷ Есть напоминание · открыть" else "На день · открыть",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
internal fun NotesListDialog(notes: List<PersonalNote>?, groupNames: Map<Int, String>, error: String,
    onDismiss: () -> Unit, onOpen: (PersonalNote) -> Unit, onAdd: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Мои заметки") }, text = {
        when {
            error.isNotBlank() -> Text(error, color = MaterialTheme.colorScheme.error)
            notes == null -> CircularProgressIndicator()
            notes.isEmpty() -> Text("Добавь заметку на день или нажми значок заметки у нужной пары.")
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(notes, key = { it.id }, contentType = { "note" }) { note ->
                    Surface(onClick = { onOpen(note) }, shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(note.target.subject, fontWeight = FontWeight.Bold)
                            Text(note.target.date.format(noteDate) + (if (note.target.isDay) " · на день" else " · ${note.target.time}") +
                                "\n" + (groupNames[note.target.groupId] ?: "Группа №${note.target.groupId}"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(note.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onAdd) { Text("+ На день") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
