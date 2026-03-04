package com.example.poselandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

    // Pose correction state
    private var stateName: String = ""
    private var repCount: Int = 0
    private var feedback: String = ""
    private var isMatching: Boolean = false

    private val pointPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = LANDMARK_STROKE_WIDTH
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Dynamic line paint - color changes based on pose matching
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

    private val feedbackPaint = Paint().apply {
        color = Color.WHITE
        textSize = 64f
        isAntiAlias = true
        isFakeBoldText = true
        setShadowLayer(8f, 2f, 2f, Color.BLACK)
        textAlign = Paint.Align.CENTER
    }

    private val statePaint = Paint().apply {
        color = Color.rgb(76, 175, 80) // Green
        textSize = 48f
        isAntiAlias = true
        isFakeBoldText = true
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    private val badgePaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setResults(
        poseLandmarkerResult: PoseLandmarkerResult,
        imgHeight: Int,
        imgWidth: Int,
        currentFps: Int,
        inferenceTime: Long,
        delegate: String,
        poseStateName: String = "",
        poseRepCount: Int = 0,
        poseFeedback: String = "",
        poseIsMatching: Boolean = false
    ) {
        results = poseLandmarkerResult
        imageHeight = imgHeight
        imageWidth = imgWidth
        fps = currentFps
        inferenceTimeMs = inferenceTime
        delegateName = delegate
        stateName = poseStateName
        repCount = poseRepCount
        feedback = poseFeedback
        isMatching = poseIsMatching

        // Update skeleton color based on pose matching
        linePaint.color = if (isMatching) {
            Color.rgb(76, 175, 80) // Green when matching
        } else {
            Color.rgb(255, 152, 0) // Orange when not matching
        }

        // PreviewView FILL_START mode: scale up to match preview size
        scaleFactor = max(width * 1f / imageWidth, height * 1f / imageHeight)
        invalidate()
    }

    fun clear() {
        results = null
        fps = 0
        inferenceTimeMs = 0
        stateName = ""
        repCount = 0
        feedback = ""
        isMatching = false
        invalidate()
    }

    /** Get the height of the system status bar so HUD text is drawn below it */
    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 80
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // HUD: FPS, inference time, delegate — positioned below the system status bar
        val topOffset = getStatusBarHeight() + 24f
        canvas.drawText("FPS: $fps", 32f, topOffset, textPaint)
        canvas.drawText("Inference: ${inferenceTimeMs}ms", 32f, topOffset + 60f, textPaint)
        canvas.drawText("Delegate: $delegateName", 32f, topOffset + 120f, textPaint)

        // Draw pose correction state info (top right)
        if (stateName.isNotEmpty()) {
            val rightX = width - 32f
            statePaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(stateName, rightX, topOffset, statePaint)
            canvas.drawText("Reps: $repCount", rightX, topOffset + 60f, statePaint)
        }

        // Draw feedback at bottom center with background badge
        if (feedback.isNotEmpty()) {
            val feedbackY = height - 120f
            val textWidth = feedbackPaint.measureText(feedback)
            val padding = 24f

            // Draw semi-transparent background
            val bgRect = RectF(
                width / 2f - textWidth / 2f - padding,
                feedbackY - 50f,
                width / 2f + textWidth / 2f + padding,
                feedbackY + 20f
            )

            // Change background color based on matching state
            badgePaint.color = if (isMatching) {
                Color.argb(200, 46, 125, 50) // Dark green
            } else {
                Color.argb(200, 230, 81, 0) // Dark orange
            }

            canvas.drawRoundRect(bgRect, 16f, 16f, badgePaint)

            // Draw feedback text
            feedbackPaint.color = Color.WHITE
            canvas.drawText(feedback, width / 2f, feedbackY, feedbackPaint)
        }

        val poseLandmarkerResult = results ?: return
        val allLandmarks = poseLandmarkerResult.landmarks()
        if (allLandmarks.isEmpty()) return

        // Only draw the first detected person
        val landmark = allLandmarks[0]

        // Filter out false detections via world landmark visibility
        val worldLandmarks = poseLandmarkerResult.worldLandmarks()
        if (worldLandmarks.isNotEmpty()) {
            val wl = worldLandmarks[0]
            val keyIndices = intArrayOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
            val visibleCount = keyIndices.count { idx ->
                idx < wl.size && wl[idx].visibility().isPresent && wl[idx].visibility().get() > 0.5f
            }
            if (visibleCount < 4) return
        }

        // Draw connections using official POSE_LANDMARKS topology
        PoseLandmarker.POSE_LANDMARKS.forEach { connection ->
            val startLm = landmark[connection!!.start()]
            val endLm = landmark[connection.end()]
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

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 12f
    }
}
