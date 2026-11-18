package app.overdrive.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.overdrive.companion.model.Body
import app.overdrive.companion.model.ProblemAction
import app.overdrive.companion.model.ScreenUi
import app.overdrive.uikit.EmptyBlock
import app.overdrive.uikit.ErrorBlock
import app.overdrive.uikit.LoadingBlock
import app.overdrive.uikit.ScreenHeader
import app.overdrive.uikit.StatusLine

/**
 * Pull down at the top of a list to refresh (design/companion.md §3.3): the pull shows nothing of its own, no stretching indicator
 * and no bounce; the header says "Updating…" while the request runs. A pull of [thresholdDp] released at the top asks.
 */
@Composable
fun Modifier.pullToRefresh(
    onRefresh: () -> Unit,
    thresholdDp: Float = PULL_THRESHOLD_DP,
): Modifier {
    val ask by rememberUpdatedState(onRefresh)
    val threshold = with(LocalDensity.current) { thresholdDp.dp.toPx() }
    val connection =
        remember(threshold) {
            object : NestedScrollConnection {
                private var pulled = 0f

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // What the list did not take of a downward drag is a pull from its top.
                    if (source == NestedScrollSource.UserInput) {
                        pulled = if (available.y > 0f) pulled + available.y else 0f
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (pulled >= threshold) ask()
                    pulled = 0f
                    return Velocity.Zero
                }
            }
        }
    return this.nestedScroll(connection)
}

private const val PULL_THRESHOLD_DP = 64f

/**
 * The frame of a top-level screen (design/companion.md §3, §5): one lazy list whose first items are the header, the problem line
 * and the screen's controls, then the body for its state: loading, the error block, the empty state or the content. Screens with
 * several cards are one list, so off-screen charts are not composed (design §9).
 *
 * @param controls items under the status line and over the body (the range selector); shown in every state.
 */
@Composable
fun <T> TabScreen(
    title: String,
    ui: ScreenUi<T>,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onPhoneSettings: () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    onEmptyAction: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
    controls: LazyListScope.() -> Unit = {},
    content: LazyListScope.(T) -> Unit,
) {
    LazyColumn(
        modifier.fillMaxSize().pullToRefresh(onRefresh),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") { ScreenHeader(title, ui.subtitle, ui.refreshing, trailing = trailing) }
        ui.status?.let { problem ->
            item(key = "status") {
                StatusLine(
                    problem.title,
                    problem.cause,
                    actionLabel = problem.action?.label,
                    onAction = {
                        if (problem.action ==
                            ProblemAction.PhoneSettings
                        ) {
                            onPhoneSettings()
                        } else {
                            onSettings()
                        }
                    },
                )
            }
        }
        controls()
        when (val body = ui.body) {
            is Body.Loading -> {
                item(key = "loading") { LoadingBlock(body.text, Modifier.height(BLOCK_HEIGHT)) }
            }

            is Body.Failed -> {
                item(key = "error") {
                    ErrorBlock(
                        heading = "Could not reach your service",
                        cause = body.problem.cause,
                        hint = body.problem.hint,
                        onTryAgain = onRefresh,
                        modifier = Modifier.height(BLOCK_HEIGHT),
                        settingsLabel = body.problem.action?.label,
                        onSettings = {
                            if (body.problem.action ==
                                ProblemAction.PhoneSettings
                            ) {
                                onPhoneSettings()
                            } else {
                                onSettings()
                            }
                        },
                    )
                }
            }

            is Body.Empty -> {
                item(
                    key = "empty",
                ) { EmptyBlock(body.heading, body.text, actionLabel = body.action, onAction = onEmptyAction) }
            }

            is Body.Content -> {
                content(body.value)
            }
        }
    }
}

private val BLOCK_HEIGHT = 480.dp

/** Scrolls [state] to the top when [tab] is tapped again (design/companion.md §2). */
@Composable
fun ScrollToTopOn(
    nav: NavState,
    tab: Tab,
    state: LazyListState,
) {
    LaunchedEffect(nav, tab, state) {
        nav.scrollToTop.collect { if (it == tab) state.scrollToItem(0) }
    }
}
