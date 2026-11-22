package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadLogicTest {

    private fun ep(index: Int, pubAt: Long) = UgcEpisode(
        bvid = "BV$index",
        aid = index.toLong(),
        cid = index.toLong() * 10L,
        title = "EP $index",
        duration = 60,
        cover = "",
        publishedAt = pubAt,
        charge = false
    )

    private fun task(id: Long, epNo: Int, pubAt: Long, createdAt: Long = id * 1000L): DownloadManager.Task =
        DownloadManager.Task().apply {
            this.id = id
            this.episodeNo = epNo
            this.publishedAt = pubAt
            this.createdAt = createdAt
            this.title = "Task $id"
        }

    @Test
    fun sortEpisodesOrdersByPublishedAt() {
        val list = listOf(
            ep(1, 2000L),
            ep(2, 1000L),
            ep(3, 3000L),
            ep(4, 0L)
        )
        assertEquals(listOf("BV1", "BV2", "BV3", "BV4"),
            DownloadManager.sortEpisodes(list, DownloadManager.SeasonSortOrder.DEFAULT).map { it.bvid })
        assertEquals(listOf("BV2", "BV1", "BV3", "BV4"),
            DownloadManager.sortEpisodes(list, DownloadManager.SeasonSortOrder.OLDEST_FIRST).map { it.bvid })
        assertEquals(listOf("BV3", "BV1", "BV2", "BV4"),
            DownloadManager.sortEpisodes(list, DownloadManager.SeasonSortOrder.NEWEST_FIRST).map { it.bvid })
    }

    @Test
    fun sortTasksOrdersCorrectly() {
        val t1 = task(10L, epNo = 2, pubAt = 200L)
        val t2 = task(20L, epNo = 1, pubAt = 300L)
        val t3 = task(30L, epNo = 3, pubAt = 100L)
        val input = listOf(t1, t2, t3)

        assertEquals(listOf(20L, 10L, 30L),
            DownloadManager.sortTasks(input, DownloadManager.SeasonSortOrder.DEFAULT).map { it.id })
        assertEquals(listOf(30L, 10L, 20L),
            DownloadManager.sortTasks(input, DownloadManager.SeasonSortOrder.OLDEST_FIRST).map { it.id })
        assertEquals(listOf(20L, 10L, 30L),
            DownloadManager.sortTasks(input, DownloadManager.SeasonSortOrder.NEWEST_FIRST).map { it.id })
    }

    @Test
    fun localPlayModesResolveNextAndPrev() {
        val playlist = listOf(
            task(101L, 1, 100L),
            task(102L, 2, 200L),
            task(103L, 3, 300L)
        )

        // 顺序播放
        assertEquals(102L, DownloadManager.resolveNextPlayableTaskId(playlist, 101L, DownloadManager.LocalPlayMode.SEASON_SEQUENCE))
        assertNull(DownloadManager.resolveNextPlayableTaskId(playlist, 103L, DownloadManager.LocalPlayMode.SEASON_SEQUENCE))
        assertNull(DownloadManager.resolvePrevPlayableTaskId(playlist, 101L, DownloadManager.LocalPlayMode.SEASON_SEQUENCE))
        assertEquals(101L, DownloadManager.resolvePrevPlayableTaskId(playlist, 102L, DownloadManager.LocalPlayMode.SEASON_SEQUENCE))

        // 列表循环
        assertEquals(101L, DownloadManager.resolveNextPlayableTaskId(playlist, 103L, DownloadManager.LocalPlayMode.LIST_LOOP))
        assertEquals(103L, DownloadManager.resolvePrevPlayableTaskId(playlist, 101L, DownloadManager.LocalPlayMode.LIST_LOOP))

        // 单集循环
        assertEquals(102L, DownloadManager.resolveNextPlayableTaskId(playlist, 102L, DownloadManager.LocalPlayMode.SINGLE_LOOP))
        assertEquals(102L, DownloadManager.resolvePrevPlayableTaskId(playlist, 102L, DownloadManager.LocalPlayMode.SINGLE_LOOP))

        // 随机播放（排除当前集）
        val picked = DownloadManager.resolveNextPlayableTaskId(playlist, 102L, DownloadManager.LocalPlayMode.SHUFFLE) { 0 }
        assertTrue(picked == 101L || picked == 103L)
        assertEquals(101L, picked)
    }

    @Test
    fun seasonPlayModeCyclesThroughThreeStates() {
        val modes = DownloadManager.LocalPlayMode.SEASON_SEQUENCE
        assertEquals(DownloadManager.LocalPlayMode.SINGLE_LOOP, modes.nextSeasonMode())
        assertEquals(DownloadManager.LocalPlayMode.SHUFFLE, modes.nextSeasonMode().nextSeasonMode())
        assertEquals(DownloadManager.LocalPlayMode.SEASON_SEQUENCE,
            modes.nextSeasonMode().nextSeasonMode().nextSeasonMode())
        // 旧的四档模式混入时也收敛回顺序播放
        assertEquals(DownloadManager.LocalPlayMode.SEASON_SEQUENCE,
            DownloadManager.LocalPlayMode.LIST_LOOP.nextSeasonMode())
    }

    @Test
    fun sequencePlaybackFollowsSortOrder() {
        val t1 = task(10L, epNo = 1, pubAt = 300L)
        val t2 = task(20L, epNo = 2, pubAt = 100L)
        val t3 = task(30L, epNo = 3, pubAt = 200L)
        val oldestFirst = DownloadManager.sortTasks(
            listOf(t1, t2, t3), DownloadManager.SeasonSortOrder.OLDEST_FIRST
        )
        assertEquals(listOf(20L, 30L, 10L), oldestFirst.map { it.id })
        // 顺序播放严格按排序后的列表推进，播到末集停止
        assertEquals(30L, DownloadManager.resolveNextPlayableTaskId(
            oldestFirst, 20L, DownloadManager.LocalPlayMode.SEASON_SEQUENCE))
        assertEquals(10L, DownloadManager.resolveNextPlayableTaskId(
            oldestFirst, 30L, DownloadManager.LocalPlayMode.SEASON_SEQUENCE))
        assertNull(DownloadManager.resolveNextPlayableTaskId(
            oldestFirst, 10L, DownloadManager.LocalPlayMode.SEASON_SEQUENCE))
    }

    @Test
    fun computeDownloadProgressClampsSafely() {
        assertEquals(0f, DownloadManager.computeDownloadProgress(500L, 0L), 0.0001f)
        assertEquals(0.5f, DownloadManager.computeDownloadProgress(500L, 1000L), 0.0001f)
        assertEquals(1f, DownloadManager.computeDownloadProgress(1500L, 1000L), 0.0001f)
    }

    @Test
    fun mergeAndResolveAudioPlaylistFiltersInvalidAndDeduplicates() {
        val merged = DownloadManager.mergeAudioPlaylist(listOf(1L, 2L, 0L, 2L), listOf(2L, 3L, -1L, 4L))
        assertEquals(listOf(1L, 2L, 3L, 4L), merged)

        val f1 = java.io.File.createTempFile("yuibili_test_a1", ".m4a").apply { deleteOnExit() }
        val f2 = java.io.File.createTempFile("yuibili_test_v2", ".mp4").apply { deleteOnExit() }
        val f4 = java.io.File.createTempFile("yuibili_test_a4", ".m4a").apply { deleteOnExit() }

        val audio1 = task(1L, 1, 100L).apply {
            type = DownloadManager.TYPE_AUDIO
            state = DownloadManager.STATE_COMPLETED
            audioPath = f1.absolutePath
        }
        val video2 = task(2L, 2, 200L).apply {
            type = DownloadManager.TYPE_VIDEO
            state = DownloadManager.STATE_COMPLETED
            singlePaths.add(f2.absolutePath)
        }
        val brokenAudio3 = task(3L, 3, 300L).apply {
            type = DownloadManager.TYPE_AUDIO
            state = DownloadManager.STATE_FAILED
            audioPath = ""
        }
        val audio4 = task(4L, 4, 400L).apply {
            type = DownloadManager.TYPE_AUDIO
            state = DownloadManager.STATE_COMPLETED
            audioPath = f4.absolutePath
        }
        val resolved = DownloadManager.resolveSavedAudioPlaylist(
            savedIds = listOf(4L, 2L, 3L, 1L, 4L, 999L),
            allTasks = listOf(audio1, video2, brokenAudio3, audio4)
        )
        assertEquals(listOf(4L, 1L), resolved.map { it.id })
    }
}
