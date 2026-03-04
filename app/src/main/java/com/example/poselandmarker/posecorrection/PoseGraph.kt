package com.example.poselandmarker.posecorrection

import android.content.Context
import org.json.JSONArray
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
    val meanKeypoints: List<Pair<Float, Float>>? = null
) {
    /**
     * Check if given features match this state within tolerances.
     * @param features Feature dictionary to check
     * @param strict If true, all features must match. If false, allow partial match (60%)
     * @return Pair of (matches, deviations map)
     */
    fun matches(features: Map<String, Float>, strict: Boolean = false): Pair<Boolean, Map<String, Float>> {
        val deviations = mutableMapOf<String, Float>()
        var matchesCount = 0
        var totalCount = 0

        for ((featureName, meanVal) in meanFeatures) {
            val currentVal = features[featureName] ?: if (strict) return Pair(false, emptyMap()) else continue

            totalCount++
            val tolerance = featureTolerances[featureName] ?: 10f
            val deviation = abs(currentVal - meanVal)
            deviations[featureName] = deviation

            if (deviation <= tolerance) {
                matchesCount++
            }
        }

        if (totalCount == 0) return Pair(false, emptyMap())

        val matches = if (strict) {
            matchesCount == totalCount
        } else {
            matchesCount.toFloat() / totalCount >= 0.6f
        }

        return Pair(matches, deviations)
    }

    companion object {
        fun fromJson(json: JSONObject): PoseState {
            val meanFeatures = mutableMapOf<String, Float>()
            val tolerances = mutableMapOf<String, Float>()
            var keypoints: List<Pair<Float, Float>>? = null

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
                    Pair(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
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
 * Directed graph of pose states and their transitions.
 */
data class PoseGraph(
    val states: List<PoseState>,
    val transitions: Map<Int, List<Int>>,  // state_id -> [next_state_ids]
    val metadata: GraphMetadata
) {
    fun getState(stateId: Int): PoseState? = states.find { it.stateId == stateId }

    fun getNextStates(currentStateId: Int): List<Int> = transitions[currentStateId] ?: emptyList()

    fun getStartState(): PoseState? = states.firstOrNull()

    companion object {
        /**
         * Load a pose graph from a JSON file in the assets folder.
         */
        fun loadFromAssets(context: Context, filename: String): PoseGraph {
            val jsonStr = context.assets.open(filename).bufferedReader().use { it.readText() }
            return fromJson(JSONObject(jsonStr))
        }

        fun fromJson(json: JSONObject): PoseGraph {
            val metadataJson = json.getJSONObject("metadata")
            val metadata = GraphMetadata(
                videoPath = metadataJson.optString("video_path", ""),
                fps = metadataJson.optDouble("fps", 30.0).toFloat(),
                numStates = metadataJson.optInt("num_states", 0),
                createdAt = metadataJson.optString("created_at", "")
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
    val createdAt: String
)
