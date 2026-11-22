package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class BiliDashManifestTest {
    @Test
    fun preservesByteRangesAndAssignsDisjointTrackPriorities() {
        val vPrimary = "https://cdn.example.com/video.m4s?deadline=1&token=2"
        val vBackup = "https://backup.example.com/video.m4s"
        val aPrimary = "https://cdn.example.com/audio.m4s?deadline=1&token=2"
        val aBackup = "https://backup.example.com/audio.m4s"
        val video = BiliPlayUrl.DashVideoTrack(
            64, vPrimary, listOf(vPrimary, vBackup), 450_000,
            "video/mp4", "avc1.640028", 1280, 720, "30", "0-891", "892-1545"
        )
        val audio = BiliPlayUrl.DashAudioTrack(
            30280, aPrimary, listOf(aBackup), 128_000,
            "audio/mp4", "mp4a.40.2", "0-500", "501-900"
        )
        val stream = BiliPlayUrl.DashStream(
            listOf(video), listOf(audio), 5_250L, 64, listOf(64), emptyList(), true
        )
        val xml = BiliDashManifest.build(stream)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val urls = document.getElementsByTagName("BaseURL")
        assertEquals(4, urls.length)
        assertEquals(vPrimary, urls.item(0).textContent)
        assertEquals(vBackup, urls.item(1).textContent)
        assertEquals("1", (urls.item(0) as Element).getAttribute("dvb:priority"))
        assertEquals("2", (urls.item(1) as Element).getAttribute("dvb:priority"))
        assertEquals(aPrimary, urls.item(2).textContent)
        assertEquals(aBackup, urls.item(3).textContent)
        assertEquals("101", (urls.item(2) as Element).getAttribute("dvb:priority"))
        assertEquals("102", (urls.item(3) as Element).getAttribute("dvb:priority"))
        assertEquals("0-891", (document.getElementsByTagName("Initialization").item(0) as Element).getAttribute("range"))
        assertEquals("892-1545", (document.getElementsByTagName("SegmentBase").item(0) as Element).getAttribute("indexRange"))
        assertEquals("PT5.250S", document.documentElement.getAttribute("mediaPresentationDuration"))
    }

    @Test
    fun wbiSignProducesCanonicalSortedSignatureAndExpiryDetectionWorks() {
        val signed = BiliWbiSign.signWithMixin(
            mapOf("cid" to "12345", "bvid" to "BV1xx411c7mD"),
            mixin = "ea1db124af3c7062474693fa704f4ff8",
            timestampSeconds = 1_700_000_000L
        )
        assertTrue(signed.startsWith("bvid=BV1xx411c7mD&cid=12345&wts=1700000000&w_rid="))
        val wrid = signed.substringAfter("w_rid=")
        assertEquals(32, wrid.length)

        val nowMs = 1_700_000_000_000L
        val soonSec = (nowMs + 10_000L) / 1000L
        val laterSec = (nowMs + 120_000L) / 1000L
        val expiringTrack = BiliPlayUrl.DashVideoTrack(
            64, "https://cdn.bilivideo.com/v.m4s?deadline=$soonSec", emptyList(),
            1000, "video/mp4", "avc1", 1280, 720, "30", null, null
        )
        val expiringStream = BiliPlayUrl.DashStream(
            listOf(expiringTrack), emptyList(), 1000L, 64, listOf(64), emptyList(), true
        )
        assertNotNull(BiliPlayUrl.earliestUrlDeadlineMs(expiringStream))
        assertTrue(BiliPlayUrl.isStreamNearExpiry(expiringStream, nowMs))

        val freshStream = expiringStream.copy(
            videoTracks = listOf(expiringTrack.copy(url = "https://cdn.bilivideo.com/v.m4s?deadline=$laterSec"))
        )
        assertFalse(BiliPlayUrl.isStreamNearExpiry(freshStream, nowMs))
    }
}
