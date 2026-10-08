package com.casthub.core

import android.content.Context
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleOutputBuffer

/** Shift decoded subtitle timestamps, so both early and late offsets work during seek/pause. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SubtitleRenderers(context: Context) : DefaultRenderersFactory(context) {
    private val store = ModuleEnabledStore(context)
    override fun buildTextRenderers(context: Context, output: TextOutput, outputLooper: Looper,
                                   extensionRendererMode: Int, out: ArrayList<Renderer>) {
        val factory = object : SubtitleDecoderFactory {
            override fun supportsFormat(format: Format) = SubtitleDecoderFactory.DEFAULT.supportsFormat(format)
            override fun createDecoder(format: Format): SubtitleDecoder {
                val decoder = SubtitleDecoderFactory.DEFAULT.createDecoder(format)
                return object : SubtitleDecoder by decoder {
                    override fun dequeueOutputBuffer(): SubtitleOutputBuffer? {
                        val buffer = decoder.dequeueOutputBuffer() ?: return null
                        if (buffer.isEndOfStream) return buffer
                        val shift = store.subtitleOffsetMs() * 1000
                        if (shift == 0L) return buffer
                        val times = LongArray(buffer.eventTimeCount) { buffer.getEventTime(it) }
                        val cues = times.map { buffer.getCues(it).toList() }
                        buffer.setContent(buffer.timeUs + shift, ShiftedSubtitle(times, cues, shift), 0)
                        return buffer
                    }
                }
            }
        }
        out.add(TextRenderer(output, outputLooper, factory))
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ShiftedSubtitle(private val times: LongArray, private val cues: List<List<Cue>>, private val offsetUs: Long) : Subtitle {
    override fun getEventTimeCount() = times.size
    override fun getEventTime(index: Int) = times[index] + offsetUs
    override fun getNextEventTimeIndex(timeUs: Long): Int = times.indexOfFirst { it + offsetUs > timeUs }
    override fun getCues(timeUs: Long): List<Cue> {
        val index = times.indexOfLast { it + offsetUs <= timeUs }
        return if (index < 0) emptyList() else cues[index]
    }
}
