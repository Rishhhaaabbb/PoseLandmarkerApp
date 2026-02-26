package com.example.poselandmarker

/**
 * Causal Savitzky-Golay smoothing filter for real-time signals.
 *
 * Uses a sliding window of recent samples and fits a 2nd-order (quadratic)
 * polynomial, then evaluates at the most recent point. Only uses past and
 * current samples (no future look-ahead), making it suitable for real-time
 * streaming applications.
 *
 * Pre-computed convolution coefficients for quadratic fitting evaluated at
 * the rightmost (newest) sample, derived from:
 *     h = [1, t, t²] · (Vᵀ V)⁻¹ · Vᵀ
 * where V is the Vandermonde matrix and t = windowSize − 1.
 *
 * @param windowSize Number of samples in the sliding window (3, 5, or 7).
 */
class SavitzkyGolayFilter(windowSize: Int = 5) {

    private val buffer = ArrayDeque<Double>()
    private val coefficients: DoubleArray
    private val effectiveWindowSize: Int

    init {
        val (coeffs, ws) = when {
            windowSize >= 7 -> Pair(
                // Window 7, order 2: [5, −3, −6, −4, 3, 15, 32] / 42
                doubleArrayOf(5.0/42, -3.0/42, -6.0/42, -4.0/42, 3.0/42, 15.0/42, 32.0/42),
                7
            )
            windowSize >= 5 -> Pair(
                // Window 5, order 2: [3, −5, −3, 9, 31] / 35
                doubleArrayOf(3.0/35, -5.0/35, -3.0/35, 9.0/35, 31.0/35),
                5
            )
            else -> Pair(
                // Window 3, order 2: exact interpolation (identity at rightmost point)
                doubleArrayOf(0.0, 0.0, 1.0),
                3
            )
        }
        coefficients = coeffs
        effectiveWindowSize = ws
    }

    /**
     * Filter a single value using Savitzky-Golay smoothing.
     * @param value The raw input value
     * @return The smoothed value
     */
    fun filter(value: Double): Double {
        buffer.addLast(value)
        if (buffer.size > effectiveWindowSize) buffer.removeFirst()

        // Not enough samples yet — pass through raw value
        if (buffer.size < effectiveWindowSize) return value

        // Convolve with pre-computed coefficients
        var result = 0.0
        val data = buffer.toList()
        for (i in coefficients.indices) {
            result += coefficients[i] * data[i]
        }
        return result
    }

    /** Reset buffer (call when input source changes). */
    fun reset() {
        buffer.clear()
    }
}
