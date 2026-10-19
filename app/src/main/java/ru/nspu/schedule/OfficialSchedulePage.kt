package ru.nspu.schedule

import java.net.URI

/**
 * Typed navigation destination based on actual public links from NSPU.
 * No arbitrary external URLs or authenticated student account pages.
 */
data class OfficialSchedulePage(val kind: Kind, val id: Int, val title: String) {
    enum class Kind(val path: String, val defaultTitle: String) {
        TEACHER("teacher_shedule.php", "Преподаватель"),
        ROOM("room_shedule.php", "Аудитория"),
        GROUP("group_shedule.php", "Группа"),
        EXTRAMURAL_GROUP("zgroup_shedule.php", "Группа (заочно)")
    }

    val url: String get() = "https://schedule.nspu.ru/${kind.path}?id=$id"

    companion object {
        fun fromUrl(input: String, label: String = ""): OfficialSchedulePage? {
            val uri = runCatching { URI(input) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host != "schedule.nspu.ru" ||
                uri.port != -1 || uri.userInfo != null || uri.fragment != null) return null
            val kind = Kind.entries.firstOrNull { uri.path == "/" + it.path } ?: return null
            val match = Regex("""id=(\d{1,9})""").matchEntire(uri.rawQuery ?: "") ?: return null
            val id = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return OfficialSchedulePage(kind, id, label.ifBlank { kind.defaultTitle })
        }
    }
}
