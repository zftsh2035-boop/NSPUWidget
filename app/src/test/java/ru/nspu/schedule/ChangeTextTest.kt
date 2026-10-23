package ru.nspu.schedule

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

class ChangeTextTest {
    @Test fun onlyReplacedValuesReceiveBoldSpansInTheActualComposeText() {
        val record = ChangeLog.replacement("Ауд.", "411", "213").prefixed("Алгебра: ")
        val text = record.annotated()
        assertEquals("Алгебра: Ауд. 411 → 213", text.text)
        assertEquals(listOf("411", "213"), text.spanStyles.map { text.text.substring(it.start,it.end) })
        assertTrue(text.spanStyles.all { it.item.fontWeight == FontWeight.Bold })
    }
    @Test fun legacyHistoryAndInvalidRangesDoNotBoldAnEntireMessage() {
        assertTrue(ChangeRecord("Старое изменение").annotated().spanStyles.isEmpty())
        assertTrue(ChangeRecord("Текст",listOf(ChangedRange(-1,2),ChangedRange(0,100))).annotated().spanStyles.isEmpty())
    }
}
