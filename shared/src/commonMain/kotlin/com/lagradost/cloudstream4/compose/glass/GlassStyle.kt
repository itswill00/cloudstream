package com.lagradost.cloudstream4.compose.glass

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState

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
            // Apple's regular variant blurs and adjusts the luminosity of the
            // background so text stays legible. A 10% white tint was too weak
            // to do that, the text sat almost directly on the content.
            tint = Color.White.copy(alpha = 0.18f),
            border = Color.White.copy(alpha = 0.28f),
        )
    }
}

val LocalGlassStyle = staticCompositionLocalOf { GlassStyle.Disabled }

/**
 * The [HazeState] shared by every source and every effect of the app.
 *
 * Haze needs both ends to point at the same state: the content that is blurred
 * is tagged with [dev.chrisbanes.haze.hazeSource], the glass that blurs it is
 * tagged with [dev.chrisbanes.haze.hazeEffect]. Feeding both from a single
 * local means a screen does not have to wire the state through by hand, and
 * the app bar keeps the blur even when it is not given a state explicitly.
 *
 * It is null outside of [com.lagradost.cloudstream4.theme.CloudStreamTheme],
 * so the glass degrades to a plain translucent surface instead of crashing.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }
