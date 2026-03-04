package com.example.poselandmarker.posecorrection

import android.os.SystemClock

/**
 * Tracks pose state transitions and rep counting.
 */
class PoseStateMachine(
    private val graph: PoseGraph,
    private val minHoldFrames: Int = 5,
    private val transitionCooldownMs: Long = 300
) {
    var currentStateId: Int = graph.getStartState()?.stateId ?: 0
        private set

    var repCount: Int = 0
        private set

    var currentDeviations: Map<String, Float> = emptyMap()
        private set

    var isInTargetPose: Boolean = false
        private set

    private var holdFrameCount: Int = 0
    private var lastTransitionTime: Long = 0
    private var startStateReached: Boolean = false

    // For rep counting: track when we complete a full cycle back to start
    private val startStateId: Int = graph.getStartState()?.stateId ?: 0

    /**
     * Update state machine with new features.
     * @param features Current extracted pose features
     * @return StateUpdate with current state info
     */
    fun update(features: Map<String, Float>): StateUpdate {
        val now = SystemClock.uptimeMillis()

        // Check if we match the current target state
        val currentState = graph.getState(currentStateId) ?: return StateUpdate(
            stateId = currentStateId,
            stateName = "Unknown",
            repCount = repCount,
            isMatching = false,
            deviations = emptyMap(),
            feedback = "No pose graph loaded"
        )

        val (matchesCurrent, deviationsCurrent) = currentState.matches(features)
        currentDeviations = deviationsCurrent
        isInTargetPose = matchesCurrent

        if (matchesCurrent) {
            holdFrameCount++

            // Check for transition to next state(s)
            if (holdFrameCount >= minHoldFrames && now - lastTransitionTime > transitionCooldownMs) {
                val nextStates = graph.getNextStates(currentStateId)

                if (nextStates.isNotEmpty()) {
                    // Prefer the next state that we also match (for branching graphs)
                    var bestNextStateId = nextStates.first()

                    for (nextId in nextStates) {
                        val nextState = graph.getState(nextId)
                        if (nextState != null) {
                            val (matchesNext, _) = nextState.matches(features)
                            if (matchesNext) {
                                bestNextStateId = nextId
                                break
                            }
                        }
                    }

                    // Check if completing a rep (returning to start state)
                    if (bestNextStateId == startStateId && startStateReached) {
                        repCount++
                    }

                    currentStateId = bestNextStateId
                    holdFrameCount = 0
                    lastTransitionTime = now
                    startStateReached = true
                }
            }
        } else {
            // Not matching - check if we match any valid next state
            val nextStates = graph.getNextStates(currentStateId)

            for (nextId in nextStates) {
                val nextState = graph.getState(nextId)
                if (nextState != null) {
                    val (matchesNext, deviationsNext) = nextState.matches(features)
                    if (matchesNext && now - lastTransitionTime > transitionCooldownMs) {
                        // Check if completing a rep
                        if (nextId == startStateId && startStateReached) {
                            repCount++
                        }

                        currentStateId = nextId
                        currentDeviations = deviationsNext
                        isInTargetPose = true
                        holdFrameCount = 1
                        lastTransitionTime = now
                        startStateReached = true
                        break
                    }
                }
            }

            // Reset hold count if not matching current state
            if (!isInTargetPose) {
                holdFrameCount = 0
            }
        }

        val stateName = "State ${currentStateId + 1}/${graph.states.size}"

        return StateUpdate(
            stateId = currentStateId,
            stateName = stateName,
            repCount = repCount,
            isMatching = isInTargetPose,
            deviations = currentDeviations,
            feedback = if (isInTargetPose) "Good! Hold the pose" else "Adjust to match pose"
        )
    }

    /**
     * Reset the state machine for a new session.
     */
    fun reset() {
        currentStateId = startStateId
        repCount = 0
        holdFrameCount = 0
        lastTransitionTime = 0
        startStateReached = false
        currentDeviations = emptyMap()
        isInTargetPose = false
    }
}

data class StateUpdate(
    val stateId: Int,
    val stateName: String,
    val repCount: Int,
    val isMatching: Boolean,
    val deviations: Map<String, Float>,
    val feedback: String
)
