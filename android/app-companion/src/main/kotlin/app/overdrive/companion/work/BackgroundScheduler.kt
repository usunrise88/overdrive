package app.overdrive.companion.work

/**
 * How the watch data's reading is put on the system's clock (design/companion.md §9, ADR 0027): **one job a day**, and the first read
 * of the last 60 days as expedited work. No foreground service, no alarm, no polling. The real one is [WorkManagerScheduler]; the
 * container's tests use [NoBackgroundWork] and drive the reading themselves.
 */
interface BackgroundScheduler {
    /** Makes sure the daily read is scheduled (about 06:00, only when the network is there). Safe to call at every start. */
    fun scheduleDaily()

    /**
     * Starts the first read of the last 60 days as expedited work, so it goes on when the rider leaves the app.
     * @return false when it could not be queued: the caller then reads in the app's own process.
     */
    fun startFirstRead(): Boolean

    /**
     * Makes sure the FIT files that wait for the service are sent as soon as there is a network, even with the app closed (ADR 0028):
     * one job with a network constraint and its own backoff. Safe to call whenever a file is kept.
     */
    fun scheduleFitFlush()

    /** Cancels the watch data's jobs: the rider took the watch data away. */
    fun cancelAll()
}

/** No system job: what a test of the container uses, and what a build without WorkManager would. */
object NoBackgroundWork : BackgroundScheduler {
    override fun scheduleDaily() = Unit

    override fun startFirstRead(): Boolean = false

    override fun scheduleFitFlush() = Unit

    override fun cancelAll() = Unit
}
