package ru.nspu.schedule

import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesTest {
    @Test fun keepsWarningsAndReadableChanges() {
        val actual = ReleaseNotes.display("# NSPUWidget 2.12\n\nПеред установкой сохрани заметки.\n\n- **Виджет** обновляется.\n- [Подробности](https://example.com) доступны.")
        assertEquals("Перед установкой сохрани заметки.\n\n• Виджет обновляется.\n• Подробности доступны.", actual)
    }
    @Test fun buildAppendixStaysOnGithub() {
        val actual = ReleaseNotes.display("## Что изменилось\n- Новая иконка.\n\n### Измеренные характеристики APK\n- SHA-256: abc123")
        assertEquals("Что изменилось\n• Новая иконка.", actual)
        assertFalse(actual.contains("SHA"))
    }
    @Test fun emptyOrCodeOnlyDoesNotExposeTechnicalBlocks() {
        assertEquals("", ReleaseNotes.display(""))
        assertEquals("", ReleaseNotes.display("```json\n{\"metadata\": 42}\n```"))
    }
    @Test fun longNotesStayBoundedWithFullReleaseLinkHint() {
        val actual = ReleaseNotes.display((1..100).joinToString("\n") { "- Изменение $it: " + "текст ".repeat(15) })
        assertTrue(actual.length <= 5000)
        assertTrue(actual.contains("Продолжение"))
        assertTrue(actual.contains("Изменение 1:"))
    }
}
