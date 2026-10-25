package com.herrderb.pssst

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** The exact patterns Digital Wellbeing plays for Flip to Shhh. */
class Haptics(context: Context) {

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** Three fading taps. */
    fun on() = play(
        VibrationEffect.createWaveform(
            longArrayOf(12, 150, 10, 250, 8),
            intArrayOf(200, 0, 150, 0, 100),
            -1,
        )
    )

    /** One crisp tick. */
    fun off() = play(VibrationEffect.createOneShot(12, 255))

    // Google uses usage COMMUNICATION_REQUEST plus a bypass flag that only system apps
    // may set. Alarm usage is the closest a normal app gets: it still vibrates in
    // silent mode and is allowed by the default Do Not Disturb policy.
    private fun play(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}
