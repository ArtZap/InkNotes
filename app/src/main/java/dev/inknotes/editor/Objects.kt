package dev.inknotes.editor

import android.graphics.RectF
import androidx.ink.strokes.StrokeInput
import dev.inknotes.data.InkKind
import dev.inknotes.data.PageFill
import dev.inknotes.data.PageStroke
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.max

/**
 * What a tap selects: the element under the finger and everything touching it, one after
 * another — a stamp, a table, a drawing, a word. Too large a cluster (lines of text running
 * into each other) gives just the element itself.
 */
object Objects {

    fun joined(doc: PageDocument, seed: Any, allowed: (Any) -> Boolean = { true }): List<Any> {
        if (seed !is PageStroke && seed !is PageFill) return listOf(seed)
        val items: List<Any> = (doc.strokes.filter { it.kind != InkKind.HIGHLIGHTER } + doc.fills).filter(allowed)
        val samples = IdentityHashMap<Any, FloatArray>()
        fun pts(x: Any): FloatArray = samples.getOrPut(x) { if (x is PageStroke) sample(x) else (x as PageFill).outline }
        fun box(x: Any): RectF? = if (x is PageStroke) PageCache.strokeBounds(x) else (x as PageFill).bounds
        fun width(x: Any) = if (x is PageStroke) x.stroke.brush.size else 0f

        val found: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        found += seed
        val queue = ArrayDeque<Any>().apply { add(seed) }
        while (queue.isNotEmpty()) {
            val a = queue.removeFirst()
            val ab = box(a) ?: continue
            val reach = GAP + width(a) / 2
            val grown = RectF(ab).apply { inset(-reach - MAX_HALF_WIDTH, -reach - MAX_HALF_WIDTH) }
            for (b in items) {
                if (b in found) continue
                val bb = box(b) ?: continue
                if (!RectF.intersects(grown, bb)) continue
                if (!touch(pts(a), a is PageFill, pts(b), b is PageFill, reach + width(b) / 2)) continue
                found += b
                queue += b
                if (found.size > MAX_GROUP) return listOf(seed)
            }
        }
        // In page order, as the elements were drawn.
        return items.filter { it in found } + listOf(seed).filter { it !in items }
    }

    /** Up to [MAX_SAMPLES] points along the stroke. */
    private fun sample(s: PageStroke): FloatArray {
        val inputs = s.stroke.inputs
        val n = inputs.size
        val stride = max(1, n / MAX_SAMPLES)
        val out = ArrayList<Float>()
        val p = StrokeInput()
        var i = 0
        while (i < n) {
            inputs.populate(i, p)
            out += p.x
            out += p.y
            i += stride
        }
        if (n > 0 && (n - 1) % stride != 0) {
            inputs.populate(n - 1, p)
            out += p.x
            out += p.y
        }
        return out.toFloatArray()
    }

    /** Some point of either polyline within [reach] of the other, or inside it when it is a fill outline. */
    private fun touch(a: FloatArray, aClosed: Boolean, b: FloatArray, bClosed: Boolean, reach: Float): Boolean {
        fun near(p: FloatArray, line: FloatArray, closed: Boolean): Boolean {
            for (i in p.indices step 2) {
                if (line.size == 2) {
                    if (kotlin.math.hypot(p[i] - line[0], p[i + 1] - line[1]) <= reach) return true
                    continue
                }
                var j = 0
                while (j + 3 < line.size) {
                    if (PageEdits.distanceToSegment(p[i], p[i + 1], line[j], line[j + 1], line[j + 2], line[j + 3]) <= reach) return true
                    j += 2
                }
                if (closed && line.size >= 6 && PageEdits.inside(p[i], p[i + 1], line)) return true
                if (closed && line.size >= 6 &&
                    PageEdits.distanceToSegment(p[i], p[i + 1], line[line.size - 2], line[line.size - 1], line[0], line[1]) <= reach
                ) return true
            }
            return false
        }
        return near(a, b, bClosed) || near(b, a, aClosed)
    }

    private const val GAP = 1.5f
    private const val MAX_HALF_WIDTH = 30f
    private const val MAX_GROUP = 200
    private const val MAX_SAMPLES = 64
}
