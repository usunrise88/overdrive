package app.overdrive.ride

import android.app.Application
import app.overdrive.ride.overlay.OverlayModel
import app.overdrive.ride.overlay.OverlayModelProvider

/** The process-wide owner of the ride: see [AppContainer]. Nothing starts until the container is first asked for. */
class OverdriveApp :
    Application(),
    OverlayModelProvider {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun overlayModel(): OverlayModel = container.overlayModel

    override fun onCreate() {
        super.onCreate()
        // Build the container (and with it the first ride, in READY) before the first frame instead of during it.
        container
    }
}
