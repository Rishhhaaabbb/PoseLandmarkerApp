package com.example.poselandmarker.posecorrection

import android.os.SystemClock

/**
 * Enhanced pose state machine that follows the temporal_sequence, tracks
 * hold timers, accuracy, progressive tolerances, and transition grace periods.
 *
 * Ported from Python online_monitor_final.py's EnhancedPoseMonitor logic.
 */
class PoseStateMachine(
    private val graph: PoseGraph,
    private val enableAdaptive: Boolean = true,
    private val enablePreview: Boolean = true,
    private val previewDurationMs: Long = 2500
) {
    // ── Temporal sequence tracking ──────────────────────────────────────
    private val temporalSequence: List<Int> = graph.metadata.temporalSequence.ifEmpty {
        // Fallback: create sequence from states [0, 1, 2, ..., n-1]
        (0 until graph.states.size).toList()
    }
    private var sequenceIndex: Int = 0
    val currentTargetStateId: Int get() = temporalSequence.getOrElse(sequenceIndex) { 0 }
    val currentState: PoseState? get() = graph.getState(currentTargetStateId)

    // ── Rep counting ────────────────────────────────────────────────────
    var repCount: Int = 0
        private set
    var maxReps: Int? = null  // null = unlimited

    // ── Hold timer ──────────────────────────────────────────────────────
    private var holdStartTimeMs: Long = 0
    private var accumulatedHoldMs: Long = 0
    private var wasHoldingLastFrame: Boolean = false

    /** Progress through the hold requirement [0.0, 1.0] */
    val holdProgress: Float get() {
        val state = currentState ?: return 0f
        val requiredMs = (state.minHoldDuration * 1000).toLong()
        if (requiredMs <= 0) return 1f
        return (accumulatedHoldMs.toFloat() / requiredMs).coerceIn(0f, 1f)
    }

    /** Remaining hold time in seconds */
    val holdRemainingSeconds: Float get() {
        val state = currentState ?: return 0f
        val requiredMs = (state.minHoldDuration * 1000).toLong()
        val remainingMs = (requiredMs - accumulatedHoldMs).coerceAtLeast(0)
        return remainingMs / 1000f
    }

    // ── Transition grace period ─────────────────────────────────────────
    private var lastTransitionTimeMs: Long = 0
    private val gracePeriodMs: Long = 400
    val isInGracePeriod: Boolean get() {
        if (lastTransitionTimeMs == 0L) return false
        return SystemClock.uptimeMillis() - lastTransitionTimeMs < gracePeriodMs
    }

    // ── Preview / Guidance ──────────────────────────────────────────────
    var isGuidanceActive: Boolean = false
        private set
    var nextStatePreview: PoseState? = null
        private set
    private var guidanceStartTimeMs: Long = 0

    /** Remaining guidance countdown in seconds */
    val guidanceRemainingSeconds: Float get() {
        if (!isGuidanceActive) return 0f
        val elapsedMs = SystemClock.uptimeMillis() - guidanceStartTimeMs
        return ((previewDurationMs - elapsedMs) / 1000f).coerceAtLeast(0f)
    }

    // ── Progressive tolerance ───────────────────────────────────────────
    private var noMatchStartTimeMs: Long = 0
    private val progressiveStartDelayMs: Long = 5000   // Start widening after 5s of struggle
    private val progressiveMaxScale: Float = 2.0f
    private val progressiveRampMs: Float = 10000f       // Ramp up over 10s

    /** Current effective tolerance scale = base * progressive */
    val currentToleranceScale: Float get() {
        var scale = graph.baseToleranceMultiplier
        if (enableAdaptive && noMatchStartTimeMs > 0) {
            val struggleMs = SystemClock.uptimeMillis() - noMatchStartTimeMs
            if (struggleMs > progressiveStartDelayMs) {
                val progressMs = (struggleMs - progressiveStartDelayMs).coerceAtMost(progressiveRampMs.toLong())
                val extra = (progressMs / progressiveRampMs) * (progressiveMaxScale - 1f)
                scale *= (1f + extra)
            }
        }
        return scale
    }

    // ── Accuracy tracking ───────────────────────────────────────────────
    private var greenFrames: Int = 0
    private var evaluatedFrames: Int = 0

    /** Current accuracy percentage [0, 100] */
    val accuracyPercent: Int get() {
        return if (evaluatedFrames > 0) ((greenFrames.toFloat() / evaluatedFrames) * 100).toInt() else 0
    }

    // ── Streak tracking ─────────────────────────────────────────────────
    private var currentStreak: Int = 0
    var maxStreak: Int = 0
        private set

    // ── State output ────────────────────────────────────────────────────
    var isInTargetPose: Boolean = false
        private set
    var currentDeviations: Map<String, Float> = emptyMap()
        private set
    var currentMatchRatio: Float = 0f
        private set
    var sessionComplete: Boolean = false
        private set

    // ── Per-joint error ratios for visualization ────────────────────────
    /** Map of feature name → error ratio (deviation / tolerance). >1.0 means out of tolerance */
    var perJointErrorRatios: Map<String, Float> = emptyMap()
        private set

    /**
     * Process one frame of features through the state machine.
     * @param features  Extracted pose features (normal orientation)
     * @param mirroredFeatures  Same features with left/right swapped (for front camera)
     * @return StateUpdate with all current state info
     */
    fun update(
        features: Map<String, Float>,
        mirroredFeatures: Map<String, Float>? = null
    ): StateUpdate {
        val now = SystemClock.uptimeMillis()

        // Check if session is complete
        if (sessionComplete) return buildUpdate("Session complete!")

        // Handle guidance countdown
        if (isGuidanceActive) {
            if (now - guidanceStartTimeMs >= previewDurationMs) {
                // Guidance period over → activate the next state
                finalizeTransition(now)
            }
            return buildUpdate("Get ready for next pose!")
        }

        // During grace period, don't evaluate
        if (isInGracePeriod) {
            return buildUpdate("Transitioning...")
        }

        val state = currentState ?: return buildUpdate("No pose graph loaded")

        // Try both normal and mirrored features, use better match
        val normalResult = state.matches(features, currentToleranceScale)
        val mirrorResult = mirroredFeatures?.let { state.matches(it, currentToleranceScale) }

        val bestResult: MatchResult
        val bestDeviations: Map<String, Float>

        if (mirrorResult != null && mirrorResult.matchRatio > normalResult.matchRatio) {
            bestResult = mirrorResult
            bestDeviations = mirrorResult.deviations
        } else {
            bestResult = normalResult
            bestDeviations = normalResult.deviations
        }

        currentDeviations = bestDeviations
        currentMatchRatio = bestResult.matchRatio
        isInTargetPose = bestResult.isMatch

        // Compute per-joint error ratios
        perJointErrorRatios = bestDeviations.mapValues { (feature, signedDev) ->
            val tolerance = state.featureTolerances[feature] ?: 10f
            val scaledTol = tolerance * currentToleranceScale
            kotlin.math.abs(signedDev) / scaledTol
        }

        // ── Accuracy tracking ───────────────────────────────────────
        evaluatedFrames++
        if (isInTargetPose) {
            greenFrames++
            currentStreak++
            if (currentStreak > maxStreak) maxStreak = currentStreak
        } else {
            currentStreak = 0
        }

        // ── Progressive tolerance tracking ──────────────────────────
        if (isInTargetPose) {
            noMatchStartTimeMs = 0  // Reset struggle timer on success
        } else {
            if (noMatchStartTimeMs == 0L) noMatchStartTimeMs = now
        }

        // ── Hold timer ──────────────────────────────────────────────
        if (isInTargetPose) {
            if (!wasHoldingLastFrame) {
                holdStartTimeMs = now
            } else {
                accumulatedHoldMs += (now - holdStartTimeMs)
                holdStartTimeMs = now
            }
            wasHoldingLastFrame = true

            // Check if hold is complete
            val requiredMs = (state.minHoldDuration * 1000).toLong()
            if (accumulatedHoldMs >= requiredMs) {
                advanceToNextState(now)
            }
        } else {
            wasHoldingLastFrame = false
            // Don't reset accumulated hold — let it persist (lenient)
            // Only reset if they've been off for more than 2 seconds
            if (holdStartTimeMs > 0 && now - holdStartTimeMs > 2000) {
                accumulatedHoldMs = (accumulatedHoldMs * 0.8f).toLong() // Decay instead of hard reset
            }
            holdStartTimeMs = now
        }

        val feedbackText = if (isInTargetPose) {
            "Hold! ${holdRemainingSeconds.let { if (it > 0.1f) String.format("%.1fs", it) else "Done!" }}"
        } else {
            "" // Let the FeedbackGenerator handle this
        }

        return buildUpdate(feedbackText)
    }

    /**
     * Advance to the next state in the temporal sequence.
     */
    private fun advanceToNextState(now: Long) {
        val nextIdx = sequenceIndex + 1

        if (nextIdx >= temporalSequence.size) {
            // Completed one full cycle → increment rep
            repCount++

            // Check if we've reached max reps
            if (maxReps != null && repCount >= maxReps!!) {
                sessionComplete = true
                return
            }

            // Reset sequence to start
            sequenceIndex = 0
        } else {
            sequenceIndex = nextIdx
        }

        val nextState = graph.getState(currentTargetStateId)

        if (enablePreview && nextState != null) {
            // Show preview of next state before transitioning
            nextStatePreview = nextState
            isGuidanceActive = true
            guidanceStartTimeMs = now
        } else {
            // Direct transition
            finalizeTransition(now)
        }
    }

    /**
     * Finalize the transition (after preview ends, or immediately if no preview).
     */
    private fun finalizeTransition(now: Long) {
        isGuidanceActive = false
        nextStatePreview = null
        lastTransitionTimeMs = now

        // Reset hold state for new target
        accumulatedHoldMs = 0
        holdStartTimeMs = 0
        wasHoldingLastFrame = false

        // Reset progressive tolerance
        noMatchStartTimeMs = 0

        // Reset accuracy for new state
        greenFrames = 0
        evaluatedFrames = 0
        currentStreak = 0
    }

    private fun buildUpdate(feedback: String): StateUpdate {
        val state = currentState
        return StateUpdate(
            stateId = currentTargetStateId,
            stateName = "Pose ${currentTargetStateId + 1}/${graph.states.size}",
            sequenceProgress = "${sequenceIndex + 1}/${temporalSequence.size}",
            repCount = repCount,
            isMatching = isInTargetPose,
            deviations = currentDeviations,
            feedback = feedback,
            holdProgress = holdProgress,
            holdRemainingSeconds = holdRemainingSeconds,
            accuracyPercent = accuracyPercent,
            matchRatio = currentMatchRatio,
            isInGracePeriod = isInGracePeriod,
            isGuidanceActive = isGuidanceActive,
            guidanceRemainingSeconds = guidanceRemainingSeconds,
            nextStatePreview = nextStatePreview,
            currentTargetKeypoints = state?.meanKeypoints,
            perJointErrorRatios = perJointErrorRatios,
            sessionComplete = sessionComplete,
            streak = currentStreak,
            toleranceScale = currentToleranceScale
        )
    }

    /**
     * Reset the state machine for a new session.
     */
    fun reset() {
        sequenceIndex = 0
        repCount = 0
        accumulatedHoldMs = 0
        holdStartTimeMs = 0
        wasHoldingLastFrame = false
        lastTransitionTimeMs = 0
        noMatchStartTimeMs = 0
        greenFrames = 0
        evaluatedFrames = 0
        currentStreak = 0
        maxStreak = 0
        currentDeviations = emptyMap()
        isInTargetPose = false
        sessionComplete = false
        isGuidanceActive = false
        nextStatePreview = null
        perJointErrorRatios = emptyMap()
        currentMatchRatio = 0f
    }
}

data class StateUpdate(
    val stateId: Int,
    val stateName: String,
    val sequenceProgress: String,
    val repCount: Int,
    val isMatching: Boolean,
    val deviations: Map<String, Float>,
    val feedback: String,
    val holdProgress: Float,
    val holdRemainingSeconds: Float,
    val accuracyPercent: Int,
    val matchRatio: Float,
    val isInGracePeriod: Boolean,
    val isGuidanceActive: Boolean,
    val guidanceRemainingSeconds: Float,
    val nextStatePreview: PoseState?,
    val currentTargetKeypoints: List<Triple<Float, Float, Float>>?,
    val perJointErrorRatios: Map<String, Float>,
    val sessionComplete: Boolean,
    val streak: Int,
    val toleranceScale: Float
)
