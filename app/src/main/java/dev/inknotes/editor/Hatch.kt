package dev.inknotes.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.inknotes.data.FillPattern
import dev.inknotes.data.HatchStyle
import dev.inknotes.data.LineType
import kotlin.math.floor
import kotlin.math.max

/**
 * Fill patterns. Each one is drawn axis-aligned in "pattern space", which is the page
 * rotated by [HatchStyle.angle] around the anchor point, so any pattern can be tilted and
 * stays attached to its shape when the shape moves.
 */
object Hatch {
    /** Upper bound of drawn primitives per fill, so a tiny spacing on a big area stays fast. */
    const val MAX_CELLS = 120_000f

    /** Budget across all fills of a page, so many big dense fills can't stall a frame. */
    private const val MAX_PAGE_CELLS = 400_000f

    /** Per-fill primitive budget when a page has [fillCount] fills; the page total never exceeds [MAX_PAGE_CELLS]. */
    fun budgetFor(fillCount: Int): Float = (MAX_PAGE_CELLS / fillCount.coerceAtLeast(1)).coerceAtMost(MAX_CELLS)
    private const val MIN_STEP = 2.5f

    private val rotation = Matrix()
    private val area = RectF()
    private val plain = Path()
    private val effect = Path()

    /**
     * Draws [pattern] over [bounds] (the caller clips to the shape). [alpha] 0..255 is
     * multiplied with the colour's alpha and the style's opacity. Scratch objects are shared
     * between the UI, thumbnail and export threads, hence the lock.
     */
    fun draw(
        canvas: Canvas,
        bounds: RectF,
        ox: Float,
        oy: Float,
        pattern: FillPattern,
        style: HatchStyle,
        color: Int,
        alpha: Int,
        paint: Paint,
        maxCells: Float = MAX_CELLS,
    ) = synchronized(this) { drawLocked(canvas, bounds, ox, oy, pattern, style.sane(), color, alpha, paint, maxCells) }

    private val clip = android.graphics.Rect()

    private fun drawLocked(canvas: Canvas, bounds: RectF, ox: Float, oy: Float, pattern: FillPattern, s: HatchStyle, color: Int, alpha: Int, paint: Paint, maxCells: Float) {
        // Only the part that can show: the fill's bounds within the canvas clip (screen, page).
        if (!canvas.getClipBounds(clip)) return
        val b = RectF(bounds)
        if (!b.intersect(clip.left.toFloat(), clip.top.toFloat(), clip.right.toFloat(), clip.bottom.toFloat())) return
        paint.reset()
        paint.isAntiAlias = true
        paint.color = color
        var a = alpha * Color.alpha(color) / 255f * s.opacity
        if (pattern == FillPattern.SOLID) a *= 90f / 255f
        if (pattern == FillPattern.CHECKER) a *= 0.55f
        paint.alpha = a.toInt().coerceIn(0, 255)
        val w = s.width * s.scale
        paint.strokeWidth = w
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE

        if (pattern == FillPattern.SOLID || pattern == FillPattern.INK) {
            paint.style = Paint.Style.FILL
            canvas.drawRect(b, paint)
            return
        }

        canvas.save()
        canvas.rotate(s.angle, ox, oy)
        // The fill's bounds seen from pattern space.
        rotation.setRotate(-s.angle, ox, oy)
        rotation.mapRect(area, b)
        val r = area
        var step = max(s.spacing * s.scale, MIN_STEP)
        // Grid and bricks draw two families of lines per cell.
        val budget = if (pattern == FillPattern.CROSS || pattern == FillPattern.BRICKS) maxCells / 2 else maxCells
        while ((r.width() / step + 1) * (r.height() / step + 1) > budget) step *= 1.5f

        plain.rewind()
        effect.rewind()
        val lineType = s.lineType
        // Dash lengths follow the spacing so the look keeps its proportions when scaled.
        val unit = max(step, w * 2)
        val period = when (lineType) {
            LineType.DASHED, LineType.SOLID_DASHED -> unit * 1.5f
            LineType.DASH_DOT, LineType.SOLID_DASH_DOT -> unit * 2.1f
            else -> unit
        }

        /** Path for row [k] (rows counted from the anchor), or null if the row is skipped. */
        fun row(k: Int): Path? = when (lineType) {
            LineType.SOLID -> plain
            LineType.DASHED, LineType.DASH_DOT -> effect
            LineType.SOLID_DASHED, LineType.SOLID_DASH_DOT -> if (Math.floorMod(k, 2) == 0) plain else effect
            LineType.TWO_AND_GAP -> if (Math.floorMod(k, 3) == 2) null else plain
        }
        fun rowIndex(y: Float, anchor: Float) = Math.round((y - anchor) / step)

        /** Straight rows y = anchor + k·step across [r]; dashes start on a grid fixed to the shape. */
        fun horizontal(path: (Int) -> Path?) {
            val x0 = snap(r.left, ox, period)
            var y = snap(r.top, oy, step)
            while (y <= r.bottom) {
                path(rowIndex(y, oy))?.let {
                    it.moveTo(x0, y)
                    it.lineTo(r.right, y)
                }
                y += step
            }
        }

        when (pattern) {
            FillPattern.SOLID, FillPattern.INK -> Unit
            FillPattern.LINES -> horizontal(::row)
            FillPattern.CROSS -> {
                horizontal(::row)
                val y0 = snap(r.top, oy, period)
                var x = snap(r.left, ox, step)
                while (x <= r.right) {
                    row(rowIndex(x, ox))?.let {
                        it.moveTo(x, y0)
                        it.lineTo(x, r.bottom)
                    }
                    x += step
                }
            }
            FillPattern.DOTS -> {
                paint.strokeWidth = max(w * 1.9f, 0.8f)
                val pts = ArrayList<Float>()
                var y = snap(r.top, oy, step)
                while (y <= r.bottom) {
                    var x = snap(r.left, ox, step)
                    while (x <= r.right) {
                        pts += x; pts += y
                        x += step
                    }
                    y += step
                }
                canvas.drawPoints(pts.toFloatArray(), paint)
            }
            FillPattern.WAVES -> {
                // Half a wave per step, amplitude a third of the row spacing.
                val amp = step * 0.3f
                var y = snap(r.top - amp, oy, step)
                while (y <= r.bottom + amp) {
                    val path = row(rowIndex(y, oy))
                    if (path != null) {
                        var x = snap(r.left, ox, step * 2)
                        path.moveTo(x, y)
                        var up = true
                        while (x <= r.right) {
                            path.quadTo(x + step / 2, if (up) y - 2 * amp else y + 2 * amp, x + step, y)
                            x += step
                            up = !up
                        }
                    }
                    y += step
                }
            }
            FillPattern.ZIGZAG -> {
                val amp = step * 0.3f
                var y = snap(r.top - amp, oy, step)
                while (y <= r.bottom + amp) {
                    val path = row(rowIndex(y, oy))
                    if (path != null) {
                        var x = snap(r.left, ox, step)
                        path.moveTo(x, y + amp)
                        while (x <= r.right) {
                            path.lineTo(x + step / 2, y - amp)
                            path.lineTo(x + step, y + amp)
                            x += step
                        }
                    }
                    y += step
                }
            }
            FillPattern.BRICKS -> {
                // Rows one step high, bricks two steps long, joints offset on every other row.
                horizontal(::row)
                var y = snap(r.top, oy, step)
                while (y <= r.bottom) {
                    val k = rowIndex(y, oy)
                    val path = if (lineType == LineType.TWO_AND_GAP && Math.floorMod(k, 3) == 2) null else plain
                    if (path != null) {
                        var x = snap(r.left, ox + Math.floorMod(k, 2) * step, step * 2)
                        while (x <= r.right) {
                            path.moveTo(x, y)
                            path.lineTo(x, y + step)
                            x += step * 2
                        }
                    }
                    y += step
                }
            }
            FillPattern.CHECKER -> {
                paint.style = Paint.Style.FILL
                var y = snap(r.top, oy, step)
                while (y <= r.bottom) {
                    val rowK = rowIndex(y, oy)
                    var x = snap(r.left, ox, step)
                    while (x <= r.right) {
                        if (Math.floorMod(rowK + rowIndex(x, ox), 2) == 0) plain.addRect(x, y, x + step, y + step, Path.Direction.CW)
                        x += step
                    }
                    y += step
                }
            }
        }
        if (!plain.isEmpty) canvas.drawPath(plain, paint)
        if (!effect.isEmpty) {
            paint.pathEffect = when (lineType) {
                LineType.DASHED, LineType.SOLID_DASHED -> DashPathEffect(floatArrayOf(unit, unit * 0.5f), 0f)
                else -> DashPathEffect(floatArrayOf(unit * 1.1f, unit * 0.5f, 0.01f, unit * 0.49f), 0f)
            }
            canvas.drawPath(effect, paint)
            paint.pathEffect = null
        }
        canvas.restore()
    }

    /** Largest grid value `anchor + k·step` not above [value]. */
    private fun snap(value: Float, anchor: Float, step: Float) = anchor + floor((value - anchor) / step) * step
}
