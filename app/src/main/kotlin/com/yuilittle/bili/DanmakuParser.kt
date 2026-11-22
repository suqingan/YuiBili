package com.yuilittle.bili

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** 单条弹幕模型（不可变、精简，按 progressMs 升序存储）。 */
internal data class DanmakuItem(
    val id: Long,
    val progressMs: Int,
    val text: String,
    val colorArgb: Int,
    val fontSize: Int,
    val mode: Int,
    val weight: Int,
    val pool: Int,
    val flags: Int
) {
    val isColorDanmaku: Boolean
        get() = (flags and FLAG_COLORFUL) != 0 || ((colorArgb and 0x00FFFFFF) != 0x00FFFFFF)

    companion object {
        const val MODE_SCROLL_NORMAL = 1
        const val MODE_SCROLL_ALT_2 = 2
        const val MODE_SCROLL_ALT_3 = 3
        const val MODE_BOTTOM = 4
        const val MODE_TOP = 5
        const val MODE_SCROLL_REVERSE = 6

        const val POOL_NORMAL = 0
        const val POOL_SUBTITLE = 1

        const val FLAG_COLORFUL = 1 shl 0
        const val FLAG_SUBTITLE_POOL = 1 shl 1
        const val FLAG_SYNTHETIC_ID = 1 shl 2
    }
}

internal data class DanmakuSegmentParseResult(
    val items: List<DanmakuItem>,
    val closed: Boolean,
    val rawCount: Int,
    val filteredCount: Int,
    val droppedByBudget: Int,
    val estimatedBytes: Int
)

internal data class DanmakuViewMeta(
    val state: Int,
    val pageSizeMs: Long,
    val totalSegments: Int
) {
    val isClosed: Boolean get() = state == 1
}

internal class DanmakuParseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 纯 Kotlin 有界 Protobuf Wire Reader + 弹幕分段/元数据/XML 解析与高密度均匀裁剪。
 * 不引入 protobuf runtime 依赖；严格校验边界、长度、varint 与内存配额。
 */
internal object DanmakuParser {

    const val DEFAULT_SEGMENT_MS = 360_000L
    const val MIN_VALID_PAGE_SIZE_MS = 60_000L
    const val MAX_VALID_PAGE_SIZE_MS = 3_600_000L

    const val MAX_SEG_PAYLOAD_BYTES = 8 * 1024 * 1024
    const val MAX_META_PAYLOAD_BYTES = 256 * 1024
    const val MAX_XML_DECOMPRESSED_BYTES = 8 * 1024 * 1024
    const val MAX_ELEM_BYTES = 64 * 1024
    const val MAX_CONTENT_UTF8_BYTES = 1024
    const val MAX_DISPLAY_CODE_POINTS = 256
    const val MAX_PARSED_ELEMENTS = 50_000

    const val MAX_RETAINED_PER_SEGMENT = 8_000
    const val MAX_SEGMENT_ESTIMATED_BYTES = 2 * 1024 * 1024
    const val MAX_ITEMS_PER_SECOND_BUCKET = 24
    private const val ESTIMATED_FIXED_ITEM_BYTES = 96

    /** 解析 DmSegMobileReply（field 1: repeated DanmakuElem, field 2: state）。 */
    fun parseSegmentProto(
        data: ByteArray,
        isCancelled: () -> Boolean = { false }
    ): DanmakuSegmentParseResult {
        if (data.size > MAX_SEG_PAYLOAD_BYTES) {
            throw DanmakuParseException("分段包体超出上限：${data.size}")
        }
        if (data.isEmpty()) {
            return DanmakuSegmentParseResult(emptyList(), closed = false, 0, 0, 0, 0)
        }
        val reader = ProtoWireReader(data, 0, data.size)
        val raw = ArrayList<RawEntry>(minOf(512, data.size / 32 + 16))
        var closed = false
        var rawCount = 0
        var filteredCount = 0
        var orderSeq = 0
        var syntheticIdSeq = -1L

        while (reader.hasRemaining()) {
            if ((rawCount and 0xFF) == 0 && isCancelled()) {
                throw DanmakuParseException("解析已取消")
            }
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> {
                    if (wireType != WIRE_LEN) {
                        throw DanmakuParseException("elems 字段类型错误：$wireType")
                    }
                    val subLen = reader.readLength()
                    val subStart = reader.position
                    reader.advance(subLen)
                    if (rawCount >= MAX_PARSED_ELEMENTS) {
                        filteredCount++
                        continue
                    }
                    rawCount++
                    if (subLen > MAX_ELEM_BYTES) {
                        filteredCount++
                        continue
                    }
                    val item = parseDanmakuElem(data, subStart, subStart + subLen) {
                        val next = syntheticIdSeq
                        syntheticIdSeq -= 1L
                        next
                    }
                    if (item != null) {
                        raw.add(RawEntry(item, orderSeq++))
                    } else {
                        filteredCount++
                    }
                }
                2 -> {
                    if (wireType != WIRE_VARINT) {
                        throw DanmakuParseException("state 字段类型错误：$wireType")
                    }
                    val state = reader.readVarint64().toInt()
                    if (state == 1) closed = true
                }
                else -> reader.skipField(wireType)
            }
        }

        if (closed) {
            return DanmakuSegmentParseResult(emptyList(), closed = true, rawCount, filteredCount, 0, 0)
        }
        return finalizeSegmentItems(raw, rawCount, filteredCount, isCancelled)
    }

    /** 解析 DmWebViewReply（只解析 field 1: state、field 4: dmSge{pageSize=1, total=2}）。 */
    fun parseViewMetaProto(data: ByteArray): DanmakuViewMeta {
        if (data.size > MAX_META_PAYLOAD_BYTES) {
            throw DanmakuParseException("元数据包体超出上限：${data.size}")
        }
        if (data.isEmpty()) {
            return DanmakuViewMeta(state = 0, pageSizeMs = DEFAULT_SEGMENT_MS, totalSegments = 0)
        }
        val reader = ProtoWireReader(data, 0, data.size)
        var state = 0
        var pageSize = DEFAULT_SEGMENT_MS
        var total = 0

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> {
                    if (wireType != WIRE_VARINT) {
                        throw DanmakuParseException("view.state 字段类型错误：$wireType")
                    }
                    state = reader.readVarint64().toInt()
                }
                4 -> {
                    if (wireType != WIRE_LEN) {
                        throw DanmakuParseException("view.dmSge 字段类型错误：$wireType")
                    }
                    val len = reader.readLength()
                    val start = reader.position
                    reader.advance(len)
                    val sgeReader = ProtoWireReader(data, start, start + len)
                    while (sgeReader.hasRemaining()) {
                        val sTag = sgeReader.readTag()
                        val sField = sTag ushr 3
                        val sWire = sTag and 0x07
                        when (sField) {
                            1 -> {
                                if (sWire != WIRE_VARINT) {
                                    throw DanmakuParseException("dmSge.pageSize 字段类型错误：$sWire")
                                }
                                val v = sgeReader.readVarint64()
                                // 不盲猜秒/毫秒，只在可信毫秒范围内采纳，否则回退默认 360000ms
                                if (v in MIN_VALID_PAGE_SIZE_MS..MAX_VALID_PAGE_SIZE_MS) {
                                    pageSize = v
                                }
                            }
                            2 -> {
                                if (sWire != WIRE_VARINT) {
                                    throw DanmakuParseException("dmSge.total 字段类型错误：$sWire")
                                }
                                val v = sgeReader.readVarint64()
                                if (v in 1L..2000L) total = v.toInt()
                            }
                            else -> sgeReader.skipField(sWire)
                        }
                    }
                }
                else -> reader.skipField(wireType)
            }
        }
        return DanmakuViewMeta(state = state, pageSizeMs = pageSize, totalSegments = total)
    }

    /** 受控 XML 兜底解析（支持 deflate 有界解压；SAX 流式解析且禁止外部实体与 DTD）。 */
    fun parseXmlDanmaku(
        rawBytes: ByteArray,
        contentEncoding: String?,
        isCancelled: () -> Boolean = { false }
    ): DanmakuSegmentParseResult {
        if (rawBytes.isEmpty()) {
            return DanmakuSegmentParseResult(emptyList(), closed = false, 0, 0, 0, 0)
        }
        val xmlBytes = maybeInflateBounded(rawBytes, contentEncoding)
        val raw = ArrayList<RawEntry>(256)
        var rawCount = 0
        var filteredCount = 0
        var orderSeq = 0
        var syntheticIdSeq = -1L

        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val saxParser = factory.newSAXParser()
        val textBuf = StringBuilder(128)
        var inDanmakuTag = false
        var currentPAttr = ""

        try {
            saxParser.parse(ByteArrayInputStream(xmlBytes), object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    if (qName == "d") {
                        if ((rawCount and 0xFF) == 0 && isCancelled()) {
                            throw DanmakuParseException("XML 解析已取消")
                        }
                        inDanmakuTag = true
                        currentPAttr = attributes?.getValue("p").orEmpty()
                        textBuf.setLength(0)
                    }
                }

                override fun characters(ch: CharArray?, start: Int, length: Int) {
                    if (inDanmakuTag && ch != null && textBuf.length < MAX_CONTENT_UTF8_BYTES) {
                        val take = minOf(length, MAX_CONTENT_UTF8_BYTES - textBuf.length)
                        textBuf.append(ch, start, take)
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    if (qName == "d" && inDanmakuTag) {
                        inDanmakuTag = false
                        val text = textBuf.toString()
                        textBuf.setLength(0)
                        if (rawCount < MAX_PARSED_ELEMENTS) {
                            rawCount++
                            val item = parseXmlEntry(currentPAttr, text) {
                                val next = syntheticIdSeq
                                syntheticIdSeq -= 1L
                                next
                            }
                            if (item != null) {
                                raw.add(RawEntry(item, orderSeq++))
                            } else {
                                filteredCount++
                            }
                        } else {
                            filteredCount++
                        }
                    }
                }
            })
        } catch (e: DanmakuParseException) {
            throw e
        } catch (e: Exception) {
            throw DanmakuParseException("XML 弹幕解析失败：${e.message ?: "格式错误"}", e)
        }
        return finalizeSegmentItems(raw, rawCount, filteredCount, isCancelled)
    }

    /** 有界读取流，超过 maxBytes 立即抛异常，防 OOM。 */
    fun readBoundedBytes(stream: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > maxBytes) {
                throw DanmakuParseException("响应体超出安全上限（>$maxBytes 字节）")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    internal fun maybeInflateBounded(rawBytes: ByteArray, contentEncoding: String?): ByteArray {
        if (looksLikeXml(rawBytes)) return rawBytes
        val enc = contentEncoding?.lowercase(java.util.Locale.US).orEmpty()
        if (!enc.contains("deflate") && rawBytes.firstOrNull() == '<'.code.toByte()) {
            return rawBytes
        }
        // B 站旧 XML 接口常见 raw deflate (nowrap=true)，也可能带 zlib header (nowrap=false)
        val first = tryInflate(rawBytes, nowrap = true, maxOut = MAX_XML_DECOMPRESSED_BYTES)
        if (first != null && looksLikeXml(first)) return first
        val second = tryInflate(rawBytes, nowrap = false, maxOut = MAX_XML_DECOMPRESSED_BYTES)
        if (second != null && looksLikeXml(second)) return second
        if (first != null) return first
        if (second != null) return second
        throw DanmakuParseException("XML 弹幕解压失败")
    }

    private fun tryInflate(input: ByteArray, nowrap: Boolean, maxOut: Int): ByteArray? {
        val inflater = Inflater(nowrap)
        return try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream(minOf(maxOut, input.size * 3 + 512))
            val buf = ByteArray(8192)
            var total = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n > 0) {
                    total += n
                    if (total > maxOut) {
                        throw DanmakuParseException("XML 解压后超出上限（>$maxOut 字节）")
                    }
                    out.write(buf, 0, n)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            }
            if (total == 0) null else out.toByteArray()
        } catch (e: DanmakuParseException) {
            throw e
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun looksLikeXml(bytes: ByteArray): Boolean {
        var i = 0
        // 跳过 UTF-8 BOM 与前导空白
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) i = 3
        while (i < bytes.size && i < 64) {
            val b = bytes[i].toInt() and 0xFF
            if (b == ' '.code || b == '\t'.code || b == '\r'.code || b == '\n'.code) {
                i++
            } else {
                return b == '<'.code
            }
        }
        return false
    }

    private fun parseXmlEntry(
        pAttr: String,
        rawText: String,
        nextSyntheticId: () -> Long
    ): DanmakuItem? {
        if (pAttr.isBlank()) return null
        val parts = pAttr.split(',')
        if (parts.size < 4) return null
        val sec = parts[0].toDoubleOrNull() ?: return null
        if (!sec.isFinite() || sec < 0.0 || sec > 86_400.0 * 7) return null
        val progressMs = (sec * 1000.0).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val mode = parts[1].toIntOrNull() ?: return null
        if (!isSupportedMode(mode)) return null
        val fontSize = (parts[2].toIntOrNull() ?: 25).coerceIn(12, 64)
        val colorRgb = (parts[3].toLongOrNull() ?: 0xFFFFFFL) and 0x00FFFFFFL
        val pool = parts.getOrNull(5)?.toIntOrNull() ?: DanmakuItem.POOL_NORMAL
        if (!isSupportedPool(pool)) return null
        val parsedId = parts.getOrNull(7)?.toLongOrNull()?.takeIf { it > 0L }
        val weight = (parts.getOrNull(8)?.toIntOrNull() ?: 0).coerceIn(0, 15)
        val text = sanitizeDisplayText(rawText) ?: return null
        var flags = 0
        if (pool == DanmakuItem.POOL_SUBTITLE) flags = flags or DanmakuItem.FLAG_SUBTITLE_POOL
        val id = if (parsedId != null) {
            parsedId
        } else {
            flags = flags or DanmakuItem.FLAG_SYNTHETIC_ID
            nextSyntheticId()
        }
        return DanmakuItem(
            id = id,
            progressMs = progressMs,
            text = text,
            colorArgb = (0xFF000000L or colorRgb).toInt(),
            fontSize = fontSize,
            mode = mode,
            weight = weight,
            pool = pool,
            flags = flags
        )
    }

    private fun parseDanmakuElem(
        data: ByteArray,
        start: Int,
        end: Int,
        nextSyntheticId: () -> Long
    ): DanmakuItem? {
        val reader = ProtoWireReader(data, start, end)
        var id = 0L
        var idStr: String? = null
        var progressMs = 0
        var hasInvalidProgress = false
        var mode = 0
        var fontSize = 25
        var colorRgb = 0x00FFFFFF
        var hasColor = false
        var content: String? = null
        var contentTooLarge = false
        var weight = 0
        var pool = DanmakuItem.POOL_NORMAL
        var colorful = 0

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNumber) {
                1 -> {
                    if (wireType != WIRE_VARINT) throw DanmakuParseException("elem.id 类型错误：$wireType")
                    id = reader.readVarint64()
                }
                2 -> {
                    if (wireType != WIRE_VARINT) throw DanmakuParseException("elem.progress 类型错误：$wireType")
                    val raw = reader.readVarint64().toInt()
                    if (raw < 0) {
                        hasInvalidProgress = true
                    } else {
                        progressMs = raw
                    }
                }
                3 -> {
                    if (wireType != WIRE_VARINT) throw DanmakuParseException("elem.mode 类型错误：$wireType")
                    mode = reader.readVarint64().toInt()
                }
                4 -> {
                    if (wireType != WIRE_VARINT) throw DanmakuParseException("elem.fontsize 类型错误：$wireType")
                    fontSize = reader.readVarint64().toInt()
                }
                5 -> {
                    if (wireType != WIRE_VARINT) throw DanmakuParseException("elem.color 类型错误：$wireType")
                    colorRgb = (reader.readVarint64() and 0x00FFFFFFL).toInt()
                    hasColor = true
                }
                7 -> {
                    if (wireType != WIRE_LEN) throw DanmakuParseException("elem.content 类型错误：$wireType")
                    val len = reader.readLength()
                    val pos = reader.position
                    reader.advance(len)
                    if (len > MAX_CONTENT_UTF8_BYTES) {
                        contentTooLarge = true
                    } else if (len > 0) {
                        content = String(data, pos, len, StandardCharsets.UTF_8)
                    } else {
                        content = ""
                    }
                }
                9 -> {
                    if (wireType != WIRE_VARINT) throw DanmakuParseException("elem.weight 类型错误：$wireType")
                    weight = reader.readVarint64().toInt().coerceIn(0, 15)
                }
                11 -> {
                    if (wireType != WIRE_VARINT) throw DanmakuParseException("elem.pool 类型错误：$wireType")
                    pool = reader.readVarint64().toInt()
                }
                12 -> {
                    if (wireType != WIRE_LEN) throw DanmakuParseException("elem.idStr 类型错误：$wireType")
                    val len = reader.readLength()
                    val pos = reader.position
                    reader.advance(len)
                    if (len in 1..32) {
                        idStr = String(data, pos, len, StandardCharsets.US_ASCII)
                    }
                }
                24 -> {
                    if (wireType != WIRE_VARINT) throw DanmakuParseException("elem.colorful 类型错误：$wireType")
                    colorful = reader.readVarint64().toInt()
                }
                else -> reader.skipField(wireType)
            }
        }

        if (hasInvalidProgress || contentTooLarge) return null
        if (!isSupportedMode(mode) || !isSupportedPool(pool)) return null
        val text = sanitizeDisplayText(content ?: return null) ?: return null
        val effectiveFontSize = if (fontSize <= 0) 25 else fontSize.coerceIn(12, 64)
        // color=0 表示黑色，不能覆盖为白色；只有字段省略时默认白色
        val finalColorRgb = if (hasColor) colorRgb else 0x00FFFFFF
        var flags = 0
        if (colorful != 0) flags = flags or DanmakuItem.FLAG_COLORFUL
        if (pool == DanmakuItem.POOL_SUBTITLE) flags = flags or DanmakuItem.FLAG_SUBTITLE_POOL

        val resolvedId = when {
            id > 0L -> id
            else -> idStr?.toLongOrNull()?.takeIf { it > 0L } ?: run {
                flags = flags or DanmakuItem.FLAG_SYNTHETIC_ID
                nextSyntheticId()
            }
        }

        return DanmakuItem(
            id = resolvedId,
            progressMs = progressMs,
            text = text,
            colorArgb = (0xFF000000L or (finalColorRgb.toLong() and 0x00FFFFFFL)).toInt(),
            fontSize = effectiveFontSize,
            mode = mode,
            weight = weight,
            pool = pool,
            flags = flags
        )
    }

    private fun isSupportedMode(mode: Int): Boolean = mode in 1..6

    private fun isSupportedPool(pool: Int): Boolean =
        pool == DanmakuItem.POOL_NORMAL || pool == DanmakuItem.POOL_SUBTITLE

    internal fun sanitizeDisplayText(raw: String): String? {
        if (raw.isEmpty()) return null
        val sb = StringBuilder(minOf(raw.length, MAX_DISPLAY_CODE_POINTS))
        var cpCount = 0
        var i = 0
        var lastWasSpace = false
        while (i < raw.length && cpCount < MAX_DISPLAY_CODE_POINTS) {
            val cp = raw.codePointAt(i)
            val charCount = Character.charCount(cp)
            i += charCount
            // 换行/制表/控制字符规整为空格或跳过，避免单条弹幕纵向撑爆轨道
            when {
                cp == '\r'.code || cp == '\n'.code || cp == '\t'.code -> {
                    if (sb.isNotEmpty() && !lastWasSpace) {
                        sb.append(' ')
                        cpCount++
                        lastWasSpace = true
                    }
                }
                cp < 0x20 || cp == 0x7F -> continue
                else -> {
                    sb.appendCodePoint(cp)
                    cpCount++
                    lastWasSpace = (cp == ' '.code)
                }
            }
        }
        val trimmed = sb.toString().trim()
        return trimmed.ifEmpty { null }
    }

    private data class RawEntry(val item: DanmakuItem, val order: Int)

    private fun finalizeSegmentItems(
        raw: ArrayList<RawEntry>,
        rawCount: Int,
        filteredCount: Int,
        isCancelled: () -> Boolean
    ): DanmakuSegmentParseResult {
        if (raw.isEmpty()) {
            return DanmakuSegmentParseResult(emptyList(), closed = false, rawCount, filteredCount, 0, 0)
        }
        // 1. 按正数 id 去重（合成负数 id 不合并）
        val deduped = ArrayList<RawEntry>(raw.size)
        val seenIds = HashSet<Long>(minOf(raw.size, 8192))
        var dupDropped = 0
        for (entry in raw) {
            val id = entry.item.id
            if (id > 0L) {
                if (!seenIds.add(id)) {
                    dupDropped++
                    continue
                }
            }
            deduped.add(entry)
        }

        // 2. 按时间升序、id 升序、输入顺序稳定排序
        deduped.sortWith(
            compareBy<RawEntry> { it.item.progressMs }
                .thenBy { it.item.id }
                .thenBy { it.order }
        )

        if (isCancelled()) throw DanmakuParseException("解析已取消")

        // 3. 高密度按 1 秒时间桶均匀裁剪：每桶保留高 weight 优先，保证整段尾端不会被截断
        val bucketed = ArrayList<RawEntry>(minOf(deduped.size, MAX_RETAINED_PER_SEGMENT))
        var dropped = dupDropped
        var i = 0
        while (i < deduped.size) {
            val sec = deduped[i].item.progressMs / 1000
            var j = i + 1
            while (j < deduped.size && deduped[j].item.progressMs / 1000 == sec) j++
            val bucketSize = j - i
            if (bucketSize <= MAX_ITEMS_PER_SECOND_BUCKET) {
                for (k in i until j) bucketed.add(deduped[k])
            } else {
                val slice = deduped.subList(i, j).toMutableList()
                // 字幕池优先保留，其次高 weight，再按稳定输入顺序
                slice.sortWith(
                    compareByDescending<RawEntry> { it.item.pool == DanmakuItem.POOL_SUBTITLE }
                        .thenByDescending { it.item.weight }
                        .thenBy { it.order }
                )
                val kept = slice.subList(0, MAX_ITEMS_PER_SECOND_BUCKET)
                kept.sortWith(
                    compareBy<RawEntry> { it.item.progressMs }
                        .thenBy { it.item.id }
                        .thenBy { it.order }
                )
                bucketed.addAll(kept)
                dropped += bucketSize - MAX_ITEMS_PER_SECOND_BUCKET
            }
            i = j
        }

        // 4. 若仍超出单段条数/字节预算，按时间均匀步长配额缩减（而非截断尾部）
        var selected = bucketed
        if (selected.size > MAX_RETAINED_PER_SEGMENT) {
            val trimmed = trimEvenlyByCount(selected, MAX_RETAINED_PER_SEGMENT)
            dropped += selected.size - trimmed.size
            selected = trimmed
        }

        var estBytes = 0
        for (e in selected) estBytes += estimateItemBytes(e.item)
        if (estBytes > MAX_SEGMENT_ESTIMATED_BYTES && selected.isNotEmpty()) {
            val avg = (estBytes / selected.size).coerceAtLeast(ESTIMATED_FIXED_ITEM_BYTES)
            val targetCount = (MAX_SEGMENT_ESTIMATED_BYTES / avg).coerceAtLeast(1)
            val trimmed = trimEvenlyByCount(selected, targetCount)
            dropped += selected.size - trimmed.size
            selected = trimmed
            estBytes = 0
            for (e in selected) estBytes += estimateItemBytes(e.item)
        }

        val items = ArrayList<DanmakuItem>(selected.size)
        for (e in selected) items.add(e.item)
        return DanmakuSegmentParseResult(
            items = items,
            closed = false,
            rawCount = rawCount,
            filteredCount = filteredCount,
            droppedByBudget = dropped,
            estimatedBytes = estBytes
        )
    }

    private fun trimEvenlyByCount(sorted: List<RawEntry>, limit: Int): ArrayList<RawEntry> {
        if (sorted.size <= limit) return ArrayList(sorted)
        val out = ArrayList<RawEntry>(limit)
        val step = sorted.size.toDouble() / limit.toDouble()
        for (k in 0 until limit) {
            val start = (k * step).toInt()
            val end = (((k + 1) * step).toInt()).coerceIn(start + 1, sorted.size)
            var best = sorted[start]
            for (idx in (start + 1) until end) {
                val cand = sorted[idx]
                if (cand.item.weight > best.item.weight) best = cand
            }
            out.add(best)
        }
        return out
    }

    internal fun estimateItemBytes(item: DanmakuItem): Int =
        ESTIMATED_FIXED_ITEM_BYTES + item.text.length * 2

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5

    /** 零拷贝区间 Protobuf Wire Reader，严格校验 varint 与长度越界。 */
    internal class ProtoWireReader(
        private val buf: ByteArray,
        start: Int,
        private val limit: Int
    ) {
        var position: Int = start
            private set

        fun hasRemaining(): Boolean = position < limit

        fun readTag(): Int {
            val raw = readVarint64()
            if (raw <= 0L || raw > 0xFFFFFFFFL) {
                throw DanmakuParseException("非法 Protobuf tag：$raw")
            }
            val tag = raw.toInt()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            if (fieldNumber <= 0 || wireType == 3 || wireType == 4 || wireType > 5) {
                throw DanmakuParseException("非法字段号或 WireType：field=$fieldNumber wire=$wireType")
            }
            return tag
        }

        fun readVarint64(): Long {
            var result = 0L
            var shift = 0
            for (i in 0 until 10) {
                if (position >= limit) {
                    throw DanmakuParseException("Varint 提前截断")
                }
                val b = buf[position++].toInt() and 0xFF
                if (i == 9 && (b and 0xFE) != 0) {
                    throw DanmakuParseException("Varint 超出 64 位上限")
                }
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            throw DanmakuParseException("Varint 超过 10 字节")
        }

        fun readLength(): Int {
            val len = readVarint64()
            val remaining = (limit - position).toLong()
            if (len < 0L || len > remaining || len > Int.MAX_VALUE.toLong()) {
                throw DanmakuParseException("Length-delimited 长度越界：len=$len remaining=$remaining")
            }
            return len.toInt()
        }

        fun advance(bytes: Int) {
            if (bytes < 0 || bytes > limit - position) {
                throw DanmakuParseException("跳过长度越界：bytes=$bytes remaining=${limit - position}")
            }
            position += bytes
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint64()
                WIRE_FIXED64 -> advance(8)
                WIRE_LEN -> advance(readLength())
                WIRE_FIXED32 -> advance(4)
                else -> throw DanmakuParseException("不支持的 WireType：$wireType")
            }
        }
    }
}
