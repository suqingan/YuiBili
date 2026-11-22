package com.yuilittle.bili

/**
 * 评论正文里的链接识别：
 * 1. 显式 URL（http/https/www/常见短链域名）
 * 2. B 站富文本链接（接口 jump_url 或 <a> 标签给出的区间）
 */
internal object CommentLinkParser {

    private val URL_REGEX = Regex(
        """(?i)(?:https?://|www\.)[^\s<>"'{}|\\^`\[\]（）【】《》，。；：！？、]+""" +
            """|(?i)(?<![A-Za-z0-9._%+\-@])(?:b23\.tv|acg\.tv|bilibili\.com|bili2233\.cn)/[^\s<>"'{}|\\^`\[\]（）【】《》，。；：！？、]*"""
    )

    private const val TRAILING_PUNCT = ".,;:!?'\"。，、；：！？“”‘’…~·"

    data class LinkMatch(val start: Int, val end: Int, val url: String)

    /** 扫描纯文本里的 URL，返回区间与补全协议后的跳转地址。 */
    fun findPlainLinks(text: String, skipRanges: List<IntRange> = emptyList()): List<LinkMatch> {
        if (text.isBlank()) return emptyList()
        val result = ArrayList<LinkMatch>()
        URL_REGEX.findAll(text).forEach { match ->
            var start = match.range.first
            var end = match.range.last + 1
            // 尾部标点与不成对括号不计入链接
            while (end > start && text[end - 1] in TRAILING_PUNCT) end--
            while (end > start && isUnbalancedCloser(text.substring(start, end))) end--
            if (end <= start) return@forEach
            if (skipRanges.any { start < it.last + 1 && end - 1 >= it.first }) return@forEach
            val raw = text.substring(start, end)
            val url = when {
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                else -> "https://$raw"
            }
            result.add(LinkMatch(start, end, url))
        }
        return result
    }

    /** 尾字符是右括号但串内左括号不足时，说明括号属于正文而非链接。 */
    private fun isUnbalancedCloser(part: String): Boolean {
        val last = part.lastOrNull() ?: return false
        return when (last) {
            ')' -> part.count { it == '(' } < part.count { it == ')' }
            ']' -> part.count { it == '[' } < part.count { it == ']' }
            '}' -> part.count { it == '{' } < part.count { it == '}' }
            '）' -> true
            '】' -> true
            '》' -> true
            '」' -> true
            else -> false
        }
    }

    /** 只允许 http/https，其他 scheme（intent/file/javascript）一律拒绝。 */
    fun isSafeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}
