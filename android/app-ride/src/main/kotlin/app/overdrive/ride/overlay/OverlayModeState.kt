package app.overdrive.ride.overlay

import app.overdrive.core.ride.ModeChoice
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.StepChangeWindow
import app.overdrive.ride.host.RideUi
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.OverlayStartMode
import app.overdrive.uikit.overlay.OverlayMode
import app.overdrive.uikit.overlay.OverlaySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Which mode the overlay is **shown** in (spec §11.3, docs/adr/0034): the rider's chosen mode, widened to detailed around a step change.
 * The rules are `core`'s [StepChangeWindow]; this class holds the one piece of state they need that is not in a setting, whether the rider
 * collapsed the overlay inside the window that is open now, and turns the ride and the settings into flows for the window.
 *
 * **Nothing here is continuous.** The window flow changes twice per step change; the look flow changes when it does, when the rider taps
 * and when a setting does. Everything is read from the snapshot flow that already exists, off the main thread (the caller's `flowOn`).
 */
class OverlayModeState {
    private val dismissed = MutableStateFlow(false)

    /** Whether the window around a step change is open, as the ride goes on (emits only changes). Setting off: never. */
    fun windowOpen(
        rides: Flow<RideUi>,
        settings: Flow<AppSettings>,
    ): Flow<Boolean> =
        combine(
            rides.map {
                it.snapshot
            },
            settings.map { it.overlayExpandsAtSteps }.distinctUntilChanged(),
        ) { snapshot, enabled ->
            StepChangeWindow.isOpen(snapshot, enabled)
        }.distinctUntilChanged()

    /** The look of the window: the opacity and the mode it is shown in now (emits only changes). */
    fun looks(
        windowOpen: Flow<Boolean>,
        settings: Flow<AppSettings>,
    ): Flow<OverlaySettings> =
        combine(
            windowOpen,
            settings
                .map { (it.overlayMode == OverlayStartMode.Detailed) to it.overlayOpacityPercent }
                .distinctUntilChanged(),
            dismissed,
        ) { open, (detailed, opacity), collapsed ->
            look(opacity, StepChangeWindow.showsDetailed(ModeChoice(detailed, collapsed), open))
        }.distinctUntilChanged()

    /** The look for the window's first frame, from the ride and the settings as they are now. */
    fun look(
        snapshot: RideSnapshot,
        settings: AppSettings,
    ): OverlaySettings {
        val open = StepChangeWindow.isOpen(snapshot, settings.overlayExpandsAtSteps)
        val choice = ModeChoice(settings.overlayMode == OverlayStartMode.Detailed, dismissed.value)
        return look(settings.overlayOpacityPercent, StepChangeWindow.showsDetailed(choice, open))
    }

    /** The window closed: a dismissal only lasts as long as the window it was made in. */
    fun onWindow(open: Boolean) {
        if (!open) dismissed.value = false
    }

    /**
     * A tap on the overlay (spec §4.6, §11.3): flips what is **shown**. Returns the mode to store as the rider's choice. Collapsing an overlay
     * that was expanded around a step change does not change the choice (the automatic return afterwards does nothing) and keeps the
     * window from expanding it again; choosing detailed is for good.
     */
    fun tap(
        snapshot: RideSnapshot,
        settings: AppSettings,
    ): OverlayStartMode {
        val open = StepChangeWindow.isOpen(snapshot, settings.overlayExpandsAtSteps)
        val next =
            StepChangeWindow.tap(
                ModeChoice(settings.overlayMode == OverlayStartMode.Detailed, dismissed.value),
                open,
            )
        dismissed.value = next.dismissed
        return if (next.detailed) OverlayStartMode.Detailed else OverlayStartMode.Compact
    }

    private fun look(
        opacityPercent: Int,
        detailed: Boolean,
    ) = OverlaySettings(
        opacity = opacityPercent / PERCENT,
        mode = if (detailed) OverlayMode.Detailed else OverlayMode.Compact,
    )

    private companion object {
        const val PERCENT = 100f
    }
}
