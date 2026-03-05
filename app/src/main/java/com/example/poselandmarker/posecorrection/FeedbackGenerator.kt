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
        return when {
            featureName.contains("elbow") -> {
                val side = if (featureName.contains("left")) "left" else "right"
                if (signedDeviation > 0) "Straighten $side arm more" else "Bend $side elbow deeper"
            }

            featureName.contains("knee") -> {
                val side = if (featureName.contains("left")) "left" else "right"
                if (signedDeviation > 0) "Straighten $side leg" else "Bend $side knee more"
            }

            featureName.contains("hip") -> {
                if (signedDeviation > 0) "Hinge forward at hips more" else "Straighten hips, stand taller"
            }

            featureName.contains("shoulder") -> {
                val side = if (featureName.contains("left")) "left" else "right"
                if (signedDeviation > 0) "Raise $side arm higher" else "Lower $side arm"
            }

            featureName.contains("torso") -> {
                if (signedDeviation > 0) "Lean torso forward slightly" else "Bring torso more upright"
            }

            featureName.contains("arm_elevation") -> {
                val side = if (featureName.contains("left")) "left" else "right"
                // Positive deviation = arm higher than target
                if (signedDeviation > 0) "Lower $side arm slightly" else "Raise $side arm higher"
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
