package com.crimson.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.crimson.BuildConfig
import com.crimson.core.audio.DialogueLeveler
import com.crimson.core.audio.SpeechMeter
import com.crimson.core.skip.ChromaMeter
import java.nio.ByteBuffer

/**
 * What the UI thread and the playback thread share about audio: the dialogue-boost switch, and a
 * tap on the decoded audio for automatic caption sync. Volatile fields only; nothing here blocks
 * the audio path.
 */
class AudioEffects {

    @Volatile
    var dialogueBoost: Boolean = false

    /** Set while downloaded captions are showing: sync has to hear the audio, so it is decoded. */
    @Volatile
    var decodeForSync: Boolean = false

    /**
     * Receives the speech level of each tenth of a second, stamped in the renderer's timeline
     * (see [lastPlayoutUs] for how that maps to the video's). Null when nothing is listening,
     * which is most of the time; the meter does no work then.
     */
    @Volatile
    var speechListener: ((rendererTimeUs: Long, levelDb: Float) -> Unit)? = null

    /** Set while a show's theme songs are being listened for: they have to be heard, so decoded. */
    @Volatile
    var decodeForThemes: Boolean = false

    /**
     * Receives the notes sounding eight times a second ([ChromaMeter]), stamped like
     * [speechListener], for recognising theme songs. Null when not listening.
     */
    @Volatile
    var themeListener: ((rendererTimeUs: Long, chroma: ByteArray) -> Unit)? = null

    /** Where audio output has got to, in the renderer's timeline. */
    @Volatile
    var lastPlayoutUs: Long = C.TIME_UNSET

    /** True while the audio goes to the TV or receiver still encoded (Dolby passthrough). */
    @Volatile
    var passthrough: Boolean = false

    /**
     * Counts stream starts. The renderer timeline restarts with each one, so speech readings from
     * an earlier start cannot be placed on the video and are thrown away.
     */
    @Volatile
    var generation: Int = 0
}

/** Builds the player's renderers with [DialogueAudioProcessor] and [TappingAudioSink] in the audio path. */
@OptIn(UnstableApi::class)
class CrimsonRenderersFactory(context: Context, private val effects: AudioEffects) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        TappingAudioSink(
            DefaultAudioSink.Builder(context)
                // 16-bit output keeps every decoder's output converted to what the processor reads.
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(DialogueAudioProcessor(effects)))
                .build(),
            effects,
        )
}

/** Runs [DialogueLeveler] on the decoded audio. Always in the chain; it bypasses itself when off. */
@OptIn(UnstableApi::class)
class DialogueAudioProcessor(private val effects: AudioEffects) : BaseAudioProcessor() {

    private var leveler: DialogueLeveler? = null
    private var channels = 0
    private var scratch = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        leveler = DialogueLeveler(inputAudioFormat.sampleRate, channels)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val leveler = leveler
        val samples = bytes / 2
        if (scratch.size < samples) scratch = ShortArray(samples)
        inputBuffer.asShortBuffer().get(scratch, 0, samples)
        inputBuffer.position(inputBuffer.position() + samples * 2)
        if (leveler != null && channels > 0) {
            val before = if (BuildConfig.DEBUG) peakAndEnergy(samples) else null
            leveler.enabled = effects.dialogueBoost
            leveler.process(scratch, samples / channels)
            if (before != null) debugLevels(before, peakAndEnergy(samples), samples)
        }
        val out = replaceOutputBuffer(samples * 2)
        out.asShortBuffer().put(scratch, 0, samples)
        out.position(samples * 2)
        out.flip()
    }

    override fun onFlush() {
        leveler?.reset()
    }

    // Debug builds: every few seconds, the loudest peak and the average level going in and coming
    // out, which is how the effect was checked on an emulator nobody could listen to.
    private val debugIn = DoubleArray(2)
    private val debugOut = DoubleArray(2)
    private var debugSamples = 0L

    private fun peakAndEnergy(samples: Int): DoubleArray {
        var peak = 0.0
        var energy = 0.0
        for (i in 0 until samples) {
            val x = scratch[i] / 32768.0
            energy += x * x
            if (kotlin.math.abs(x) > peak) peak = kotlin.math.abs(x)
        }
        return doubleArrayOf(peak, energy)
    }

    private fun debugLevels(before: DoubleArray, after: DoubleArray, samples: Int) {
        debugIn[0] = maxOf(debugIn[0], before[0]); debugIn[1] += before[1]
        debugOut[0] = maxOf(debugOut[0], after[0]); debugOut[1] += after[1]
        debugSamples += samples
        if (debugSamples < 48_000L * 2 * 5) return
        fun db(x: Double) = String.format("%.1f", 20 * kotlin.math.log10(x + 1e-9))
        android.util.Log.d(
            "CrimsonAudio",
            "boost=${effects.dialogueBoost} in: peak ${db(debugIn[0])} dB, rms ${db(kotlin.math.sqrt(debugIn[1] / debugSamples))} dB" +
                " -> out: peak ${db(debugOut[0])} dB, rms ${db(kotlin.math.sqrt(debugOut[1] / debugSamples))} dB",
        )
        debugIn.fill(0.0); debugOut.fill(0.0); debugSamples = 0
    }

    override fun onReset() {
        leveler = null
        channels = 0
    }
}

/**
 * The audio sink, wrapped for two things.
 *
 * **Dialogue boost needs decoded audio**, and so does caption sync. A Dolby track normally goes to
 * the TV or receiver still encoded, where nothing in the app can touch it; when either needs it, the sink says it cannot take
 * encoded audio, so the player decodes it (Fire TVs carry Dolby decoders) and the processor gets
 * to work on it. Only when a decoder exists — otherwise the track would have no sound at all.
 *
 * **Caption sync needs to hear the dialogue, and theme skipping the music.** Each decoded buffer
 * arrives here with its timestamp, so the speech meter's and chroma meter's readings can be placed
 * on the video's timeline.
 */
@OptIn(UnstableApi::class)
class TappingAudioSink(sink: AudioSink, private val effects: AudioEffects) : ForwardingAudioSink(sink) {

    private var meter: SpeechMeter? = null
    private var chroma: ChromaMeter? = null
    private var sampleRate = 0
    private var channels = 0
    private var lastBuffer: ByteBuffer? = null
    private var lastPresentationUs = Long.MIN_VALUE
    private var scratch = ShortArray(0)

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        if ((effects.dialogueBoost || effects.decodeForSync || effects.decodeForThemes) && isEncoded(format) && hasDecoder(format)) {
            return AudioSink.SINK_FORMAT_UNSUPPORTED
        }
        return super.getFormatSupport(format)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        val pcm16 = inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW && inputFormat.pcmEncoding == C.ENCODING_PCM_16BIT
        effects.passthrough = inputFormat.sampleMimeType != MimeTypes.AUDIO_RAW
        sampleRate = inputFormat.sampleRate
        channels = inputFormat.channelCount
        meter = if (pcm16 && sampleRate > 0 && channels > 0) SpeechMeter(sampleRate, channels) else null
        chroma = if (pcm16 && sampleRate > 0 && channels > 0) ChromaMeter(sampleRate, channels) else null
        lastBuffer = null
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val listener = effects.speechListener
        val themes = effects.themeListener
        // The same buffer comes back until the sink has taken all of it; measure it once.
        if ((listener != null || themes != null) && (buffer !== lastBuffer || presentationTimeUs != lastPresentationUs)) {
            lastBuffer = buffer
            lastPresentationUs = presentationTimeUs
            val meter = meter
            val chroma = chroma
            val frames = if ((listener != null && meter != null) || (themes != null && chroma != null)) read(buffer) else 0
            if (frames > 0) {
                if (listener != null && meter != null) measure(frames, presentationTimeUs, meter, listener)
                if (themes != null && chroma != null) listen(frames, presentationTimeUs, chroma, themes)
            }
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    /** Copies the buffer's samples into [scratch] without consuming it; returns the frame count. */
    private fun read(buffer: ByteBuffer): Int {
        if (channels <= 0) return 0
        val view = buffer.duplicate().order(buffer.order())
        val samples = view.remaining() / 2
        if (samples == 0) return 0
        if (scratch.size < samples) scratch = ShortArray(samples)
        view.asShortBuffer().get(scratch, 0, samples)
        return samples / channels
    }

    private fun listen(frames: Int, presentationTimeUs: Long, meter: ChromaMeter, listener: (Long, ByteArray) -> Unit) {
        meter.feed(scratch, frames) { endFrame, chroma ->
            listener(presentationTimeUs + endFrame * 1_000_000L / sampleRate - meter.centreLagUs, chroma)
        }
    }

    private fun measure(frames: Int, presentationTimeUs: Long, meter: SpeechMeter, listener: (Long, Float) -> Unit) {
        val halfWindowUs = SpeechMeter.WINDOW_MS * 500
        meter.feed(scratch, frames) { endFrame, levelDb ->
            listener(presentationTimeUs + endFrame * 1_000_000L / sampleRate - halfWindowUs, levelDb)
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        super.getCurrentPositionUs(sourceEnded).also { if (it != AudioSink.CURRENT_POSITION_NOT_SET) effects.lastPlayoutUs = it }

    override fun flush() {
        meter?.reset()
        chroma?.reset()
        lastBuffer = null
        effects.lastPlayoutUs = C.TIME_UNSET
        super.flush()
    }

    private fun isEncoded(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        return mime != MimeTypes.AUDIO_RAW && mime in PASSTHROUGH_TYPES
    }

    private fun hasDecoder(format: Format): Boolean {
        val mime = format.sampleMimeType ?: return false
        return decoders.getOrPut(mime) {
            runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false).isNotEmpty() }.getOrDefault(false)
        }
    }

    companion object {
        private val decoders = HashMap<String, Boolean>()

        private val PASSTHROUGH_TYPES = setOf(
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC4,
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_TRUEHD,
        )
    }
}
