package ru.nspu.schedule

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Sea = Color(0xFF008D80)
private val Blue = Color(0xFF2563DB)
private val Violet = Color(0xFF8E53DA)
private val Amber = Color(0xFFC07816)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sync.schedule(this)
        if (Store.cached(this).isEmpty()) Sync.now(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme(primary = Color(0xFF9CC5FF), secondary = Color(0xFF5DE0C9))
                else -> lightColorScheme(primary = Blue, secondary = Sea, background = Color(0xFFF6F8FE))
            }
            MaterialTheme(colorScheme = colors) { AppScreen() }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppScreen() {
        var rerender by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(3200)
                rerender += 1
            }
        }
        val refreshTick = rerender // Read state to reflect background sync/cache changes.
        var selectedTab by remember { mutableIntStateOf(if (intent.getBooleanExtra("show_notices", false)) 1 else 0) }
        val date = Store.date(this)
        val lessons = Store.forDate(this, date)
        val notices = Store.notices(this)
        val group = Store.scheduleGroupName(this)
        val subgroup = Store.group(this)
        val availableSubgroups = Store.availableSubgroups(this)
        val focusedLesson = Store.focus(this)
        val lastUpdate = Store.status(this)
        val error = Store.error(this)
        var picker by remember { mutableIntStateOf(0) }
        var working by remember { mutableStateOf(false) }
        var pickerError by remember { mutableStateOf("") }
        var institutes by remember { mutableStateOf<List<Faculty>>(emptyList()) }
        var groupOptions by remember { mutableStateOf<List<UniversityGroup>>(emptyList()) }
        var faculty by remember { mutableStateOf<Faculty?>(null) }
        var subgroupPicker by remember { mutableStateOf(false) }

        fun loadInstitutes() {
            picker = 1
            working = true
            pickerError = ""
            Thread {
                val result = runCatching { GroupCatalog.faculties() }
                runOnUiThread {
                    working = false
                    result.onSuccess { institutes = it }.onFailure { pickerError = it.message ?: "Сбой сети" }
                }
            }.start()
        }
        fun loadGroups(f: Faculty) {
            faculty = f
            picker = 2
            working = true
            pickerError = ""
            Thread {
                val result = runCatching { GroupCatalog.groups(f.id) }
                runOnUiThread {
                    working = false
                    result.onSuccess { groupOptions = it }.onFailure { pickerError = it.message ?: "Сбой сети" }
                }
            }.start()
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Расписание НГПУ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                    actions = {
                        TextButton(onClick = { Sync.now(this@MainActivity); rerender++ }) {
                            Text("⟳", fontSize = 27.sp)
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Text("▦", fontSize = 22.sp) }, label = { Text("Расписание") })
                    NavigationBarItem(selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Text("◉", fontSize = 22.sp) },
                        label = { Text("Объявления" + if (notices.any { it.isNew }) " ●" else "") })
                }
            }
        ) { insets ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Surface(shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("ТВОЯ ГРУППА", fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(group.substringBefore(" "), fontSize = 24.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(group.substringAfter(" ", ""), maxLines = 2,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontSize = 12.sp)
                                }
                                FilledTonalButton(onClick = { loadInstitutes() }) { Text("Сменить") }
                            }
                        }
                    }
                }

                if (selectedTab == 0) {
                    if (availableSubgroups.isNotEmpty()) item {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Подгруппа", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            AssistChip(onClick = { subgroupPicker = true }, label = { Text(subgroup + "  ▾") })
                        }
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((-1..5).toList()) { daysFromToday ->
                                val relative = LocalDate.now(ZONE).plusDays(daysFromToday.toLong())
                                val active = relative == date
                                val caption = relative.format(DateTimeFormatter.ofPattern("EE", Locale.forLanguageTag("ru-RU")))
                                val number = relative.dayOfMonth
                                if (active) Button(
                                    onClick = {
                                        Store.jump(this@MainActivity, daysFromToday)
                                        ScheduleWidget.renderAll(this@MainActivity)
                                        rerender++
                                    },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) { Text("$caption $number") }
                                else OutlinedButton(
                                    onClick = {
                                        Store.jump(this@MainActivity, daysFromToday)
                                        ScheduleWidget.renderAll(this@MainActivity)
                                        rerender++
                                    },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) { Text("$caption $number") }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(date.format(DateTimeFormatter.ofPattern("d MMMM, EEEE",
                                    Locale.forLanguageTag("ru-RU"))),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(lessons.size.toString() + " пар",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (notices.isNotEmpty()) {
                        item {
                            val top = notices.firstOrNull { it.isNew } ?: notices.first()
                            Surface(shape = RoundedCornerShape(20.dp),
                                onClick = { selectedTab = 1 },
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("📣 " + if (top.isNew) "Новое объявление" else "Объявление НГПУ",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                                    Spacer(Modifier.height(6.dp))
                                    Text(top.text, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    if (lessons.isEmpty()) item {
                        Surface(shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Text("Занятий на выбранный день нет. Если расписание ещё не загружено, нажми ⟳.",
                                modifier = Modifier.padding(20.dp))
                        }
                    }
                    items(lessons) { lesson ->
                        LessonCard(lesson, date,
                            LessonFocus.matches(focusedLesson, date, lesson),
                            focusedLesson?.ongoing == true)
                    }
                } else {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Объявления · " + notices.size,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            TextButton(onClick = {
                                Store.markAllRead(this@MainActivity)
                                ScheduleWidget.renderAll(this@MainActivity)
                                rerender++
                            }) { Text("Прочитано") }
                        }
                    }
                    if (notices.isEmpty()) item {
                        Text("Объявлений на сайте сейчас нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(notices) { notice ->
                        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(if (notice.isNew) "● НОВОЕ" else "НГПУ",
                                    color = if (notice.isNew) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(notice.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    item {
                        OutlinedButton(onClick = {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Store.source(this@MainActivity))))
                        }) { Text("Открыть оригинал на сайте НГПУ ↗") }
                    }
                }
                item {
                    Text(
                        if (error.isNotBlank()) "Нет связи · сохранённая версия ($error)"
                        else "Проверено: $lastUpdate · автообновление примерно раз в 3 часа",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (picker != 0) {
            AlertDialog(onDismissRequest = { picker = 0 },
                title = { Text(if (picker == 1) "Выбери институт" else faculty?.title ?: "Выбери группу") },
                text = {
                    when {
                        working -> CircularProgressIndicator()
                        pickerError.isNotBlank() -> Text("Ошибка: $pickerError")
                        else -> LazyColumn(Modifier.heightIn(max = 410.dp)) {
                            if (picker == 1) items(institutes) { option ->
                                Text(option.title, modifier = Modifier.fillMaxWidth()
                                    .clickable { loadGroups(option) }
                                    .padding(vertical = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium)
                                HorizontalDivider()
                            }
                            else items(groupOptions) { option ->
                                Text(option.title, modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        Store.selectScheduleGroup(this@MainActivity, option.id, option.title)
                                        ScheduleWidget.renderAll(this@MainActivity)
                                        Sync.now(this@MainActivity)
                                        picker = 0
                                        rerender++
                                    }.padding(vertical = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium)
                                HorizontalDivider()
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { picker = 0 }) { Text("Закрыть") } },
                dismissButton = if (picker == 2) {
                    { TextButton(onClick = { picker = 1 }) { Text("Назад") } }
                } else null)
        }
        if (subgroupPicker) {
            AlertDialog(onDismissRequest = { subgroupPicker = false },
                title = { Text("Подгруппа") },
                text = {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(listOf("Все") + availableSubgroups) { name ->
                            Text(name, modifier = Modifier.fillMaxWidth().clickable {
                                Store.setGroup(this@MainActivity, name)
                                ScheduleWidget.renderAll(this@MainActivity)
                                subgroupPicker = false
                                rerender++
                            }.padding(12.dp))
                        }
                    }
                }, confirmButton = { TextButton(onClick = { subgroupPicker = false }) { Text("Отмена") } })
        }
    }

    @Composable
    private fun LessonCard(lesson: Lesson, date: LocalDate, highlighted: Boolean, ongoing: Boolean) {
        val data = SiteParser.details(lesson, date)
        val accent = Color(SiteParser.lessonColor(data.kind))
        Surface(shape = RoundedCornerShape(21.dp),
            tonalElevation = if (highlighted) 9.dp else 2.dp,
            color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                else BorderStroke(1.dp, accent.copy(alpha = .34f)),
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.fillMaxHeight().width(5.dp).background(accent))
                Column(Modifier.width(84.dp).padding(start = 12.dp, top = 14.dp)) {
                    Text(lesson.time.substringBefore('-'), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(lesson.time.substringAfter('-', ""), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = accent.copy(alpha = .18f),
                            shape = RoundedCornerShape(8.dp)) {
                            Text(SiteParser.shortKind(data.kind), fontSize = 12.sp,
                                color = accent, fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                        }
                        if (lesson.changed) {
                            Spacer(Modifier.width(7.dp))
                            Text("● ЗАМЕНА", color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (highlighted) {
                        Spacer(Modifier.height(7.dp))
                        Text(if (ongoing) "● ИДЁТ СЕЙЧАС" else "● СЛЕДУЮЩАЯ",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(data.subject, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(data.teacher, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Surface(shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("Ауд. " + data.room, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
