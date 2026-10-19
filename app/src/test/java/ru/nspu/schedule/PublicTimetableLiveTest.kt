package ru.nspu.schedule

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/** Run with NSPU_LIVE=true to verify current public HTML, not just synthetic fixtures. */
class PublicTimetableLiveTest {
    @Test fun parsesPublicTeacherAndRoomPagesFromUniversity() {
        assumeTrue(System.getenv("NSPU_LIVE") == "true")
        for (page in listOf(
            OfficialSchedulePage(OfficialSchedulePage.Kind.TEACHER,452,"Азарова"),
            OfficialSchedulePage(OfficialSchedulePage.Kind.ROOM,159,"Ауд. 206")
        )) {
            val doc = Jsoup.connect(page.url).userAgent("Mozilla/5.0")
                .timeout(20_000).get()
            val parsed = PublicTimetableParser.parse(doc, page, 0,
                LocalDate.of(2026,10,19))
            println("LIVE " + page.kind + ": " + parsed.sessions.size +
                " events, week " + parsed.week +
                ", weekly=" + parsed.sessions.count { it.section == PublicSection.WEEKLY } +
                ", dated=" + parsed.sessions.count { it.section == PublicSection.DATED } +
                ", exam=" + parsed.sessions.count { it.section == PublicSection.EXAMS })
            assertTrue(page.kind.toString(), parsed.week > 0)
            assertTrue(page.kind.toString(), parsed.sessions.isNotEmpty())
            assertTrue(page.kind.toString(),
                parsed.sessions.any { it.section == PublicSection.WEEKLY })
            assertTrue("Must preserve group/room/teacher hyperlinks",
                parsed.sessions.any { it.links.isNotEmpty() })
            if (page.kind == OfficialSchedulePage.Kind.TEACHER) {
                assertTrue(parsed.sessions.any { it.section == PublicSection.EXAMS })
                assertTrue(parsed.sessions.any { it.section == PublicSection.DATED })
            }
        }
    }
}
