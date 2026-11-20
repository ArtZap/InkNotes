package dev.inknotes.data

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ColorFunction
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.TextureBitmapStore
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Stored by ordinal: only append. FOUNTAIN since page format v5, NEON since v8, LINE and SPOILER since v10. */
enum class InkKind {
    PEN,
    HIGHLIGHTER,
    FOUNTAIN,

    /**
     * A glowing tube with a light core and a halo ([dev.inknotes.editor.Neon]); the brush size
     * is the width of the tube, the core and halo are in proportion (see PageRenderer).
     */
    NEON,

    /** Graphite with paper grain (the stock pencil texture). */
    PENCIL,

    /** An even dashed line, for diagrams and construction lines. */
    DASHED,

    /** A line of the same width all along: ideal shapes, stamps, tables, lines along the ruler. */
    LINE,

    /**
     * Hint marker: a band that hides what is under it until a finger taps it (the page
     * shows it revealed until the next tap). Drawn above all ink.
     */
    SPOILER,
    ;

    /** Ink drawn above highlighter (everything except the highlighter and the hint marker). */
    val isInk get() = this != HIGHLIGHTER && this != SPOILER
}

/**
 * Identity matters: selection and undo track elements by reference, so no data class.
 * [dash] is the pattern of a [InkKind.DASHED] line (null: the stock dashes of older notes).
 */
class PageStroke(val kind: InkKind, val stroke: Stroke, val dash: DashStyle? = null, val layer: Int = Layers.active) {
    /** The same line with another stroke (moved, recoloured, cut…), its dash pattern and layer kept. */
    fun with(stroke: Stroke, kind: InkKind = this.kind) = PageStroke(kind, stroke, if (kind == InkKind.DASHED) dash else null, layer)

    fun onLayer(layer: Int) = PageStroke(kind, stroke, dash, layer)
}

/**
 * Layers of a notebook: every element is on one (0 is the main layer, the only one of notes
 * made before layers). New elements go onto [active]; hidden layers are not drawn, and neither
 * hidden nor locked ones are erased, selected or moved.
 */
object Layers {
    const val MAX = 8

    /** Layer that new elements are put on (set by the open notebook). */
    @Volatile
    var active = 0

    fun of(x: Any): Int = when (x) {
        is PageStroke -> x.layer
        is PageFill -> x.layer
        is PageImage -> x.layer
        else -> 0
    }

    fun onLayer(x: Any, layer: Int): Any = when (x) {
        is PageStroke -> x.onLayer(layer)
        is PageFill -> x.copy(layer = layer)
        is PageImage -> x.onLayer(layer)
        else -> x
    }

    /** [c] without the elements of [hidden] layers. */
    fun visible(c: PageContent, hidden: Set<Int>): PageContent =
        if (hidden.isEmpty()) c else PageContent(c.strokes.filter { it.layer !in hidden }, c.fills.filter { it.layer !in hidden }, c.images.filter { it.layer !in hidden })
}

/** Marks a dashed line is made of, repeated; stored by ordinal (page format v11+): only append. */
enum class DashPattern(val title: String) {
    DASH(tr("Штрихи", "Dashes")),
    DOT(tr("Точки", "Dots")),
    DASH_DOT(tr("Штрих-точка", "Dash-dot")),
    DASH_DOT_DOT(tr("Штрих, две точки", "Dash, two dots")),
    LONG_SHORT(tr("Длинный, короткий", "Long, short")),
    DASH_DASH_DOT(tr("Два штриха, точка", "Two dashes, dot")),
}

/**
 * How a dashed line is drawn, in page units: dashes [dash] long (as they look, round ends
 * included) with [gap] of paper between marks. Dots are as wide as the line.
 */
data class DashStyle(
    val pattern: DashPattern = DashPattern.DASH,
    val dash: Float = 14f,
    val gap: Float = 9f,
) {
    fun sane() = DashStyle(pattern, dash.coerceIn(MIN_LENGTH, MAX_LENGTH), gap.coerceIn(MIN_LENGTH, MAX_LENGTH))

    /**
     * On and off lengths for a line [width] wide with round ends: the ends stick out half the
     * width past each mark, so a mark is drawn that much shorter and a gap that much longer.
     */
    fun intervals(width: Float): FloatArray {
        val s = sane()
        val marks = when (s.pattern) {
            DashPattern.DASH -> listOf(s.dash)
            DashPattern.DOT -> listOf(0f)
            DashPattern.DASH_DOT -> listOf(s.dash, 0f)
            DashPattern.DASH_DOT_DOT -> listOf(s.dash, 0f, 0f)
            DashPattern.LONG_SHORT -> listOf(s.dash, s.dash * 0.35f)
            DashPattern.DASH_DASH_DOT -> listOf(s.dash, s.dash, 0f)
        }
        val out = FloatArray(marks.size * 2)
        for ((i, m) in marks.withIndex()) {
            out[i * 2] = (m - width).coerceAtLeast(0.01f)
            out[i * 2 + 1] = s.gap + width
        }
        return out
    }

    companion object {
        const val MIN_LENGTH = 1f
        const val MAX_LENGTH = 120f
        val DEFAULT = DashStyle()
    }
}

/** Stored by ordinal (page format v3+): only append new entries. */
enum class FillPattern(val title: String) {
    SOLID(tr("Сплошная", "Solid")),
    LINES(tr("Линии", "Lines")),
    CROSS(tr("Сетка", "Grid")),
    DOTS(tr("Точки", "Dots")),
    WAVES(tr("Волны", "Waves")),
    ZIGZAG(tr("Зигзаг", "Zigzag")),
    BRICKS(tr("Кирпич", "Brick")),
    CHECKER(tr("Шахматы", "Checkers")),

    /** Opaque, unlike [SOLID] (a light tint): arrowheads and anything filled in. Page format v10+. */
    INK(tr("Заливка", "Fill")),
}

/** How the lines of a pattern are drawn; stored by ordinal (page format v4+): only append. */
enum class LineType(val title: String) {
    SOLID(tr("Сплошная", "Solid")),
    DASHED(tr("Пунктир", "Dashed")),
    DASH_DOT(tr("Штрихпунктир", "Dash-dotted")),
    SOLID_DASH_DOT(tr("Сплошная + штрихпунктир", "Solid + dash-dot")),
    TWO_AND_GAP(tr("Две линии, пропуск", "Two lines, gap")),
    SOLID_DASHED(tr("Сплошная + пунктир", "Solid + dashed")),
}

/**
 * How a fill pattern is drawn, in page units: line [width], [spacing] between lines/dots,
 * [angle] in degrees (clockwise, the page's y axis points down), a [scale] applied to both
 * width and spacing, and [opacity] on top of the colour's own alpha.
 */
data class HatchStyle(
    val width: Float = 1.6f,
    val spacing: Float = 9f,
    val angle: Float = 45f,
    val scale: Float = 1f,
    val opacity: Float = 1f,
    val lineType: LineType = LineType.SOLID,
) {
    /** Values clamped to what the renderer can draw in reasonable time. */
    fun sane() = HatchStyle(
        width.coerceIn(MIN_WIDTH, MAX_WIDTH),
        spacing.coerceIn(MIN_SPACING, MAX_SPACING),
        ((angle % 180f) + 180f) % 180f,
        scale.coerceIn(MIN_SCALE, MAX_SCALE),
        opacity.coerceIn(MIN_OPACITY, 1f),
        lineType,
    )

    companion object {
        const val MIN_WIDTH = 0.3f
        const val MAX_WIDTH = 12f
        const val MIN_SPACING = 3f
        const val MAX_SPACING = 80f
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 4f
        const val MIN_OPACITY = 0.1f
        val DEFAULT = HatchStyle()
    }
}

/** A partial-eraser pass through a fill: polyline [points] (x,y pairs) with [radius]. */
class EraseCut(val points: FloatArray, val radius: Float)

/** A hatched region. [outline] is a closed polygon as x,y pairs in page coordinates. */
class PageFill(
    val outline: FloatArray,
    val pattern: FillPattern,
    val color: Int,
    val cuts: List<EraseCut> = emptyList(),
    val style: HatchStyle = HatchStyle.DEFAULT,
    val layer: Int = Layers.active,
) {
    val path: Path by lazy {
        Path().apply {
            moveTo(outline[0], outline[1])
            for (i in 2 until outline.size step 2) lineTo(outline[i], outline[i + 1])
            close()
        }
    }

    val bounds: RectF by lazy { RectF().also { path.computeBounds(it, true) } }

    /** Area removed by the partial eraser, as a fill path (null if nothing was erased). */
    val cutPath: Path? by lazy {
        if (cuts.isEmpty()) return@lazy null
        val result = Path()
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (cut in cuts) {
            val line = Path().apply {
                moveTo(cut.points[0], cut.points[1])
                for (i in 2 until cut.points.size step 2) lineTo(cut.points[i], cut.points[i + 1])
                // A single tap leaves a zero-length cut; give it length so the round cap shows.
                val p = cut.points
                if (p.size == 2 || (p.size == 4 && p[0] == p[2] && p[1] == p[3])) lineTo(p[0] + 0.01f, p[1])
            }
            paint.strokeWidth = cut.radius * 2
            val outline = Path()
            @Suppress("DEPRECATION")
            paint.getFillPath(line, outline)
            result.op(outline, Path.Op.UNION)
        }
        result
    }

    fun copy(
        outline: FloatArray = this.outline,
        color: Int = this.color,
        cuts: List<EraseCut> = this.cuts,
        pattern: FillPattern = this.pattern,
        style: HatchStyle = this.style,
        layer: Int = this.layer,
    ) = PageFill(outline, pattern, color, cuts, style, layer)
}

/**
 * A picture placed on the page: [file] is an image in the notebook folder (`img-<uuid>.jpg|png`),
 * shown in the rectangle [x], [y], [w], [h] (page units), turned by [rotation] degrees
 * (clockwise) around its centre. Identity matters, like strokes.
 */
class PageImage(
    val file: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val rotation: Float = 0f,
    val text: PageText? = null,
    val layer: Int = Layers.active,
) {
    /** Moved or resized; typed text grows and shrinks with its box (by the smaller side's factor). */
    fun moved(x: Float = this.x, y: Float = this.y, w: Float = this.w, h: Float = this.h, rotation: Float = this.rotation) =
        PageImage(file, x, y, w, h, rotation, text?.let { t -> if (w == this.w && h == this.h) t else t.copy(size = (t.size * minOf(w / this.w, h / this.h)).coerceIn(PageText.MIN_SIZE, PageText.MAX_SIZE)) }, layer)

    fun onLayer(layer: Int) = PageImage(file, x, y, w, h, rotation, text, layer)

    val isText get() = text != null

    companion object {
        val NAME = Regex("img-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)")

        /** "File" name of a text box: it has no file, the name only tells it apart. */
        const val TEXT_PREFIX = "txt-"

        fun textBox(text: PageText, x: Float, y: Float, w: Float, h: Float, rotation: Float = 0f, layer: Int = Layers.active) =
            PageImage(TEXT_PREFIX + java.util.UUID.randomUUID(), x, y, w, h, rotation, text, layer)
    }
}

/** Typed text on the page (page format v12): drawn in the box of its [PageImage], [size] page units high letters. */
data class PageText(val text: String, val size: Float, val color: Int) {
    companion object {
        const val MIN_SIZE = 3f
        const val MAX_SIZE = 400f
        const val MAX_CHARS = 20_000
    }
}

class PageContent(val strokes: List<PageStroke>, val fills: List<PageFill>, val images: List<PageImage> = emptyList()) {
    companion object {
        val EMPTY = PageContent(emptyList(), emptyList())
    }
}

/** Textures of textured brushes (the pencil); set from the app's resources at start. */
object InkTextures {
    @Volatile
    var store: TextureBitmapStore = TextureBitmapStore { null }

    /** For renderers made before the store is set: always asks the current one. */
    val delegate = TextureBitmapStore { id -> store.get(id) }
}

object InkBrushes {
    private val pen: BrushFamily by lazy { StockBrushes.pressurePen() }
    private val pencil: BrushFamily by lazy { StockBrushes.pencilUnstable }
    private val dashed: BrushFamily by lazy { StockBrushes.dashedLine() }
    private val highlighter: BrushFamily by lazy { StockBrushes.highlighter() }

    /** Even round line: a neon tube is the same width all along, whatever the pressure. */
    private val tube: BrushFamily by lazy { StockBrushes.marker() }

    /**
     * Even line for shapes, stamps and the ruler; rounded ends. Its points are taken as they
     * are (no smoothing), so corners stay sharp.
     */
    @OptIn(ExperimentalInkCustomBrushApi::class)
    private val line: BrushFamily by lazy { StockBrushes.marker().copy(inputModel = BrushFamily.EXPERIMENTAL_NAIVE_MODEL) }

    /**
     * Fountain pen: a flat nib held at 45°, so the line is broad or fine depending on the
     * direction of travel, plus some pressure response. The thin side is a quarter of the size.
     */
    @OptIn(ExperimentalInkCustomBrushApi::class)
    private val fountain: BrushFamily by lazy {
        val pressure = BrushBehavior(BrushBehavior.Source.NORMALIZED_PRESSURE, BrushBehavior.Target.SIZE_MULTIPLIER, 0f, 1f, 0.6f, 1.15f)
        val tip = BrushTip(1f, 0.25f, 0.6f, 0f, 0f, 45f, 0f, 0L, listOf(pressure))
        BrushFamily(tip, BrushPaint(), "inknotes-fountain")
    }

    /**
     * Size of an even line ([InkKind.LINE]) as thick as a pen line of [penSize] drawn at
     * [pressure]: the pen keeps its size up to a medium press and grows a quarter at full
     * (measured on the stock pressure pen).
     */
    fun lineWidth(penSize: Float, pressure: Float = 0.5f) = penSize * (1f + (pressure - 0.6f).coerceIn(0f, 0.4f) * 0.87f)

    private val liveNeon = HashMap<Int, BrushFamily>()

    /**
     * Neon line while it is being drawn: the tube in [color] with its light core ([core],
     * [coreShare] of the tube wide) on top as a second coat, as the stored line is drawn.
     */
    @OptIn(ExperimentalInkCustomBrushApi::class)
    fun neonLive(color: Int, core: Int, coreShare: Float, size: Float): Brush {
        val family = synchronized(liveNeon) {
            if (liveNeon.size > 32) liveNeon.clear()
            liveNeon.getOrPut(core) {
                val coat = tube.coats[0]
                val inner = BrushCoat(
                    coat.tip.copy(scaleX = coat.tip.scaleX * coreShare, scaleY = coat.tip.scaleY * coreShare),
                    BrushPaint(emptyList(), listOf(ColorFunction.ReplaceColor.withColorIntArgb(core))),
                )
                tube.copy(coats = listOf(coat, inner))
            }
        }
        return Brush.createWithColorIntArgb(family = family, colorIntArgb = color, size = size, epsilon = 0.05f)
    }

    fun create(kind: InkKind, color: Int, size: Float): Brush =
        Brush.createWithColorIntArgb(
            family = when (kind) {
                InkKind.PEN -> pen
                InkKind.NEON -> tube
                InkKind.HIGHLIGHTER -> highlighter
                InkKind.FOUNTAIN -> fountain
                InkKind.PENCIL -> pencil
                InkKind.DASHED -> dashed
                InkKind.LINE -> line
                InkKind.SPOILER -> tube
            },
            colorIntArgb = color,
            size = size,
            epsilon = 0.05f,
        )
}

/**
 * Page file: header, strokes (ink kind, colour, size, raw pointer inputs in androidx.ink's
 * compact encoding), then fills (v2+, with a [HatchStyle] since v3, its line type since v4).
 * v5 adds the fountain pen ink kind, v6 placed images (drawn under everything else), v7 their rotation, v8 the neon ink kind (written only by pages that have neon, so older apps still read the rest), v9 the pencil and dashed kinds (likewise), v10 even lines and the hint marker (likewise), v11 dash patterns of dashed lines (likewise, after the pictures), v12 typed text boxes (likewise, after the dash patterns). The brush family is not stored; it is rebuilt from
 * the kind, so stock brush upgrades apply to old notes.
 */
/** A page written by a newer app version (not a corrupt file). */
class NewerFormatException(message: String) : IOException(message)

/**
 * A page that holds more than a page file may (see [PageCodec.Limits]): it is not written, so
 * the copy on disk stays readable. [message] says what to do, in the words of the interface.
 */
class PageTooComplexException(message: String) : IOException(message)

object PageCodec {
    private const val MAGIC = 0x494E4B50 // "INKP"
    private const val VERSION = 12

    /** v2 pattern ordinals: SOLID, DIAGONAL, CROSS, HORIZONTAL, DOTS (all with fixed looks). */
    private val V2_PATTERNS = listOf(
        FillPattern.SOLID to HatchStyle(),
        FillPattern.LINES to HatchStyle(),
        FillPattern.CROSS to HatchStyle(),
        FillPattern.LINES to HatchStyle(angle = 0f),
        FillPattern.DOTS to HatchStyle(angle = 0f),
    )

    /**
     * Page files may come from other people's devices (shared notebooks): every length read
     * from the file is capped before allocating. [STRICT] is what a page file may hold: it is
     * checked on import and every write keeps to it, so what one device writes every other
     * one reads. [OWN] is wider, for pages already on this device written by versions that
     * did not check (before 0.39): such a page opens instead of being set aside as damaged.
     */
    class Limits(
        val elements: Int,
        val strokeBytes: Int,
        val floats: Int,
        /** Fills per page: each can cost up to [dev.inknotes.editor.Hatch] budget on every redraw. */
        val fills: Int,
        /** Point coordinates of all fills and cuts of a page together, and cuts in all. */
        val pageFloats: Long,
        val cuts: Int,
        val images: Int,
    ) {
        companion object {
            val STRICT = Limits(200_000, 8 * 1024 * 1024, 2_000_000, 2_000, 4_000_000L, 20_000, 500)
            val OWN = Limits(2_000_000, 64 * 1024 * 1024, 20_000_000, 20_000, 40_000_000L, 200_000, 5_000)
        }
    }

    /** Real coordinates stay within a few page heights; huge ones upset Skia's path math. */
    private const val MAX_COORD = 1_000_000f
    private const val MAX_BRUSH = 1000f

    private fun DataInputStream.readCount(max: Int): Int {
        val n = readInt()
        if (n < 0 || n > max) throw IOException("corrupt page file: length $n")
        return n
    }

    /**
     * What on [content] keeps it from being written within [Limits.STRICT], in words for the
     * user, or null if it fits. Counts only: numbers out of range are brought into range by
     * [write] itself. The size of each line's encoding is checked while writing.
     */
    fun tooComplex(content: PageContent, l: Limits = Limits.STRICT): String? {
        val pictures = content.images.count { !it.isText }
        val texts = content.images.size - pictures
        val fills = content.fills.filter { it.outline.size >= 6 }
        val cuts = fills.sumOf { f -> f.cuts.count { it.points.size >= 2 } }
        val floats = fills.sumOf { f -> f.outline.size.toLong() + f.cuts.sumOf { it.points.size.toLong() } }
        return when {
            content.strokes.size > l.elements -> tr("на странице больше ${l.elements} линий", "the page has more than ${l.elements} lines")
            fills.size > l.fills -> tr("на странице больше ${l.fills} штриховок", "the page has more than ${l.fills} hatchings")
            pictures > l.images -> tr("на странице больше ${l.images} картинок", "the page has more than ${l.images} pictures")
            texts > l.images -> tr("на странице больше ${l.images} текстовых блоков", "the page has more than ${l.images} text boxes")
            cuts > l.cuts || floats > l.pageFloats || fills.any { f -> f.outline.size > l.floats || f.cuts.any { it.points.size > l.floats } } ->
                tr("штриховки на странице слишком сложные (много стёртых мест)", "the hatchings on the page are too complex (many erased spots)")
            else -> null
        }
    }

    private fun finite(v: Float) = if (v.isFinite()) v.coerceIn(-MAX_COORD, MAX_COORD) else 0f

    private fun clampPoints(values: FloatArray): FloatArray {
        val even = if (values.size % 2 == 0) values else values.copyOf(values.size - 1)
        return FloatArray(even.size) { finite(even[it]) }
    }

    /**
     * Writes [content] so that [decode] with [Limits.STRICT] reads it back: coordinates and
     * sizes are brought into range, degenerate fills and cuts (invisible) are left out, and a
     * page over the counts throws [PageTooComplexException] before anything is written.
     */
    fun write(out: OutputStream, content: PageContent, limits: Limits = Limits.STRICT) {
        tooComplex(content, limits)?.let { throw PageTooComplexException(it) }
        val data = DataOutputStream(BufferedOutputStream(out))
        data.writeInt(MAGIC)
        data.writeInt(
            when {
                content.images.any { it.isText } || anyLayer(content) -> 12
                content.strokes.any { it.dash != null } -> 11
                content.strokes.any { it.kind == InkKind.LINE || it.kind == InkKind.SPOILER } || content.fills.any { it.pattern == FillPattern.INK } -> 10
                content.strokes.any { it.kind == InkKind.PENCIL || it.kind == InkKind.DASHED } -> 9
                content.strokes.any { it.kind == InkKind.NEON } -> 8
                else -> 7
            },
        )
        data.writeInt(content.strokes.size)
        val buffer = ByteArrayOutputStream()
        for (s in content.strokes) {
            data.writeByte(s.kind.ordinal)
            data.writeInt(s.stroke.brush.colorIntArgb)
            val size = s.stroke.brush.size
            data.writeFloat(if (size.isFinite() && size > 0f) size.coerceAtMost(MAX_BRUSH) else 1f)
            buffer.reset()
            s.stroke.inputs.encode(buffer)
            if (buffer.size() > limits.strokeBytes) throw PageTooComplexException(tr("на странице есть слишком длинная линия", "the page has a line that is too long"))
            data.writeInt(buffer.size())
            buffer.writeTo(data)
        }
        val fills = content.fills.filter { it.outline.size >= 6 }
        data.writeInt(fills.size)
        for (f in fills) {
            data.writeByte(f.pattern.ordinal)
            data.writeInt(f.color)
            data.writeFloat(f.style.width)
            data.writeFloat(f.style.spacing)
            data.writeFloat(f.style.angle)
            data.writeFloat(f.style.scale)
            data.writeFloat(f.style.opacity)
            data.writeByte(f.style.lineType.ordinal)
            writeFloats(data, clampPoints(f.outline))
            val cuts = f.cuts.filter { it.points.size >= 2 && it.radius.isFinite() && it.radius > 0f }
            data.writeInt(cuts.size)
            for (cut in cuts) {
                data.writeFloat(cut.radius)
                writeFloats(data, clampPoints(cut.points))
            }
        }
        val pictures = content.images.filter { !it.isText }
        data.writeInt(pictures.size)
        for (img in pictures) {
            data.writeUTF(img.file)
            writeRect(data, img)
        }
        // v11: dash patterns, by index of the line (only lines that have one).
        val dashed = content.strokes.withIndex().filter { it.value.dash != null }
        val texts = content.images.filter { it.isText }
        val version = if (texts.isNotEmpty() || anyLayer(content)) 12 else 0
        if (dashed.isNotEmpty() || version >= 12) {
            data.writeInt(dashed.size)
            for ((i, s) in dashed) {
                val d = s.dash!!
                data.writeInt(i)
                data.writeByte(d.pattern.ordinal)
                data.writeFloat(d.dash)
                data.writeFloat(d.gap)
            }
        }
        // v12: text boxes, in their place among the pictures (drawn after them), then layers.
        if (version >= 12) {
            data.writeInt(texts.size)
            for (img in texts) {
                val t = img.text!!
                data.writeUTF(t.text.take(PageText.MAX_CHARS))
                data.writeFloat(if (t.size.isFinite()) t.size.coerceIn(PageText.MIN_SIZE, PageText.MAX_SIZE) else PageText.MIN_SIZE)
                data.writeInt(t.color)
                writeRect(data, img)
            }
            // Layers other than the main one: kind (0 line, 1 fill, 2 picture or text), index, layer.
            val all = (content.strokes.withIndex().map { Triple(0, it.index, it.value.layer) } +
                fills.withIndex().map { Triple(1, it.index, it.value.layer) } +
                (pictures + texts).withIndex().map { Triple(2, it.index, it.value.layer) }).filter { it.third != 0 }
            data.writeInt(all.size)
            for ((kind, i, layer) in all) {
                data.writeByte(kind)
                data.writeInt(i)
                data.writeByte(layer)
            }
        }
        data.flush()
    }

    private fun writeRect(data: DataOutputStream, img: PageImage) {
        data.writeFloat(finite(img.x))
        data.writeFloat(finite(img.y))
        data.writeFloat(finite(img.w).coerceAtLeast(1f))
        data.writeFloat(finite(img.h).coerceAtLeast(1f))
        data.writeFloat(if (img.rotation.isFinite()) img.rotation else 0f)
    }

    private fun anyLayer(c: PageContent) = c.strokes.any { it.layer != 0 } || c.fills.any { it.layer != 0 } || c.images.any { it.layer != 0 }

    fun read(file: File, limits: Limits = Limits.STRICT): PageContent = decode(file.readBytes(), limits)

    /** A page from its file's bytes; any error here means the bytes are not a page. */
    fun decode(bytes: ByteArray, limits: Limits = Limits.STRICT): PageContent =
        DataInputStream(java.io.ByteArrayInputStream(bytes)).use { data ->
            if (data.readInt() != MAGIC) throw IOException("not a page file")
            val version = data.readInt()
            if (version > VERSION) throw NewerFormatException("page format v$version is newer than supported v$VERSION")
            // What the whole page may hold, however it is split up: every cut is a path operation on each redraw.
            val floatsLeft = longArrayOf(limits.pageFloats)
            var cutsLeft = limits.cuts
            val strokes = MutableList(data.readCount(limits.elements)) {
                val kind = InkKind.entries.getOrNull(data.readUnsignedByte()) ?: throw IOException("bad ink kind")
                val color = data.readInt()
                val size = data.readFloat()
                if (!size.isFinite() || size <= 0f || size > MAX_BRUSH) throw IOException("bad brush size")
                val bytes = ByteArray(data.readCount(limits.strokeBytes)).also(data::readFully)
                val inputs = StrokeInputBatch.decode(ByteArrayInputStream(bytes))
                PageStroke(kind, Stroke(InkBrushes.create(kind, color, size), inputs), layer = 0)
            }
            val fills = if (version < 2) {
                emptyList()
            } else {
                List(data.readCount(limits.fills)) {
                    val code = data.readUnsignedByte()
                    val pattern: FillPattern
                    var style = HatchStyle.DEFAULT
                    if (version < 3) {
                        val legacy = V2_PATTERNS.getOrNull(code) ?: throw IOException("bad pattern")
                        pattern = legacy.first
                        style = legacy.second
                    } else {
                        pattern = FillPattern.entries.getOrNull(code) ?: throw IOException("bad pattern")
                    }
                    val color = data.readInt()
                    if (version >= 3) {
                        val values = FloatArray(5) { data.readFloat() }
                        if (values.any { !it.isFinite() }) throw IOException("bad hatch style")
                        val lineType = if (version >= 4) {
                            LineType.entries.getOrNull(data.readUnsignedByte()) ?: throw IOException("bad line type")
                        } else {
                            LineType.SOLID
                        }
                        style = HatchStyle(values[0], values[1], values[2], values[3], values[4], lineType).sane()
                    }
                    val outline = readFloats(data, floatsLeft, limits.floats)
                    if (outline.size < 6) throw IOException("fill outline needs 3 points")
                    val cutCount = data.readCount(limits.elements)
                    cutsLeft -= cutCount
                    if (cutsLeft < 0) throw IOException("too many erase cuts")
                    val cuts = List(cutCount) {
                        val radius = data.readFloat()
                        val points = readFloats(data, floatsLeft, limits.floats)
                        if (points.size < 2 || !radius.isFinite() || radius <= 0f) throw IOException("bad erase cut")
                        EraseCut(points, radius)
                    }
                    PageFill(outline, pattern, color, cuts, style, layer = 0)
                }
            }
            val images = if (version < 6) {
                emptyList()
            } else {
                List(data.readCount(limits.images)) {
                    // The name becomes a file path: accept only names the app generates.
                    val file = data.readUTF()
                    if (!PageImage.NAME.matches(file)) throw IOException("bad image name")
                    val v = FloatArray(4) { data.readFloat() }
                    if (v.any { !it.isFinite() || it > MAX_COORD || it < -MAX_COORD } || v[2] < 1f || v[3] < 1f) {
                        throw IOException("bad image rect")
                    }
                    val rotation = if (version >= 7) data.readFloat() else 0f
                    if (!rotation.isFinite()) throw IOException("bad image rotation")
                    PageImage(file, v[0], v[1], v[2], v[3], ((rotation % 360f) + 360f) % 360f, layer = 0)
                }
            }
            if (version >= 11) {
                repeat(data.readCount(limits.elements)) {
                    val i = data.readInt()
                    val pattern = DashPattern.entries.getOrNull(data.readUnsignedByte()) ?: throw IOException("bad dash pattern")
                    val dash = data.readFloat()
                    val gap = data.readFloat()
                    if (i !in strokes.indices || !dash.isFinite() || !gap.isFinite()) throw IOException("bad dash style")
                    val s = strokes[i]
                    if (s.kind == InkKind.DASHED) strokes[i] = PageStroke(s.kind, s.stroke, DashStyle(pattern, dash, gap).sane(), s.layer)
                }
            }
            val texts = if (version < 12) {
                emptyList()
            } else {
                List(data.readCount(limits.images)) {
                    val text = data.readUTF()
                    val size = data.readFloat()
                    val color = data.readInt()
                    val v = FloatArray(5) { data.readFloat() }
                    if (!size.isFinite() || size < PageText.MIN_SIZE || size > PageText.MAX_SIZE) throw IOException("bad text size")
                    if (v.any { !it.isFinite() || it > MAX_COORD || it < -MAX_COORD } || v[2] < 1f || v[3] < 1f) throw IOException("bad text box")
                    PageImage.textBox(PageText(text, size, color), v[0], v[1], v[2], v[3], ((v[4] % 360f) + 360f) % 360f, layer = 0)
                }
            }
            val fillList = fills.toMutableList()
            val imageList = (images + texts).toMutableList()
            if (version >= 12) {
                repeat(data.readCount(limits.elements)) {
                    val kind = data.readUnsignedByte()
                    val i = data.readInt()
                    val layer = data.readUnsignedByte()
                    if (layer >= Layers.MAX) throw IOException("bad layer")
                    when (kind) {
                        0 -> if (i in strokes.indices) strokes[i] = strokes[i].onLayer(layer) else throw IOException("bad layer index")
                        1 -> if (i in fillList.indices) fillList[i] = fillList[i].copy(layer = layer) else throw IOException("bad layer index")
                        2 -> if (i in imageList.indices) imageList[i] = imageList[i].onLayer(layer) else throw IOException("bad layer index")
                        else -> throw IOException("bad layer kind")
                    }
                }
            }
            PageContent(strokes, fillList, imageList)
        }

    private fun writeFloats(data: DataOutputStream, values: FloatArray) {
        data.writeInt(values.size)
        for (v in values) data.writeFloat(v)
    }

    private fun readFloats(data: DataInputStream, left: LongArray, max: Int): FloatArray {
        val n = data.readCount(max)
        left[0] -= n
        if (left[0] < 0) throw IOException("page too complex")
        val values = FloatArray(n) { data.readFloat() }
        if (values.size % 2 != 0 || values.any { !it.isFinite() || it > MAX_COORD || it < -MAX_COORD }) throw IOException("corrupt point list")
        return values
    }
}
