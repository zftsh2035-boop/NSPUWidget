package ru.nspu.schedule

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object WidgetSetup {
    /** Query launcher state on IO, only on a normal first launch. */
    fun shouldOffer(c: Context): Boolean {
        if (AppSettings.widgetIntroSeen(c)) return false
        val installed = AppWidgetManager.getInstance(c)
            .getAppWidgetIds(ComponentName(c, ScheduleWidget::class.java)).isNotEmpty()
        if (installed) AppSettings.setWidgetIntroSeen(c)
        return !installed
    }

    /** A user click opens the launcher's own confirmation. It never pins silently. */
    fun requestPin(c: Context): Boolean = runCatching {
        val manager = AppWidgetManager.getInstance(c)
        manager.isRequestPinAppWidgetSupported &&
            manager.requestPinAppWidget(ComponentName(c, ScheduleWidget::class.java), null, null)
    }.onFailure { android.util.Log.w("NSPUWidget", "Launcher pin request unavailable", it) }.getOrDefault(false)
}

@Composable
internal fun WidgetSetupDialog(onDismiss: () -> Unit, onPin: () -> Boolean) {
    var manual by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Text("▦", fontSize = 32.sp, color = MaterialTheme.colorScheme.primary) },
        title = { Text(if (manual) "Как добавить виджет" else "Добавь виджет расписания") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (manual) {
                    Text("1. Открой главный экран телефона и зажми пустое место.")
                    Text("2. Выбери «Виджеты» и найди «Расписание НГПУ».")
                    Text("3. Перетащи виджет на свободное место. Потяни за его края, чтобы изменить размер.")
                } else {
                    Text("Текущая пара, аудитория и личные заметки будут видны прямо на главном экране.")
                    Text("Размер можно менять, а избранные группы — переключать кнопкой ☆.")
                    TextButton(onClick = { manual = true }) { Text("Как добавить вручную") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (manual || onPin()) onDismiss() else manual = true
            }) { Text(if (manual) "Понятно" else "Добавить виджет") }
        },
        dismissButton = {
            TextButton(onClick = { if (manual) manual = false else onDismiss() }) {
                Text(if (manual) "Назад" else "Позже")
            }
        })
}
