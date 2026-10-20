package com.lagradost.cloudstream3.ui.glass

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.Window
import android.view.WindowManager
import com.lagradost.cloudstream3.R

/**
 * The Liquid Glass theme.
 *
 * The surfaces of the theme are translucent, so the background shows through
 * them. On Android 12 and newer the system can blur the background of a
 * window for real, that is what [blurBackground] does.
 */
object Glass {
    /** The radius of the blur of a dialog, 80 pixels is a good frosted glass */
    private const val BLUR_RADIUS = 80

    /** How opaque the background of a dialog is when the blur is on */
    private const val ALPHA_WITH_BLUR = 170

    /** How opaque the background of a dialog is when the device has no blur */
    private const val ALPHA_NO_BLUR = 255

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
     * Not every device supports it, when the blur is off the background of the
     * window has to be more opaque, otherwise the text is hard to read.
     *
     * It does nothing when the Liquid Glass theme is not active.
     */
    fun blurBackground(window: Window) {
        if (!isEnabled(window.context)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        window.setBackgroundBlurRadius(BLUR_RADIUS)

        val background: Drawable = window.decorView.background ?: return
        val manager = window.context.getSystemService(WindowManager::class.java) ?: return

        fun update(enabled: Boolean) {
            background.alpha = if (enabled) ALPHA_WITH_BLUR else ALPHA_NO_BLUR
        }

        update(manager.isCrossWindowBlurEnabled)
        manager.addCrossWindowBlurEnabledListener(window.context.mainExecutor) { enabled ->
            update(enabled)
        }
    }
}
