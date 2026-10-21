package ru.nspu.schedule

/** Release prose belongs in the app; build hashes and APK measurements stay on GitHub. */
internal object ReleaseNotes {
    fun display(raw: String): String {
        var fenced = false
        val lines = mutableListOf<String>()
        for (source in raw.replace("\r\n", "\n").lineSequence().take(500)) {
            val line = source.trim()
            if (line.contains("Измеренные характеристики APK", ignoreCase = true)) break
            if (line.startsWith("```")) { fenced = !fenced; continue }
            if (fenced || line.matches(Regex("#{1,2}\\s+NSPUWidget.*"))) continue
            val cleaned = line.replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
                .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                .replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("^[-*]\\s+"), "• ")
                .replace("**", "").replace("`", "")
            if (cleaned.isNotBlank() || lines.lastOrNull()?.isNotBlank() == true) lines += cleaned
        }
        val result = lines.joinToString("\n").trim()
        return if (result.length <= 5000) result else result.take(4900).substringBeforeLast('\n') +
            "\n\nПродолжение — на странице выпуска в GitHub."
    }
}
