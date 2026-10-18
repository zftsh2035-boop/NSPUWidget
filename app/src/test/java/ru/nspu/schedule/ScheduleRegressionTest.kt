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

}
