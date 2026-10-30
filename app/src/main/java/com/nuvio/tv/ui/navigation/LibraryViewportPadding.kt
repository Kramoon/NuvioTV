package com.nuvio.tv.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

internal data class LibraryViewportPadding(val current: Dp, val root: Dp)

internal val LocalLibraryViewportPadding = compositionLocalOf {
    LibraryViewportPadding(current = 0.dp, root = 0.dp)
}

// The parent animates its start padding based on the current route. Keep Library's
// own viewport at its root-route width even while it is entering or leaving.
internal fun Modifier.libraryViewportPadding(padding: LibraryViewportPadding): Modifier =
    layout { measurable, constraints ->
        val inset = (padding.root.roundToPx() - padding.current.roundToPx())
            .coerceIn(0, constraints.maxWidth)
        val placeable = measurable.measure(constraints.offset(horizontal = -inset))
        layout(placeable.width + inset, placeable.height) {
            placeable.placeRelative(inset, 0)
        }
    }
