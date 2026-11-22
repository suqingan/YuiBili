package com.yuilittle.bili

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 实时采样 PCM 能量，供粒子 / 音柱视觉使用。
 * 只在渲染线程做分析与限频发布，绝不触碰 View。
 */
@UnstableApi
internal class EnergyAudioProcessor(
    private val onSample: (overall: Float, bass: Float) -> Unit
) : BaseAudioProcessor() {
    private var lp = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val lim = inputBuffer.limit()
        val len = lim - pos
        if (len <= 0) return

        val out = replaceOutputBuffer(len)
        val dup = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var sumSq = 0.0
        var bassSq = 0.0
        var samples = 0
        val step = 8 // 降采样步长，保证零音频卡顿
        var i = pos
        while (i + 1 < lim) {
            val s = dup.getShort(i) / 32768f
            lp = lp * 0.86f + s * 0.14f
            sumSq += (s * s).toDouble()
            bassSq += (lp * lp).toDouble()
            samples++
            i += 2 * step
        }
        if (samples > 0) {
            val rms = sqrt(sumSq / samples).toFloat().coerceIn(0f, 1f)
            val bassRms = sqrt(bassSq / samples).toFloat().coerceIn(0f, 1f)
            onSample((rms * 2.4f).coerceIn(0f, 1f), (bassRms * 2.8f).coerceIn(0f, 1f))
        }
        out.put(inputBuffer)
        out.flip()
    }
}
