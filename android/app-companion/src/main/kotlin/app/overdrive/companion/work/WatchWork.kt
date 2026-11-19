package app.overdrive.companion.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.overdrive.companion.CompanionApp
import app.overdrive.core.health.RunReason
import app.overdrive.core.outdoor.FitFlushReason
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The watch data's one background job (ADR 0027): reads what Health Connect has that is new, queues it, sends it, and refreshes the
 * figures Today shows. It does the same thing for both of its names, the daily read and the first read; only the reason differs, which
 * decides whether the missing background permission is a problem.
 *
 * It asks the container for everything, so it needs no input but its reason; a result of "try again" (an upload that failed, a read
 * that did not finish) is WorkManager's backoff, and after [MAX_ATTEMPTS] the day's attempt ends and the next day's job goes on: the
 * batches wait in the outbox either way.
 */
class WatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CompanionApp).container
        val reason = if (inputData.getBoolean(KEY_FIRST, false)) RunReason.FIRST_READ else RunReason.DAILY
        val result = container.runWatch(reason)
        // The files shared from OnelapFit that wait for the service go with the day's read (ADR 0028).
        val files = container.flushFits(FitFlushReason.BACKGROUND)
        val again = result.shouldRetry || files.failed
        return if (again && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_FIRST = "first"
        const val MAX_ATTEMPTS = 4
    }
}

/**
 * The FIT files that wait for the service (ADR 0028): sends them when the network is there. WorkManager runs it as soon as its network
 * constraint holds, also with the app closed and after a restart of the phone; a failure is retried with the system's backoff, up to
 * [MAX_ATTEMPTS] times, and the files wait on disk either way (the daily read and the app opening send them too).
 */
class FitFlushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as CompanionApp).container
        val files = container.flushFits(FitFlushReason.BACKGROUND)
        return if (files.failed && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        const val MAX_ATTEMPTS = 4
    }
}

/**
 * [BackgroundScheduler] on AndroidX WorkManager, which is the phone's own scheduler (JobScheduler) and uses no Google Play Services.
 *
 * - **Daily:** a 24 h periodic job whose first run is the next 06:00 of the phone's clock, with a 1 h flex, **network connected**
 *   (a read that cannot send waits, which is cheaper than reading twice), exponential backoff from 15 minutes. `KEEP`, so opening the
 *   app does not move the hour.
 * - **First read:** one expedited job, no constraints (reading is local; the batches wait for the network). If the system's expedited
 *   quota is used up it runs as ordinary work instead of failing.
 * - Honest about the battery: Android may still delay either when the rider's phone saves battery; Settings says so
 *   ("Blocked by the phone's battery saving") and the first run's third step helps to allow it.
 */
class WorkManagerScheduler(
    context: Context,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : BackgroundScheduler {
    private val manager by lazy { WorkManager.getInstance(context.applicationContext) }

    override fun scheduleDaily() {
        val request =
            PeriodicWorkRequestBuilder<WatchWorker>(24, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setInitialDelay(millisUntilNext(DAILY_HOUR), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
        manager.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun startFirstRead(): Boolean =
        try {
            val request =
                OneTimeWorkRequestBuilder<WatchWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(Data.Builder().putBoolean(WatchWorker.KEY_FIRST, true).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
            manager.enqueueUniqueWork(FIRST, ExistingWorkPolicy.KEEP, request)
            true
        } catch (e: IllegalStateException) {
            false
        }

    override fun scheduleFitFlush() {
        try {
            val request =
                OneTimeWorkRequestBuilder<FitFlushWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build()
            // Appended: a job that is running has already listed what waits, so a file kept meanwhile needs the next one.
            manager.enqueueUniqueWork(FIT_FLUSH, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        } catch (e: IllegalStateException) {
            // The system's scheduler is not there (not initialised): the file waits on disk, and the app opening, Sync now and the
            // daily read send it.
        }
    }

    override fun cancelAll() {
        manager.cancelUniqueWork(DAILY)
        manager.cancelUniqueWork(FIRST)
    }

    /** Milliseconds from now to the next [hour]:00 on the phone's clock. */
    internal fun millisUntilNext(hour: Int): Long {
        val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis()), zone())
        var next = now.toLocalDate().atTime(hour, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }

    companion object {
        const val DAILY = "watch-daily"
        const val FIRST = "watch-first-read"
        const val FIT_FLUSH = "fit-flush"
        const val DAILY_HOUR = 6
    }
}
