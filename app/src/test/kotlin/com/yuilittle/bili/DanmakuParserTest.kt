package com.yuilittle.bili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

class DanmakuParserTest {

    @Test
    fun parseSegmentProto_parsesNormalAndZeroFieldsAndUnknownWireTypes() {
        val elem1 = buildProto {
            writeVarintField(1, 1001L)
            writeVarintField(2, 0L) // progress=0 是合法值，不能误判丢失
            writeVarintField(3, 1L) // 滚动
            writeVarintField(4, 25L)
            writeVarintField(5, 0L) // color=0 表示黑色，不能替换白色
            writeStringField(7, "第一条黑色弹幕")
            writeVarintField(9, 7L)
            writeVarintField(11, 0L) // pool=0 普通池
            // 未知字段：fixed64 (wire 1), fixed32 (wire 5), len (wire 2)
            writeFixed64Field(99, 0x1122334455667788L)
            writeFixed32Field(100, 0xAABBCCDD.toInt())
            writeStringField(101, "unknown-field")
        }
        val elem2 = buildProto {
            // 无 id，从 idStr 补全；无 color 字段默认白色
            writeVarintField(2, 12500L)
            writeVarintField(3, 5L) // 顶部
            writeVarintField(4, 36L)
            writeStringField(7, "高能预警\n带换行")
            writeStringField(12, "2002")
            writeVarintField(24, 60001L) // colorful 标记
        }
        val elemAdvanced = buildProto {
            // mode=7 高级弹幕应被过滤
            writeVarintField(1, 3003L)
            writeVarintField(2, 5000L)
            writeVarintField(3, 7L)
            writeStringField(7, "高级弹幕")
        }
        val elemSpecialPool = buildProto {
            // pool=2 特殊池应被过滤
            writeVarintField(1, 4004L)
            writeVarintField(2, 6000L)
            writeVarintField(3, 1L)
            writeVarintField(11, 2L)
            writeStringField(7, "特殊池")
        }
        val segBytes = buildProto {
            writeBytesField(1, elem2) // 故意乱序输入，验证输出按 progressMs 升序
            writeBytesField(1, elemAdvanced)
            writeBytesField(1, elemSpecialPool)
            writeBytesField(1, elem1)
        }

        val res = DanmakuParser.parseSegmentProto(segBytes)
        assertFalse(res.closed)
        assertEquals(4, res.rawCount)
        assertEquals(2, res.filteredCount)
        assertEquals(2, res.items.size)

        val first = res.items[0]
        assertEquals(1001L, first.id)
        assertEquals(0, first.progressMs)
        assertEquals(0xFF000000.toInt(), first.colorArgb) // 保留黑色
        assertEquals("第一条黑色弹幕", first.text)
        assertTrue(first.isColorDanmaku)

        val second = res.items[1]
        assertEquals(2002L, second.id)
        assertEquals(12500, second.progressMs)
        assertEquals(0xFFFFFFFF.toInt(), second.colorArgb)
        assertEquals("高能预警 带换行", second.text)
        assertEquals(DanmakuItem.MODE_TOP, second.mode)
        assertTrue(second.isColorDanmaku) // colorful 标记触发彩色判断
    }

    @Test
    fun parseSegmentProto_respectsServerClosedState() {
        val elem = buildProto {
            writeVarintField(1, 1L)
            writeVarintField(2, 1000L)
            writeVarintField(3, 1L)
            writeStringField(7, "ignored")
        }
        val segBytes = buildProto {
            writeBytesField(1, elem)
            writeVarintField(2, 1L) // state=1 关闭弹幕
        }
        val res = DanmakuParser.parseSegmentProto(segBytes)
        assertTrue(res.closed)
        assertTrue(res.items.isEmpty())
    }

    @Test
    fun parseSegmentProto_dedupsPositiveIdsAndPreservesSyntheticIds() {
        val dup1 = buildProto {
            writeVarintField(1, 555L)
            writeVarintField(2, 2000L)
            writeVarintField(3, 1L)
            writeStringField(7, "重复A")
        }
        val dup2 = buildProto {
            writeVarintField(1, 555L)
            writeVarintField(2, 2000L)
            writeVarintField(3, 1L)
            writeStringField(7, "重复B")
        }
        val noId1 = buildProto {
            writeVarintField(2, 3000L)
            writeVarintField(3, 1L)
            writeStringField(7, "哈哈哈哈")
        }
        val noId2 = buildProto {
            writeVarintField(2, 3000L)
            writeVarintField(3, 1L)
            writeStringField(7, "哈哈哈哈")
        }
        val res = DanmakuParser.parseSegmentProto(buildProto {
            writeBytesField(1, dup1)
            writeBytesField(1, dup2)
            writeBytesField(1, noId1)
            writeBytesField(1, noId2)
        })
        assertEquals(3, res.items.size)
        assertEquals("重复A", res.items[0].text)
        assertTrue(res.items[1].id < 0L)
        assertTrue(res.items[2].id < 0L)
        assertTrue(res.items[1].id != res.items[2].id)
    }

    @Test
    fun parseSegmentProto_rejectsTruncatedAndOverlongVarintAndOverflowLength() {
        // 截断 varint
        try {
            DanmakuParser.parseSegmentProto(byteArrayOf(0x0A, 0x80.toByte()))
            fail("应拒绝截断 varint")
        } catch (_: DanmakuParseException) {
            // expected
        }

        // 超出边界的 length-delimited 长度
        try {
            DanmakuParser.parseSegmentProto(byteArrayOf(0x0A, 0x7F, 0x01, 0x02))
            fail("应拒绝越界长度")
        } catch (_: DanmakuParseException) {
            // expected
        }

        // 11 字节非法 varint
        try {
            val bad = ByteArray(12) { 0x81.toByte() }
            bad[0] = 0x10 // field 2 varint
            bad[11] = 0x01
            DanmakuParser.parseSegmentProto(bad)
            fail("应拒绝超过 10 字节的 varint")
        } catch (_: DanmakuParseException) {
            // expected
        }
    }

    @Test
    fun parseSegmentProto_highDensityKeepsTailSegmentsViaPerSecondBucketing() {
        // 构造第 1 秒 60 条高密度 + 第 350 秒 5 条尾部弹幕，确认段尾不会被截掉
        val segBytes = buildProto {
            var id = 1L
            for (k in 0 until 60) {
                val elem = buildProto {
                    writeVarintField(1, id++)
                    writeVarintField(2, 1000L + k)
                    writeVarintField(3, 1L)
                    writeVarintField(9, (k % 10).toLong())
                    writeStringField(7, "密集-$k")
                }
                writeBytesField(1, elem)
            }
            for (k in 0 until 5) {
                val elem = buildProto {
                    writeVarintField(1, id++)
                    writeVarintField(2, 350_000L + k * 100L)
                    writeVarintField(3, 1L)
                    writeStringField(7, "尾部-$k")
                }
                writeBytesField(1, elem)
            }
        }
        val res = DanmakuParser.parseSegmentProto(segBytes)
        assertEquals(DanmakuParser.MAX_ITEMS_PER_SECOND_BUCKET + 5, res.items.size)
        assertEquals(350_400, res.items.last().progressMs)
        assertEquals("尾部-4", res.items.last().text)
    }

    @Test
    fun parseViewMetaProto_validatesPageSizeAndTotal() {
        val dmSge = buildProto {
            writeVarintField(1, 360_000L)
            writeVarintField(2, 12L)
        }
        val bytes = buildProto {
            writeVarintField(1, 0L)
            writeBytesField(4, dmSge)
        }
        val meta = DanmakuParser.parseViewMetaProto(bytes)
        assertFalse(meta.isClosed)
        assertEquals(360_000L, meta.pageSizeMs)
        assertEquals(12, meta.totalSegments)

        // 异常小值（如秒 360）不自动猜测，回退默认 360000ms
        val weirdSge = buildProto {
            writeVarintField(1, 360L)
            writeVarintField(2, 3L)
        }
        val metaFallback = DanmakuParser.parseViewMetaProto(buildProto {
            writeBytesField(4, weirdSge)
        })
        assertEquals(DanmakuParser.DEFAULT_SEGMENT_MS, metaFallback.pageSizeMs)
        assertEquals(3, metaFallback.totalSegments)
    }

    @Test
    fun parseXmlDanmaku_supportsRawDeflateAndPlainXml() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><i><d p="12.50,1,25,16777215,1700000000,0,abc,9001,6">hello xml</d><d p="3.2,4,25,16711680,1700000001,0,def,9002,8">底部红色</d></i>"""
        val rawUtf8 = xml.toByteArray(StandardCharsets.UTF_8)
        val compressed = deflateRaw(rawUtf8)

        val res = DanmakuParser.parseXmlDanmaku(compressed, "deflate")
        assertEquals(2, res.items.size)
        assertEquals(3200, res.items[0].progressMs)
        assertEquals(DanmakuItem.MODE_BOTTOM, res.items[0].mode)
        assertEquals(0xFFFF0000.toInt(), res.items[0].colorArgb)
        assertEquals(12500, res.items[1].progressMs)
        assertEquals("hello xml", res.items[1].text)
    }

    @Test
    fun settingsSanitizeAndLowerBound_behaveCorrectly() {
        val dirty = DanmakuSettingsStore.Snapshot(
            opacity = Float.NaN,
            textScale = 9.0f,
            speedScale = -2.0f,
            areaFraction = 0.68f
        ).sanitized()
        assertEquals(0.85f, dirty.opacity, 0.001f)
        assertEquals(1.6f, dirty.textScale, 0.001f)
        assertEquals(0.6f, dirty.speedScale, 0.001f)
        assertEquals(0.75f, dirty.areaFraction, 0.001f)

        val items = listOf(100, 300, 300, 900).mapIndexed { i, ms ->
            DanmakuItem(i + 1L, ms, "t$i", 0xFFFFFFFF.toInt(), 25, 1, 0, 0, 0)
        }
        assertEquals(0, DanmakuOverlayView.lowerBound(items, 50))
        assertEquals(1, DanmakuOverlayView.lowerBound(items, 300))
        assertEquals(3, DanmakuOverlayView.lowerBound(items, 301))
        assertEquals(4, DanmakuOverlayView.lowerBound(items, 1000))
    }

    // ── Protobuf 测试构造辅助 ──

    private class ProtoBuilder {
        private val out = ByteArrayOutputStream()

        fun writeVarintField(fieldNumber: Int, value: Long) {
            writeVarint(((fieldNumber shl 3) or 0).toLong())
            writeVarint(value)
        }

        fun writeFixed64Field(fieldNumber: Int, value: Long) {
            writeVarint(((fieldNumber shl 3) or 1).toLong())
            for (i in 0 until 8) out.write(((value ushr (i * 8)) and 0xFFL).toInt())
        }

        fun writeFixed32Field(fieldNumber: Int, value: Int) {
            writeVarint(((fieldNumber shl 3) or 5).toLong())
            for (i in 0 until 4) out.write((value ushr (i * 8)) and 0xFF)
        }

        fun writeStringField(fieldNumber: Int, s: String) {
            writeBytesField(fieldNumber, s.toByteArray(StandardCharsets.UTF_8))
        }

        fun writeBytesField(fieldNumber: Int, bytes: ByteArray) {
            writeVarint(((fieldNumber shl 3) or 2).toLong())
            writeVarint(bytes.size.toLong())
            out.write(bytes)
        }

        private fun writeVarint(v: Long) {
            var cur = v
            while (true) {
                if ((cur and 0x7FL.inv()) == 0L) {
                    out.write(cur.toInt())
                    return
                }
                out.write(((cur.toInt() and 0x7F) or 0x80))
                cur = cur ushr 7
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun buildProto(block: ProtoBuilder.() -> Unit): ByteArray =
        ProtoBuilder().apply(block).toByteArray()

    private fun deflateRaw(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }
}
