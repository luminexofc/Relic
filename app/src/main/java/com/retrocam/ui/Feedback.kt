package com.retrocam.ui

import android.content.Context
import android.media.MediaActionSound
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

/** One-to-one haptic + sound map (Animations.md §3). Never fires when disabled. */
object Feedback {

    private var actionSound: MediaActionSound? = null

    fun ensureSoundLoaded() {
        if (actionSound == null) {
            runCatching {
                MediaActionSound().also {
                    it.load(MediaActionSound.SHUTTER_CLICK)
                    actionSound = it
                }
            }
        }
    }

    fun shutterClick(context: Context, soundOn: Boolean) {
        if (!soundOn) return
        ensureSoundLoaded()
        runCatching { actionSound?.play(MediaActionSound.SHUTTER_CLICK) }
    }

    fun select(context: Context) = buzz(context, VibrationEffect.EFFECT_TICK)
    fun shutter(context: Context) = buzz(context, VibrationEffect.EFFECT_CLICK)
    fun saved(context: Context) = buzz(context, VibrationEffect.EFFECT_DOUBLE_CLICK)
    fun error(context: Context) = buzz(context, VibrationEffect.EFFECT_HEAVY_CLICK)

    private fun buzz(context: Context, effectId: Int) {
        if (Build.VERSION.SDK_INT < 29) return
        val enabled = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1
        }.getOrDefault(true)
        if (!enabled) return
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (vibrator.hasVibrator()) {
            runCatching { vibrator.vibrate(VibrationEffect.createPredefined(effectId)) }
        }
    }
}
