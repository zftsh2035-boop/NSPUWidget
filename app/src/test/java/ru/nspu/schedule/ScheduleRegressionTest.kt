package ru.nspu.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleRegressionTest {
    @Test fun roomCannotBeTeacherInitials() {
        val a = Lesson(0, "8:30-10:00",
            "Математика, доц. А.А.Иванова, (л.: 1-16 нед. *), а. 404")
        assertEquals("404", SiteParser.details(a, LocalDate.of(2026, 10, 19)).room)
        val b = Lesson(0, "8:30-10:00",
            "История, ст. преп. В.И.Баяндин, (л.: 1-16 нед. *), а. 1п")
        assertEquals("1п", SiteParser.details(b, LocalDate.of(2026, 10, 19)).room)
        val c = Lesson(0, "8:30-10:00", "История, ст. преп. А.А.Юров")
        assertEquals("—", SiteParser.details(c, LocalDate.of(2026, 10, 19)).room)
        val d = Lesson(0, "8:30-10:00",
            "Физическая культура и спорт, Е.В.Шестаев, (п.з.: 1-16 нед.), а. Спортзал #1")
        assertEquals("Спортзал #1", SiteParser.details(d, LocalDate.of(2026, 10, 19)).room)
    }

    @Test fun subgroupChoicesBelongToThisGroup() {
        val two = listOf(
            Lesson(0, "12:10-13:40", "Иностранный язык, преподаватель, (л.з.: 3-14 нед.), а. 401, А1"),
            Lesson(0, "12:10-13:40", "Иностранный язык, преподаватель, (л.з.: 3-14 нед.), а. 402, А2"),
            Lesson(0, "14:10-15:40", "История, А.А.Юров, а. 403, А10")
        )
        assertEquals(listOf("А1", "А2"), SiteParser.languageOptions(two))
        assertTrue(SiteParser.languageOptions(listOf(Lesson(0, "8:30-10:00",
            "История, доц. А.А.Юров, а. 412"))).isEmpty())
        assertEquals(listOf("А2", "Немецкий"), SiteParser.languageOptions(listOf(
            Lesson(0, "12:10-13:40", "Иностранный язык, Петрова, а. 403, А2"),
            Lesson(0, "12:10-13:40", "Иностранный язык, Прокопьева, а. 415, Немецкий язык")
        )))
    }

    @Test fun focusChangesOnBreakAndOvernight() {
        val friday = LocalDate.of(2026, 10, 16)
        val monday = LocalDate.of(2026, 10, 19)
        val classes = mapOf(
            friday to listOf(
                Lesson(4, "8:30-10:00", "Пара первая"),
                Lesson(4, "10:10-11:40", "Пара вторая"),
                Lesson(4, "15:50-17:20", "Пара последняя")
            ),
            monday to listOf(Lesson(0, "8:30-10:00", "Первая в понедельник"))
        )
        val src: (LocalDate) -> List<Lesson> = { classes[it].orEmpty() }
        val live = LessonFocus.choose(LocalDateTime.of(2026, 10, 16, 9, 15), src)!!
        assertTrue(live.ongoing)
        assertEquals("Пара первая", live.lesson.raw)
        val breakTime = LessonFocus.choose(LocalDateTime.of(2026, 10, 16, 10, 5), src)!!
        assertFalse(breakTime.ongoing)
        assertEquals("Пара вторая", breakTime.lesson.raw)
        val night = LessonFocus.choose(LocalDateTime.of(2026, 10, 16, 18, 0), src)!!
        assertEquals(monday, night.date)
        assertEquals("Первая в понедельник", night.lesson.raw)
        assertTrue(LessonFocus.matches(night, monday, classes[monday]!!.first()))
        assertEquals(LocalDateTime.of(2026, 10, 19, 8, 30),
            LessonFocus.transitionAt(LocalDateTime.of(2026, 10, 16, 18, 0), night))
    }

    @Test fun groupAbbreviationsPreserved() {
        assertEquals("л.", SiteParser.shortKind("ЛЕКЦИЯ"))
        assertEquals("п.з.", SiteParser.shortKind("ПРАКТИКА"))
        assertEquals("л.з.", SiteParser.shortKind("ЛАБОРАТОРНАЯ"))
        assertEquals("зач.", SiteParser.shortKind("ЗАЧЁТ"))
        assertEquals("экз.", SiteParser.shortKind("ЭКЗАМЕН"))
    }
    @Test fun swipeDirectionAndThreshold() {
        assertEquals(1, DaySwipe.direction(-125f, 64f))
        assertEquals(-1, DaySwipe.direction(125f, 64f))
        assertEquals(0, DaySwipe.direction(-63f, 64f))
        assertEquals(0, DaySwipe.direction(63f, 64f))
        assertEquals(0, DaySwipe.direction(0f, 64f))
        assertEquals(0, DaySwipe.direction(Float.NaN, 64f))
    }

    @Test fun countdownAndProgress() {
        val day = LocalDate.of(2026,10,19)
        val item = Lesson(0, "8:30-10:00", "Алгебра, преподаватель, а. 405")
        val pending = FocusDisplay.of(LocalDateTime.of(2026,10,19,8,20),
            FocusedLesson(day,item,false))!!
        assertEquals("Через 10 мин", pending.label)
        assertEquals(0f, pending.progress, 0.001f)
        val live = FocusDisplay.of(LocalDateTime.of(2026,10,19,9,0),
            FocusedLesson(day,item,true))!!
        assertEquals("Осталось 1 ч", live.label)
        assertEquals(1f/3f, live.progress, 0.02f)
        val tomorrow = FocusDisplay.of(LocalDateTime.of(2026,10,18,17,0),
            FocusedLesson(day,item,false))!!
        assertTrue(tomorrow.label.startsWith("Через 15 ч"))
    }

    @Test fun semanticChangeLogUsesActualFields() {
        val date = LocalDate.of(2026, 10, 19)
        val term = LocalDate.of(2026, 9, 1)
        val old = listOf(Lesson(0,"8:30-10:00",
            "Алгебра, И.И.Петров, (п.з.: 1-16 нед.), а. 411"))
        val fresh = listOf(Lesson(0,"8:30-10:00",
            "Алгебра, И.И.Петров, (п.з.: 1-16 нед.), а. 213"))
        val diff = ChangeLog.diff(old,fresh,date,term)
        assertEquals(1,diff.size)
        assertTrue(diff[0].message.contains("411 → 213"))
        assertTrue(ChangeLog.diff(old,old,date,term).isEmpty())
    }

    @Test fun semesterHeaderAndWeekAreDetected() {
        assertEquals(LocalDate.of(2026,9,1),
            SiteParser.termStart("1 семестр Теоретическое обучение с 01.09.2026 г."))
        assertEquals(LocalDate.of(2027,2,1),
            SiteParser.termStart("2 семестр Теоретическое обучение с 01.02.2027 г."))
        assertEquals(1,SiteParser.academicWeek(LocalDate.of(2027,2,1),
            LocalDate.of(2027,2,1)))
        assertEquals(2,SiteParser.academicWeek(LocalDate.of(2027,2,8),
            LocalDate.of(2027,2,1)))
    }

    @Test fun githubReleaseVersionOrdering() {
        assertTrue(UpdateChecker.newer("v1.8", "1.7"))
        assertTrue(UpdateChecker.newer("1.10", "1.9"))
        assertTrue(UpdateChecker.newer("1.8.1", "1.8"))
        assertFalse(UpdateChecker.newer("1.8", "1.8"))
        assertFalse(UpdateChecker.newer("v1.7", "1.8"))
        assertFalse(UpdateChecker.newer("bad-version", "1.7"))
    }

    @Test fun coffeeWindowBetweenFirstAndThirdLesson() {
        val entries = DayTimeline.build(listOf(
            Lesson(0, "8:30-10:00", "Математика"),
            Lesson(0, "12:10-13:40", "Информатика")
        ))
        assertEquals(3, entries.size)
        assertTrue(entries[0] is DayRow.LessonRow)
        val gap = entries[1] as DayRow.WindowRow
        assertEquals("10:10", gap.start)
        assertEquals("11:40", gap.end)
        assertEquals(1, gap.periods)
        assertTrue(gap.message.contains("Может, по кофе?"))
        assertTrue(entries[2] is DayRow.LessonRow)
    }

    @Test fun noWindowForNormalBreakOrAfterLastLesson() {
        val result = DayTimeline.build(listOf(
            Lesson(0, "8:30-10:00", "Математика"),
            Lesson(0, "10:10-11:40", "История"),
            Lesson(0, "12:10-13:40", "Физика")
        ))
        assertEquals(3, result.size)
        assertTrue(result.all { it is DayRow.LessonRow })
        assertEquals(0, DayTimeline.build(emptyList()).size)
        assertEquals(1, DayTimeline.build(listOf(Lesson(0,"12:10-13:40","Физика"))).size)
    }

    @Test fun twoConsecutiveMissingLessonsAreOneWindow() {
        val result = DayTimeline.build(listOf(
            Lesson(2, "8:30-10:00", "Математика"),
            Lesson(2, "14:10-15:40", "История")
        ))
        val gap = result[1] as DayRow.WindowRow
        assertEquals("10:10", gap.start)
        assertEquals("13:40", gap.end)
        assertEquals(2, gap.periods)
    }

    @Test fun sameTimeSubgroupsDoNotIntroduceFalseWindows() {
        val result = DayTimeline.build(listOf(
            Lesson(0,"8:30-10:00","Иностранный язык, А7"),
            Lesson(0,"8:30-10:00","Иностранный язык, А8"),
            Lesson(0,"10:10-11:40","Информатика")
        ))
        assertEquals(3, result.size)
        assertTrue(result.all { it is DayRow.LessonRow })
    }

    @Test fun unknownLectureTimesDoNotCreateGuessWindows() {
        val result = DayTimeline.build(listOf(
            Lesson(0,"8:30-10:00","Первая пара"),
            Lesson(0,"13:55-15:25","Особое время"),
            Lesson(0,"15:50-17:20","Пятая пара")
        ))
        assertTrue(result.none { it is DayRow.WindowRow })
    }

}
