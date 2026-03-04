package com.example.poselandmarker.posecorrection

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Extracts normalized geometric features from MediaPipe 33-landmark skeleton.
 * 
 * MediaPipe Pose Landmark indices:
 * 0: nose, 11: left_shoulder, 12: right_shoulder, 13: left_elbow, 14: right_elbow,
 * 15: left_wrist, 16: right_wrist, 23: left_hip, 24: right_hip,
 * 25: left_knee, 26: right_knee, 27: left_ankle, 28: right_ankle
 */
class PoseFeatureExtractor(
    private val confidenceThreshold: Float = 0.5f
) {
    companion object {
        // MediaPipe pose landmark indices
        const val NOSE = 0
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_ELBOW = 13
        const val RIGHT_ELBOW = 14
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25
        const val RIGHT_KNEE = 26
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28
    }

    /**
     * Extract normalized pose features from landmarks.
     * @param landmarks List of 33 normalized landmarks from MediaPipe
     * @return Map of feature names to values, or null if insufficient confidence
     */
    fun extractFeatures(landmarks: List<NormalizedLandmark>): Map<String, Float>? {
        if (landmarks.size < 33) return null
        if (!validateLandmarks(landmarks)) return null

        val bodyScale = computeBodyScale(landmarks)
        if (bodyScale < 0.01f) return null // Too small

        val features = mutableMapOf<String, Float>()

        // Joint angles
        computeJointAngles(landmarks)?.let { features.putAll(it) }

        // Torso angle
        features["torso_angle"] = computeTorsoAngle(landmarks)

        // Arm elevations
        computeArmElevation(landmarks, LEFT_WRIST, LEFT_SHOULDER, bodyScale)?.let {
            features["left_arm_elevation"] = it
        }
        computeArmElevation(landmarks, RIGHT_WRIST, RIGHT_SHOULDER, bodyScale)?.let {
            features["right_arm_elevation"] = it
        }

        // Leg spread
        computeLegSpread(landmarks, bodyScale).let { features.putAll(it) }

        // Body center Y
        features["body_center_y"] = computeBodyCenterY(landmarks, bodyScale)

        return features
    }

    private fun validateLandmarks(landmarks: List<NormalizedLandmark>): Boolean {
        val criticalIndices = listOf(
            LEFT_SHOULDER, RIGHT_SHOULDER,
            LEFT_HIP, RIGHT_HIP,
            LEFT_KNEE, RIGHT_KNEE
        )
        val validCount = criticalIndices.count { idx ->
            landmarks.getOrNull(idx)?.let { it.visibility().orElse(0f) >= confidenceThreshold } ?: false
        }
        return validCount >= 4
    }

    private fun computeBodyScale(landmarks: List<NormalizedLandmark>): Float {
        val leftShoulder = landmarks[LEFT_SHOULDER]
        val rightShoulder = landmarks[RIGHT_SHOULDER]
        val leftHip = landmarks[LEFT_HIP]
        val rightHip = landmarks[RIGHT_HIP]

        val shoulderCenterX = (leftShoulder.x() + rightShoulder.x()) / 2f
        val shoulderCenterY = (leftShoulder.y() + rightShoulder.y()) / 2f
        val hipCenterX = (leftHip.x() + rightHip.x()) / 2f
        val hipCenterY = (leftHip.y() + rightHip.y()) / 2f

        val dx = shoulderCenterX - hipCenterX
        val dy = shoulderCenterY - hipCenterY
        return sqrt(dx * dx + dy * dy)
    }

    private fun computeJointAngles(landmarks: List<NormalizedLandmark>): Map<String, Float>? {
        val angles = mutableMapOf<String, Float>()

        // Left elbow angle
        computeAngle(
            landmarks[LEFT_SHOULDER], landmarks[LEFT_ELBOW], landmarks[LEFT_WRIST]
        )?.let { angles["left_elbow_angle"] = it }

        // Right elbow angle
        computeAngle(
            landmarks[RIGHT_SHOULDER], landmarks[RIGHT_ELBOW], landmarks[RIGHT_WRIST]
        )?.let { angles["right_elbow_angle"] = it }

        // Left knee angle
        computeAngle(
            landmarks[LEFT_HIP], landmarks[LEFT_KNEE], landmarks[LEFT_ANKLE]
        )?.let { angles["left_knee_angle"] = it }

        // Right knee angle
        computeAngle(
            landmarks[RIGHT_HIP], landmarks[RIGHT_KNEE], landmarks[RIGHT_ANKLE]
        )?.let { angles["right_knee_angle"] = it }

        // Left shoulder angle (arm relative to torso)
        computeAngle(
            landmarks[LEFT_HIP], landmarks[LEFT_SHOULDER], landmarks[LEFT_ELBOW]
        )?.let { angles["left_shoulder_angle"] = it }

        // Right shoulder angle
        computeAngle(
            landmarks[RIGHT_HIP], landmarks[RIGHT_SHOULDER], landmarks[RIGHT_ELBOW]
        )?.let { angles["right_shoulder_angle"] = it }

        // Left hip angle (thigh relative to torso)
        computeAngle(
            landmarks[LEFT_SHOULDER], landmarks[LEFT_HIP], landmarks[LEFT_KNEE]
        )?.let { angles["left_hip_angle"] = it }

        // Right hip angle
        computeAngle(
            landmarks[RIGHT_SHOULDER], landmarks[RIGHT_HIP], landmarks[RIGHT_KNEE]
        )?.let { angles["right_hip_angle"] = it }

        return if (angles.isNotEmpty()) angles else null
    }

    /**
     * Compute angle at p2 formed by p1-p2-p3. Returns angle in degrees [0, 180].
     */
    private fun computeAngle(
        p1: NormalizedLandmark,
        p2: NormalizedLandmark,
        p3: NormalizedLandmark
    ): Float? {
        val minVis = minOf(
            p1.visibility().orElse(0f),
            p2.visibility().orElse(0f),
            p3.visibility().orElse(0f)
        )
        if (minVis < confidenceThreshold) return null

        val v1x = p1.x() - p2.x()
        val v1y = p1.y() - p2.y()
        val v2x = p3.x() - p2.x()
        val v2y = p3.y() - p2.y()

        val v1Norm = sqrt(v1x * v1x + v1y * v1y)
        val v2Norm = sqrt(v2x * v2x + v2y * v2y)

        if (v1Norm < 1e-6f || v2Norm < 1e-6f) return null

        val dot = (v1x * v2x + v1y * v2y) / (v1Norm * v2Norm)
        val clampedDot = dot.coerceIn(-1f, 1f)
        return Math.toDegrees(acos(clampedDot).toDouble()).toFloat()
    }

    /**
     * Compute torso orientation angle relative to vertical.
     * Returns angle in degrees [-90, 90] where 0 is upright.
     */
    private fun computeTorsoAngle(landmarks: List<NormalizedLandmark>): Float {
        val leftShoulder = landmarks[LEFT_SHOULDER]
        val rightShoulder = landmarks[RIGHT_SHOULDER]
        val leftHip = landmarks[LEFT_HIP]
        val rightHip = landmarks[RIGHT_HIP]

        val shoulderCenterX = (leftShoulder.x() + rightShoulder.x()) / 2f
        val shoulderCenterY = (leftShoulder.y() + rightShoulder.y()) / 2f
        val hipCenterX = (leftHip.x() + rightHip.x()) / 2f
        val hipCenterY = (leftHip.y() + rightHip.y()) / 2f

        val torsoVecX = shoulderCenterX - hipCenterX
        val torsoVecY = shoulderCenterY - hipCenterY

        // Angle from vertical (in normalized coords, y increases downward)
        return Math.toDegrees(atan2(torsoVecX.toDouble(), (-torsoVecY).toDouble())).toFloat()
    }

    /**
     * Compute normalized vertical distance between wrist and shoulder.
     * Positive = wrist above shoulder, Negative = wrist below shoulder.
     */
    private fun computeArmElevation(
        landmarks: List<NormalizedLandmark>,
        wristIdx: Int,
        shoulderIdx: Int,
        bodyScale: Float
    ): Float? {
        val wrist = landmarks[wristIdx]
        val shoulder = landmarks[shoulderIdx]

        if (wrist.visibility().orElse(0f) < confidenceThreshold ||
            shoulder.visibility().orElse(0f) < confidenceThreshold) {
            return null
        }

        // Normalize by body scale (negative because y increases downward)
        return -(wrist.y() - shoulder.y()) / bodyScale
    }

    /**
     * Compute normalized horizontal distances for leg positioning.
     */
    private fun computeLegSpread(
        landmarks: List<NormalizedLandmark>,
        bodyScale: Float
    ): Map<String, Float> {
        val spread = mutableMapOf<String, Float>()

        val leftHip = landmarks[LEFT_HIP]
        val leftAnkle = landmarks[LEFT_ANKLE]
        val rightHip = landmarks[RIGHT_HIP]
        val rightAnkle = landmarks[RIGHT_ANKLE]

        if (leftHip.visibility().orElse(0f) >= confidenceThreshold &&
            leftAnkle.visibility().orElse(0f) >= confidenceThreshold) {
            spread["left_leg_spread"] = abs(leftAnkle.x() - leftHip.x()) / bodyScale
        }

        if (rightHip.visibility().orElse(0f) >= confidenceThreshold &&
            rightAnkle.visibility().orElse(0f) >= confidenceThreshold) {
            spread["right_leg_spread"] = abs(rightAnkle.x() - rightHip.x()) / bodyScale
        }

        return spread
    }

    /**
     * Compute body center height (for standing/sitting/ground poses).
     */
    private fun computeBodyCenterY(
        landmarks: List<NormalizedLandmark>,
        bodyScale: Float
    ): Float {
        val leftHip = landmarks[LEFT_HIP]
        val rightHip = landmarks[RIGHT_HIP]
        val leftShoulder = landmarks[LEFT_SHOULDER]
        val rightShoulder = landmarks[RIGHT_SHOULDER]

        val centerY = (leftHip.y() + rightHip.y() + leftShoulder.y() + rightShoulder.y()) / 4f
        return centerY / bodyScale
    }
}
