package com.lava.floorislava

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short vibration cues for game events. Respects the menu's vibration setting. */
object Haptics {

    fun tick(context: Context) =
        play(context, VibrationEffect.createOneShot(25L, VibrationEffect.DEFAULT_AMPLITUDE))

    fun roundStart(context: Context) =
        play(context, VibrationEffect.createWaveform(longArrayOf(0L, 60L, 80L, 120L), -1))

    fun win(context: Context) =
        play(context, VibrationEffect.createWaveform(longArrayOf(0L, 70L, 60L, 70L, 60L, 220L), -1))

    fun lose(context: Context) =
        play(context, VibrationEffect.createOneShot(650L, VibrationEffect.DEFAULT_AMPLITUDE))

    private fun play(context: Context, effect: VibrationEffect) {
        if (!GamePrefs.hapticsEnabled(context)) return
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(effect)
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            legacyVibrator(context)
        }

    @Suppress("DEPRECATION")
    private fun legacyVibrator(context: Context): Vibrator? =
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
}
