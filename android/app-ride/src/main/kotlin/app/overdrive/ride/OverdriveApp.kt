package app.overdrive.ride

import android.app.Application

/** The process-wide owner of the ride: see [AppContainer]. Nothing starts until the container is first asked for. */
class OverdriveApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Build the container (and with it the first ride, in READY) before the first frame instead of during it.
        container
    }
}
