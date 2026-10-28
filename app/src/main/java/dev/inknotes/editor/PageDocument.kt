package dev.inknotes.editor

import dev.inknotes.data.PageContent
import dev.inknotes.data.PageFill
import dev.inknotes.data.PageImage
import dev.inknotes.data.PageStroke

/**
 * Elements of one page plus undo/redo. Lists are immutable and replaced on every change,
 * so an undo step is just the lists before and after (cheap: references only).
 * Main thread only.
 */
class PageDocument(val pageId: String, initial: PageContent) {

    var strokes: List<PageStroke> = initial.strokes
        private set
    var fills: List<PageFill> = initial.fills
        private set
    var images: List<PageImage> = initial.images
        private set

    /** Bumped on every change; the editor compares it with the last saved value. */
    var revision = 0
        private set

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /**
     * Called after each new undo step. The notebook keeps one history across pages and
     * uses this to record which page to undo next.
     */
    var onEdit: ((PageDocument) -> Unit)? = null

    private class Edit(val before: PageContent, val after: PageContent)

    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private var sessionStart: PageContent? = null

    fun content() = PageContent(strokes, fills, images)

    /**
     * A session groups many [set] calls (e.g. one eraser gesture) into one undo step.
     * [edit] is a one-shot session.
     */
    fun beginSession() {
        if (sessionStart == null) sessionStart = content()
    }

    fun set(strokes: List<PageStroke> = this.strokes, fills: List<PageFill> = this.fills, images: List<PageImage> = this.images) {
        if (strokes === this.strokes && fills === this.fills && images === this.images) return
        this.strokes = strokes
        this.fills = fills
        this.images = images
        revision++
    }

    fun commitSession() {
        val start = sessionStart ?: return
        sessionStart = null
        if (start.strokes === strokes && start.fills === fills && start.images === images) return
        undoStack.addLast(Edit(start, content()))
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        onEdit?.invoke(this)
    }

    /** A new edit anywhere in the notebook invalidates redo on every page. */
    fun clearRedo() = redoStack.clear()

    fun edit(strokes: List<PageStroke> = this.strokes, fills: List<PageFill> = this.fills, images: List<PageImage> = this.images) {
        beginSession()
        set(strokes, fills, images)
        commitSession()
    }

    fun add(stroke: PageStroke) = edit(strokes = strokes + stroke)

    fun undo(): Boolean {
        commitSession()
        val edit = undoStack.removeLastOrNull() ?: return false
        restore(edit.before)
        redoStack.addLast(edit)
        return true
    }

    fun redo(): Boolean {
        commitSession()
        val edit = redoStack.removeLastOrNull() ?: return false
        restore(edit.after)
        undoStack.addLast(edit)
        return true
    }

    private fun restore(c: PageContent) {
        strokes = c.strokes
        fills = c.fills
        images = c.images
        revision++
    }

    private companion object {
        const val MAX_UNDO = 150
    }
}
