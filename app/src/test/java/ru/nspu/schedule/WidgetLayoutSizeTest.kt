package ru.nspu.schedule

import org.junit.Assert.*
import org.junit.Test

class WidgetLayoutSizeTest {
    @Test fun pinningWithNoOptionsStartsCompact() {
        assertEquals(WidgetLayoutSize(180, 100), WidgetLayoutSize.fallback(0, 0, 0, 0, false))
        assertEquals(WidgetLayoutSize(180, 100), WidgetLayoutSize.fallback(0, 0, 0, 0, true))
    }
    @Test fun portraitAndLandscapeUseTheirOwnDimensions() {
        assertEquals(WidgetLayoutSize(210, 320), WidgetLayoutSize.fallback(210, 112, 450, 320, false))
        assertEquals(WidgetLayoutSize(450, 112), WidgetLayoutSize.fallback(210, 112, 450, 320, true))
    }
    @Test fun partialOemOptionsNeverInventABigWidget() {
        assertEquals(WidgetLayoutSize(180, 100), WidgetLayoutSize.fallback(-1, -1, 0, 0, false))
        assertEquals(WidgetLayoutSize(220, 120), WidgetLayoutSize.fallback(220, 120, 0, 0, false))
    }
}
