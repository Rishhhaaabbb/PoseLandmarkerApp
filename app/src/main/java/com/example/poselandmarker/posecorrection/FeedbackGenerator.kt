package com.example.poselandmarker.posecorrection

import kotlin.math.abs

/**
 * Generates human-readable feedback based on pose deviations.
 */
class FeedbackGenerator {

    private val recentFeedback = mutableListOf<String>()
    private val maxHistory = 3

    /**
     * Generate feedback text based on current deviations from target pose.
     * Prioritizes core body parts and avoids repeating the same feedback.
     */
    fun generateFeedback(
        deviations: Map<String, Float>,
        tolerances: Map<String, Float>,
        isMatching: Boolean
    ): String {
        if (isMatching) {
            recentFeedback.clear()
            return "Perfect! Hold this pose"
        }

        if (deviations.isEmpty()) {
            return "Move into frame"
        }

        // Find features exceeding their tolerance
        val outOfTolerance = deviations.filter { (feature, deviation) ->
            val tolerance = tolerances[feature] ?: 10f
            deviation > tolerance
        }

        if (outOfTolerance.isEmpty()) {
            return "Almost there - minor adjustments"
        }

        // Prioritize core body parts
        val coreFeatures = listOf("hip", "knee", "elbow", "torso", "shoulder")
        val coreDeviations = outOfTolerance.filter { (feature, _) ->
            coreFeatures.any { feature.contains(it) }
        }

        val targetDeviations = coreDeviations.ifEmpty { outOfTolerance }

        // Get the worst deviation that wasn't recently addressed
        val sortedDeviations = targetDeviations.entries
            .sortedByDescending { it.value }
            .filter { !recentFeedback.contains(it.key) }

        val (worstFeature, worstDeviation) = if (sortedDeviations.isNotEmpty()) {
            sortedDeviations.first().toPair()
        } else {
            // All were recent - just use the worst
            targetDeviations.maxByOrNull { it.value }?.toPair() ?: return "Adjust pose"
        }

        // Track this feedback
        recentFeedback.add(worstFeature)
        if (recentFeedback.size > maxHistory) {
            recentFeedback.removeAt(0)
        }

        return generateSpecificFeedback(worstFeature, worstDeviation)
    }

    private fun generateSpecificFeedback(featureName: String, deviation: Float): String {
        val direction = if (deviation > 0) "more" else "less"

        return when {
            featureName.contains("elbow") -> {
                val side = if (featureName.contains("left")) "left" else "right"
                if (deviation > 0) "Straighten $side arm more" else "Bend $side elbow deeper"
            }

            featureName.contains("knee") -> {
                val side = if (featureName.contains("left")) "left" else "right"
                if (deviation > 0) "Straighten $side leg" else "Bend $side knee more"
            }

            featureName.contains("hip") -> {
                if (abs(deviation) > 15) {
                    "Adjust hip angle"
                } else {
                    "Fine-tune hip position"
                }
            }

            featureName.contains("shoulder") -> {
                val side = if (featureName.contains("left")) "left" else "right"
                "Adjust $side shoulder position"
            }

            featureName.contains("torso") -> {
                if (deviation > 0) "Lean forward slightly" else "Stand more upright"
            }

            featureName.contains("arm_elevation") -> {
                val side = if (featureName.contains("left")) "left" else "right"
                if (deviation > 0) "Raise $side arm higher" else "Lower $side arm"
            }

            featureName.contains("leg_spread") -> {
                val side = if (featureName.contains("left")) "left" else "right"
                if (deviation > 0) "Bring $side foot closer" else "Spread $side leg wider"
            }

            featureName.contains("body_center") -> {
                "Adjust body height"
            }

            else -> "Adjust ${featureName.replace("_", " ")}"
        }
    }

    /**
     * Clear feedback history (e.g., when transitioning to a new state).
     */
    fun clearHistory() {
        recentFeedback.clear()
    }
}
