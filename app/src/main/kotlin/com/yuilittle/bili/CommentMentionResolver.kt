package com.yuilittle.bili

/**
 * 评论 @昵称 → mid 的解析表。
 * B 站评论接口不直接给出被 @ 用户的 mid，这里用已加载评论的作者补全映射，
 * 命中不了时点击不跳转（不猜测，避免跳到错误用户）。
 */
internal object CommentMentionResolver {
    private val nameToMid = HashMap<String, Long>()
    private val midToName = HashMap<Long, String>()

    fun register(name: String, mid: Long) {
        if (name.isBlank() || mid <= 0L) return
        nameToMid[name] = mid
        midToName[mid] = name
    }

    fun register(comment: VideoComment) {
        register(comment.user, comment.userMid)
        comment.replies.forEach { register(it) }
    }

    fun resolve(name: String): Long {
        if (name.isBlank()) return 0L
        return nameToMid[name] ?: 0L
    }

    fun clear() {
        nameToMid.clear()
        midToName.clear()
    }
}
