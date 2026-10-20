package ru.nspu.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val NoteEditorRequestSaver = listSaver<NoteEditorRequest?, String>(
    save = { request -> request?.let {
        val target = it.target
        listOf(target.groupId.toString(), target.date.toString(), target.subject, target.time,
            target.kind, target.subgroup, it.note?.id.orEmpty(), it.note?.text.orEmpty(),
            (it.note?.updatedAt ?: 0L).toString(), target.uniqueOnDay.toString())
    }.orEmpty() },
    restore = { values -> if (values.isEmpty()) null else {
        val target = NoteTarget(values[0].toInt(), LocalDate.parse(values[1]), values[2],
            values[3], values[4], values[5], values.getOrNull(9) == "true")
        NoteEditorRequest(target, values[6].takeIf { it.isNotEmpty() }?.let {
            PersonalNote(it, target, values[7], values[8].toLong())
        })
    } }
)

private val noteDate = DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", Locale.forLanguageTag("ru-RU"))

@Composable
internal fun NoteEditor(request: NoteEditorRequest, groupName: String, saving: Boolean,
    error: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: () -> Unit) {
    var text by rememberSaveable(request.target, request.note?.id) {
        mutableStateOf(request.note?.text.orEmpty())
    }
    val tooLong = text.length > PersonalNote.MAX_LENGTH
    Dialog(onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        // A bounded window keeps actions above the IME, including in landscape.
        // Only the body scrolls; a long draft cannot push Save below the keyboard.
        Box(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp),
            contentAlignment = Alignment.Center) {
            Surface(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp)) {
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (request.note == null) "Заметка к паре" else "Личная заметка",
                            style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(request.target.subject, fontWeight = FontWeight.Bold)
                        Text(request.target.date.format(noteDate) + " · " + request.target.time + "\n" + groupName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = text, onValueChange = { text = it },
                            enabled = !saving, label = { Text("Заметка") },
                            placeholder = { Text("Например, взять бинты") },
                            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7,
                            isError = tooLong || error.isNotBlank(), supportingText = {
                                Text(when {
                                    error.isNotBlank() -> error
                                    tooLong -> "Максимум ${PersonalNote.MAX_LENGTH} символов"
                                    else -> "Видна в виджете. Только для этой пары."
                                })
                            })
                        if (request.note != null) TextButton(onClick = onDelete, enabled = !saving,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text("Удалить заметку")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.weight(1f)) {
                            Text("Отмена")
                        }
                        TextButton(onClick = { onSave(text) }, modifier = Modifier.weight(1f),
                            enabled = !saving && !tooLong && (request.note != null || text.isNotBlank())) {
                            Text(if (saving) "Сохраняю…" else "Сохранить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NotesListDialog(notes: List<PersonalNote>?, groupNames: Map<Int, String>, error: String,
    onDismiss: () -> Unit, onOpen: (PersonalNote) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Мои заметки") },
        text = {
            when {
                error.isNotBlank() -> Text(error, color = MaterialTheme.colorScheme.error)
                notes == null -> CircularProgressIndicator()
                notes.isEmpty() -> Text("Открой нужный день и нажми значок заметки рядом со временем пары.")
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(notes, key = { it.id }, contentType = { "note" }) { note ->
                        Column(Modifier.fillMaxWidth().clickable { onOpen(note) }.padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(note.target.subject, fontWeight = FontWeight.Bold)
                            Text(note.target.date.format(noteDate) + " · " + note.target.time + "\n" +
                                (groupNames[note.target.groupId] ?: "Группа №${note.target.groupId}"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(note.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
