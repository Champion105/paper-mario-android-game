package com.alijah.myapplication

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

enum class BattleHapticType {
    LIGHT_TAP,
    MEDIUM_THUD,
    DOUBLE_EXPLODING_KICK
}

class BattleHapticFeedback(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun play(type: BattleHapticType) {
        mainHandler.post {
            val deviceVibrator = vibrator ?: return@post
            if (!deviceVibrator.hasVibrator()) return@post
            when (type) {
                BattleHapticType.LIGHT_TAP -> vibrateLightTap(deviceVibrator)
                BattleHapticType.MEDIUM_THUD -> vibrateMediumThud(deviceVibrator)
                BattleHapticType.DOUBLE_EXPLODING_KICK -> vibrateWaveform(deviceVibrator)
            }
        }
    }

    private fun vibrateLightTap(vibrator: Vibrator) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(18L)
        }
    }

    private fun vibrateMediumThud(vibrator: Vibrator) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(42L)
        }
    }

    private fun vibrateWaveform(vibrator: Vibrator) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0L, 34L, 44L, 70L)
            val amplitudes = intArrayOf(0, 220, 0, 255)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0L, 34L, 44L, 70L), -1)
        }
    }
}
