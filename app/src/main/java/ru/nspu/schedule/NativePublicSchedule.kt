package ru.nspu.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Native Compose timetable. No WebView, preserves all public NSPU lesson categories. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativePublicSchedule(
    page: OfficialSchedulePage,
    onBack: () -> Unit,
    onOpenReference: (OfficialSchedulePage) -> Unit
) {
    var weekShift by rememberSaveable(page.url) { mutableIntStateOf(0) }
    var day by rememberSaveable(page.url) {
        mutableIntStateOf((LocalDate.now(ZONE).dayOfWeek.value - 1).coerceIn(0, 5))
    }
    var tab by rememberSaveable(page.url) { mutableIntStateOf(0) }
    var showPast by rememberSaveable(page.url) { mutableStateOf(false) }
    var refresh by remember(page.url) { mutableIntStateOf(0) }
    var snapshot by remember(page.url) { mutableStateOf<PublicTimetable?>(null) }
    var loading by remember(page.url) { mutableStateOf(true) }
    var error by remember(page.url) { mutableStateOf("") }

    LaunchedEffect(page.url, weekShift, showPast, refresh) {
        loading = true
        error = ""
        snapshot = null
        runCatching {
            withContext(Dispatchers.IO) {
                PublicTimetableRepository.load(page, weekShift, showPast)
            }
        }.onSuccess { snapshot = it }
            .onFailure { error = it.localizedMessage ?: "Ошибка загрузки" }
        loading = false
    }
    BackHandler(onBack = onBack)
    val locale = remember { Locale.forLanguageTag("ru-RU") }
    val dateFormat = remember { DateTimeFormatter.ofPattern("d MMMM", locale) }
    val fullDate = remember { DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE", locale) }
    val dayTitles = listOf("Пн","Вт","Ср","Чт","Пт","Сб")
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column {
                        Text(page.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(page.kind.defaultTitle + " · НГПУ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Назад") } },
                actions = { TextButton(onClick = { refresh++ }) { Text("⟳", fontSize = 24.sp) } }
            )
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(snapshot?.title ?: page.title, maxLines = 3,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (!snapshot?.roomInfo.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(snapshot!!.roomInfo, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = { weekShift = (weekShift - 1).coerceAtLeast(-80) },
                            enabled = weekShift > -80,
                            contentPadding = PaddingValues(horizontal = 12.dp)) { Text("‹") }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            val monday = snapshot?.weekStart ?: LocalDate.now(ZONE)
                                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                                .plusWeeks(weekShift.toLong())
                            Text(monday.format(dateFormat) + " – " +
                                monday.plusDays(5).format(dateFormat),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold)
                            val weekNumber = snapshot?.week
                            Text((weekNumber?.toString() ?: "…") + " неделя · " +
                                (if ((weekNumber ?: 1) % 2 == 1) "числитель" else "знаменатель"),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        FilledTonalButton(
                            onClick = { weekShift = (weekShift + 1).coerceAtMost(80) },
                            enabled = weekShift < 80,
                            contentPadding = PaddingValues(horizontal = 12.dp)) { Text("›") }
                    }
                    if (weekShift != 0) TextButton(onClick = {
                        weekShift = 0
                        day = (LocalDate.now(ZONE).dayOfWeek.value - 1).coerceIn(0, 5)
                    }) { Text("Текущая неделя") }
                }
            }
            TabRow(selectedTabIndex = tab) {
                listOf("Неделя", "По датам", "Экзамены").forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i },
                        text = { Text(title, maxLines = 1) })
                }
            }
            if (tab == 0) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items((0..5).toList()) { index ->
                        val count = snapshot?.byDay(index)?.size ?: 0
                        FilterChip(selected = day == index, onClick = { day = index },
                            label = { Text(dayTitles[index] +
                                (if (count == 0) "" else " · " + count)) })
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Показывать прошедшие занятия",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    Switch(checked = showPast, onCheckedChange = { showPast = it })
                }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error.isNotBlank() -> Column(
                        Modifier.align(Alignment.Center).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Не удалось загрузить расписание",
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(error.take(200), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { refresh++ }) { Text("Повторить") }
                    }
                    snapshot != null -> {
                        val timetable = snapshot!!
                        val events = when (tab) {
                            0 -> timetable.byDay(day)
                            1 -> timetable.dated(PublicSection.DATED)
                            else -> timetable.dated(PublicSection.EXAMS)
                        }
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (tab == 0) item {
                                Text(timetable.weekStart.plusDays(day.toLong()).format(fullDate),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold)
                            } else item {
                                Text(if (tab == 1) "Занятия по датам"
                                     else "Консультации, зачёты, экзамены",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold)
                            }
                            if (events.isEmpty()) item {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow
                                ) {
                                    Text("На сайте нет занятий для выбранного периода.",
                                        modifier = Modifier.padding(18.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            var previous: LocalDate? = null
                            events.forEach { event ->
                                if (tab != 0 && event.date != previous) {
                                    previous = event.date
                                    item {
                                        Text(event.date?.format(fullDate).orEmpty(),
                                            modifier = Modifier.padding(top = 8.dp),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold)
                                    }
                                }
                                item { NativePublicLessonCard(event, onOpenReference) }
                            }
                            item { Spacer(Modifier.height(12.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativePublicLessonCard(
    lesson: PublicClass,
    onOpenReference: (OfficialSchedulePage) -> Unit
) {
    val context = LocalContext.current
    val colorMode = when (AppSettings.theme(context)) {
        AppSettings.NEON -> WidgetMode.NEON
        AppSettings.LIGHT -> WidgetMode.LIGHT
        else -> WidgetMode.DARK
    }
    val accent = Color(WidgetTheme.lessonAccent(lesson.kind, colorMode))
    var expanded by remember(lesson.description) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, accent.copy(alpha = .36f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lesson.time, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Surface(color = accent.copy(alpha = .19f),
                    shape = RoundedCornerShape(8.dp)) {
                    Text(SiteParser.shortKind(lesson.kind), color = accent,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold)
                }
            }
            Text(lesson.subject, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(lesson.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded })
            val links = lesson.links.mapNotNull { link ->
                OfficialSchedulePage.fromUrl(link.url, link.label)?.let { link to it }
            }.distinctBy { it.second.url }
            if (links.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(links) { pair ->
                        AssistChip(onClick = { onOpenReference(pair.second) },
                            label = { Text(pair.first.label.take(45) + " ↗",
                                maxLines = 1, fontSize = 11.sp) })
                    }
                }
            }
            if (lesson.description.length > 130) {
                TextButton(onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp)) {
                    Text(if (expanded) "Свернуть" else "Подробнее",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
