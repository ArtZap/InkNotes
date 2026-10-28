package dev.inknotes.data

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** Page files: hatch styles survive a round trip, v2 files still open, bad styles are rejected. */
@RunWith(AndroidJUnit4::class)
class PageCodecTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "codec-test").apply { mkdirs() }
    private val square = floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)

    @Test
    fun hatchStyleRoundTrip() {
        val style = HatchStyle(width = 3.5f, spacing = 22f, angle = 120f, scale = 2f, opacity = 0.4f, lineType = LineType.SOLID_DASH_DOT)
        val file = File(dir, "v3.ink")
        file.outputStream().use { PageCodec.write(it, PageContent(emptyList(), listOf(PageFill(square, FillPattern.BRICKS, Color.RED, style = style)))) }
        val fill = PageCodec.read(file).fills.single()
        assertEquals(FillPattern.BRICKS, fill.pattern)
        assertEquals(style, fill.style)
    }

    /** Neon survives a round trip; pages without it stay readable by v7 apps. */
    @Test
    fun neonInkRoundTripAndVersion() {
        val batch = androidx.ink.strokes.MutableStrokeInputBatch()
        for (i in 0..10) batch.add(androidx.ink.brush.InputToolType.STYLUS, i * 10f, 50f, i * 8L)
        fun stroke(kind: InkKind) = PageStroke(kind, androidx.ink.strokes.Stroke(InkBrushes.create(kind, 0xFFFF2BD6.toInt(), 8f), batch))
        fun version(f: File) = java.io.DataInputStream(f.inputStream()).use { it.readInt(); it.readInt() }
        val neon = File(dir, "neon.ink")
        neon.outputStream().use { PageCodec.write(it, PageContent(listOf(stroke(InkKind.PEN), stroke(InkKind.NEON)), emptyList())) }
        assertEquals(8, version(neon))
        val read = PageCodec.read(neon).strokes
        assertEquals(listOf(InkKind.PEN, InkKind.NEON), read.map { it.kind })
        assertEquals(8f, read[1].stroke.brush.size)
        val plain = File(dir, "plain.ink")
        plain.outputStream().use { PageCodec.write(it, PageContent(listOf(stroke(InkKind.PEN)), emptyList())) }
        assertEquals(7, version(plain))
        val drawn = File(dir, "pencil.ink")
        drawn.outputStream().use { PageCodec.write(it, PageContent(listOf(stroke(InkKind.PENCIL), stroke(InkKind.DASHED), stroke(InkKind.NEON)), emptyList())) }
        assertEquals(9, version(drawn))
        assertEquals(listOf(InkKind.PENCIL, InkKind.DASHED, InkKind.NEON), PageCodec.read(drawn).strokes.map { it.kind })
    }

    /** v2 stored SOLID, DIAGONAL, CROSS, HORIZONTAL, DOTS by ordinal with fixed looks. */
    @Test
    fun readsVersion2Patterns() {
        val file = File(dir, "v2.ink")
        DataOutputStream(file.outputStream()).use { d ->
            d.writeInt(0x494E4B50)
            d.writeInt(2)
            d.writeInt(0) // strokes
            d.writeInt(5) // fills
            for (code in 0 until 5) {
                d.writeByte(code)
                d.writeInt(Color.BLACK)
                d.writeInt(square.size)
                for (v in square) d.writeFloat(v)
                d.writeInt(0) // cuts
            }
        }
        val fills = PageCodec.read(file).fills
        assertEquals(listOf(FillPattern.SOLID, FillPattern.LINES, FillPattern.CROSS, FillPattern.LINES, FillPattern.DOTS), fills.map { it.pattern })
        assertEquals(45f, fills[1].style.angle) // old diagonal
        assertEquals(0f, fills[3].style.angle) // old horizontal lines
        assertEquals(9f, fills[1].style.spacing)
    }

    @Test
    fun rejectsNonFiniteStylesAndClampsExtremes() {
        fun fileWith(values: FloatArray) = File(dir, "bad.ink").also { f ->
            DataOutputStream(f.outputStream()).use { d ->
                d.writeInt(0x494E4B50)
                d.writeInt(3)
                d.writeInt(0)
                d.writeInt(1)
                d.writeByte(FillPattern.DOTS.ordinal)
                d.writeInt(Color.BLACK)
                for (v in values) d.writeFloat(v)
                d.writeInt(square.size)
                for (v in square) d.writeFloat(v)
                d.writeInt(0)
            }
        }
        assertTrue(runCatching { PageCodec.read(fileWith(floatArrayOf(1f, Float.NaN, 0f, 1f, 1f))) }.exceptionOrNull() is IOException)
        // A spacing of 0.0001 on a huge area would mean billions of dots: clamped on read.
        val style = PageCodec.read(fileWith(floatArrayOf(1e9f, 0.0001f, -30f, 1000f, 7f))).fills.single().style
        assertEquals(HatchStyle.MAX_WIDTH, style.width)
        assertEquals(HatchStyle.MIN_SPACING, style.spacing)
        assertEquals(150f, style.angle)
        assertEquals(HatchStyle.MAX_SCALE, style.scale)
        assertEquals(1f, style.opacity)
    }

    /** Absurd (finite) coordinates from a shared file are rejected before they reach Skia. */
    @Test
    fun rejectsHugeCoordinates() {
        val file = File(dir, "huge.ink")
        DataOutputStream(file.outputStream()).use { d ->
            d.writeInt(0x494E4B50)
            d.writeInt(3)
            d.writeInt(0)
            d.writeInt(1)
            d.writeByte(FillPattern.LINES.ordinal)
            d.writeInt(Color.BLACK)
            for (v in floatArrayOf(1.6f, 9f, 45f, 1f, 1f)) d.writeFloat(v)
            val outline = floatArrayOf(0f, 0f, 3e38f, 0f, 3e38f, 3e38f)
            d.writeInt(outline.size)
            for (v in outline) d.writeFloat(v)
            d.writeInt(0)
        }
        assertTrue(runCatching { PageCodec.read(file) }.exceptionOrNull() is IOException)
    }

    /** The fountain nib is flat at 45°: the same stroke is broad in one diagonal, fine in the other. */
    @Test
    fun fountainPenWidthDependsOnDirection() {
        fun diagonal(dy: Float): PageStroke {
            val batch = androidx.ink.strokes.MutableStrokeInputBatch()
            for (i in 0..30) batch.add(androidx.ink.brush.InputToolType.STYLUS, 100f + i * 10f, 500f + i * 10f * dy, i * 8L, pressure = 0.5f)
            return PageStroke(InkKind.FOUNTAIN, androidx.ink.strokes.Stroke(InkBrushes.create(InkKind.FOUNTAIN, Color.BLACK, 12f), batch))
        }
        // Inked pixels when rendered: an axis-aligned box can't tell broad from fine on a diagonal.
        fun area(s: PageStroke): Int {
            val bitmap = android.graphics.Bitmap.createBitmap(600, 1000, android.graphics.Bitmap.Config.ARGB_8888)
            androidx.ink.rendering.android.canvas.CanvasStrokeRenderer.create()
                .draw(android.graphics.Canvas(bitmap), s.stroke, android.graphics.Matrix())
            val px = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            return px.count { Color.alpha(it) > 128 }
        }
        val down = diagonal(1f)
        val up = diagonal(-1f)
        val a = area(down)
        val b = area(up)
        assertTrue("one diagonal must be clearly broader: $a vs $b", maxOf(a, b) > 1.8f * minOf(a, b))

        val file = File(dir, "fountain.ink")
        file.outputStream().use { PageCodec.write(it, PageContent(listOf(down), emptyList())) }
        assertEquals(InkKind.FOUNTAIN, PageCodec.read(file).strokes.single().kind)
    }

    @Test
    fun imagesRoundTripAndBadNamesRejected() {
        val name = "img-${java.util.UUID.randomUUID()}.png"
        val file = File(dir, "images.ink")
        file.outputStream().use { PageCodec.write(it, PageContent(emptyList(), emptyList(), listOf(PageImage(name, 10f, 20f, 300f, 200f, rotation = 30f)))) }
        val img = PageCodec.read(file).images.single()
        assertEquals(name, img.file)
        assertEquals(300f, img.w)
        assertEquals(30f, img.rotation)

        // A path instead of a generated name must never reach the file system.
        file.outputStream().use { PageCodec.write(it, PageContent(emptyList(), emptyList(), listOf(PageImage("../../evil.png", 0f, 0f, 10f, 10f)))) }
        assertTrue(runCatching { PageCodec.read(file) }.exceptionOrNull() is IOException)
    }
}
