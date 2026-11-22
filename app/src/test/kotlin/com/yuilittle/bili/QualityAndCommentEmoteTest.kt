package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityAndCommentEmoteTest {
    @Test
    fun selectEmoteUrlPrefersGifThenWebpThenUrlAndNormalizesScheme() {
        assertEquals(
            "https://i0.hdslb.com/bfs/emote/anim.gif",
            BiliApi.selectEmoteUrl(
                gifUrl = "//i0.hdslb.com/bfs/emote/anim.gif",
                webpUrl = "https://i0.hdslb.com/bfs/emote/anim.webp",
                fallbackUrl = "http://i0.hdslb.com/bfs/emote/static.png"
            )
        )
        assertEquals(
            "https://i0.hdslb.com/bfs/garb/only_webp.webp",
            BiliApi.selectEmoteUrl(
                gifUrl = "",
                webpUrl = "//i0.hdslb.com/bfs/garb/only_webp.webp",
                fallbackUrl = "http://i0.hdslb.com/bfs/garb/only_url.png"
            )
        )
        assertEquals(
            "https://i0.hdslb.com/bfs/garb/only_url.png",
            BiliApi.selectEmoteUrl(
                gifUrl = "",
                webpUrl = "",
                fallbackUrl = "http://i0.hdslb.com/bfs/garb/only_url.png"
            )
        )
        assertEquals("", BiliApi.selectEmoteUrl("", "", ""))
    }

    @Test
    fun buildEmoteCandidatesIncludesWebpThenCleanFallbackForBiliCdn() {
        assertEquals(
            listOf(
                "https://i0.hdslb.com/bfs/emote/foo.png@108w_108h_1c.webp",
                "https://i0.hdslb.com/bfs/emote/foo.png@96w_96h.webp",
                "https://i0.hdslb.com/bfs/emote/foo.png"
            ),
            buildEmoteCandidates("//i0.hdslb.com/bfs/emote/foo.png")
        )
        assertEquals(
            listOf(
                "https://i0.hdslb.com/bfs/garb/foo.webp@100w.webp",
                "https://i0.hdslb.com/bfs/garb/foo.webp"
            ),
            buildEmoteCandidates("http://i0.hdslb.com/bfs/garb/foo.webp@100w.webp")
        )
    }

    @Test
    fun selectDashStreamTrackRespectsRequestedQualityAndExcludedFailedCodecs() {
        val stream = BiliPlayUrl.DashStream(
            videoTracks = listOf(
                BiliPlayUrl.DashVideoTrack(
                    120, "https://cdn.example.com/120_hev.m4s", emptyList(), 6_000_000,
                    "video/mp4", "hev1.1.6.L150.90", 3840, 2160, "60", null, null
                ),
                BiliPlayUrl.DashVideoTrack(
                    120, "https://cdn.example.com/120_avc.m4s", emptyList(), 5_500_000,
                    "video/mp4", "avc1.640033", 3840, 2160, "60", null, null
                ),
                BiliPlayUrl.DashVideoTrack(
                    80, "https://cdn.example.com/80_avc.m4s", emptyList(), 2_200_000,
                    "video/mp4", "avc1.640028", 1920, 1080, "30", null, null
                ),
                BiliPlayUrl.DashVideoTrack(
                    64, "https://cdn.example.com/64_avc.m4s", emptyList(), 1_100_000,
                    "video/mp4", "avc1.64001f", 1280, 720, "30", null, null
                )
            ),
            audioTracks = listOf(
                BiliPlayUrl.DashAudioTrack(
                    30280, "https://cdn.example.com/audio.m4s", emptyList(), 192_000,
                    "audio/mp4", "mp4a.40.2", null, null
                )
            ),
            durationMs = 120_000L,
            actualQuality = 80,
            acceptedQualities = listOf(120, 80, 64, 32),
            acceptedDescriptions = listOf("4K", "1080P", "720P", "480P"),
            isDash = true
        )

        val picked4k = BiliPlayUrl.selectDashStreamTrack(stream, BiliPlayUrl.QUALITY_4K)
        assertEquals(120, picked4k.actualQuality)
        assertEquals("avc1.640033", picked4k.videoTracks.single().codecs)
        assertEquals(3840, picked4k.videoTracks.single().width)

        val fallbackCodec4k = BiliPlayUrl.selectDashStreamTrack(
            stream,
            BiliPlayUrl.QUALITY_4K,
            excludedTracks = setOf(120 to "avc1.640033")
        )
        assertEquals(120, fallbackCodec4k.actualQuality)
        assertEquals("hev1.1.6.L150.90", fallbackCodec4k.videoTracks.single().codecs)

        val picked1080 = BiliPlayUrl.selectDashStreamTrack(stream, BiliPlayUrl.QUALITY_1080)
        assertEquals(80, picked1080.actualQuality)
        assertEquals(1920, picked1080.videoTracks.single().width)
        assertTrue(picked1080.audioTracks.isNotEmpty())
    }

    @Test
    fun buildHistoryKidRequiresPositiveKidAndNonBlankBusiness() {
        assertEquals("archive_12345", BiliApi.buildHistoryKid("archive", 12345L))
        assertEquals("pgc_678", BiliApi.buildHistoryKid("pgc", 678L))
        assertEquals("", BiliApi.buildHistoryKid("archive", 0L))
        assertEquals("", BiliApi.buildHistoryKid("", 12345L))
    }

    @Test
    fun historyCursorEndedDetectsZeroCursorAndRepeatedPagesWithoutStoppingFirstEmptyZero() {
        assertTrue(BiliApi.isHistoryCursorEnded(listLength = 0, prevMax = 0L, prevViewAt = 0L, nextMax = 0L, nextViewAt = 0L))
        assertTrue(BiliApi.isHistoryCursorEnded(listLength = 5, prevMax = 100L, prevViewAt = 200L, nextMax = 0L, nextViewAt = 0L))
        assertTrue(BiliApi.isHistoryCursorEnded(listLength = 5, prevMax = 100L, prevViewAt = 200L, nextMax = 100L, nextViewAt = 200L))
        assertFalse(BiliApi.isHistoryCursorEnded(listLength = 10, prevMax = 0L, prevViewAt = 0L, nextMax = 99L, nextViewAt = 170000L))
        assertFalse(BiliApi.isHistoryCursorEnded(listLength = 10, prevMax = 99L, prevViewAt = 170000L, nextMax = 88L, nextViewAt = 160000L))
    }

    @Test
    fun computeDecodeSampleSizePreservesWidthForOriginalLongVerticalImages() {
        // 1080x9600 超长图以原图查看时应保持 1080 宽（sample=2 让高<=8192，或 1080x6400 保持 sample=1），
        // 而不是按总高 / 2048 缩成 8 倍糊图。
        assertEquals(1, computeImageDecodeSampleSize(1080, 6400, 2048, isOriginalLongImage = true))
        assertEquals(2, computeImageDecodeSampleSize(1080, 9600, 2048, isOriginalLongImage = true))
        assertEquals(4, computeImageDecodeSampleSize(1080, 6400, 2048, isOriginalLongImage = false))
    }

    @Test
    fun formatHistoryCoverBadgeAndProgressRatioFormatAndClampCorrectly() {
        assertEquals("00:07 / 07:31", formatHistoryCoverBadge(progressSec = 7L, durationSec = 451))
        assertEquals("07:31 / 07:31", formatHistoryCoverBadge(progressSec = -1L, durationSec = 451))
        assertEquals("00:00 / 07:31", formatHistoryCoverBadge(progressSec = 0L, durationSec = 451))
        assertEquals("07:31", formatHistoryCoverBadge(progressSec = null, durationSec = 451))
        assertEquals("1:01:05 / 1:05:00", formatHistoryCoverBadge(progressSec = 3665L, durationSec = 3900))

        assertEquals(0f, computeHistoryProgressRatio(progressSec = 0L, durationSec = 451), 0.0001f)
        assertEquals(0f, computeHistoryProgressRatio(progressSec = null, durationSec = 451), 0.0001f)
        assertEquals(1f, computeHistoryProgressRatio(progressSec = -1L, durationSec = 451), 0.0001f)
        assertEquals(0.5f, computeHistoryProgressRatio(progressSec = 200L, durationSec = 400), 0.0001f)
        assertEquals(0.02f, computeHistoryProgressRatio(progressSec = 1L, durationSec = 451), 0.0001f)
    }

    @Test
    fun formatQualityButtonTextReturnsCompactSingleLineLabels() {
        assertEquals("1080p60", formatQualityButtonText(116))
        assertEquals("1080p+", formatQualityButtonText(112))
        assertEquals("杜比", formatQualityButtonText(126))
        assertEquals("4K", formatQualityButtonText(120))
        assertEquals("720p", formatQualityButtonText(64))
    }
}
