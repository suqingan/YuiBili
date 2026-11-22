package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimelineTest {
    @Test fun tracksTheEntireProgressiveSequence() {
        val timeline = PlaybackTimeline(listOf(9_000L, 12_000L, 4_000L))
        assertEquals(25_000L, timeline.durationMs)
        assertEquals(22_250L, timeline.global(2, 1_250L))
        assertEquals(PlaybackTimeline.Position(2, 1_250L), timeline.locate(22_250L))
    }

    @Test fun seeksToTheNextSegmentAtAnExactBoundary() {
        val timeline = PlaybackTimeline(listOf(9_000L, 12_000L, 4_000L))
        assertEquals(PlaybackTimeline.Position(1, 0L), timeline.locate(9_000L))
        assertEquals(PlaybackTimeline.Position(2, 4_000L), timeline.locate(Long.MAX_VALUE))
    }

    @Test fun doesNotChangeSingleSourceOffsets() {
        val timeline = PlaybackTimeline(emptyList())
        assertEquals(22_000L, timeline.global(0, 22_000L))
        assertEquals(PlaybackTimeline.Position(0, 22_000L), timeline.locate(22_000L))
    }

    @Test fun recoversOneUnreportedDurationFromTheTotal() {
        val timeline = PlaybackTimeline(listOf(9_000L, 0L, 4_000L), 25_000L)
        assertEquals(25_000L, timeline.durationMs)
        assertEquals(PlaybackTimeline.Position(1, 3_000L), timeline.locate(12_000L))
        assertEquals(21_000L, timeline.global(2, 0L))
    }

    @Test fun usesLocalOffsetsWhenDurationsCannotBeReconstructed() {
        val timeline = PlaybackTimeline(listOf(9_000L, 0L, 4_000L))
        assertEquals(0L, timeline.durationMs)
        assertEquals(3_000L, timeline.global(2, 3_000L))
    }

    @Test fun prefersRequestedPartAndRecoversAStaleIdentifier() {
        val first = VideoPart(1, "上集", 101, 40)
        val second = VideoPart(2, "下集", 102, 50)
        val parts = listOf(first, second)
        assertEquals(second, selectPlaybackPart(parts, 102, 101))
        assertEquals(first, selectPlaybackPart(parts, 103, 101))
        assertEquals(first, selectPlaybackPart(parts, 0, 0))
        assertEquals(null, selectPlaybackPart(emptyList(), 0, 0))
    }
}
