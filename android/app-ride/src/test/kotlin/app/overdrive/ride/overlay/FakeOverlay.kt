package app.overdrive.ride.overlay

import android.app.Application
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.host.RideUi
import app.overdrive.ride.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A ride, the settings and the app's visibility that a test drives by hand, in place of the real container. */
class FakeOverlayModel : OverlayModel {
    override val rides = MutableStateFlow(ride(RideState.READY))
    override val settings = MutableStateFlow(AppSettings(ftp = 250))
    override val appVisible = MutableStateFlow(true)

    val savedSettings: StateFlow<AppSettings> get() = settings

    override fun updateSettings(change: (AppSettings) -> AppSettings) {
        settings.value = change(settings.value).sanitized()
    }

    companion object {
        fun ride(
            state: RideState,
            power: Int? = 200,
            averagePower: Int? = null,
        ): RideUi =
            RideUi(
                snapshot =
                    RideSnapshot(
                        state = state,
                        ftp = 250,
                        elapsedSeconds = 125,
                        power = power,
                        heartRate = 141,
                        cadence = 88,
                        mode = TrainerMode.SIMULATION,
                        gear = 12,
                    ),
                choice = RideChoice.Free,
                profile = null,
                averagePower = averagePower,
                lapAveragePower = null,
                trace = emptyList(),
                finished = null,
                rideNumber = 1,
            )
    }
}

/** The application of the service tests: hands the service a [FakeOverlayModel]. */
class FakeOverlayApp :
    Application(),
    OverlayModelProvider {
    val model = FakeOverlayModel()

    override fun overlayModel(): OverlayModel = model
}
