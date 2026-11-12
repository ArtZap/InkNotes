package dev.inknotes.ui

import kotlin.math.abs
import android.content.ContentResolver
import dev.inknotes.data.PdfGeometry
import dev.inknotes.data.PdfPageRef
import dev.inknotes.data.PdfText
import dev.inknotes.data.PdfWord
import dev.inknotes.data.PdfWords
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import dev.inknotes.data.InkKind
import dev.inknotes.data.DashStyle
import dev.inknotes.data.FillPattern
import dev.inknotes.data.HatchStyle
import dev.inknotes.data.Notebook
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PageBackground
import dev.inknotes.data.PageSize
import dev.inknotes.data.PageImage
import dev.inknotes.data.PageStroke
import dev.inknotes.data.PageContent
import dev.inknotes.data.PaperKind
import dev.inknotes.data.PdfExporter
import dev.inknotes.data.PdfPages
import dev.inknotes.editor.EraserMode
import dev.inknotes.editor.PageDocument
import dev.inknotes.editor.PageEditorView
import dev.inknotes.editor.PdfRegion
import dev.inknotes.editor.PageRenderer
import dev.inknotes.editor.PageEdits
import dev.inknotes.editor.PenButtonBinding
import dev.inknotes.editor.Stamps
import dev.inknotes.editor.Tool
import dev.inknotes.search.HandwritingIndex
import dev.inknotes.search.SearchHit
import dev.inknotes.math.MathNotes
import dev.inknotes.math.PageAnswer
import dev.inknotes.math.Printer
import dev.inknotes.search.TextLine
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * State of an open notebook: tool settings, the pages shown by [PageEditorView], one undo
 * history across all pages, page thumbnails, autosave, PDF export and `.inknote` sharing.
 */
class EditorController(
    private val repo: NotebookRepository,
    notebook: Notebook,
    val penButton: PenButtonBinding,
    private val prefs: AppPrefs,
    dark: Boolean,
    val handwriting: HandwritingIndex,
    val math: MathNotes,
) : PageEditorView.Listener, PageEditorView.PageSource {

    var dark by mutableStateOf(dark)
        private set

    /** The paper is dark (theme or chosen colour): neutral ink is shown light on it. */
    val paperDark get() = notebook.background(dark).color?.let(dev.inknotes.editor.PaperStyle::isDark) ?: dark

    /** Custom colours picked from the spectrum/palette, newest first. */
    var recentColors by mutableStateOf(prefs.recentColors)
        private set
    var importingPhoto by mutableStateOf(false)
        private set

    /** Short-lived message about export/share progress (shown in the toolbar). */
    var status by mutableStateOf<String?>(null)
        private set

    var notebook by mutableStateOf(notebook)
        private set

    /** Page in the middle of the screen. */
    var pageIndex by mutableIntStateOf(0)
        private set
    var tool by mutableStateOf(Tool.PEN)
        private set
    var penColor by mutableIntStateOf(PEN_COLORS[0])
        private set
    var penSize by mutableFloatStateOf(prefs.toolSize("pen", PEN_RANGE.default).coerceIn(PEN_RANGE.min, PEN_RANGE.max))
        private set
    var fountainColor by mutableIntStateOf(PEN_COLORS[0])
        private set
    var fountainSize by mutableFloatStateOf(prefs.toolSize("fountain", FOUNTAIN_RANGE.default).coerceIn(FOUNTAIN_RANGE.min, FOUNTAIN_RANGE.max))
        private set
    var neonColor by mutableIntStateOf(NEON_COLORS[0])
        private set
    var neonSize by mutableFloatStateOf(prefs.toolSize("neon", NEON_RANGE.default).coerceIn(NEON_RANGE.min, NEON_RANGE.max))
        private set
    var highlighterColor by mutableIntStateOf(HIGHLIGHTER_COLORS[0])
        private set
    var spoilerColor by mutableIntStateOf(SPOILER_COLORS[0])
        private set
    var spoilerSize by mutableFloatStateOf(prefs.toolSize("spoiler", SPOILER_RANGE.default).coerceIn(SPOILER_RANGE.min, SPOILER_RANGE.max))
        private set

    /** The pen line follows the pressure; off, it is one width all along. */
    var penPressure by mutableStateOf(prefs.penPressure)
        private set

    fun togglePenPressure() {
        penPressure = !penPressure
        prefs.penPressure = penPressure
        syncView()
        showStatus(if (penPressure) "Ручка чувствует нажим" else "Ручка без нажима: линия одной толщины")
    }

    /** The zoom window is open at the bottom of the page. */
    var zoomPadOpen by mutableStateOf(false)
        private set

    fun toggleZoomPad() {
        val v = view ?: return
        if (v.zoomPadOpen) v.closeZoomPad() else v.openZoomPad()
    }

    override fun onZoomPadChanged(open: Boolean) {
        zoomPadOpen = open
        if (open && tool != Tool.PEN && tool != Tool.FOUNTAIN && tool != Tool.HIGHLIGHTER && tool != Tool.NEON && tool != Tool.SPOILER) selectTool(Tool.PEN)
    }

    /** A zigzag over writing erases it. */
    var scribbleErase by mutableStateOf(prefs.scribbleErase)
        private set

    fun toggleScribbleErase() {
        scribbleErase = !scribbleErase
        prefs.scribbleErase = scribbleErase
        syncView()
    }

    /** The ruler is lying on the page. */
    /** Where the buttons are; read again on attach, as another tab may have changed it. */
    var toolbar by mutableStateOf(dev.inknotes.data.ToolbarLayout.decode(prefs.toolbarLayout))
        private set

    fun changeToolbar(layout: dev.inknotes.data.ToolbarLayout) {
        toolbar = layout
        prefs.toolbarLayout = layout.encode()
    }

    /** The choice of a ready-made layout, offered the first time a notebook is opened. */
    var toolbarIntro by mutableStateOf(!prefs.toolbarIntroShown)
        private set

    fun closeToolbarIntro() {
        toolbarIntro = false
        prefs.toolbarIntroShown = true
    }

    var rulerShown by mutableStateOf(false)
        private set

    fun toggleRuler() {
        rulerShown = view?.toggleRuler(protractor = false) ?: false
        protractorShown = false
    }

    /** The protractor is lying on the page (in place of the ruler). */
    var protractorShown by mutableStateOf(false)
        private set

    fun toggleProtractor() {
        protractorShown = view?.toggleRuler(protractor = true) ?: false
        rulerShown = false
        if (protractorShown) showStatus("Транспортир: пальцами двигать и поворачивать, пером — линия по низу или дуга по краю, наведите перо — угол")
    }

    /** The pen draws circles with the compass. */
    var compassOn by mutableStateOf(false)
        private set

    fun toggleCompass() {
        compassOn = !compassOn
        view?.compassOn = compassOn
        if (compassOn) showStatus("Циркуль: перо в центр, тяните до радиуса и отпустите — окружность; обведите часть круга — дуга")
    }

    /** A request to the screen from a key (open search, export, print, the timer); the screen clears it. */
    var uiRequest by mutableStateOf<String?>(null)

    private var clip: dev.inknotes.editor.PageEditorView.Clip? = null
    private var clipPage = -1

    fun copySelection(cut: Boolean = false) {
        val v = view ?: return
        val c = v.copySelection() ?: return
        clip = c
        clipPage = v.selectionPageIndex
        if (cut) v.deleteSelection()
        showStatus(if (cut) "Вырезано" else "Скопировано")
    }

    fun pasteClip() {
        val c = clip ?: return showStatus("Сначала скопируйте выделенное (Ctrl+C)")
        val v = view ?: return
        selectTool(Tool.LASSO)
        v.paste(pageIndex, c, samePlace = pageIndex == clipPage)
    }

    /** A tool chosen by holding its key: back to the one before when the key is let go. */
    private var heldKey = -1
    private var heldSince = 0L
    private var toolBeforeKey: Tool? = null

    /**
     * Keyboard shortcuts, for keys nothing else on the screen took (a text field gets its letters).
     * Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y, Ctrl+C / X / V / D, Delete, Ctrl+F, Ctrl+P, Ctrl+E,
     * PageUp / PageDown; P, H, E, L, N without Ctrl choose a tool (held: only while held).
     */
    fun handleShortcut(e: KeyEvent): Boolean {
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        val toolKeys = mapOf(
            KeyEvent.KEYCODE_P to Tool.PEN,
            KeyEvent.KEYCODE_H to Tool.HIGHLIGHTER,
            KeyEvent.KEYCODE_E to Tool.ERASER,
            KeyEvent.KEYCODE_L to Tool.LASSO,
            KeyEvent.KEYCODE_N to Tool.NEON,
        )
        if (!ctrl && !e.isAltPressed && e.keyCode in toolKeys) {
            val t = toolKeys.getValue(e.keyCode)
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                heldKey = e.keyCode
                heldSince = e.eventTime
                toolBeforeKey = tool
                selectTool(t)
            } else if (e.action == KeyEvent.ACTION_UP && heldKey == e.keyCode) {
                // Held longer than a tap: the tool was for that moment only.
                if (e.eventTime - heldSince > HOLD_KEY_MS) toolBeforeKey?.let(::selectTool)
                heldKey = -1
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return false
        when {
            ctrl && e.keyCode == KeyEvent.KEYCODE_Z && e.isShiftPressed -> redo()
            ctrl && e.keyCode == KeyEvent.KEYCODE_Z -> undo()
            ctrl && e.keyCode == KeyEvent.KEYCODE_Y -> redo()
            ctrl && e.keyCode == KeyEvent.KEYCODE_C -> copySelection()
            ctrl && e.keyCode == KeyEvent.KEYCODE_X -> copySelection(cut = true)
            ctrl && e.keyCode == KeyEvent.KEYCODE_V -> pasteClip()
            ctrl && e.keyCode == KeyEvent.KEYCODE_D -> duplicateSelection()
            ctrl && e.keyCode == KeyEvent.KEYCODE_F -> uiRequest = "search"
            ctrl && e.keyCode == KeyEvent.KEYCODE_P -> uiRequest = "print"
            ctrl && e.keyCode == KeyEvent.KEYCODE_E -> uiRequest = "export"
            ctrl && e.keyCode == KeyEvent.KEYCODE_T -> uiRequest = "timer"
            !ctrl && (e.keyCode == KeyEvent.KEYCODE_DEL || e.keyCode == KeyEvent.KEYCODE_FORWARD_DEL) && selectionCount > 0 -> deleteSelection()
            e.keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> goToPage(pageIndex + 1)
            e.keyCode == KeyEvent.KEYCODE_PAGE_UP -> goToPage(pageIndex - 1)
            else -> return false
        }
        return true
    }

    fun flipSelection(horizontal: Boolean) = view?.flipSelection(horizontal)

    fun addToTable(row: Boolean) = view?.addToTable(row)

    fun removeFromTable(row: Boolean) {
        if (view?.removeFromTable(row) == false) showStatus(if (row) "В таблице одна строка" else "В таблице один столбец")
    }

    /** The eyedropper is waiting for a touch on the page. */
    var pickingColor by mutableStateOf(false)
        private set
    var highlighterSize by mutableFloatStateOf(prefs.toolSize("highlighter", HIGHLIGHTER_RANGE.default).coerceIn(HIGHLIGHTER_RANGE.min, HIGHLIGHTER_RANGE.max))
        private set
    /** Highlighter passes over text are made level and fitted behind the letters. */
    var straightHighlighter by mutableStateOf(prefs.straightHighlighter)
        private set

    fun toggleStraightHighlighter() {
        straightHighlighter = !straightHighlighter
        prefs.straightHighlighter = straightHighlighter
        view?.straightHighlighter = straightHighlighter
    }

    var eraserSize by mutableFloatStateOf(prefs.toolSize("eraser", ERASER_RANGE.default).coerceIn(ERASER_RANGE.min, ERASER_RANGE.max))
        private set
    var eraserMode by mutableStateOf(EraserMode.WHOLE)
        private set

    /** What the eraser takes (everything, only the marker…); back to everything when the notebook is opened. */
    var eraserTarget by mutableStateOf(dev.inknotes.editor.EraserTarget.ALL)
        private set

    fun selectEraserTarget(t: dev.inknotes.editor.EraserTarget) {
        eraserTarget = t
        syncView()
    }
    var fillPattern by mutableStateOf(prefs.hatchPattern)
        private set
    var fillStyle by mutableStateOf(prefs.hatchStyle)
        private set

    /** Fills among the lasso selection (they can be restyled). */
    var selectedFills by mutableIntStateOf(0)
        private set

    /** The selection is a table: rows and columns can be added. */
    var selectionIsTable by mutableStateOf(false)
        private set

    /** Kinds of line among the selection that its line type can be changed from; empty: none. */
    var selectionLineKinds by mutableStateOf<Set<InkKind>>(emptySet())
        private set
    var fillColor by mutableIntStateOf(FILL_COLORS[0])
        private set
    var selectionCount by mutableIntStateOf(0)
        private set
    var hasPendingArea by mutableStateOf(false)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var learningPenButton by mutableStateOf(false)
    var penButtonLabel by mutableStateOf(penButton.describe())
        private set

    /** Page thumbnails by page id; filled lazily by [requestThumbnail]. */
    val thumbnails = mutableStateMapOf<String, ImageBitmap>()

    /** Bumped when page contents changed, so visible thumbnails ask for a refresh. */
    var thumbnailTick by mutableIntStateOf(0)
        private set

    private var lastInkTool = Tool.PEN
    private var lastPenButtonPress = 0L
    private var view: PageEditorView? = null
    private val docs = HashMap<String, PageDocument>()
    private val savedRevision = HashMap<String, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { flush() }
    private val thumbnailRunnable = Runnable { thumbnailTick++ }

    // Answers after "=" by page id, and the page revision they were computed from.
    private val mathAnswers = HashMap<String, List<PageAnswer>>()
    private val mathRevision = HashMap<String, Int>()
    private val mathRunnable = Runnable { requestMath() }

    // One history for the whole notebook: which page to undo/redo next.
    /** History steps: the pages each step changed (more than one when a selection moved between pages). */
    private val undoPages = ArrayDeque<List<String>>()
    private val redoPages = ArrayDeque<List<String>>()

    /** Pages edited inside [editsTogether], recorded as one step at its end. */
    private var group: MutableList<String>? = null

    private val photoCache = HashMap<String, Bitmap?>()
    private val pdfPages = PdfPages(repo)
    private val pdfBitmaps = HashMap<String, Bitmap>()
    private val pdfPending = HashSet<String>()

    private val thumbExec = Executors.newSingleThreadExecutor { r -> Thread(r, "thumbnails") }
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "page-load") }
    private val loading = HashSet<String>()

    // Pictures placed on pages, decoded off the main thread; LruCache is thread-safe, the
    // thumbnail thread reads it too.
    private val imageCache = object : LruCache<String, Bitmap>(IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val imagePending = HashSet<String>()
    private val imageExec = Executors.newSingleThreadExecutor { r -> Thread(r, "page-images") }
    var importingImage by mutableStateOf(false)
        private set
    private val thumbRevision = HashMap<String, Int>()

    /** Search hit to show once the view is attached (notebook opened from library search). */
    var pendingHit: SearchHit? = null

    /** Page to show when the editor first appears (a quick note: the empty page at the end). */
    var openAtPage: Int? = null

    fun attach(v: PageEditorView) {
        view = v
        toolbar = dev.inknotes.data.ToolbarLayout.decode(prefs.toolbarLayout)
        toolbarIntro = !prefs.toolbarIntroShown
        dev.inknotes.data.Layers.active = notebook.activeLayer
        rulerShown = false
        v.listener = this
        v.dark = dark
        syncView()
        applyBackground()
        v.source = this
        updateUndo()
        pendingHit?.let(::showHit)
        val start = openAtPage
        if (start != null) handler.post { view?.scrollToPage(start, 0f) }
        else if (pendingHit == null) returnPlace?.let { (page, place) -> handler.post { view?.restorePagePlace(page, place) } }
        pendingHit = null
        openAtPage = null
        math.onReady = { requestMath() }
        handler.post(mathRunnable)
    }

    /**
     * The editor screen went away (another tab, or the library): the notebook stays open.
     * [from]: the view that screen showed. A newer view may already be attached (two panes
     * changing places make the new view before the old one is gone): then it is left alone.
     */
    fun detach(from: PageEditorView? = null) {
        flush()
        if (from != null && view !== from) return
        // Where it was, to come back to in this tab.
        view?.let { v -> returnPlace = pageIndex to (v.pagePlace(pageIndex) ?: 0f) }
        math.onReady = null
        handler.removeCallbacks(mathRunnable)
        view?.listener = null
        view = null
        zoomPadOpen = false
    }

    /** The notebook's tab is closed: nothing of it runs any more. */
    fun close() {
        stopRecording()
        stopPlaying()
        detach()
        pdfPages.close()
        pdfText?.close()
        thumbExec.shutdown()
        loader.shutdown()
        imageExec.shutdown()
        cardExec.shutdown()
    }

    /** Page and its place on screen when the tab was left. */
    private var returnPlace: Pair<Int, Float>? = null

    // ------------------------------------------------------------ page source

    override val pageCount get() = notebook.pageIds.size

    override fun pageHeight(index: Int): Float = notebook.pageHeightUnits(notebook.pageIds[index])

    /** Millimetres of paper per page unit on page [index]. */
    fun mmPerUnit(index: Int = pageIndex): Float {
        val id = notebook.pageIds[index.coerceIn(0, notebook.pageIds.lastIndex)]
        return notebook.pageWidthMm(id) / notebook.pageWidthUnits(id)
    }

    override fun pageWidth(index: Int): Float = notebook.pageWidthUnits(notebook.pageIds[index])

    override fun document(index: Int): PageDocument = docFor(notebook.pageIds[index])

    override fun pdfBitmap(index: Int): Bitmap? {
        val pageId = notebook.pageIds[index]
        pdfBitmaps[pageId]?.let { return it }
        val ref = notebook.pdfPages[pageId] ?: return null
        val v = view ?: return null
        if (pdfPending.add(pageId)) {
            val widthPx = (v.resources.displayMetrics.widthPixels * PDF_RENDER_SCALE).toInt().coerceAtMost(MAX_PDF_WIDTH_PX)
            pdfTextPage(ref)
            pdfPages.renderAsync(notebook, ref, widthPx, pdfDark) { bitmap ->
                pdfPending.remove(pageId)
                if (bitmap != null && view === v) {
                    pdfBitmaps[pageId] = bitmap
                    v.refresh()
                }
            }
        }
        return null
    }

    override fun pdfRegion(index: Int): PdfRegion? {
        val nb = notebook
        val ref = nb.pdfPages[nb.pageIds[index]] ?: return null
        val pages = pdfPages
        val dark = pdfDark
        return PdfRegion { fullWidthPx, left, top, w, h -> pages.renderRegionBlocking(nb, ref, fullWidthPx, left, top, w, h, dark) }
    }

    override fun image(file: String): Bitmap? {
        if (file.startsWith(PageImage.TEXT_PREFIX)) return null
        imageCache.get(file)?.let { return it }
        val nb = notebook
        if (imagePending.add(file)) {
            runCatching {
                imageExec.execute {
                    val bitmap = repo.loadImage(nb, file)
                    handler.post {
                        imagePending.remove(file)
                        if (bitmap != null) {
                            imageCache.put(file, bitmap)
                            view?.refresh()
                        }
                    }
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- insert

    /** Copies the picked picture into the notebook and places it, selected, on the current page. */
    fun insertImage(uri: Uri, resolver: ContentResolver) {
        importingImage = true
        repo.importImageAsync(notebook, uri, resolver) { result ->
            importingImage = false
            val (file, pxW, pxH) = result ?: return@importImageAsync showStatus("Не удалось вставить картинку")
            // About 40% of the page width, smaller pictures not upscaled past 2× their pixels.
            placeNewImage(file, pxW, pxH, pageIndex)
        }
    }

    /** A stamp at the middle of the screen, selected so it can be dragged into place. */
    // ------------------------------------------------------------------- sound

    /** When the recording under way began (ms since 1970), or null. */
    var recordingSince by mutableStateOf<Long?>(null)
        private set
    private var recorder: android.media.MediaRecorder? = null
    private var recordingFile: File? = null
    private val pendingMarks = ArrayList<dev.inknotes.data.AudioMark>()

    /** Playing: which recording; [playPosition] polled by the screen. */
    var playing by mutableStateOf<dev.inknotes.data.Recording?>(null)
        private set
    var playPaused by mutableStateOf(false)
        private set
    private var player: android.media.MediaPlayer? = null

    /**
     * A finger tap on writing made during a recording plays it from that moment; such places
     * are underlined. On by itself in a notebook that has them (it was hidden behind a switch
     * before 0.35, so recordings looked as if nothing could be heard from them).
     */
    var listenByTouch by mutableStateOf(notebook.audioMarks.isNotEmpty())
        private set

    fun toggleListenByTouch() {
        listenByTouch = !listenByTouch
        if (listenByTouch && notebook.audioMarks.isEmpty()) showStatus("Пока нечего слушать: запишите звук и пишите во время записи")
        view?.refresh()
    }

    fun startRecording(context: Context) {
        if (recorder != null) return
        stopPlaying()
        val file = repo.newRecordingFile(notebook)
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(context) else @Suppress("DEPRECATION") android.media.MediaRecorder()
        val ok = runCatching {
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.path)
            r.prepare()
            r.start()
        }.isSuccess
        if (!ok) {
            runCatching { r.release() }
            file.delete()
            showStatus("Не удалось начать запись")
            return
        }
        recorder = r
        recordingFile = file
        pendingMarks.clear()
        recordingSince = System.currentTimeMillis()
    }

    fun stopRecording() {
        val r = recorder ?: return
        val since = recordingSince ?: return
        val file = recordingFile ?: return
        recorder = null
        recordingSince = null
        recordingFile = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!ok) {
            // Stopped right after starting: nothing was recorded.
            file.delete()
            return
        }
        val rec = dev.inknotes.data.Recording(file.name, since, System.currentTimeMillis() - since)
        val marks = pendingMarks.toList()
        notebook = repo.addRecording(notebook, rec, marks)
        pendingMarks.clear()
        if (marks.isNotEmpty()) {
            listenByTouch = true
            showStatus("Запись сохранена. Написанное во время неё подчёркнуто оранжевым — коснитесь пальцем, и включится звук")
        } else {
            showStatus("Запись сохранена. Во время неё ничего не написано — слушайте её в «Записи звука…»")
        }
        view?.refresh()
    }

    override fun onInkAdded(page: Int, bounds: android.graphics.RectF) {
        val since = recordingSince ?: return
        val file = recordingFile ?: return
        val pageId = notebook.pageIds.getOrNull(page) ?: return
        val at = System.currentTimeMillis() - since
        val last = pendingMarks.lastOrNull()
        // Strokes of one word or formula, written one after another, are one mark.
        if (last != null && last.pageId == pageId && at - last.atMs < MARK_JOIN_MS &&
            bounds.left < last.right + MARK_JOIN_GAP && bounds.right > last.left - MARK_JOIN_GAP &&
            bounds.top < last.bottom + MARK_JOIN_GAP && bounds.bottom > last.top - MARK_JOIN_GAP
        ) {
            pendingMarks[pendingMarks.lastIndex] = last.copy(
                left = minOf(last.left, bounds.left), top = minOf(last.top, bounds.top),
                right = maxOf(last.right, bounds.right), bottom = maxOf(last.bottom, bounds.bottom),
            )
        } else {
            pendingMarks += dev.inknotes.data.AudioMark(pageId, bounds.left, bounds.top, bounds.right, bounds.bottom, file.name, at)
        }
    }

    /** Plays [rec] from [fromMs]. */
    fun play(rec: dev.inknotes.data.Recording, fromMs: Long = 0) {
        val file = repo.recordingFile(notebook, rec.file) ?: return showStatus("Файл записи не найден")
        if (playing == rec && player != null) {
            player?.seekTo(fromMs.coerceAtLeast(0), android.media.MediaPlayer.SEEK_CLOSEST)
            player?.start()
            playPaused = false
            return
        }
        stopPlaying()
        val p = android.media.MediaPlayer()
        val ok = runCatching {
            p.setDataSource(file.path)
            p.prepare()
            p.seekTo(fromMs.coerceAtLeast(0), android.media.MediaPlayer.SEEK_CLOSEST)
            p.start()
        }.isSuccess
        if (!ok) {
            p.release()
            return showStatus("Не удалось включить запись")
        }
        p.setOnCompletionListener { stopPlaying() }
        player = p
        playing = rec
        playPaused = false
    }

    fun togglePause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            playPaused = true
        } else {
            p.start()
            playPaused = false
        }
    }

    fun seekPlaying(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0), android.media.MediaPlayer.SEEK_CLOSEST)
    }

    fun playPosition(): Long = runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: 0L

    fun stopPlaying() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playing = null
        playPaused = false
    }

    fun deleteRecording(rec: dev.inknotes.data.Recording) {
        if (playing == rec) stopPlaying()
        notebook = repo.deleteRecording(notebook, rec)
        view?.refresh()
    }

    override fun listenAt(page: Int, x: Float, y: Float): Boolean {
        if (!listenByTouch) return false
        val pageId = notebook.pageIds.getOrNull(page) ?: return false
        val doc = docs[pageId]
        val mark = notebook.audioMarks.lastOrNull { it.pageId == pageId && it.contains(x, y, MARK_SLOP) && (doc == null || hasInk(doc, it)) } ?: return false
        val rec = notebook.recordings.firstOrNull { it.file == mark.file } ?: return false
        // A little before the writing began: what was being said then.
        play(rec, (mark.atMs - LISTEN_LEAD_MS).coerceAtLeast(0))
        return true
    }

    override fun soundMarks(index: Int): List<android.graphics.RectF> {
        if (!listenByTouch) return emptyList()
        val pageId = notebook.pageIds.getOrNull(index) ?: return emptyList()
        val doc = docs[pageId]
        // Erased writing has no sound any more.
        return notebook.audioMarks.filter { it.pageId == pageId && (doc == null || hasInk(doc, it)) }
            .map { android.graphics.RectF(it.left, it.top, it.right, it.bottom) }
    }

    // ------------------------------------------------------------------ layers

    private fun saveLayers(layers: List<dev.inknotes.data.LayerInfo>, active: Int = notebook.activeLayer) {
        notebook = repo.setLayers(notebook, layers, active)
        dev.inknotes.data.Layers.active = notebook.activeLayer
        view?.clearSelection()
        syncView()
        invalidateThumbnails()
    }

    /** Writing goes onto layer [i] from now on (shown and unlocked, so it can be seen). */
    fun setActiveLayer(i: Int) {
        val list = notebook.layerList().toMutableList()
        if (i !in list.indices) return
        list[i] = list[i].copy(visible = true, locked = false)
        saveLayers(list, i)
    }

    fun toggleLayerVisible(i: Int) {
        val list = notebook.layerList().toMutableList()
        if (i !in list.indices) return
        list[i] = list[i].copy(visible = !list[i].visible)
        saveLayers(list)
        if (!list[i].visible && i == notebook.activeLayer) showStatus("Новое пишется на скрытый слой: выберите другой")
    }

    fun toggleLayerLocked(i: Int) {
        val list = notebook.layerList().toMutableList()
        if (i !in list.indices) return
        list[i] = list[i].copy(locked = !list[i].locked)
        saveLayers(list)
    }

    fun addLayer(name: String) {
        val list = notebook.layerList()
        if (list.size >= dev.inknotes.data.Layers.MAX) return showStatus("Не больше ${dev.inknotes.data.Layers.MAX} слоёв")
        saveLayers(list + dev.inknotes.data.LayerInfo(name.trim().ifEmpty { "Слой ${list.size + 1}" }), list.size)
    }

    fun renameLayer(i: Int, name: String) {
        val list = notebook.layerList().toMutableList()
        if (i !in list.indices || name.isBlank()) return
        list[i] = list[i].copy(name = name.trim())
        saveLayers(list)
    }

    /** Removes layer [i]; what is on it moves to the main layer (layers after it move down one). */
    fun deleteLayer(i: Int) {
        val list = notebook.layerList()
        if (i <= 0 || i !in list.indices) return
        flush()
        fun map(l: Int) = when {
            l == i -> 0
            l > i -> l - 1
            else -> l
        }
        editsTogether {
            for (pageId in notebook.pageIds) {
                val doc = docFor(pageId)
                if (doc.strokes.none { it.layer >= i } && doc.fills.none { it.layer >= i } && doc.images.none { it.layer >= i }) continue
                doc.edit(
                    doc.strokes.map { if (it.layer >= i) it.onLayer(map(it.layer)) else it },
                    doc.fills.map { if (it.layer >= i) it.copy(layer = map(it.layer)) else it },
                    doc.images.map { if (it.layer >= i) it.onLayer(map(it.layer)) else it },
                )
            }
        }
        saveLayers(list.filterIndexed { k, _ -> k != i }, map(notebook.activeLayer))
        view?.refresh()
    }

    fun moveSelectionToLayer(i: Int) {
        if (i !in notebook.layerList().indices) return
        view?.moveSelectionToLayer(i)
        showStatus("Перенесено на слой «${notebook.layerList()[i].name}»")
    }

    // ------------------------------------------------------------- typed text

    var editingText by mutableStateOf(false)

    /** The selection is one text box (it can be edited). */
    var selectedText by mutableStateOf<String?>(null)
        private set

    /** Typed text in the middle of the page in view, in the pen's colour. */
    fun insertText(text: String) {
        val v = view ?: return
        if (text.isBlank()) return
        val page = pageIndex
        val c = v.visibleCenter(page)
        val width = pageWidth(page)
        val t = dev.inknotes.data.PageText(text.take(dev.inknotes.data.PageText.MAX_CHARS), TEXT_SIZE, penColor)
        val box = dev.inknotes.editor.TextBoxes.box(t, 0f, 0f, width * 0.7f)
        v.insert(page, images = listOf(box.moved(x = (c[0] - box.w / 2).coerceIn(0f, width - box.w), y = c[1] - box.h / 2)))
    }

    fun editSelectedText(text: String) {
        val v = view ?: return
        val old = v.selectedTextBox() ?: return
        if (text.isBlank()) {
            v.deleteSelection()
            return
        }
        v.replaceTextBox(dev.inknotes.editor.TextBoxes.edited(old, old.text!!.copy(text = text.take(dev.inknotes.data.PageText.MAX_CHARS))))
    }

    /** Changes the size of the selected text's letters (×[k]), its box fitted again. */
    fun scaleSelectedText(k: Float) {
        val v = view ?: return
        val old = v.selectedTextBox() ?: return
        val t = old.text!!
        v.replaceTextBox(dev.inknotes.editor.TextBoxes.edited(old, t.copy(size = (t.size * k).coerceIn(dev.inknotes.data.PageText.MIN_SIZE, dev.inknotes.data.PageText.MAX_SIZE))))
    }

    /**
     * The selected handwriting replaced by [text] typed in its place: as wide as the writing,
     * letters about as tall as its lines, in the colour of its ink.
     */
    fun replaceSelectionWithText(text: String) {
        val v = view ?: return
        val r = v.selectionRect() ?: return
        if (text.isBlank()) return
        val ink = v.selectedInk()
        val lines = text.lines().count { it.isNotBlank() }.coerceAtLeast(1)
        val size = (r.height() / lines * 0.55f).coerceIn(10f, 80f)
        val color = ink.groupingBy { it.stroke.brush.colorIntArgb }.eachCount().maxByOrNull { it.value }?.key ?: penColor
        val t = dev.inknotes.data.PageText(text.take(dev.inknotes.data.PageText.MAX_CHARS), size, color)
        val w = maxOf(r.width(), size * 3)
        v.replaceSelection(PageImage.textBox(t, r.left, r.top, w, dev.inknotes.editor.TextBoxes.height(t, w)))
        recognizedText = null
    }

    fun insertStamp(stamp: Stamps.Stamp) {
        val v = view ?: return
        val page = pageIndex
        val c = v.visibleCenter(page)
        selectTool(Tool.LASSO)
        val placed = Stamps.place(stamp, c[0], c[1], Stamps.SIZE, penColor, penSize)
        v.insert(page, strokes = placed.strokes, fills = placed.fills)
    }

    fun insertTable(rows: Int, cols: Int) {
        val v = view ?: return
        val page = pageIndex
        val c = v.visibleCenter(page)
        selectTool(Tool.LASSO)
        v.insert(page, strokes = Stamps.table(rows, cols, c[0], c[1], penColor, penSize, pageWidth(page)))
    }

    private fun docFor(pageId: String): PageDocument = docs[pageId] ?: install(pageId, repo.loadPage(notebook, pageId))

    private fun install(pageId: String, content: dev.inknotes.data.PageContent): PageDocument =
        PageDocument(pageId, content).also {
            docs[pageId] = it
            savedRevision[pageId] = it.revision
            it.onEdit = ::onPageEdited
        }

    private fun prefetch(index: Int) {
        val id = notebook.pageIds.getOrNull(index) ?: return
        if (id !in docs && id !in loading) loadedDocument(index)
    }

    /**
     * Pages are read in the background as they come into view, so scrolling never waits for
     * a page file: a page with thousands of strokes takes a noticeable moment to decode.
     */
    override fun loadedDocument(index: Int): PageDocument? {
        val pageId = notebook.pageIds.getOrNull(index) ?: return null
        docs[pageId]?.let {
            // The neighbours are read ahead, so a touch there finds them ready.
            prefetch(index - 1)
            prefetch(index + 1)
            return it
        }
        if (loading.add(pageId)) {
            val nb = notebook
            runCatching {
                loader.execute {
                    val content = runCatching { repo.loadPage(nb, pageId) }.getOrNull()
                    handler.post {
                        loading.remove(pageId)
                        if (content != null && pageId in notebook.pageIds && pageId !in docs) {
                            install(pageId, content)
                            view?.refresh()
                            handler.removeCallbacks(mathRunnable)
                            handler.postDelayed(mathRunnable, MATH_DELAY_MS / 2)
                        }
                    }
                }
            }.onFailure { loading.remove(pageId) }
        }
        return null
    }

    override fun thumbnail(index: Int): Bitmap? =
        notebook.pageIds.getOrNull(index)?.let { thumbnails[it] }?.asAndroidBitmap()

    /**
     * Pages far from the screen leave memory, unless they have unsaved changes or an undo
     * history: a notebook of a hundred full pages would otherwise hold all their strokes.
     */
    private fun unloadFarPages() {
        val keep = (pageIndex - KEEP_PAGES..pageIndex + KEEP_PAGES).mapNotNull { notebook.pageIds.getOrNull(it) }.toSet()
        val selectionPage = view?.selectionPageIndex?.let { notebook.pageIds.getOrNull(it) }
        val drop = docs.filter { (id, doc) ->
            id !in keep && id != selectionPage && savedRevision[id] == doc.revision && !doc.canUndo && !doc.canRedo
        }.keys
        for (id in drop) docs.remove(id)
    }

    // ------------------------------------------------------------------ PDF

    /** Reads words, links and contents of the notebook's PDFs (made when the editor is first shown). */
    private var pdfText: PdfText? = null
    private val pdfTextPages = object : LinkedHashMap<String, PdfText.Page>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PdfText.Page>?) = size > MAX_PDF_TEXT_PAGES
    }
    private val pdfTextPending = HashSet<String>()

    /** Words of each PDF page in its page units, with the layout they were placed for. */
    private val pdfWordCache = HashMap<String, Pair<PdfPageRef, List<PdfWord>>>()

    /** PDF pages shown dark. */
    var pdfDark by mutableStateOf(prefs.pdfDark)
        private set

    fun togglePdfDark() {
        pdfDark = !pdfDark
        prefs.pdfDark = pdfDark
        pdfBitmaps.clear()
        view?.pdfDark = pdfDark
        view?.refresh()
        showStatus(if (pdfDark) "PDF тёмный: светлый текст на тёмном" else "PDF как есть")
    }

    /** The pen picks words of PDF text instead of writing. */
    var pdfTextOn by mutableStateOf(false)
        private set

    fun togglePdfText() {
        if (!pdfTextOn && notebook.pdfPages.isEmpty()) {
            showStatus("В тетради нет страниц PDF")
            return
        }
        pdfTextOn = !pdfTextOn
        if (pdfTextOn && compassOn) toggleCompass()
        view?.pdfTextOn = pdfTextOn
        if (pdfTextOn) showStatus("Текст PDF: проведите пером по словам — их можно скопировать или выделить маркером")
    }

    /** Words of PDF text picked with the pen and what they say. */
    class PdfPick(val page: Int, val from: Int, val to: Int, val text: String)

    var pdfPick by mutableStateOf<PdfPick?>(null)
        private set

    override fun onPdfTextPicked(page: Int, from: Int, to: Int) {
        val words = if (page >= 0) pdfWords(page) else null
        pdfPick = if (words == null || from < 0) null else PdfPick(page, from, to, PdfWords.text(words, from, to))
    }

    fun highlightPdfPick() {
        view?.highlightPdfText()
        pdfPick = null
    }

    fun closePdfPick() {
        view?.clearPdfText()
        pdfPick = null
    }

    private fun pdfReader(): PdfText? {
        pdfText?.let { return it }
        val ctx = view?.context ?: return null
        return runCatching { PdfText(ctx) }.getOrNull()?.also { pdfText = it }
    }

    private fun pdfKey(ref: PdfPageRef) = "${ref.file}#${ref.index}"

    /** Words and links of the PDF page under [ref]; null while they are read (the view is refreshed then). */
    private fun pdfTextPage(ref: PdfPageRef): PdfText.Page? {
        val key = pdfKey(ref)
        pdfTextPages[key]?.let { return it }
        val reader = pdfReader() ?: return null
        val file = repo.pdfFile(notebook, ref.file) ?: return null
        if (pdfTextPending.add(key)) {
            reader.pageAsync(file, ref.index) { page ->
                pdfTextPending.remove(key)
                pdfTextPages[key] = page ?: PdfText.Page(emptyList(), emptyList())
                view?.refresh()
            }
        }
        return null
    }

    /** From shares (0…1) of the PDF page as shown to page units. */
    private fun unitToPage(ref: PdfPageRef) = PdfGeometry.then(PdfGeometry.scale(ref.width, ref.height), ref.pdfToUnits())

    override fun pageLocked(index: Int): Boolean =
        notebook.pageIds.getOrNull(index)?.let { repo.isLocked(notebook, it) } == true

    override fun onPageLocked(page: Int) {
        showStatus("Страницу ${page + 1} не удалось прочитать, она открыта только для просмотра. Файл не тронут — откройте тетрадь заново позже.")
    }

    override fun pdfWords(index: Int): List<PdfWord>? {
        val pageId = notebook.pageIds.getOrNull(index) ?: return null
        val ref = notebook.pdfPages[pageId] ?: return null
        pdfWordCache[pageId]?.takeIf { it.first == ref }?.let { return it.second }
        val page = pdfTextPage(ref) ?: return null
        val m = unitToPage(ref)
        val words = page.words.map { it.mapped(m) }
        pdfWordCache[pageId] = ref to words
        return words
    }

    /** A link of the PDF itself at ([x], [y]) of page [pageId]: to another of its pages or to the web. */
    private fun followPdfLink(pageId: String, x: Float, y: Float): Boolean {
        val ref = notebook.pdfPages[pageId] ?: return false
        val page = pdfTextPage(ref) ?: return false
        if (page.links.isEmpty()) return false
        val (u, v) = PdfGeometry.map(PdfGeometry.invert(unitToPage(ref)), x, y)
        val link = page.links.lastOrNull { it.contains(u, v) } ?: return false
        val url = link.url
        if (url != null) {
            val ctx = view?.context ?: return false
            val ok = runCatching {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            showStatus(if (ok) "Открываю $url" else "Нечем открыть ссылку $url")
            return true
        }
        val target = notebook.pageIds.indexOfFirst { id -> notebook.pdfPages[id]?.let { it.file == ref.file && it.index == link.page } == true }
        if (target < 0) {
            showStatus("Страницы ${link.page + 1} этого PDF нет в тетради")
            return true
        }
        val tref = notebook.pdfPages.getValue(notebook.pageIds[target])
        goToPage(target, PdfGeometry.map(unitToPage(tref), 0f, link.y).second.coerceAtLeast(0f))
        showStatus("Страница ${target + 1}")
        return true
    }

    /** Is the page shown now a PDF page, and are there any in the notebook. */
    val hasPdf get() = notebook.pdfPages.isNotEmpty()

    fun pdfRef(index: Int = pageIndex): PdfPageRef? = notebook.pageIds.getOrNull(index)?.let { notebook.pdfPages[it] }

    /**
     * PDF pages laid out anew: turned by [rotateBy] degrees more and/or with [margins] round
     * them; this page or every PDF page ([all]). What is written moves with the PDF.
     */
    fun setPdfLayout(all: Boolean, rotateBy: Int? = null, margins: PdfGeometry.Margins? = null) {
        val here = notebook.pageIds.getOrNull(pageIndex)
        val ids = if (all) notebook.pageIds.filter { it in notebook.pdfPages } else listOfNotNull(here?.takeIf { it in notebook.pdfPages })
        if (ids.isEmpty()) {
            showStatus("Это не страница PDF")
            return
        }
        flush()
        view?.clearSelection()
        closePdfPick()
        val changed = HashMap<String, PdfPageRef>()
        val moves = HashMap<String, FloatArray>()
        for (id in ids) {
            val old = notebook.pdfPages.getValue(id)
            val new = old.copy(
                rotation = rotateBy?.let { PdfGeometry.normalRotation(old.rotation + it) } ?: old.rotation,
                margins = margins?.sane() ?: old.margins,
            )
            if (new == old) continue
            changed[id] = new
            moves[id] = PdfGeometry.moveContent(old.width, old.height, old.rotation, old.margins, new.rotation, new.margins, Notebook.PAGE_UNITS)
        }
        if (changed.isEmpty()) return
        val nb = notebook
        for ((id, m) in moves) {
            val content = docs[id]?.content() ?: repo.loadPage(nb, id)
            val moved = movedContent(content, m)
            repo.savePageAsync(nb, id, moved)
            handwriting.pageSaved(nb, id, moved)
            val open = docs.remove(id) != null
            savedRevision.remove(id)
            if (open) install(id, moved)
            pdfBitmaps.remove(id)
            pdfWordCache.remove(id)
            thumbnails.remove(id)
            thumbRevision.remove(id)
        }
        // Undo steps of the moved pages would put the writing back where the PDF no longer is.
        for (history in listOf(undoPages, redoPages)) {
            val kept = history.map { step -> step.filter { it !in moves } }.filter { it.isNotEmpty() }
            history.clear()
            history.addAll(kept)
        }
        fun mapY(id: String, y: Float): Float {
            val m = moves[id] ?: return y
            return PdfGeometry.map(m, Notebook.PAGE_UNITS / 2, y).second.coerceAtLeast(0f)
        }
        val toc = nb.toc.map { if (it.pageId in moves) it.copy(y = mapY(it.pageId, it.y)) else it }
        val links = nb.links.map { l ->
            val m = moves[l.pageId]
            var out = if (m == null) l else {
                val (x1, y1) = PdfGeometry.map(m, l.left, l.top)
                val (x2, y2) = PdfGeometry.map(m, l.right, l.bottom)
                l.copy(left = min(x1, x2), top = min(y1, y2), right = max(x1, x2), bottom = max(y1, y2))
            }
            if (l.target in moves) out = out.copy(targetY = mapY(l.target, l.targetY))
            out
        }
        notebook = repo.setPdfPages(nb, changed, toc, links)
        updateUndo()
        view?.relayout()
        goToPage(pageIndex)
    }

    private fun movedContent(c: dev.inknotes.data.PageContent, m: FloatArray): dev.inknotes.data.PageContent {
        val t = PageEdits.Transform(
            android.graphics.Matrix().apply { setValues(PdfGeometry.toMatrixValues(m)) },
            PdfGeometry.scaleOf(m),
            PdfGeometry.degreesOf(m),
        )
        return dev.inknotes.data.PageContent(
            c.strokes.map { PageEdits.transform(it, t) },
            c.fills.map { PageEdits.transform(it, t) },
            c.images.map { PageEdits.transform(it, t) },
        )
    }

    var insertingPdf by mutableStateOf(false)
        private set

    /** The pages of another PDF put in after the page shown, its contents added to the notebook's. */
    fun insertPdf(uri: android.net.Uri, resolver: ContentResolver, context: Context) {
        if (insertingPdf) return
        insertingPdf = true
        showStatus("Вставляю страницы PDF…")
        flush()
        val id = notebook.id
        repo.copyPdfAsync(notebook, uri, resolver, outline = { PdfText.outlineOf(context, it) }) { part ->
            insertingPdf = false
            if (part == null) {
                showStatus("Не удалось открыть PDF")
                return@copyPdfAsync
            }
            if (notebook.id != id) return@copyPdfAsync
            val at = (pageIndex + 1).coerceAtMost(notebook.pageIds.size)
            notebook = repo.addPdfPages(notebook, at, part)
            view?.relayout()
            goToPage(at)
            showStatus("Вставлено страниц: ${part.refs.size}")
        }
    }

    /** The contents of the notebook's PDFs added to its own (entries already there are kept once). */
    fun readPdfOutline() {
        val reader = pdfReader() ?: return
        val files = notebook.pdfPages.values.map { it.file }.distinct()
        if (files.isEmpty()) {
            showStatus("В тетради нет страниц PDF")
            return
        }
        var left = files.size
        val found = ArrayList<dev.inknotes.data.Bookmark>()
        for (name in files) {
            val file = repo.pdfFile(notebook, name)
            if (file == null) {
                left--
                continue
            }
            reader.outlineAsync(file) { items ->
                // Pages of this PDF in the notebook, by their page in the PDF (the first if repeated).
                val byIndex = HashMap<Int, String>()
                for (pid in notebook.pageIds) notebook.pdfPages[pid]?.takeIf { it.file == name }?.let { byIndex.putIfAbsent(it.index, pid) }
                val n = (byIndex.keys.maxOrNull() ?: -1) + 1
                val ids = List(n) { byIndex[it] ?: "" }
                val refs = List(n) { i -> byIndex[i]?.let(notebook.pdfPages::get) ?: PdfPageRef(name, i, 1f, 1f) }
                found += repo.bookmarks(items, ids, refs).filter { it.pageId.isNotEmpty() }
                if (--left == 0) {
                    val have = notebook.toc.map { it.pageId to it.title.trim() }.toSet()
                    val add = found.filter { (it.pageId to it.title.trim()) !in have }
                    if (add.isNotEmpty()) notebook = repo.setToc(notebook, notebook.toc + add)
                    showStatus(if (found.isEmpty()) "В PDF нет оглавления" else "Оглавление PDF: добавлено ${add.size}")
                }
            }
        }
    }

    // ---------------------------------------------------------------- export

    /** What to export: PDF or PNG pictures, and which pages. */
    enum class ExportFormat(val ext: String, val mime: String) { PDF("pdf", "application/pdf"), PNG("png", "image/png") }

    sealed class ExportPages {
        data object All : ExportPages()
        data object Current : ExportPages()
        data class Range(val from: Int, val to: Int) : ExportPages()
    }

    /** Page indices of [sel], in order (a range given backwards is turned round). */
    fun exportIndices(sel: ExportPages): List<Int> {
        val n = notebook.pageIds.size
        return when (sel) {
            ExportPages.All -> (0 until n).toList()
            ExportPages.Current -> listOf(pageIndex.coerceIn(0, max(0, n - 1)))
            is ExportPages.Range -> {
                val a = (min(sel.from, sel.to) - 1).coerceIn(0, max(0, n - 1))
                val b = (max(sel.from, sel.to) - 1).coerceIn(0, max(0, n - 1))
                (a..b).toList()
            }
        }.filter { it < n }
    }

    /** File name of an export: the notebook's title, with the page or pages when not all. */
    fun exportName(pages: List<Int>, format: ExportFormat, page: Int? = null): String {
        val base = fileName(notebook.title)
        val all = pages.size == notebook.pageIds.size
        val part = when {
            page != null -> " — стр. ${page + 1}"
            all -> ""
            pages.size == 1 -> " — стр. ${pages[0] + 1}"
            else -> " — стр. ${pages.first() + 1}-${pages.last() + 1}"
        }
        return "$base$part.${format.ext}"
    }

    var exportPagesChoice: String
        get() = prefs.exportPages
        set(value) {
            prefs.exportPages = value
        }

    var exportFormat: ExportFormat
        get() = if (prefs.exportFormat == "png") ExportFormat.PNG else ExportFormat.PDF
        set(value) {
            prefs.exportFormat = value.ext
        }

    /** Writes [pages] as PDF to [out] or as PNG files (one per page) from [outFor], on a background thread. */
    private fun runExport(pages: List<Int>, format: ExportFormat, outFor: (page: Int, index: Int) -> java.io.OutputStream?, done: (Boolean) -> Unit) {
        flush()
        val nb = notebook
        status = if (format == ExportFormat.PDF) "Экспорт PDF…" else "Экспорт PNG…"
        Thread({
            val result = runCatching {
                if (format == ExportFormat.PDF) {
                    (outFor(-1, 0) ?: error("cannot open output")).use { out ->
                        PdfExporter.export(repo, nb, out, pages) { k, total ->
                            handler.post { status = "Экспорт PDF: $k / $total" }
                        }
                    }
                } else {
                    pages.forEachIndexed { k, i ->
                        (outFor(i, k) ?: error("cannot open output")).use { out -> PdfExporter.writePng(repo, nb, i, out) }
                        handler.post { status = "Экспорт PNG: ${k + 1} / ${pages.size}" }
                    }
                }
            }
            handler.post {
                status = null
                done(result.isSuccess)
            }
        }, "export").start()
    }

    /** Writes the whole notebook as PDF to [uri] (from the system "save as" dialog). */
    fun exportPdf(uri: Uri, resolver: ContentResolver) = exportTo(uri, resolver, notebook.pageIds.indices.toList(), ExportFormat.PDF)

    /** [pages] in [format] to [uri] chosen in the system "save as" dialog (PNG: one page). */
    fun exportTo(uri: Uri, resolver: ContentResolver, pages: List<Int>, format: ExportFormat) {
        if (pages.isEmpty()) return
        val only = if (format == ExportFormat.PNG) pages.take(1) else pages
        runExport(only, format, { _, _ -> resolver.openOutputStream(uri, "wt") }) { ok ->
            showStatus(if (!ok) "Не удалось сохранить" else if (format == ExportFormat.PDF) "PDF сохранён" else "Картинка сохранена")
        }
    }

    /** PNG pictures of [pages] into the folder [tree] (picked in the system dialog). */
    fun exportPngToFolder(tree: Uri, resolver: ContentResolver, pages: List<Int>) {
        if (pages.isEmpty()) return
        val dir = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        runExport(pages, ExportFormat.PNG, { page, _ ->
            val name = exportName(pages, ExportFormat.PNG, page).removeSuffix(".png")
            android.provider.DocumentsContract.createDocument(resolver, dir, "image/png", name)?.let { resolver.openOutputStream(it, "wt") }
        }) { ok -> showStatus(if (ok) "Сохранено картинок: ${pages.size}" else "Не удалось сохранить картинки") }
    }

    /** [pages] in [format] as files in the cache, handed to [onReady] as content:// URIs to share. */
    fun exportForSharing(context: Context, pages: List<Int>, format: ExportFormat, onReady: (List<Uri>) -> Unit) {
        if (pages.isEmpty()) return
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest shared files are kept
        val files = ArrayList<File>()
        runExport(pages, format, { page, _ ->
            val f = File(dir, if (format == ExportFormat.PDF) exportName(pages, format) else exportName(pages, format, page))
            synchronized(files) { files += f }
            f.outputStream()
        }) { ok ->
            if (ok) onReady(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
            else showStatus("Не удалось подготовить файл")
        }
    }

    /** Sends [pages] to the system print dialog (as the PDF the notebook exports to). */
    fun print(context: Context, pages: List<Int>) {
        if (pages.isEmpty()) return
        flush()
        val pm = context.getSystemService(Context.PRINT_SERVICE) as? android.print.PrintManager ?: return showStatus("Печать недоступна")
        val nb = notebook
        val name = exportName(pages, ExportFormat.PDF).removeSuffix(".pdf")
        val adapter = object : android.print.PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: android.print.PrintAttributes?,
                newAttributes: android.print.PrintAttributes,
                cancellationSignal: android.os.CancellationSignal,
                callback: LayoutResultCallback,
                extras: android.os.Bundle?,
            ) {
                if (cancellationSignal.isCanceled) return callback.onLayoutCancelled()
                val info = android.print.PrintDocumentInfo.Builder("$name.pdf")
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pages.size)
                    .build()
                callback.onLayoutFinished(info, oldAttributes == null)
            }

            override fun onWrite(
                ranges: Array<out android.print.PageRange>,
                destination: android.os.ParcelFileDescriptor,
                cancellationSignal: android.os.CancellationSignal,
                callback: WriteResultCallback,
            ) {
                Thread({
                    val result = runCatching {
                        java.io.FileOutputStream(destination.fileDescriptor).use { out -> PdfExporter.export(repo, nb, out, pages) { _, _ -> } }
                    }
                    handler.post {
                        when {
                            cancellationSignal.isCanceled -> callback.onWriteCancelled()
                            result.isSuccess -> callback.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                            else -> callback.onWriteFailed(result.exceptionOrNull()?.message)
                        }
                    }
                }, "print").start()
            }
        }
        pm.print(name, adapter, null)
    }

    /** Saves the notebook as an `.inknote` file at [uri] (system "save as" dialog). */
    fun saveNotebookFile(uri: Uri, resolver: ContentResolver) {
        flush()
        status = "Сохраняю файл…"
        repo.exportArchiveAsync(notebook, { resolver.openOutputStream(uri, "wt") }) { ok ->
            showStatus(if (ok) "Файл сохранён" else "Не удалось сохранить файл")
        }
    }

    /** Packs the notebook into a cache file and hands a shareable content:// URI to [onReady]. */
    fun shareNotebookFile(context: Context, onReady: (Uri) -> Unit) {
        flush()
        status = "Готовлю файл…"
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest shared file is kept
        val file = File(dir, fileName(notebook.title) + ".inknote")
        repo.exportArchiveAsync(notebook, { file.outputStream() }) { ok ->
            if (ok) {
                status = null
                onReady(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
            } else {
                showStatus("Не удалось подготовить файл")
            }
        }
    }

    fun fileName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(60).ifEmpty { "notebook" }

    fun showStatus(text: String) {
        status = text
        handler.postDelayed({ if (status == text) status = null }, 4000)
    }

    // ----------------------------------------------------------------- tools

    /** The pen button stands for the pen kinds and the fountain pen: the one chosen last. */
    var penIsFountain by mutableStateOf(tool == Tool.FOUNTAIN)
        private set

    fun selectTool(t: Tool) {
        if (t == Tool.PEN) penIsFountain = false
        if (t == Tool.FOUNTAIN) penIsFountain = true
        if (t == Tool.PEN || t == Tool.FOUNTAIN || t == Tool.HIGHLIGHTER || t == Tool.NEON || t == Tool.SPOILER) lastInkTool = t
        tool = t
        pickingColor = false
        syncView()
    }

    fun selectColor(color: Int) {
        // With something selected the colour bar recolours it, whatever the tool.
        if (selectionCount > 0 && tool != Tool.LASSO) {
            view?.recolorSelection(color)
            return
        }
        when (tool) {
            Tool.HIGHLIGHTER -> highlighterColor = color
            Tool.PEN -> penColor = color
            Tool.FOUNTAIN -> fountainColor = color
            Tool.NEON -> neonColor = color
            Tool.SPOILER -> spoilerColor = color
            Tool.AREA -> fillColor = color
            Tool.LASSO -> view?.recolorSelection(color)
            Tool.ERASER, Tool.LASER -> Unit
        }
        syncView()
    }



    /** Swatches the current tool always offers. */
    fun presetColors(): List<Int> = if (selectionCount > 0) PEN_COLORS else when (tool) {
        Tool.HIGHLIGHTER -> HIGHLIGHTER_COLORS
        Tool.NEON -> NEON_COLORS
        Tool.SPOILER -> SPOILER_COLORS
        Tool.AREA -> FILL_COLORS
        else -> PEN_COLORS
    }

    /** The user's own colours, in the order they were added, except twins of the tool's presets. */
    fun customChoices(): List<Int> = recentColors.filter { r -> presetColors().none { sameColor(it, r) } }

    /** Takes a custom colour out of the palette. */
    fun removeCustomColor(color: Int) {
        recentColors = recentColors.filter { (it and 0xFFFFFF) != (color and 0xFFFFFF) }
        prefs.recentColors = recentColors
    }

    /**
     * A colour from the picker or the eyedropper. One that matches a swatch already shown
     * selects that swatch instead of adding a twin. Highlighter colours are always translucent.
     */
    fun pickCustomColor(color: Int) {
        val opaque = color or 0xFF000000.toInt()
        val existing = (presetColors() + recentColors).firstOrNull { sameColor(it, opaque) }
        if (existing == null) {
            // New colours go to the end of the palette; the oldest leave only past the limit.
            recentColors = (recentColors + opaque).takeLast(MAX_CUSTOM)
            prefs.recentColors = recentColors
        }
        val chosen = (existing ?: opaque) or 0xFF000000.toInt()
        // A recoloured highlighter stroke keeps its own translucency (see PageEdits.recolor).
        selectColor(if (tool == Tool.HIGHLIGHTER && selectionCount == 0) (chosen and 0x00FFFFFF) or 0x80000000.toInt() else chosen)
    }

    /** Same colour to the eye: every channel within a few steps (alpha ignored). */
    private fun sameColor(a: Int, b: Int): Boolean =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= COLOR_TOLERANCE &&
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= COLOR_TOLERANCE &&
            abs((a and 0xFF) - (b and 0xFF)) <= COLOR_TOLERANCE

    /** Colour the current tool would paint with (for preselecting the picker). */
    fun currentColor(): Int = when (tool) {
        Tool.HIGHLIGHTER -> highlighterColor
        Tool.SPOILER -> spoilerColor
        Tool.AREA -> fillColor
        Tool.FOUNTAIN -> fountainColor
        Tool.NEON -> neonColor
        else -> penColor
    }

    /** Eyedropper on or off; the picked colour goes to the current tool like a custom one. */
    fun toggleColorPick() {
        pickingColor = !pickingColor
        if (pickingColor) showStatus("Коснитесь места, откуда взять цвет")
        syncView()
    }

    /** What the pen tool draws: a pen line, pencil or dashes. */
    var penKind by mutableStateOf(prefs.penKind)
        private set

    fun selectPenKind(kind: InkKind) {
        penKind = kind
        prefs.penKind = kind
        syncView()
    }

    /** Room kept beside the pages (see [PageEditorView.pageShift]). */
    var pageShift by mutableFloatStateOf(prefs.pageShift)
        private set

    fun changePageShift(value: Float) {
        pageShift = value.coerceIn(-PageEditorView.MAX_PAGE_SHIFT, PageEditorView.MAX_PAGE_SHIFT)
        prefs.pageShift = pageShift
        syncView()
    }

    /** Pattern of new dashed lines. */
    var dashStyle by mutableStateOf(prefs.dashStyle)
        private set

    fun changeDashStyle(style: DashStyle) {
        dashStyle = style.sane()
        prefs.dashStyle = dashStyle
        syncView()
    }

    /** The current dash pattern given to the selected dashed lines. */
    fun applyDashToSelection() {
        view?.restyleSelectedDashes(dashStyle)
    }

    /** Width the selected lines show (page units), or null when no lines are selected. */
    var selectionLineWidth by mutableStateOf<Float?>(null)
        private set

    /** Live while the slider moves; [endSelectionLineWidth] makes it one undo step. */
    fun setSelectionLineWidth(width: Float) {
        view?.setSelectionLineWidth(width)
        selectionLineWidth = width
    }

    fun endSelectionLineWidth() {
        view?.endSelectionLineWidth()
    }

    /** Text read from the selected handwriting, shown to copy (null: none). */
    var recognizedText by mutableStateOf<String?>(null)

    /** Reads the handwriting in the lasso selection as text. */
    fun recognizeSelection() {
        val ink = view?.selectedInk().orEmpty()
        when {
            ink.isEmpty() -> showStatus("В выделении нет рукописных строк")
            handwriting.state != HandwritingIndex.State.Ready ->
                showStatus("Сначала включите распознавание почерка: «Включить» под строкой поиска в библиотеке")
            else -> {
                showStatus("Распознаю…")
                handwriting.recognizeText(ink) { text ->
                    if (text == null) showStatus("Не удалось распознать") else recognizedText = text
                }
            }
        }
    }

    /** The answer a finger tapped, shown with how it was found (null: none). */
    var tappedAnswer by mutableStateOf<PageAnswer?>(null)

    /**
     * Saves the page of [answer] as a test case with the answer it should have given, and offers
     * to send the file ([onReady] gets a shareable link). Kept in the app's files too.
     */
    fun reportWrongAnswer(context: Context, answer: PageAnswer, expected: String, onReady: (Uri) -> Unit) {
        val pageId = mathAnswers.entries.firstOrNull { (_, list) -> list.any { it === answer } }?.key ?: return showStatus("Страница ответа не найдена")
        val doc = docs[pageId] ?: return
        showStatus("Сохраняю пример…")
        math.report(doc.strokes, notebook.pageWidthUnits(pageId), answer.x, answer.y, expected) { json ->
            if (json == null) return@report showStatus("Не удалось сохранить пример")
            val name = "math-report-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())}.json"
            runCatching { context.getExternalFilesDir("math-reports")?.let { File(it, name).writeText(json) } }
            val shared = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(shared, name)
            runCatching { file.writeText(json) }.onFailure { return@report showStatus("Не удалось сохранить пример") }
            showStatus("Пример сохранён")
            onReady(FileProvider.getUriForFile(context, context.packageName + ".files", file))
        }
    }

    override fun onAnswerTapped(answer: PageAnswer) {
        tappedAnswer = answer
    }

    override fun onHint(text: String) = showStatus(text)

    override fun onColorPicked(color: Int) {
        pickingColor = false
        pickCustomColor(color)
    }

    fun setDarkTheme(value: Boolean) {
        if (dark == value) return
        dark = value
        view?.dark = value
        applyBackground()
        invalidateThumbnails()
    }

    fun setBackground(forDark: Boolean, bg: PageBackground) {
        notebook = repo.setBackground(notebook, forDark, bg)
        applyBackground()
        invalidateThumbnails()
    }

    fun importPhoto(forDark: Boolean, uri: Uri, resolver: ContentResolver) {
        importingPhoto = true
        repo.importPhotoAsync(notebook, uri, resolver) { name ->
            importingPhoto = false
            if (name != null) setBackground(forDark, PageBackground(PaperKind.PHOTO, name))
        }
    }

    /** Thumbnail source for the background dialog; decoded once per photo. */
    fun photo(name: String?): Bitmap? =
        name?.let { photoCache.getOrPut(it) { repo.loadPhoto(notebook, it) } }

    private fun applyBackground() {
        val v = view ?: return
        val bg = notebook.background(dark)
        v.paper = bg
        v.paperPhoto = if (bg.kind == PaperKind.PHOTO) photo(bg.photo) else null
    }

    fun selectEraserMode(m: EraserMode) {
        eraserMode = m
        syncView()
    }

    fun selectFillPattern(p: FillPattern) {
        fillPattern = p
        prefs.hatchPattern = p
        syncView()
    }

    fun changeFillStyle(style: HatchStyle) {
        fillStyle = style.sane()
        prefs.hatchStyle = fillStyle
        syncView()
    }

    fun applyFill() = view?.applyFill(fillPattern, fillColor, fillStyle)

    /** Current pattern and hatch settings onto the selected fills (one undo step). */
    fun restyleSelection() = view?.restyleSelection(fillPattern, fillStyle)

    fun cancelArea() = view?.clearPendingArea()

    fun deleteSelection() = view?.deleteSelection()

    fun duplicateSelection() = view?.duplicateSelection()

    fun clearSelection() = view?.clearSelection()

    fun selectSize(size: Float) {
        when (tool) {
            Tool.HIGHLIGHTER -> highlighterSize = size.also { prefs.setToolSize("highlighter", it) }
            Tool.FOUNTAIN -> fountainSize = size.also { prefs.setToolSize("fountain", it) }
            Tool.NEON -> neonSize = size.also { prefs.setToolSize("neon", it) }
            Tool.SPOILER -> spoilerSize = size.also { prefs.setToolSize("spoiler", it) }
            Tool.ERASER -> eraserSize = size.also { prefs.setToolSize("eraser", it) }
            else -> penSize = size.also { prefs.setToolSize("pen", it) }
        }
        syncView()
    }

    // ------------------------------------------------------------ undo / redo

    private fun onPageEdited(doc: PageDocument) {
        group?.let {
            if (doc.pageId !in it) it += doc.pageId
            return
        }
        record(listOf(doc.pageId))
    }

    private fun record(pages: List<String>) {
        undoPages.addLast(pages)
        if (undoPages.size > MAX_HISTORY) undoPages.removeFirst()
        redoPages.clear()
        for ((id, other) in docs) if (id !in pages) other.clearRedo()
        updateUndo()
    }

    /** Edits made by [block] undo and redo as one step. */
    override fun editsTogether(block: () -> Unit) {
        if (group != null) return block()
        val pages = ArrayList<String>()
        group = pages
        try {
            block()
        } finally {
            group = null
            if (pages.isNotEmpty()) record(pages)
        }
    }

    /** The undo button: not in the middle of a stroke or a drag, which would be recorded against the undone page. */
    fun undo() {
        if (view?.busy != true) undoStep()
    }

    fun redo() {
        if (view?.busy != true) redoStep()
    }

    private fun undoStep() {
        while (undoPages.isNotEmpty()) {
            val step = undoPages.removeLast()
            val done = step.asReversed().filter { docs[it]?.undo() == true }
            if (done.isEmpty()) continue
            redoPages.addLast(done.asReversed())
            contentReplaced(done.last())
            return
        }
        updateUndo()
    }

    private fun redoStep() {
        while (redoPages.isNotEmpty()) {
            val step = redoPages.removeLast()
            val done = step.filter { docs[it]?.redo() == true }
            if (done.isEmpty()) continue
            undoPages.addLast(done)
            contentReplaced(done.last())
            return
        }
        updateUndo()
    }

    /** Shows the page an undo/redo touched if it is off screen. */
    private fun contentReplaced(pageId: String) {
        view?.onContentReplaced()
        val index = notebook.pageIds.indexOf(pageId)
        if (index >= 0 && index != pageIndex) view?.scrollToPage(index)
        onDocumentChanged()
    }

    private fun updateUndo() {
        canUndo = undoPages.isNotEmpty()
        canRedo = redoPages.isNotEmpty()
    }

    // ------------------------------------------------------------------ pages

    /** Jumps to a search hit and highlights the found line. */
    fun showHit(hit: SearchHit) {
        val line = hit.line
        if (line == null) goToPage(hit.pageIndex) else view?.flash(hit.pageIndex, line.box)
    }

    fun searchHere(query: String, onResult: (List<SearchHit>) -> Unit) {
        flush()
        handwriting.search(query, notebook, onResult)
    }

    fun currentPageText(onResult: (List<TextLine>) -> Unit) {
        flush()
        handwriting.pageText(notebook, notebook.pageIds[pageIndex], onResult)
    }

    fun goToPage(index: Int, y: Float = 0f) {
        if (index !in notebook.pageIds.indices) return
        view?.scrollToPage(index, y)
    }

    // ------------------------------------------------------ contents and links

    /** Dialogs asked for from the selection bar. */
    var showLinkDialog by mutableStateOf(false)
    var showAddBookmark by mutableStateOf(false)
    var showLayersDialog by mutableStateOf(false)

    fun goToBookmark(b: dev.inknotes.data.Bookmark) = goToPage(notebook.pageIds.indexOf(b.pageId), b.y)

    /** A new entry of the table of contents at the selection (or the top of the current page). */
    fun addBookmark(title: String, page: Int, y: Float) {
        val pageId = notebook.pageIds.getOrNull(page) ?: return
        notebook = repo.setToc(notebook, notebook.toc + dev.inknotes.data.Bookmark(pageId, y, title.trim().ifEmpty { "Страница ${page + 1}" }))
        showStatus("Добавлено в оглавление")
    }

    fun renameBookmark(b: dev.inknotes.data.Bookmark, title: String) {
        notebook = repo.setToc(notebook, notebook.toc.map { if (it == b) it.copy(title = title.trim().ifEmpty { it.title }) else it })
    }

    fun deleteBookmark(b: dev.inknotes.data.Bookmark) {
        notebook = repo.setToc(notebook, notebook.toc - b)
    }

    /** Where a new entry or link from the selection goes: its page and top; else the page in view. */
    fun selectionPlace(): Pair<Int, android.graphics.RectF?> {
        val v = view
        val r = if (selectionCount > 0) v?.selectionRect() else null
        return (if (r != null && v != null) v.selectionPageIndex else pageIndex) to r
    }

    /** The selection's handwriting as text (for the title of an entry), or null when it cannot be read. */
    fun selectionText(onResult: (String?) -> Unit) {
        val ink = view?.selectedInk().orEmpty()
        if (ink.isEmpty() || handwriting.state != HandwritingIndex.State.Ready) return onResult(null)
        handwriting.recognizeText(ink) { onResult(it?.lines()?.firstOrNull { l -> l.isNotBlank() }?.trim()) }
    }

    /** Makes the selection a link to page [target] (at [targetY]). */
    fun linkSelection(target: Int, targetY: Float = 0f) {
        val (page, r) = selectionPlace()
        val pageId = notebook.pageIds.getOrNull(page) ?: return
        val targetId = notebook.pageIds.getOrNull(target) ?: return
        if (r == null) return
        val link = dev.inknotes.data.PageLink(pageId, r.left, r.top, r.right, r.bottom, targetId, targetY)
        // A new link over an old one replaces it.
        notebook = repo.setLinks(notebook, notebook.links.filterNot { it.pageId == pageId && overlaps(it, r) } + link)
        view?.clearSelection()
        view?.refresh()
        showStatus("Ссылка на страницу ${target + 1}: коснитесь её пальцем")
    }

    /** Links under the selection. */
    fun selectionLinks(): List<dev.inknotes.data.PageLink> {
        val (page, r) = selectionPlace()
        val pageId = notebook.pageIds.getOrNull(page) ?: return emptyList()
        if (r == null) return emptyList()
        return notebook.links.filter { it.pageId == pageId && overlaps(it, r) }
    }

    fun removeLinks(links: List<dev.inknotes.data.PageLink>) {
        notebook = repo.setLinks(notebook, notebook.links - links.toSet())
        view?.refresh()
    }

    private fun overlaps(l: dev.inknotes.data.PageLink, r: android.graphics.RectF) =
        l.left < r.right && l.right > r.left && l.top < r.bottom && l.bottom > r.top

    override fun links(index: Int): List<android.graphics.RectF> {
        val pageId = notebook.pageIds.getOrNull(index) ?: return emptyList()
        return notebook.links.filter { it.pageId == pageId }.map { android.graphics.RectF(it.left, it.top, it.right, it.bottom) }
    }

    override fun followLink(index: Int, x: Float, y: Float): Boolean {
        val pageId = notebook.pageIds.getOrNull(index) ?: return false
        val link = notebook.links.lastOrNull { it.pageId == pageId && it.contains(x, y) } ?: return followPdfLink(pageId, x, y)
        val target = notebook.pageIds.indexOf(link.target)
        if (target < 0) return false
        goToPage(target, link.targetY)
        showStatus("Страница ${target + 1}")
        return true
    }

    fun addPageAfterCurrent() = addPageAt(pageIndex + 1)

    /** The next stroke down a page opens room there (see [PageEditorView.insertingSpace]). */
    var insertingSpace by mutableStateOf(false)
        private set

    fun toggleInsertSpace() {
        insertingSpace = !insertingSpace
        if (insertingSpace) showStatus("Проведите вниз от места, где нужна строка; вверх — убрать пустое место")
        syncView()
    }

    override fun onSpaceDone() {
        insertingSpace = false
    }

    override fun addPageAfter(after: Int): Int? {
        if (after !in notebook.pageIds.indices) return null
        val pageId = notebook.pageIds[after]
        val size = pageId.takeIf { it !in notebook.pdfPages }?.let(notebook::pageSize) ?: PageSize.DEFAULT
        notebook = repo.insertPage(notebook, after + 1, size)
        view?.relayout()
        showStatus("Не поместившееся — на новой странице ${after + 2}")
        return after + 1
    }

    fun addPageAt(index: Int) {
        flush()
        // A new page continues the one before it: same paper size and orientation.
        val before = notebook.pageIds.getOrNull(index - 1)
        val size = before?.takeIf { it !in notebook.pdfPages }?.let(notebook::pageSize) ?: PageSize.DEFAULT
        notebook = repo.insertPage(notebook, index.coerceIn(0, notebook.pageIds.size), size)
        view?.relayout()
        goToPage(index)
    }

    fun deletePage(index: Int) {
        if (notebook.pageIds.size <= 1 || index !in notebook.pageIds.indices) return
        flush()
        val pageId = notebook.pageIds[index]
        notebook = repo.deletePage(notebook, pageId)
        docs.remove(pageId)
        savedRevision.remove(pageId)
        for (history in listOf(undoPages, redoPages)) {
            val kept = history.map { it - pageId }.filter { it.isNotEmpty() }
            history.clear()
            history.addAll(kept)
        }
        pdfBitmaps.remove(pageId)
        thumbnails.remove(pageId)
        updateUndo()
        view?.relayout()
        goToPage(index.coerceAtMost(notebook.pageIds.lastIndex))
    }

    /** Paper size of page [index], or of every paper page when [all]. */
    fun setPageSize(index: Int, size: PageSize, all: Boolean = false) {
        if (index !in notebook.pageIds.indices) return
        flush()
        val ids = if (all) notebook.pageIds else listOf(notebook.pageIds[index])
        notebook = repo.setPageSize(notebook, ids, size)
        invalidateThumbnails()
        view?.relayout()
        goToPage(index)
    }

    /** The size dialog's lock and unit, kept while the notebook is open. */
    var keepProportions by mutableStateOf(true)
    var sizeUnit by mutableStateOf(LengthUnit.MM)

    /** Width and height of the selection on paper, in millimetres. */
    fun selectionSizeMm(): Pair<Float, Float>? {
        val v = view ?: return null
        val (w, h) = v.selectionSize() ?: return null
        val k = mmPerUnit(v.selectionPageIndex)
        return w * k to h * k
    }

    fun resizeSelectionMm(wMm: Float, hMm: Float) {
        val v = view ?: return
        val k = mmPerUnit(v.selectionPageIndex)
        v.resizeSelection(wMm / k, hMm / k, proportional = keepProportions)
    }

    fun isPdfPage(index: Int = pageIndex) = notebook.pageIds.getOrNull(index)?.let { it in notebook.pdfPages } == true

    fun pageSize(index: Int = pageIndex): PageSize = notebook.pageIds.getOrNull(index)?.let(notebook::pageSize) ?: PageSize.DEFAULT

    /**
     * Moves page [from] to [to]. With [follow] the moved page is shown; otherwise the page
     * on screen stays where it is on screen (dragging thumbnails around).
     */
    fun movePage(from: Int, to: Int, follow: Boolean = false) {
        if (from !in notebook.pageIds.indices || to !in notebook.pageIds.indices || from == to) return
        val shown = notebook.pageIds.getOrNull(pageIndex)
        val place = view?.pagePlace(pageIndex)
        notebook = repo.movePage(notebook, from, to)
        view?.relayout()
        val now = notebook.pageIds.indexOf(shown)
        when {
            follow -> goToPage(to)
            place != null && now >= 0 -> view?.restorePagePlace(now, place)
        }
    }

    /** Puts a copy of page [index] right after it and shows the copy. */
    fun duplicatePage(index: Int) {
        if (index !in notebook.pageIds.indices) return
        flush()
        val pageId = notebook.pageIds[index]
        val content = docs[pageId]?.content() ?: repo.loadPage(notebook, pageId)
        // New elements: selection and undo go by identity, so the pages share none.
        val copy = PageContent(
            content.strokes.map { PageStroke(it.kind, it.stroke, it.dash, it.layer) },
            content.fills.map { it.copy() },
            content.images.map { it.moved() },
        )
        notebook = repo.duplicatePage(notebook, index, copy)
        handwriting.pageSaved(notebook, notebook.pageIds[index + 1], copy)
        view?.relayout()
        goToPage(index + 1)
    }

    // ------------------------------------------------------------- thumbnails

    /**
     * Renders a small picture of a page on a background thread unless the stored one is
     * current. Content is snapshotted on the main thread (page lists are immutable).
     */
    fun requestThumbnail(pageId: String) {
        val doc = docs[pageId]
        val revision = doc?.revision ?: -1
        if (thumbRevision[pageId] == revision && thumbnails.containsKey(pageId)) return
        thumbRevision[pageId] = revision
        val nb = notebook
        val content = doc?.content()
        val ref = nb.pdfPages[pageId]
        val bg = nb.background(dark)
        val photo = if (bg.kind == PaperKind.PHOTO) photo(bg.photo) else null
        val darkNow = dark
        runCatching {
            thumbExec.execute {
                // A broken page must cost a thumbnail, never the app: an uncaught throwable
                // on this thread would kill the process.
                val bitmap = runCatching { renderThumbnail(nb, pageId, content, ref, bg, photo, darkNow) }.getOrNull()
                    ?: return@execute
                handler.post { thumbnails[pageId] = bitmap.asImageBitmap() }
            }
        }
    }

    private fun renderThumbnail(
        nb: Notebook,
        pageId: String,
        content: dev.inknotes.data.PageContent?,
        ref: dev.inknotes.data.PdfPageRef?,
        bg: PageBackground,
        photo: Bitmap?,
        darkNow: Boolean,
    ): Bitmap {
        val c = content ?: repo.loadPage(nb, pageId)
        val pageHeight = nb.pageHeightUnits(pageId)
        val pdf = ref?.let { runCatching { pdfPages.renderBlocking(nb, it, THUMB_WIDTH) }.getOrNull() }
        val pageWidth = nb.pageWidthUnits(pageId)
        val k = THUMB_WIDTH / pageWidth
        val bitmap = Bitmap.createBitmap(THUMB_WIDTH, (pageHeight * k).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = Matrix().apply { setScale(k, k) }
        canvas.concat(scale)
        thumbRenderer.apply {
            this.dark = darkNow
            paper = bg
            this.photo = photo
            this.pdf = pdf
            this.pageHeight = pageHeight
            this.pageWidth = pageWidth
            drawBackground(canvas, edgeWidth = 0f)
            val shown = dev.inknotes.data.Layers.visible(c, nb.hiddenLayers())
            drawContent(canvas, shown.strokes, shown.fills, scale, shown.images, loadImage = { file -> imageCache.get(file) ?: thumbImages.getOrPut(file) { repo.loadImage(nb, file, THUMB_IMAGE_SIDE) } })
        }
        return bitmap
    }

    /** Small decodes of pictures for thumbnails; thumbnail thread only. */
    private val thumbImages = object : LinkedHashMap<String, Bitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>) = size > THUMB_IMAGE_CACHE
    }

    /** Used only on the thumbnail thread. */
    private val thumbRenderer by lazy { PageRenderer() }

    private fun invalidateThumbnails() {
        thumbRevision.clear()
        thumbnailTick++
    }

    // ------------------------------------------------------------- flash cards

    /** A band of the hint marker as a card: [band] hides the answer, [area] is the part of the page shown. */
    class StudyCard(val pageIndex: Int, val pageId: String, val key: String, val band: android.graphics.RectF, val area: android.graphics.RectF)

    /** Cards of the notebook while the review is open (null: closed, empty list: none at all). */
    var studyCards by mutableStateOf<List<StudyCard>?>(null)
        private set
    var cardStates by mutableStateOf<Map<String, dev.inknotes.data.Cards.State>>(emptyMap())
        private set
    private val cardPages = HashMap<String, PageContent>()
    private val cardExec = Executors.newSingleThreadExecutor { r -> Thread(r, "cards") }
    private val cardRenderer by lazy { PageRenderer() }

    /** Collects the cards of all pages (in the background) and opens the review. */
    fun openCards() {
        flush()
        val nb = notebook
        // Pages open in the editor are taken as they are now; the rest from their files.
        val open = nb.pageIds.associateWith { docs[it]?.content() }
        status = "Собираю карточки…"
        runCatching {
            cardExec.execute {
                val pages = HashMap<String, PageContent>()
                val cards = ArrayList<StudyCard>()
                for ((index, pageId) in nb.pageIds.withIndex()) {
                    val content = open[pageId] ?: runCatching { repo.loadPage(nb, pageId) }.getOrNull() ?: continue
                    val w = nb.pageWidthUnits(pageId)
                    val h = nb.pageHeightUnits(pageId)
                    val bands = content.strokes.filter { it.kind == InkKind.SPOILER }
                    if (bands.isEmpty()) continue
                    pages[pageId] = content
                    for (st in bands.sortedWith(compareBy({ PageEdits.bounds(it)?.top ?: 0f }, { PageEdits.bounds(it)?.left ?: 0f }))) {
                        val b = PageEdits.bounds(st) ?: continue
                        val line = maxOf(b.height(), CARD_MIN_LINE)
                        val area = android.graphics.RectF(0f, (b.top - CARD_CONTEXT * line).coerceAtLeast(0f), w, (b.bottom + CARD_CONTEXT * line).coerceAtMost(h))
                        cards += StudyCard(index, pageId, dev.inknotes.data.Cards.key(pageId, b.left, b.top, b.right, b.bottom), b, area)
                    }
                }
                val states = repo.loadCards(nb.id)
                handler.post {
                    status = null
                    cardPages.clear()
                    cardPages.putAll(pages)
                    cardStates = states
                    studyCards = cards
                }
            }
        }
    }

    fun closeCards() {
        studyCards = null
        cardPages.clear()
    }

    /** The card's part of the page, the band hidden or [revealed], [widthPx] wide; [onDone] on the main thread. */
    fun renderCard(card: StudyCard, revealed: Boolean, widthPx: Int, onDone: (ImageBitmap) -> Unit) {
        val content = cardPages[card.pageId] ?: return
        val nb = notebook
        val bg = nb.background(dark)
        val photo = if (bg.kind == PaperKind.PHOTO) photo(bg.photo) else null
        val ref = nb.pdfPages[card.pageId]
        val darkNow = dark
        runCatching {
            cardExec.execute {
                val bitmap = runCatching {
                    val pageWidth = nb.pageWidthUnits(card.pageId)
                    val pageHeight = nb.pageHeightUnits(card.pageId)
                    val k = widthPx / card.area.width()
                    val bmp = Bitmap.createBitmap(widthPx, maxOf(1, (card.area.height() * k).toInt()), Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    val m = Matrix().apply {
                        setTranslate(-card.area.left, -card.area.top)
                        postScale(k, k)
                    }
                    canvas.concat(m)
                    cardRenderer.apply {
                        this.dark = darkNow
                        paper = bg
                        this.photo = photo
                        pdf = ref?.let { runCatching { pdfPages.renderBlocking(nb, it, (pageWidth * k).toInt().coerceIn(1, 4096)) }.getOrNull() }
                        this.pageHeight = pageHeight
                        this.pageWidth = pageWidth
                        drawBackground(canvas, edgeWidth = 0f)
                        drawContent(canvas, content.strokes, content.fills, m, content.images, { file -> imageCache.get(file) ?: repo.loadImage(nb, file, THUMB_IMAGE_SIDE * 2) }) { st ->
                            revealed && PageEdits.bounds(st)?.let { b -> dev.inknotes.data.Cards.key(card.pageId, b.left, b.top, b.right, b.bottom) == card.key } == true
                        }
                    }
                    bmp
                }.getOrNull() ?: return@execute
                handler.post { onDone(bitmap.asImageBitmap()) }
            }
        }
    }

    fun gradeCard(card: StudyCard, grade: dev.inknotes.data.Cards.Grade) {
        val today = dev.inknotes.data.Cards.today()
        cardStates = cardStates + (card.key to dev.inknotes.data.Cards.grade(cardStates[card.key], grade, today))
        // Cards of bands no longer on the pages are dropped from the file.
        val keys = studyCards.orEmpty().map { it.key }.toSet()
        repo.saveCardsAsync(notebook.id, cardStates.filterKeys { it in keys })
    }

    /** Leaves the review at the card's page. */
    fun showCard(card: StudyCard) {
        closeCards()
        goToPage(card.pageIndex)
    }

    // ------------------------------------------------------------------- keys

    /** Returns true if the key was consumed (pen button or button learning). */
    fun handleKey(e: KeyEvent): Boolean {
        if (learningPenButton) {
            // Back cancels learning; it is not a pen button.
            if (e.keyCode == KeyEvent.KEYCODE_BACK) {
                if (e.action == KeyEvent.ACTION_UP) learningPenButton = false
                return true
            }
            if (e.action != KeyEvent.ACTION_DOWN) return true
            if (penButton.learn(e)) {
                learningPenButton = false
                penButtonLabel = penButton.describe()
                return true
            }
            // A typing key or a keyboard's key: refused, it keeps its meaning.
            showStatus("Эту клавишу назначить нельзя: она нужна для набора текста. Нажмите кнопку пера.")
            return false
        }
        if (!penButton.matches(e)) return false
        // M-Pencil reports one double-tap as two presses ~70 ms apart; count it once.
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 &&
            e.eventTime - lastPenButtonPress > PEN_BUTTON_DEBOUNCE_MS
        ) {
            lastPenButtonPress = e.eventTime
            selectTool(if (tool == Tool.ERASER) lastInkTool else Tool.ERASER)
        }
        return true
    }

    // ------------------------------------------------------------------- save

    /** Writes every page whose revision changed since the last save. */
    fun flush() {
        handler.removeCallbacks(saveRunnable)
        var any = false
        for ((id, doc) in docs) {
            if (savedRevision[id] == doc.revision) continue
            val content = doc.content()
            repo.savePageAsync(notebook, id, content)
            handwriting.pageSaved(notebook, id, content)
            savedRevision[id] = doc.revision
            any = true
        }
        if (any) notebook = pruneMarks() ?: repo.touch(notebook)
    }

    /**
     * Sound marks whose writing is gone (erased, moved away) are dropped: the notebook saved
     * without them, or null when every mark still has its writing.
     */
    private fun pruneMarks(): Notebook? {
        if (notebook.audioMarks.isEmpty()) return null
        val kept = notebook.audioMarks.filter { m -> docs[m.pageId]?.let { hasInk(it, m) } ?: true }
        if (kept.size == notebook.audioMarks.size) return null
        return repo.setAudioMarks(notebook, kept)
    }

    /** Some of the writing a mark was made for is still there: a stroke with its middle inside the mark. */
    private fun hasInk(doc: PageDocument, m: dev.inknotes.data.AudioMark): Boolean {
        val pad = MARK_SLOP
        return doc.strokes.any { s ->
            s.kind.isInk && dev.inknotes.editor.PageCache.strokeBounds(s)?.let { b ->
                b.centerX() in m.left - pad..m.right + pad && b.centerY() in m.top - pad..m.bottom + pad
            } == true
        }
    }

    override fun onDocumentChanged() {
        updateUndo()
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_DELAY_MS)
        handler.removeCallbacks(thumbnailRunnable)
        handler.postDelayed(thumbnailRunnable, THUMBNAIL_DELAY_MS)
        handler.removeCallbacks(mathRunnable)
        handler.postDelayed(mathRunnable, MATH_DELAY_MS)
    }

    // The finger gesture itself is still on: it ends right after.
    override fun onUndoGesture() = undoStep()

    override fun onRedoGesture() = redoStep()

    override fun onSelectionChanged(count: Int) {
        selectionCount = count
        selectedFills = view?.selectedFillCount() ?: 0
        selectionIsTable = count > 0 && view?.selectionIsTable() == true
        selectionLineKinds = if (count > 0) view?.selectedLineKinds().orEmpty() else emptySet()
        selectionLineWidth = if (count > 0) view?.selectionLineWidth() else null
        selectedText = if (count == 1) view?.selectedTextBox()?.text?.text else null
        selectedPicture = if (count == 1) view?.selectedPicture() else null
    }

    /** Size of a letter of a formula picture put on the page, and the least height of one (page units). */
    private val HOLD_KEY_MS = 400L
    private val FORMULA_PX = 72f
    private val MIN_FORMULA_HEIGHT = 30f
    private val FORMULA_GAP = 12f

    /** The one selected picture: it can be cropped, straightened… */
    var selectedPicture by mutableStateOf<PageImage?>(null)
        private set

    /** A picture open in the photo editor: what to edit, and the picture on the page it replaces (null: a new one). */
    class PhotoEdit(val bitmap: Bitmap, val target: PageImage?, val page: Int, val mode: PhotoMode)

    var photoEdit by mutableStateOf<PhotoEdit?>(null)
        private set

    /** Opens the selected picture in the photo editor. */
    fun editSelectedPicture() {
        val img = view?.selectedPicture() ?: return
        val nb = notebook
        val page = view?.selectionPageIndex ?: pageIndex
        status = "Открываю картинку…"
        Thread({
            val bmp = repo.loadImage(nb, img.file)?.let { it.copy(Bitmap.Config.ARGB_8888, true).also { c -> if (c !== it) it.recycle() } }
            handler.post {
                status = null
                if (bmp == null) showStatus("Не удалось открыть картинку") else photoEdit = PhotoEdit(bmp, img, page, PhotoMode.CROP)
            }
        }, "photo-open").start()
    }

    /** A photo from the camera for the document scanner: straightened first, then put on the page. */
    fun scanned(uri: Uri, resolver: ContentResolver) {
        status = "Открываю снимок…"
        Thread({
            val bmp = repo.decodePicture(uri, resolver)
            handler.post {
                status = null
                if (bmp == null) showStatus("Снимок не получился") else photoEdit = PhotoEdit(bmp, null, pageIndex, PhotoMode.CORNERS)
            }
        }, "scan-open").start()
    }

    fun cancelPhotoEdit() {
        photoEdit = null
    }

    /** The edited picture saved into the notebook: in place of the old one (as wide), or new on the page. */
    fun finishPhotoEdit(result: Bitmap) {
        val edit = photoEdit ?: return
        photoEdit = null
        val nb = notebook
        status = "Сохраняю картинку…"
        repo.saveImageAsync(nb, result) { saved ->
            status = null
            val (file, pxW, pxH) = saved ?: return@saveImageAsync showStatus("Не удалось сохранить картинку")
            val v = view ?: return@saveImageAsync
            val old = edit.target
            if (old != null && v.selectedPicture() === old) {
                val w = old.w
                val h = w * pxH / pxW
                val cy = old.y + old.h / 2
                v.replaceSelection(PageImage(file, old.x, cy - h / 2, w, h, old.rotation, layer = old.layer))
            } else {
                placeNewImage(file, pxW, pxH, edit.page)
            }
        }
    }

    private fun placeNewImage(file: String, pxW: Int, pxH: Int, page: Int) {
        val v = view ?: return
        var w = min(IMAGE_WIDTH, pxW * 2f)
        var h = w * pxH / pxW
        if (h > IMAGE_MAX_HEIGHT) {
            w *= IMAGE_MAX_HEIGHT / h
            h = IMAGE_MAX_HEIGHT
        }
        val c = v.visibleCenter(page)
        selectTool(Tool.LASSO)
        v.insert(page, images = listOf(PageImage(file, c[0] - w / 2, c[1] - h / 2, w, h)))
    }

    /** The selected lines drawn as [kind]: neon as an ordinary pen line, a pen line as pencil… */
    fun restyleSelection(kind: InkKind) {
        view?.restyleSelection(kind)
    }

    override fun onPendingAreaChanged(pending: Boolean) {
        hasPendingArea = pending
    }

    override fun onVisiblePageChanged(index: Int) {
        pageIndex = index
        // Keep rendered PDF pages only around what is on screen.
        val keep = (index - 2..index + 2).mapNotNull { notebook.pageIds.getOrNull(it) }.toSet()
        pdfBitmaps.keys.retainAll(keep)
        unloadFarPages()
        handler.removeCallbacks(mathRunnable)
        handler.postDelayed(mathRunnable, MATH_DELAY_MS / 2)
    }

    // ------------------------------------------------------------------ math

    /** Recomputes the answers of the pages around the current one whose ink changed. */
    private fun requestMath() {
        if (math.state != MathNotes.State.Ready) return
        val ids = notebook.pageIds
        for (i in pageIndex - 1..pageIndex + 1) {
            val id = ids.getOrNull(i) ?: continue
            val doc = docs[id] ?: continue
            if (mathRevision[id] == doc.revision) continue
            mathRevision[id] = doc.revision
            math.analyze("${notebook.id}/$id", doc.strokes, notebook.pageWidthUnits(id)) { answers ->
                if (answers.isEmpty()) mathAnswers.remove(id) else mathAnswers[id] = answers
                view?.refresh()
            }
        }
    }

    /** Switches answers after "=" on (downloading the formula model the first time) or off. */
    fun toggleMath() {
        if (math.state == MathNotes.State.Ready || math.state == MathNotes.State.Downloading) {
            math.disable()
            mathAnswers.clear()
            mathRevision.clear()
            view?.refresh()
        } else {
            math.enable()
        }
    }

    // ---------------------------------------------------------------- formulas

    /** A handwritten formula read for the formula window: its LaTeX, or null text when it could not be read. */
    class FormulaRead(val latex: String, val failed: Boolean)

    var formula by mutableStateOf<FormulaRead?>(null)
        private set

    /** Reads the selected handwriting as a formula and opens the formula window. */
    fun formulaFromSelection() {
        val v = view ?: return
        val ink = v.selectedInk()
        if (ink.isEmpty()) return showStatus("Выделите формулу лассо, затем нажмите «Формула»")
        when (math.state) {
            MathNotes.State.Ready -> Unit
            MathNotes.State.Downloading -> return showStatus("Скачивается распознавание формул, подождите немного")
            else -> {
                math.enable()
                return showStatus("Скачиваю распознавание формул (один раз) — нажмите «Формула» ещё раз через минуту")
            }
        }
        status = "Читаю формулу…"
        math.readFormula(ink) { source ->
            status = null
            formula = if (source.isNullOrBlank()) FormulaRead("", failed = true) else FormulaRead(dev.inknotes.math.Typeset.latex(source), failed = false)
        }
    }

    fun closeFormula() {
        formula = null
    }

    fun copyFormulaLatex(context: Context, latex: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("LaTeX", latex))
        showStatus("LaTeX скопирован")
    }

    fun copyFormulaPicture(context: Context, latex: String) {
        val bmp = FormulaArt.bitmap(latex, FORMULA_PX, android.graphics.Color.BLACK) ?: return
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "formula.png")
        runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        bmp.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, "Формула", uri))
        showStatus("Картинка формулы скопирована")
    }

    /** The formula set in print on the page: in place of the selected handwriting, or under it. */
    fun placeFormula(latex: String, replace: Boolean) {
        val v = view ?: return
        val rect = v.selectionRect() ?: return
        val page = v.selectionPageIndex
        val bmp = FormulaArt.bitmap(latex, FORMULA_PX, penColor) ?: return
        formula = null
        // As tall as the handwriting was, but letters no smaller than a line of writing.
        val h = max(rect.height(), MIN_FORMULA_HEIGHT) * bmp.height / (bmp.height - FORMULA_PX * 0.5f).coerceAtLeast(1f)
        val w = h * bmp.width / bmp.height
        repo.saveImageAsync(notebook, bmp) { saved ->
            val (file, _, _) = saved ?: return@saveImageAsync showStatus("Не удалось вставить формулу")
            val vv = view ?: return@saveImageAsync
            if (replace) {
                vv.replaceSelection(PageImage(file, rect.left, rect.centerY() - h / 2, w, h))
            } else {
                vv.insert(page, images = listOf(PageImage(file, rect.left, rect.bottom + FORMULA_GAP, w, h)))
            }
        }
    }

    /** Answers as fractions (1/3) or decimals (0,333333); recomputed at once. */
    var fractionAnswers by mutableStateOf(prefs.fractionAnswers.also(::applyNumbers))
        private set

    fun toggleFractionAnswers() {
        fractionAnswers = !fractionAnswers
        prefs.fractionAnswers = fractionAnswers
        applyNumbers(fractionAnswers)
        mathRevision.clear()
        requestMath()
    }

    /** Units after numbers and physical constants in formulas; recomputed at once. */
    var mathUnits by mutableStateOf(prefs.mathUnits.also { dev.inknotes.math.Parser.units = it })
        private set

    fun toggleMathUnits() {
        mathUnits = !mathUnits
        prefs.mathUnits = mathUnits
        dev.inknotes.math.Parser.units = mathUnits
        mathRevision.clear()
        requestMath()
    }

    private fun applyNumbers(fractions: Boolean) {
        Printer.numbers = if (fractions) Printer.Numbers.FRACTION else Printer.Numbers.DECIMAL
    }

    override fun answers(index: Int): List<PageAnswer> =
        if (math.state == MathNotes.State.Ready) mathAnswers[notebook.pageIds.getOrNull(index)].orEmpty() else emptyList()

    private fun syncView() {
        val v = view ?: return
        v.tool = tool
        v.penKind = penKind
        v.dashStyle = dashStyle
        v.pageShift = pageShift
        v.penColor = penColor
        v.penSize = penSize
        v.fountainColor = fountainColor
        v.fountainSize = fountainSize
        v.neonColor = neonColor
        v.neonSize = neonSize
        if (v.pickingColor != pickingColor) v.pickingColor = pickingColor
        v.highlighterColor = highlighterColor
        v.highlighterSize = highlighterSize
        v.spoilerColor = spoilerColor
        v.spoilerSize = spoilerSize
        v.scribbleErase = scribbleErase
        v.penPressure = penPressure
        v.compassOn = compassOn
        v.pdfDark = pdfDark
        if (v.pdfTextOn != pdfTextOn) v.pdfTextOn = pdfTextOn
        v.straightHighlighter = straightHighlighter
        v.eraserRadiusDp = eraserSize
        v.eraserMode = eraserMode
        v.eraserTarget = eraserTarget
        if (v.insertingSpace != insertingSpace) v.insertingSpace = insertingSpace
        v.hiddenLayers = notebook.hiddenLayers()
        v.frozenLayers = notebook.frozenLayers()
        v.fillPattern = fillPattern
        v.fillStyle = fillStyle
        v.fillColor = fillColor
        v.refresh()
    }

    companion object {
        private const val SAVE_DELAY_MS = 800L

        /** Pages either side of the current one kept in memory. */
        private const val KEEP_PAGES = 4
        private const val THUMBNAIL_DELAY_MS = 1200L
        private const val MATH_DELAY_MS = 700L
        private const val PEN_BUTTON_DEBOUNCE_MS = 400L
        /** Custom colours kept in the palette (it scrolls). */
        private const val MAX_CUSTOM = 40
        private const val COLOR_TOLERANCE = 10
        private const val MAX_HISTORY = 500
        private const val THUMB_WIDTH = 240

        /** A card shows this many line heights above and below its band; a line is at least [CARD_MIN_LINE] units. */
        private const val CARD_CONTEXT = 2.5f

        /** Strokes this close in time (ms) and place (units) make one sound mark; a tap finds one this far off. */
        private const val MARK_JOIN_MS = 4000L
        private const val MARK_JOIN_GAP = 60f
        private const val MARK_SLOP = 6f

        /** Playing from a mark starts this much before the writing. */
        private const val LISTEN_LEAD_MS = 2000L

        /** Height of typed letters, page units (about 4 mm on A4). */
        private const val TEXT_SIZE = 20f
        private const val CARD_MIN_LINE = 24f
        private const val IMAGE_CACHE_BYTES = 64 * 1024 * 1024
        private const val THUMB_IMAGE_SIDE = 256
        private const val THUMB_IMAGE_CACHE = 64
        private const val IMAGE_WIDTH = 400f
        private const val IMAGE_MAX_HEIGHT = 600f

        /** PDF pages are rendered a bit above screen width so light zoom stays sharp. */
        private const val PDF_RENDER_SCALE = 1.25f
        private const val MAX_PDF_TEXT_PAGES = 200
        private const val MAX_PDF_WIDTH_PX = 2560
        val PEN_COLORS = listOf(0xFF1A1A1A, 0xFF1E5BD8, 0xFFD32F2F, 0xFF2E7D32).map { it.toInt() }
        val HIGHLIGHTER_COLORS = listOf(0x80FFEB3B, 0x8076FF03, 0x80FF80AB, 0x8040C4FF).map { it.toInt() }
        val FILL_COLORS = listOf(0xFF1A1A1A, 0xFF1E5BD8, 0xFFD32F2F, 0xFF2E7D32, 0xFFF9A825, 0xFF7B1FA2)
            .map { it.toInt() }
        /** Slider range of a tool's size and its default. */
        class SizeRange(val min: Float, val max: Float, val default: Float)

        /** Page units (1000 = page width). */
        val PEN_RANGE = SizeRange(0.8f, 12f, 2.8f)

        /** Broad side of the fountain nib; the fine side is a quarter of it. */
        val FOUNTAIN_RANGE = SizeRange(2f, 24f, 6.5f)
        val HIGHLIGHTER_RANGE = SizeRange(6f, 60f, 22f)
        /** Width of the neon tube; its light core and halo follow ([dev.inknotes.editor.Neon]). */
        val NEON_RANGE = SizeRange(3f, 20f, 6.5f)

        /** Bright tube colours: pink, violet, cyan, green, orange. */
        val NEON_COLORS = listOf(0xFFFF2BD6, 0xFFB14DFF, 0xFF00E5FF, 0xFF39FF14, 0xFFFF9100).map { it.toInt() }

        /** Hint-marker bands: slate, ink blue, graphite, plum. */
        val SPOILER_COLORS = listOf(0xFF7E8A9A, 0xFF3D5A80, 0xFF4A4A4A, 0xFF7B4B8C).map { it.toInt() }
        val SPOILER_RANGE = SizeRange(10f, 70f, 26f)

        /** Eraser radius in dp on screen. */
        val ERASER_RANGE = SizeRange(4f, 60f, 10f)
    }
}
