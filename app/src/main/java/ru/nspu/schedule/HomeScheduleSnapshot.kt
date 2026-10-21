package ru.nspu.schedule

import java.time.LocalDate

/** One coherent UI snapshot, prepared on a worker dispatcher instead of during scroll. */
internal data class HomeScheduleSnapshot(
    val date: LocalDate,
    val lessons: List<Lesson>,
    val cards: Map<Lesson, LessonPresentation>,
    val timeline: List<DayRow>,
    val focus: FocusedLesson?,
    val notices: List<Notice>,
    val group: String,
    val subgroup: String,
    val availableSubgroups: List<String>,
    val changes: List<String>,
    val updated: String,
    val error: String,
    val groupId: Int,
    val favorites: List<FavoriteGroup>,
    val primaryGroupId: Int,
    val notesError: String = "",
    val dayNotes: List<PersonalNote> = emptyList(),
    val nextNoteBoundary: Long? = null,
    val assessments: List<AssessmentEntry> = emptyList(),
    val automaticUpcomingDay: Boolean = false,
    val cachedLessonCount: Int = 0
)
