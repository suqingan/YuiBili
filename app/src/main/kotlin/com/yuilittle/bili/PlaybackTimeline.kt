package com.yuilittle.bili

internal class PlaybackTimeline(lengthsMs: List<Long>, advertisedDurationMs: Long = 0L) {
    data class Position(val index: Int, val offsetMs: Long)

    private val boundaries: LongArray
    val durationMs: Long get() = boundaries.last()

    init {
        val missing = lengthsMs.count { it <= 0L }
        val knownDuration = lengthsMs.sumOf { it.coerceAtLeast(0L) }
        val remaining = (advertisedDurationMs - knownDuration).coerceAtLeast(0L)
        val canMapAllParts = missing == 0 || (missing == 1 && remaining > 0L)
        boundaries = if (canMapAllParts) {
            LongArray(lengthsMs.size + 1).also { offsets ->
                lengthsMs.forEachIndexed { index, length ->
                    offsets[index + 1] = offsets[index] + if (length > 0L) length else remaining
                }
            }
        } else {
            longArrayOf(0L)
        }
    }

    fun global(index: Int, offsetMs: Long): Long {
        val start = boundaries.getOrElse(index) { 0L }
        return start + offsetMs.coerceAtLeast(0L)
    }

    fun locate(positionMs: Long): Position {
        if (boundaries.size <= 1 || durationMs == 0L) return Position(0, positionMs.coerceAtLeast(0L))
        val target = positionMs.coerceIn(0L, durationMs)
        val lastIndex = boundaries.size - 2
        if (target == durationMs) return Position(lastIndex, target - boundaries[lastIndex])
        var left = 0
        var right = lastIndex
        while (left < right) {
            val middle = (left + right) ushr 1
            if (target < boundaries[middle + 1]) right = middle else left = middle + 1
        }
        return Position(left, target - boundaries[left])
    }
}

internal fun selectPlaybackPart(parts: List<VideoPart>, requestedCid: Long, defaultCid: Long): VideoPart? =
    parts.firstOrNull { it.cid > 0L && it.cid == requestedCid }
        ?: parts.firstOrNull { it.cid > 0L && it.cid == defaultCid }
        ?: parts.firstOrNull { it.cid > 0L }
