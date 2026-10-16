package ru.nspu.schedule

import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Live catalogue from the official university pages.
 * No group ids are hard-coded except the initial default in Store.
 */
data class Faculty(val id: Int, val title: String)
data class UniversityGroup(val id: Int, val title: String)

object GroupCatalog {
    private const val ROOT = "https://schedule.nspu.ru/"
    private fun page(path: String) = Jsoup.connect(ROOT + path)
        .userAgent("Mozilla/5.0 NSPUWidget/1.2")
        .timeout(20000)
        .get()

    fun faculties(): List<Faculty> = page("dep_index.php")
        .select("a[href*='group_index.php?dep=']")
        .mapNotNull { a ->
            val id = Regex("""[?&]dep=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            val title = a.text().trim()
            if (id == null || title.isBlank()) null else Faculty(id, title)
        }.distinctBy { it.id }

    fun groups(facultyId: Int): List<UniversityGroup> = page("group_index.php?dep=" + facultyId)
        .select("a[href*='group_shedule.php?id=']")
        .mapNotNull { a ->
            val id = Regex("""[?&]id=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            val title = a.text().trim()
            if (id == null || title.isBlank()) null else UniversityGroup(id, title)
        }.distinctBy { it.id }
}
