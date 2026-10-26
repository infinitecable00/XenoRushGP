package com.denoya.xenorush

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short pulses through the platform vibrator. */}
class Haptics(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun pulse(kind: HapticKind) {
        try {
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) {
                val effect = when (kind) {
                    HapticKind.PICKUP -> VibrationEffect.createOneShot(16, 48)
                    HapticKind.BOOST -> VibrationEffect.createOneShot(32, 90)
                    HapticKind.HIT -> VibrationEffect.createOneShot(26, 160)
                    HapticKind.EXPLOSION -> VibrationEffect.createWaveform(
                        longArrayOf(0, 36, 28, 48), intArrayOf(0, 200, 0, 255), -1)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(when (kind) {
                    HapticKind.PICKUP -> 16L
                    HapticKind.BOOST -> 32L
                    HapticKind.HIT -> 26L
                    HapticKind.EXPLOSION -> 80L
                })
            }
        } catch (_: Exception) {
        }
    }
}
