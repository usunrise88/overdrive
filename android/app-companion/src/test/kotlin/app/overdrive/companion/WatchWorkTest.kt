package app.overdrive.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.overdrive.companion.work.NoBackgroundWork
import app.overdrive.companion.work.WatchWorker
import app.overdrive.companion.work.WorkManagerScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/**
 * The daily job on the system's clock (ADR 0027): one periodic job a day, first at the next 06:00 of the phone's clock, only with a
 * network, kept (not moved) when the app opens again; the first read as one job; and the worker that runs the container's read. The
 * app is the real [CompanionApp], so the worker finds its container the way it does on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = CompanionApp::class)
class WatchWorkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        (context as CompanionApp).container.close()
    }

    private fun infos(name: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    @Test
    fun `the daily read is one job, with a network, and opening the app again does not add or move it`() {
        val scheduler = WorkManagerScheduler(context)
        scheduler.scheduleDaily()
        scheduler.scheduleDaily()
        val daily = infos(WorkManagerScheduler.DAILY)
        assertEquals(1, daily.size)
        assertEquals(NetworkType.CONNECTED, daily.single().constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, daily.single().state)
        scheduler.cancelAll()
        assertTrue(infos(WorkManagerScheduler.DAILY).all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `the first read is queued once, whatever the number of asks`() {
        val scheduler = WorkManagerScheduler(context)
        assertTrue(scheduler.startFirstRead())
        assertTrue(scheduler.startFirstRead())
        assertEquals(1, infos(WorkManagerScheduler.FIRST).size)
    }

    @Test
    fun `the first run is the next 06 00 on the phone's clock, today before it and tomorrow after it`() {
        val zone = ZoneId.of("Europe/Paris")

        fun until(isoLocal: String): Long {
            val now =
                java.time.LocalDateTime
                    .parse(isoLocal)
                    .atZone(zone)
                    .toInstant()
            return WorkManagerScheduler(context, { zone }, { Instant.from(now).toEpochMilli() }).millisUntilNext(6)
        }
        val hour = 3_600_000L
        assertEquals(1 * hour, until("2026-11-18T05:00:00"))
        assertEquals(24 * hour, until("2026-11-18T06:00:00"))
        assertEquals(22 * hour + hour / 2, until("2026-11-18T07:30:00"))
        assertEquals(7 * hour, until("2026-11-18T23:00:00"))
    }

    @Test
    fun `no scheduler is no job, and the container then reads in its own process`() {
        assertFalse(NoBackgroundWork.startFirstRead())
        NoBackgroundWork.scheduleDaily()
        NoBackgroundWork.cancelAll()
    }

    @Test
    fun `the worker runs the container's read and ends the day's attempt when nothing is allowed`() {
        val worker = TestListenableWorkerBuilder<WatchWorker>(context).build()
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
    }
}
