package ru.nspu.schedule

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.SharedPreferences
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Sea = Color(0xFF008D80)
private val Blue = Color(0xFF2563DB)
private val Violet = Color(0xFF8E53DA)
private val Amber = Color(0xFFC07816)

class MainActivity : ComponentActivity() {
    private var scheduleRevision by mutableIntStateOf(0)
    private var screenActive by mutableStateOf(false)
    private var navigationRequest by mutableLongStateOf(0L)
    private var handledNavigation = 0L
    private var incomingNote: NoteEditorRequest? = null
    private var offerWidgetIntro by mutableStateOf(false)
    private val scheduleKeys = setOf("lessons", "notices", "updated", "error", "change_history",
        "group_id", "subgroup", "offset", "selected_date", "schedule_links", "term_start", "favorites", "primary_group_id")
    private val scheduleListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in scheduleKeys || key == "data_revision_${Store.scheduleGroupId(this)}") {
            if (Looper.myLooper() == Looper.getMainLooper()) scheduleRevision++
            else runOnUiThread { scheduleRevision++ }
        }
    }

    private fun refreshWidgets() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { ScheduleWidget.renderAll(applicationContext) }
                .onFailure { android.util.Log.e("NSPUWidget", "Widget refresh failed", it) }
        }
    }

    private fun handleWidgetIntent(value: Intent) {
        if (!value.hasExtra("open_group_id") && !value.getBooleanExtra("show_favorites", false)) return
        lifecycleScope.launch(Dispatchers.IO) {
            Store.initialize(applicationContext)
            val id = value.getIntExtra("open_group_id", -1)
            Favorites.find(applicationContext, id)?.let {
                Store.selectScheduleGroup(applicationContext, it.id, it.title)
                if (Store.data(applicationContext, it.id).cached().isEmpty()) Sync.now(applicationContext, it.id)
            }
            val note = runCatching { NotesNavigation.read(applicationContext, value) }
                .onFailure { android.util.Log.e("NSPUWidget", "Cannot open note", it) }.getOrNull()
            if (note != null) Store.appView(applicationContext).selectDate(note.target.date)
            withContext(Dispatchers.Main) {
                setIntent(value)
                incomingNote = note
                navigationRequest = System.nanoTime()
                scheduleRevision++
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        screenActive = true
        scheduleRevision++
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { NoteReminders.reschedule(applicationContext) }
                .onFailure { android.util.Log.e("NSPUWidget", "Cannot restore reminders", it) }
        }
    }

    override fun onPause() {
        screenActive = false
        super.onPause()
    }

    override fun onDestroy() {
        getSharedPreferences("schedule", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(scheduleListener)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences("schedule", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(scheduleListener)
        lifecycleScope.launch(Dispatchers.IO) {
            Store.initialize(applicationContext)
            Sync.schedule(applicationContext)
            if (Store.cached(applicationContext).isEmpty() || Store.missingScheduleLinks(applicationContext)) {
                Sync.now(applicationContext)
            }
            // Refresh existing launcher instances immediately after an APK upgrade.
            ScheduleWidget.renderAll(applicationContext)
            if (savedInstanceState == null && !intent.hasExtra("open_group_id") &&
                !intent.getBooleanExtra("show_notices", false) && !intent.getBooleanExtra("show_changes", false)) {
                val offer = runCatching { WidgetSetup.shouldOffer(applicationContext) }.getOrDefault(false)
                withContext(Dispatchers.Main) { offerWidgetIntro = offer }
            }
        }
        if (savedInstanceState == null) handleWidgetIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent {
            var currentTheme by remember { mutableStateOf(AppSettings.theme(this@MainActivity)) }
            val systemDark = isSystemInDarkTheme()
            val dark = when (currentTheme) {
                AppSettings.LIGHT -> false
                AppSettings.DARK, AppSettings.AMOLED, AppSettings.NEON -> true
                else -> systemDark
            }
            val base = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme(primary = Color(0xFF9CC5FF), secondary = Color(0xFF5DE0C9))
                else -> lightColorScheme(primary = Blue, secondary = Sea, background = Color(0xFFF6F8FE))
            }
            val colors = if (currentTheme == AppSettings.NEON) {
                // Fluorescent accents on a true-black AMOLED canvas, not the Android wallpaper palette.
                darkColorScheme(
                    primary = Color(0xFF39FF14), onPrimary = Color.Black,
                    primaryContainer = Color(0xFF102700), onPrimaryContainer = Color(0xFFCBFFAE),
                    secondary = Color(0xFF00F5FF), onSecondary = Color.Black,
                    secondaryContainer = Color(0xFF003137), onSecondaryContainer = Color(0xFFA7F9FF),
                    tertiary = Color(0xFFFF00EB), onTertiary = Color.Black,
                    tertiaryContainer = Color(0xFF3A0038), onTertiaryContainer = Color(0xFFFFBBF8),
                    error = Color(0xFFFF4265), onError = Color.Black,
                    background = Color.Black, onBackground = Color.White,
                    surface = Color.Black, onSurface = Color.White,
                    surfaceVariant = Color(0xFF141414), onSurfaceVariant = Color(0xFFC8EFCE),
                    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF090909),
                    surfaceContainer = Color(0xFF101010), surfaceContainerHigh = Color(0xFF161616),
                    surfaceContainerHighest = Color(0xFF202020),
                    surfaceDim = Color.Black, surfaceBright = Color(0xFF242424),
                    outline = Color(0xFF39FF14), outlineVariant = Color(0xFF215F19)
                )
            } else if (currentTheme == AppSettings.AMOLED) base.copy(
                background = Color.Black, surface = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceContainerLow = Color(0xFF0C0C0C),
                surfaceContainer = Color(0xFF121212),
                surfaceContainerHigh = Color(0xFF191919),
                surfaceContainerHighest = Color(0xFF222222),
                surfaceDim = Color.Black, surfaceBright = Color(0xFF202020)
            ) else base
            val pageStack = remember { mutableStateListOf<OfficialSchedulePage>() }
            val pageStateHolder = rememberSaveableStateHolder()
            LaunchedEffect(navigationRequest) {
                if (navigationRequest > 0) pageStack.clear()
            }
            MaterialTheme(colorScheme = colors) {
                val top = pageStack.lastOrNull()
                if (top == null) {
                    pageStateHolder.SaveableStateProvider("home") {
                        AppScreen(currentTheme,
                            changeTheme = { value ->
                                AppSettings.setTheme(this@MainActivity, value)
                                currentTheme = value
                            },
                            onOpenSchedule = { page -> pageStack.add(page) }
                        )
                    }
                } else {
                    pageStateHolder.SaveableStateProvider(top.url) {
                        NativePublicSchedule(
                            page = top,
                            onBack = { if (pageStack.isNotEmpty()) pageStack.removeAt(pageStack.lastIndex) },
                            onOpenReference = { newPage -> pageStack.add(newPage) }
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppScreen(currentTheme: String, changeTheme: (String) -> Unit,
        onOpenSchedule: (OfficialSchedulePage) -> Unit) {
        var rerender by remember { mutableIntStateOf(0) }
        val refreshTick = rerender + scheduleRevision // Observe data revision without 15-second global polling.
        var favoritePicker by remember { mutableStateOf(false) }
        var notesVisible by rememberSaveable { mutableStateOf(false) }
        var widgetHelp by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(offerWidgetIntro) {
            if (offerWidgetIntro) {
                widgetHelp = true
                offerWidgetIntro = false
            }
        }
        var editingNote by rememberSaveable(stateSaver = NoteEditorRequestSaver) {
            mutableStateOf<NoteEditorRequest?>(null)
        }
        var viewingNote by rememberSaveable(stateSaver = NoteEditorRequestSaver) {
            mutableStateOf<NoteEditorRequest?>(null)
        }
        var savingNote by remember { mutableStateOf(false) }
        var noteError by remember { mutableStateOf("") }
        var notesListError by remember { mutableStateOf("") }
        // AGP 8.7/K2 false positive: issuetracker.google.com/issues/349411310.
        // The producer explicitly assigns this.value below.
        @Suppress("ProduceStateDoesNotAssignValue")
        val savedNotes by produceState<List<PersonalNote>?>(null, notesVisible, refreshTick) {
            if (notesVisible) {
                val result = withContext(Dispatchers.IO) { runCatching { Notes.all(applicationContext) } }
                notesListError = if (result.isFailure) "Не удалось прочитать заметки. Попробуй открыть их ещё раз." else ""
                this.value = result.getOrNull()
            }
        }

        fun writeNote(request: NoteEditorRequest, text: String = "", delete: Boolean = false,
            options: NoteOptions = request.note?.options ?: NoteOptions()) {
            if (savingNote) return
            savingNote = true
            noteError = ""
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        if (delete) request.note?.let { Notes.delete(applicationContext, it) }
                        else Notes.save(applicationContext, request, text, options)
                    }
                }
                savingNote = false
                if (result.isSuccess) {
                    editingNote = null
                    viewingNote = null
                    rerender++
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { ScheduleWidget.renderGroup(applicationContext, request.target.groupId) }
                            .onFailure { android.util.Log.e("NSPUWidget", "Note widget refresh failed", it) }
                    }
                } else {
                    android.util.Log.e("NSPUWidget", "Personal note write failed", result.exceptionOrNull())
                    noteError = "Не удалось сохранить. Текст остался здесь — попробуй ещё раз."
                }
            }
        }
        var pendingFocusGroup by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Int?>(null) }
        val selectedGroupId = remember(refreshTick) { Store.scheduleGroupId(this@MainActivity) }
        // Same AGP/K2 lint issue; keep the suppression local to this state producer.
        @Suppress("ProduceStateDoesNotAssignValue")
        val snapshot by key(selectedGroupId) {
            produceState<HomeScheduleSnapshot?>(null, refreshTick) {
                this.value = withContext(Dispatchers.IO) { Store.homeSnapshot(applicationContext, selectedGroupId) }
            }
        }
        var availableUpdate by remember { mutableStateOf<AppUpdate?>(null) }
        var showUpdateDialog by remember { mutableStateOf(false) }
        var channel by remember { mutableStateOf(AppSettings.channel(this@MainActivity)) }
        var animateHighlight by remember { mutableStateOf(AppSettings.animateHighlight(this@MainActivity)) }
        var autoScroll by remember { mutableStateOf(AppSettings.autoScroll(this@MainActivity)) }
        var updatePrompts by remember { mutableStateOf(AppSettings.updatePrompts(this@MainActivity)) }
        var showAbout by remember { mutableStateOf(false) }
        var widgetTheme by remember { mutableStateOf(AppSettings.widgetTheme(this@MainActivity)) }
        var checkCounter by remember { mutableIntStateOf(0) }
        var manualCheck by remember { mutableStateOf(false) }
        LaunchedEffect(channel, checkCounter) {
            val force = manualCheck
            manualCheck = false
            availableUpdate = null
            UpdateChecker.check(this@MainActivity, force = force) { update ->
                availableUpdate = update
                if (update != null && updatePrompts &&
                    !UpdateChecker.previouslyPrompted(this@MainActivity, update.key)) {
                    UpdateChecker.markPrompted(this@MainActivity, update.key)
                    showUpdateDialog = true
                }
            }
        }
        var selectedTab by androidx.compose.runtime.saveable.rememberSaveable {
            mutableIntStateOf(when {
                intent.getBooleanExtra("show_changes", false) -> 2
                intent.getBooleanExtra("show_notices", false) -> 1
                else -> 0
            })
        }
        LaunchedEffect(navigationRequest) {
            if (navigationRequest > 0 && handledNavigation != navigationRequest) {
                handledNavigation = navigationRequest
                // A launcher intent is an event. Do not replay its editor when
                // the home composition is rebuilt for another group.
                val noteRequest = incomingNote
                incomingNote = null
                selectedTab = if (intent.getBooleanExtra("show_notices", false)) 1 else 0
                favoritePicker = intent.getBooleanExtra("show_favorites", false)
                editingNote = noteRequest?.takeIf { it.note == null }
                viewingNote = noteRequest?.takeIf { it.note != null }
                noteError = ""
                notesVisible = false
                widgetHelp = false
                offerWidgetIntro = false
                pendingFocusGroup = if (noteRequest == null)
                    intent.getIntExtra("open_group_id", -1).takeIf { it > 0 } else null
            }
        }
        val listState = rememberLazyListState()
        // Pass State without reading it here: scrolling must not recompose AppScreen.
        val listScrolling = remember { derivedStateOf { listState.isScrollInProgress } }
        val home = snapshot
        if (home == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }
        val date = home.date
        val lessons = home.lessons
        val timeline = home.timeline
        val notices = home.notices
        val group = home.group
        val subgroup = home.subgroup
        val availableSubgroups = home.availableSubgroups
        val focusedLesson = home.focus
        LaunchedEffect(screenActive, home.nextNoteBoundary) {
            if (screenActive) home.nextNoteBoundary?.let {
                delay((it - System.currentTimeMillis()).coerceAtLeast(1L))
                scheduleRevision++
            }
        }
        LaunchedEffect(screenActive, focusedLesson, refreshTick) {
            if (screenActive) {
                val at = LocalDateTime.now(ZONE)
                val next = LessonFocus.transitionAt(at, focusedLesson)
                val wait = if (next != null) Duration.between(at, next).toMillis() + 1200L
                    else 60L * 60L * 1000L
                delay(wait.coerceIn(1200L, 60L * 60L * 1000L))
                scheduleRevision++
            }
        }
        // Prewarm only the next teacher's public timetable while a student is
        // already reading their group schedule. One bounded request, not a crawl
        // of all university teachers, and never on the Compose main thread.
        LaunchedEffect(home.cards, screenActive, selectedTab) {
            if (!screenActive || selectedTab != 0) return@LaunchedEffect
            delay(1700)
            val page = home.cards.values.firstNotNullOfOrNull { it.teacherPage }
                ?: return@LaunchedEffect
            withContext(Dispatchers.IO) {
                runCatching {
                    PublicTimetableRepository.load(this@MainActivity.applicationContext, page, 0)
                }
            }
        }
        val changes = home.changes
        val lastUpdate = home.updated
        val error = home.error
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

        // Scroll only when the focused lesson/day changes; don't fight user scrolling.
        val focusIndex = timeline.indexOfFirst { row ->
            row is DayRow.LessonRow && LessonFocus.matches(focusedLesson, date, row.lesson)
        }
        LaunchedEffect(selectedTab, date, focusIndex, home.groupId, pendingFocusGroup) {
            if (selectedTab == 0 && (autoScroll || pendingFocusGroup == home.groupId) && focusIndex >= 0) {
                val prefix = 3 + (if (availableSubgroups.isNotEmpty()) 1 else 0) +
                    (if (notices.isNotEmpty()) 1 else 0)
                listState.scrollToItem(prefix + focusIndex)
                pendingFocusGroup = null
            } else if (selectedTab != 0) listState.scrollToItem(0)
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val favorite = home.favorites.firstOrNull { it.id == home.groupId }
                        Column(Modifier.fillMaxWidth().clickable { favoritePicker = true }
                            .semantics { contentDescription = "Избранные группы" }) {
                            Text("Расписание НГПУ", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(favorite?.displayName ?: group.substringBefore(" "),
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Text("  ☆", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { notesVisible = true }) {
                            Icon(painterResource(R.drawable.ic_note), contentDescription = "Мои заметки")
                        }
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
                    NavigationBarItem(selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Text("↺", fontSize = 22.sp) }, label = { Text("Изменения") })
                    NavigationBarItem(selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Text("⚙", fontSize = 22.sp) }, label = { Text("Настройки") })
                }
            }
        ) { insets ->
            // A horizontal swipe navigates days without interfering with normal vertical scrolling.
            val daySwipeModifier = if (selectedTab == 0) Modifier.pointerInput(selectedTab) {
                var draggedPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { draggedPx = 0f },
                    onHorizontalDrag = { change, amount ->
                        draggedPx += amount
                        change.consume()
                    },
                    onDragCancel = { draggedPx = 0f },
                    onDragEnd = {
                        val direction = DaySwipe.direction(draggedPx, 64.dp.toPx())
                        if (direction != 0) {
                            Store.offset(this@MainActivity, direction)
                            rerender++
                        }
                        draggedPx = 0f
                    }
                )
            } else Modifier
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(insets).then(daySwipeModifier),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                        DateStrip(date) { chosen ->
                            Store.appView(this@MainActivity).selectDate(chosen)
                            rerender++
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(date.format(DateTimeFormatter.ofPattern("d MMMM, EEEE",
                                    Locale.forLanguageTag("ru-RU"))),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).clickable {
                                    pickNoteDate(this@MainActivity, date) { chosen ->
                                        Store.appView(this@MainActivity).selectDate(chosen); rerender++
                                    }
                                }.semantics { contentDescription = "Выбрать дату в календаре" })
                            TextButton(onClick = { Store.jump(this@MainActivity, 0); rerender++ }) { Text("Сегодня") }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Пары: ${lessons.size}", modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = {
                                    noteError = ""
                                    editingNote = NoteEditorRequest(NoteTarget.day(home.groupId, date))
                                }) { Text("+ Заметка на день") }
                            }
                            if (home.dayNotes.isNotEmpty()) DayNotesTiles(home.dayNotes) { note ->
                                viewingNote = NoteEditorRequest(note.target, note)
                            }
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
                    items(timeline, contentType = { row ->
                        if (row is DayRow.LessonRow) "lesson" else "window"
                    }, key = { row ->
                        when (row) {
                            is DayRow.LessonRow -> "lesson:" + row.lesson.time + ":" + row.lesson.raw
                            is DayRow.WindowRow -> "window:" + row.start + ":" + row.end
                        }
                    }) { row ->
                        when (row) {
                            is DayRow.LessonRow -> LessonCard(home.cards.getValue(row.lesson),
                                LessonFocus.matches(focusedLesson, date, row.lesson),
                                focusedLesson?.ongoing == true, animateHighlight,
                                screenActive, currentTheme == AppSettings.NEON,
                                listScrolling, onOpenSchedule, onOpenNote = { card ->
                                    card.noteTarget?.let { target ->
                                        noteError = ""
                                        val request = NoteEditorRequest(target, card.note)
                                        if (card.note == null) editingNote = request else viewingNote = request
                                    }
                                })
                            is DayRow.WindowRow -> GapCard(row)
                        }
                    }
                    if (home.notesError.isNotBlank()) item {
                        Text(home.notesError, color = MaterialTheme.colorScheme.error)
                    }
                } else if (selectedTab == 1) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Объявления · " + notices.size,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            TextButton(onClick = {
                                Store.markAllRead(this@MainActivity)
                                refreshWidgets()
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
                } else if (selectedTab == 2) {
                    item {
                        Text("История изменений", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                    }
                    if (changes.isEmpty()) item {
                        Text("Подтверждённых изменений пока нет. После первой синхронизации приложение будет сохранять замену аудитории, преподавателя, времени и типа пары.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(changes) { entry ->
                        Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(entry, modifier = Modifier.padding(14.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    item {
                        Text("Настройки", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                    }
                    item {
                        SettingsPanel(title = "Виджет на главном экране") {
                            Text("Пары, аудитория и заметки — без открытия приложения.",
                                style = MaterialTheme.typography.bodySmall)
                            FilledTonalButton(onClick = { widgetHelp = true }) { Text("Добавить виджет") }
                        }
                    }
                    item {
                        SettingsPanel(title = "Канал обновлений") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = channel == AppSettings.STABLE,
                                    onClick = {
                                        channel = AppSettings.STABLE
                                        AppSettings.setChannel(this@MainActivity, channel)
                                        checkCounter++
                                    }, label = { Text("Stable") })
                                FilterChip(selected = channel == AppSettings.DEV,
                                    onClick = {
                                        channel = AppSettings.DEV
                                        AppSettings.setChannel(this@MainActivity, channel)
                                        checkCounter++
                                    }, label = { Text("Dev") })
                            }
                            Text(if (channel == AppSettings.STABLE)
                                "Проверенные выпуски из GitHub Releases."
                                else "Тестовые сборки из GitHub Actions. Для загрузки APK может понадобиться вход в GitHub.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val update = availableUpdate
                            OutlinedButton(onClick = {
                                val destination = update?.releaseUrl ?: if (channel == AppSettings.STABLE)
                                    UpdateChecker.RELEASES else UpdateChecker.DEV_BUILDS
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (update != null) "Доступна версия " + update.version + " ↗"
                                    else if (channel == AppSettings.STABLE) "Релизы GitHub ↗"
                                    else "Сборки dev на GitHub ↗")
                            }
                            TextButton(onClick = {
                                manualCheck = true
                                checkCounter++
                            }) { Text("Проверить обновления") }
                            SettingsToggle("Предлагать новые версии", updatePrompts) {
                                updatePrompts = it
                                AppSettings.setUpdatePrompts(this@MainActivity, it)
                                if (!it) showUpdateDialog = false
                            }
                        }
                    }
                    item {
                        SettingsPanel(title = "Оформление") {
                            Text("Тема приложения",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                            AppearanceChoices(currentTheme, changeTheme)
                            Text("Тема виджета",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 10.dp))
                            AppearanceChoices(widgetTheme) { selected ->
                                widgetTheme = selected
                                AppSettings.setWidgetTheme(this@MainActivity, selected)
                            }
                            Text(
                                "Темы приложения и виджета настраиваются независимо. " +
                                "Системная тема следует Android; Dynamic Color используется на Android 12+. " +
                                "AMOLED сохраняет системный акцент, но делает фон полностью чёрным. " +
                                "Neon — чёрный фон с кислотными акцентами.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                                        item {
                        SettingsPanel(title = "Расписание") {
                            SettingsToggle("Плавная пульсация выбранной пары", animateHighlight) {
                                animateHighlight = it
                                AppSettings.setAnimateHighlight(this@MainActivity, it)
                            }
                            SettingsToggle("Автопрокрутка к ближайшей паре", autoScroll) {
                                autoScroll = it
                                AppSettings.setAutoScroll(this@MainActivity, it)
                            }
                            Text("Виджет обновляется только при смене пары, без постоянной анимации.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item {
                        ElevatedCard(modifier = Modifier.fillMaxWidth(),
                            onClick = { showAbout = true }) {
                            Row(Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("ⓘ", fontSize = 24.sp,
                                    color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("О приложении",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold)
                                    Text("Версия " + UpdateChecker.installedVersion(this@MainActivity) +
                                        " · GitHub · исходный код",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("›", fontSize = 24.sp)
                            }
                        }
                    }
                }
                if (selectedTab != 3) item {
                    Text(
                        if (error.isNotBlank()) "Нет связи · сохранённая версия ($error)"
                        else "Проверено: $lastUpdate · автообновление примерно раз в 3 часа",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (showAbout) {
            AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("О приложении") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("NSPUWidget · версия " + UpdateChecker.installedVersion(this@MainActivity))
                        Text("Неофициальное приложение расписания НГПУ. Данные берутся с сайта университета.")
                        Text("Исходный код, история изменений и релизы доступны на GitHub.")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showAbout = false
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/zftsh2035-boop/NSPUWidget")))
                    }) { Text("Открыть GitHub ↗") }
                },
                dismissButton = {
                    TextButton(onClick = { showAbout = false }) { Text("Закрыть") }
                }
            )
        }
        if (showUpdateDialog && availableUpdate != null) {
            val update = availableUpdate!!
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                title = { Text("Доступно обновление") },
                text = {
                    Text(if (update.channel == AppSettings.DEV)
                        "Новая тестовая сборка " + update.version +
                            " доступна в GitHub Actions. На странице сборки открой Artifacts; может потребоваться вход в GitHub. Эту сборку повторно предлагать не будем."
                        else "Версия " + update.version +
                            " доступна в GitHub Releases. Установленная: " +
                            UpdateChecker.installedVersion(this@MainActivity) +
                            ". Если не хочешь устанавливать сейчас, повторно предлагать эту версию не будем.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showUpdateDialog = false
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse(update.apkUrl.ifBlank { update.releaseUrl })))
                    }) { Text(if (update.channel == AppSettings.DEV) "Открыть сборку" else "Скачать") }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdateDialog = false }) { Text("Не сейчас") }
                }
            )
        }

        if (widgetHelp) WidgetSetupDialog(onDismiss = {
            AppSettings.setWidgetIntroSeen(this@MainActivity)
            widgetHelp = false
        }, onPin = { WidgetSetup.requestPin(this@MainActivity) })

        if (notesVisible) NotesListDialog(savedNotes,
            home.favorites.associate { it.id to it.displayName }, notesListError,
            onDismiss = { notesVisible = false }, onOpen = { note ->
                noteError = ""
                viewingNote = NoteEditorRequest(note.target, note)
            }, onAdd = {
                notesVisible = false
                noteError = ""
                editingNote = NoteEditorRequest(NoteTarget.day(home.groupId, date))
            })
        viewingNote?.note?.let { note ->
            NoteViewer(note.copy(target = viewingNote?.target ?: note.target),
                home.favorites.firstOrNull { it.id == note.target.groupId }?.displayName
                    ?: "Группа №${note.target.groupId}",
                onDismiss = { viewingNote = null }, onEdit = {
                    noteError = ""
                    editingNote = viewingNote
                    viewingNote = null
                })
        }
        editingNote?.let { request ->
            NoteEditor(request,
                home.favorites.firstOrNull { it.id == request.target.groupId }?.displayName
                    ?: "Группа №${request.target.groupId}",
                savingNote, noteError, refreshTick, onDismiss = { editingNote = null },
                onSave = { draft -> writeNote(request.copy(target = draft.target), draft.text, options = draft.options) },
                onDelete = { writeNote(request, delete = true) })
        }

        if (favoritePicker) {
            FavoritesDialog(home.favorites, home.groupId, home.primaryGroupId,
                onDismiss = { favoritePicker = false },
                onSelect = { favorite ->
                    favoritePicker = false
                    pendingFocusGroup = favorite.id
                    selectedTab = 0
                    Store.selectScheduleGroup(this@MainActivity, favorite.id, favorite.title)
                    rerender++
                    lifecycleScope.launch(Dispatchers.IO) {
                        val data = Store.data(applicationContext, favorite.id)
                        if (data.needsRefresh()) Sync.now(applicationContext, favorite.id)
                    }
                },
                onAdd = { favoritePicker = false; loadInstitutes() },
                onRename = { favorite, name ->
                    Favorites.rename(this@MainActivity, favorite.id, name)
                    refreshWidgets(); rerender++
                },
                onRemove = { favorite ->
                    Favorites.remove(this@MainActivity, favorite.id)
                    pendingFocusGroup = Store.scheduleGroupId(this@MainActivity)
                    refreshWidgets(); rerender++
                },
                onMakePrimary = { favorite ->
                    Favorites.makePrimary(this@MainActivity, favorite.id)
                    refreshWidgets(); rerender++
                })
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
                                        pendingFocusGroup = option.id
                                        selectedTab = 0
                                        refreshWidgets()
                                        Sync.now(this@MainActivity, option.id)
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
                                refreshWidgets()
                                subgroupPicker = false
                                rerender++
                            }.padding(12.dp))
                        }
                    }
                }, confirmButton = { TextButton(onClick = { subgroupPicker = false }) { Text("Отмена") } })
        }
    }

    @Composable
    private fun SettingsPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }

    @Composable
    private fun AppearanceChoices(selected: String, onSelect: (String) -> Unit) {
        // Two compact rows of pills, matching the reference design. No oversized radio list.
        val choices = listOf(
            listOf(
                AppSettings.SYSTEM to "Системная",
                AppSettings.LIGHT to "Светлая",
                AppSettings.DARK to "Тёмная"
            ),
            listOf(
                AppSettings.AMOLED to "AMOLED",
                AppSettings.NEON to "Neon"
            )
        )
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            choices.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (value, caption) ->
                        val chosen = selected == value
                        Surface(
                            modifier = Modifier.weight(1f)
                                .height(44.dp)
                                .clickable { onSelect(value) },
                            shape = RoundedCornerShape(13.dp),
                            color = if (chosen) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                1.dp, if (chosen) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(caption, fontSize = 13.sp,
                                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun GapCard(gap: DayRow.WindowRow) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(19.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .48f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = .38f))
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("☕", fontSize = 26.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(gap.heading + " · " + gap.time,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(3.dp))
                    Text(gap.message, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }

}
