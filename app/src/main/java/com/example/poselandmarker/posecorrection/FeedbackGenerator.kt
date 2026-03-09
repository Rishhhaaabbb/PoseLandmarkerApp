package com.example.poselandmarker.posecorrection

import android.os.SystemClock
import kotlin.math.abs

/**
 * Generates human-readable feedback with debouncing (minimum display time)
 * and intelligent prioritization of core body parts.
 *
 * Ported from Python's EnhancedFeedbackGenerator.
 */
class FeedbackGenerator(
    private val minDisplayTimeMs: Long = 1800,   // Minimum time to show each feedback
    private val maxHistory: Int = 3
) {
    private val recentFeedbackFeatures = mutableListOf<String>()
    private var currentFeedbackText: String = ""
    private var lastFeedbackChangeTimeMs: Long = 0

    /**
     * Generate feedback text based on current SIGNED deviations from target pose.
     * Uses debouncing — won't change text faster than [minDisplayTimeMs].
     *
     * @param deviations  Signed deviations (current - mean): positive = over, negative = under
     * @param tolerances  Per-feature tolerance values
     * @param toleranceScale  Current effective tolerance scale (for scaling thresholds)
     * @param isMatching  True if the pose is currently matching the target
     * @return Feedback string to display
     */
    fun generateFeedback(
        deviations: Map<String, Float>,
        tolerances: Map<String, Float>,
        toleranceScale: Float = 1f,
        isMatching: Boolean
    ): String {
        val now = SystemClock.uptimeMillis()

        if (isMatching) {
            // Always show positive feedback immediately
            recentFeedbackFeatures.clear()
            updateFeedback("Perfect! Hold this pose", now)
            return currentFeedbackText
        }

        // Debounce — don't change text too fast
        if (now - lastFeedbackChangeTimeMs < minDisplayTimeMs && currentFeedbackText.isNotEmpty()) {
            return currentFeedbackText
        }

        if (deviations.isEmpty()) {
            updateFeedback("Move into frame", now)
            return currentFeedbackText
        }

        // Find features exceeding their scaled tolerance
        val outOfTolerance = deviations.filter { (feature, signedDev) ->
            val tolerance = (tolerances[feature] ?: 10f) * toleranceScale
            abs(signedDev) > tolerance
        }

        if (outOfTolerance.isEmpty()) {
            updateFeedback("Almost there — minor adjustments", now)
            return currentFeedbackText
        }

        // Prioritize core body parts
        val coreKeywords = listOf("hip", "knee", "elbow", "torso", "shoulder")
        val coreDeviations = outOfTolerance.filter { (feature, _) ->
            coreKeywords.any { feature.contains(it) }
        }
        val targetDeviations = coreDeviations.ifEmpty { outOfTolerance }

        // Pick worst deviation that wasn't recently addressed
        val sorted = targetDeviations.entries
            .sortedByDescending { abs(it.value) }
            .filter { !recentFeedbackFeatures.contains(it.key) }

        val (worstFeature, worstSignedDev) = if (sorted.isNotEmpty()) {
            sorted.first().toPair()
        } else {
            targetDeviations.maxByOrNull { abs(it.value) }?.toPair()
                ?: return currentFeedbackText
        }

        // Track this feedback
        recentFeedbackFeatures.add(worstFeature)
        if (recentFeedbackFeatures.size > maxHistory) {
            recentFeedbackFeatures.removeAt(0)
        }

        val text = generateSpecificFeedback(worstFeature, worstSignedDev)
        updateFeedback(text, now)
        return currentFeedbackText
    }

    private fun updateFeedback(text: String, now: Long) {
        if (text != currentFeedbackText) {
            currentFeedbackText = text
            lastFeedbackChangeTimeMs = now
        }
    }

    private fun generateSpecificFeedback(featureName: String, signedDeviation: Float): String {
        // signedDeviation = current - mean.  Positive = feature value is HIGHER than target.
        // For angles: higher = more straight/open.  For elevations: higher = arm raised more.
        return when {
            featureName.contains("elbow") -> {
                // Higher angle = straighter arm.  signedDev > 0 → too straight → bend more
                val side = if (featureName.contains("left")) "left" else "right"
                if (signedDeviation > 0) "Bend $side elbow more" else "Straighten $side arm"
            }

            featureName.contains("knee") -> {
                // Higher angle = straighter leg.  signedDev > 0 → too straight → bend more
                val side = if (featureName.contains("left")) "left" else "right"
                if (signedDeviation > 0) "Bend $side knee more" else "Straighten $side leg"
            }

            featureName.contains("hip") -> {
                // Higher angle = more open/straight hip.  signedDev > 0 → too straight → bend
                if (signedDeviation > 0) "Hinge forward at hips" else "Stand taller, extend hips"
            }

            featureName.contains("shoulder") -> {
                // Higher angle = arm more raised from body.  signedDev > 0 → too high → lower
                val side = if (featureName.contains("left")) "left" else "right"
                if (signedDeviation > 0) "Lower $side arm" else "Raise $side arm higher"
            }

            featureName.contains("torso") -> {
                // Positive torso_angle = leaning forward.  signedDev > 0 → too forward → upright
                if (signedDeviation > 0) "Stand more upright" else "Lean forward slightly"
            }

            featureName.contains("arm_elevation") -> {
                val side = if (featureName.contains("left")) "left" else "right"
                // Positive elevation = arm above shoulder.  signedDev > 0 → too high → lower
                if (signedDeviation > 0) "Lower $side arm" else "Raise $side arm higher"
            }

            featureName.contains("leg_spread") -> {
                val side = if (featureName.contains("left")) "left" else "right"
                if (signedDeviation > 0) "Bring $side foot closer" else "Spread $side leg wider"
            }

            featureName.contains("body_center") -> {
                "Adjust body height"
            }

            else -> "Adjust ${featureName.replace("_", " ")}"
        }
    }

    fun clearHistory() {
        recentFeedbackFeatures.clear()
        currentFeedbackText = ""
        lastFeedbackChangeTimeMs = 0
    }
}
