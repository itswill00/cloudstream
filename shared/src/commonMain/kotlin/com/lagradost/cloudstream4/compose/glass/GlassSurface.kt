package com.lagradost.cloudstream4.compose.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * A surface of glass, the background shows through it.
 *
 * It uses the style of the current theme, in the other themes it is just a
 * normal surface.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    tint: androidx.compose.ui.graphics.Color = LocalGlassStyle.current.tint,
    border: androidx.compose.ui.graphics.Color = LocalGlassStyle.current.border,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
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
