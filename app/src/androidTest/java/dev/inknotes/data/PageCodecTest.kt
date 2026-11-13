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

    /** A dashed line's pattern survives a round trip; only pages that have one are v11. */
    @Test
    fun dashStyleRoundTrip() {
        val batch = androidx.ink.strokes.MutableStrokeInputBatch()
        for (i in 0..10) batch.add(androidx.ink.brush.InputToolType.STYLUS, i * 10f, 50f, i * 8L)
        fun stroke(kind: InkKind, dash: DashStyle? = null) = PageStroke(kind, androidx.ink.strokes.Stroke(InkBrushes.create(kind, Color.BLACK, 3f), batch), dash)
        fun version(f: File) = java.io.DataInputStream(f.inputStream()).use { it.readInt(); it.readInt() }
        val style = DashStyle(DashPattern.DASH_DOT, dash = 20f, gap = 7f)
        val file = File(dir, "dash.ink")
        file.outputStream().use { PageCodec.write(it, PageContent(listOf(stroke(InkKind.PEN), stroke(InkKind.DASHED, style), stroke(InkKind.DASHED)), emptyList())) }
        assertEquals(11, version(file))
        val read = PageCodec.read(file).strokes
        assertEquals(null, read[0].dash)
        assertEquals(style, read[1].dash)
        // Dashed lines of older notes keep the stock dashes.
        assertEquals(null, read[2].dash)
    }

    /** Text boxes and layers survive a round trip as v12; pages without them keep their old version. */
    @Test
    fun textAndLayersRoundTrip() {
        val batch = androidx.ink.strokes.MutableStrokeInputBatch()
        for (i in 0..10) batch.add(androidx.ink.brush.InputToolType.STYLUS, i * 10f, 50f, i * 8L)
        fun stroke(layer: Int, dash: DashStyle? = null) =
            PageStroke(if (dash != null) InkKind.DASHED else InkKind.PEN, androidx.ink.strokes.Stroke(InkBrushes.create(InkKind.PEN, Color.BLACK, 3f), batch), dash, layer)
        fun version(f: File) = java.io.DataInputStream(f.inputStream()).use { it.readInt(); it.readInt() }
        val text = PageImage.textBox(PageText("Теорема 1\nформула", 20f, Color.BLUE), 40f, 60f, 300f, 50f, layer = 2)
        val style = DashStyle(DashPattern.DOT, 10f, 5f)
        val content = PageContent(
            listOf(stroke(0), stroke(1), stroke(3, style)),
            listOf(PageFill(square, FillPattern.LINES, Color.RED, layer = 1)),
            listOf(text),
        )
        val file = File(dir, "v12.ink")
        file.outputStream().use { PageCodec.write(it, content) }
        assertEquals(12, version(file))
        val read = PageCodec.read(file)
        assertEquals(listOf(0, 1, 3), read.strokes.map { it.layer })
        assertEquals(style, read.strokes[2].dash)
        assertEquals(1, read.fills.single().layer)
        val t = read.images.single()
        assertEquals("Теорема 1\nформула", t.text?.text)
        assertEquals(2, t.layer)
        assertEquals(300f, t.w)
        // A page on the main layer only, without text, stays as it was.
        val plain = File(dir, "plain12.ink")
        plain.outputStream().use { PageCodec.write(it, PageContent(listOf(stroke(0)), emptyList())) }
        assertEquals(7, version(plain))
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

    /**
     * What is written reads back with the strict limits (those of shared files): numbers out of
     * range are brought into range, too many elements are refused before anything is written.
     */
    @Test
    fun writingKeepsToTheLimitsOfReading() {
        val file = File(dir, "limits.ink")
        val far = PageImage("img-00000000-0000-0000-0000-000000000000.png", 5e7f, Float.NaN, 0.5f, 200f)
        val flat = PageFill(floatArrayOf(0f, 0f, 1f), FillPattern.SOLID, Color.RED)
        val wild = PageFill(floatArrayOf(0f, 0f, 1e9f, 0f, Float.POSITIVE_INFINITY, 100f), FillPattern.SOLID, Color.RED, layer = 1)
        file.outputStream().use { PageCodec.write(it, PageContent(emptyList(), listOf(flat, wild), listOf(far))) }
        val read = PageCodec.read(file)
        // The fill with fewer than three points is invisible and left out; the other keeps its layer.
        assertEquals(1, read.fills.size)
        assertEquals(1, read.fills[0].layer)
        assertTrue(read.fills[0].outline.all { it.isFinite() && it <= 1_000_000f })
        assertTrue(read.images.single().w >= 1f)

        val tooMany = PageContent(emptyList(), List(2001) { PageFill(square, FillPattern.SOLID, Color.RED) })
        assertTrue(PageCodec.tooComplex(tooMany) != null)
        val before = file.readBytes()
        try {
            file.outputStream().use { PageCodec.write(it, tooMany) }
            throw AssertionError("a page over the limits was written")
        } catch (e: PageTooComplexException) {
            // Refused before the first byte: what the stream got is empty.
        }
        assertEquals(0L, file.length())
        file.writeBytes(before)
        assertEquals(1, PageCodec.read(file).fills.size)
    }

    /** A page over the strict limits, written by an older version, opens on this device. */
    @Test
    fun ownOldPagesOverTheLimitsStillOpen() {
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).use { d ->
            d.writeInt(0x494E4B50)
            d.writeInt(7)
            d.writeInt(0) // strokes
            d.writeInt(2500) // fills: over the strict 2000
            repeat(2500) {
                d.writeByte(FillPattern.SOLID.ordinal)
                d.writeInt(Color.RED)
                repeat(5) { d.writeFloat(1f) }
                d.writeByte(0)
                d.writeInt(square.size)
                square.forEach(d::writeFloat)
                d.writeInt(0)
            }
            d.writeInt(0) // pictures
        }
        try {
            PageCodec.decode(bytes.toByteArray())
            throw AssertionError("strict limits let it through")
        } catch (e: IOException) {
            // As for a shared file.
        }
        assertEquals(2500, PageCodec.decode(bytes.toByteArray(), PageCodec.Limits.OWN).fills.size)
    }
}
