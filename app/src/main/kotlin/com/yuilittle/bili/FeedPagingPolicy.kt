package com.yuilittle.bili

/**
 * Pure paging rules shared by the home feeds and executable host-side tests.
 * The memory limit is a sliding window, never an end-of-pagination signal.
 */
object FeedPagingPolicy {
    const val PAGE_SIZE = 12
    const val MAX_IN_MEMORY_ITEMS = 480
    const val RETAINED_ITEMS = 360
    const val MAX_REFRESH_SCAN_PAGES = 4
    const val MAX_APPEND_SKIP_PAGES = 2

    fun nextRefreshPage(lastScannedPage: Int): Int =
        if (lastScannedPage <= 0) 1 else lastScannedPage + 1

    fun scannedCursor(candidatePage: Int, endReached: Boolean, rotatingFeed: Boolean): Int =
        if (rotatingFeed && endReached) 0 else candidatePage

    fun trimFromStart(currentSize: Int, additionsSize: Int): Int {
        val total = currentSize.coerceAtLeast(0) + additionsSize.coerceAtLeast(0)
        if (total <= MAX_IN_MEMORY_ITEMS) return 0
        // Trim whole rows so the surviving cards keep their column and scroll anchor.
        return (total - RETAINED_ITEMS + 1) / 2 * 2
    }
}
