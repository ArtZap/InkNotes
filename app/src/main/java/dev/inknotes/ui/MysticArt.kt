package dev.inknotes.ui

import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** The six covers of the reference set, in the order of the cover picker. */
enum class CoverArt(val title: String, val color: Int) {
    LEAVES("Листья", 0xFF2C4838.toInt()),
    ARCH("Арка", 0xFF936622.toInt()),
    MOON("Луна и звезда", 0xFF2B3D9E.toInt()),
    OVAL("Овал", 0xFF6C1F21.toInt()),
    MEDALLIONS("Медальоны", 0xFF1F4868.toInt()),
    WREATH("Венок с розой", 0xFF3D4A44.toInt()),
    ;

    /** Where the title sits (fraction of the height) and how wide it may be (of the width). */
    val titleY get() = when (this) {
        LEAVES -> 0.443f
        OVAL -> 0.555f
        MEDALLIONS -> 0.35f
        WREATH -> 0.47f
        else -> 0.5f
    }
    val titleWidth get() = when (this) {
        LEAVES -> 0.38f
        ARCH -> 0.36f
        MOON -> 0.28f
        OVAL -> 0.3f
        MEDALLIONS -> 0.36f
        WREATH -> 0.46f
    }
    val titleLines get() = if (this == MEDALLIONS || this == MOON) 2 else 3

    companion object {
        fun of(index: Int): CoverArt = entries[index.coerceIn(0, entries.lastIndex)]
    }
}

/**
 * Mystic covers drawn as pictures: cloth with its grain, gold foil with its mottling and
 * the ornament of each of the six designs. Drawing one costs a few milliseconds, so each
 * picture is drawn once per colour and size and kept; the library then only copies bitmaps.
 */
object MysticArt {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** The cover as a bitmap of [width]×[height] px; [see] = glass (the cloth lets the page through). */
    fun picture(art: CoverArt, color: Int, width: Int, height: Int, see: Boolean): ImageBitmap {
        val key = "$art|$color|${width}x$height|$see"
        cache.get(key)?.let { return it }
        val image = ImageBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1))
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(width.toFloat(), height.toFloat())) {
            Painter(this, art, Color(color), see).paint()
        }
        cache.put(key, image)
        return image
    }

    /** Fine random speckle, tiled: the grain of cloth and the mottling of foil. */
    private val noise: ImageBitmap by lazy {
        val n = 128
        val rnd = java.util.Random(42)
        val px = IntArray(n * n) {
            val v = rnd.nextInt(256)
            val a = 40 + rnd.nextInt(80)
            (a shl 24) or (v shl 16) or (v shl 8) or v
        }
        android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private class Painter(val s: DrawScope, val art: CoverArt, val color: Color, val see: Boolean) {
        val w = s.size.width
        val h = s.size.height
        val u = min(w, h * 0.72f) / 100f
        val rnd = java.util.Random(art.ordinal * 7919L + 17)
        val alpha = if (see) GLASS_ALPHA else 1f
        val grain = ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated))

        fun p(x: Float, y: Float) = Offset(x * w, y * h)

        fun paint() {
            when (art) {
                CoverArt.LEAVES -> leaves()
                CoverArt.ARCH -> arch()
                CoverArt.MOON -> moon()
                CoverArt.OVAL -> oval()
                CoverArt.MEDALLIONS -> medallions()
                CoverArt.WREATH -> wreath()
            }
        }

        // ------------------------------------------------------------ materials

        /** Cloth of [base]: a little lighter at the top, a vignette, its grain. */
        fun cloth(base: Color, grainAlpha: Float = 0.16f) = with(s) {
            drawRect(Brush.verticalGradient(listOf(lerp(base, Color.White, 0.05f), base, lerp(base, Color.Black, 0.2f))), alpha = alpha)
            drawRect(grain, alpha = grainAlpha * alpha, blendMode = BlendMode.Overlay)
            drawRect(
                Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)), Offset(w / 2, h * 0.45f), h * 0.85f),
                alpha = alpha,
            )
        }

        val foil = Brush.linearGradient(
            listOf(Color(0xFFF3DA8F), Color(0xFFC99A3C), Color(0xFFEBC871), Color(0xFFAA7E2D), Color(0xFFE2BC62), Color(0xFFC29338)),
            start = Offset.Zero,
            end = Offset(w, h),
        )

        /** Gold work: everything [block] draws is foil, mottled afterwards like real leaf. */
        fun gold(brush: Brush = foil, block: DrawScope.(Brush) -> Unit) = with(s) {
            val canvas = drawContext.canvas
            canvas.saveLayer(Rect(0f, 0f, w, h), Paint())
            s.block(brush)
            drawRect(grain, alpha = 0.55f, blendMode = BlendMode.SrcAtop)
            drawRect(Color(0xFF5A3A0A).copy(alpha = 0.10f), blendMode = BlendMode.SrcAtop)
            canvas.restore()
        }

        fun line(width: Float) = Stroke(width * u, cap = StrokeCap.Round, join = StrokeJoin.Round)

        // ------------------------------------------------------------ shapes

        /**
         * A leaf grown along a bending midrib from [base] towards [deg], [len] long and
         * [width] at its widest; [bend] turns the tip (degrees), [lobes] scallops the edge
         * like acanthus.
         */
        fun Path.leaf(base: Offset, deg: Float, len: Float, width: Float, bend: Float = 0f, lobes: Int = 0) {
            val n = 18
            val mid = ArrayList<Offset>(n + 1)
            val nor = ArrayList<Offset>(n + 1)
            var pos = base
            for (i in 0..n) {
                val t = i / n.toFloat()
                val a = Math.toRadians((deg + bend * t * t).toDouble())
                val d = Offset(cos(a).toFloat(), sin(a).toFloat())
                mid += pos
                nor += Offset(-d.y, d.x)
                pos += d * (len / n)
            }
            fun half(i: Int, side: Int): Float {
                val t = i / n.toFloat()
                var hw = width / 2 * sin(PI * t).toFloat().pow(0.6f) * (1f - 0.3f * t)
                // Acanthus: deep pointed lobes, offset between the two edges.
                if (lobes > 0) hw *= 0.5f + 0.5f * abs(sin(t * PI.toFloat() * lobes + if (side > 0) 0f else 0.9f)).pow(0.45f)
                return hw
            }
            moveTo(base.x, base.y)
            for (i in 1..n) {
                val q = mid[i] + nor[i] * half(i, 1)
                lineTo(q.x, q.y)
            }
            for (i in n - 1 downTo 1) {
                val q = mid[i] - nor[i] * half(i, -1)
                lineTo(q.x, q.y)
            }
            close()
        }

        /** A spiral of [turns] shrinking into [c]: the curl of a scroll. */
        fun Path.curl(c: Offset, r: Float, startDeg: Float, clockwise: Boolean, turns: Float = 1.4f, move: Boolean = true) {
            val n = 36
            for (i in 0..n) {
                val t = i / n.toFloat()
                val a = Math.toRadians((startDeg + (if (clockwise) 1 else -1) * 360f * turns * t).toDouble())
                val rr = r * (1f - 0.8f * t)
                val x = c.x + rr * cos(a).toFloat()
                val y = c.y + rr * sin(a).toFloat()
                if (i == 0 && move) moveTo(x, y) else lineTo(x, y)
            }
        }

        fun bez(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
            val q = 1 - t
            return p0 * (q * q * q) + p1 * (3 * q * q * t) + p2 * (3 * q * t * t) + p3 * (t * t * t)
        }

        fun angle(d: Offset) = Math.toDegrees(atan2(d.y.toDouble(), d.x.toDouble())).toFloat()

        /**
         * A scrolling stem: from [a] to [b] along a cubic, a curl at the end turning on the
         * way it goes, leaves along it on alternate sides.
         */
        fun scroll(
            stems: Path,
            leaves: Path,
            a: Offset, c1: Offset, c2: Offset, b: Offset,
            curlR: Float, clockwise: Boolean,
            count: Int, leafLen: Float, leafW: Float, lobes: Int = 0,
        ) {
            stems.moveTo(a.x, a.y)
            stems.cubicTo(c1.x, c1.y, c2.x, c2.y, b.x, b.y)
            if (curlR > 0f) {
                val dir = angle(b - bez(a, c1, c2, b, 0.96f))
                val side = if (clockwise) 90f else -90f
                val r = Math.toRadians((dir + side).toDouble())
                val centre = b + Offset(cos(r).toFloat(), sin(r).toFloat()) * curlR
                stems.curl(centre, curlR, dir - side, clockwise, move = false)
            }
            for (i in 1..count) {
                val t = i / (count + 1f)
                val q = bez(a, c1, c2, b, t)
                val dir = angle(bez(a, c1, c2, b, t + 0.02f) - q)
                val left = i % 2 == 0
                leaves.leaf(q, dir + if (left) -52f else 52f, leafLen, leafW, if (left) 30f else -30f, lobes)
            }
        }

        /** Mirror a path left-right about the middle. */
        fun mirrorX(path: Path): Path = Path().apply {
            addPath(path)
            transform(androidx.compose.ui.graphics.Matrix().apply { translate(w, 0f); scale(-1f, 1f) })
        }

        fun mirrorY(path: Path): Path = Path().apply {
            addPath(path)
            transform(androidx.compose.ui.graphics.Matrix().apply { translate(0f, h); scale(1f, -1f) })
        }

        fun both(path: Path): Path = Path().apply {
            addPath(path)
            addPath(mirrorX(path))
        }

        fun four(path: Path): Path = Path().apply {
            val two = both(path)
            addPath(two)
            addPath(mirrorY(two))
        }

        fun sparkle(c: Offset, r: Float, k: Float = 0.22f): Path = Path().apply {
            val q = r * k
            moveTo(c.x, c.y - r)
            quadraticTo(c.x + q, c.y - q, c.x + r, c.y)
            quadraticTo(c.x + q, c.y + q, c.x, c.y + r)
            quadraticTo(c.x - q, c.y + q, c.x - r, c.y)
            quadraticTo(c.x - q, c.y - q, c.x, c.y - r)
            close()
        }

        // ------------------------------------------------------------ the six covers

        /** Green: gold acanthus round a framed panel set high, a bouquet of leaves below it. */
        fun leaves() = with(s) {
            cloth(color)
            val l = 0.27f * w
            val r = 0.73f * w
            val t = 0.205f * h
            val b = 0.68f * h
            drawRect(lerp(color, Color.Black, 0.12f), Offset(l, t), Size(r - l, b - t), alpha = alpha)
            val stems = Path()
            val leaves = Path()
            // Climbing stem up the side, leaves turned out and up, curls tucked in.
            scroll(stems, leaves, p(0.2f, 0.97f), p(0.02f, 0.75f), p(0.2f, 0.45f), p(0.1f, 0.22f), 2.6f * u, true, 12, 9.5f * u, 5f * u, 3)
            scroll(stems, leaves, p(0.1f, 0.22f), p(0.03f, 0.12f), p(0.1f, 0.04f), p(0.24f, 0.06f), 2.4f * u, true, 4, 8f * u, 4.2f * u, 2)
            scroll(stems, leaves, p(0.06f, 0.6f), p(0.04f, 0.5f), p(0.12f, 0.42f), p(0.16f, 0.34f), 2f * u, false, 4, 7f * u, 3.6f * u, 2)
            scroll(stems, leaves, p(0.08f, 0.9f), p(0.05f, 0.82f), p(0.12f, 0.74f), p(0.2f, 0.72f), 2f * u, true, 4, 7f * u, 3.6f * u, 2)
            // Over the panel: two arms meeting in the middle.
            scroll(stems, leaves, p(0.5f, 0.17f), p(0.42f, 0.11f), p(0.33f, 0.19f), p(0.27f, 0.12f), 2.4f * u, false, 4, 7.5f * u, 3.8f * u, 2)
            scroll(stems, leaves, p(0.23f, 0.165f), p(0.2f, 0.2f), p(0.17f, 0.3f), p(0.22f, 0.36f), 2f * u, true, 3, 6.5f * u, 3.2f * u, 2)
            scroll(stems, leaves, p(0.42f, 0.06f), p(0.36f, 0.03f), p(0.3f, 0.08f), p(0.33f, 0.1f), 1.6f * u, true, 2, 5.5f * u, 2.8f * u, 1)
            // Along the panel's side.
            scroll(stems, leaves, p(0.23f, 0.66f), p(0.16f, 0.6f), p(0.24f, 0.48f), p(0.2f, 0.4f), 1.8f * u, false, 4, 7f * u, 3.4f * u, 2)
            // The bouquet under the panel: big leaves fanning out of the middle.
            scroll(stems, leaves, p(0.5f, 0.965f), p(0.44f, 0.86f), p(0.3f, 0.84f), p(0.3f, 0.73f), 2.6f * u, false, 6, 10f * u, 5f * u, 3)
            scroll(stems, leaves, p(0.5f, 0.9f), p(0.46f, 0.8f), p(0.4f, 0.74f), p(0.43f, 0.705f), 1.6f * u, true, 3, 7.5f * u, 3.6f * u, 2)
            scroll(stems, leaves, p(0.36f, 0.95f), p(0.28f, 0.97f), p(0.2f, 0.9f), p(0.16f, 0.95f), 1.8f * u, false, 3, 7f * u, 3.4f * u, 2)
            leaves.leaf(p(0.5f, 0.965f), -128f, 16f * u, 7f * u, -50f, 3)
            leaves.leaf(p(0.5f, 0.965f), -105f, 11f * u, 5f * u, -25f, 2)
            val dots = Path().apply {
                for (q in listOf(p(0.35f, 0.075f), p(0.06f, 0.5f), p(0.16f, 0.8f), p(0.38f, 0.93f), p(0.5f, 0.11f))) addOval(Rect(q, 0.55f * u))
            }
            gold {
                drawRect(it, Offset(2.4f * u, 2.4f * u), Size(w - 4.8f * u, h - 4.8f * u), style = line(0.45f))
                drawRect(it, Offset(l, t), Size(r - l, b - t), style = line(0.5f))
                drawRect(it, Offset(l + 1.8f * u, t + 1.8f * u), Size(r - l - 3.6f * u, b - t - 3.6f * u), style = line(0.3f))
                // A string of beads between the two lines.
                val beads = Path()
                val g = 0.9f * u
                var x = l + g
                while (x < r - g) {
                    beads.addOval(Rect(Offset(x, t + g), 0.22f * u))
                    beads.addOval(Rect(Offset(x, b - g), 0.22f * u))
                    x += 1.3f * u
                }
                var y = t + g
                while (y < b - g) {
                    beads.addOval(Rect(Offset(l + g, y), 0.22f * u))
                    beads.addOval(Rect(Offset(r - g, y), 0.22f * u))
                    y += 1.3f * u
                }
                drawPath(beads, it)
                drawPath(both(stems), it, style = line(0.45f))
                drawPath(both(leaves), it)
                drawPath(both(dots), it)
            }
        }

        /** Ochre: marbled leather in a black border, filigree all over, a black Mughal arch. */
        fun arch() = with(s) {
            cloth(color, 0.2f)
            // Marbling: soft clouds darker and lighter than the leather.
            repeat(60) {
                val c = Offset(rnd.nextFloat() * w, rnd.nextFloat() * h)
                val r = (4f + rnd.nextFloat() * 16f) * u
                val dark = rnd.nextFloat() < 0.6f
                val tone = if (dark) lerp(color, Color.Black, 0.55f) else lerp(color, Color(0xFFE8B85A), 0.5f)
                drawCircle(Brush.radialGradient(listOf(tone.copy(alpha = 0.2f * alpha), Color.Transparent), c, r), r, c)
            }
            val black = Color(0xFF0B0805)
            drawRect(black, style = Stroke(4.4f * u), alpha = if (see) 0.7f else 1f)
            drawRect(black, Offset(4.6f * u, 4.6f * u), Size(w - 9.2f * u, h - 9.2f * u), style = Stroke(0.45f * u), alpha = if (see) 0.7f else 1f)
            val shape = mughal(1f)
            // Filigree on the left half, mirrored: thin stems curling, little leaves and birds.
            val stems = Path()
            val leaves = Path()
            var y = 0.07f * h
            var row = 0
            while (y < 0.95f * h) {
                var x = 0.08f * w + (row % 2) * 0.06f * w
                while (x < 0.5f * w) {
                    val c = Offset(x + (rnd.nextFloat() - 0.5f) * 2f * u, y + (rnd.nextFloat() - 0.5f) * 2f * u)
                    // Clear of the arch (a diamond a little larger than it).
                    if (abs(c.x - 0.5f * w) / (0.4f * w) + abs(c.y - 0.5f * h) / (0.43f * h) > 1f) {
                        val turn = rnd.nextFloat() * 360f
                        val cw = rnd.nextBoolean()
                        val r = (1.8f + rnd.nextFloat() * 1f) * u
                        val a = Math.toRadians(turn.toDouble())
                        val start = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (r * 2.6f)
                        stems.moveTo(start.x, start.y)
                        stems.quadraticTo(c.x + (start.y - c.y) * 0.5f, c.y - (start.x - c.x) * 0.5f, c.x + r, c.y)
                        stems.curl(c, r, 0f, cw, move = false)
                        leaves.leaf(start, turn + 150f, 2.6f * u, 1.3f * u, 20f)
                        leaves.leaf(start, turn + 210f, 2.2f * u, 1.1f * u, -20f)
                        if (rnd.nextFloat() < 0.35f) {
                            // A little bird in flight.
                            val bq = c + Offset(r * 1.6f, -r * 1.4f)
                            leaves.leaf(bq, -160f, 1.6f * u, 0.9f * u, 30f)
                            leaves.leaf(bq, -20f, 1.6f * u, 0.9f * u, -30f)
                        }
                    }
                    x += 0.12f * w
                }
                y += 0.06f * h
                row++
            }
            gold {
                drawPath(both(stems), it, style = line(0.28f))
                drawPath(both(leaves), it)
            }
            drawPath(shape, Color.Black, alpha = if (see) 0.78f else 1f)
            gold {
                drawPath(shape, it, style = line(1.3f))
                drawPath(mughal(0.94f), it, style = line(0.3f))
            }
        }

        /** The Mughal arch: pointed top and bottom, lobed shoulders, points at the sides. */
        fun mughal(k: Float): Path {
            val c = p(0.5f, 0.5f)
            // One quarter (top right) as offsets from the centre, in cover fractions.
            fun q(x: Float, y: Float) = Offset(x * w * k, y * h * k)
            val quarter = listOf(
                // to, control 1, control 2
                Triple(q(0.1f, -0.265f), q(0.015f, -0.34f), q(0.085f, -0.31f)),
                Triple(q(0.2f, -0.215f), q(0.15f, -0.27f), q(0.205f, -0.255f)),
                Triple(q(0.33f, -0.01f), q(0.17f, -0.12f), q(0.27f, -0.03f)),
            )
            val top = q(0f, -0.36f)
            return Path().apply {
                moveTo(c.x + top.x, c.y + top.y)
                for ((to, c1, c2) in quarter) cubicTo(c.x + c1.x, c.y + c1.y, c.x + c2.x, c.y + c2.y, c.x + to.x, c.y + to.y)
                // Down the right side to the bottom, mirrored in y.
                val down = listOf(quarter[1].first, quarter[0].first, top)
                val all = listOf(quarter[2], quarter[1], quarter[0])
                for ((i, seg) in all.withIndex()) {
                    val to = down[i]
                    cubicTo(c.x + seg.third.x, c.y - seg.third.y, c.x + seg.second.x, c.y - seg.second.y, c.x + to.x, c.y - to.y)
                }
                // Up the left side.
                val up = listOf(quarter[0].first, quarter[1].first, quarter[2].first)
                for ((i, seg) in quarter.withIndex()) {
                    val to = up[i]
                    cubicTo(c.x - seg.second.x, c.y - seg.second.y, c.x - seg.third.x, c.y - seg.third.y, c.x - to.x, c.y - to.y)
                }
                val back = listOf(quarter[1].first, quarter[0].first, top)
                for ((i, seg) in all.withIndex()) {
                    val to = back[i]
                    cubicTo(c.x - seg.third.x, c.y + seg.third.y, c.x - seg.second.x, c.y + seg.second.y, c.x - to.x, c.y + to.y)
                }
                close()
            }
        }

        /** Royal blue: leaded lines like a window, a gold star in the middle, the moon above. */
        fun moon() = with(s) {
            cloth(color, 0.14f)
            val lead = lerp(color, Color(0xFF060A2A), 0.72f)
            val leads = Path()
            for (x in listOf(0.355f, 0.645f)) {
                leads.moveTo(x * w, 0f)
                leads.lineTo(x * w, h)
            }
            // Two waving lines a side, crossing each other into almond panes.
            for (side in listOf(0f, 1f)) {
                for (sign in listOf(1f, -1f)) {
                    var first = true
                    val n = 80
                    for (i in 0..n) {
                        val y = h * i / n
                        val x0 = 0.18f * w + sign * 0.12f * w * sin(y / h * PI.toFloat() * 3.2f + 0.4f)
                        val x = if (side == 0f) x0 else w - x0
                        if (first) leads.moveTo(x, y) else leads.lineTo(x, y)
                        first = false
                    }
                }
                // A third, gentler line near the edge.
                var first = true
                for (i in 0..80) {
                    val y = h * i / 80
                    val x0 = 0.045f * w + 0.03f * w * sin(y / h * PI.toFloat() * 5f)
                    val x = if (side == 0f) x0 else w - x0
                    if (first) leads.moveTo(x, y) else leads.lineTo(x, y)
                    first = false
                }
            }
            // Over and under the star, lines bowing out from its points.
            for (sy in listOf(1f, -1f)) {
                val tip = p(0.5f, 0.5f - sy * 0.255f)
                val end = if (sy > 0) 0f else h
                leads.moveTo(tip.x, tip.y)
                leads.cubicTo(tip.x - 0.08f * w, tip.y - sy * 0.06f * h, 0.42f * w, end + sy * 0.08f * h, 0.4f * w, end)
                leads.moveTo(tip.x, tip.y)
                leads.cubicTo(tip.x + 0.08f * w, tip.y - sy * 0.06f * h, 0.58f * w, end + sy * 0.08f * h, 0.6f * w, end)
            }
            drawPath(leads, lead, style = line(0.75f), alpha = if (see) 0.8f else 1f)
            // Light caught in the glass: a few soft glints between the leads.
            for ((gx, gy, gr) in listOf(Triple(0.2f, 0.3f, 14f), Triple(0.78f, 0.22f, 12f), Triple(0.75f, 0.78f, 15f), Triple(0.22f, 0.8f, 11f))) {
                val gc = p(gx, gy)
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.12f * alpha), Color.Transparent), gc, gr * u), gr * u, gc)
            }
            val c = p(0.5f, 0.5f)
            val tips = listOf(p(0.5f, 0.245f), p(0.8f, 0.5f), p(0.5f, 0.755f), p(0.2f, 0.5f))
            val star = Path().apply {
                moveTo(tips[0].x, tips[0].y)
                for (i in 0 until 4) {
                    val a = tips[i]
                    val b = tips[(i + 1) % 4]
                    val ctl = c + ((a + b) * 0.5f - c) * 0.34f
                    quadraticTo(ctl.x, ctl.y, b.x, b.y)
                }
                close()
            }
            val mc = p(0.47f, 0.115f)
            val mr = 0.085f * w
            val moon = Path().apply {
                op(
                    Path().apply { addOval(Rect(mc, mr)) },
                    Path().apply { addOval(Rect(mc + Offset(0.04f * w, -0.028f * h), mr * 0.86f)) },
                    PathOperation.Difference,
                )
            }
            val stars = Path()
            for ((x, y, r) in listOf(
                Triple(0.27f, 0.17f, 3.2f), Triple(0.62f, 0.215f, 1.6f), Triple(0.83f, 0.15f, 1.9f), Triple(0.67f, 0.335f, 2.6f),
                Triple(0.86f, 0.42f, 1.5f), Triple(0.27f, 0.6f, 1.5f), Triple(0.33f, 0.76f, 2f), Triple(0.6f, 0.87f, 1.5f),
                Triple(0.8f, 0.76f, 1.6f), Triple(0.93f, 0.63f, 3f), Triple(0.42f, 0.955f, 1.3f), Triple(0.13f, 0.88f, 1.4f),
            )) stars.addPath(sparkle(p(x, y), r * u, 0.18f))
            // Bound like a book: a dark band round the edge, a double gold frame, stars at its corners.
            drawRect(lead, style = Stroke(4.4f * u), alpha = if (see) 0.75f else 1f)
            val corners = Path()
            for ((x, y) in listOf(4.4f * u to 4.4f * u, w - 4.4f * u to 4.4f * u, 4.4f * u to h - 4.4f * u, w - 4.4f * u to h - 4.4f * u)) {
                corners.addPath(sparkle(Offset(x, y), 2.4f * u, 0.2f))
            }
            for ((x, y) in listOf(w / 2 to 3.4f * u, w / 2 to h - 3.4f * u)) corners.addPath(sparkle(Offset(x, y), 1.6f * u, 0.2f))
            // Star dust: fine gold specks over the glass, clear of the big star.
            val dust = Path()
            repeat(150) {
                val q = Offset((0.06f + rnd.nextFloat() * 0.88f) * w, (0.05f + rnd.nextFloat() * 0.9f) * h)
                val nx = abs(q.x - c.x) / (0.32f * w)
                val ny = abs(q.y - c.y) / (0.27f * h)
                if (nx + ny > 1.05f) dust.addOval(Rect(q, (0.12f + rnd.nextFloat() * 0.22f) * u))
            }
            // A ring of dots round the star, like a halo pressed into the cover.
            val halo = Path()
            for (k in 0 until 72) {
                val a = 2 * PI * k / 72
                halo.addOval(Rect(Offset(c.x + 0.36f * w * cos(a).toFloat(), c.y + 0.3f * h * sin(a).toFloat()), (if (k % 6 == 0) 0.55f else 0.28f) * u))
            }
            // Rays from the middle of each concave edge of the star, and its inner line.
            val rays = Path()
            for (i in 0 until 4) {
                val a = tips[i]
                val b = tips[(i + 1) % 4]
                val mid = c + ((a + b) * 0.5f - c) * 0.34f
                val dir = (mid - c) / (mid - c).getDistance()
                val from = mid + dir * (2f * u)
                val to = mid + dir * (21f * u)
                rays.moveTo(from.x, from.y)
                rays.lineTo(to.x, to.y)
                for (side in listOf(-1f, 1f)) {
                    val sideDir = Offset(dir.x * 0.92f - side * dir.y * 0.38f, dir.y * 0.92f + side * dir.x * 0.38f)
                    val f2 = mid + sideDir * (6f * u)
                    val t2 = mid + sideDir * (14f * u)
                    rays.moveTo(f2.x, f2.y)
                    rays.lineTo(t2.x, t2.y)
                }
                corners.addPath(sparkle(to + dir * (1.6f * u), 1.3f * u, 0.2f))
            }
            val innerStar = Path().apply {
                val k = 0.8f
                val inner = tips.map { c + (it - c) * k }
                moveTo(inner[0].x, inner[0].y)
                for (i in 0 until 4) {
                    val a = inner[i]
                    val b = inner[(i + 1) % 4]
                    val ctl = c + ((a + b) * 0.5f - c) * 0.34f
                    quadraticTo(ctl.x, ctl.y, b.x, b.y)
                }
                close()
            }
            // Two constellations: stars joined by dotted lines.
            val figures = listOf(
                listOf(p(0.13f, 0.25f), p(0.2f, 0.32f), p(0.16f, 0.4f), p(0.25f, 0.44f)),
                listOf(p(0.7f, 0.66f), p(0.8f, 0.69f), p(0.86f, 0.8f), p(0.77f, 0.88f), p(0.68f, 0.84f)),
            )
            val links = Path()
            for (fig in figures) {
                links.moveTo(fig[0].x, fig[0].y)
                for (q in fig.drop(1)) links.lineTo(q.x, q.y)
                for ((i, q) in fig.withIndex()) corners.addPath(sparkle(q, (if (i % 2 == 0) 1.5f else 1.1f) * u, 0.2f))
            }
            // Little stars keeping the moon company, and its engraved inner edge.
            for ((x, y, r) in listOf(Triple(0.6f, 0.07f, 1f), Triple(0.38f, 0.07f, 0.8f), Triple(0.58f, 0.16f, 0.7f))) corners.addPath(sparkle(p(x, y), r * u, 0.2f))
            val moonEdge = Path().apply {
                op(
                    Path().apply { addOval(Rect(mc, mr * 0.84f)) },
                    Path().apply { addOval(Rect(mc + Offset(0.04f * w, -0.028f * h), mr * 0.98f)) },
                    PathOperation.Difference,
                )
            }
            gold {
                drawRect(it, Offset(2.4f * u, 2.4f * u), Size(w - 4.8f * u, h - 4.8f * u), style = line(0.5f))
                drawRect(it, Offset(4.4f * u, 4.4f * u), Size(w - 8.8f * u, h - 8.8f * u), style = line(0.28f))
                drawPath(dust, it, alpha = 0.8f)
                drawPath(halo, it, alpha = 0.85f)
                drawPath(links, it, style = Stroke(0.22f * u, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(0.9f * u, 0.9f * u))), alpha = 0.8f)
                drawPath(rays, it, style = line(0.3f))
                drawPath(corners, it)
                drawPath(star, it)
                drawPath(moon, it)
                drawPath(stars, it)
            }
            // Engraving on the gold: the star's inner line and the moon's inner edge.
            val engrave = lerp(Color(0xFF8A6420), Color.Black, 0.15f)
            drawPath(innerStar, engrave, style = line(0.3f), alpha = 0.75f)
            drawPath(moonEdge, engrave, style = line(0.25f), alpha = 0.6f)
        }

        /** Red: a coral border with baroque scrolls at the top, a coral oval, engraved acanthus. */
        fun oval() = with(s) {
            val coral = lerp(color, Color(0xFFF4B19C), 0.62f)
            val dark = lerp(color, Color.Black, 0.55f)
            val deep = lerp(color, Color.Black, 0.22f)
            drawRect(coral, alpha = alpha)
            drawRect(grain, alpha = 0.12f * alpha, blendMode = BlendMode.Overlay)
            val inset = 0.04f * w
            drawRect(color, Offset(inset, inset), Size(w - 2 * inset, h - 2 * inset), alpha = alpha)
            drawRect(grain, Offset(inset, inset), Size(w - 2 * inset, h - 2 * inset), alpha = 0.18f * alpha, blendMode = BlendMode.Overlay)
            // Brocade: a faint lattice of quatrefoils woven into the red, clear of the oval.
            val oc0 = p(0.5f, 0.555f)
            val brocade = Path()
            var by = inset + 4f * u
            var brow = 0
            while (by < h - inset) {
                var bx = inset + 4f * u + (brow % 2) * 3.5f * u
                while (bx < w - inset) {
                    val nx = (bx - oc0.x) / (0.2f * w + 5f * u)
                    val ny = (by - oc0.y) / (0.255f * h + 5f * u)
                    if (nx * nx + ny * ny > 1f) {
                        brocade.addPath(sparkle(Offset(bx, by), 1.3f * u, 0.5f))
                        brocade.addOval(Rect(Offset(bx, by), 0.35f * u))
                    }
                    bx += 7f * u
                }
                by += 6f * u
                brow++
            }
            drawPath(brocade, lerp(color, Color.Black, 0.3f), alpha = 0.45f * alpha)
            drawPath(brocade, lerp(color, coral, 0.25f), alpha = 0.18f * alpha)
            // A thin coral line inside the panel.
            drawRect(coral, Offset(inset + 1.8f * u, 0.16f * h), Size(w - 2 * inset - 3.6f * u, h - 0.16f * h - inset - 1.8f * u), style = Stroke(0.35f * u), alpha = alpha)

            /** A band of coral with a dark outline, like a cut-out. */
            fun band(path: Path, width: Float) {
                drawPath(path, dark, style = line(width + 0.7f), alpha = alpha)
                drawPath(path, coral, style = line(width), alpha = alpha)
            }
            // Baroque C-scrolls filling the top corners, a stem along the top and one down the side.
            val corner = Path().apply {
                curl(p(0.12f, 0.085f), 6f * u, 180f, true, turns = 1.3f)
                moveTo(0.12f * w - 6f * u, 0.085f * h)
                cubicTo(0.05f * w, 0.15f * h, 0.07f * w, 0.19f * h, inset + 1.8f * u, 0.22f * h)
                moveTo(0.12f * w, 0.085f * h - 6f * u)
                cubicTo(0.22f * w, 0.02f * h, 0.3f * w, 0.07f * h, 0.38f * w, 0.04f * h)
                curl(p(0.38f, 0.06f), 1.6f * u, 270f, false, turns = 1.2f, move = false)
                moveTo(0.18f * w, 0.115f * h)
                cubicTo(0.24f * w, 0.14f * h, 0.29f * w, 0.1f * h, 0.33f * w, 0.12f * h)
                curl(p(0.33f, 0.1f), 1.6f * u, 90f, true, turns = 1.2f, move = false)
            }
            band(both(corner), 1.4f)
            val cornerLeaves = Path().apply {
                leaf(p(0.2f, 0.045f), -10f, 7f * u, 3f * u, 50f, 2)
                leaf(p(0.25f, 0.125f), 0f, 6f * u, 2.6f * u, -50f, 2)
                leaf(p(0.06f, 0.15f), 100f, 6f * u, 2.6f * u, -40f, 2)
            }
            drawPath(both(cornerLeaves), coral, alpha = alpha)
            drawPath(both(cornerLeaves), dark, style = line(0.3f), alpha = alpha)
            // The oval: a wide coral band with fine lines either side.
            val oc = p(0.5f, 0.555f)
            val rx = 0.2f * w
            val ry = 0.255f * h
            val oval = Path().apply { addOval(Rect(oc.x - rx, oc.y - ry, oc.x + rx, oc.y + ry)) }
            band(oval, 1.8f)
            drawPath(Path().apply { addOval(Rect(oc.x - rx + 2f * u, oc.y - ry + 2f * u, oc.x + rx - 2f * u, oc.y + ry - 2f * u)) }, coral, style = line(0.3f), alpha = alpha)
            // A string of coral beads round the oval.
            val beads = Path()
            val beadN = 64
            for (k in 0 until beadN) {
                val a = 2 * PI * k / beadN
                beads.addOval(Rect(Offset(oc.x + (rx + 3.2f * u) * cos(a).toFloat(), oc.y + (ry + 3.2f * u) * sin(a).toFloat()), 0.55f * u))
            }
            drawPath(beads, dark, alpha = alpha)
            drawPath(Path().apply {
                for (k in 0 until beadN) {
                    val a = 2 * PI * k / beadN
                    addOval(Rect(Offset(oc.x + (rx + 3.2f * u) * cos(a).toFloat(), oc.y + (ry + 3.2f * u) * sin(a).toFloat()), 0.38f * u))
                }
            }, coral, alpha = alpha)
            // The crest over the oval: two C-scrolls back to back and a fan of leaves.
            val crestY = oc.y - ry - 5.5f * u
            val crest = Path().apply {
                moveTo(oc.x, crestY + 2.5f * u)
                cubicTo(oc.x - 3f * u, crestY - 1f * u, oc.x - 8f * u, crestY - 1f * u, oc.x - 10f * u, crestY + 2f * u)
                curl(Offset(oc.x - 10f * u, crestY + 4f * u), 2f * u, 270f, false, turns = 1.2f, move = false)
                moveTo(oc.x - 4f * u, crestY + 3.5f * u)
                quadraticTo(oc.x - 12f * u, crestY + 7f * u, oc.x - 17f * u, crestY + 9f * u)
                curl(Offset(oc.x - 17f * u, crestY + 11f * u), 1.6f * u, 270f, true, turns = 1.2f, move = false)
            }
            band(both(crest), 1f)
            val fan = Path().apply {
                for (k in 0..4) leaf(Offset(oc.x, crestY + 2f * u), -150f + k * 30f, (6.5f - abs(k - 2) * 1.2f) * u, 2.2f * u, 0f, 2)
            }
            drawPath(fan, coral, alpha = alpha)
            drawPath(fan, dark, style = line(0.3f), alpha = alpha)
            drawCircle(dark, 1.6f * u, Offset(oc.x, crestY + 2.5f * u), alpha = alpha)
            drawCircle(coral, 1.1f * u, Offset(oc.x, crestY + 2.5f * u), alpha = alpha)
            // Garlands hanging from the corner scrolls down the sides: a waving stem of small leaves.
            val garland = Path()
            val garlandLeaves = Path()
            val gx = 0.115f * w
            var gfirst = true
            val gn = 30
            for (i in 0..gn) {
                val t = i / gn.toFloat()
                val y = 0.2f * h + t * 0.3f * h
                val x = gx + 1.6f * u * sin(t * PI.toFloat() * 5f)
                if (gfirst) garland.moveTo(x, y) else garland.lineTo(x, y)
                gfirst = false
                if (i % 3 == 1) garlandLeaves.leaf(Offset(x, y), if ((i / 3) % 2 == 0) 30f else 150f, 3.6f * u, 1.6f * u, if ((i / 3) % 2 == 0) 25f else -25f, 1)
            }
            garlandLeaves.addOval(Rect(Offset(gx, 0.51f * h), 1.2f * u))
            band(both(garland), 0.45f)
            drawPath(both(garlandLeaves), coral, alpha = alpha)
            drawPath(both(garlandLeaves), dark, style = line(0.25f), alpha = alpha)
            // Acanthus: big scalloped leaves rising from the bottom corners, engraved.
            val acanthus = Path().apply {
                leaf(p(0.14f, 0.975f), -80f, 52f * u, 19f * u, 130f, 6)
                leaf(p(0.08f, 0.96f), -100f, 40f * u, 15f * u, -125f, 5)
                leaf(p(0.26f, 0.975f), -50f, 30f * u, 13f * u, 120f, 4)
                leaf(p(0.13f, 0.975f), -10f, 22f * u, 10f * u, -80f, 3)
                leaf(p(0.05f, 0.62f), -60f, 20f * u, 9f * u, 110f, 3)
            }
            val leaves = Path().apply {
                op(both(acanthus), Path().apply { addRect(Rect(inset, inset, w - inset, h - inset)) }, PathOperation.Intersect)
            }
            drawPath(leaves, deep, alpha = alpha)
            // Engraving: veins and hatching inside the leaves.
            val veins = Path().apply {
                for ((b, d, l, bend) in listOf(
                    Quad(p(0.14f, 0.975f), -80f, 52f * u, 130f), Quad(p(0.08f, 0.96f), -100f, 40f * u, -125f),
                    Quad(p(0.26f, 0.975f), -50f, 30f * u, 120f), Quad(p(0.05f, 0.62f), -60f, 20f * u, 110f),
                )) {
                    var pos = b
                    moveTo(pos.x, pos.y)
                    for (i in 1..14) {
                        val t = i / 14f
                        val a = Math.toRadians((d + bend * t * t).toDouble())
                        val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
                        pos += dir * (l / 14f)
                        lineTo(pos.x, pos.y)
                        if (i in 2..12 && i % 2 == 0) {
                            // Hatch strokes off the midrib.
                            val nrm = Offset(-dir.y, dir.x)
                            val reach = l * 0.1f * sin(PI * t).toFloat()
                            moveTo(pos.x + nrm.x * reach, pos.y + nrm.y * reach)
                            lineTo(pos.x + dir.x * reach * 0.6f, pos.y + dir.y * reach * 0.6f)
                            lineTo(pos.x - nrm.x * reach, pos.y - nrm.y * reach)
                            moveTo(pos.x, pos.y)
                        }
                    }
                }
            }
            drawPath(both(veins), dark, style = line(0.3f), alpha = alpha)
            drawPath(leaves, dark, style = line(0.4f), alpha = alpha)
            // Highlights along the leaves' edges.
            drawPath(leaves, lerp(color, coral, 0.35f), style = line(0.15f), alpha = 0.6f * alpha)
            // The little coral circle at the foot.
            val foot = p(0.5f, 0.957f)
            drawCircle(dark, 2f * u, foot, alpha = alpha)
            drawCircle(coral, 1.5f * u, foot, alpha = alpha)
        }

        /** Teal: gold speckles, a framed panel, four medallions with dice, scrolls in the corners. */
        fun medallions() = with(s) {
            cloth(color)
            val l = 0.29f * w
            val r = 0.71f * w
            val t = 0.2f * h
            val b = 0.8f * h
            val mr = 0.135f * w
            val centres = listOf(p(0.5f, 0.2f), p(0.5f, 0.8f), Offset(l, 0.5f * h), Offset(r, 0.5f * h))
            drawRect(lerp(color, Color.White, 0.04f), Offset(l, t), Size(r - l, b - t), alpha = alpha)
            val speck = Path()
            repeat(900) {
                val q = Offset(rnd.nextFloat() * w, rnd.nextFloat() * h)
                val inPanel = q.x in l..r && q.y in t..b
                if (!inPanel && centres.none { (it - q).getDistance() < mr + 0.5f * u }) speck.addOval(Rect(q, (0.16f + rnd.nextFloat() * 0.2f) * u))
            }
            val stems = Path()
            val leaves = Path()
            // Scrolls in the top-left space, mirrored into the other three.
            scroll(stems, leaves, p(0.37f, 0.125f), p(0.3f, 0.05f), p(0.17f, 0.04f), p(0.12f, 0.1f), 2.6f * u, false, 4, 4.6f * u, 2.2f * u, 2)
            scroll(stems, leaves, p(0.25f, 0.4f), p(0.15f, 0.34f), p(0.06f, 0.26f), p(0.1f, 0.17f), 2.4f * u, true, 4, 4.4f * u, 2.1f * u, 2)
            scroll(stems, leaves, p(0.22f, 0.24f), p(0.17f, 0.2f), p(0.2f, 0.15f), p(0.24f, 0.16f), 1.4f * u, true, 2, 3.4f * u, 1.6f * u, 1)
            gold {
                drawPath(speck, it, alpha = 0.75f)
                drawRect(it, Offset(2.4f * u, 2.4f * u), Size(w - 4.8f * u, h - 4.8f * u), style = line(0.45f))
                drawPath(four(stems), it, style = line(0.45f))
                drawPath(four(leaves), it)
                drawRect(it, Offset(l, t), Size(r - l, b - t), style = line(0.55f))
                // The inner line with notched corners.
                val g = 1.6f * u
                val nch = 2.6f * u
                val inner = Path().apply {
                    moveTo(l + g + nch, t + g)
                    lineTo(r - g - nch, t + g); lineTo(r - g - nch, t + g + nch); lineTo(r - g, t + g + nch)
                    lineTo(r - g, b - g - nch); lineTo(r - g - nch, b - g - nch); lineTo(r - g - nch, b - g)
                    lineTo(l + g + nch, b - g); lineTo(l + g + nch, b - g - nch); lineTo(l + g, b - g - nch)
                    lineTo(l + g, t + g + nch); lineTo(l + g + nch, t + g + nch); close()
                }
                drawPath(inner, it, style = line(0.3f))
            }
            for (c in centres) drawCircle(lerp(color, Color.Black, 0.04f), mr, c, alpha = alpha)
            val dice = Path().apply {
                icosahedron(centres[0], 0.085f * w)
                pyramid(centres[1], 0.08f * w)
                octahedron(centres[2], 0.085f * w)
                octahedron(centres[3], 0.085f * w)
            }
            gold {
                for (c in centres) {
                    drawCircle(it, mr, c, style = line(0.55f))
                    drawCircle(it, mr - 1.3f * u, c, style = line(0.25f))
                }
                drawPath(dice, it, style = line(0.35f))
            }
        }

        fun Path.icosahedron(c: Offset, r: Float) {
            val hex = (0 until 6).map { k -> c + Offset(r * cos(PI / 3 * k - PI / 2).toFloat(), r * sin(PI / 3 * k - PI / 2).toFloat()) }
            val tri = (0 until 3).map { k -> c + Offset(0.5f * r * cos(2 * PI / 3 * k - PI / 2).toFloat(), 0.5f * r * sin(2 * PI / 3 * k - PI / 2).toFloat() + 0.12f * r) }
            moveTo(hex[0].x, hex[0].y)
            for (q in hex.drop(1)) lineTo(q.x, q.y)
            close()
            moveTo(tri[0].x, tri[0].y)
            lineTo(tri[1].x, tri[1].y)
            lineTo(tri[2].x, tri[2].y)
            close()
            for (q in tri) for (v in hex.sortedBy { (it - q).getDistanceSquared() }.take(2)) {
                moveTo(q.x, q.y)
                lineTo(v.x, v.y)
            }
            moveTo(tri[1].x, tri[1].y); lineTo(hex[2].x, hex[2].y)
            moveTo(tri[2].x, tri[2].y); lineTo(hex[4].x, hex[4].y)
        }

        fun Path.pyramid(c: Offset, r: Float) {
            val top = c + Offset(0f, -r)
            val left = c + Offset(-r * 0.92f, r * 0.62f)
            val right = c + Offset(r * 0.92f, r * 0.62f)
            val front = c + Offset(r * 0.12f, r * 0.62f)
            moveTo(top.x, top.y); lineTo(left.x, left.y); lineTo(right.x, right.y); close()
            moveTo(top.x, top.y); lineTo(front.x, front.y)
            moveTo(top.x, top.y); lineTo(c.x - r * 0.2f, c.y + r * 0.3f); lineTo(right.x, right.y)
        }

        fun Path.octahedron(c: Offset, r: Float) {
            val top = c + Offset(0f, -r)
            val bottom = c + Offset(0f, r)
            val left = c + Offset(-r * 0.72f, -0.05f * r)
            val right = c + Offset(r * 0.72f, -0.05f * r)
            val front = c + Offset(r * 0.12f, r * 0.12f)
            moveTo(top.x, top.y); lineTo(right.x, right.y); lineTo(bottom.x, bottom.y); lineTo(left.x, left.y); close()
            moveTo(left.x, left.y); lineTo(front.x, front.y); lineTo(right.x, right.y)
            moveTo(top.x, top.y); lineTo(front.x, front.y); lineTo(bottom.x, bottom.y)
        }

        /** Grey-green: a ruled border, dense vines round a round clearing, a rose at its foot. */
        fun wreath() = with(s) {
            cloth(color)
            val muted = Brush.linearGradient(listOf(Color(0xFFCDBD82), Color(0xFF9C8C55), Color(0xFFC2B276), Color(0xFF8E7F4A)), Offset.Zero, Offset(w, h))
            val c = p(0.5f, 0.47f)
            val clear = 0.3f * w
            val rose = p(0.5f, 0.875f)
            val stems = Path()
            val leaves = Path()
            fun vine(a: Offset, c1: Offset, c2: Offset, b: Offset, curl: Float, cw: Boolean, n: Int) {
                stems.moveTo(a.x, a.y)
                stems.cubicTo(c1.x, c1.y, c2.x, c2.y, b.x, b.y)
                if (curl > 0f) {
                    val dir = angle(b - bez(a, c1, c2, b, 0.96f))
                    val side = if (cw) 90f else -90f
                    val r = Math.toRadians((dir + side).toDouble())
                    stems.curl(b + Offset(cos(r).toFloat(), sin(r).toFloat()) * curl, curl, dir - side, cw, turns = 1.6f, move = false)
                }
                for (i in 1..n) {
                    val t = i / (n + 1f)
                    val q = bez(a, c1, c2, b, t)
                    val dir = angle(bez(a, c1, c2, b, t + 0.02f) - q)
                    leaves.leaf(q, dir + if (i % 2 == 0) -55f else 55f, 2.4f * u, 1.1f * u, if (i % 2 == 0) 25f else -25f)
                }
            }
            // Paisley swirls at the top, branches round the clearing, stems down the sides.
            vine(p(0.5f, 0.1f), p(0.38f, 0.04f), p(0.12f, 0.08f), p(0.22f, 0.2f), 7f * u, false, 16)
            vine(p(0.5f, 0.15f), p(0.42f, 0.11f), p(0.32f, 0.17f), p(0.36f, 0.22f), 3.5f * u, false, 8)
            vine(p(0.12f, 0.08f), p(0.1f, 0.3f), p(0.2f, 0.35f), p(0.14f, 0.5f), 4f * u, true, 14)
            vine(p(0.1f, 0.5f), p(0.08f, 0.65f), p(0.2f, 0.7f), p(0.17f, 0.8f), 3.6f * u, false, 12)
            vine(p(0.1f, 0.95f), p(0.2f, 0.88f), p(0.3f, 0.95f), p(0.38f, 0.86f), 3f * u, true, 10)
            vine(p(0.2f, 0.28f), p(0.24f, 0.32f), p(0.17f, 0.4f), p(0.2f, 0.45f), 2.4f * u, true, 6)
            vine(p(0.2f, 0.6f), p(0.25f, 0.68f), p(0.3f, 0.74f), p(0.28f, 0.8f), 2.4f * u, false, 6)
            vine(p(0.08f, 0.2f), p(0.12f, 0.24f), p(0.06f, 0.3f), p(0.09f, 0.36f), 2f * u, true, 5)
            vine(p(0.3f, 0.86f), p(0.36f, 0.8f), p(0.42f, 0.84f), p(0.4f, 0.9f), 2f * u, false, 5)
            // Loose leaves filling what is left, clear of the middle and the rose.
            repeat(260) {
                val q = Offset((0.07f + rnd.nextFloat() * 0.43f) * w, (0.06f + rnd.nextFloat() * 0.88f) * h)
                if ((q - c).getDistance() > clear + 1.5f * u && (q - rose).getDistance() > 7f * u) {
                    leaves.leaf(q, rnd.nextFloat() * 360f, (1.6f + rnd.nextFloat()) * u, 0.8f * u, 20f)
                }
            }
            // The rim of the clearing.
            for (k in 0 until 44) {
                val a = 2 * PI * k / 44
                val q = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (clear + 1.8f * u)
                if (q.x > 0.5f * w + 1f || (q - rose).getDistance() < 8f * u) continue
                leaves.leaf(q, Math.toDegrees(a).toFloat() + if (k % 2 == 0) 70f else 110f, 2.6f * u, 1.1f * u, 15f)
            }
            val roseShape = Path().apply {
                addOval(Rect(rose, 3.8f * u))
                leaf(rose + Offset(-3f * u, 1.2f * u), 165f, 5.4f * u, 2.4f * u, -30f, 1)
                leaf(rose + Offset(3f * u, 1.2f * u), 15f, 5.4f * u, 2.4f * u, 30f, 1)
            }
            gold(muted) {
                // The ruled border: bands of lines, short dashes down the sides, boxes at the corners.
                val rule = Path().apply {
                    addRect(Rect(1.6f * u, 1.6f * u, w - 1.6f * u, h - 1.6f * u))
                    for (k in 0..3) {
                        val y = (2.8f + k * 1.1f) * u
                        moveTo(7f * u, y); lineTo(w - 7f * u, y)
                        moveTo(7f * u, h - y); lineTo(w - 7f * u, h - y)
                    }
                    for (k in 0..2) {
                        val x = (2.8f + k * 1.5f) * u
                        moveTo(x, 7f * u); lineTo(x, h - 7f * u)
                        moveTo(w - x, 7f * u); lineTo(w - x, h - 7f * u)
                    }
                    addRect(Rect(7f * u, 7f * u, w - 7f * u, h - 7f * u))
                    for ((x, y) in listOf(1.6f * u to 1.6f * u, w - 7f * u to 1.6f * u, 1.6f * u to h - 7f * u, w - 7f * u to h - 7f * u)) {
                        addRect(Rect(x, y, x + 5.4f * u, y + 5.4f * u))
                    }
                }
                drawPath(rule, it, style = line(0.3f))
                drawPath(both(stems), it, style = line(0.3f))
                drawPath(both(leaves), it, alpha = 0.95f)
                drawPath(roseShape, it)
            }
            // The rose's petals pressed into the gold.
            val petals = Path().apply {
                curl(rose, 3.2f * u, 200f, true, turns = 2f)
                moveTo(rose.x - 3.6f * u, rose.y + 0.8f * u)
                quadraticTo(rose.x, rose.y + 4.4f * u, rose.x + 3.6f * u, rose.y + 0.8f * u)
            }
            drawPath(petals, lerp(color, Color.Black, 0.3f), style = line(0.35f))
        }
    }

    private data class Quad(val a: Offset, val b: Float, val c: Float, val d: Float)
}
