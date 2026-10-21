package ru.nspu.schedule

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun UpdateDialog(update: AppUpdate, onDismiss: () -> Unit, onDownload: () -> Unit,
    onRelease: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Доступно обновление ${update.version}") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (update.channel == AppSettings.DEV) "Тестовая сборка · dev" else "Стабильная версия")
                Text("Изменения", style = MaterialTheme.typography.titleSmall)
                Text(ReleaseNotes.display(update.notes).ifBlank {
                    "Описание для этой версии не опубликовано. Подробности доступны на GitHub."
                })
                if (update.channel == AppSettings.DEV) Text("Сборка доступна в разделе Artifacts; может потребоваться вход в GitHub.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRelease) { Text("Все изменения на GitHub ↗") }
            }
        },
        confirmButton = { TextButton(onClick = onDownload) {
            Text(if (update.channel == AppSettings.DEV) "Открыть сборку" else "Скачать")
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Позже") } })
}

@Composable
internal fun InstalledChangesDialog(onDismiss: () -> Unit, onRelease: () -> Unit) {
    val c = LocalContext.current.applicationContext
    var notes by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { notes = withContext(Dispatchers.IO) { UpdateChecker.installedNotes(c) } }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Что нового в ${UpdateChecker.installedVersion(c)}") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (notes == null) CircularProgressIndicator()
                else Text(notes!!.ifBlank { "Описание пока недоступно. Открой страницу выпуска в GitHub." })
            }
        },
        confirmButton = { TextButton(onClick = onRelease) { Text("GitHub ↗") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}

@Composable
internal fun WidgetDiagnosticsDialog(onDismiss: () -> Unit) {
    val c = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    suspend fun load(redraw: Boolean) {
        busy = true
        report = withContext(Dispatchers.IO) {
            runCatching {
                if (redraw) ScheduleWidget.renderAll(c)
                WidgetDiagnostics.report(c)
            }.getOrElse {
                "Не удалось собрать отчёт: ${it.javaClass.simpleName}"
            }
        }
        busy = false
    }
    LaunchedEffect(Unit) { load(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Диагностика виджетов") },
        text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Отчёт поможет выяснить, почему виджет не обновляется. Тексты заметок и занятий в него не входят.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = { scope.launch { load(true) } }) {
                    Text("Перерисовать виджеты")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                SelectionContainer { Text(report ?: "Собираем данные…", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = report != null && !busy, onClick = {
            c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NSPUWidget", report))
            Toast.makeText(c, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
        }) { Text("Копировать отчёт") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
