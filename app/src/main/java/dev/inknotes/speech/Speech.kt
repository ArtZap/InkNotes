package dev.inknotes.speech

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.tr
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Lecture recordings to text, on the device (Vosk). The speech model is not in the app: it is
 * downloaded once, only when the user asks for a transcript, and can be deleted again. One
 * recording at a time, on its own thread; what is done is saved after every utterance, so a
 * transcription stopped (the app closed) goes on from there next time.
 */
object Speech {
    enum class Model(val lang: String, val dirName: String, val mb: Int) {
        RU("ru", "vosk-model-small-ru-0.22", 45),
        EN("en", "vosk-model-small-en-us-0.15", 40);

        val url: String get() = "https://alphacephei.com/vosk/models/$dirName.zip"
        val title: String get() = if (this == RU) tr("русская", "Russian") else tr("английская", "English")

        companion object {
            fun of(lang: String): Model? = entries.firstOrNull { it.lang == lang }
        }
    }

    /** The work under way: a download (of [model]) or a transcription of [file]; [progress] 0..1. */
    class Task(val nbId: String, val file: String, val model: Model, val downloading: Boolean, val progress: Float)

    var task by mutableStateOf<Task?>(null)
        private set

    /** Bumped whenever a transcript or a model changes: screens read them again. */
    var version by mutableIntStateOf(0)
        private set

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "speech").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)

    private const val TAG = "Speech"
    private const val READY = ".ready"
    private const val MAX_ZIP_BYTES = 150L * 1024 * 1024
    private const val MAX_MODEL_BYTES = 300L * 1024 * 1024
    private const val RATE = 16_000

    private fun root(context: Context) = File(context.filesDir, "speech")
    private fun modelDir(context: Context, m: Model) = File(root(context), m.dirName)

    fun installed(context: Context, m: Model): Boolean = File(modelDir(context, m), READY).isFile

    /** Frees the space of a downloaded model (not while it is in use). */
    fun deleteModel(context: Context, m: Model) {
        if (task?.model == m) return
        val dir = modelDir(context, m)
        exec.execute {
            File(dir, READY).delete()
            dir.deleteRecursively()
            main.post { version++ }
        }
    }

    /** Transcripts of a notebook's recordings, by recording file. Not on the main thread. */
    fun transcripts(repo: NotebookRepository, nbId: String): Map<String, Transcript> = Transcripts.decode(repo.loadSpeech(nbId))

    fun cancel() = cancelled.set(true)

    /**
     * Transcribes recording [file] of notebook [nbId] (downloading [m] first if need be);
     * [onDone] on the main thread with null, or why it did not work. False when busy.
     */
    fun start(context: Context, repo: NotebookRepository, nbId: String, file: File, recName: String, durationMs: Long, m: Model, onDone: (String?) -> Unit): Boolean {
        if (task != null) return false
        val app = context.applicationContext
        cancelled.set(false)
        task = Task(nbId, recName, m, !installed(app, m), 0f)
        exec.execute {
            var downloading = false
            val why = try {
                if (!installed(app, m)) {
                    downloading = true
                    download(app, m) { p -> post(Task(nbId, recName, m, true, p)) }
                    downloading = false
                }
                if (cancelled.get()) null else {
                    post(Task(nbId, recName, m, false, 0f))
                    transcribe(app, repo, nbId, file, recName, durationMs, m)
                    null
                }
            } catch (e: Throwable) {
                Log.w(TAG, "transcription failed", e)
                when {
                    cancelled.get() -> null
                    e is OutOfMemoryError -> tr("Не хватило памяти", "Not enough memory")
                    e is IOException && downloading -> tr("Не удалось скачать модель речи. Нужен интернет.", "Could not download the speech model. An internet connection is needed.")
                    else -> tr("Не удалось расшифровать запись", "Could not transcribe the recording")
                }
            }
            main.post {
                task = null
                version++
                onDone(why)
            }
        }
        return true
    }

    private fun post(t: Task) = main.post { if (task != null) task = t }

    // ------------------------------------------------------------ the model

    private fun download(context: Context, m: Model, progress: (Float) -> Unit) {
        val root = root(context).apply { mkdirs() }
        val zip = File(root, m.dirName + ".zip.part")
        val tmp = File(root, m.dirName + ".part")
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            client.newCall(okhttp3.Request.Builder().url(m.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("no body")
                val total = body.contentLength().takeIf { it > 0 } ?: (m.mb * 1024L * 1024)
                body.byteStream().use { input ->
                    FileOutputStream(zip).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        var shown = -1
                        while (true) {
                            if (cancelled.get()) throw IOException("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            got += n
                            if (got > MAX_ZIP_BYTES) throw IOException("model too large")
                            out.write(buf, 0, n)
                            val pct = (100 * got / total).toInt()
                            if (pct != shown) {
                                shown = pct
                                progress((got.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            tmp.deleteRecursively()
            unzip(zip, tmp)
            if (!File(tmp, "am").isDirectory && !File(tmp, "conf").isDirectory) throw IOException("not a speech model")
            File(tmp, READY).writeText(m.dirName)
            val dir = modelDir(context, m)
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IOException("could not put the model in place")
            main.post { version++ }
        } finally {
            zip.delete()
            tmp.deleteRecursively()
        }
    }

    /** The archive's one top folder becomes [into]; nothing is written outside it. */
    private fun unzip(zip: File, into: File) {
        into.mkdirs()
        val base = into.canonicalPath + File.separator
        var total = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { z ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.replace('\\', '/').substringAfter('/', "")
                if (name.isEmpty()) continue
                val f = File(into, name)
                if (!f.canonicalPath.startsWith(base)) throw IOException("bad entry")
                if (e.isDirectory) {
                    f.mkdirs()
                    continue
                }
                f.parentFile?.mkdirs()
                FileOutputStream(f).use { out ->
                    while (true) {
                        if (cancelled.get()) throw IOException("cancelled")
                        val n = z.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_MODEL_BYTES) throw IOException("model too large")
                        out.write(buf, 0, n)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ transcribing

    private fun transcribe(context: Context, repo: NotebookRepository, nbId: String, file: File, recName: String, durationMs: Long, m: Model) {
        val before = Transcripts.decode(repo.loadSpeech(nbId))[recName]
        // Going on where a stopped one ended (same language); else from the start.
        val resume = before?.takeIf { it.lang == m.lang && !it.complete }
        val fromMs = resume?.doneMs ?: 0L
        var phrases = resume?.phrases.orEmpty()
        var doneMs = fromMs
        fun save(complete: Boolean) {
            val all = Transcripts.decode(repo.loadSpeech(nbId)).toMutableMap()
            all[recName] = Transcript(recName, m.lang, doneMs, complete, phrases)
            repo.saveSpeech(nbId, Transcripts.encode(all.values))
            main.post { version++ }
        }
        fun take(json: String, offsetMs: Long) {
            val words = Transcripts.words(json, offsetMs).filter { it.startMs >= fromMs }
            if (words.isEmpty()) return
            phrases = phrases + Transcripts.phrases(words)
            doneMs = words.last().endMs
            save(complete = false)
        }
        runCatching { org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS) }
        val model = org.vosk.Model(modelDir(context, m).path)
        try {
            val rec = org.vosk.Recognizer(model, RATE.toFloat())
            try {
                rec.setWords(true)
                decode(file, fromMs, durationMs, onProgress = { ms ->
                    post(Task(nbId, recName, m, false, if (durationMs > 0) (ms.toFloat() / durationMs).coerceIn(0f, 1f) else 0f))
                }) { pcm, offsetMs ->
                    if (rec.acceptWaveForm(pcm, pcm.size)) take(rec.result, offsetMs)
                    !cancelled.get()
                }.let { offsetMs ->
                    if (cancelled.get()) {
                        // What was heard so far stays; next time it goes on from the last pause.
                        save(complete = false)
                        return
                    }
                    take(rec.finalResult, offsetMs)
                }
                doneMs = maxOf(doneMs, durationMs)
                save(complete = true)
            } finally {
                rec.close()
            }
        } finally {
            model.close()
        }
    }

    /**
     * Decodes [file] from [fromMs] to 16 kHz mono, giving it to [sink] with where in the
     * recording the fed sound began; [sink] returns false to stop. Returns that start.
     */
    internal fun decode(file: File, fromMs: Long, durationMs: Long, onProgress: (Long) -> Unit, sink: (ShortArray, Long) -> Boolean): Long {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var offsetMs = fromMs
        try {
            ex.setDataSource(file.path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IOException("no sound in the file")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            if (fromMs > 0) ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floats = false
            var down: Downsampler? = null
            var first = true
            var lastShown = -1L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = c.getInputBuffer(i) ?: throw IOException("no input buffer")
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime.coerceAtLeast(0), 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floats = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    down = null
                } else if (o >= 0) {
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0) {
                        val buf = c.getOutputBuffer(o) ?: throw IOException("no output buffer")
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = buf.order(ByteOrder.nativeOrder())
                        val pcm = if (floats) {
                            val fb = bytes.asFloatBuffer()
                            ShortArray(fb.remaining()) { (fb.get() * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }
                        } else {
                            val sb = bytes.asShortBuffer()
                            ShortArray(sb.remaining()).also { sb.get(it) }
                        }
                        val at = info.presentationTimeUs / 1000
                        if (first) {
                            first = false
                            offsetMs = at
                        }
                        val d = down ?: Downsampler(rate, channels).also { down = it }
                        val out = d.feed(pcm)
                        if (out.isNotEmpty() && !sink(out, offsetMs)) {
                            c.releaseOutputBuffer(o, false)
                            return offsetMs
                        }
                        if (at - lastShown >= 2_000) {
                            lastShown = at
                            onProgress(at)
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (end) break
                }
            }
            onProgress(durationMs)
            return offsetMs
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}
