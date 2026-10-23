package ru.nspu.schedule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun AssessmentOverviewButton(entries: List<AssessmentEntry>, onOpen: () -> Unit) {
    val upcoming = entries.count { it.state != AssessmentState.PAST }
    Surface(onClick = onOpen, shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().height(84.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Важное из расписания", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                Text(if (entries.isEmpty()) "На сайте пока нет записей" else "Открыть все события · ${entries.size}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(if (upcoming > 0) "$upcoming  ›" else "›", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun AssessmentCard(entry: AssessmentEntry, onOpen: () -> Unit) {
    val past = entry.state == AssessmentState.PAST
    val accent = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    val date = entry.date?.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru-RU")))
        ?: "Дата уточняется"
    Surface(onClick = onOpen, shape = RoundedCornerShape(16.dp),
        color = if (past) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, if (past) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().height(202.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.kindLabel, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.ExtraBold)
                Text(if (entry.notice.isNew && !past) "● Новое" else if (past && entry.kind in setOf("КОНСУЛЬТАЦИЯ", "КУРСОВАЯ"))
                    "Прошла" else entry.state.label, style = MaterialTheme.typography.labelMedium, color = accent)
            }
            Text(entry.info.subject, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text("$date · ${entry.lesson.time.ifBlank { "Время не указано" }}", style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf("Ауд. ${entry.info.room}", entry.info.teacher).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entry.weekLabel, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
