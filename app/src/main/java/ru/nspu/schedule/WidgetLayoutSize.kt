package ru.nspu.schedule

internal data class WidgetLayoutSize(val width: Int, val height: Int) {
    companion object {
        /** The pin flow can supply an empty/zero bundle until after the first update. */
        fun fallback(minWidth: Int, minHeight: Int, maxWidth: Int, maxHeight: Int,
            landscape: Boolean): WidgetLayoutSize {
            val width = (if (landscape) maxWidth else minWidth).takeIf { it > 0 }
                ?: minWidth.takeIf { it > 0 } ?: 180
            val height = (if (landscape) minHeight else maxHeight).takeIf { it > 0 }
                ?: minHeight.takeIf { it > 0 } ?: 100
            return WidgetLayoutSize(width, height)
        }
    }
}
