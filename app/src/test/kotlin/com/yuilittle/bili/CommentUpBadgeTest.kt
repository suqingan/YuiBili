package com.yuilittle.bili

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentUpBadgeTest {
    private fun comment(
        id: Long = 1L,
        userMid: Long = 0L,
        isUp: Boolean = false
    ) = VideoComment(
        id = id,
        user = "测试用户",
        avatar = "",
        content = "测试内容",
        likes = 0L,
        publishedAt = 0L,
        userMid = userMid,
        isUp = isUp
    )

    @Test
    fun matchesUpByServerFlag() {
        assertTrue(isCommentFromUp(comment(userMid = 999L, isUp = true), videoOwnerMid = 100L))
    }

    @Test
    fun matchesUpByAuthorMidWhenFlagAbsent() {
        assertTrue(isCommentFromUp(comment(userMid = 10086L, isUp = false), videoOwnerMid = 10086L))
        assertFalse(isCommentFromUp(comment(userMid = 10087L, isUp = false), videoOwnerMid = 10086L))
        assertFalse(isCommentFromUp(comment(userMid = 0L, isUp = false), videoOwnerMid = 0L))
    }
}
