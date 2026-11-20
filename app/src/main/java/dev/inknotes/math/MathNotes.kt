package dev.inknotes.math

import dev.inknotes.data.tr
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.strokes.StrokeInput
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import dev.inknotes.data.PageStroke
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import com.google.mlkit.vision.digitalink.recognition.Ink as MlInk

/**
 * Math notes: answers after handwritten "=". Formulas are taken apart by [MathPage] and their
 * runs of symbols read by ML Kit with the English model (digits, Latin letters, operators),
 * downloaded once when the user switches the feature on. Work runs on one background thread;
 * readings are cached by the ink they came from, so editing a page re-reads only what changed.
 */
class MathNotes(
    private val enabledPref: () -> Boolean,
    private val setEnabledPref: (Boolean) -> Unit,
    /** Corrections by hand: formulas pinned to strokes, symbols learned. */
    val fixes: FixStore = FixStore(null),
) {

    sealed interface State {
        data object Off : State
        data object Downloading : State
        data object Ready : State
        class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Off)
        private set

    /** Called on the main thread when the feature becomes ready (to compute open pages). */
    var onReady: (() -> Unit)? = null

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "math") }
    private val main = Handler(Looper.getMainLooper())
    private val model = DigitalInkRecognitionModel.builder(DigitalInkRecognitionModelIdentifier.EN_US).build()

    /** Latest request number per page; older requests are skipped or their results dropped. */
    private val latest = ConcurrentHashMap<String, Int>()

    // Math thread only.
    private var recognizer: DigitalInkRecognizer? = null
    private val cache = object : LinkedHashMap<String, List<String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > CACHE_SIZE
    }

    init {
        if (enabledPref()) enable()
    }

    private var attempt = 0
    private var timeout: Runnable? = null

    fun enable() {
        if (state == State.Downloading || state == State.Ready) return
        state = State.Downloading
        val manager = RemoteModelManager.getInstance()
        // Each «Включить» is its own try: a late answer or the time limit of an earlier one
        // changes nothing in this one.
        val mine = ++attempt
        timeout?.let(main::removeCallbacks)
        // Turned off meanwhile, or given up on: a late answer changes nothing.
        val ready = {
            if (mine == attempt && state == State.Downloading) {
                timeout?.let(main::removeCallbacks)
                setEnabledPref(true)
                state = State.Ready
                onReady?.invoke()
            }
        }
        val failed = {
            if (mine == attempt && state == State.Downloading) state = State.Failed(tr("Не удалось загрузить модель распознавания формул. Нужен интернет.", "Could not download the formula recognition model. An internet connection is needed."))
        }
        timeout = Runnable { failed() }.also { main.postDelayed(it, DOWNLOAD_TIMEOUT_MS) }
        // A repeated download request of a present model may never complete; ask first.
        manager.isModelDownloaded(model)
            .addOnSuccessListener { present ->
                if (present) {
                    ready()
                } else {
                    manager.download(model, DownloadConditions.Builder().build())
                        .addOnSuccessListener { ready() }
                        .addOnFailureListener { failed() }
                }
            }
            .addOnFailureListener { failed() }
    }

    fun disable() {
        setEnabledPref(false)
        state = State.Off
    }

    /**
     * Computes the answers on page [key] from its [strokes] (immutable, safe to read on another
     * thread); [onResult] runs on the main thread unless a newer request for the page came.
     */
    /**
     * A number being turned on its scale right now: its value, by the key of its strokes;
     * taken before the saved ones ([FixStore.scales]). Set on the main thread.
     */
    @Volatile
    var turning: Pair<String, Double>? = null

    fun analyze(key: String, strokes: List<PageStroke>, pageWidth: Float, onKnobs: ((List<Knob>) -> Unit)? = null, onResult: (List<PageAnswer>) -> Unit) {
        if (state != State.Ready) return
        val n = (latest[key] ?: 0) + 1
        latest[key] = n
        runCatching {
            exec.execute {
                if (latest[key] != n) return@execute
                // A failure must cost the answers of a page, never the app.
                usePersonal()
                val knobs = ArrayList<Knob>()
                val scaled = fixes.scales().let { saved -> turning?.let { saved + it } ?: saved }
                val answers = runCatching { MathPage.analyze(toInk(strokes), reader, pageWidth, fixes.pins(), scaled, knobs) }.getOrDefault(emptyList())
                main.post {
                    if (latest[key] == n) {
                        onKnobs?.invoke(knobs)
                        onResult(answers)
                    }
                }
            }
        }
    }

    /** The text of the formula written in [strokes] (null: not read), on the main thread. */
    fun readFormula(strokes: List<PageStroke>, onResult: (String?) -> Unit) {
        if (state != State.Ready) return onResult(null)
        runCatching {
            exec.execute {
                val text = runCatching { MathPage.formulaSource(toInk(strokes), reader) }.getOrNull()
                main.post { onResult(text) }
            }
        }.onFailure { onResult(null) }
    }

    /**
     * A wrong answer as a test case: the page's ink, every run the reader was asked about with
     * what it read, the answer at ([x], [y]) and the one [expected]. JSON, read back by
     * MathReportTest in the unit tests; [onDone] on the main thread (null: nothing to save).
     */
    fun report(strokes: List<PageStroke>, pageWidth: Float, x: Float, y: Float, expected: String, onDone: (String?) -> Unit) {
        if (state != State.Ready) return onDone(null)
        runCatching {
            exec.execute {
                val json = runCatching {
                    val ink = toInk(strokes)
                    val reads = LinkedHashMap<List<Int>, List<String>>()
                    val recorder = RunReader { run, h -> read(run, h).also { reads[run.map { it.order }.sorted()] = it } }
                    MathPage.analyze(ink, recorder, pageWidth)
                    fun q(t: String) = "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
                    fun floats(a: FloatArray) = a.joinToString(",") { "%.2f".format(java.util.Locale.ROOT, it) }
                    buildString {
                        append("{\"pageWidth\":").append(pageWidth).append(",\"x\":").append(x).append(",\"y\":").append(y)
                        append(",\"expected\":").append(q(expected)).append(",\n\"strokes\":[")
                        append(ink.joinToString(",\n") { "{\"o\":${it.order},\"x\":[${floats(it.xs)}],\"y\":[${floats(it.ys)}]}" })
                        append("],\n\"reads\":[")
                        append(reads.entries.joinToString(",\n") { (k, v) -> "{\"s\":[${k.joinToString(",")}],\"t\":[${v.joinToString(",") { q(it) }}]}" })
                        append("]}\n")
                    }
                }.getOrNull()
                main.post { onDone(json) }
            }
        }.onFailure { onDone(null) }
    }

    internal fun toInk(strokes: List<PageStroke>): List<Ink> {
        val out = ArrayList<Ink>()
        val input = StrokeInput()
        for ((order, s) in strokes.withIndex()) {
            if (!s.kind.isInk) continue
            val inputs = s.stroke.inputs
            val n = inputs.size
            if (n == 0) continue
            // Long strokes are drawings, not symbols; keep every k-th point of them.
            val step = max(1, n / MAX_POINTS)
            val count = (n + step - 1) / step
            val xs = FloatArray(count)
            val ys = FloatArray(count)
            val ts = LongArray(count)
            for (k in 0 until count) {
                inputs.populate(k * step, input)
                xs[k] = input.x
                ys[k] = input.y
                ts[k] = input.elapsedTimeMillis
            }
            if (xs.any { !it.isFinite() } || ys.any { !it.isFinite() }) continue
            out += Ink(xs, ys, order, ts)
            if (out.size >= MAX_STROKES) break
        }
        return out
    }

    /**
     * A formula taken apart for the correction window, read on the math thread: its sides
     * symbol by symbol; [onResult] on the main thread (null: nothing to correct).
     */
    fun prepare(fix: Fix, onResult: (Draft?) -> Unit) {
        if (state != State.Ready) return onResult(null)
        runCatching {
            exec.execute {
                usePersonal()
                val draft = runCatching {
                    val learned = fixes.learned.takeIf { fixes.learn }
                    val samples = fixes.samples.takeIf { fixes.learn }
                    val rows = fix.rows.map { row ->
                        val left = Correct.side(row.left, row.h, row.axis, reader, row.leftRead, learned, samples)
                        val right = row.right?.let { Correct.side(it, row.h, row.axis, reader, row.rightRead, learned, samples) }
                        if (left == null || row.right != null && right == null) return@runCatching null
                        Row(row, left, right)
                    }
                    Draft(fix, rows)
                }.getOrNull()
                main.post { onResult(draft) }
            }
        }.onFailure { onResult(null) }
    }

    /** A formula as read, symbol by symbol: its [rows] (the equations of a system, or one). */
    class Draft(val fix: Fix, val rows: List<Row>)

    /** The layout follows the writer's placements when learning is on (on the math thread). */
    private fun usePersonal() {
        Tune.personal = fixes.placements.takeIf { fixes.learn && !it.isEmpty() }
    }

    /** Both sides of one formula; [right] null for `expr =`. */
    class Row(val fix: Fix, val left: Correct.Side, val right: Correct.Side?)

    internal val reader = object : RunReader {
        override fun read(strokes: List<Ink>, lineHeight: Float): List<String> {
            val read = this@MathNotes.read(strokes, lineHeight)
            val learning = fixes.learn && !(fixes.learned.isEmpty() && fixes.samples.isEmpty())
            if (read.isEmpty() || !learning && !ReadOptions.glyphByGlyph) return read
            val glyphs = runCatching {
                MathLayout.items(strokes, lineHeight).filterIsInstance<MathLayout.G>().sortedBy { it.box.left }.map { it.glyph.strokes }
            }.getOrDefault(emptyList())
            // Symbols run together or cut apart by the reader: the glyphs read one by one too.
            val texts = if (ReadOptions.glyphByGlyph) GlyphReading.extend(read, glyphs) { this@MathNotes.read(it, lineHeight) } else read
            // What the writer meant by a symbol, learned from their corrections: the choices
            // they made, then their own symbols' shapes as a second voice.
            if (!learning) return texts
            val counted = fixes.learned.adjust(texts, glyphs.size)
            return Voices.adjust(counted, glyphs, fixes.samples)
        }

        // Both readings (plain and after arithmetic) put the same text first: measured on
        // CROHME with ML Kit, answers where they differ are wrong far more often.
        override fun sure(strokes: List<Ink>): Boolean = doubt(strokes)?.agree ?: true
    }

    private fun read(run: List<Ink>, h: Float): List<String> {
        val key = signature(run)
        val cacheKey = key + ReadOptions.key()
        cache[cacheKey]?.let { return it }
        val r = recognizer ?: DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).setMaxResultCount(CANDIDATES).build(),
        ).also { recognizer = it }
        val input = ReadOptions.input(run, h)
        val ink = MlInk.builder()
        for (s in input.strokes) {
            val b = MlInk.Stroke.builder()
            for (k in s.xs.indices) b.addPoint(MlInk.Point.create(s.xs[k], s.ys[k], s.ts[k]))
            ink.addStroke(b.build())
        }
        val area = WritingArea(input.width, input.height)
        val built = ink.build()
        val margins = ArrayList<Float>()
        fun recognize(pre: String): List<String> {
            val context = RecognitionContext.builder().setPreContext(pre).setWritingArea(area).build()
            val cands = runCatching { Tasks.await(r.recognize(built, context), TIMEOUT_S, TimeUnit.SECONDS) }.getOrNull()?.candidates.orEmpty()
            // Scores: lower is likelier; the gap between the first two says how sure the first is.
            val a = cands.getOrNull(0)?.score
            val b = cands.getOrNull(1)?.score
            if (a != null && b != null) margins += b - a
            return cands.map { it.text }
        }
        // Read twice: as is, and after a bit of arithmetic, which steers the language model
        // towards digits and operators. The guesses are interleaved, plain ones first.
        val plain = recognize("")
        val math = recognize(MATH_CONTEXT)
        if (plain.isEmpty() && math.isEmpty()) return emptyList()
        doubts[key] = Doubt(plain.firstOrNull() == math.firstOrNull(), margins.minOrNull())
        val texts = ArrayList<String>()
        for (k in 0 until max(plain.size, math.size)) {
            plain.getOrNull(k)?.let { if (it !in texts) texts += it }
            math.getOrNull(k)?.let { if (it !in texts) texts += it }
        }
        cache[cacheKey] = texts
        return texts
    }

    /**
     * How sure the reader was of a run it read: both readings (plain and after arithmetic) put
     * the same text first; [margin] the smallest score gap between first and second guess
     * (null when the model gives no scores).
     */
    class Doubt(val agree: Boolean, val margin: Float?)

    // Math thread only, as the cache, and as large.
    private val doubts = object : LinkedHashMap<String, Doubt>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Doubt>?) = size > CACHE_SIZE
    }

    /** What the reader made of [run] when it last read it; null when it has not (math thread). */
    internal fun doubt(run: List<Ink>): Doubt? = doubts[signature(run)]

    private fun signature(run: List<Ink>): String {
        var hash = 1125899906842597L
        for (s in run) {
            hash = 31 * hash + s.xs.size
            for (k in s.xs.indices) {
                hash = 31 * hash + (s.xs[k] * 4).toInt()
                hash = 31 * hash + (s.ys[k] * 4).toInt()
            }
        }
        return "${run.size}:${java.lang.Long.toHexString(hash)}"
    }

    private companion object {
        const val CANDIDATES = 5
        const val MATH_CONTEXT = "12+3*4 = "
        const val CACHE_SIZE = 3000
        const val DOWNLOAD_TIMEOUT_MS = 5 * 60_000L
        const val TIMEOUT_S = 10L
        const val MAX_POINTS = 400
        const val MAX_STROKES = 6000
    }
}
