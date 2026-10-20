package com.lagradost.cloudstream3.ui.glass

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.Window
import com.lagradost.cloudstream3.R

/**
 * The Liquid Glass theme.
 *
 * The surfaces of the theme are translucent, so the background shows through
 * them. On Android 12 and newer the system can blur the background of a
 * window for real, that is what [blurBackground] does.
 */
object Glass {
    /** True when the Liquid Glass theme is active */
    fun isEnabled(context: Context): Boolean {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(R.attr.isGlassTheme, value, true)) return false
        return value.data != 0
    }

    /**
     * Blurs everything behind the window, that is only possible on Android 12
     * and newer. The window needs a translucent background for it to show.
     *
     * It does nothing when the Liquid Glass theme is not active.
     */
    fun blurBackground(window: Window, radiusDp: Int = 40) {
        if (!isEnabled(window.context)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val density = window.context.resources.displayMetrics.density
        window.setBackgroundBlurRadius((radiusDp * density).toInt())
    }
}
