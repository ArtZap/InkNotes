package dev.inknotes.probe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor

/**
 * Drawing surface for the probe: wet ink goes through [InProgressStrokesView]
 * (front-buffered, low latency), finished strokes are drawn by [FinishedStrokesView].
 * Every touch/hover event is reported to [probe] before it is drawn.
 */
@SuppressLint("ViewConstructor")
class InkProbeView(context: Context, private val probe: Probe) :
    FrameLayout(context), InProgressStrokesFinishedListener {

    var allowFingerDrawing = false

    private val finishedView = FinishedStrokesView(context)
    private val inProgressView = InProgressStrokesView(context)
    private val predictor = MotionEventPredictor.newInstance(this)
    private val activeStrokes = HashMap<Int, InProgressStrokeId>()
    private val density = resources.displayMetrics.density

    init {
        setBackgroundColor(Color.WHITE)
        addView(finishedView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inProgressView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inProgressView.addFinishedStrokesListener(this)
        setOnGenericMotionListener { _, event ->
            probe.onMotion(event, "generic")
            true
        }
    }

    fun clearStrokes() {
        finishedView.strokes.clear()
        finishedView.invalidate()
        probe.strokeCount = 0
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        probe.onMotion(event, "touch")
        predictor.record(event)

        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!canDraw(event.getToolType(index))) return true
                if (event.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(event)
                val pointerId = event.getPointerId(index)
                activeStrokes[pointerId] = inProgressView.startStroke(event, pointerId, brushFor(event, index))
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeStrokes.isEmpty()) return true
                val predicted = predictor.predict()
                try {
                    for ((pointerId, strokeId) in activeStrokes) {
                        if (event.findPointerIndex(pointerId) < 0) continue
                        inProgressView.addToStroke(event, pointerId, strokeId, predicted)
                    }
                } finally {
                    predicted?.recycle()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(index)
                val strokeId = activeStrokes.remove(pointerId) ?: return true
                if (wasCanceled(event)) {
                    inProgressView.cancelStroke(strokeId, event)
                } else {
                    inProgressView.finishStroke(event, pointerId, strokeId)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                for (strokeId in activeStrokes.values) inProgressView.cancelStroke(strokeId, event)
                activeStrokes.clear()
            }
        }
        return true
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        finishedView.strokes.addAll(strokes.values)
        finishedView.invalidate()
        inProgressView.removeFinishedStrokes(strokes.keys)
        probe.strokeCount += strokes.size
    }

    private fun canDraw(toolType: Int) =
        Probe.isPen(toolType) || (allowFingerDrawing && toolType == MotionEvent.TOOL_TYPE_FINGER)

    private fun wasCanceled(event: MotionEvent) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            event.flags and MotionEvent.FLAG_CANCELED != 0

    /** Colour encodes what the system reported: red = eraser end or a pen button held, blue = finger. */
    private fun brushFor(event: MotionEvent, index: Int): Brush {
        val toolType = event.getToolType(index)
        val penButton = event.buttonState and
            (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY) != 0
        val color = when {
            toolType == MotionEvent.TOOL_TYPE_ERASER || penButton -> Color.RED
            toolType == MotionEvent.TOOL_TYPE_FINGER -> Color.BLUE
            else -> Color.BLACK
        }
        return Brush.createWithColorIntArgb(
            family = StockBrushes.pressurePen(),
            colorIntArgb = color,
            size = 3f * density,
            epsilon = 0.1f,
        )
    }

    private class FinishedStrokesView(context: Context) : View(context) {
        val strokes = ArrayList<Stroke>()
        private val renderer = CanvasStrokeRenderer.create()
        private val identity = Matrix()

        override fun onDraw(canvas: Canvas) {
            for (stroke in strokes) renderer.draw(canvas, stroke, identity)
        }
    }
}
