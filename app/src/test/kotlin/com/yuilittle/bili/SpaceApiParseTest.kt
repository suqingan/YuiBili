package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceApiParseTest {

    @Test
    fun parseAccInfoHandlesLevelVipAndFollowStateSafely() {
        // 关注状态与解析辅助
        assertEquals(FollowState.FOLLOWED, BiliSpaceApi.resolveAccFollowState(isSelf = false, loggedIn = true, hasFollowedField = true, isFollowed = true))
        assertEquals(FollowState.NOT_FOLLOWED, BiliSpaceApi.resolveAccFollowState(isSelf = false, loggedIn = true, hasFollowedField = true, isFollowed = false))
        assertEquals(FollowState.UNKNOWN, BiliSpaceApi.resolveAccFollowState(isSelf = false, loggedIn = false, hasFollowedField = true, isFollowed = false))
        assertEquals(FollowState.SELF, BiliSpaceApi.resolveAccFollowState(isSelf = true, loggedIn = true, hasFollowedField = false, isFollowed = false))
        assertEquals("年度大会员", BiliSpaceApi.resolveVipLabel(vipStatus = 1, vipType = 2, labelText = "年度大会员"))
        assertEquals("大会员", BiliSpaceApi.resolveVipLabel(vipStatus = 1, vipType = 1, labelText = ""))
        assertNull(BiliSpaceApi.resolveVipLabel(vipStatus = 0, vipType = 2, labelText = "年度大会员"))
        assertEquals(6, BiliSpaceApi.clampSpaceLevel(9))
        assertEquals(0, BiliSpaceApi.clampSpaceLevel(-2))
        assertNull(BiliSpaceApi.clampSpaceLevel(null))
    }

    @Test
    fun parseFlexibleCountAndVideoHelpersHandleStringsAndDurations() {
        assertEquals(12000L, BiliSpaceApi.parseFlexibleCount("1.2万"))
        assertEquals(200000000L, BiliSpaceApi.parseFlexibleCount("2亿"))
        assertEquals(320L, BiliSpaceApi.parseFlexibleCount(320))
        assertNull(BiliSpaceApi.parseFlexibleCount("--"))
        assertNull(BiliSpaceApi.parseFlexibleCount(null))

        assertEquals(754, BiliSpaceApi.parseDurationStringToSec("12:34"))
        assertEquals(3665, BiliSpaceApi.parseDurationStringToSec("1:01:05"))
        assertEquals(0, BiliSpaceApi.parseDurationStringToSec(null))

        assertTrue(BiliSpaceApi.computeVideoHasMore(serverPn = 1, serverPs = 20, total = 25L, itemCount = 20))
        assertFalse(BiliSpaceApi.computeVideoHasMore(serverPn = 2, serverPs = 20, total = 25L, itemCount = 5))
        assertFalse(BiliSpaceApi.computeVideoHasMore(serverPn = 1, serverPs = 20, total = 25L, itemCount = 0))
        assertTrue(BiliSpaceApi.computeVideoHasMore(serverPn = 1, serverPs = 20, total = null, itemCount = 20))
        assertFalse(BiliSpaceApi.computeVideoHasMore(serverPn = 1, serverPs = 20, total = null, itemCount = 12))

        assertEquals("https://img/a.jpg", BiliSpaceApi.normalizeSpaceUrl("//img/a.jpg"))
        assertEquals("https://img/a.jpg", BiliSpaceApi.normalizeSpaceUrl("http://img/a.jpg"))
        assertEquals("abc", BiliSpaceApi.limitUnicodeCodePoints("abcdef", 3))
    }

    @Test
    fun spaceVideoToVideoItemMapsDurationAndViewsCorrectly() {
        val sv = SpaceVideo(
            aid = 11L,
            bvid = "BV1abc",
            title = "测试视频",
            cover = "https://cov.jpg",
            duration = "12:34",
            publishedAt = 1700000000L,
            play = 5432L,
            danmaku = 99L,
            comments = 12L,
            jumpUrl = null
        )
        val converted = BiliSpaceApi.spaceVideoToVideoItem(sv, "UP", 10L)
        assertEquals(754, converted.duration)
        assertEquals(5432L, converted.views)
        assertEquals(99L, converted.danmakuCount)
        assertEquals(12L, converted.replyCount)
        assertEquals(10L, converted.ownerMid)
    }

    @Test
    fun matchesDynamicKeywordChecksTitleTextAndVideoTitle() {
        val dyn = SpaceDynamic(
            id = "d1",
            kind = DynamicKind.VIDEO,
            authorMid = 1L,
            authorName = "Me",
            authorFace = null,
            pubTs = null,
            pubTime = null,
            text = "推荐看看这期讲解",
            richNodes = emptyList(),
            images = emptyList(),
            video = SpaceVideo(1L, "BV1x", "Rust 实战指南", null, null, null, null, null, null, null),
            stats = DynamicStats(null, null),
            isPinned = false,
            visible = true,
            jumpUrl = null,
            title = "周末更新计划"
        )
        assertTrue(BiliSpaceApi.matchesDynamicKeyword(dyn, "推荐"))
        assertTrue(BiliSpaceApi.matchesDynamicKeyword(dyn, "rust"))
        assertTrue(BiliSpaceApi.matchesDynamicKeyword(dyn, "周末"))
        assertFalse(BiliSpaceApi.matchesDynamicKeyword(dyn, "不存在"))
    }

    @Test
    fun dynamicSupportsDetailAndCommentRoutingDistinguishesPureVideoVsCaptionedVideo() {
        val pureVideo = SpaceDynamic(
            id = "9002",
            kind = DynamicKind.VIDEO,
            authorMid = 100L,
            authorName = "UP",
            authorFace = null,
            pubTs = 1700000100L,
            pubTime = null,
            text = "",
            richNodes = emptyList(),
            images = emptyList(),
            video = SpaceVideo(556677L, "BV1pure", "纯投稿视频", null, "04:10", null, null, null, null, null),
            stats = DynamicStats(null, null),
            isPinned = false,
            visible = true,
            jumpUrl = null,
            title = null,
            commentOid = "556677",
            commentType = 1,
            supportsDetail = false
        )
        assertFalse(pureVideo.supportsDetail)
        assertEquals("556677", pureVideo.commentOid)
        assertEquals(1, pureVideo.commentType)

        val captionedVideo = SpaceDynamic(
            id = "9003",
            kind = DynamicKind.VIDEO,
            authorMid = 100L,
            authorName = "UP",
            authorFace = null,
            pubTs = 1700000200L,
            pubTime = null,
            text = "新视频上线啦，大家去看看",
            richNodes = emptyList(),
            images = emptyList(),
            video = SpaceVideo(889900L, "BV1cap", "带配文视频", null, "12:00", null, null, null, null, null),
            stats = DynamicStats(10L, 25L),
            isPinned = false,
            visible = true,
            jumpUrl = null,
            title = null,
            commentOid = "9003",
            commentType = 17,
            supportsDetail = true
        )
        assertTrue(captionedVideo.supportsDetail)
        assertEquals("9003", captionedVideo.commentOid)
        assertEquals(17, captionedVideo.commentType)
    }

    @Test
    fun formatNullableCountDistinguishesNullZeroAndWan() {
        assertEquals("--", BiliSpaceApi.formatNullableCount(null))
        assertEquals("0", BiliSpaceApi.formatNullableCount(0L))
        assertEquals("986", BiliSpaceApi.formatNullableCount(986L))
        assertEquals("1.2万", BiliSpaceApi.formatNullableCount(12_345L))
        assertEquals("2万", BiliSpaceApi.formatNullableCount(20_000L))
        val formatted = SpaceDynamicCardUi.formatDynamicTime(pubTs = null, rawPubTime = "3小时前")
        assertEquals("3小时前", formatted)
        assertEquals("刚刚", SpaceDynamicCardUi.formatDynamicTime(pubTs = (System.currentTimeMillis() / 1000L) - 10L, rawPubTime = null))
    }

    @Test
    fun buildDynamicImageThumbUrlPreservesAspectAndQueryAndSkipsNonBfs() {
        val longPortrait = SpaceImage("https://i0.hdslb.com/bfs/new_dyn/abc.jpg", width = 1000, height = 3200)
        assertEquals(
            "https://i0.hdslb.com/bfs/new_dyn/abc.jpg@720w.webp",
            SpaceDynamicCardUi.buildDynamicImageThumbUrl(longPortrait, singleMode = true)
        )
        assertEquals(
            "https://i0.hdslb.com/bfs/new_dyn/abc.jpg@420w_420h_1c.webp",
            SpaceDynamicCardUi.buildDynamicImageThumbUrl(longPortrait, singleMode = false)
        )
        val withQuery = SpaceImage("https://i0.hdslb.com/bfs/album/foo.png@100w.webp?auth=1", width = 1600, height = 800)
        assertEquals(
            "https://i0.hdslb.com/bfs/album/foo.png@800w.webp?auth=1",
            SpaceDynamicCardUi.buildDynamicImageThumbUrl(withQuery, singleMode = true)
        )
        val gif = SpaceImage("https://i0.hdslb.com/bfs/new_dyn/anim.gif", width = 400, height = 400)
        assertEquals(
            "https://i0.hdslb.com/bfs/new_dyn/anim.gif",
            SpaceDynamicCardUi.buildDynamicImageThumbUrl(gif, singleMode = true)
        )
        val external = SpaceImage("https://example.com/pic.jpg", width = 800, height = 600)
        assertEquals(
            "https://example.com/pic.jpg",
            SpaceDynamicCardUi.buildDynamicImageThumbUrl(external, singleMode = true)
        )
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/video.jpg@720w_405h_1c.webp?t=1",
            SpaceDynamicCardUi.buildDynamicVideoCoverThumbUrl("https://i0.hdslb.com/bfs/archive/video.jpg@640w.webp?t=1")
        )
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/video.jpg@480w_300h_1c.webp?t=1",
            SpaceDynamicCardUi.buildSpaceVideoCoverThumbUrl("https://i0.hdslb.com/bfs/archive/video.jpg@640w.webp?t=1")
        )
        assertEquals(
            "https://example.com/cover.jpg",
            SpaceDynamicCardUi.buildSpaceVideoCoverThumbUrl("https://example.com/cover.jpg")
        )
    }
}
