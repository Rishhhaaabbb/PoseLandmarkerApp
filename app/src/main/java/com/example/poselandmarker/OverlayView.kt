package com.example.poselandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.max

/**
 * Draws pose landmarks and skeleton connections identical to Google's official demo.
 * Uses PoseLandmarker.POSE_LANDMARKS for connections and proper scaleFactor.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var results: PoseLandmarkerResult? = null
    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    // Metrics
    private var fps: Int = 0
    private var inferenceTimeMs: Long = 0
    private var delegateName: String = "GPU"

    private val pointPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = LANDMARK_STROKE_WIDTH
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.rgb(0, 188, 212) // Cyan/teal matching Google demo
        strokeWidth = LANDMARK_STROKE_WIDTH
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 52f
        isAntiAlias = true
        isFakeBoldText = true
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    fun setResults(
        poseLandmarkerResult: PoseLandmarkerResult,
        imgHeight: Int,
        imgWidth: Int,
        currentFps: Int,
        inferenceTime: Long,
        delegate: String
    ) {
        results = poseLandmarkerResult
        imageHeight = imgHeight
        imageWidth = imgWidth
        fps = currentFps
        inferenceTimeMs = inferenceTime
        delegateName = delegate

        // PreviewView FILL_START mode: scale up to match preview size
        scaleFactor = max(width * 1f / imageWidth, height * 1f / imageHeight)
        invalidate()
    }

    fun clear() {
        results = null
        fps = 0
        inferenceTimeMs = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // HUD: FPS, inference time, delegate
        canvas.drawText("FPS: $fps", 32f, 80f, textPaint)
        canvas.drawText("Inference: ${inferenceTimeMs}ms", 32f, 140f, textPaint)
        canvas.drawText("Delegate: $delegateName", 32f, 200f, textPaint)

        val poseLandmarkerResult = results ?: return
        val allLandmarks = poseLandmarkerResult.landmarks()
        if (allLandmarks.isEmpty()) return

        for (landmark in allLandmarks) {
            // Draw connections using official POSE_LANDMARKS topology
            PoseLandmarker.POSE_LANDMARKS.forEach { connection ->
                val startLm = allLandmarks[0][connection!!.start()]
                val endLm = allLandmarks[0][connection.end()]
                canvas.drawLine(
                    startLm.x() * imageWidth * scaleFactor,
                    startLm.y() * imageHeight * scaleFactor,
                    endLm.x() * imageWidth * scaleFactor,
                    endLm.y() * imageHeight * scaleFactor,
                    linePaint
                )
            }

            // Draw landmark points
            for (normalizedLandmark in landmark) {
                canvas.drawPoint(
                    normalizedLandmark.x() * imageWidth * scaleFactor,
                    normalizedLandmark.y() * imageHeight * scaleFactor,
                    pointPaint
                )
            }
        }
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 12f
    }
}
