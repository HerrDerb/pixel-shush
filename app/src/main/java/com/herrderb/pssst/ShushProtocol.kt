package com.herrderb.pssst

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Messages of the Pixel "shush" nanoapp on the sensor hub, as Google Play Services sends them
 * (contextmanager ShushGestureSensor, GMS 26.42). Pure Kotlin so it can be unit tested.
 */
object ShushProtocol {

    const val NANOAPP_ID = 0x476f6f676c001011L

    /** shush_nanoapp_minimum_version. */
    const val MIN_VERSION = 0x10001L

    // Host -> nanoapp
    const val MSG_START = 1
    const val MSG_STOP = 2
    const val MSG_SCREEN = 8

    // Nanoapp -> host (3 and 4 exist too; GMS ignores them)
    const val MSG_FACE_DOWN = 5
    const val MSG_FACE_UP = 6

    /** screen_off_proximity_use_delay_millis: GMS tells the nanoapp about screen off this much later. */
    const val SCREEN_OFF_DELAY_MS = 4_250L

    const val START_SIZE = 77

    /**
     * Start message with the detection config. Values are the GMS defaults; the ones Pssst
     * also uses for its own detection come from [FlipDetector.Params].
     */
    fun start(screenOn: Boolean, p: FlipDetector.Params = FlipDetector.Params()): ByteArray {
        // Bit 0 enable_facedown_proximity (off), bit 1 use proximity (screen off, since
        // enable_proximity_when_screen_off is on), bit 2 enable_detect_proximity_0cm_issue (on),
        // bit 3 enable_accel_only_exit_condition (off).
        val flags = (if (screenOn) 0 else 2) or 4
        return ByteBuffer.allocate(START_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.1f) // shush_sensor_batching_delay_second
            .putFloat(p.averagingRate)
            .putFloat(p.variationWithProximity)
            .putFloat(p.angleTolerance)
            .putFloat(p.magnitudeTolerance)
            .putFloat(p.minFaceDownSeconds)
            .putFloat(p.quotaSize)
            .putFloat(p.quotaReplenishPerSecond)
            .putFloat(2.0f) // shush_proximity_transition_window_second
            .put(flags.toByte())
            .putFloat(p.exitAccelDiff) // lenient_accel_diff
            .putFloat(3.0f) // lenient_gravity_angle_tolerance
            .putFloat(5.0f) // pre_gesture_x_angle_threshold
            .putFloat(5.0f) // pre_gesture_y_angle_threshold
            .putFloat(8.0f) // pre_gesture_backwards_y_angle_threshold
            .putFloat(2.25f) // major_motion_filter_window_second
            .putFloat(p.variationWithoutProximity)
            .putFloat(0.8f) // quick_near_far_transition_window_0cm_second
            .putFloat(0.6f) // gravity_angle_tolerance_0cm
            .putFloat(5.0f) // gravity_angle_tolerance_super_fast_0cm
            .array()
    }

    /** Screen state message: 1 lets the nanoapp use the proximity sensor (screen off). */
    fun screen(screenOn: Boolean): ByteArray = byteArrayOf(if (screenOn) 0 else 1)
}
