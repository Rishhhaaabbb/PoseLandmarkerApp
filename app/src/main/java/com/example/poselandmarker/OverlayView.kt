package com.example.poselandmarker

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.poselandmarker.posecorrection.PoseState
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Production-quality overlay that draws:
 *  - Color-coded skeleton (per-joint deviation highlighting)
 *  - Top HUD: status bar with state, reps, accuracy gauge, streak
 *  - Hold timer progress arc
 *  - Feedback badge (debounced, positioned above bottom controls)
 *  - Mini target-skeleton panel (bottom-right corner)
 *  - Full-screen guidance/preview overlay when transitioning
 *  - FPS counter (small, top-left corner)
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Input data ──────────────────────────────────────────────────────
    private var results: PoseLandmarkerResult? = null
    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    // Metrics
    private var fps: Int = 0
    private var inferenceTimeMs: Long = 0

    // Pose correction state
    private var stateName: String = ""
    private var sequenceProgress: String = ""
    private var repCount: Int = 0
    private var feedback: String = ""
    private var isMatching: Boolean = false
    private var holdProgress: Float = 0f
    private var holdRemainingSeconds: Float = 0f
    private var accuracyPercent: Int = 0
    private var matchRatio: Float = 0f
    private var streak: Int = 0

    // Grace / Guidance
    private var isInGracePeriod: Boolean = false
    private var isGuidanceActive: Boolean = false
    private var guidanceRemainingSeconds: Float = 0f
    private var nextStatePreview: PoseState? = null

    // Target keypoints for mini skeleton
    private var currentTargetKeypoints: List<Triple<Float, Float, Float>>? = null

    // Per-joint error ratios (feature_name → ratio where >1 = over tolerance)
    private var perJointErrorRatios: Map<String, Float> = emptyMap()

    // ── Paints ──────────────────────────────────────────────────────────
    private val dp = context.resources.displayMetrics.density

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 10f * dp / 2.5f
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * dp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(3f * dp, 1f, 1f, Color.BLACK)
    }

    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * dp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f * dp, 1f, 1f, Color.BLACK)
    }

    private val feedbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 17f * dp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(5f * dp, 2f, 2f, Color.BLACK)
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * dp
        strokeCap = Paint.Cap.ROUND
    }

    private val miniSkeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val miniPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f * dp
        style = Paint.Style.FILL
    }

    private val guidanceLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 8f * dp
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(180, 50, 50, 50)
    }

    private val guidancePointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 6f * dp
        style = Paint.Style.FILL
        color = Color.argb(200, 50, 50, 50)
    }

    // Colors
    private val colorGreen = Color.rgb(76, 175, 80)
    private val colorOrange = Color.rgb(255, 152, 0)
    private val colorRed = Color.rgb(244, 67, 54)
    private val colorYellow = Color.rgb(255, 235, 59)
    private val colorCyan = Color.rgb(0, 188, 212)
    private val colorWhite = Color.WHITE

    // MediaPipe skeleton bone connections grouped by body region
    private val torsoBones = intArrayOf(11,12, 11,23, 12,24, 23,24)
    private val armBones = intArrayOf(11,13, 13,15, 12,14, 14,16)
    private val legBones = intArrayOf(23,25, 25,27, 24,26, 26,28)

    // Joint index → feature name mapping for per-joint coloring
    // Extended to cover more joints for better error propagation
    private val jointFeatureMap = mapOf(
        13 to "left_elbow_angle",  14 to "right_elbow_angle",
        25 to "left_knee_angle",   26 to "right_knee_angle",
        11 to "left_shoulder_angle", 12 to "right_shoulder_angle",
        23 to "left_hip_angle",    24 to "right_hip_angle",
        15 to "left_arm_elevation", 16 to "right_arm_elevation",
        27 to "left_knee_angle",   28 to "right_knee_angle"    // ankles inherit from knees
    )

    // Adjacency: for landmarks with no feature, inherit from nearest mapped neighbor
    private val jointErrorFallback = mapOf(
        0 to 11,   // nose → left shoulder
        1 to 11, 2 to 12, 3 to 11, 4 to 12,  // eyes/ears → shoulders
        5 to 11, 6 to 12, 7 to 13, 8 to 14,  // unused MP indices
        9 to 15, 10 to 16,
        17 to 15, 18 to 16, 19 to 15, 20 to 16,  // hand landmarks
        21 to 15, 22 to 16,
        29 to 27, 30 to 28, 31 to 27, 32 to 28   // foot landmarks
    )

    // ── Public API ──────────────────────────────────────────────────────

    fun setResults(
        poseLandmarkerResult: PoseLandmarkerResult,
        imgHeight: Int,
        imgWidth: Int,
        currentFps: Int,
        inferenceTime: Long,
        poseStateName: String = "",
        poseSequenceProgress: String = "",
        poseRepCount: Int = 0,
        poseFeedback: String = "",
        poseIsMatching: Boolean = false,
        poseHoldProgress: Float = 0f,
        poseHoldRemainingSeconds: Float = 0f,
        poseAccuracyPercent: Int = 0,
        poseMatchRatio: Float = 0f,
        poseStreak: Int = 0,
        poseIsInGracePeriod: Boolean = false,
        poseIsGuidanceActive: Boolean = false,
        poseGuidanceRemainingSeconds: Float = 0f,
        poseNextStatePreview: PoseState? = null,
        poseCurrentTargetKeypoints: List<Triple<Float, Float, Float>>? = null,
        posePerJointErrorRatios: Map<String, Float> = emptyMap()
    ) {
        results = poseLandmarkerResult
        imageHeight = imgHeight
        imageWidth = imgWidth
        fps = currentFps
        inferenceTimeMs = inferenceTime
        stateName = poseStateName
        sequenceProgress = poseSequenceProgress
        repCount = poseRepCount
        feedback = poseFeedback
        isMatching = poseIsMatching
        holdProgress = poseHoldProgress
        holdRemainingSeconds = poseHoldRemainingSeconds
        accuracyPercent = poseAccuracyPercent
        matchRatio = poseMatchRatio
        streak = poseStreak
        isInGracePeriod = poseIsInGracePeriod
        isGuidanceActive = poseIsGuidanceActive
        guidanceRemainingSeconds = poseGuidanceRemainingSeconds
        nextStatePreview = poseNextStatePreview
        currentTargetKeypoints = poseCurrentTargetKeypoints
        perJointErrorRatios = posePerJointErrorRatios

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
        holdProgress = 0f
        accuracyPercent = 0
        invalidate()
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1) Full-screen guidance overlay (takes over everything)
        if (isGuidanceActive && nextStatePreview != null) {
            drawGuidanceOverlay(canvas)
            return
        }

        // 2) Draw live skeleton with per-joint coloring
        drawSkeleton(canvas)

        // 3) HUD elements
        drawTopHud(canvas)
        drawHoldTimerArc(canvas)
        drawAccuracyGauge(canvas)
        drawFeedbackBadge(canvas)
        drawMiniTargetSkeleton(canvas)

        // 4) Streak indicator
        if (streak >= 3) drawStreakBadge(canvas)
    }

    // ── Skeleton with per-joint error coloring ──────────────────────────

    private fun drawSkeleton(canvas: Canvas) {
        val poseLandmarkerResult = results ?: return
        val allLandmarks = poseLandmarkerResult.landmarks()
        if (allLandmarks.isEmpty()) return
        val landmarks = allLandmarks[0]

        // Filter out false detections
        val worldLandmarks = poseLandmarkerResult.worldLandmarks()
        if (worldLandmarks.isNotEmpty()) {
            val wl = worldLandmarks[0]
            val keyIndices = intArrayOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
            val visibleCount = keyIndices.count { idx ->
                idx < wl.size && wl[idx].visibility().isPresent && wl[idx].visibility().get() > 0.5f
            }
            if (visibleCount < 4) return
        }

        // Default skeleton color based on match state
        val baseColor = when {
            isInGracePeriod -> colorCyan
            isMatching -> colorGreen
            else -> colorOrange
        }

        // Draw connections using official POSE_LANDMARKS
        PoseLandmarker.POSE_LANDMARKS.forEach { connection ->
            val startIdx = connection!!.start()
            val endIdx = connection.end()
            val startLm = landmarks[startIdx]
            val endLm = landmarks[endIdx]

            // Determine color based on per-joint errors
            val startError = getJointErrorRatio(startIdx)
            val endError = getJointErrorRatio(endIdx)
            val maxError = max(startError, endError)

            linePaint.color = when {
                isInGracePeriod -> colorCyan
                maxError > 1.5f -> colorRed
                maxError > 1.0f -> colorOrange
                maxError > 0.7f -> colorYellow
                else -> colorGreen
            }

            canvas.drawLine(
                startLm.x() * imageWidth * scaleFactor,
                startLm.y() * imageHeight * scaleFactor,
                endLm.x() * imageWidth * scaleFactor,
                endLm.y() * imageHeight * scaleFactor,
                linePaint
            )
        }

        // Draw landmark points with color coding
        for (i in landmarks.indices) {
            val lm = landmarks[i]
            val x = lm.x() * imageWidth * scaleFactor
            val y = lm.y() * imageHeight * scaleFactor
            val errorRatio = getJointErrorRatio(i)

            pointPaint.color = when {
                isInGracePeriod -> colorCyan
                errorRatio > 1.5f -> colorRed
                errorRatio > 1.0f -> colorOrange
                errorRatio > 0.7f -> colorYellow
                else -> colorGreen
            }

            val radius = when {
                errorRatio > 1.0f -> 7f * dp   // Larger for deviating joints
                else -> 5f * dp
            }
            canvas.drawCircle(x, y, radius, pointPaint)
        }
    }

    private fun getJointErrorRatio(landmarkIndex: Int): Float {
        if (perJointErrorRatios.isEmpty()) {
            return if (isMatching) 0f else 0.8f
        }
        // Direct mapping first
        val directFeature = jointFeatureMap[landmarkIndex]
        if (directFeature != null) {
            return perJointErrorRatios[directFeature] ?: 0f
        }
        // Fallback: inherit from nearest mapped neighbor
        val fallbackIdx = jointErrorFallback[landmarkIndex]
        if (fallbackIdx != null) {
            val fallbackFeature = jointFeatureMap[fallbackIdx]
            if (fallbackFeature != null) {
                return perJointErrorRatios[fallbackFeature] ?: 0f
            }
        }
        // Truly unmapped → assume OK
        return 0f
    }

    // ── Top HUD ─────────────────────────────────────────────────────────

    private fun drawTopHud(canvas: Canvas) {
        val statusBarH = getStatusBarHeight().toFloat()
        val topY = statusBarH + 12f * dp

        // Left side: FPS (small, unobtrusive)
        textPaint.textSize = 11f * dp
        textPaint.color = Color.argb(180, 255, 255, 255)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("$fps FPS · ${inferenceTimeMs}ms", 12f * dp, topY, textPaint)

        // Center: State info
        if (stateName.isNotEmpty()) {
            hudPaint.textSize = 15f * dp
            hudPaint.textAlign = Paint.Align.CENTER
            hudPaint.color = if (isMatching) colorGreen else colorWhite

            val centerX = width / 2f
            canvas.drawText(stateName, centerX, topY, hudPaint)

            hudPaint.textSize = 12f * dp
            hudPaint.color = Color.argb(200, 200, 200, 200)
            canvas.drawText("Step $sequenceProgress", centerX, topY + 18f * dp, hudPaint)
        }

        // Right side: Reps
        hudPaint.textSize = 16f * dp
        hudPaint.textAlign = Paint.Align.RIGHT
        hudPaint.color = colorWhite
        canvas.drawText("Reps: $repCount", width - 12f * dp, topY, hudPaint)
    }

    // ── Hold Timer Arc ──────────────────────────────────────────────────

    private fun drawHoldTimerArc(canvas: Canvas) {
        if (holdProgress <= 0.001f && !isMatching) return

        val centerX = width / 2f
        val centerY = height * 0.15f
        val radius = 32f * dp
        val rect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        // Background circle
        progressPaint.color = Color.argb(60, 255, 255, 255)
        canvas.drawArc(rect, 0f, 360f, false, progressPaint)

        // Progress arc
        progressPaint.color = when {
            holdProgress >= 1f -> colorGreen
            holdProgress > 0.5f -> colorCyan
            else -> colorOrange
        }
        canvas.drawArc(rect, -90f, holdProgress * 360f, false, progressPaint)

        // Center text: remaining seconds
        hudPaint.textSize = 14f * dp
        hudPaint.textAlign = Paint.Align.CENTER
        hudPaint.color = colorWhite
        if (holdRemainingSeconds > 0.1f) {
            canvas.drawText(String.format("%.1f", holdRemainingSeconds), centerX, centerY + 5f * dp, hudPaint)
        } else if (holdProgress >= 1f) {
            hudPaint.color = colorGreen
            canvas.drawText("✓", centerX, centerY + 5f * dp, hudPaint)
        }

        // Label below
        hudPaint.textSize = 10f * dp
        hudPaint.color = Color.argb(180, 255, 255, 255)
        canvas.drawText("HOLD", centerX, centerY + radius + 14f * dp, hudPaint)
    }

    // ── Accuracy Gauge ──────────────────────────────────────────────────

    private fun drawAccuracyGauge(canvas: Canvas) {
        if (accuracyPercent <= 0 && !isMatching) return

        val gaugeX = width - 48f * dp
        val gaugeY = height * 0.15f
        val radius = 28f * dp
        val rect = RectF(gaugeX - radius, gaugeY - radius, gaugeX + radius, gaugeY + radius)

        // Background
        progressPaint.color = Color.argb(50, 255, 255, 255)
        progressPaint.strokeWidth = 5f * dp
        canvas.drawArc(rect, 0f, 360f, false, progressPaint)

        // Accuracy arc
        progressPaint.color = when {
            accuracyPercent >= 85 -> colorGreen
            accuracyPercent >= 70 -> colorCyan
            accuracyPercent >= 50 -> colorYellow
            else -> colorOrange
        }
        canvas.drawArc(rect, -90f, accuracyPercent * 3.6f, false, progressPaint)
        progressPaint.strokeWidth = 6f * dp // reset

        // Text in center
        hudPaint.textSize = 13f * dp
        hudPaint.textAlign = Paint.Align.CENTER
        hudPaint.color = colorWhite
        canvas.drawText("$accuracyPercent%", gaugeX, gaugeY + 5f * dp, hudPaint)

        // Label below
        hudPaint.textSize = 9f * dp
        hudPaint.color = Color.argb(180, 255, 255, 255)
        canvas.drawText("ACC", gaugeX, gaugeY + radius + 12f * dp, hudPaint)
    }

    // ── Feedback Badge ──────────────────────────────────────────────────

    private fun drawFeedbackBadge(canvas: Canvas) {
        if (feedback.isEmpty()) return

        // Positioned above the bottom controls (delegate spinner area at ~bottom 80dp)
        val feedbackY = height - 100f * dp
        feedbackPaint.textSize = 16f * dp
        val textW = feedbackPaint.measureText(feedback)
        val padding = 16f * dp

        val bgRect = RectF(
            width / 2f - textW / 2f - padding,
            feedbackY - 28f * dp,
            width / 2f + textW / 2f + padding,
            feedbackY + 12f * dp
        )

        // Background
        badgePaint.color = when {
            isInGracePeriod -> Color.argb(200, 0, 150, 136)   // Teal
            isMatching      -> Color.argb(210, 27, 94, 32)    // Dark green
            else            -> Color.argb(210, 191, 54, 12)   // Dark orange
        }
        canvas.drawRoundRect(bgRect, 12f * dp, 12f * dp, badgePaint)

        // Border
        badgePaint.style = Paint.Style.STROKE
        badgePaint.strokeWidth = 2f * dp
        badgePaint.color = if (isMatching) colorGreen else colorOrange
        canvas.drawRoundRect(bgRect, 12f * dp, 12f * dp, badgePaint)
        badgePaint.style = Paint.Style.FILL

        // Text
        feedbackPaint.color = colorWhite
        canvas.drawText(feedback, width / 2f, feedbackY, feedbackPaint)
    }

    // ── Mini Target Skeleton Panel (bottom-right) ───────────────────────

    private fun drawMiniTargetSkeleton(canvas: Canvas) {
        val keypoints = currentTargetKeypoints ?: return
        if (keypoints.isEmpty()) return

        // Larger panel for better visibility
        val panelW = (width * 0.28f).toInt()
        val panelH = (height * 0.32f).toInt()
        val panelX = width - panelW - (8f * dp).toInt()
        val panelY = height - panelH - (90f * dp).toInt()

        // Semi-transparent panel background
        badgePaint.color = Color.argb(180, 15, 15, 15)
        val panelRect = RectF(panelX.toFloat(), panelY.toFloat(),
            (panelX + panelW).toFloat(), (panelY + panelH).toFloat())
        canvas.drawRoundRect(panelRect, 10f * dp, 10f * dp, badgePaint)

        // Border colored by match status
        badgePaint.style = Paint.Style.STROKE
        badgePaint.strokeWidth = 1.5f * dp
        badgePaint.color = if (isMatching) Color.argb(140, 76, 175, 80) else Color.argb(120, 200, 200, 200)
        canvas.drawRoundRect(panelRect, 10f * dp, 10f * dp, badgePaint)
        badgePaint.style = Paint.Style.FILL

        // Label
        textPaint.textSize = 10f * dp
        textPaint.color = Color.argb(220, 220, 220, 220)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("TARGET POSE", panelX + panelW / 2f, panelY + 14f * dp, textPaint)
        textPaint.textAlign = Paint.Align.LEFT

        // Scale keypoints using BODY-ONLY bounding box (landmarks 11-28)
        val bodyLabelH = (18f * dp).toInt()
        val scaled = scaleKeypointsToPanel(keypoints, panelW, panelH - bodyLabelH, panelX, panelY + bodyLabelH, bodyOnly = true)

        // Draw bones with per-joint error coloring
        val bones = listOf(
            11 to 12, 11 to 23, 12 to 24, 23 to 24,  // torso
            11 to 13, 13 to 15, 12 to 14, 14 to 16,  // arms
            23 to 25, 25 to 27, 24 to 26, 26 to 28   // legs
        )

        for ((s, e) in bones) {
            if (s >= scaled.size || e >= scaled.size) continue
            val (sx, sy, sc) = scaled[s]
            val (ex, ey, ec) = scaled[e]
            if (sc < 0.3f || ec < 0.3f) continue

            // Color based on error ratios for these joints
            val startErr = getJointErrorRatio(s)
            val endErr = getJointErrorRatio(e)
            val maxErr = max(startErr, endErr)

            miniSkeletonPaint.color = when {
                perJointErrorRatios.isEmpty() -> Color.rgb(120, 200, 120)
                maxErr > 1.5f -> colorRed
                maxErr > 1.0f -> colorOrange
                maxErr > 0.7f -> colorYellow
                else -> Color.rgb(120, 200, 120)
            }
            canvas.drawLine(sx, sy, ex, ey, miniSkeletonPaint)
        }

        // Draw joints with error coloring
        val majorJoints = intArrayOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
        for (i in majorJoints) {
            if (i >= scaled.size) continue
            val (x, y, c) = scaled[i]
            if (c < 0.3f) continue

            val errRatio = getJointErrorRatio(i)
            miniPointPaint.color = when {
                perJointErrorRatios.isEmpty() -> Color.rgb(150, 220, 150)
                errRatio > 1.5f -> colorRed
                errRatio > 1.0f -> colorOrange
                errRatio > 0.7f -> colorYellow
                else -> Color.rgb(150, 220, 150)
            }
            val r = if (errRatio > 1.0f) 4.5f * dp else 3.5f * dp
            canvas.drawCircle(x, y, r, miniPointPaint)
        }

        // Draw head circle
        if (scaled.size > 4) {
            val (nx, ny, nc) = scaled[0]
            if (nc > 0.3f) {
                miniPointPaint.color = Color.rgb(200, 180, 160)
                canvas.drawCircle(nx, ny, 7f * dp, miniPointPaint)
                // Neck line from head to midpoint of shoulders
                if (scaled.size > 12) {
                    val (lsx, lsy, lsc) = scaled[11]
                    val (rsx, rsy, rsc) = scaled[12]
                    if (lsc > 0.3f && rsc > 0.3f) {
                        val neckX = (lsx + rsx) / 2f
                        val neckY = (lsy + rsy) / 2f
                        miniSkeletonPaint.color = Color.rgb(120, 200, 120)
                        canvas.drawLine(nx, ny, neckX, neckY, miniSkeletonPaint)
                    }
                }
            }
        }
    }

    private fun scaleKeypointsToPanel(
        keypoints: List<Triple<Float, Float, Float>>,
        panelW: Int, panelH: Int,
        offsetX: Int, offsetY: Int,
        bodyOnly: Boolean = false
    ): List<Triple<Float, Float, Float>> {
        // If bodyOnly, compute bounding box from landmarks 11-28 only (skip face 0-10)
        val valid = if (bodyOnly) {
            keypoints.filterIndexed { i, kp -> i in 11..28 && kp.third > 0.3f }
        } else {
            keypoints.filter { it.third > 0.3f }
        }
        if (valid.isEmpty()) return keypoints.map { Triple(0f, 0f, 0f) }

        val minX = valid.minOf { it.first }
        val maxX = valid.maxOf { it.first }
        val minY = valid.minOf { it.second }
        val maxY = valid.maxOf { it.second }
        val rangeX = (maxX - minX).coerceAtLeast(1f)
        val rangeY = (maxY - minY).coerceAtLeast(1f)

        // Leave room for head above shoulders (add 15% top padding if bodyOnly)
        val topPad = if (bodyOnly) 0.18f else 0.12f
        val padding = 0.10f
        val scale = min(
            panelW * (1 - 2 * padding) / rangeX,
            panelH * (1 - padding - topPad) / rangeY
        )

        val oX = offsetX + (panelW - rangeX * scale) / 2f
        val oY = offsetY + topPad * panelH + (panelH * (1 - padding - topPad) - rangeY * scale) / 2f

        return keypoints.map { (x, y, c) ->
            Triple(
                (x - minX) * scale + oX,
                (y - minY) * scale + oY,
                c
            )
        }
    }

    // ── Streak Badge ────────────────────────────────────────────────────

    private fun drawStreakBadge(canvas: Canvas) {
        val badgeX = 12f * dp
        val badgeY = height * 0.15f

        hudPaint.textSize = 12f * dp
        hudPaint.textAlign = Paint.Align.LEFT
        hudPaint.color = if (streak >= 10) colorGreen else colorYellow
        val streakText = if (streak >= 10) "🔥 STREAK: $streak" else "Streak: $streak"
        canvas.drawText(streakText, badgeX, badgeY, hudPaint)
    }

    // ── Full-screen Guidance Overlay ────────────────────────────────────

    private fun drawGuidanceOverlay(canvas: Canvas) {
        // Dark translucent overlay (not opaque white)
        canvas.drawColor(Color.argb(220, 20, 20, 30))

        val preview = nextStatePreview ?: return
        val keypoints = preview.meanKeypoints ?: return

        // Draw ghost skeleton scaled to full screen, body-only bounding box
        val scaled = scaleKeypointsToPanel(keypoints, width, (height * 0.65f).toInt(), 0, (height * 0.12f).toInt(), bodyOnly = true)

        val bones = listOf(
            11 to 12, 11 to 23, 12 to 24, 23 to 24,
            11 to 13, 13 to 15, 12 to 14, 14 to 16,
            23 to 25, 25 to 27, 24 to 26, 26 to 28
        )

        // Draw bones with a glow effect
        for ((s, e) in bones) {
            if (s >= scaled.size || e >= scaled.size) continue
            val (sx, sy, sc) = scaled[s]
            val (ex, ey, ec) = scaled[e]
            if (sc < 0.3f || ec < 0.3f) continue

            // Glow layer
            guidanceLinePaint.color = Color.argb(50, 76, 175, 80)
            guidanceLinePaint.strokeWidth = 14f * dp
            canvas.drawLine(sx, sy, ex, ey, guidanceLinePaint)

            // Main line
            guidanceLinePaint.color = Color.argb(200, 76, 175, 80)
            guidanceLinePaint.strokeWidth = 6f * dp
            canvas.drawLine(sx, sy, ex, ey, guidanceLinePaint)
        }

        // Reset stroke width
        guidanceLinePaint.strokeWidth = 8f * dp

        // Draw head with neck
        if (scaled.size > 12) {
            val (nx, ny, nc) = scaled[0]
            val (lsx, lsy, lsc) = scaled[11]
            val (rsx, rsy, rsc) = scaled[12]
            if (nc > 0.3f) {
                guidancePointPaint.color = Color.argb(180, 76, 175, 80)
                canvas.drawCircle(nx, ny, 20f * dp, guidancePointPaint)
                // Neck line
                if (lsc > 0.3f && rsc > 0.3f) {
                    val neckX = (lsx + rsx) / 2f
                    val neckY = (lsy + rsy) / 2f
                    guidanceLinePaint.color = Color.argb(200, 76, 175, 80)
                    guidanceLinePaint.strokeWidth = 6f * dp
                    canvas.drawLine(nx, ny, neckX, neckY, guidanceLinePaint)
                }
            }
        }

        // Draw joints
        val majorJoints = intArrayOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
        for (i in majorJoints) {
            if (i >= scaled.size) continue
            val (x, y, c) = scaled[i]
            if (c < 0.3f) continue
            // Outer glow
            guidancePointPaint.color = Color.argb(60, 76, 175, 80)
            canvas.drawCircle(x, y, 10f * dp, guidancePointPaint)
            // Inner point
            guidancePointPaint.color = Color.argb(220, 100, 200, 100)
            canvas.drawCircle(x, y, 5f * dp, guidancePointPaint)
        }

        // Title
        hudPaint.textSize = 22f * dp
        hudPaint.textAlign = Paint.Align.CENTER
        hudPaint.color = Color.rgb(200, 200, 200)
        canvas.drawText("GET READY", width / 2f, height * 0.06f, hudPaint)

        // State name
        hudPaint.textSize = 16f * dp
        hudPaint.color = Color.rgb(76, 175, 80)
        canvas.drawText("Next: Pose ${preview.stateId + 1}", width / 2f, height * 0.06f + 26f * dp, hudPaint)

        // Countdown - large centered
        hudPaint.textSize = 48f * dp
        hudPaint.color = colorWhite
        canvas.drawText(
            String.format("%.0f", guidanceRemainingSeconds),
            width / 2f,
            height * 0.88f,
            hudPaint
        )

        hudPaint.textSize = 14f * dp
        hudPaint.color = Color.rgb(160, 160, 160)
        canvas.drawText("seconds", width / 2f, height * 0.88f + 22f * dp, hudPaint)
    }

    // ── Utility ─────────────────────────────────────────────────────────

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else (24f * dp).toInt()
    }

    companion object {
        private const val TAG = "OverlayView"
    }
}
