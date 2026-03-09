package com.example.poselandmarker.posecorrection

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs

/**
 * Represents a discovered pose state with mean features and tolerances.
 */
data class PoseState(
    val stateId: Int,
    val meanFeatures: Map<String, Float>,
    val featureTolerances: Map<String, Float>,
    val minHoldDuration: Float,
    val meanKeypoints: List<Triple<Float, Float, Float>>? = null // x, y, confidence
) {
    /**
     * Check if given features match this state within scaled tolerances.
     * @param features  Feature dictionary to check
     * @param toleranceScale  Multiplier applied to all tolerances (for progressive relaxation)
     * @param matchThreshold  Fraction of features that must be within tolerance (default 0.6)
     * @return MatchResult with match status, signed deviations, and match ratio
     */
    fun matches(
        features: Map<String, Float>,
        toleranceScale: Float = 1.0f,
        matchThreshold: Float = 0.6f
    ): MatchResult {
        val deviations = mutableMapOf<String, Float>()
        var matchesCount = 0
        var totalCount = 0

        for ((featureName, meanVal) in meanFeatures) {
            val currentVal = features[featureName] ?: continue

            totalCount++
            val baseTolerance = featureTolerances[featureName] ?: 10f
            val scaledTolerance = baseTolerance * toleranceScale
            val signedDeviation = currentVal - meanVal // Signed for direction feedback
            deviations[featureName] = signedDeviation

            if (abs(signedDeviation) <= scaledTolerance) {
                matchesCount++
            }
        }

        if (totalCount == 0) return MatchResult(false, emptyMap(), 0f)

        val ratio = matchesCount.toFloat() / totalCount
        return MatchResult(
            isMatch = ratio >= matchThreshold,
            deviations = deviations,
            matchRatio = ratio
        )
    }

    companion object {
        fun fromJson(json: JSONObject): PoseState {
            val meanFeatures = mutableMapOf<String, Float>()
            val tolerances = mutableMapOf<String, Float>()
            var keypoints: List<Triple<Float, Float, Float>>? = null

            val meanFeaturesJson = json.getJSONObject("mean_features")
            for (key in meanFeaturesJson.keys()) {
                meanFeatures[key] = meanFeaturesJson.getDouble(key).toFloat()
            }

            val tolerancesJson = json.getJSONObject("feature_tolerances")
            for (key in tolerancesJson.keys()) {
                tolerances[key] = tolerancesJson.getDouble(key).toFloat()
            }

            if (json.has("mean_keypoints")) {
                val kpArray = json.getJSONArray("mean_keypoints")
                keypoints = (0 until kpArray.length()).map { i ->
                    val point = kpArray.getJSONArray(i)
                    Triple(
                        point.getDouble(0).toFloat(),
                        point.getDouble(1).toFloat(),
                        if (point.length() > 2) point.getDouble(2).toFloat() else 1f
                    )
                }
            }

            // Apply mobile tolerance floors — graphs were trained from fixed laptop
            // webcam; phone camera distance/angle varies much more.
            for (key in tolerances.keys.toList()) {
                val floor = when {
                    key == "body_center_y" -> 1.0f   // Very camera-distance-dependent
                    key.endsWith("_elevation") -> 0.3f // Camera-angle-dependent
                    key.endsWith("_spread") -> 0.2f   // Camera-angle-dependent
                    else -> null
                }
                if (floor != null) {
                    tolerances[key] = maxOf(tolerances[key] ?: floor, floor)
                }
            }

            return PoseState(
                stateId = json.getInt("state_id"),
                meanFeatures = meanFeatures,
                featureTolerances = tolerances,
                minHoldDuration = json.getDouble("min_hold_duration").toFloat(),
                meanKeypoints = keypoints
            )
        }
    }
}

/**
 * Result of matching features against a PoseState.
 */
data class MatchResult(
    val isMatch: Boolean,
    val deviations: Map<String, Float>,  // Signed deviations (positive = over target, negative = under)
    val matchRatio: Float                // Fraction of features within tolerance [0, 1]
)

/**
 * Directed graph of pose states with temporal sequence and exercise metadata.
 */
data class PoseGraph(
    val states: List<PoseState>,
    val transitions: Map<Int, List<Int>>,
    val metadata: GraphMetadata
) {
    /** Auto-computed base tolerance multiplier for tight-tolerance graphs */
    val baseToleranceMultiplier: Float by lazy { computeBaseMultiplier() }

    fun getState(stateId: Int): PoseState? = states.find { it.stateId == stateId }

    fun getNextStates(currentStateId: Int): List<Int> = transitions[currentStateId] ?: emptyList()

    fun getStartState(): PoseState? = states.firstOrNull()

    /**
     * If average tolerance across all states is very low, apply a base multiplier
     * to make the graph more forgiving (like Python's ExerciseComplexityAnalyzer).
     */
    private fun computeBaseMultiplier(): Float {
        if (states.isEmpty()) return 1.0f
        val avgTolerance = states.flatMap { it.featureTolerances.values }
            .average().toFloat()
        return when {
            avgTolerance < 3f  -> 1.8f  // Extremely tight
            avgTolerance < 5f  -> 1.5f  // Very tight
            avgTolerance < 8f  -> 1.2f  // Moderately tight
            else -> 1.0f
        }
    }

    companion object {
        fun loadFromAssets(context: Context, filename: String): PoseGraph {
            val jsonStr = context.assets.open(filename).bufferedReader().use { it.readText() }
            return fromJson(JSONObject(jsonStr))
        }

        fun fromJson(json: JSONObject): PoseGraph {
            val metadataJson = json.getJSONObject("metadata")

            // Parse temporal_sequence
            val temporalSequence = mutableListOf<Int>()
            if (metadataJson.has("temporal_sequence")) {
                val seqArr = metadataJson.getJSONArray("temporal_sequence")
                for (i in 0 until seqArr.length()) {
                    temporalSequence.add(seqArr.getInt(i))
                }
            }

            val metadata = GraphMetadata(
                videoPath = metadataJson.optString("video_path", ""),
                fps = metadataJson.optDouble("fps", 30.0).toFloat(),
                numStates = metadataJson.optInt("num_states", 0),
                createdAt = metadataJson.optString("created_at", ""),
                temporalSequence = temporalSequence
            )

            val statesJson = json.getJSONArray("states")
            val states = (0 until statesJson.length()).map { i ->
                PoseState.fromJson(statesJson.getJSONObject(i))
            }

            val transitions = mutableMapOf<Int, MutableList<Int>>()
            val transitionsJson = json.getJSONObject("transitions")
            for (key in transitionsJson.keys()) {
                val fromId = key.toInt()
                val toIds = transitionsJson.getJSONArray(key)
                transitions[fromId] = (0 until toIds.length()).map { toIds.getInt(it) }.toMutableList()
            }

            return PoseGraph(
                states = states,
                transitions = transitions,
                metadata = metadata
            )
        }
    }
}

data class GraphMetadata(
    val videoPath: String,
    val fps: Float,
    val numStates: Int,
    val createdAt: String,
    val temporalSequence: List<Int> = emptyList()
)
