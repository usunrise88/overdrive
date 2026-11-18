package app.overdrive.companion

import android.app.Application

/** The process-wide owner of the companion's data: see [CompanionContainer]. Nothing starts until the container is first asked for. */
class CompanionApp : Application() {
    val container: CompanionContainer by lazy { CompanionContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Build the container (and with it read the caches and start the first refresh) before the first frame, not during it.
        container
    }
}
