package dev.inknotes.editor

import android.graphics.Matrix
import android.graphics.RectF
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import dev.inknotes.data.EraseCut
import dev.inknotes.data.InkBrushes
import dev.inknotes.data.InkKind
import dev.inknotes.data.PageFill
import dev.inknotes.data.PageImage
import dev.inknotes.data.PageStroke
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure geometry and element transforms used by the editor tools. Coordinates are page units. */
object PageEdits {
    private const val MIN_BRUSH = 0.2f
    private const val MAX_BRUSH = 500f

    fun bounds(s: PageStroke): RectF? {
        val b = s.stroke.shape.computeBoundingBox() ?: return null
        return RectF(b.xMin, b.yMin, b.xMax, b.yMax)
    }

    fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Even-odd point-in-polygon test; [poly] is x,y pairs. */
    fun inside(x: Float, y: Float, poly: FloatArray): Boolean {
        var inside = false
        var j = poly.size - 2
        var i = 0
        while (i < poly.size) {
            val xi = poly[i]
            val yi = poly[i + 1]
            val xj = poly[j]
            val yj = poly[j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
            i += 2
        }
        return inside
    }

    /** True when the capsule around segment a–b with radius [r] can touch [rect]. */
    fun segmentNear(rect: RectF, ax: Float, ay: Float, bx: Float, by: Float, r: Float): Boolean =
        max(ax, bx) + r >= rect.left && min(ax, bx) - r <= rect.right &&
            max(ay, by) + r >= rect.top && min(ay, by) - r <= rect.bottom

    // ------------------------------------------------------------ eraser

    fun strokeTouched(s: PageStroke, ax: Float, ay: Float, bx: Float, by: Float, r: Float): Boolean {
        val b = bounds(s) ?: return false
        if (!segmentNear(b, ax, ay, bx, by, r)) return false
        val inputs = s.stroke.inputs
        val reach = r + s.stroke.brush.size / 2
        val p = StrokeInput()
        for (i in 0 until inputs.size) {
            inputs.populate(i, p)
            if (distanceToSegment(p.x, p.y, ax, ay, bx, by) <= reach) return true
        }
        return false
    }

    fun fillTouched(f: PageFill, x: Float, y: Float, r: Float): Boolean {
        if (!segmentNear(f.bounds, x, y, x, y, r)) return false
        if (inside(x, y, f.outline)) return true
        val o = f.outline
        for (i in o.indices step 2) {
            val j = (i + 2) % o.size
            if (distanceToSegment(x, y, o[i], o[i + 1], o[j], o[j + 1]) <= r) return true
        }
        return false
    }

    /**
     * Partial eraser: removes the part of [s] within [r] of segment a–b and returns the
     * remaining pieces, or null if the stroke is untouched. The stroke is resampled first
     * so long, sparsely sampled segments are cut where touched, not at their endpoints.
     */
    fun splitStroke(s: PageStroke, ax: Float, ay: Float, bx: Float, by: Float, r: Float): List<PageStroke>? {
        val b = bounds(s) ?: return null
        if (!segmentNear(b, ax, ay, bx, by, r)) return null
        val reach = r + s.stroke.brush.size / 2
        val dense = resample(s.stroke, max(reach / 3f, 0.5f))
        if (dense.none { distanceToSegment(it.x, it.y, ax, ay, bx, by) <= reach }) return null

        val pieces = ArrayList<PageStroke>()
        var current = ArrayList<StrokeInput>()
        fun flush() {
            if (current.size >= 2) toStroke(s, current)?.let(pieces::add)
            current = ArrayList()
        }
        for (p in dense) {
            if (distanceToSegment(p.x, p.y, ax, ay, bx, by) <= reach) flush() else current += p
        }
        flush()
        return pieces
    }

    private fun resample(stroke: Stroke, step: Float): List<StrokeInput> {
        val inputs = stroke.inputs
        val out = ArrayList<StrokeInput>(inputs.size * 2)
        var prev: StrokeInput? = null
        for (i in 0 until inputs.size) {
            val cur = inputs.get(i)
            val p = prev
            if (p != null) {
                val n = ceil(hypot(cur.x - p.x, cur.y - p.y) / step).toInt()
                for (k in 1 until n) out += lerp(p, cur, k.toFloat() / n)
            }
            out += cur
            prev = cur
        }
        return out
    }

    private fun lerp(a: StrokeInput, b: StrokeInput, t: Float): StrokeInput {
        fun mix(x: Float, y: Float) = x + (y - x) * t
        return StrokeInput().apply {
            update(
                x = mix(a.x, b.x),
                y = mix(a.y, b.y),
                elapsedTimeMillis = a.elapsedTimeMillis + ((b.elapsedTimeMillis - a.elapsedTimeMillis) * t).toLong(),
                toolType = a.toolType,
                strokeUnitLengthCm = a.strokeUnitLengthCm,
                pressure = if (a.hasPressure) mix(a.pressure, b.pressure) else StrokeInput.NO_PRESSURE,
                tiltRadians = if (a.hasTilt) mix(a.tiltRadians, b.tiltRadians) else StrokeInput.NO_TILT,
                orientationRadians = if (a.hasOrientation) a.orientationRadians else StrokeInput.NO_ORIENTATION,
            )
        }
    }

    private fun toStroke(template: PageStroke, inputs: List<StrokeInput>): PageStroke? =
        runCatching {
            val batch = MutableStrokeInputBatch()
            for (p in inputs) batch.add(p)
            PageStroke(template.kind, Stroke(template.stroke.brush, batch))
        }.getOrNull()

    /** Adds the eraser segment to [f]'s cuts, extending the previous cut when it continues it. */
    fun cutFill(f: PageFill, ax: Float, ay: Float, bx: Float, by: Float, r: Float): PageFill {
        val last = f.cuts.lastOrNull()
        if (last != null && last.radius == r) {
            val pts = last.points
            if (pts[pts.size - 2] == ax && pts[pts.size - 1] == ay) {
                val extended = EraseCut(pts + floatArrayOf(bx, by), r)
                return f.copy(cuts = f.cuts.dropLast(1) + extended)
            }
        }
        return f.copy(cuts = f.cuts + EraseCut(floatArrayOf(ax, ay, bx, by), r))
    }

    // ------------------------------------------------------------- lasso

    /** A stroke is selected when at least half of its (sampled) input points are inside. */
    fun strokeInLasso(s: PageStroke, lasso: FloatArray, lassoBounds: RectF): Boolean {
        val b = bounds(s) ?: return false
        if (!RectF.intersects(b, lassoBounds)) return false
        val inputs = s.stroke.inputs
        val stride = max(1, inputs.size / 32)
        val p = StrokeInput()
        var total = 0
        var hits = 0
        var i = 0
        while (i < inputs.size) {
            inputs.populate(i, p)
            total++
            if (inside(p.x, p.y, lasso)) hits++
            i += stride
        }
        return hits * 2 >= total
    }

    fun fillInLasso(f: PageFill, lasso: FloatArray): Boolean {
        val o = f.outline
        var hits = 0
        for (i in o.indices step 2) if (inside(o[i], o[i + 1], lasso)) hits++
        return hits * 2 >= o.size / 2
    }

    /**
     * A similarity transform of page elements: [matrix] maps points; [scale] (uniform) also
     * scales line widths and picture sizes; [rotation] (degrees, clockwise) also turns hatch
     * patterns and pictures.
     */
    class Transform(
        val matrix: Matrix,
        val scale: Float = 1f,
        val rotation: Float = 0f,
        val sx: Float = scale,
        val sy: Float = scale,
    ) {
        private val pt = FloatArray(2)

        fun map(x: Float, y: Float): FloatArray {
            pt[0] = x
            pt[1] = y
            matrix.mapPoints(pt)
            return pt
        }

        companion object {
            fun translation(dx: Float, dy: Float) = Transform(Matrix().apply { setTranslate(dx, dy) })

            /**
             * Width times [sx], height times [sy] around ([ax], [ay]). Lines get the smaller
             * factor: stretching a table wider must not make its lines fatter.
             */
            fun stretch(sx: Float, sy: Float, ax: Float, ay: Float) =
                Transform(Matrix().apply { setScale(sx, sy, ax, ay) }, min(sx, sy), 0f, sx, sy)

            /**
             * Scale by [k] around ([ax], [ay]), then turn by [degrees] around ([cx], [cy]),
             * then move by ([dx], [dy]) — the order the editor previews a drag in.
             */
            fun of(k: Float, ax: Float, ay: Float, degrees: Float, cx: Float, cy: Float, dx: Float, dy: Float) = Transform(
                Matrix().apply {
                    setScale(k, k, ax, ay)
                    postRotate(degrees, cx, cy)
                    postTranslate(dx, dy)
                },
                k,
                degrees,
            )
        }
    }

    fun translate(s: PageStroke, dx: Float, dy: Float): PageStroke = transform(s, Transform.translation(dx, dy))

    fun translate(f: PageFill, dx: Float, dy: Float): PageFill = transform(f, Transform.translation(dx, dy))

    fun translate(img: PageImage, dx: Float, dy: Float): PageImage = transform(img, Transform.translation(dx, dy))

    /** Line widths scale too, so a resized stamp or table keeps its proportions. */
    fun transform(s: PageStroke, t: Transform): PageStroke {
        val inputs = s.stroke.inputs
        val batch = MutableStrokeInputBatch()
        val p = StrokeInput()
        for (i in 0 until inputs.size) {
            inputs.populate(i, p)
            val q = t.map(p.x, p.y)
            p.update(
                x = q[0],
                y = q[1],
                elapsedTimeMillis = p.elapsedTimeMillis,
                toolType = p.toolType,
                strokeUnitLengthCm = p.strokeUnitLengthCm,
                pressure = p.pressure,
                tiltRadians = p.tiltRadians,
                orientationRadians = p.orientationRadians,
            )
            batch.add(p)
        }
        val brush = if (t.scale == 1f) {
            s.stroke.brush
        } else {
            InkBrushes.create(s.kind, s.stroke.brush.colorIntArgb, (s.stroke.brush.size * t.scale).coerceIn(MIN_BRUSH, MAX_BRUSH))
        }
        return PageStroke(s.kind, Stroke(brush, batch))
    }

    fun transform(f: PageFill, t: Transform): PageFill {
        fun map(a: FloatArray) = a.copyOf().also(t.matrix::mapPoints)
        val style = if (t.rotation == 0f) f.style else f.style.copy(angle = f.style.angle + t.rotation).sane()
        return f.copy(outline = map(f.outline), cuts = f.cuts.map { EraseCut(map(it.points), it.radius * t.scale) }, style = style)
    }

    fun transform(img: PageImage, t: Transform): PageImage {
        val c = t.map(img.x + img.w / 2, img.y + img.h / 2)
        // A stretch acts on the picture's own sides: swapped when it stands turned by 90°.
        val quarter = Math.round(img.rotation / 90f).toInt()
        val square = abs(img.rotation - quarter * 90f) < 0.5f
        val (kw, kh) = when {
            t.sx == t.sy -> t.scale to t.scale
            square && quarter % 2 != 0 -> t.sy to t.sx
            square -> t.sx to t.sy
            else -> sqrt(t.sx * t.sy) to sqrt(t.sx * t.sy)
        }
        val w = img.w * kw
        val h = img.h * kh
        return img.moved(x = c[0] - w / 2, y = c[1] - h / 2, w = w, h = h, rotation = ((img.rotation + t.rotation) % 360f + 360f) % 360f)
    }

    /** Axis-aligned bounds of a (possibly rotated) picture. */
    fun bounds(img: PageImage): RectF {
        val r = RectF(img.x, img.y, img.x + img.w, img.y + img.h)
        if (img.rotation != 0f) Matrix().apply { setRotate(img.rotation, r.centerX(), r.centerY()) }.mapRect(r)
        return r
    }

    /** An image is selected when its centre is inside the lasso. */
    fun imageInLasso(img: PageImage, lasso: FloatArray): Boolean = inside(img.x + img.w / 2, img.y + img.h / 2, lasso)

    /** Highlighter strokes keep their translucency when recoloured. */
    fun recolor(s: PageStroke, color: Int): PageStroke {
        val c = if (s.kind == InkKind.HIGHLIGHTER) (color and 0x00FFFFFF) or (0x80 shl 24) else color
        return PageStroke(s.kind, s.stroke.copy(s.stroke.brush.copyWithColorIntArgb(colorIntArgb = c)))
    }

    fun recolor(f: PageFill, color: Int): PageFill = f.copy(color = color)

    /** Drops points closer than [minDist] to the previous kept point. */
    fun simplify(points: FloatArray, minDist: Float): FloatArray {
        if (points.size <= 4) return points
        val out = ArrayList<Float>(points.size)
        out += points[0]
        out += points[1]
        for (i in 2 until points.size step 2) {
            if (hypot(points[i] - out[out.size - 2], points[i + 1] - out[out.size - 1]) >= minDist) {
                out += points[i]
                out += points[i + 1]
            }
        }
        return out.toFloatArray()
    }
}
