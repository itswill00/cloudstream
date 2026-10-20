package com.mihon.presentation.settings

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component1
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component2
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import com.mihon.material.AppBar
import com.mihon.material.Scaffold
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Composable
fun PreferenceScaffold(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    onBackPressed: (() -> Unit)? = null,
    itemsProvider: @Composable () -> List<Preference>,
) {
    val (top, bottom) = remember { FocusRequester.createRefs() }
    // The content scrolls under the app bar, the app bar blurs it
    val hazeState = rememberHazeState()

    Scaffold(
        // TODO @WindowInsets remove this when we have converted everything to compose
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppBar(
                modifier = Modifier.focusRequester(top).focusProperties {
                    down = bottom
                },
                title = title,
                navigateUp = onBackPressed,
                actions = actions,
                scrollBehavior = it,
                hazeState = hazeState,
            )
        },
        content = { contentPadding ->
            PreferenceScreen(
                modifier = Modifier
                    .focusRequester(bottom).focusProperties {
                        up = top
                    }
                    .hazeSource(hazeState),
                items = itemsProvider(),
                contentPadding = contentPadding,
            )
        },
    )
}
