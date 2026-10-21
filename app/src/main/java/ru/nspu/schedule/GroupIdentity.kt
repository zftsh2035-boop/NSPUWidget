package ru.nspu.schedule

/** group_shedule and zgroup_shedule have independent, overlapping numeric ids. */
internal object GroupIdentity {
    private const val SPACE = 1_000_000
    fun siteId(id: Int) = id % SPACE
    fun extramural(id: Int) = id / SPACE == 1
    fun catalogueId(siteId: Int, extramural: Boolean): Int {
        require(siteId in 1 until SPACE)
        return siteId + if (extramural) SPACE else 0
    }
    // Preserve a legacy extramural favorite's raw id, notes and widgets. If its
    // namesake full-time id is selected later, that new favorite uses this space.
    fun alternateFullTimeId(id: Int) = siteId(id) + 2 * SPACE
}
