package com.herrderb.pssst

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Face-down detection modeled on the parameters Google Play Services sends to the
 * Pixel "shush" nanoapp (contextmanager flags, defaults as of GMS 26.42).
 * Pure Kotlin so it can be unit tested without a device.
 */
class FlipDetector(private val params: Params = Params()) {

    data class Params(
        /** shush_accel_averaging_rate: low-pass factor per sample. */
        val averagingRate: Float = 0.4f,
        /** shush_accel_magnitude_tolerance: |a| must be within g ± this (m/s²). */
        val magnitudeTolerance: Float = 5.0f,
        /** shush_accel_variation_threshold: stillness limit when proximity backs us up. */
        val variationWithProximity: Float = 0.15f,
        /** no_prox_accel_variation_threshold: stricter stillness limit without proximity. */
        val variationWithoutProximity: Float = 0.065f,
        /** shush_gravity_angle_tolerance: max angle (rad) between gravity and screen normal. */
        val angleTolerance: Float = 0.1f,
        /** shush_minimum_facedown_duration_second. */
        val minFaceDownSeconds: Float = 1.0f,
        /** lenient_accel_diff: gravity change (m/s²) from the face-down pose that ends a session. */
        val exitAccelDiff: Float = 3.0f,
        /** shush_trigger_quota_size / shush_trigger_quota_replenish_rate (per second). */
        val quotaSize: Float = 30f,
        val quotaReplenishPerSecond: Float = 0.04f,
    )

    enum class Event { FLIPPED, UNFLIPPED }

    var isFlipped = false
        private set

    /** True while the proximity sensor is part of the decision (screen off). */
    var proximityBacked = false

    // Latest values, for debug logging.
    var angle = Float.NaN
        private set
    var variation = 0f
        private set

    private var avg: FloatArray? = null
    private var faceDownSinceNs = -1L
    private val pose = FloatArray(3)
    private var quota = params.quotaSize
    private var lastQuotaNs = -1L

    /** Forget filter history, e.g. after the accelerometer was off for a while. */
    fun resetFilter() {
        avg = null
        variation = 0f
        faceDownSinceNs = -1L
    }

    fun reset() {
        resetFilter()
        isFlipped = false
    }

    fun onAccel(x: Float, y: Float, z: Float, timestampNs: Long): Event? {
        replenishQuota(timestampNs)

        val a = avg
        if (a == null) {
            avg = floatArrayOf(x, y, z)
            return null
        }
        val dev = norm(x - a[0], y - a[1], z - a[2])
        val r = params.averagingRate
        a[0] += r * (x - a[0])
        a[1] += r * (y - a[1])
        a[2] += r * (z - a[2])
        variation += r * (dev - variation)

        val mag = norm(a[0], a[1], a[2])
        angle = if (mag > 0f) acos((-a[2] / mag).coerceIn(-1f, 1f)) else Float.NaN

        if (isFlipped) {
            if (norm(a[0] - pose[0], a[1] - pose[1], a[2] - pose[2]) > params.exitAccelDiff) {
                isFlipped = false
                faceDownSinceNs = -1L
                return Event.UNFLIPPED
            }
            return null
        }

        val stillLimit = if (proximityBacked) params.variationWithProximity else params.variationWithoutProximity
        val faceDown = abs(mag - GRAVITY) < params.magnitudeTolerance &&
            angle < params.angleTolerance &&
            variation < stillLimit
        if (!faceDown) {
            faceDownSinceNs = -1L
            return null
        }
        if (faceDownSinceNs < 0) {
            faceDownSinceNs = timestampNs
            return null
        }
        if ((timestampNs - faceDownSinceNs) / 1e9f < params.minFaceDownSeconds) return null
        if (quota < 1f) return null

        quota -= 1f
        a.copyInto(pose)
        isFlipped = true
        return Event.FLIPPED
    }

    private fun replenishQuota(nowNs: Long) {
        if (lastQuotaNs >= 0) {
            val dt = (nowNs - lastQuotaNs) / 1e9f
            if (dt > 0) quota = (quota + dt * params.quotaReplenishPerSecond).coerceAtMost(params.quotaSize)
        }
        lastQuotaNs = nowNs
    }

    private fun norm(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

    companion object {
        const val GRAVITY = 9.80665f
    }
}
