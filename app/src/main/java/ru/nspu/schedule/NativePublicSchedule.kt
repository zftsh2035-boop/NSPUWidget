package ru.nspu.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

/**
 * Compact native weekly timetable, inspired by the official NSPU Android app layout.
 * No embedded browser, nested lesson carousels or links to rooms/groups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativePublicSchedule(
    page: OfficialSchedulePage,
    onBack: () -> Unit,
    onOpenReference: (OfficialSchedulePage) -> Unit
) {
    val context = LocalContext.current.applicationContext
    var weekShift by rememberSaveable(page.url) { mutableIntStateOf(0) }
    var selectedTab by rememberSaveable(page.url) { mutableIntStateOf(0) }
    var showPast by rememberSaveable(page.url) { mutableStateOf(false) }
    var refreshCounter by remember(page.url) { mutableIntStateOf(0) }
    var forceRefresh by remember(page.url) { mutableStateOf(false) }
    var data by remember(page.url) { mutableStateOf<PublicTimetable?>(null) }
    var displayedRequest by remember(page.url) { mutableStateOf("") }
    var loading by remember(page.url) { mutableStateOf(true) }
    var refreshing by remember(page.url) { mutableStateOf(false) }
    var error by remember(page.url) { mutableStateOf("") }
    val requestKey = page.url + "/" + weekShift + "/" + showPast
    val cachedInMemory = remember(requestKey) {
        PublicTimetableRepository.memory(page, weekShift, showPast)
    }

    LaunchedEffect(requestKey, refreshCounter) {
        val requested = requestKey
        val diskCache = withContext(Dispatchers.IO) {
            PublicTimetableRepository.cached(context, page, weekShift, showPast)
        }
        if (diskCache != null) {
            data = diskCache
            displayedRequest = requested
            loading = false
        } else if (cachedInMemory != null) {
            data = cachedInMemory
            displayedRequest = requested
            loading = false
        } else {
            // Do not display last week's timetable with this week's header.
            data = null
            displayedRequest = requested
            loading = true
        }
        val force = forceRefresh
        forceRefresh = false
        val needsFetch = force ||
            !withContext(Dispatchers.IO) {
                PublicTimetableRepository.isFresh(context, page, weekShift, showPast)
            }
        if (needsFetch) {
            refreshing = data != null
            val fetched = runCatching {
                withContext(Dispatchers.IO) {
                    PublicTimetableRepository.load(context, page, weekShift, showPast,
                        force = force)
                }
            }
            fetched.onSuccess {
                data = it
                error = ""
            }.onFailure {
                error = it.localizedMessage ?: "Не удалось получить расписание"
            }
        }
        loading = false
        refreshing = false
    }

    BackHandler { onBack() }
    val locale = remember { Locale.forLanguageTag("ru-RU") }
    val date = remember { DateTimeFormatter.ofPattern("d MMMM", locale) }
    val fullDate = remember { DateTimeFormatter.ofPattern("d MMMM yyyy", locale) }
    val dayNames = remember {
        listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота")
    }
    val timetable = if (displayedRequest == requestKey) data else cachedInMemory
    val monday = timetable?.weekStart ?: LocalDate.now(ZONE)
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .plusWeeks(weekShift.toLong())

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(page.title, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (page.kind == OfficialSchedulePage.Kind.TEACHER)
                            "Расписание преподавателя" else "Расписание аудитории",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ Назад") } },
            actions = {
                TextButton(onClick = { forceRefresh = true; refreshCounter++ }) { Text("⟳", fontSize = 22.sp) }
            }
        )
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            // Small period selector like the official app; no oversized hero block.
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { weekShift = (weekShift - 1).coerceAtLeast(-80) },
                    enabled = weekShift > -80) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(monday.format(date) + " — " + monday.plusDays(5).format(date),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val week = timetable?.week
                    Text((week?.toString() ?: "…") + " неделя · " +
                        (if ((week ?: 1) % 2 == 1) "числитель" else "знаменатель"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { weekShift = (weekShift + 1).coerceAtMost(80) },
                    enabled = weekShift < 80) { Text("›", style = MaterialTheme.typography.headlineMedium) }
            }
            if (!timetable?.roomInfo.isNullOrBlank() && page.kind == OfficialSchedulePage.Kind.ROOM) {
                Text(timetable!!.roomInfo, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 17.dp, vertical = 2.dp))
            }
            SecondaryTabRow(selectedTabIndex = selectedTab) {
                listOf("Неделя", "По датам", "Экзамены").forEachIndexed { i, name ->
                    Tab(selected = selectedTab == i, onClick = { selectedTab = i },
                        text = { Text(name, maxLines = 1) })
                }
            }
            if (selectedTab != 0) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Показывать прошедшие занятия",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f))
                    Switch(checked = showPast, onCheckedChange = { showPast = it })
                }
            }
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                if (timetable == null && loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else if (timetable == null) {
                    Column(Modifier.align(Alignment.Center).padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Не удалось открыть расписание",
                            style = MaterialTheme.typography.titleMedium)
                        Text(error.take(180), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { forceRefresh = true; refreshCounter++ }) { Text("Повторить") }
                    }
                } else {
                    val lessons = when (selectedTab) {
                        1 -> timetable.dated(PublicSection.DATED)
                        2 -> timetable.dated(PublicSection.EXAMS)
                        else -> emptyList()
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (selectedTab == 0) {
                            for (i in 0..5) {
                                val perDay = timetable.byDay(i)
                                item(key = "d$i") {
                                    Text(dayNames[i] + " · " +
                                        monday.plusDays(i.toLong()).format(fullDate),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = 13.dp, bottom = 3.dp))
                                }
                                if (perDay.isEmpty()) {
                                    item(key = "empty$i") {
                                        Text("Нет занятий",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                                    }
                                }
                                perDay.forEachIndexed { idx, event ->
                                    item(key = "lesson-$i-$idx") {
                                        SimpleTimetableLesson(event)
                                    }
                                }
                            }
                        } else {
                            val header = if (selectedTab == 1) "Занятия по датам"
                                else "Консультации и экзамены"
                            item(key="heading") {
                                Text(header, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(vertical = 8.dp))
                            }
                            if (lessons.isEmpty()) item {
                                Text("На сайте пока нет записей в этом разделе.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp))
                            }
                            var last: LocalDate? = null
                            lessons.forEachIndexed { idx, event ->
                                if (event.date != last) {
                                    last = event.date
                                    item(key = "date$idx") {
                                        Text(event.date?.format(fullDate).orEmpty(),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(top = 9.dp, bottom = 3.dp))
                                    }
                                }
                                item(key = "event$idx") {
                                    SimpleTimetableLesson(event)
                                }
                            }
                        }
                        if (error.isNotBlank()) item(key="error") {
                            Text("Показан кэш: " + error.take(100),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/** One fast Material 3 card without IntrinsicSize or nested LazyRow. */
@Composable
private fun SimpleTimetableLesson(event: PublicClass) {
    var expanded by remember(event.description) { mutableStateOf(false) }
    val groups = remember(event.links) {
        event.links.filter { it.type == "group" }.map { it.label }
            .distinct().take(4).joinToString(", ")
    }
    val rooms = remember(event.links) {
        event.links.filter { it.type == "room" }.map { it.label }
            .distinct().take(3).joinToString(", ")
    }
    val teachers = remember(event.links) {
        event.links.filter { it.type == "teacher" }.map { it.label }
            .distinct().take(3).joinToString(", ")
    }
    val meta = listOf(groups, rooms, teachers).filter { it.isNotBlank() }.joinToString(" · ")
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(event.subject, modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (expanded) 10 else 2,
                    overflow = TextOverflow.Ellipsis)
                Text(event.time, style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(meta.ifBlank { event.description.substringAfter(",").trim() },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 8 else 2,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                val accent = MaterialTheme.colorScheme.primary
                Surface(shape = RoundedCornerShape(7.dp),
                    color = accent.copy(alpha = .10f)) {
                    Text(SiteParser.shortKind(event.kind),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent, fontWeight = FontWeight.Bold)
                }
            }
            if (expanded) {
                Text(event.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
