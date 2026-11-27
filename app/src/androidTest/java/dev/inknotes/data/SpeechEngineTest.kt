package dev.inknotes.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.speech.Speech
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Lecture transcripts without the speech model (it is downloaded only by the user): the engine
 * loads in this build (JNA after R8), and a recording as the app makes it (AAC in ADTS) is
 * decoded to 16 kHz mono.
 */
@RunWith(AndroidJUnit4::class)
class SpeechEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theEngineLoadsAndRefusesAMissingModel() {
        org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS)
        val failed = runCatching { org.vosk.Model(File(context.cacheDir, "no-such-model").path).close() }.isFailure
        assertTrue("a model that is not there must fail, not crash", failed)
    }

    @Test
    fun aRecordingIsDecodedTo16kHzMono() {
        val file = File(context.cacheDir, "tone.m4a")
        val seconds = 3
        writeTone(file, 44_100, 440.0, seconds)
        var samples = 0L
        var crossings = 0
        var last: Short = 0
        Speech.decode(file, 0, seconds * 1000L, onProgress = {}) { pcm, _ ->
            for (v in pcm) {
                if ((v < 0) != (last < 0)) crossings++
                last = v
            }
            samples += pcm.size
            true
        }
        file.delete()
        assertTrue("samples $samples", abs(samples - 16_000L * seconds) < 4_000)
        // 440 Hz: 880 sign changes a second (the encoder's lead-in is silence).
        assertTrue("crossings $crossings", abs(crossings - 880 * seconds) < 300)
    }

    /** A tone as the recorder writes it: AAC-LC frames, each with an ADTS header. */
    private fun writeTone(file: File, rate: Int, hz: Double, seconds: Int) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val total = rate * seconds
        var fed = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        FileOutputStream(file).use { out ->
            while (true) {
                if (!inputDone) {
                    val i = enc.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = enc.getInputBuffer(i)!!
                        b.clear()
                        val n = minOf(b.capacity() / 2, 1024, total - fed)
                        if (n <= 0) {
                            enc.queueInputBuffer(i, 0, 0, fed * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sb = b.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                            for (k in 0 until n) sb.put((12_000 * sin(2 * PI * hz * (fed + k) / rate)).toInt().toShort())
                            enc.queueInputBuffer(i, 0, n * 2, fed * 1_000_000L / rate, 0)
                            fed += n
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        val b = enc.getOutputBuffer(o)!!
                        val frame = ByteArray(info.size)
                        b.position(info.offset)
                        b.get(frame)
                        out.write(adts(frame.size, freqIndex = 4, channels = 1))
                        out.write(frame)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        enc.stop()
        enc.release()
    }

    private fun adts(size: Int, freqIndex: Int, channels: Int): ByteArray {
        val len = size + 7
        val profile = 2 // AAC LC
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            (((profile - 1) shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (len shr 11)).toByte(),
            ((len and 0x7FF) shr 3).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}
