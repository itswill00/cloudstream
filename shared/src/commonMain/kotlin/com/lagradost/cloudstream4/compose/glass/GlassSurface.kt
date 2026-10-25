package com.lagradost.cloudstream4.compose.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass

/**
 * A surface of glass, the background shows through it.
 *
 * It uses the style of the current theme, in the other themes it is just a
 * normal surface. When a [HazeState] is available the surface really blurs what
 * is drawn behind it, otherwise it falls back to a translucent tint so the
 * content stays readable.
 *
 * [shape] is a [RoundedCornerShape] rather than a [androidx.compose.ui.graphics.Shape]
 * because the glass style can only describe rounded corners.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    tint: Color = LocalGlassStyle.current.tint,
    border: Color = LocalGlassStyle.current.border,
    hazeState: HazeState? = LocalHazeState.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalGlassStyle.current
    val blur = glass.enabled && hazeState != null
    // Haze compares styles by identity, building a new one on every recomposition
    // would replace the style on each consumer
    val blurStyle = remember(shape, tint) {
        GlassStyle.regular.then {
            shape(shape)
            tint(tint)
        }
    }
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (blur) {
                    Modifier.hazeGlass(
                        input = HazeInput.Sources(hazeState),
                        style = blurStyle,
                    )
                } else {
                    Modifier
                }
            )
            // The tint is painted over the blurred content, so the glass keeps a
            // readable surface in both the blurred and the fallback case
            .background(tint)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(border, border.copy(alpha = 0f))),
                shape = shape,
            ),
    ) {
        content()
    }
}
