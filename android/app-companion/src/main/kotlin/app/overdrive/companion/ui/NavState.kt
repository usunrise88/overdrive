package app.overdrive.companion.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.vector.ImageVector
import app.overdrive.uikit.icons.OverdriveIcons
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** The five top-level screens (design/companion.md §2). */
enum class Tab(
    val label: String,
    val icon: ImageVector,
) {
    Today("Today", OverdriveIcons.House),
    Fitness("Fitness", OverdriveIcons.Activity),
    Power("Power", OverdriveIcons.Zap),
    Wellness("Wellness", OverdriveIcons.Moon),
    Rides("Rides", OverdriveIcons.List),
}

/** A screen pushed over a tab (back arrow, no bottom navigation). */
sealed interface Route {
    data object Settings : Route

    data object Licences : Route

    data object Notes : Route

    data class RideDetail(
        val id: String,
    ) : Route

    data class ActivityDetail(
        val id: String,
    ) : Route
}

/**
 * Where the rider is (design/companion.md §2): the selected tab and each tab's own back stack. It lives in the container, so a
 * recreated activity (rotation, process kept alive) shows the same screen. System back pops a pushed screen; on a tab other than
 * Today it goes to Today; on Today it leaves the app.
 */
@Stable
class NavState {
    var tab: Tab by mutableStateOf(Tab.Today)
        private set

    private val stacks: Map<Tab, SnapshotStateList<Route>> = Tab.entries.associateWith { mutableStateListOf() }
    private val scrollRequests =
        MutableSharedFlow<Tab>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The active tab was tapped again: its screen scrolls to the top. */
    val scrollToTop: SharedFlow<Tab> = scrollRequests.asSharedFlow()

    /** The pushed screen over the current tab, or null at its root. */
    val route: Route? get() = stacks.getValue(tab).lastOrNull()

    fun select(next: Tab) {
        tab = next
    }

    /** Tapping the active tab: back to its root, and its list to the top. */
    fun reselect(active: Tab) {
        stacks.getValue(active).clear()
        scrollRequests.tryEmit(active)
    }

    fun push(route: Route) {
        stacks.getValue(tab).add(route)
    }

    /** System back. Returns whether it was handled; false on Today's root, which leaves the app. */
    fun back(): Boolean {
        val stack = stacks.getValue(tab)
        return when {
            stack.isNotEmpty() -> {
                stack.removeAt(stack.lastIndex)
                true
            }

            tab != Tab.Today -> {
                tab = Tab.Today
                true
            }

            else -> {
                false
            }
        }
    }
}
