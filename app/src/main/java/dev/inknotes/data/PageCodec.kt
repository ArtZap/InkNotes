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

/** Identity matters: selection and undo track elements by reference, so no data class. */
class PageStroke(val kind: InkKind, val stroke: Stroke)

/** Stored by ordinal (page format v3+): only append new entries. */
enum class FillPattern(val title: String) {
    SOLID("Сплошная"),
    LINES("Линии"),
    CROSS("Сетка"),
    DOTS("Точки"),
    WAVES("Волны"),
    ZIGZAG("Зигзаг"),
    BRICKS("Кирпич"),
    CHECKER("Шахматы"),

    /** Opaque, unlike [SOLID] (a light tint): arrowheads and anything filled in. Page format v10+. */
    INK("Заливка"),
}

/** How the lines of a pattern are drawn; stored by ordinal (page format v4+): only append. */
enum class LineType(val title: String) {
    SOLID("Сплошная"),
    DASHED("Пунктир"),
    DASH_DOT("Штрихпунктир"),
    SOLID_DASH_DOT("Сплошная + штрихпунктир"),
    TWO_AND_GAP("Две линии, пропуск"),
    SOLID_DASHED("Сплошная + пунктир"),
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
    ) = PageFill(outline, pattern, color, cuts, style)
}

/**
 * A picture placed on the page: [file] is an image in the notebook folder (`img-<uuid>.jpg|png`),
 * shown in the rectangle [x], [y], [w], [h] (page units), turned by [rotation] degrees
 * (clockwise) around its centre. Identity matters, like strokes.
 */
class PageImage(val file: String, val x: Float, val y: Float, val w: Float, val h: Float, val rotation: Float = 0f) {
    fun moved(x: Float = this.x, y: Float = this.y, w: Float = this.w, h: Float = this.h, rotation: Float = this.rotation) =
        PageImage(file, x, y, w, h, rotation)

    companion object {
        val NAME = Regex("img-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png)")
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
 * v5 adds the fountain pen ink kind, v6 placed images (drawn under everything else), v7 their rotation, v8 the neon ink kind (written only by pages that have neon, so older apps still read the rest), v9 the pencil and dashed kinds (likewise), v10 even lines and the hint marker (likewise). The brush family is not stored; it is rebuilt from
 * the kind, so stock brush upgrades apply to old notes.
 */
/** A page written by a newer app version (not a corrupt file). */
class NewerFormatException(message: String) : IOException(message)

object PageCodec {
    private const val MAGIC = 0x494E4B50 // "INKP"
    private const val VERSION = 10

    /** v2 pattern ordinals: SOLID, DIAGONAL, CROSS, HORIZONTAL, DOTS (all with fixed looks). */
    private val V2_PATTERNS = listOf(
        FillPattern.SOLID to HatchStyle(),
        FillPattern.LINES to HatchStyle(),
        FillPattern.CROSS to HatchStyle(),
        FillPattern.LINES to HatchStyle(angle = 0f),
        FillPattern.DOTS to HatchStyle(angle = 0f),
    )

    // Page files may come from other people's devices (shared notebooks): cap every
    // length read from the file before allocating.
    private const val MAX_ELEMENTS = 200_000
    private const val MAX_STROKE_BYTES = 8 * 1024 * 1024
    private const val MAX_FLOATS = 2_000_000

    /** Fills per page: each can cost up to [dev.inknotes.editor.Hatch] budget on every redraw. */
    private const val MAX_FILLS = 2_000

    /** Point coordinates of all fills and cuts of a page together, and cuts in all. */
    private const val MAX_PAGE_FLOATS = 4_000_000L
    private const val MAX_PAGE_CUTS = 20_000

    /** Real coordinates stay within a few page heights; huge ones upset Skia's path math. */
    private const val MAX_COORD = 1_000_000f
    private const val MAX_IMAGES = 500

    private fun DataInputStream.readCount(max: Int): Int {
        val n = readInt()
        if (n < 0 || n > max) throw IOException("corrupt page file: length $n")
        return n
    }

    fun write(out: OutputStream, content: PageContent) {
        val data = DataOutputStream(BufferedOutputStream(out))
        data.writeInt(MAGIC)
        data.writeInt(
            when {
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
            data.writeFloat(s.stroke.brush.size)
            buffer.reset()
            s.stroke.inputs.encode(buffer)
            data.writeInt(buffer.size())
            buffer.writeTo(data)
        }
        data.writeInt(content.fills.size)
        for (f in content.fills) {
            data.writeByte(f.pattern.ordinal)
            data.writeInt(f.color)
            data.writeFloat(f.style.width)
            data.writeFloat(f.style.spacing)
            data.writeFloat(f.style.angle)
            data.writeFloat(f.style.scale)
            data.writeFloat(f.style.opacity)
            data.writeByte(f.style.lineType.ordinal)
            writeFloats(data, f.outline)
            data.writeInt(f.cuts.size)
            for (cut in f.cuts) {
                data.writeFloat(cut.radius)
                writeFloats(data, cut.points)
            }
        }
        data.writeInt(content.images.size)
        for (img in content.images) {
            data.writeUTF(img.file)
            data.writeFloat(img.x)
            data.writeFloat(img.y)
            data.writeFloat(img.w)
            data.writeFloat(img.h)
            data.writeFloat(img.rotation)
        }
        data.flush()
    }

    fun read(file: File): PageContent =
        DataInputStream(BufferedInputStream(file.inputStream())).use { data ->
            if (data.readInt() != MAGIC) throw IOException("not a page file: $file")
            val version = data.readInt()
            if (version > VERSION) throw NewerFormatException("page format v$version is newer than supported v$VERSION")
            // What the whole page may hold, however it is split up: every cut is a path operation on each redraw.
            val floatsLeft = longArrayOf(MAX_PAGE_FLOATS)
            var cutsLeft = MAX_PAGE_CUTS
            val strokes = List(data.readCount(MAX_ELEMENTS)) {
                val kind = InkKind.entries.getOrNull(data.readUnsignedByte()) ?: throw IOException("bad ink kind")
                val color = data.readInt()
                val size = data.readFloat()
                if (!size.isFinite() || size <= 0f || size > 1000f) throw IOException("bad brush size")
                val bytes = ByteArray(data.readCount(MAX_STROKE_BYTES)).also(data::readFully)
                val inputs = StrokeInputBatch.decode(ByteArrayInputStream(bytes))
                PageStroke(kind, Stroke(InkBrushes.create(kind, color, size), inputs))
            }
            val fills = if (version < 2) {
                emptyList()
            } else {
                List(data.readCount(MAX_FILLS)) {
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
                    val outline = readFloats(data, floatsLeft)
                    if (outline.size < 6) throw IOException("fill outline needs 3 points")
                    val cutCount = data.readCount(MAX_ELEMENTS)
                    cutsLeft -= cutCount
                    if (cutsLeft < 0) throw IOException("too many erase cuts")
                    val cuts = List(cutCount) {
                        val radius = data.readFloat()
                        val points = readFloats(data, floatsLeft)
                        if (points.size < 2 || !radius.isFinite() || radius <= 0f) throw IOException("bad erase cut")
                        EraseCut(points, radius)
                    }
                    PageFill(outline, pattern, color, cuts, style)
                }
            }
            val images = if (version < 6) {
                emptyList()
            } else {
                List(data.readCount(MAX_IMAGES)) {
                    // The name becomes a file path: accept only names the app generates.
                    val file = data.readUTF()
                    if (!PageImage.NAME.matches(file)) throw IOException("bad image name")
                    val v = FloatArray(4) { data.readFloat() }
                    if (v.any { !it.isFinite() || it > MAX_COORD || it < -MAX_COORD } || v[2] < 1f || v[3] < 1f) {
                        throw IOException("bad image rect")
                    }
                    val rotation = if (version >= 7) data.readFloat() else 0f
                    if (!rotation.isFinite()) throw IOException("bad image rotation")
                    PageImage(file, v[0], v[1], v[2], v[3], ((rotation % 360f) + 360f) % 360f)
                }
            }
            PageContent(strokes, fills, images)
        }

    private fun writeFloats(data: DataOutputStream, values: FloatArray) {
        data.writeInt(values.size)
        for (v in values) data.writeFloat(v)
    }

    private fun readFloats(data: DataInputStream, left: LongArray): FloatArray {
        val n = data.readCount(MAX_FLOATS)
        left[0] -= n
        if (left[0] < 0) throw IOException("page too complex")
        val values = FloatArray(n) { data.readFloat() }
        if (values.size % 2 != 0 || values.any { !it.isFinite() || it > MAX_COORD || it < -MAX_COORD }) throw IOException("corrupt point list")
        return values
    }
}
