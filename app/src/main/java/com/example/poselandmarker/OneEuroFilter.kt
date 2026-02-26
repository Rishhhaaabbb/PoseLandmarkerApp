package com.example.poselandmarker

import kotlin.math.abs

/**
 * Implementation of the 1€ (One Euro) Filter for real-time signal smoothing.
 *
 * Adapts cutoff frequency based on the speed of change:
 * - Stable signal → low cutoff → heavy smoothing → eliminates jitter
 * - Fast movement → high cutoff → light smoothing → preserves responsiveness
 *
 * Reference: Casiez, Roussel, Vogel. "1€ Filter: A Simple Speed-based
 * Low-pass Filter for Noisy Input in Interactive Systems." CHI 2012.
 *
 * @param minCutoff Minimum cutoff frequency in Hz. Lower = more smoothing.
 * @param beta Speed coefficient. Higher = less lag during fast motion.
 * @param dCutoff Cutoff frequency for the derivative low-pass filter in Hz.
 */
class OneEuroFilter(
    private val minCutoff: Double = 1.5,
    private val beta: Double = 0.01,
    private val dCutoff: Double = 1.0
) {
    private var xFilter: LowPassFilter? = null
    private var dxFilter: LowPassFilter? = null
    private var lastTimestamp: Double = -1.0

    /**
     * Filter a single value.
     * @param value The raw input value
     * @param timestamp Time in seconds (must be monotonically increasing)
     * @return The filtered (smoothed) value
     */
    fun filter(value: Double, timestamp: Double): Double {
        if (lastTimestamp < 0.0) {
            lastTimestamp = timestamp
            xFilter = LowPassFilter(computeAlpha(minCutoff), value)
            dxFilter = LowPassFilter(computeAlpha(dCutoff), 0.0)
            return value
        }

        val dt = timestamp - lastTimestamp
        if (dt <= 0.0) return xFilter?.lastValue() ?: value
        lastTimestamp = timestamp

        // Estimate the derivative of the signal
        val rawDx = (value - (xFilter?.lastValue() ?: value)) / dt
        val filteredDx = dxFilter!!.filter(rawDx, computeAlpha(dCutoff, dt))

        // Adapt the cutoff frequency based on speed of movement
        val adaptiveCutoff = minCutoff + beta * abs(filteredDx)
        return xFilter!!.filter(value, computeAlpha(adaptiveCutoff, dt))
    }

    /** Reset filter state (call when input source changes, e.g., camera flip). */
    fun reset() {
        xFilter = null
        dxFilter = null
        lastTimestamp = -1.0
    }

    /**
     * Compute the smoothing factor alpha for a given cutoff frequency.
     * alpha = 1 / (1 + tau/dt), where tau = 1 / (2π * cutoff)
     */
    private fun computeAlpha(cutoff: Double, dt: Double = 1.0 / 60.0): Double {
        val tau = 1.0 / (2.0 * Math.PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    /** First-order IIR low-pass filter. */
    private class LowPassFilter(private var alpha: Double, private var s: Double) {

        fun lastValue(): Double = s

        fun filter(value: Double, alpha: Double): Double {
            this.alpha = alpha
            s = alpha * value + (1.0 - alpha) * s
            return s
        }
    }
}
