package ru.nspu.schedule

import java.time.LocalDate
import androidx.compose.runtime.Immutable

/** All parsing and navigation resolution happens before the row enters LazyColumn. */
@Immutable
internal data class LessonPresentation(
    val lesson: Lesson,
    val date: LocalDate,
    val subject: String,
    val teacher: String,
    val room: String,
    val kind: String,
    val kindLabel: String,
    val start: String,
    val end: String,
    val teacherPage: OfficialSchedulePage?,
    val roomPage: OfficialSchedulePage?
)

internal fun Lesson.present(date: LocalDate, termStart: LocalDate,
    links: ScheduleLinks.Index): LessonPresentation {
    val info = SiteParser.details(this, date, termStart)
    val teacher = links.teacher(raw)
    return LessonPresentation(this, date, info.subject, teacher?.label ?: info.teacher,
        info.room, info.kind, SiteParser.shortKind(info.kind),
        time.substringBefore('-'), time.substringAfter('-', ""),
        teacher?.let { OfficialSchedulePage.fromUrl(it.url, it.label) },
        links.room(info.room)?.let { OfficialSchedulePage.fromUrl(it.url, "Ауд. " + info.room) })
}
