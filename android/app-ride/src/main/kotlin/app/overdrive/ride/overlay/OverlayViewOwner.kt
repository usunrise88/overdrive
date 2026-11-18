package app.overdrive.ride.overlay

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The lifecycle and saved-state owner Compose looks for above a `ComposeView` (docs/android/overlay-notes.md §3). An
 * activity provides them; a window added by a service has neither, so the service supplies this one. It is driven by
 * hand, which settles the notes' open question about `LifecycleService`: [start] moves it to `RESUMED` once, [destroy]
 * to `DESTROYED`, and nothing depends on a framework class dispatching events. No `ViewModelStore`: the overlay has no
 * view model.
 *
 * Main thread only, like every lifecycle registry.
 */
class OverlayViewOwner :
    LifecycleOwner,
    SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    /** The overlay can be composed: `RESUMED`, so Compose runs its frame clock. */
    fun start() {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.RESUMED
    }

    /** Ends the lifecycle for good: compositions that are still attached dispose. */
    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }

    /** Makes [view] and everything below it findable by Compose as this owner's. */
    fun attachTo(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}
