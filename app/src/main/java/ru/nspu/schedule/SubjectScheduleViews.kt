package ru.nspu.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubjectScheduleSheet(entries: List<SubjectScheduleEntry>, onDismiss: () -> Unit,
    onOpenDate: (LocalDate) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(entries, query) { entries.filter { it.subject.contains(query.trim(), ignoreCase = true) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 16.dp)) {
            Text("Предметы и недели", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Все предметы группы, включая дни без пар. Следующая неделя считается от выбранной даты.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Найти предмет") }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (shown.isEmpty()) item { Text(if (entries.isEmpty()) "На сайте пока нет предметов" else "Предмет не найден") }
                items(shown, key = { it.subject }) { entry ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(entry.subject, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            entry.variants.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text(entry.nextWeek, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            entry.nextDate?.let { date ->
                                TextButton(onClick = { onOpenDate(date) }) {
                                    Text("Ближайшая пара: " + date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + "  ›")
                                }
                            } ?: Text("Дальнейшие занятия не опубликованы", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
