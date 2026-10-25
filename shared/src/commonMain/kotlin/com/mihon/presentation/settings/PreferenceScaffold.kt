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
import com.lagradost.cloudstream4.compose.glass.LocalHazeState
import com.mihon.material.AppBar
import com.mihon.material.Scaffold
import dev.chrisbanes.haze.hazeSource

@Composable
fun PreferenceScaffold(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    onBackPressed: (() -> Unit)? = null,
    itemsProvider: @Composable () -> List<Preference>,
) {
    val (top, bottom) = remember { FocusRequester.createRefs() }
    // The content scrolls under the app bar, the app bar blurs it. The state is
    // the app wide one, so the app bar and this source always agree on it.
    val hazeState = LocalHazeState.current

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
                    .then(
                        if (hazeState != null) Modifier.hazeSource(hazeState) else Modifier
                    ),
                items = itemsProvider(),
                contentPadding = contentPadding,
            )
        },
    )
}
