package ru.nspu.schedule

/** Positive means next day (swipe left); negative means previous (swipe right). */
object DaySwipe {
    fun direction(horizontalDragPx: Float, minDragPx: Float): Int {
        if (!horizontalDragPx.isFinite() || !minDragPx.isFinite() || minDragPx <= 0f) return 0
        return when {
            horizontalDragPx <= -minDragPx -> 1
            horizontalDragPx >= minDragPx -> -1
            else -> 0
        }
    }
}
