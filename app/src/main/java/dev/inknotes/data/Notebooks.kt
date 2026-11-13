package dev.inknotes.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max

enum class PaperKind(val title: String) {
    BLANK("Чистая"),
    LINED("Линейка"),
    FINE_LINED("Мелкая линейка"),
    GRID("Клетка"),
    DASH_GRID("Клетка пунктиром"),
    DOTS("Точки"),
    MILLIMETER("Миллиметровка"),
    MUSIC("Нотный стан"),
    CORNELL("Конспект Корнелла"),
    PLANNER("Неделя"),
    PHOTO("Фото"),
}

/**
 * Page background; [photo] is a file name inside the notebook folder when [kind] is PHOTO.
 * [color] is the paper colour chosen for it (opaque ARGB), null for the theme's own.
 * [lines] is the opacity of the ruling (lines, grid, dots), 0..1.
 */
data class PageBackground(val kind: PaperKind, val photo: String? = null, val color: Int? = null, val lines: Float = 1f)

/** Paper sizes in millimetres, portrait; stored by name: renaming breaks saved notebooks. */
enum class PaperFormat(val title: String, val widthMm: Float, val heightMm: Float) {
    A3("A3", 297f, 420f),
    A4("A4", 210f, 297f),
    A5("A5", 148f, 210f),
    A6("A6", 105f, 148f),
    B5("B5", 176f, 250f),
    LETTER("Letter", 215.9f, 279.4f),
    LEGAL("Legal", 215.9f, 355.6f),
    SQUARE("Квадрат", 210f, 210f),
}

/**
 * Size of a paper page: a format, upright or turned to landscape; or a canvas, [canvasW] by
 * [canvasH] mm, that grows to the right and down as it is written on (see [CanvasGrowth]).
 * A canvas is stored with format A4 too, so versions before 0.38 open it as an A4 page
 * (what lies past it is kept, not shown).
 */
data class PageSize(
    val format: PaperFormat = PaperFormat.A4,
    val landscape: Boolean = false,
    val canvasW: Float = 0f,
    val canvasH: Float = 0f,
) {
    val isCanvas get() = canvasW > 0f && canvasH > 0f
    val widthMm get() = if (isCanvas) canvasW else if (landscape) format.heightMm else format.widthMm
    val heightMm get() = if (isCanvas) canvasH else if (landscape) format.widthMm else format.heightMm

    companion object {
        val DEFAULT = PageSize()

        /** A new canvas: a landscape A3 to start with. */
        val CANVAS = PageSize(canvasW = CanvasGrowth.START_W, canvasH = CanvasGrowth.START_H)
    }
}

/**
 * A notebook page backed by page [index] of the copied PDF [file]; size in PDF points. The PDF
 * page can be turned by [rotation] degrees clockwise and have [margins] round it for notes.
 */
data class PdfPageRef(
    val file: String,
    val index: Int,
    val width: Float,
    val height: Float,
    val rotation: Int = 0,
    val margins: PdfGeometry.Margins = PdfGeometry.Margins.NONE,
) {
    /** The whole notebook page in points: the PDF page as turned and its margins. */
    val outerWidth get() = PdfGeometry.outer(width, height, rotation, margins).first
    val outerHeight get() = PdfGeometry.outer(width, height, rotation, margins).second

    /** From the PDF page's points (top left origin) to page units of the notebook page. */
    fun pdfToUnits() = PdfGeometry.pdfToUnits(width, height, rotation, margins, Notebook.PAGE_UNITS)
}

/**
 * Each notebook keeps its own background for the light and for the dark theme.
 * Pages listed in [pdfPages] show a PDF page instead and keep its aspect ratio.
 */
/** Number of cover patterns of the mystic style. */
const val COVER_ARTS = 6

/** Number of plastic covers of the notebook style. */
const val PLASTIC_KINDS = 3

/** An entry of the table of contents: [title] at [y] page units from the top of page [pageId]. */
data class Bookmark(val pageId: String, val y: Float, val title: String)

/**
 * A link: a finger tap on the rectangle [left]…[bottom] of page [pageId] goes to page [target]
 * ([targetY] units from its top). Shared with the notebook.
 */
data class PageLink(
    val pageId: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val target: String,
    val targetY: Float = 0f,
) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** A sound recording of a notebook: its file (`rec-<uuid>.m4a`), when it began and how long it is. */
data class Recording(val file: String, val startedAt: Long, val durationMs: Long)

/** Writing made during [file]'s recording: the box it covers on [pageId] and the moment, [atMs] into it. */
data class AudioMark(val pageId: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val file: String, val atMs: Long) {
    fun contains(x: Float, y: Float, slop: Float) = x in left - slop..right + slop && y in top - slop..bottom + slop
}

/** A layer of a notebook (see [Layers]): its name, and whether it is shown and can be changed. */
data class LayerInfo(val name: String, val visible: Boolean = true, val locked: Boolean = false)

data class Notebook(
    val id: String,
    val title: String,
    val light: PageBackground,
    val dark: PageBackground,
    val createdAt: Long,
    val updatedAt: Long,
    val pageIds: List<String>,
    val pdfPages: Map<String, PdfPageRef> = emptyMap(),
    /** Cover colour (opaque ARGB); null = picked from the id, so old notebooks still differ. */
    val cover: Int? = null,
    /** Library folder on this device; not part of shared files. */
    val folder: String? = null,
    /** Paper size of pages that differ from A4 upright (PDF pages keep the PDF's size). */
    val pageSizes: Map<String, PageSize> = emptyMap(),
    /** Starred: listed first in the library. */
    val favorite: Boolean = false,
    /** Pattern of the cover in the mystic style (0 until [COVER_ARTS]); null = picked from the id. */
    val coverArt: Int? = null,
    /** Kind of plastic cover in the notebook style (0 until [PLASTIC_KINDS]); null = picked from the id. */
    val plasticKind: Int? = null,
    /** Table of contents, in the order of the pages. */
    val toc: List<Bookmark> = emptyList(),
    val links: List<PageLink> = emptyList(),
    /** Layers by number; empty: only the main one, shown and unlocked. */
    val layers: List<LayerInfo> = emptyList(),
    /** Layer new writing goes onto. */
    val activeLayer: Int = 0,
    val recordings: List<Recording> = emptyList(),
    val audioMarks: List<AudioMark> = emptyList(),
) {
    fun layerList(): List<LayerInfo> = layers.ifEmpty { listOf(LayerInfo(MAIN_LAYER)) }

    fun hiddenLayers(): Set<Int> = layers.withIndex().filter { !it.value.visible }.map { it.index }.toSet()

    /** Layers whose elements cannot be erased, selected or moved (locked or hidden). */
    fun frozenLayers(): Set<Int> = layers.withIndex().filter { !it.value.visible || it.value.locked }.map { it.index }.toSet()

    fun plasticKindOrDefault(): Int = plasticKind ?: ((id.hashCode() ushr 3 and 0x7fffffff) % PLASTIC_KINDS)

    fun coverArtOrDefault(): Int = coverArt ?: ((id.hashCode() and 0x7fffffff) % COVER_ARTS)

    fun background(darkTheme: Boolean) = if (darkTheme) dark else light

    fun pageSize(pageId: String): PageSize = pageSizes[pageId] ?: PageSize.DEFAULT

    /** Page width on paper. */
    fun pageWidthMm(pageId: String): Float =
        pdfPages[pageId]?.let { it.outerWidth * MM_PER_POINT } ?: pageSize(pageId).widthMm

    /**
     * Page width in page units. On paper pages a unit is always [MM_PER_UNIT] mm, so writing
     * keeps its size in millimetres whatever the format (A4 is [PAGE_UNITS] wide). A PDF page
     * is [PAGE_UNITS] wide whatever its size, as it always was.
     */
    fun pageWidthUnits(pageId: String): Float =
        if (pageId in pdfPages) PAGE_UNITS else pageSize(pageId).widthMm / MM_PER_UNIT

    fun pageHeightUnits(pageId: String): Float {
        pdfPages[pageId]?.let { return PAGE_UNITS * it.outerHeight / it.outerWidth }
        return pageSize(pageId).heightMm / MM_PER_UNIT
    }

    fun coverColor(): Int = cover ?: DEFAULT_COVERS[(id.hashCode() and 0x7fffffff) % DEFAULT_COVERS.size]

    companion object {
        const val MAIN_LAYER = "Основной"
        const val PAGE_UNITS = 1000f
        const val MM_PER_POINT = 25.4f / 72f

        /** Millimetres in a page unit on paper pages: an A4 page is 1000 units wide. */
        const val MM_PER_UNIT = 0.21f

        /** Muted colours given to notebooks without a chosen cover (as before covers existed). */
        private val DEFAULT_COVERS = listOf(0xFF3F6E8C, 0xFF8C5A3F, 0xFF4F7F52, 0xFF6B4F8C, 0xFF8C3F55).map { it.toInt() }

        /** Cover choices offered in the library. */
        val COVERS = DEFAULT_COVERS + listOf(
            0xFF3D63C9, 0xFF1E88E5, 0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFF9A825,
            0xFFFB8C00, 0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF6D4C41, 0xFF546E7A, 0xFF263238,
        ).map { it.toInt() }
    }
}

/** A library folder; [parent] null = top level of the library. */
data class Folder(val id: String, val name: String, val parent: String? = null)

/** Folder tree helpers over a flat folder list. */
object FolderTree {
    /** [id] and every folder below it. */
    fun subtree(folders: List<Folder>, id: String): Set<String> {
        val out = linkedSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (f in folders) if (f.parent in out && out.add(f.id)) grew = true
        }
        return out
    }

    /** Folders from the top level down to [id] (empty for null or an unknown id). */
    fun path(folders: List<Folder>, id: String?): List<Folder> {
        val byId = folders.associateBy { it.id }
        val out = ArrayList<Folder>()
        var cur = id?.let(byId::get)
        while (cur != null && out.size <= folders.size) {
            out.add(0, cur)
            cur = cur.parent?.let(byId::get)
        }
        return out
    }

    /** Depth-first order with depth, children sorted by name: for pickers. */
    fun flatten(folders: List<Folder>): List<Pair<Folder, Int>> {
        val children = folders.groupBy { it.parent }
        val out = ArrayList<Pair<Folder, Int>>()
        fun walk(parent: String?, depth: Int) {
            for (f in children[parent].orEmpty().sortedBy { it.name.lowercase() }) {
                out += f to depth
                walk(f.id, depth + 1)
            }
        }
        walk(null, 0)
        return out
    }
}

/**
 * File layout: `notebooks/<id>/notebook.json`, `notebooks/<id>/pages/<pageId>.ink`,
 * background photos as `notebooks/<id>/bg-<uuid>.jpg`, imported PDFs as `notebooks/<id>/doc-<uuid>.pdf`.
 * Every write goes to a temp file first and is renamed over the target, so a crash
 * mid-write never leaves a half-written notebook or page.
 */
class NotebookRepository(filesDir: File) {
    private val root = File(filesDir, "notebooks").apply { mkdirs() }
    @Volatile
    private var ioThread: Thread? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "notebook-io").also { ioThread = it } }
    private val ioDispatcher = io.asCoroutineDispatcher()
    private val main = Handler(Looper.getMainLooper())
    private val backups = Executors.newSingleThreadExecutor { r -> Thread(r, "notebook-backup").apply { priority = Thread.MIN_PRIORITY } }

    /**
     * Slow transfers (reading picked files and PDFs from other apps or the cloud, writing
     * exports to them) run here, not on [io]: the library and the editor wait on [io], so a
     * download there would freeze the app. What they need from the library they take from
     * [io] in short steps.
     */
    private val transfer = Executors.newSingleThreadExecutor { r -> Thread(r, "notebook-transfer") }

    /** Pages that could not be read for a reason other than damage (too little memory, newer app). */
    private val unreadable = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Pages set aside as damaged in this run, by path: the name of the kept copy. */
    private val damaged = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Notebooks with a page saved in this run: an undo there may bring a picture back. */
    private val savedThisRun = java.util.Collections.synchronizedSet(HashSet<String>())

    /** When this run began: lock screen notes older than this were left by an earlier one. */
    private val startedAt = System.currentTimeMillis()

    init {
        // Leftovers of imports and exports cut short by the app being closed. On the thread
        // that makes them, first, so it never meets one of this run's.
        transferring {
            root.listFiles().orEmpty()
                .filter { it.name.startsWith(".import-") || it.name.startsWith(".export-") }
                .forEach { deleteQuietly(it) }
        }
        // A notebook being replaced when the app was stopped goes back (or its old copy goes).
        background {
            SafeFiles.recoverAside(root)
            runCatching { recoverRecordings() }.onFailure { Log.w(TAG, "recording recovery failed", it) }
            // One notebook at a time, each queued after what was asked meanwhile: opening the
            // library or a notebook does not wait for every page of every notebook to be read.
            val dirs = root.listFiles().orEmpty().filter { !it.name.startsWith(".") && it.isDirectory }
            sweepNext(dirs, 0)
        }
    }

    // Reads go through the same executor as writes, so they always see queued saves.
    fun list(): List<Notebook> = onIo { listIn(root.listFiles().orEmpty()) }

    /**
     * Like [list], but a library folder that can't be read at all (storage trouble) is an error,
     * not an empty library: the sync must never take every notebook for deleted here.
     */
    fun listForSync(): List<Notebook> = onIo {
        listIn(root.listFiles() ?: throw IOException("library folder unreadable"))
    }

    private fun listIn(dirs: Array<File>): List<Notebook> {
        val missing = HashSet<String>()
        return dirs.asList()
            .filter { !it.name.startsWith(".") } // skip import staging folders
            .filter { !File(it, LOCK_NOTE).exists() } // a note being written over the lock screen
            .mapNotNull { dir ->
                runCatching { readMeta(dir) }.getOrElse { e ->
                    recoverMeta(dir, e).also { if (it == null && UUID_RE.matches(dir.name) && File(dir, META).exists()) missing += dir.name }
                }
            }
            .sortedByDescending { it.updatedAt }
            .also { unlisted = missing }
    }

    /**
     * Notebooks whose folder is here but that the last [list] could not read nor recover:
     * not deleted, only unknown for now. The sync must not take them for deleted here.
     */
    @Volatile
    var unlisted: Set<String> = emptySet()
        private set

    /** Notebooks rebuilt from their pages ([recoverMeta]) that are still marked so. */
    fun recoveredIds(): Set<String> = onIo {
        root.listFiles().orEmpty().filter { File(it, RECOVERED).exists() && UUID_RE.matches(it.name) }.map { it.name }.toSet()
    }

    /**
     * Recovered notebook [id] becomes a notebook of its own under a new id, so a whole copy
     * of [id] (from the Drive) can take its place instead of being written over by it.
     * Returns the new id, or null if it could not be moved.
     */
    fun detachRecovered(id: String): String? = onIo {
        val from = dir(id)
        val nb = runCatching { readMeta(from) }.getOrNull() ?: return@onIo null
        val copy = nb.copy(id = newId(), updatedAt = System.currentTimeMillis())
        if (!from.renameTo(dir(copy.id))) return@onIo null
        writeMetaNow(copy)
        // Done once: from now on it is a notebook like any other (its files are still kept).
        settle(dir(copy.id))
        copy.id
    }

    /**
     * Recovered notebook [id] has nothing on the Drive to be confused with: from now on it syncs
     * like any other. Its files stay protected from the orphan sweep.
     */
    fun settleRecovered(id: String) = onIo { settle(dir(id)) }

    private fun settle(d: File) {
        val mark = File(d, RECOVERED)
        if (mark.exists() && !mark.renameTo(File(d, RECOVERED_KEPT))) mark.delete()
    }

    /**
     * A notebook whose notebook.json can't be read still has its pages: rather than drop it from
     * the library unseen, the file is set aside (`notebook.json.broken-<time>`) and a new one
     * lists the page files found, so the notebook shows up as «Восстановленная тетрадь». Page
     * order, PDF pages under them and the like are lost; the files themselves all stay (the
     * orphan sweep leaves such a notebook alone). Null when there is nothing to recover: a
     * folder with no pages, or the leftover of a notebook that is in the trash.
     */
    private fun recoverMeta(dir: File, cause: Throwable): Notebook? {
        if (!UUID_RE.matches(dir.name) || !dir.isDirectory) return null
        if (File(trashDir(), dir.name).exists()) return null
        val pagesDir = File(dir, "pages")
        val pages = pagesDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".ink") && UUID_RE.matches(it.name.removeSuffix(".ink")) }
            .sortedBy { it.lastModified() }
        val meta = File(dir, META)
        if (pages.isEmpty() && !meta.exists()) return null
        Log.e(TAG, "notebook ${dir.name} unreadable, recovering its pages", cause)
        return runCatching {
            if (meta.exists() && !meta.renameTo(File(dir, "$META.broken-${System.currentTimeMillis()}"))) return null
            File(dir, RECOVERED).writeText(System.currentTimeMillis().toString())
            val now = System.currentTimeMillis()
            val created = pages.minOfOrNull { it.lastModified() } ?: now
            val bg = PageBackground(PaperKind.BLANK)
            val ids = pages.map { it.name.removeSuffix(".ink") }.ifEmpty { listOf(newId()) }
            Notebook(dir.name, "Восстановленная тетрадь", bg, bg, created, now, ids).also(::writeMetaNow)
        }.onFailure { Log.e(TAG, "could not recover ${dir.name}", it) }.getOrNull()
    }

    fun create(
        title: String,
        paper: PaperKind,
        cover: Int? = null,
        folder: String? = null,
        coverArt: Int? = null,
        background: PageBackground? = null,
        pageSize: PageSize = PageSize.DEFAULT,
    ): Notebook {
        val now = System.currentTimeMillis()
        val bg = background ?: PageBackground(paper)
        val first = newId()
        // New pages take the size of the page before them, so the first one sets it for all.
        val sizes = if (pageSize == PageSize.DEFAULT) emptyMap() else mapOf(first to pageSize)
        val nb = Notebook(newId(), title, bg, bg, now, now, listOf(first), cover = cover, folder = folder, coverArt = coverArt?.takeIf { it in 0 until COVER_ARTS }, pageSizes = sizes)
        writeMeta(nb)
        return nb
    }

    // ------------------------------------------------- notes over the lock screen

    /**
     * A notebook of its own for one quick note written over the lock screen: it has only the
     * new page, so nothing written before can be seen or reached without unlocking. It is not
     * listed in the library; [mergeLockNote] moves what was written into the quick notes.
     */
    fun createLockNote(title: String, paper: PaperKind): Notebook {
        val nb = create(title, paper)
        val marker = File(dir(nb.id), LOCK_NOTE)
        background { runCatching { marker.writeText(System.currentTimeMillis().toString()) } }
        return nb
    }

    /**
     * Lock screen notes not yet moved into the quick notes. [leftOnly]: only those of an earlier
     * run of the app (stopped before it could move them), never one being written now.
     */
    fun lockNotes(leftOnly: Boolean = false): List<Notebook> = onIo {
        root.listFiles().orEmpty()
            .filter { d -> File(d, LOCK_NOTE).let { it.exists() && (!leftOnly || it.lastModified() < startedAt) } }
            .mapNotNull { runCatching { readMeta(it) }.getOrNull() }
    }

    /**
     * Moves the pages written in lock screen note [note] (empty ones are dropped), with their
     * pictures and recordings, to the end of notebook [intoId], and removes the note. With no
     * such notebook (never made, or deleted) the note itself becomes it, under [title].
     * Returns the notebook that has the pages now (null: nothing was written).
     */
    fun mergeLockNote(note: Notebook, intoId: String?, title: String): Notebook? = onIo {
        val from = dir(note.id)
        val nb = runCatching { readMeta(from) }.getOrNull()
        if (nb == null) {
            deleteQuietly(from)
            return@onIo null
        }
        val written = nb.pageIds.filter { id ->
            val f = File(from, "pages/$id.ink")
            f.isFile && runCatching { PageCodec.read(f, PageCodec.Limits.OWN) }.getOrNull()
                ?.let { it.strokes.isNotEmpty() || it.fills.isNotEmpty() || it.images.isNotEmpty() } != false
        }
        if (written.isEmpty() && nb.recordings.isEmpty()) {
            deleteQuietly(from)
            return@onIo null
        }
        val target = intoId?.takeIf { UUID_RE.matches(it) && it != note.id }?.let { id ->
            dir(id).takeIf { File(it, META).isFile && !File(it, LOCK_NOTE).exists() }?.let { runCatching { readMeta(it) }.getOrNull() }
        }
        if (target == null) {
            // Becomes the quick notes notebook itself.
            val own = nb.copy(title = title, pageIds = written.ifEmpty { nb.pageIds.take(1) }, updatedAt = System.currentTimeMillis())
            writeMetaNow(own)
            File(from, LOCK_NOTE).delete()
            return@onIo own
        }
        val into = dir(target.id)
        val intoPages = File(into, "pages").apply { mkdirs() }
        for (id in written) File(from, "pages/$id.ink").renameTo(File(intoPages, "$id.ink"))
        for (f in from.listFiles().orEmpty()) {
            if (PageImage.NAME.matches(f.name) || REC_NAME.matches(f.name) || PHOTO_NAME.matches(f.name)) f.renameTo(File(into, f.name))
        }
        val pages = written.toSet()
        val merged = target.copy(
            pageIds = target.pageIds + written,
            pageSizes = target.pageSizes + nb.pageSizes.filterKeys { it in pages },
            recordings = target.recordings + nb.recordings,
            audioMarks = (target.audioMarks + nb.audioMarks.filter { it.pageId in pages }).takeLast(MAX_MARKS),
            updatedAt = System.currentTimeMillis(),
        )
        writeMetaNow(merged)
        deleteQuietly(from)
        merged
    }

    /** [mergeLockNote] off the main thread; [onDone] on the main thread. */
    fun mergeLockNoteAsync(note: Notebook, intoId: String?, title: String, onDone: (Notebook?) -> Unit) {
        background {
            val nb = runCatching { mergeLockNote(note, intoId, title) }.onFailure { Log.e(TAG, "lock note not merged", it) }.getOrNull()
            main.post { onDone(nb) }
        }
    }

    fun rename(nb: Notebook, title: String): Notebook =
        nb.copy(title = title, updatedAt = System.currentTimeMillis()).also(::writeMeta)

    fun setCover(nb: Notebook, color: Int): Notebook =
        nb.copy(cover = color or 0xFF000000.toInt(), updatedAt = System.currentTimeMillis()).also(::writeMeta)

    fun setCoverArt(nb: Notebook, art: Int?): Notebook =
        nb.copy(coverArt = art?.takeIf { it in 0 until COVER_ARTS }, updatedAt = System.currentTimeMillis()).also(::writeMeta)

    fun setPlasticKind(nb: Notebook, kind: Int?): Notebook =
        nb.copy(plasticKind = kind?.takeIf { it in 0 until PLASTIC_KINDS }, updatedAt = System.currentTimeMillis()).also(::writeMeta)

    /** Keeps the entries in page order, top to bottom; entries of missing pages are dropped. */
    fun setToc(nb: Notebook, toc: List<Bookmark>): Notebook {
        val order = nb.pageIds.withIndex().associate { it.value to it.index }
        val sorted = toc.filter { it.pageId in order }.sortedWith(compareBy({ order[it.pageId] }, { it.y }))
            .map { it.copy(title = it.title.take(MAX_TITLE)) }.take(MAX_TOC)
        return nb.copy(toc = sorted, updatedAt = System.currentTimeMillis()).also(::writeMeta)
    }

    fun setLinks(nb: Notebook, links: List<PageLink>): Notebook =
        nb.copy(links = links.filter { it.pageId in nb.pageIds && it.target in nb.pageIds }.take(MAX_LINKS), updatedAt = System.currentTimeMillis())
            .also(::writeMeta)

    fun setLayers(nb: Notebook, layers: List<LayerInfo>, active: Int): Notebook {
        val list = layers.take(Layers.MAX).map { it.copy(name = it.name.take(MAX_TITLE)) }
        return nb.copy(layers = list, activeLayer = active.coerceIn(0, maxOf(0, list.lastIndex)), updatedAt = System.currentTimeMillis()).also(::writeMeta)
    }

    // ------------------------------------------------------------ recordings

    /** A file for a new recording in the notebook's folder. */
    fun newRecordingFile(nb: Notebook): File = File(dir(nb.id).apply { mkdirs() }, "rec-${newId()}.m4a")

    fun recordingFile(nb: Notebook, name: String): File? = if (REC_NAME.matches(name)) File(dir(nb.id), name).takeIf { it.isFile } else null

    fun addRecording(nb: Notebook, rec: Recording, marks: List<AudioMark>): Notebook =
        nb.copy(
            recordings = nb.recordings + rec,
            audioMarks = (nb.audioMarks + marks.filter { it.pageId in nb.pageIds }).takeLast(MAX_MARKS),
            updatedAt = System.currentTimeMillis(),
        ).also(::writeMeta)

    /**
     * A recording under way, written down beside its file (`rec-….m4a.live`): when it began and
     * the writing made so far. If the app is stopped before the recording is (killed by the
     * system, out of battery), the next start adds it to its notebook from this (see
     * [recoverRecordings]); a finished recording removes it ([endRecordingJournal]).
     */
    fun saveRecordingJournal(nb: Notebook, file: String, since: Long, marks: List<AudioMark>) {
        if (!REC_NAME.matches(file)) return
        val json = JSONObject().put("at", since).put(
            "marks",
            JSONArray(marks.takeLast(MAX_MARKS).map { JSONObject().put("page", it.pageId).put("l", it.left.toDouble()).put("t", it.top.toDouble()).put("r", it.right.toDouble()).put("b", it.bottom.toDouble()).put("ms", it.atMs) }),
        ).toString().toByteArray()
        val target = File(dir(nb.id), "$file$LIVE")
        background { if (target.parentFile?.isDirectory == true) atomicWrite(target) { it.write(json) } }
    }

    fun endRecordingJournal(nb: Notebook, file: String) {
        if (!REC_NAME.matches(file)) return
        val target = File(dir(nb.id), "$file$LIVE")
        background { target.delete() }
    }

    /**
     * Recordings left unfinished by an app that was stopped: each one whose file has sound goes
     * into its notebook with the writing made during it (the format, AAC in ADTS frames, plays
     * up to where it was cut off). Run at start, before any recording can be under way.
     */
    private fun recoverRecordings() {
        for (d in root.listFiles().orEmpty()) {
            if (d.name.startsWith(".") || !d.isDirectory) continue
            val journals = d.listFiles().orEmpty().filter { it.name.endsWith(LIVE) }
            if (journals.isEmpty()) continue
            var nb = runCatching { readMeta(d) }.getOrNull() ?: continue
            for (j in journals) {
                val name = j.name.removeSuffix(LIVE)
                val audio = File(d, name)
                runCatching {
                    if (!REC_NAME.matches(name) || !audio.isFile || audio.length() < MIN_RECORDING_BYTES) {
                        audio.takeIf { REC_NAME.matches(name) }?.delete()
                        return@runCatching
                    }
                    if (nb.recordings.any { it.file == name }) return@runCatching
                    val o = JSONObject(j.readText())
                    val since = o.getLong("at")
                    val pages = nb.pageIds.toSet()
                    val a = o.optJSONArray("marks") ?: JSONArray()
                    val marks = (0 until minOf(a.length(), MAX_MARKS)).mapNotNull { i ->
                        val m = a.optJSONObject(i) ?: return@mapNotNull null
                        val page = m.optString("page").takeIf { it in pages } ?: return@mapNotNull null
                        AudioMark(page, m.optDouble("l").toFloat(), m.optDouble("t").toFloat(), m.optDouble("r").toFloat(), m.optDouble("b").toFloat(), name, m.optLong("ms"))
                    }
                    val duration = durationOf(audio) ?: (audio.lastModified() - since).coerceAtLeast(0L)
                    nb = nb.copy(
                        recordings = nb.recordings + Recording(name, since, duration),
                        audioMarks = (nb.audioMarks + marks).takeLast(MAX_MARKS),
                        updatedAt = System.currentTimeMillis(),
                    )
                    writeMetaNow(nb)
                    Log.w(TAG, "recording $name of ${nb.id} recovered")
                }.onFailure { Log.e(TAG, "recording ${j.name} not recovered", it) }
                j.delete()
            }
        }
    }

    private fun durationOf(file: File): Long? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } finally {
            r.release()
        }
    }.getOrNull()

    fun setAudioMarks(nb: Notebook, marks: List<AudioMark>): Notebook =
        nb.copy(audioMarks = marks.filter { it.pageId in nb.pageIds }.takeLast(MAX_MARKS)).also(::writeMeta)

    fun deleteRecording(nb: Notebook, rec: Recording): Notebook {
        val updated = nb.copy(recordings = nb.recordings - rec, audioMarks = nb.audioMarks.filter { it.file != rec.file }, updatedAt = System.currentTimeMillis())
        writeMeta(updated)
        if (REC_NAME.matches(rec.file)) background { File(dir(nb.id), rec.file).delete() }
        return updated
    }

    fun setFavorite(nb: Notebook, favorite: Boolean): Notebook =
        nb.copy(favorite = favorite).also(::writeMeta)

    fun moveToFolder(nb: Notebook, folder: String?): Notebook =
        nb.copy(folder = folder).also(::writeMeta)

    // ---------------------------------------------------------------- folders

    fun folders(): List<Folder> = onIo { readFoldersOrNull().orEmpty() }

    /** The folder list, or null when it can't be read now (then nothing may be written over it). */
    private fun readFoldersOrNull(): List<Folder>? = runCatching { readFolders() }.getOrElse {
        foldersProblem = "Не удалось прочитать список папок; изменения папок не сохранены. Попробуйте ещё раз."
        null
    }

    fun createFolder(name: String, parent: String? = null): Folder = onIo {
        val folders = readFoldersOrNull() ?: return@onIo Folder(newId(), name.take(MAX_TITLE), null)
        val p = parent?.takeIf { id -> folders.any { it.id == id } }
        Folder(newId(), name.take(MAX_TITLE), p).also { writeFolders(folders + it) }
    }

    /** Adds [folder] made elsewhere (its id new); its parent must exist, else it goes to the top. */
    fun addFolder(folder: Folder) = onIo {
        val folders = readFoldersOrNull() ?: return@onIo
        if (!UUID_RE.matches(folder.id) || folders.any { it.id == folder.id }) return@onIo
        val p = folder.parent?.takeIf { id -> folders.any { it.id == id } }
        writeFolders(folders + folder.copy(name = folder.name.take(MAX_TITLE), parent = p))
    }

    /** Moves [folder] under [parent] (null = top level); refused if that would make a loop. */
    fun moveFolder(folder: Folder, parent: String?): Boolean = onIo {
        val folders = readFoldersOrNull() ?: return@onIo false
        if (parent != null && (parent in FolderTree.subtree(folders, folder.id) || folders.none { it.id == parent })) {
            return@onIo false
        }
        writeFolders(folders.map { if (it.id == folder.id) it.copy(parent = parent) else it })
        true
    }

    /**
     * Moves several notebooks and folders into [into] (null = top level) at once. A folder that
     * would end up inside itself stays where it is. Returns notebooks and folders moved.
     */
    fun moveAll(notebooks: List<Notebook>, folderIds: Set<String>, into: String?): Pair<List<Notebook>, Int> = onIo {
        val folders = readFoldersOrNull() ?: return@onIo emptyList<Notebook>() to 0
        val target = into?.takeIf { id -> folders.any { it.id == id } }
        val movable = folderIds.filter { id -> target == null || target !in FolderTree.subtree(folders, id) }.toSet()
        val moved = folders.filter { it.id in movable && it.parent != target }
        if (moved.isNotEmpty()) writeFolders(folders.map { if (it in moved) it.copy(parent = target) else it })
        notebooks.filter { it.folder != target }.map { nb -> nb.copy(folder = target).also(::writeMetaNow) } to moved.size
    }

    fun renameFolder(folder: Folder, name: String) = onIo {
        val folders = readFoldersOrNull() ?: return@onIo
        writeFolders(folders.map { if (it.id == folder.id) it.copy(name = name.take(MAX_TITLE)) else it })
    }

    /** Removes the folder; its notebooks and subfolders move up to the folder's parent. */
    fun deleteFolder(folder: Folder) = onIo {
        val folders = readFoldersOrNull() ?: return@onIo
        val parent = folders.find { it.id == folder.id }?.parent
        writeFolders(folders.filter { it.id != folder.id }.map { if (it.parent == folder.id) it.copy(parent = parent) else it })
        for (dir in root.listFiles().orEmpty()) {
            if (dir.name.startsWith(".")) continue
            val nb = runCatching { readMeta(dir) }.getOrNull() ?: continue
            if (nb.folder == folder.id) writeMetaNow(nb.copy(folder = parent))
        }
    }

    /**
     * Set when the folder list could not be read: the damaged file was put aside (nothing is
     * written over it) and the library says so once.
     */
    @Volatile
    var foldersProblem: String? = null

    private fun readFolders(): List<Folder> {
        val file = File(root, FOLDERS)
        if (!file.exists()) return emptyList()
        val raw = runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Folder(o.getString("id"), o.getString("name").take(MAX_TITLE), o.optString("parent").takeIf(UUID_RE::matches))
            }.filter { UUID_RE.matches(it.id) }.distinctBy { it.id }
        }.getOrElse { e ->
            if (e is IOException || e is OutOfMemoryError) {
                // Could not be read just now: the file stays, and nothing may be written over it.
                throw IOException("folder list unreadable", e)
            }
            // Damaged: kept aside under its own name, so the next write cannot destroy it.
            val aside = File(root, "$FOLDERS.broken-${System.currentTimeMillis()}")
            if (!file.renameTo(aside)) throw IOException("folder list damaged and can't be moved aside", e)
            Log.w(TAG, "folder list damaged, kept as ${aside.name}", e)
            foldersProblem = "Список папок повреждён: тетради показаны без папок. Старый список сохранён в файле ${aside.name}."
            emptyList()
        }
        // A missing parent or a loop (from a damaged file) puts the folder at the top level.
        val ids = raw.map { it.id }.toSet()
        return raw.map { f ->
            val broken = f.parent != null && (f.parent !in ids || FolderTree.path(raw, f.id).size > raw.size ||
                FolderTree.path(raw, f.id).firstOrNull()?.parent != null)
            if (broken) f.copy(parent = null) else f
        }
    }

    private fun writeFolders(folders: List<Folder>) {
        val arr = JSONArray(folders.map { f -> JSONObject().put("id", f.id).put("name", f.name).apply { f.parent?.let { put("parent", it) } } })
        atomicWrite(File(root, FOLDERS)) { it.write(arr.toString().toByteArray()) }
    }

    // ------------------------------------------------------------------ trash

    /** A notebook in the trash and when it went there. */
    class Trashed(val notebook: Notebook, val deletedAt: Long)

    /**
     * Moves the notebook to the trash (a rename, instant at any size); it can be restored
     * for [TRASH_DAYS] days, then [purgeOldTrash] removes it.
     */
    fun delete(nb: Notebook) = background {
        val from = dir(nb.id)
        if (!from.isDirectory) return@background
        val to = File(trashDir(), nb.id)
        // An older copy of it already in the trash (deleted, restored, deleted again) is kept
        // under an id of its own, not removed unseen.
        if (to.exists()) keepAsOtherTrashCopy(to)
        if (from.renameTo(to)) {
            runCatching { File(to, DELETED_AT).writeText(System.currentTimeMillis().toString()) }
        } else {
            Log.w(TAG, "could not move ${nb.id} to the trash")
        }
    }

    private fun keepAsOtherTrashCopy(old: File) {
        val nb = runCatching { readMeta(old) }.getOrNull()
        if (nb == null) {
            deleteQuietly(old)
            return
        }
        val title = if (nb.title.endsWith(OLD_COPY)) nb.title else "${nb.title}$OLD_COPY".take(MAX_TITLE)
        val copy = nb.copy(id = newId(), title = title)
        val moved = File(trashDir(), copy.id)
        if (!old.renameTo(moved)) {
            deleteQuietly(old)
            return
        }
        runCatching { atomicWrite(File(moved, META)) { it.write(encodeMeta(copy, moved).second) } }
    }

    val trashDays get() = TRASH_DAYS

    fun trash(): List<Trashed> = onIo {
        trashDir().listFiles().orEmpty().mapNotNull { d ->
            val nb = runCatching { readMeta(d) }.getOrNull()?.takeIf { it.id == d.name } ?: return@mapNotNull null
            Trashed(nb, deletedAt(d))
        }.sortedByDescending { it.deletedAt }
    }

    /** Back from the trash; into the top level when its folder is gone. Null if it failed. */
    fun restore(nb: Notebook): Notebook? = onIo {
        val from = File(trashDir(), nb.id)
        val to = dir(nb.id)
        if (!from.isDirectory) return@onIo null
        // A late index write may have left an empty folder with the same name.
        if (to.exists() && !File(to, META).isFile) deleteQuietly(to)
        if (to.exists() || !from.renameTo(to)) return@onIo null
        File(to, DELETED_AT).delete()
        val meta = runCatching { readMeta(to) }.getOrNull() ?: return@onIo null
        val folders = readFoldersOrNull() ?: return@onIo meta
        if (meta.folder != null && folders.none { it.id == meta.folder }) meta.copy(folder = null).also(::writeMetaNow) else meta
    }

    /** Deletes the notebook for good (from the trash). */
    fun purge(nb: Notebook) {
        background { deleteQuietly(File(trashDir(), nb.id)) }
    }

    fun emptyTrash() {
        background { trashDir().listFiles().orEmpty().forEach { deleteQuietly(it) } }
    }

    /** Removes notebooks that have been in the trash longer than [TRASH_DAYS]. */
    fun purgeOldTrash() {
        background {
            val limit = System.currentTimeMillis() - TRASH_DAYS * 24L * 3600 * 1000
            trashDir().listFiles().orEmpty().filter { deletedAt(it) < limit }.forEach { deleteQuietly(it) }
        }
    }

    private fun trashDir() = File(root, TRASH).apply { mkdirs() }

    /**
     * When [d] went to the trash. Without a readable record (it could not be written then) the
     * time is taken as now and written down: the folder's own date is when the notebook was
     * last changed, maybe long ago, and the notebook would be removed for good at once.
     */
    private fun deletedAt(d: File): Long =
        runCatching { File(d, DELETED_AT).readText().trim().toLong() }.getOrElse {
            val now = System.currentTimeMillis()
            runCatching { File(d, DELETED_AT).writeText(now.toString()) }
            now
        }

    fun insertPage(nb: Notebook, index: Int, size: PageSize = PageSize.DEFAULT): Notebook {
        val id = newId()
        val pages = nb.pageIds.toMutableList().apply { add(index, id) }
        val sizes = if (size == PageSize.DEFAULT) nb.pageSizes else nb.pageSizes + (id to size)
        return nb.copy(pageIds = pages, pageSizes = sizes, updatedAt = System.currentTimeMillis()).also(::writeMeta)
    }

    /** Gives paper pages [pageIds] the [size]; PDF pages keep theirs. */
    fun setPageSize(nb: Notebook, pageIds: Collection<String>, size: PageSize): Notebook {
        val sizes = nb.pageSizes.toMutableMap()
        for (id in pageIds) {
            if (id !in nb.pageIds || id in nb.pdfPages) continue
            if (size == PageSize.DEFAULT) sizes.remove(id) else sizes[id] = size
        }
        return nb.copy(pageSizes = sizes, updatedAt = System.currentTimeMillis()).also(::writeMeta)
    }

    fun deletePage(nb: Notebook, pageId: String): Notebook {
        require(nb.pageIds.size > 1) { "a notebook keeps at least one page" }
        val updated = nb.copy(
            pageIds = nb.pageIds - pageId,
            pdfPages = nb.pdfPages - pageId,
            pageSizes = nb.pageSizes - pageId,
            toc = nb.toc.filter { it.pageId != pageId },
            audioMarks = nb.audioMarks.filter { it.pageId != pageId },
            links = nb.links.filter { it.pageId != pageId && it.target != pageId },
            updatedAt = System.currentTimeMillis(),
        )
        writeMeta(updated)
        val file = pageFile(nb.id, pageId)
        background { file.delete() }
        return updated
    }

    /**
     * A copy of page [index] right after it: same paper size, the same PDF page under it,
     * and [content] (the page's elements as they are now) on it.
     */
    fun duplicatePage(nb: Notebook, index: Int, content: PageContent): Notebook {
        val src = nb.pageIds[index]
        val id = newId()
        val pages = nb.pageIds.toMutableList().apply { add(index + 1, id) }
        val sizes = nb.pageSizes[src]?.let { nb.pageSizes + (id to it) } ?: nb.pageSizes
        val pdf = nb.pdfPages[src]?.let { nb.pdfPages + (id to it) } ?: nb.pdfPages
        val updated = nb.copy(pageIds = pages, pageSizes = sizes, pdfPages = pdf, updatedAt = System.currentTimeMillis())
        writeMeta(updated)
        // Queued before anything can load the new page, on the same thread.
        savePageAsync(updated, id, content)
        return updated
    }

    fun movePage(nb: Notebook, from: Int, to: Int): Notebook {
        val pages = nb.pageIds.toMutableList()
        pages.add(to, pages.removeAt(from))
        return nb.copy(pageIds = pages, updatedAt = System.currentTimeMillis()).also(::writeMeta)
    }

    /**
     * The notebook changed (a page was saved): only its time, in a file of a few bytes, so
     * that writing does not rewrite the whole notebook.json (hundreds of KB for a big PDF).
     */
    fun touch(nb: Notebook): Notebook {
        val now = nb.copy(updatedAt = System.currentTimeMillis())
        val file = File(dir(nb.id), TOUCHED)
        background { if (file.parentFile?.isDirectory == true) runCatching { file.writeText(now.updatedAt.toString()) } }
        return now
    }

    private fun readTouched(dir: File): Long = runCatching { File(dir, TOUCHED).takeIf { it.isFile && it.length() < 32 }?.readText()?.trim()?.toLong() }.getOrNull() ?: 0L

    /** Replaces one theme's background and deletes a photo that is no longer used. */
    fun setBackground(nb: Notebook, darkTheme: Boolean, bg: PageBackground): Notebook {
        val updated = (if (darkTheme) nb.copy(dark = bg) else nb.copy(light = bg)).copy(updatedAt = System.currentTimeMillis())
        val old = nb.background(darkTheme).photo
        val (file, bytes) = encodeMeta(updated)
        // The old photo goes only once notebook.json no longer names it: stopped in between,
        // the notebook keeps a photo it does not use, never names one that is gone.
        background {
            atomicWrite(file) { it.write(bytes) }
            if (old != null && old != updated.light.photo && old != updated.dark.photo) File(dir(nb.id), old).delete()
        }
        return updated
    }

    /**
     * Copies the picked image into the notebook, downscaled to at most [MAX_PHOTO_SIDE] px,
     * and calls [onDone] on the main thread with the stored file name (null on failure).
     */
    fun importPhotoAsync(nb: Notebook, uri: Uri, resolver: ContentResolver, onDone: (String?) -> Unit) {
        transferring {
            val name = runCatching {
                val source = ImageDecoder.createSource(resolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val side = max(info.size.width, info.size.height)
                    if (side > MAX_PHOTO_SIDE) {
                        val k = MAX_PHOTO_SIDE.toFloat() / side
                        decoder.setTargetSize((info.size.width * k).toInt(), (info.size.height * k).toInt())
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val fileName = "bg-${newId()}.jpg"
                try {
                    atomicWrite(File(dir(nb.id).apply { mkdirs() }, fileName)) { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                } finally {
                    bitmap.recycle()
                }
                fileName
            }.getOrNull()
            main.post { onDone(name) }
        }
    }

    /**
     * Creates a notebook with one page per page of the picked PDF (the file is copied in)
     * and calls [onDone] on the main thread; null if the PDF can't be read (e.g. encrypted).
     */
    fun importPdfAsync(
        title: String,
        uri: Uri,
        resolver: ContentResolver,
        outline: (File) -> List<PdfOutlineItem> = { emptyList() },
        onDone: (Notebook?) -> Unit,
    ) {
        transferring {
            val id = newId()
            val notebook = runCatching {
                val (file, refs) = copyPdf(id, uri, resolver)
                val now = System.currentTimeMillis()
                val pageIds = refs.map { newId() }
                val bg = PageBackground(PaperKind.BLANK)
                val toc = runCatching { bookmarks(outline(file), pageIds, refs) }.getOrDefault(emptyList())
                Notebook(id, title, bg, bg, now, now, pageIds, pageIds.zip(refs).toMap(), toc = toc.take(MAX_TOC))
                    .also { writeMetaNow(it) }
            }.getOrElse {
                deleteQuietly(dir(id))
                null
            }
            main.post { onDone(notebook) }
        }
    }

    /** A PDF copied into the notebook, its pages and contents, ready for [addPdfPages]. */
    class PdfPart(val refs: List<PdfPageRef>, val outline: List<PdfOutlineItem>)

    /**
     * Copies a picked PDF into notebook [nb] and reads its pages; [onDone] on the main thread
     * (null if it can't be read). The pages go in with [addPdfPages], on the main thread.
     */
    fun copyPdfAsync(nb: Notebook, uri: Uri, resolver: ContentResolver, outline: (File) -> List<PdfOutlineItem>, onDone: (PdfPart?) -> Unit) {
        transferring {
            var copied: File? = null
            val part = runCatching {
                val (file, refs) = copyPdf(nb.id, uri, resolver)
                copied = file
                PdfPart(refs, runCatching { outline(file) }.getOrDefault(emptyList()))
            }.getOrElse {
                copied?.delete()
                null
            }
            main.post { onDone(part) }
        }
    }

    /** The pages of [part] put in at [at], its contents added to the notebook's. */
    fun addPdfPages(nb: Notebook, at: Int, part: PdfPart): Notebook {
        val ids = part.refs.map { newId() }
        val pages = nb.pageIds.toMutableList().apply { addAll(at.coerceIn(0, size), ids) }
        val updated = nb.copy(pageIds = pages, pdfPages = nb.pdfPages + ids.zip(part.refs), updatedAt = System.currentTimeMillis())
        val toc = runCatching { bookmarks(part.outline, ids, part.refs) }.getOrDefault(emptyList())
        return setToc(updated, nb.toc + toc)
    }

    /** The PDF's own contents as entries of the notebook's, [pageIds] being its pages in order. */
    fun bookmarks(items: List<PdfOutlineItem>, pageIds: List<String>, refs: List<PdfPageRef>): List<Bookmark> =
        items.mapNotNull { item ->
            val pageId = pageIds.getOrNull(item.page) ?: return@mapNotNull null
            val ref = refs[item.page]
            val y = PdfGeometry.map(ref.pdfToUnits(), 0f, item.y * ref.height).second.coerceAtLeast(0f)
            Bookmark(pageId, y, "\u2003".repeat(item.depth) + item.title)
        }

    /** PDF pages laid out anew ([refs]), with the contents and links moved along. */
    fun setPdfPages(nb: Notebook, refs: Map<String, PdfPageRef>, toc: List<Bookmark>, links: List<PageLink>): Notebook =
        nb.copy(
            pdfPages = nb.pdfPages + refs.filterKeys { it in nb.pageIds },
            toc = toc,
            links = links,
            updatedAt = System.currentTimeMillis(),
        ).also(::writeMeta)

    private fun copyPdf(notebookId: String, uri: Uri, resolver: ContentResolver): Pair<File, List<PdfPageRef>> {
        val fileName = "doc-${newId()}.pdf"
        val file = File(dir(notebookId).apply { mkdirs() }, fileName)
        try {
            atomicWrite(file) { out ->
                (resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")).use { it.copyTo(out) }
            }
            val refs = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    List(pdf.pageCount) { i ->
                        pdf.openPage(i).use { page ->
                            val w = page.width.toFloat()
                            val h = page.height.toFloat()
                            if (!validPdfPageSize(w, h)) throw IOException("unsupported page size ${w}x$h")
                            PdfPageRef(fileName, i, w, h)
                        }
                    }
                }
            }
            if (refs.isEmpty()) throw IOException("empty PDF")
            return file to refs
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
    }

    /**
     * Copies a picked picture into the notebook, downscaled to at most [MAX_IMAGE_SIDE] px
     * (PNG if it has transparency, JPEG otherwise), and calls [onDone] on the main thread
     * with the file name and pixel size, or null on failure.
     */
    fun importImageAsync(nb: Notebook, uri: Uri, resolver: ContentResolver, onDone: (Triple<String, Int, Int>?) -> Unit) {
        transferring {
            val result = runCatching {
                val source = ImageDecoder.createSource(resolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val side = max(info.size.width, info.size.height)
                    if (side > MAX_IMAGE_SIDE) {
                        val k = MAX_IMAGE_SIDE.toFloat() / side
                        decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val png = bitmap.hasAlpha()
                val fileName = "img-${newId()}.${if (png) "png" else "jpg"}"
                atomicWrite(File(dir(nb.id).apply { mkdirs() }, fileName)) { out ->
                    bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, out)
                }
                Triple(fileName, bitmap.width, bitmap.height).also { bitmap.recycle() }
            }.getOrNull()
            main.post { onDone(result) }
        }
    }

    /**
     * Saves an edited picture (cropped, straightened…) as a new file of the notebook (PNG when
     * it has see-through parts, JPEG otherwise) and calls [onDone] on the main thread with its
     * name and pixel size, or null on failure. The bitmap is recycled.
     */
    fun saveImageAsync(nb: Notebook, bitmap: Bitmap, onDone: (Triple<String, Int, Int>?) -> Unit) {
        transferring {
            val result = runCatching {
                val png = bitmap.hasAlpha()
                val fileName = "img-${newId()}.${if (png) "png" else "jpg"}"
                atomicWrite(File(dir(nb.id).apply { mkdirs() }, fileName)) { out ->
                    bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
                }
                Triple(fileName, bitmap.width, bitmap.height)
            }.getOrNull()
            bitmap.recycle()
            main.post { onDone(result) }
        }
    }

    /** Decodes a picture from [uri] (camera or gallery), at most [MAX_IMAGE_SIDE] px, upright. Blocking. */
    fun decodePicture(uri: Uri, resolver: ContentResolver): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val side = max(info.size.width, info.size.height)
            if (side > MAX_IMAGE_SIDE) {
                val k = MAX_IMAGE_SIDE.toFloat() / side
                decoder.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
    }.getOrNull()

    /**
     * A picture placed on a page, decoded no larger than [MAX_IMAGE_SIDE] px whatever the file
     * says (it may come from someone else's notebook); null for unknown names or bad files.
     */
    fun loadImage(nb: Notebook, name: String, maxSide: Int = MAX_IMAGE_SIDE): Bitmap? {
        if (!PageImage.NAME.matches(name)) return null
        return decodeSampled(File(dir(nb.id), name), maxSide)
    }

    /** A background photo decoded no larger than [maxSide] px (for small previews). */
    fun loadPhoto(nb: Notebook, name: String, maxSide: Int): Bitmap? =
        if (!PHOTO_NAME.matches(name)) null else decodeSampled(File(dir(nb.id), name), maxSide)

    private fun decodeSampled(file: File, maxSide: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            // Header sizes no real picture has are refused before decoding anything.
            if (w <= 0 || h <= 0 || max(w, h) > MAX_IMAGE_HEADER_SIDE) return null
            var sample = 1
            while (max(w, h) / sample > maxSide) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            if (max(bitmap.width, bitmap.height) > maxSide * 2) {
                bitmap.recycle()
                return null
            }
            bitmap
        }.getOrNull()
    }

    /** The copied PDF of a notebook, or null for names this class didn't generate. */
    fun pdfFile(nb: Notebook, name: String): File? =
        if (PDF_NAME.matches(name)) File(dir(nb.id), name) else null

    // ------------------------------------------------------------ .inknote files

    /** Result of reading a shared `.inknote` file, before it is added to the library. */
    sealed interface ArchiveImport {
        /** Unpacked into [staging]; [existing] is the local notebook with the same id, if any. */
        class Ready(val notebook: Notebook, val staging: File, val existing: Notebook?) : ArchiveImport
        class Failed(val reason: String) : ArchiveImport
    }

    /**
     * Writes the notebook as a zip: manifest, notebook.json, page files, background photos
     * and imported PDFs. Everything a friend needs to keep editing it in their own app.
     */
    fun exportArchiveAsync(nb: Notebook, open: () -> OutputStream?, onDone: (Boolean) -> Unit) {
        transferring {
            val ok = runCatching {
                val snapshot = snapshot(nb) { it }
                try {
                    (open() ?: throw IOException("cannot open output")).use { raw -> snapshot.inputStream().use { it.copyTo(raw) } }
                } finally {
                    snapshot.delete()
                }
            }.isSuccess
            main.post { onDone(ok) }
        }
    }

    /**
     * The `.inknote` of [nb] as it is now, written on [io] to a local file (quick), so the
     * slow copy to wherever it goes does not hold up the library. [adjust] picks the version
     * to write from the one on disk; it throws if the notebook went away.
     */
    private fun snapshot(nb: Notebook, adjust: (Notebook) -> Notebook): File = onIo {
        val now = runCatching { readMeta(dir(nb.id)) }.getOrNull()?.takeIf { it.id == nb.id }
            ?: throw IOException("notebook ${nb.id} is gone")
        val file = File(root, ".export-${newId()}")
        try {
            file.outputStream().buffered().use { writeArchive(adjust(now), it) }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        file
    }

    /** The `.inknote` zip of [nb] into [out]; [out] is finished but not closed. */
    private fun writeArchive(nb: Notebook, out: OutputStream) {
        val dir = dir(nb.id)
        val zip = ZipOutputStream(out)
        fun put(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        fun putFile(name: String, file: File) {
            if (!file.isFile) return
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        put(ARCHIVE_MANIFEST, JSONObject().put("format", ARCHIVE_FORMAT).put("version", ARCHIVE_VERSION).toString().toByteArray())
        put(META, encodeMeta(nb.copy(folder = null, favorite = false)).second)
        for (pageId in nb.pageIds) putFile("pages/$pageId.ink", File(dir, "pages/$pageId.ink"))
        // Only pictures some page still uses: those of deleted pages stay out of the file.
        val pictures = usedPictures(nb, dir)
        val extras = listOfNotNull(nb.light.photo, nb.dark.photo) + nb.pdfPages.values.map { it.file } + pictures + nb.recordings.map { it.file }
        for (name in extras.distinct()) putFile(name, File(dir, name))
        zip.finish()
        zip.flush()
    }

    /**
     * Pictures in [dir] that a page of [nb] uses. Copies of damaged pages kept beside them
     * (`.broken-…`, `.kept-…`) count as pages: the pictures they name stay, so a copy that is
     * repaired later still has them.
     */
    private fun usedPictures(nb: Notebook, dir: File): Set<String> {
        val all = dir.list().orEmpty().filter { PageImage.NAME.matches(it) }.toSet()
        val pagesDir = File(dir, "pages")
        val copies = pagesDir.list().orEmpty().filter { PageCopies.pageOf(it) != null }.map { File(pagesDir, it) }
        val pages = (nb.pageIds.asSequence().map { File(pagesDir, "$it.ink") } + copies.asSequence())
            .filter { it.isFile }.map { it.readBytes() }
        return Orphans.referenced(all, pages)
    }

    private fun sweepNext(dirs: List<File>, i: Int) {
        if (i >= dirs.size) return
        runCatching { sweepOrphans(dirs[i]) }.onFailure { Log.w(TAG, "orphan sweep failed", it) }
        background { sweepNext(dirs, i + 1) }
    }

    /**
     * Pictures and PDFs no page of the notebook in [dir] uses any more (left by deleted pages)
     * are removed from disk, only files older than a day. Started when the app starts; a
     * notebook changed since then is left alone (an undo there may still bring a page back).
     */
    private fun sweepOrphans(dir: File) {
        val old = System.currentTimeMillis() - 24L * 3600 * 1000
        if (!dir.isDirectory || File(dir, RECOVERED).exists() || File(dir, RECOVERED_KEPT).exists() || dir.name in savedThisRun) return
        val nb = runCatching { readMeta(dir) }.getOrNull() ?: return
        val used = runCatching { usedPictures(nb, dir) }.getOrNull() ?: return
        val pdfs = nb.pdfPages.values.map { it.file }.toSet()
        for (f in dir.listFiles().orEmpty()) {
            if (f.lastModified() > old) continue
            val orphan = (PageImage.NAME.matches(f.name) && f.name !in used) || (PDF_NAME.matches(f.name) && f.name !in pdfs && f.name != nb.light.photo && f.name != nb.dark.photo)
            if (orphan) f.delete()
        }
    }

    // ------------------------------------------------------- .inknotes (several)

    /** Notebooks to import from one or more files, with the folders a bundle brought along. */
    class ImportSet(val items: List<Staged>, val folders: List<Folder>, val failures: List<String>) {
        val conflicts get() = items.count { it.ready.existing != null }
    }

    /** A staged notebook and the id of its folder inside the bundle (null: none). */
    class Staged(val ready: ArchiveImport.Ready, val folder: String?)

    /**
     * Several notebooks with their folders in one file (`.inknotes`): a manifest listing the
     * folders and, for each notebook, a whole `.inknote` archive inside. [folders] are the
     * folders to recreate (parents outside the set become top level); a notebook keeps its
     * folder if that folder is in the set.
     */
    fun exportBundleAsync(notebooks: List<Notebook>, folders: List<Folder>, open: () -> OutputStream?, onDone: (Boolean) -> Unit) {
        transferring {
            val ok = runCatching { writeBundle(notebooks, folders, open) }.isSuccess
            main.post { onDone(ok) }
        }
    }

    /**
     * Saves every notebook and folder into one `.inknotes` file on its own thread, so the
     * library and the editor never wait for it. Files are replaced only by atomic renames,
     * so reading them beside the IO thread sees whole versions.
     */
    fun backupAsync(open: () -> OutputStream?, onDone: (Boolean) -> Unit) {
        backups.execute {
            val ok = runCatching { writeBundle(list(), folders(), open) }
                .onFailure { Log.w(TAG, "backup failed", it) }.isSuccess
            main.post { onDone(ok) }
        }
    }

    /** The newest change to any notebook or folder list, to skip backups of nothing new. */
    fun lastChange(): Long = onIo {
        // A saved page is renamed into pages/, which bumps that folder's time.
        val metas = root.listFiles().orEmpty().filter { !it.name.startsWith(".") }
            .flatMap { listOf(File(it, META).lastModified(), File(it, "pages").lastModified(), it.lastModified()) }
        (metas + File(root, FOLDERS).lastModified() + trashDir().lastModified()).maxOrNull() ?: 0L
    }

    /**
     * Each notebook is archived on the IO thread whole and as it is now, then copied out; one
     * deleted meanwhile fails the copy (a backup is made again later) instead of leaving a
     * notebook with missing pages in it.
     */
    private fun writeBundle(notebooks: List<Notebook>, folders: List<Folder>, open: () -> OutputStream?) {
        val ids = folders.map { it.id }.toSet()
        (open() ?: throw IOException("cannot open output")).use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                val list = JSONArray()
                notebooks.forEachIndexed { i, nb -> list.put(JSONObject().put("file", "notebooks/$i.inknote").apply { nb.folder?.takeIf { it in ids }?.let { put("folder", it) } }) }
                val fl = JSONArray(folders.map { f -> JSONObject().put("id", f.id).put("name", f.name).apply { f.parent?.takeIf { it in ids }?.let { put("parent", it) } } })
                val manifest = JSONObject().put("format", BUNDLE_FORMAT).put("version", BUNDLE_VERSION).put("folders", fl).put("notebooks", list)
                zip.putNextEntry(ZipEntry(BUNDLE_MANIFEST))
                zip.write(manifest.toString(2).toByteArray())
                zip.closeEntry()
                // The notebooks are zips already: storing them again saves only time.
                zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                notebooks.forEachIndexed { i, nb ->
                    // Each notebook whole and as it is now, copied out off the IO thread.
                    val snapshot = snapshot(nb) { it.copy(folder = nb.folder) }
                    try {
                        zip.putNextEntry(ZipEntry("notebooks/$i.inknote"))
                        snapshot.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    } finally {
                        snapshot.delete()
                    }
                }
            }
        }
    }

    /**
     * Reads `.inknote` and `.inknotes` files (any mix) into staging folders, each checked like
     * a single shared notebook. A file that cannot be read is reported in [ImportSet.failures].
     */
    fun readImportsAsync(opens: List<() -> InputStream?>, onDone: (ImportSet) -> Unit) {
        transferring {
            val items = ArrayList<Staged>()
            val folders = ArrayList<Folder>()
            val failures = ArrayList<String>()
            // One budget for everything written to staging by this import, however the files nest.
            val budget = Budget(MAX_BUNDLE_BYTES, MAX_BUNDLE_ENTRIES)
            for (open in opens.take(MAX_BUNDLE_NOTEBOOKS)) {
                try {
                    if (isBundle(open)) {
                        val (staged, bundleFolders) = readBundle(open, budget)
                        items += staged
                        folders += bundleFolders
                    } else {
                        when (val r = readArchive(open, budget)) {
                            is ArchiveImport.Ready -> items += Staged(r, null)
                            is ArchiveImport.Failed -> failures += r.reason
                        }
                    }
                } catch (e: ArchiveError) {
                    failures += e.message ?: "Не удалось открыть файл"
                } catch (e: Throwable) {
                    failures += "Не удалось открыть файл"
                }
                if (items.size > MAX_BUNDLE_NOTEBOOKS) {
                    items.forEach { deleteQuietly(it.ready.staging) }
                    items.clear()
                    failures += "Слишком много тетрадей в файле"
                    break
                }
            }
            val set = ImportSet(items, folders, failures)
            main.post { onDone(set) }
        }
    }

    /**
     * Adds the staged notebooks to the library under folder [into] (null: top level): the
     * bundle's folders are created anew there. Notebooks that exist here are replaced or
     * added as copies, as [replace] says.
     */
    fun commitImportsAsync(set: ImportSet, replace: Boolean, into: String?, onDone: (List<Notebook>) -> Unit) {
        background {
            val created = HashMap<String, String>()
            runCatching {
                val all = readFolders().toMutableList()
                val target = into?.takeIf { id -> all.any { it.id == id } }
                val byId = set.folders.associateBy { it.id }
                // Parents first; a loop in a crafted file ends at the top of the import.
                val making = HashSet<String>()
                fun make(f: Folder, depth: Int): String {
                    created[f.id]?.let { return it }
                    making += f.id
                    val parent = f.parent?.let(byId::get)?.takeIf { depth < MAX_FOLDER_DEPTH && it.id !in making }?.let { make(it, depth + 1) } ?: target
                    val nf = Folder(newId(), f.name.take(MAX_TITLE), parent)
                    all += nf
                    created[f.id] = nf.id
                    making -= f.id
                    return nf.id
                }
                for (f in set.folders.take(MAX_BUNDLE_FOLDERS)) make(f, 0)
                writeFolders(all)
            }
            val out = ArrayList<Notebook>()
            val taken = HashSet<String>()
            for (item in set.items) {
                val ready = item.ready
                val chosen = into
                val existing = current(ready)
                val folder = SafeFiles.importFolder(item.folder?.let(created::get), chosen, existing?.folder)
                // The same notebook twice in one import: the second one is a copy.
                val again = !taken.add(ready.notebook.id)
                val nb = runCatching {
                    if (!again && (replace || existing == null)) {
                        SafeFiles.replaceDir(ready.staging, dir(ready.notebook.id))
                        ready.notebook.copy(folder = folder, favorite = existing?.favorite == true).also { writeMetaNow(it) }
                    } else {
                        val copy = ready.notebook.copy(id = newId(), title = "${ready.notebook.title} (копия)".take(MAX_TITLE), folder = folder)
                        if (!ready.staging.renameTo(dir(copy.id))) throw IOException("rename failed")
                        writeMetaNow(copy)
                        copy
                    }
                }.getOrElse {
                    deleteQuietly(ready.staging)
                    null
                }
                if (nb != null) out += nb
            }
            main.post { onDone(out) }
        }
    }

    fun discardImports(set: ImportSet) {
        background { for (it in set.items) deleteQuietly(it.ready.staging) }
    }

    /** A bundle starts with its manifest; a single notebook with its own. */
    private fun isBundle(open: () -> InputStream?): Boolean =
        (open() ?: throw IOException("cannot open file")).use { input ->
            ZipInputStream(input.buffered()).use { it.nextEntry?.name == BUNDLE_MANIFEST }
        }

    private fun readBundle(open: () -> InputStream?, budget: Budget): Pair<List<Staged>, List<Folder>> {
        val staged = HashMap<String, ArchiveImport.Ready>()
        var manifest: JSONObject? = null
        var total = 0L
        var entries = 0
        try {
            (open() ?: throw IOException("cannot open file")).use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (++entries > MAX_BUNDLE_NOTEBOOKS + 1) throw ArchiveError("Слишком много тетрадей в файле")
                        val name = entry.name
                        when {
                            name == BUNDLE_MANIFEST && manifest == null -> {
                                val bytes = readCapped(zip, MAX_BUNDLE_MANIFEST)
                                manifest = JSONObject(String(bytes))
                            }
                            BUNDLE_ENTRY.matches(name) && name !in staged -> {
                                val counted = CountingStream(KeepOpenInput(zip))
                                val r = readArchive({ counted }, budget)
                                // Whatever follows the notebook's own zip is read with the same caps.
                                total += counted.count + readCapped(zip, MAX_ARCHIVE_ENTRY_BYTES, keep = false).size.toLong()
                                if (total > MAX_BUNDLE_BYTES) {
                                    if (r is ArchiveImport.Ready) deleteQuietly(r.staging)
                                    throw ArchiveError("Файл слишком большой")
                                }
                                when (r) {
                                    is ArchiveImport.Ready -> staged[name] = r
                                    is ArchiveImport.Failed -> throw ArchiveError(r.reason)
                                }
                            }
                            else -> {
                                // Unknown entries are drained with a cap, never stored.
                                readCapped(zip, MAX_ARCHIVE_ENTRY_BYTES, keep = false)
                            }
                        }
                    }
                }
            }
            val m = manifest ?: throw ArchiveError("Это не файл тетрадей InkNotes")
            if (m.optString("format") != BUNDLE_FORMAT) throw ArchiveError("Это не файл тетрадей InkNotes")
            if (m.optInt("version", Int.MAX_VALUE) > BUNDLE_VERSION) throw ArchiveError("Файл создан более новой версией приложения")
            val folderJson = m.optJSONArray("folders") ?: JSONArray()
            val folders = (0 until minOf(folderJson.length(), MAX_BUNDLE_FOLDERS)).mapNotNull { i ->
                val o = folderJson.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { UUID_RE.matches(it) } ?: return@mapNotNull null
                Folder(id, o.optString("name").take(MAX_TITLE).ifBlank { "Папка" }, o.optString("parent").takeIf { UUID_RE.matches(it) })
            }.distinctBy { it.id }
            val ids = folders.map { it.id }.toSet()
            val list = m.optJSONArray("notebooks") ?: JSONArray()
            val out = ArrayList<Staged>()
            val used = HashSet<String>()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val file = o.optString("file")
                val ready = staged[file] ?: continue
                if (!used.add(file)) continue
                out += Staged(ready, o.optString("folder").takeIf { it in ids })
            }
            // Notebooks in the file but not in the list still come, at the top of the import.
            for ((file, ready) in staged) if (file !in used) out += Staged(ready, null)
            return out to folders.map { f -> if (f.parent in ids) f else f.copy(parent = null) }
        } catch (e: Throwable) {
            staged.values.forEach { deleteQuietly(it.staging) }
            throw e
        }
    }

    private fun readCapped(input: InputStream, cap: Long, keep: Boolean = true): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var n = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            n += r
            if (n > cap) throw ArchiveError("Файл слишком большой")
            if (keep) out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    /** Lets an archive inside a zip be read or written without closing the outer zip. */
    private class KeepOpen(out: OutputStream) : java.io.FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    private class KeepOpenInput(input: InputStream) : java.io.FilterInputStream(input) {
        override fun close() = Unit
    }

    private class CountingStream(input: InputStream) : java.io.FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    /**
     * Unpacks a `.inknote` file from an untrusted source into a staging folder. Only known
     * entry names are extracted (no path from the archive is ever used as is), sizes are
     * capped while copying, and the metadata and every page are parsed before success.
     */
    fun readArchiveAsync(open: () -> InputStream?, onDone: (ArchiveImport) -> Unit) {
        transferring {
            val result = readArchive(open)
            main.post { onDone(result) }
        }
    }

    private fun readArchive(open: () -> InputStream?, budget: Budget = Budget(MAX_ARCHIVE_BYTES, MAX_ARCHIVE_ENTRIES)): ArchiveImport {
        val staging = File(root, ".import-${newId()}")
        val result: ArchiveImport = try {
            staging.mkdirs()
            (open() ?: throw IOException("cannot open file")).use { unpack(it, staging, budget) }
            val manifest = File(staging, ARCHIVE_MANIFEST).takeIf { it.isFile }
                ?.let { f -> JSONObject(String(f.inputStream().use { readCapped(it, MAX_JSON_BYTES) })) } ?: throw ArchiveError("Это не файл тетради InkNotes")
            if (manifest.optString("format") != ARCHIVE_FORMAT) throw ArchiveError("Это не файл тетради InkNotes")
            if (manifest.optInt("version", Int.MAX_VALUE) > ARCHIVE_VERSION) {
                throw ArchiveError("Файл создан более новой версией приложения")
            }
            val nb = runCatching { readMeta(staging).copy(folder = null) }.getOrElse { throw ArchiveError("Файл тетради повреждён") }
            for (pageId in nb.pageIds) {
                val page = File(staging, "pages/$pageId.ink")
                if (page.isFile) {
                    runCatching { PageCodec.read(page) }.getOrElse {
                        throw ArchiveError(
                            if (it is NewerFormatException) "Файл создан более новой версией приложения — обновите InkNotes" else "Страница в файле повреждена",
                        )
                    }
                }
            }
            val existing = dir(nb.id).takeIf { it.isDirectory }?.let { runCatching { readMeta(it) }.getOrNull() }
            ArchiveImport.Ready(nb, staging, existing)
        } catch (e: ArchiveError) {
            deleteQuietly(staging)
            ArchiveImport.Failed(e.message ?: "Не удалось открыть файл")
        } catch (e: Throwable) {
            deleteQuietly(staging)
            ArchiveImport.Failed("Не удалось открыть файл")
        }
        return result
    }

    /** The notebook here that [ready] would replace, as it is now (null: none any more). */
    private fun current(ready: ArchiveImport.Ready): Notebook? =
        dir(ready.notebook.id).takeIf { it.isDirectory }?.let { runCatching { readMeta(it) }.getOrNull() } ?: ready.existing?.takeIf { dir(it.id).isDirectory }

    /** Moves a staged import into the library, replacing the local copy or as a new notebook. */
    fun commitImportAsync(ready: ArchiveImport.Ready, replace: Boolean, into: String? = null, onDone: (Notebook?) -> Unit) {
        background {
            // The same rule as for several files: a chosen folder, else where it already is.
            // As it is now, not when the file was read: starred or moved meanwhile, it stays so.
            val existing = current(ready)
            val folder = SafeFiles.importFolder(null, into?.takeIf { id -> readFoldersOrNull().orEmpty().any { it.id == id } }, existing?.folder)
            val nb = runCatching {
                if (replace || existing == null) {
                    SafeFiles.replaceDir(ready.staging, dir(ready.notebook.id))
                    // Folder and star belong to this device (not in shared files): replacing keeps them.
                    ready.notebook.copy(folder = folder, favorite = existing?.favorite == true).also { writeMetaNow(it) }
                } else {
                    val copy = ready.notebook.copy(
                        id = newId(),
                        title = "${ready.notebook.title} (копия)".take(MAX_TITLE),
                        folder = folder,
                    )
                    if (!ready.staging.renameTo(dir(copy.id))) throw IOException("rename failed")
                    writeMetaNow(copy)
                    copy
                }
            }.getOrElse {
                deleteQuietly(ready.staging)
                null
            }
            main.post { onDone(nb) }
        }
    }

    fun discardImport(ready: ArchiveImport.Ready) {
        background { deleteQuietly(ready.staging) }
    }

    private class ArchiveError(message: String) : Exception(message)

    /** OutputStream.nullOutputStream() needs API 33. */
    private object DiscardStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    /** Page sizes and proportions any real document has; extreme ones would make huge bitmaps. */
    private fun validPdfPageSize(w: Float, h: Float): Boolean =
        w in 1f..20_000f && h in 1f..20_000f && w / h in 0.05f..20f

    private fun unpack(input: InputStream, staging: File, budget: Budget) {
        val seen = HashSet<String>()
        var total = 0L
        var entries = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ARCHIVE_ENTRIES) throw ArchiveError("Файл слишком большой")
                budget.entry()
                val name = entry.name
                // Unknown entries are still read (with the same caps): ZipInputStream would
                // otherwise inflate them in full, unbounded, when moving to the next entry.
                val keep = !entry.isDirectory && isArchiveName(name)
                if (keep && !seen.add(name)) throw ArchiveError("Файл тетради повреждён")
                val target = if (keep) File(staging, name).also { it.parentFile?.mkdirs() } else null
                (target?.outputStream() ?: DiscardStream).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        written += n
                        total += n
                        if (written > MAX_ARCHIVE_ENTRY_BYTES || total > MAX_ARCHIVE_BYTES) throw ArchiveError("Файл слишком большой")
                        budget.spend(n)
                        out.write(buf, 0, n)
                    }
                }
            }
        }
    }

    /** Bytes and entries still allowed for one import; shared by every nested notebook it unpacks. */
    private class Budget(private var bytes: Long, private var entries: Int) {
        fun spend(n: Int) {
            bytes -= n
            if (bytes < 0) throw ArchiveError("Файл слишком большой")
        }

        fun entry() {
            if (--entries < 0) throw ArchiveError("Файл слишком большой")
        }
    }

    private fun isArchiveName(name: String) =
        name == ARCHIVE_MANIFEST || name == META || PHOTO_NAME.matches(name) || PDF_NAME.matches(name) || PageImage.NAME.matches(name) || REC_NAME.matches(name) ||
            (name.startsWith("pages/") && name.endsWith(".ink") && UUID_RE.matches(name.removePrefix("pages/").removeSuffix(".ink")))

    fun loadPhoto(nb: Notebook, name: String): Bitmap? = loadPhoto(nb, name, MAX_PHOTO_SIDE)

    fun loadPage(nb: Notebook, pageId: String): PageContent {
        val file = pageFile(nb.id, pageId)
        return onIo {
            if (!file.exists()) return@onIo PageContent.EMPTY
            var reading = true
            try {
                val bytes = file.readBytes()
                reading = false
                // Pages on this device are read with the wider limits: one written by a version
                // that did not keep to the strict ones still opens (see PageCodec.Limits).
                PageCodec.decode(bytes, PageCodec.Limits.OWN).also { unreadable -= file.path }
            } catch (t: Throwable) {
                Log.e(TAG, "page $pageId not loaded", t)
                when (PageCopies.failure(t, reading)) {
                    // Not a page: set aside, so the empty page that replaces it destroys nothing.
                    PageCopies.Failure.DAMAGED -> {
                        val aside = File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}")
                        if (file.renameTo(aside)) damaged[file.path] = aside.name
                    }
                    // Storage or memory trouble, or a newer app's page: the file is probably fine.
                    // It stays as it is and nothing is written over it (see savePageAsync).
                    PageCopies.Failure.LOCKED -> unreadable += file.path
                }
                PageContent.EMPTY
            }
        }
    }

    /**
     * Like [loadPage], but null when the page has a file that could not be read (it is shown
     * empty in the editor): for exports, which must not pass such a page off as blank.
     */
    fun readPage(nb: Notebook, pageId: String): PageContent? = onIo {
        val file = pageFile(nb.id, pageId)
        if (!file.exists()) return@onIo PageContent.EMPTY
        val content = loadPage(nb, pageId)
        if (file.path in unreadable || !file.exists()) null else content
    }

    /** Size and time of a page's file: changes whenever the page is saved ("-" when there is none). */
    fun pageStamp(nb: Notebook, pageId: String): String =
        pageFile(nb.id, pageId).let { if (it.isFile) "${it.length()}:${it.lastModified()}" else "-" }

    /**
     * Page [pageId] was found damaged when it was opened and set aside: the name of the copy
     * kept, once (null: nothing to say). The editor says so instead of showing an empty page
     * without a word.
     */
    fun takeDamaged(nb: Notebook, pageId: String): String? = damaged.remove(pageFile(nb.id, pageId).path)

    /** Page [pageId] could not be read and is shown empty: it must not be changed or saved. */
    fun isLocked(nb: Notebook, pageId: String) = pageFile(nb.id, pageId).path in unreadable

    /**
     * [content] must be immutable: it is encoded on the IO thread. [onTooComplex] is told on the
     * main thread when the page holds more than a shared page file may: what is too much, in
     * words for the user, and whether it was saved here all the same (saved: over the shared
     * limits only; not saved: over even this device's, nothing written, the old copy stays).
     */
    fun savePageAsync(nb: Notebook, pageId: String, content: PageContent, onTooComplex: ((problem: String, saved: Boolean) -> Unit)? = null) {
        savedThisRun += nb.id
        val file = pageFile(nb.id, pageId)
        background {
            // A page shown empty because it could not be read is never written over.
            if (file.path in unreadable && file.exists()) {
                Log.w(TAG, "page $pageId is locked, not saved")
                return@background
            }
            // A page over what may be shared is still saved here (this device reads it with its
            // own, wider limits): nothing written is lost. It only can't go to another device as
            // it is, which the editor says.
            val overShared = PageCodec.tooComplex(content)
            try {
                atomicWrite(file) { out -> PageCodec.write(out, content, PageCodec.Limits.OWN) }
                if (overShared != null && onTooComplex != null) main.post { onTooComplex(overShared, true) }
            } catch (e: PageTooComplexException) {
                // Nothing was written: the copy on disk is still the last one that fits.
                Log.w(TAG, "page $pageId not saved: ${e.message}")
                val problem = e.message.orEmpty()
                if (onTooComplex != null) main.post { onTooComplex(problem, false) }
                return@background
            }
            unreadable -= file.path
            pruneCopies(file)
        }
    }

    /**
     * Copies of a damaged page kept beside it: once the page is saved anew and reads back,
     * only the newest [PageCopies.KEEP] stay. Until then all stay: one may be all there is.
     */
    private fun pruneCopies(file: File) {
        val names = file.parentFile?.list().orEmpty().filter { PageCopies.pageOf(it) == file.name }
        if (names.size <= PageCopies.KEEP) return
        val ok = runCatching { PageCodec.read(file, PageCodec.Limits.OWN) }.isSuccess
        for (n in PageCopies.toDelete(names, file.name, ok)) File(file.parentFile, n).delete()
    }

    // ------------------------------------------------- flash cards

    /** Review states of a notebook's cards ([Cards.encode]); empty when none were reviewed. */
    fun loadCards(nbId: String): Map<String, Cards.State> = onIo {
        if (!UUID_RE.matches(nbId)) return@onIo emptyMap()
        val f = File(dir(nbId), CARDS)
        if (!f.isFile || f.length() > MAX_JSON_BYTES) return@onIo emptyMap()
        runCatching { Cards.decode(f.readText()) }.getOrDefault(emptyMap())
    }

    /** Kept on this device only, like the text index: a friend's notebook starts unreviewed. */
    fun saveCardsAsync(nbId: String, states: Map<String, Cards.State>) {
        if (!UUID_RE.matches(nbId)) return
        val text = Cards.encode(states)
        background {
            val d = dir(nbId)
            if (File(d, META).isFile) runCatching { atomicWrite(File(d, CARDS)) { it.write(text.toByteArray()) } }
        }
    }

    // ------------------------------------------------- handwriting text index

    /** Recognized text of a notebook (raw JSON, see HandwritingIndex), or null if none yet. */
    fun loadTextIndex(nbId: String): String? = onIo {
        if (!UUID_RE.matches(nbId)) return@onIo null
        File(dir(nbId), TEXT_INDEX).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
    }

    /** Stored only while the notebook exists; never shared in `.inknote` files. */
    fun saveTextIndexAsync(nbId: String, json: String) {
        if (!UUID_RE.matches(nbId)) return
        background {
            val d = dir(nbId)
            if (File(d, META).isFile) runCatching { atomicWrite(File(d, TEXT_INDEX)) { it.write(json.toByteArray()) } }
        }
    }

    /**
     * Runs [block] on the IO thread. An uncaught throwable there would kill the whole app
     * (and with it every queued save), so failures are logged instead.
     */
    private fun background(block: () -> Unit) {
        io.execute {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "background notebook task failed", t)
            }
        }
    }

    /** Recursive delete that tolerates the tree changing underneath (kotlin.io asserts on that). */
    /** Like [background], on the [transfer] thread. */
    private fun transferring(block: () -> Unit) {
        transfer.execute {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "transfer failed", t)
            }
        }
    }

    private fun deleteQuietly(file: File): Boolean = runCatching { file.deleteRecursively() }.getOrDefault(false)

    /** On the IO thread, waited for; run in place when already there (no deadlock). */
    private fun <T> onIo(block: () -> T): T = if (Thread.currentThread() === ioThread) block() else io.submit<T>(block).get()

    /**
     * Work of the repository off the main thread: on the IO thread itself, as a coroutine
     * (calls in [block] that would wait for it run in place).
     */
    suspend fun <T> io(block: NotebookRepository.() -> T): T = kotlinx.coroutines.withContext(ioDispatcher) { block() }

    /** The library as last read: null until read once. */
    class Library(val notebooks: List<Notebook>, val folders: List<Folder>)

    private val libraryState = kotlinx.coroutines.flow.MutableStateFlow<Library?>(null)
    val library: kotlinx.coroutines.flow.StateFlow<Library?> = libraryState

    /** Reads the library anew, off the main thread; [library] gets it. */
    suspend fun refreshLibrary() {
        libraryState.value = io { Library(list(), folders()) }
    }

    private fun dir(id: String) = File(root, id)

    private fun pageFile(nbId: String, pageId: String) =
        File(File(dir(nbId), "pages").apply { mkdirs() }, "$pageId.ink")

    private fun readMeta(dir: File): Notebook {
        val metaFile = File(dir, META)
        // A crafted notebook could carry a huge "JSON" file: refuse it before reading it in.
        if (metaFile.length() > MAX_JSON_BYTES) throw IOException("notebook.json too large")
        val json = JSONObject(metaFile.readText())
        val pages = json.getJSONArray("pages")
        // Ids become directory and file names: accept only the UUIDs this class generates.
        val id = json.getString("id")
        require(UUID_RE.matches(id)) { "bad notebook id" }
        val pageIds = List(pages.length()) { pages.getString(it) }
        require(pageIds.isNotEmpty() && pageIds.all(UUID_RE::matches) && pageIds.toSet().size == pageIds.size) { "bad page ids" }
        // Notebooks from v0.2 have a single "template" instead of per-theme backgrounds.
        val legacy = json.optString("template").takeIf { it.isNotEmpty() }
            ?.let { PageBackground(runCatching { PaperKind.valueOf(it) }.getOrDefault(PaperKind.BLANK)) }
        return Notebook(
            id = id,
            title = json.getString("title").take(MAX_TITLE),
            light = json.optJSONObject("light")?.let(::readBackground) ?: legacy ?: PageBackground(PaperKind.BLANK),
            dark = json.optJSONObject("dark")?.let(::readBackground) ?: legacy ?: PageBackground(PaperKind.BLANK),
            createdAt = json.getLong("createdAt"),
            // Writing only bumps a small file of its own (see touch); the newer time counts.
            updatedAt = maxOf(json.getLong("updatedAt"), readTouched(dir)),
            pageIds = pageIds,
            pdfPages = json.optJSONObject("pdf")?.let(::readPdfPages).orEmpty().filterKeys { it in pageIds },
            cover = (json.opt("cover") as? Number)?.let { it.toLong().toInt() or 0xFF000000.toInt() },
            folder = json.optString("folder").takeIf { UUID_RE.matches(it) },
            pageSizes = json.optJSONObject("sizes")?.let(::readPageSizes).orEmpty().filterKeys { it in pageIds },
            favorite = json.optBoolean("favorite"),
            coverArt = json.optInt("coverArt", -1).takeIf { it in 0 until COVER_ARTS },
            plasticKind = json.optInt("plasticKind", -1).takeIf { it in 0 until PLASTIC_KINDS },
            toc = json.optJSONArray("toc")?.let { readToc(it, pageIds.toSet()) }.orEmpty(),
            links = json.optJSONArray("links")?.let { readLinks(it, pageIds.toSet()) }.orEmpty(),
            layers = json.optJSONArray("layers")?.let { a ->
                (0 until minOf(a.length(), Layers.MAX)).mapNotNull { i ->
                    a.optJSONObject(i)?.let { o -> LayerInfo(o.optString("name").take(MAX_TITLE), o.optBoolean("visible", true), o.optBoolean("locked")) }
                }
            }.orEmpty(),
            activeLayer = json.optInt("activeLayer", 0).coerceIn(0, Layers.MAX - 1),
            recordings = json.optJSONArray("recordings")?.let { a ->
                (0 until minOf(a.length(), MAX_RECORDINGS)).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    val file = o.optString("file").takeIf { REC_NAME.matches(it) } ?: return@mapNotNull null
                    Recording(file, o.optLong("at"), o.optLong("ms").coerceAtLeast(0))
                }
            }.orEmpty(),
            audioMarks = json.optJSONArray("marks")?.let { a -> readMarks(a, pageIds.toSet()) }.orEmpty(),
        ).let { it.copy(activeLayer = it.activeLayer.coerceAtMost(maxOf(0, it.layers.lastIndex))) }
    }

    private fun readMarks(a: JSONArray, pages: Set<String>): List<AudioMark> = (0 until minOf(a.length(), MAX_MARKS)).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val page = o.optString("page").takeIf { it in pages } ?: return@mapNotNull null
        val file = o.optString("file").takeIf { REC_NAME.matches(it) } ?: return@mapNotNull null
        val r = listOf("l", "t", "r", "b").map { o.optDouble(it, 0.0).toFloat() }
        if (r.any { !it.isFinite() || it < -MAX_Y || it > MAX_Y }) return@mapNotNull null
        AudioMark(page, r[0], r[1], r[2], r[3], file, o.optLong("ms").coerceAtLeast(0))
    }

    private fun readToc(a: JSONArray, pages: Set<String>): List<Bookmark> = (0 until minOf(a.length(), MAX_TOC)).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val page = o.optString("page").takeIf { it in pages } ?: return@mapNotNull null
        val y = o.optDouble("y", 0.0).toFloat().takeIf { it.isFinite() } ?: 0f
        Bookmark(page, y.coerceIn(0f, MAX_Y), o.optString("title").take(MAX_TITLE))
    }

    private fun readLinks(a: JSONArray, pages: Set<String>): List<PageLink> = (0 until minOf(a.length(), MAX_LINKS)).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val page = o.optString("page").takeIf { it in pages } ?: return@mapNotNull null
        val target = o.optString("target").takeIf { it in pages } ?: return@mapNotNull null
        val r = listOf("l", "t", "r", "b", "ty").map { o.optDouble(it, 0.0).toFloat() }
        if (r.any { !it.isFinite() || it < -MAX_Y || it > MAX_Y } || r[2] <= r[0] || r[3] <= r[1]) return@mapNotNull null
        PageLink(page, r[0], r[1], r[2], r[3], target, r[4].coerceAtLeast(0f))
    }

    private fun readPageSizes(json: JSONObject): Map<String, PageSize> =
        json.keys().asSequence().mapNotNull { pageId ->
            val o = json.optJSONObject(pageId) ?: return@mapNotNull null
            val format = runCatching { PaperFormat.valueOf(o.getString("format")) }.getOrNull() ?: return@mapNotNull null
            val cw = o.optDouble("cw", 0.0).toFloat().takeIf { it.isFinite() && it > 0f }?.coerceIn(10f, CanvasGrowth.MAX) ?: 0f
            val ch = o.optDouble("ch", 0.0).toFloat().takeIf { it.isFinite() && it > 0f }?.coerceIn(10f, CanvasGrowth.MAX) ?: 0f
            pageId to PageSize(format, o.optBoolean("landscape"), cw, ch)
        }.toMap()

    private fun readPdfPages(json: JSONObject): Map<String, PdfPageRef> =
        json.keys().asSequence().mapNotNull { pageId ->
            val o = json.getJSONObject(pageId)
            // The name ends up in a file path: accept only names this class generates.
            val file = o.getString("file").takeIf { PDF_NAME.matches(it) } ?: return@mapNotNull null
            val w = o.getDouble("w").toFloat()
            val h = o.getDouble("h").toFloat()
            if (!validPdfPageSize(w, h) || o.getInt("index") < 0) return@mapNotNull null
            val m = o.optJSONArray("m")?.takeIf { it.length() == 4 }?.let {
                PdfGeometry.Margins(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat()).sane()
            } ?: PdfGeometry.Margins.NONE
            pageId to PdfPageRef(file, o.getInt("index"), w, h, PdfGeometry.normalRotation(o.optInt("rot")), m)
        }.toMap()

    private fun readBackground(json: JSONObject): PageBackground {
        val kind = runCatching { PaperKind.valueOf(json.getString("kind")) }.getOrDefault(PaperKind.BLANK)
        // The photo name ends up in a file path: accept only names this class generates.
        val photo = json.optString("photo").takeIf { PHOTO_NAME.matches(it) }
        val color = (json.opt("color") as? Number)?.let { it.toLong().toInt() or 0xFF000000.toInt() }
        val lines = json.optDouble("lines", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        return if (kind == PaperKind.PHOTO && photo == null) PageBackground(PaperKind.BLANK, color = color, lines = lines) else PageBackground(kind, photo, color, lines)
    }

    private fun writeMeta(nb: Notebook) {
        val (file, bytes) = encodeMeta(nb)
        background { atomicWrite(file) { it.write(bytes) } }
    }

    /** For code already running on the IO thread. */
    private fun writeMetaNow(nb: Notebook) {
        val (file, bytes) = encodeMeta(nb)
        atomicWrite(file) { it.write(bytes) }
    }

    private fun encodeMeta(nb: Notebook, into: File? = null): Pair<File, ByteArray> {
        fun bg(b: PageBackground) = JSONObject().put("kind", b.kind.name).apply {
            b.photo?.let { put("photo", it) }
            b.color?.let { put("color", it.toLong() and 0xFFFFFFFFL) }
            if (b.lines < 1f) put("lines", b.lines.toDouble())
        }
        val json = JSONObject()
            .put("id", nb.id)
            .put("title", nb.title)
            .put("light", bg(nb.light))
            .put("dark", bg(nb.dark))
            .put("createdAt", nb.createdAt)
            .put("updatedAt", nb.updatedAt)
            .put("pages", JSONArray(nb.pageIds))
        nb.cover?.let { json.put("cover", it.toLong() and 0xFFFFFFFFL) }
        nb.folder?.let { json.put("folder", it) }
        if (nb.favorite) json.put("favorite", true)
        nb.coverArt?.let { json.put("coverArt", it) }
        nb.plasticKind?.let { json.put("plasticKind", it) }
        if (nb.recordings.isNotEmpty()) {
            json.put("recordings", JSONArray(nb.recordings.map { JSONObject().put("file", it.file).put("at", it.startedAt).put("ms", it.durationMs) }))
            val marks = nb.audioMarks.filter { it.pageId in nb.pageIds }
            json.put(
                "marks",
                JSONArray(
                    marks.map {
                        JSONObject().put("page", it.pageId).put("l", it.left.toDouble()).put("t", it.top.toDouble())
                            .put("r", it.right.toDouble()).put("b", it.bottom.toDouble()).put("file", it.file).put("ms", it.atMs)
                    },
                ),
            )
        }
        if (nb.layers.isNotEmpty()) {
            json.put("layers", JSONArray(nb.layers.map { JSONObject().put("name", it.name).put("visible", it.visible).put("locked", it.locked) }))
            json.put("activeLayer", nb.activeLayer)
        }
        val toc = nb.toc.filter { it.pageId in nb.pageIds }
        if (toc.isNotEmpty()) {
            json.put("toc", JSONArray(toc.map { JSONObject().put("page", it.pageId).put("y", it.y.toDouble()).put("title", it.title) }))
        }
        val links = nb.links.filter { it.pageId in nb.pageIds && it.target in nb.pageIds }
        if (links.isNotEmpty()) {
            json.put(
                "links",
                JSONArray(
                    links.map {
                        JSONObject().put("page", it.pageId).put("l", it.left.toDouble()).put("t", it.top.toDouble())
                            .put("r", it.right.toDouble()).put("b", it.bottom.toDouble()).put("target", it.target).put("ty", it.targetY.toDouble())
                    },
                ),
            )
        }
        val sizes = nb.pageSizes.filter { (id, size) -> id in nb.pageIds && size != PageSize.DEFAULT }
        if (sizes.isNotEmpty()) {
            val o = JSONObject()
            for ((pageId, size) in sizes) {
                val j = JSONObject().put("format", size.format.name).put("landscape", size.landscape)
                if (size.isCanvas) j.put("cw", size.canvasW.toDouble()).put("ch", size.canvasH.toDouble())
                o.put(pageId, j)
            }
            json.put("sizes", o)
        }
        if (nb.pdfPages.isNotEmpty()) {
            val pdf = JSONObject()
            for ((pageId, ref) in nb.pdfPages) {
                val o = JSONObject().put("file", ref.file).put("index", ref.index).put("w", ref.width.toDouble()).put("h", ref.height.toDouble())
                if (ref.rotation != 0) o.put("rot", ref.rotation)
                val m = ref.margins
                if (!m.isNone) o.put("m", org.json.JSONArray().put(m.left.toDouble()).put(m.top.toDouble()).put(m.right.toDouble()).put(m.bottom.toDouble()))
                pdf.put(pageId, o)
            }
            json.put("pdf", pdf)
        }
        val file = File(into ?: dir(nb.id).apply { mkdirs() }, META)
        return file to json.toString().toByteArray()
    }

    private fun atomicWrite(target: File, body: (FileOutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                body(out)
                out.flush()
                out.fd.sync()
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        if (!tmp.renameTo(target)) throw IOException("rename failed: $tmp -> $target")
    }

    private companion object {
        const val TRASH_DAYS = 30
        private const val OLD_COPY = " (старая копия)"
        const val TAG = "NotebookRepository"
        const val META = "notebook.json"
        private const val TOUCHED = "touched"

        /** Marks a notebook for one note over the lock screen ([createLockNote]). */
        private const val LOCK_NOTE = "lockscreen"

        /** Beside a recording under way ([saveRecordingJournal]). */
        private const val LIVE = ".live"

        /** Less than this is a header with no sound in it. */
        private const val MIN_RECORDING_BYTES = 2048L

        /** Marks a notebook rebuilt from its pages ([recoverMeta]): its files are never swept. */
        private const val RECOVERED = "recovered"

        /** A recovered notebook that syncs as any other now; its files are still never swept. */
        private const val RECOVERED_KEPT = "recovered-kept"
        const val FOLDERS = ".folders.json"
        const val TRASH = ".trash"
        const val DELETED_AT = "deleted-at"
        const val TEXT_INDEX = "text.json"
        const val CARDS = "cards.tsv"
        const val MAX_TOC = 2000
        const val MAX_LINKS = 5000

        /** Places within a few page heights: anything farther is not from this app. */
        const val MAX_Y = 100_000f
        const val MAX_PHOTO_SIDE = 2048

        /** notebook.json and archive manifests are a few kilobytes; anything far bigger is not ours. */
        private const val MAX_JSON_BYTES = 4L * 1024 * 1024
        const val MAX_IMAGE_SIDE = 2048
        const val MAX_IMAGE_HEADER_SIDE = 20_000
        val PHOTO_NAME = Regex("bg-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
        val PDF_NAME = Regex("doc-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf")
        val REC_NAME = Regex("rec-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.m4a")
        const val MAX_RECORDINGS = 500
        const val MAX_MARKS = 50_000
        val UUID_RE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        const val MAX_TITLE = 200
        const val ARCHIVE_MANIFEST = "inknote.json"
        const val ARCHIVE_FORMAT = "inknotes-notebook"
        const val ARCHIVE_VERSION = 1
        const val BUNDLE_MANIFEST = BackupFiles.MANIFEST
        const val BUNDLE_FORMAT = BackupFiles.FORMAT
        const val BUNDLE_VERSION = 1
        val BUNDLE_ENTRY = Regex("notebooks/[0-9]{1,5}\\.inknote")
        const val MAX_BUNDLE_NOTEBOOKS = 1000
        const val MAX_BUNDLE_FOLDERS = 2000
        const val MAX_FOLDER_DEPTH = 64
        const val MAX_BUNDLE_MANIFEST = 4L * 1024 * 1024
        const val MAX_BUNDLE_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_ARCHIVE_ENTRIES = 20_000
        const val MAX_BUNDLE_ENTRIES = 200_000
        const val MAX_ARCHIVE_ENTRY_BYTES = 150L * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 500L * 1024 * 1024
        fun newId() = UUID.randomUUID().toString()
    }
}
