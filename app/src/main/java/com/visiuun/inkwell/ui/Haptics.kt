package com.visiuun.inkwell.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.staticCompositionLocalOf

/** Small, permission-free vibrations through the view system. Does nothing when switched off. */
class Haptics(private val view: View?, private val enabled: Boolean) {
    private fun perform(constant: Int) {
        if (enabled) view?.performHapticFeedback(constant)
    }

    /** The lightest tap: a packet group delivered, a slider step, a tab change. */
    fun tick() = perform(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)

    /** A button that starts something. */
    fun click() = perform(HapticFeedbackConstants.CONTEXT_CLICK)

    /** A switch or toggle changing state. */
    fun toggle(on: Boolean) = perform(
        when {
            Build.VERSION.SDK_INT >= 34 -> if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            else -> HapticFeedbackConstants.CLOCK_TICK
        },
    )

    /** A stage reached: the case answered, the redraw began. */
    fun step() = perform(HapticFeedbackConstants.KEYBOARD_TAP)

    fun success() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)

    fun failure() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
}

val LocalHaptics = staticCompositionLocalOf { Haptics(null, false) }
