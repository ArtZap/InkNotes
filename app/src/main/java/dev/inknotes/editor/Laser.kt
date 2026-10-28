package dev.inknotes.editor

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock

/**
 * The pointer's trail: a red glowing line that fades a moment after it is drawn and is never
 * saved. Points are kept in world units, so the trail stays on the paper while it scrolls.
 */
internal class Laser(private val density: Float) {

    private class Trail {
        var xs = FloatArray(256)
        var ys = FloatArray(256)
        var ts = LongArray(256)
        var n = 0

        fun add(x: Float, y: Float, t: Long) {
            // Held long, the faded start is dropped: the trail does not grow without end.
            if (n == xs.size && n >= 1024) {
                var keep = 0
                while (keep < n && t - ts[keep] > FADE_MS) keep++
                keep = minOf(keep, n - 1)
                if (keep > 0) {
                    System.arraycopy(xs, keep, xs, 0, n - keep)
                    System.arraycopy(ys, keep, ys, 0, n - keep)
                    System.arraycopy(ts, keep, ts, 0, n - keep)
                    n -= keep
                }
            }
            if (n == xs.size) {
                xs = xs.copyOf(n * 2)
                ys = ys.copyOf(n * 2)
                ts = ts.copyOf(n * 2)
            }
            xs[n] = x
            ys[n] = y
            ts[n] = t
            n++
        }
    }

    private val trails = ArrayList<Trail>()
    private var current: Trail? = null
    private val pt = FloatArray(4)

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = GLOW
        strokeWidth = 9f * density
        maskFilter = BlurMaskFilter(6f * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = CORE
        strokeWidth = 3f * density
    }

    val active get() = trails.isNotEmpty()

    fun start(x: Float, y: Float) {
        current = Trail().also { trails += it }
        add(x, y)
    }

    fun add(x: Float, y: Float) {
        current?.add(x, y, SystemClock.uptimeMillis())
    }

    fun end() {
        current = null
    }

    /** Draws what is still lit (view coordinates through [worldToView]); true while anything is. */
    fun draw(canvas: Canvas, worldToView: Matrix): Boolean {
        val now = SystemClock.uptimeMillis()
        trails.removeAll { t -> t !== current && (t.n == 0 || now - t.ts[t.n - 1] > FADE_MS) }
        for (t in trails) {
            for (i in 1 until t.n) {
                val age = now - t.ts[i]
                if (age > FADE_MS) continue
                val a = 1f - age.toFloat() / FADE_MS
                pt[0] = t.xs[i - 1]
                pt[1] = t.ys[i - 1]
                pt[2] = t.xs[i]
                pt[3] = t.ys[i]
                worldToView.mapPoints(pt)
                glow.alpha = (0x90 * a).toInt()
                canvas.drawLine(pt[0], pt[1], pt[2], pt[3], glow)
                core.alpha = (0xFF * a).toInt()
                canvas.drawLine(pt[0], pt[1], pt[2], pt[3], core)
            }
            // The spot where the pointer is now.
            if (t === current && t.n > 0) {
                pt[0] = t.xs[t.n - 1]
                pt[1] = t.ys[t.n - 1]
                worldToView.mapPoints(pt, 0, pt, 0, 1)
                glow.alpha = 0xB0
                canvas.drawPoint(pt[0], pt[1], glow)
                core.alpha = 0xFF
                canvas.drawPoint(pt[0], pt[1], core)
            }
        }
        return trails.isNotEmpty()
    }

    private companion object {
        const val FADE_MS = 900L
        const val GLOW = 0xFFFF2A2A.toInt()
        const val CORE = 0xFFFFE0E0.toInt()
    }
}
