package com.nuvio.tv.ui.navigation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryViewportPaddingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun libraryGridKeepsItsBoundsAndFocusedCardInLtr() = checkTransition(LayoutDirection.Ltr)

    @Test
    fun libraryGridKeepsItsBoundsAndFocusedCardInRtl() = checkTransition(LayoutDirection.Rtl)

    @Test
    fun collapsedSidebarLeavesGridUnchanged() = checkTransition(LayoutDirection.Ltr, collapsed = true)

    @Test
    fun widthWithoutColumnThresholdKeepsGridPosition() = checkTransition(LayoutDirection.Ltr, width = 340)

    private fun checkTransition(direction: LayoutDirection, collapsed: Boolean = false, width: Int = 360) {
        val rootPadding = if (collapsed) 0.dp else 40.dp
        val currentPadding = mutableStateOf(rootPadding)
        lateinit var gridState: LazyGridState
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                gridState = rememberLazyGridState(initialFirstVisibleItemIndex = 18)
                Box(Modifier.size(width.dp, 300.dp).padding(start = currentPadding.value)) {
                    Box(
                        Modifier.fillMaxSize()
                            .libraryViewportPadding(LibraryViewportPadding(currentPadding.value, rootPadding))
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(50.dp),
                            state = gridState,
                            modifier = Modifier.fillMaxSize().testTag("grid")
                        ) {
                            items((0 until 60).toList(), key = { it }) { item ->
                                Box(Modifier.size(50.dp).testTag("item-$item").focusable())
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("item-25").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        val gridBefore = compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot
        val itemBefore = compose.onNodeWithTag("item-25").fetchSemanticsNode().boundsInRoot
        val firstIndex = gridState.firstVisibleItemIndex
        val firstOffset = gridState.firstVisibleItemScrollOffset
        val columns = gridState.layoutInfo.visibleItemsInfo.map { it.column }.distinct().size

        val steps = if (collapsed) listOf(0.dp, 0.dp) else listOf(30.dp, 15.dp, 0.dp, 15.dp, 30.dp, 40.dp)
        for (padding in steps) {
            compose.runOnIdle { currentPadding.value = padding }
            compose.waitForIdle()
            assertEquals(gridBefore, compose.onNodeWithTag("grid").fetchSemanticsNode().boundsInRoot)
            assertEquals(itemBefore, compose.onNodeWithTag("item-25").fetchSemanticsNode().boundsInRoot)
            assertEquals(firstIndex, gridState.firstVisibleItemIndex)
            assertEquals(firstOffset, gridState.firstVisibleItemScrollOffset)
            assertEquals(columns, gridState.layoutInfo.visibleItemsInfo.map { it.column }.distinct().size)
            compose.onNodeWithTag("item-25").assertIsFocused()
        }
    }
}
