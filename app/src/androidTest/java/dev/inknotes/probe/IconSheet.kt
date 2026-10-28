package dev.inknotes.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.ui.InkIcons
import dev.inknotes.ui.UiStyle
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: draws every icon of [InkIcons] in every interface style into one sheet
 * (icons-sheet.png in the app's external files dir), to look the whole set over.
 */
@RunWith(AndroidJUnit4::class)
class IconSheet {
    @Test
    fun draw() {
        val icons = InkIcons::class.java.declaredMethods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == ImageVector::class.java }
            .sortedBy { it.name }
            .mapNotNull { m -> runCatching { m.invoke(InkIcons) as ImageVector }.getOrNull()?.let { m.name.removePrefix("get") to it } }
        val styles = UiStyle.entries
        val cell = 96
        val label = 230
        val bmp = Bitmap.createBitmap(label + cell * styles.size, cell * (icons.size + 1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Color.BLACK }
        for ((j, st) in styles.withIndex()) c.drawText(st.name.take(7), label + j * cell + 4f, cell * 0.6f, text)
        for ((i, pair) in icons.withIndex()) {
            val (name, icon) = pair
            val y = (i + 1) * cell
            c.drawText(name, 6f, y + cell * 0.6f, text)
            for ((j, st) in styles.withIndex()) {
                val v = InkIcons.styled(icon, st, androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color(0xFFB8862B))
                c.save()
                c.translate(label + j * cell + 12f, y + 12f)
                c.scale(3f, 3f)
                drawGroup(c, v.root)
                c.restore()
            }
            c.drawLine(0f, y.toFloat(), bmp.width.toFloat(), y.toFloat(), Paint().apply { color = 0xFFDDDDDD.toInt() })
        }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.getExternalFilesDir(null), "icons-sheet.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun drawGroup(c: Canvas, g: VectorGroup) {
        c.save()
        c.translate(g.translationX, g.translationY)
        c.rotate(g.rotation, g.pivotX, g.pivotY)
        c.scale(g.scaleX, g.scaleY, g.pivotX, g.pivotY)
        for (n in g) {
            when (n) {
                is VectorGroup -> drawGroup(c, n)
                is VectorPath -> {
                    val path = PathParser().addPathNodes(n.pathData).toPath().asAndroidPath()
                    (n.fill as? SolidColor)?.let { b ->
                        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = b.value.copy(alpha = b.value.alpha * n.fillAlpha).toArgb() })
                    }
                    (n.stroke as? SolidColor)?.let { b ->
                        c.drawPath(
                            path,
                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                style = Paint.Style.STROKE
                                color = b.value.copy(alpha = b.value.alpha * n.strokeAlpha).toArgb()
                                strokeWidth = n.strokeLineWidth
                                strokeCap = if (n.strokeLineCap == StrokeCap.Square) Paint.Cap.SQUARE else if (n.strokeLineCap == StrokeCap.Butt) Paint.Cap.BUTT else Paint.Cap.ROUND
                                strokeJoin = if (n.strokeLineJoin == StrokeJoin.Miter) Paint.Join.MITER else Paint.Join.ROUND
                            },
                        )
                    }
                }
            }
        }
        c.restore()
    }
}
