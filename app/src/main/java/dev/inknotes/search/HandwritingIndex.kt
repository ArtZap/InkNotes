package dev.inknotes.search

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import dev.inknotes.data.InkKind
import dev.inknotes.data.Notebook
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PageContent
import dev.inknotes.data.PageStroke
import dev.inknotes.editor.PageEdits
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One recognized line: best guess [text], other guesses [alternatives], where it is on the page. */
class TextLine(val text: String, val alternatives: List<String>, val box: RectF)

/** A search result: page [pageIndex] of [notebook]; [line] is null for a match in the title. */
class SearchHit(val notebook: Notebook, val pageIndex: Int, val line: TextLine?)

/**
 * Handwriting recognition (ML Kit Digital Ink, on device, Russian model downloaded once) and
 * the text index used for search. Recognized text is stored per notebook in `text.json`;
 * each page entry keeps a signature of the ink it was made from, so only changed pages are
 * recognized again. All index work runs on one background thread.
 */
class HandwritingIndex(private val repo: NotebookRepository, private val enabledPref: () -> Boolean, private val setEnabledPref: (Boolean) -> Unit) {

    sealed interface State {
        data object Off : State
        data object Downloading : State
        data object Ready : State
        class Failed(val message: String) : State
    }

    var state by mutableStateOf<State>(State.Off)
        private set

    /** Pages waiting for recognition in the current pass (for a progress line). */
    var pendingPages by mutableIntStateOf(0)
        private set

    /** Bumped whenever stored text changes, so open search results can refresh. */
    var version by mutableIntStateOf(0)
        private set

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "handwriting") }
    private val main = Handler(Looper.getMainLooper())
    private val model = DigitalInkRecognitionModel.builder(DigitalInkRecognitionModelIdentifier.RU).build()
    private val scheduled = HashMap<String, Runnable>() // main thread

    // Index thread only.
    private var recognizer: DigitalInkRecognizer? = null
    private val cache = HashMap<String, MutableMap<String, PageText>>()

    /** [stamp]: size and time of the page file the text was read from (null: from memory). */
    private class PageText(val signature: String, val lines: List<TextLine>, val stamp: String? = null)

    init {
        if (enabledPref()) enable()
    }

    /** Downloads the recognition model if needed (user action), then indexes the library. */
    fun enable() {
        if (state == State.Downloading || state == State.Ready) return
        state = State.Downloading
        val manager = RemoteModelManager.getInstance()
        val ready = {
            if (state == State.Downloading) {
                setEnabledPref(true)
                state = State.Ready
                indexAll()
            }
        }
        val failed = {
            if (state == State.Downloading) {
                state = State.Failed("Не удалось загрузить модель распознавания. Нужен интернет; без сервисов Google загрузка может не работать.")
            }
        }
        // A repeated download request of a present model may never complete: ask first,
        // and give up after a while so «Включить» can be pressed again.
        main.postDelayed({ failed() }, DOWNLOAD_TIMEOUT_MS)
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

    /**
     * Text of [strokes] (a lasso selection), line by line in reading order; null when
     * recognition is off or failed. [onResult] runs on the main thread.
     */
    fun recognizeText(strokes: List<PageStroke>, onResult: (String?) -> Unit) {
        if (state != State.Ready) return onResult(null)
        val ink = strokes.filter { it.kind.isInk }
        submit {
            val lines = recognize(ink)
            main.post { onResult(lines?.joinToString("\n") { it.text }) }
        }
    }

    /** Called after a page was saved; recognition waits a little for the writing to settle. */
    fun pageSaved(nb: Notebook, pageId: String, content: PageContent) {
        if (state != State.Ready) return
        val key = "${nb.id}/$pageId"
        scheduled.remove(key)?.let(main::removeCallbacks)
        val task = Runnable {
            scheduled.remove(key)
            submit { indexPage(nb.id, pageId, content) }
        }
        scheduled[key] = task
        main.postDelayed(task, SETTLE_MS)
    }

    /** Brings the text of every page in the library up to date (changed pages only). */
    fun indexAll() {
        if (state != State.Ready) return
        submit {
            val notebooks = repo.list()
            // A page whose file is as it was when its text was read is not opened at all.
            val todo = notebooks.associateWith { nb ->
                val text = load(nb.id)
                nb.pageIds.map { it to repo.pageStamp(nb, it) }.filter { (id, stamp) -> text[id]?.stamp != stamp }
            }.filterValues { it.isNotEmpty() }
            main.post { pendingPages = todo.values.sumOf { it.size } }
            for ((nb, pages) in todo) {
                var changed = false
                for ((pageId, stamp) in pages) {
                    changed = indexPage(nb.id, pageId, repo.loadPage(nb, pageId), stamp, save = false) || changed
                    main.post { pendingPages = (pendingPages - 1).coerceAtLeast(0) }
                }
                // Each notebook's text is written once, not once a page.
                if (changed) save(nb.id, load(nb.id))
            }
            for (nb in notebooks) prune(nb)
            main.post { pendingPages = 0 }
        }
    }

    /** Recognized lines of a page, top to bottom (empty if not recognized yet). */
    fun pageText(nb: Notebook, pageId: String, onResult: (List<TextLine>) -> Unit) = submit {
        val lines = load(nb.id)[pageId]?.lines.orEmpty()
        main.post { onResult(lines) }
    }

    /**
     * Finds [query] in notebook titles and handwritten text, in [only] one notebook or the
     * whole library; results arrive on the main thread.
     */
    fun search(query: String, only: Notebook? = null, onResult: (List<SearchHit>) -> Unit) = submit {
        val hits = ArrayList<SearchHit>()
        if (TextLines.normalize(query).isNotEmpty()) {
            val notebooks = if (only != null) listOf(only) else repo.list()
            for (nb in notebooks) {
                if (only == null && TextLines.matches(query, listOf(nb.title))) hits += SearchHit(nb, 0, null)
                val text = load(nb.id)
                nb.pageIds.forEachIndexed { index, pageId ->
                    for (line in text[pageId]?.lines.orEmpty()) {
                        if (TextLines.matches(query, listOf(line.text) + line.alternatives)) hits += SearchHit(nb, index, line)
                    }
                }
            }
        }
        main.post { onResult(hits) }
    }

    // ------------------------------------------------------------ index thread

    private fun submit(block: () -> Unit) {
        // A failure must cost a page's text, never the app.
        runCatching { exec.execute { runCatching(block) } }
    }

    /** Returns whether the notebook's text changed (and was saved, if [save]). */
    private fun indexPage(nbId: String, pageId: String, content: PageContent, stamp: String? = null, save: Boolean = true): Boolean {
        val pen = content.strokes.filter { it.kind.isInk }
        val signature = signature(pen)
        val text = load(nbId)
        val old = text[pageId]
        if (old?.signature == signature) {
            // Same writing: only the file's stamp is new (kept so the page is not opened again).
            if (stamp == null || old.stamp == stamp) return false
            text[pageId] = PageText(signature, old.lines, stamp)
            if (save) save(nbId, text)
            return true
        }
        val lines = recognize(pen) ?: return false
        text[pageId] = PageText(signature, lines, stamp)
        if (save) save(nbId, text)
        main.post { version++ }
        return true
    }

    /** Drops text of pages that no longer exist. */
    private fun prune(nb: Notebook) {
        val text = load(nb.id)
        if (text.keys.retainAll(nb.pageIds.toSet())) save(nb.id, text)
    }

    /** Null if recognition itself failed (the page is then retried on the next pass). */
    private fun recognize(pen: List<PageStroke>): List<TextLine>? {
        if (pen.isEmpty()) return emptyList()
        val r = recognizer ?: DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).setMaxResultCount(MAX_CANDIDATES).build(),
        ).also { recognizer = it }
        val boxes = pen.map { s -> PageEdits.bounds(s)?.let { TextLines.Box(it.left, it.top, it.right, it.bottom) } }
        val out = ArrayList<TextLine>()
        var before = ""
        val input = StrokeInput()
        // A page of real notes has a few dozen lines; a crafted one must not hold the thread for hours.
        for (group in TextLines.group(boxes).take(MAX_LINES_PER_PAGE)) {
            val ink = Ink.builder()
            var time = 0L
            val box = RectF()
            for (i in group) {
                val stroke = pen[i].stroke
                val inputs = stroke.inputs
                if (inputs.size == 0) continue
                val builder = Ink.Stroke.builder()
                for (k in 0 until inputs.size) {
                    inputs.populate(k, input)
                    builder.addPoint(Ink.Point.create(input.x, input.y, time + input.elapsedTimeMillis))
                }
                time += inputs.get(inputs.size - 1).elapsedTimeMillis + STROKE_PAUSE_MS
                ink.addStroke(builder.build())
                boxes[i]?.let { box.union(it.left, it.top, it.right, it.bottom) }
            }
            if (ink.isEmpty) continue
            val context = RecognitionContext.builder()
                .setPreContext(before.takeLast(PRE_CONTEXT))
                .setWritingArea(WritingArea(box.width().coerceAtLeast(1f), box.height().coerceAtLeast(1f)))
                .build()
            val result = runCatching { Tasks.await(r.recognize(ink.build(), context), RECOGNIZE_TIMEOUT_S, TimeUnit.SECONDS) }
                .getOrElse { return null }
            val texts = result.candidates.map { it.text.trim() }.filter { it.isNotEmpty() }
            if (texts.isEmpty()) continue
            out += TextLine(texts[0], texts.drop(1), box)
            before = texts[0]
        }
        return out
    }

    /** Cheap fingerprint of the ink: changes when strokes are added, erased or moved. */
    private fun signature(pen: List<PageStroke>): String {
        var h = 1125899906842597L
        val input = StrokeInput()
        for (s in pen) {
            val inputs = s.stroke.inputs
            h = 31 * h + inputs.size
            if (inputs.size == 0) continue
            for (k in intArrayOf(0, inputs.size / 2, inputs.size - 1)) {
                inputs.populate(k, input)
                h = 31 * h + (input.x * 10).toInt()
                h = 31 * h + (input.y * 10).toInt()
            }
        }
        return "${pen.size}:${java.lang.Long.toHexString(h)}"
    }

    private fun load(nbId: String): MutableMap<String, PageText> = cache.getOrPut(nbId) {
        val json = repo.loadTextIndex(nbId) ?: return@getOrPut HashMap()
        runCatching {
            val pages = JSONObject(json)
            pages.keys().asSequence().associateWithTo(HashMap()) { pageId ->
                val p = pages.getJSONObject(pageId)
                val lines = p.getJSONArray("lines")
                PageText(p.getString("sig"), lines = List(lines.length()) { i ->
                    val l = lines.getJSONObject(i)
                    val b = l.getJSONArray("b")
                    val alts = l.optJSONArray("a") ?: JSONArray()
                    TextLine(
                        l.getString("t"),
                        List(alts.length()) { alts.getString(it) },
                        RectF(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat()),
                    )
                }, stamp = p.optString("f").ifEmpty { null })
            }
        }.getOrDefault(HashMap())
    }

    private fun save(nbId: String, text: Map<String, PageText>) {
        val pages = JSONObject()
        for ((pageId, page) in text) {
            val lines = JSONArray()
            for (l in page.lines) {
                lines.put(
                    JSONObject()
                        .put("t", l.text)
                        .put("a", JSONArray(l.alternatives))
                        .put("b", JSONArray(listOf(l.box.left, l.box.top, l.box.right, l.box.bottom).map { it.toDouble() })),
                )
            }
            pages.put(pageId, JSONObject().put("sig", page.signature).put("lines", lines).apply { page.stamp?.let { put("f", it) } })
        }
        repo.saveTextIndexAsync(nbId, pages.toString())
    }

    private companion object {
        const val SETTLE_MS = 1500L
        const val MAX_CANDIDATES = 3
        const val PRE_CONTEXT = 20
        const val STROKE_PAUSE_MS = 150L
        const val RECOGNIZE_TIMEOUT_S = 20L
        const val MAX_LINES_PER_PAGE = 200
    }
}

/** Model download given up after this long (slow or blocked internet); «Включить» tries again. */
private const val DOWNLOAD_TIMEOUT_MS = 5 * 60_000L
