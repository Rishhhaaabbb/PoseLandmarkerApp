package com.example.poselandmarker

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Two-stage landmark smoother combining One Euro and Savitzky-Golay filters
 * to eliminate skeleton jitter while preserving responsive tracking.
 *
 * Pipeline:
 *   Raw landmarks → 1€ Filter (adaptive jitter removal)
 *                  → Savitzky-Golay (polynomial shape preservation)
 *                  → Clamped output [0, 1]
 *
 * Each of the 33 pose landmarks has independent X and Y filter chains,
 * giving 33 × 2 × 2 = 132 individual filter instances.
 *
 * @param numLandmarks Number of landmarks per pose (33 for MediaPipe BlazePose).
 * @param useOneEuro Enable the One Euro filter stage.
 * @param useSavitzkyGolay Enable the Savitzky-Golay filter stage.
 * @param minCutoff OE minimum cutoff frequency (Hz). Lower = smoother but laggier.
 * @param beta OE speed coefficient. Higher = faster response to movement.
 * @param dCutoff OE derivative filter cutoff (Hz).
 * @param sgWindowSize SG sliding window size (3, 5, or 7).
 */
class LandmarkSmoother(
    private val numLandmarks: Int = 33,
    private val useOneEuro: Boolean = true,
    private val useSavitzkyGolay: Boolean = false,
    minCutoff: Double = 1.2,
    beta: Double = 1.5,
    dCutoff: Double = 1.0,
    sgWindowSize: Int = 3
) {
    // One Euro filters: one per axis per landmark
    private val oeFiltersX: Array<OneEuroFilter>? =
        if (useOneEuro) Array(numLandmarks) { OneEuroFilter(minCutoff, beta, dCutoff) } else null
    private val oeFiltersY: Array<OneEuroFilter>? =
        if (useOneEuro) Array(numLandmarks) { OneEuroFilter(minCutoff, beta, dCutoff) } else null

    // Savitzky-Golay filters: one per axis per landmark
    private val sgFiltersX: Array<SavitzkyGolayFilter>? =
        if (useSavitzkyGolay) Array(numLandmarks) { SavitzkyGolayFilter(sgWindowSize) } else null
    private val sgFiltersY: Array<SavitzkyGolayFilter>? =
        if (useSavitzkyGolay) Array(numLandmarks) { SavitzkyGolayFilter(sgWindowSize) } else null

    /**
     * Smooth a list of normalized landmarks through the OE → SG pipeline.
     *
     * @param landmarks Raw landmarks from MediaPipe (normalized 0..1)
     * @return List of [x, y] float arrays with smoothed coordinates
     */
    fun smooth(landmarks: List<NormalizedLandmark>): List<FloatArray> {
        val timestamp = System.nanoTime() / 1_000_000_000.0 // seconds

        return landmarks.mapIndexed { i, lm ->
            var x = lm.x().toDouble()
            var y = lm.y().toDouble()

            // Stage 1: One Euro — adaptive jitter removal
            if (oeFiltersX != null && oeFiltersY != null && i < oeFiltersX.size) {
                x = oeFiltersX[i].filter(x, timestamp)
                y = oeFiltersY[i].filter(y, timestamp)
            }

            // Stage 2: Savitzky-Golay — polynomial trend preservation
            if (sgFiltersX != null && sgFiltersY != null && i < sgFiltersX.size) {
                x = sgFiltersX[i].filter(x)
                y = sgFiltersY[i].filter(y)
            }

            // Clamp to valid normalized coordinate range
            floatArrayOf(
                x.coerceIn(0.0, 1.0).toFloat(),
                y.coerceIn(0.0, 1.0).toFloat()
            )
        }
    }

    /**
     * Reset all filter states.
     * Call on camera switch, prolonged detection loss, or delegate change.
     */
    fun reset() {
        oeFiltersX?.forEach { it.reset() }
        oeFiltersY?.forEach { it.reset() }
        sgFiltersX?.forEach { it.reset() }
        sgFiltersY?.forEach { it.reset() }
    }
}
