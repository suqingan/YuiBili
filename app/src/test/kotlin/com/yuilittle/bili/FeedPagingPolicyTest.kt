package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedPagingPolicyTest {
    @Test fun retainsUntrimmedWindowAtLimit() {
        assertEquals(0, FeedPagingPolicy.trimFromStart(468, 12))
    }

    @Test fun trimsOnlyWholeRowsAtOddAndEvenBoundaries() {
        assertEquals(122, FeedPagingPolicy.trimFromStart(470, 12))
        assertEquals(122, FeedPagingPolicy.trimFromStart(469, 12))
        assertEquals(121, 469 + 12 - FeedPagingPolicy.RETAINED_ITEMS)
    }

    @Test fun popularCanWrapFromANonemptyLastPage() {
        val cursor = FeedPagingPolicy.scannedCursor(8, endReached = true, rotatingFeed = true)
        assertEquals(1, FeedPagingPolicy.nextRefreshPage(cursor))
    }

    @Test fun scanContinuesAfterDuplicatesAndKeepsTheNonRotatingCursor() {
        assertEquals(5, FeedPagingPolicy.nextRefreshPage(FeedPagingPolicy.scannedCursor(4, false, true)))
        assertEquals(9, FeedPagingPolicy.nextRefreshPage(FeedPagingPolicy.scannedCursor(8, true, false)))
    }
}
