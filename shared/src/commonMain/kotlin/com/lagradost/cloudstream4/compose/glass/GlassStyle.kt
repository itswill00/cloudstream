package com.lagradost.cloudstream4.compose.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The style of the glass surfaces.
 *
 * The glass is only active in the Liquid Glass theme, the other themes keep
 * the plain surfaces. The tint is translucent, so the background shows through
 * it, the border is the light that hits the edge of the glass.
 */
@Immutable
data class GlassStyle(
    val enabled: Boolean = false,
    val tint: Color = Color.Transparent,
    val border: Color = Color.Transparent,
) {
    companion object {
        val Disabled = GlassStyle()

        val LiquidGlass = GlassStyle(
            enabled = true,
            tint = Color.White.copy(alpha = 0.10f),
            border = Color.White.copy(alpha = 0.24f),
        )
    }
}

val LocalGlassStyle = staticCompositionLocalOf { GlassStyle.Disabled }
