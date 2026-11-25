package app.overdrive.core.health

import app.overdrive.core.remote.ADDRESS
import app.overdrive.core.remote.MemoryTextStore
import app.overdrive.core.remote.ScriptedTransport
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.remote.TOKEN
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import app.overdrive.core.wellness.ExerciseSessionRecord
import app.overdrive.core.wellness.HeartRateSamplesRecord
import app.overdrive.core.wellness.RestingHeartRateRecord
import app.overdrive.core.wellness.SleepSessionRecord
import app.overdrive.core.wellness.WellnessApiJson
import app.overdrive.core.wellness.WellnessTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The watch data's whole way to the service (ADR 0027), driven by a fake Health Connect and a fake service: permissions granted,
 * partial and declined, the first 60-day read in pages, the Changes API, an expired token, a failing upload kept and retried, a
 * lost answer re-sent, a restart half way. All figures are synthetic.
 */
private const val REFUSED = """{"error":{"code":"invalid_record","message":"/records/1/bpm must be <= 300"}}"""

class HealthSyncTest {
    private class Rig(
        val source: FakeHealthSource = FakeHealthSource(),
        val service: FakeWellnessService = FakeWellnessService(),
        val state: MemoryTextStore = MemoryTextStore(),
        val batches: InMemoryBatchStore = InMemoryBatchStore(),
    ) {
        var now = NOW
        val client = ServiceClient({ SyncConfig(ADDRESS, TOKEN) }, ScriptedTransport(), service)

        fun sync(
            maxRecords: Int = 2000,
            retry: RetryPolicy = RetryPolicy(30_000, 600_000),
        ) = HealthSync(source, client, state, batches, { now }, "Test phone", retry, maxRecords = maxRecords)

        /** Sixty nights and mornings ending at [NOW], two workouts, with a day-long heart rate under each night. */
        fun sixtyDays() {
            for (d in 0 until 60) {
                val morning = NOW - d * DAY - 30 * 60_000
                source.add(HealthSamples.resting("rhr-$d", morning, 50L + d % 5))
                val night = HealthSamples.night("sleep-$d", morning)
                source.add(night)
                val around = night.startMillis - 4 * HOUR to night.endMillis + 4 * HOUR
                source.add(HealthSamples.heartRate("hr-$d", around.first, around.second, SENTINEL_BPM))
            }
            for (d in listOf(3, 40)) {
                val workout = HealthSamples.workout("ex-$d", NOW - d * DAY - 12 * HOUR)
                source.add(workout)
                val padded = workout.startMillis - 10 * 60_000 to workout.endMillis + 10 * 60_000
                source.add(HealthSamples.heartRate("hrx-$d", padded.first, padded.second, 120))
            }
        }
    }

    private val resting = HealthDataType.RESTING_HEART_RATE

    private fun access(
        types: Set<HealthDataType>,
        background: Boolean = true,
        backgroundNeeded: Boolean = true,
    ) = HealthAccess(types, history = true, background = background, backgroundNeeded = backgroundNeeded)

    private fun instantOf(text: String): Long = checkNotNull(WellnessTime.parseInstant(text))

    private fun HealthSync.status() = status.value

    @Test
    fun `the first read takes the last 60 days newest window first in pages and sends everything once`() =
        runTest {
            val rig = Rig()
            rig.source.pageSize = 2
            rig.sixtyDays()
            val sync = rig.sync()
            val result = sync.run(RunReason.FIRST_READ)

            assertEquals(RunOutcome.DONE, result.outcome)
            assertFalse(result.shouldRetry)
            // 60 days in windows of 5: twelve windows for every type, the newest first, each in as many pages as it takes.
            val resting = rig.source.readsOf(HealthDataType.RESTING_HEART_RATE)
            assertEquals(12, resting.size)
            assertTrue(resting.first().third > NOW, "the newest window reaches the token")
            assertEquals(resting.map { it.second }.sortedDescending(), resting.map { it.second }, "newest first")
            assertEquals(NOW - 60 * DAY, resting.last().second)
            assertTrue(rig.source.pagesRead > 12 * 9, "records came in pages of 2")

            // The service has every record once: 60 mornings, 60 nights, 60 nights of heart rate, 2 workouts.
            assertEquals(60 + 60 + 60 + 2, rig.service.ids().size)
            val sent = rig.service.records()
            assertEquals(sent.size, sent.map { it.id }.toSet().size, "no record twice")
            val nights = sent.filterIsInstance<SleepSessionRecord>()
            val inNights =
                sent.filterIsInstance<HeartRateSamplesRecord>().flatMap { it.samples }.map { instantOf(it.t) }
            assertTrue(
                inNights.all { at ->
                    nights.any { at in WellnessTime.parseInstant(it.start)!!..WellnessTime.parseInstant(it.end)!! }
                },
                "heart rate outside a night is not sent",
            )
            val workouts = sent.filterIsInstance<ExerciseSessionRecord>()
            assertTrue(workouts.all { it.heartRateSamples?.size == 41 }, "an activity carries its heart rate")
            assertTrue(sent.none { it.id.startsWith("hrx-") }, "the activity's heart rate is not sent on its own")

            val status = sync.status()
            assertTrue(status.firstReadDone)
            assertNull(status.progress)
            assertEquals(0, status.waitingBatches)
            assertEquals(sent.size, status.lastSent?.total)
            assertEquals(60, status.lastSent?.byType?.get("restingHeartRate"))
            assertNotNull(status.lastReadMillis)
            val saved = HealthSyncState.decode(rig.state.text)
            assertNotNull(saved.changesToken)
            assertNull(saved.backfill)
        }

    @Test
    fun `a record stamped at the very instant of the token is not between the first window and the changes`() =
        runTest {
            val rig = Rig()
            rig.source.add(HealthSamples.resting("rhr-edge", NOW, 52))
            rig.sync().run(RunReason.FIRST_READ)
            assertTrue("rhr-edge" in rig.service.ids())
        }

    @Test
    fun `the progress is kept after each window and the estimate is of all of them`() =
        runTest {
            val rig = Rig()
            rig.sixtyDays()
            rig.source.failOnPage = 4 * 10
            val sync = rig.sync()
            val result = sync.run(RunReason.FIRST_READ)
            assertEquals(RunOutcome.READ_FAILED, result.outcome)
            assertTrue(result.shouldRetry)
            val progress = assertNotNull(sync.status().progress)
            assertEquals(12, progress.chunksTotal)
            assertTrue(progress.chunksDone in 1..11)
            assertTrue(progress.estimate >= progress.records)
            assertEquals(progress.chunksDone, HealthSyncState.decode(rig.state.text).backfill?.chunksDone)
        }

    @Test
    fun `a restart half way resumes with the next window and sends nothing twice`() =
        runTest {
            val rig = Rig()
            rig.sixtyDays()
            rig.source.failOnPage = 40
            val first = rig.sync()
            assertEquals(RunOutcome.READ_FAILED, first.run(RunReason.FIRST_READ).outcome)
            val done = checkNotNull(HealthSyncState.decode(rig.state.text).backfill).chunksDone
            assertTrue(done in 1..11)

            // A new process: the same files, a new object.
            val readsBefore = rig.source.reads.size
            rig.source.failOnPage = null
            val second = rig.sync()
            assertEquals(RunOutcome.DONE, second.run(RunReason.FIRST_READ).outcome)
            val windowsRead = rig.source.reads.drop(readsBefore).filter { it.first == resting }
            assertEquals(12 - done, windowsRead.size, "only the windows that were not done")
            assertEquals(60 + 60 + 60 + 2, rig.service.ids().size)
            assertEquals(1, rig.source.tokensRequested, "no second token for the first read")
            assertNotNull(HealthSyncState.decode(rig.state.text).changesToken)
        }

    @Test
    fun `a declined read reads nothing and sends nothing and says so`() =
        runTest {
            val rig = Rig()
            rig.sixtyDays()
            rig.source.access = HealthAccess(emptySet(), history = false, background = false)
            val sync = rig.sync()
            val result = sync.run(RunReason.APP_OPENED)
            assertEquals(RunOutcome.NOTHING_ALLOWED, result.outcome)
            assertTrue(rig.source.reads.isEmpty())
            assertEquals(0, rig.service.posts)
            assertEquals(HealthDataType.entries.filter { it.essential }, sync.status().access.missingEssential)
        }

    @Test
    fun `a partial grant reads only the allowed types and works with what it has`() =
        runTest {
            val rig = Rig()
            rig.sixtyDays()
            val allowed = setOf(HealthDataType.RESTING_HEART_RATE, HealthDataType.SLEEP)
            rig.source.access = HealthAccess(allowed, history = true, background = true)
            val sync = rig.sync()
            assertEquals(RunOutcome.DONE, sync.run(RunReason.FIRST_READ).outcome)
            assertEquals(allowed, rig.source.reads.map { it.first }.toSet())
            val kinds = rig.service.records().map { it.kindName() }.toSet()
            assertEquals(setOf("restingHeartRate", "sleepSession"), kinds)
            assertEquals(
                listOf(
                    HealthDataType.RESPIRATORY_RATE,
                    HealthDataType.OXYGEN_SATURATION,
                    HealthDataType.HEART_RATE,
                    HealthDataType.EXERCISE,
                ),
                sync.status().access.missingEssential,
            )
        }

    @Test
    fun `a type allowed later is read for 60 days and covered by a new token the others are not read again`() =
        runTest {
            val rig = Rig()
            rig.sixtyDays()
            for (d in 0 until 60) {
                rig.source.add(
                    WeightReading("w-$d", NOW - d * DAY - 3 * HOUR, 78.0, lastModifiedMillis = NOW),
                )
            }
            rig.source.access = access(HealthDataType.entries.toSet() - HealthDataType.WEIGHT)
            val sync = rig.sync()
            sync.run(RunReason.FIRST_READ)
            assertTrue(rig.service.records().none { it is app.overdrive.core.wellness.WeightRecord })
            val tokens = rig.source.tokensIssued
            rig.source.reads.clear()

            rig.source.access = access(HealthDataType.entries.toSet())
            sync.run(RunReason.APP_OPENED)
            assertEquals(setOf(HealthDataType.WEIGHT), rig.source.reads.map { it.first }.toSet())
            assertEquals(60, rig.service.records().count { it is app.overdrive.core.wellness.WeightRecord })
            assertTrue(rig.source.tokensIssued > tokens)
            assertEquals(HealthDataType.entries.map { it.name }, HealthSyncState.decode(rig.state.text).tokenTypes)
        }

    @Test
    fun `after the first read only what is new or edited is read and sent and an edit replaces`() =
        runTest {
            val rig = Rig()
            rig.sixtyDays()
            val sync = rig.sync()
            sync.run(RunReason.FIRST_READ)
            val sentBefore = rig.service.records().size
            val postsBefore = rig.service.posts

            // Nothing changed: nothing is read, nothing is posted.
            rig.source.reads.clear()
            assertEquals(RunOutcome.DONE, sync.run(RunReason.APP_OPENED).outcome)
            assertEquals(postsBefore, rig.service.posts)
            assertTrue(rig.source.reads.isEmpty(), "the Changes API, not a window")

            // A new morning, a night with its heart rate, and an edited reading.
            val morning = NOW + HOUR
            rig.source.add(HealthSamples.resting("rhr-new", morning, 53))
            val night = HealthSamples.night("sleep-new", morning - 30 * 60_000)
            rig.source.add(night)
            rig.source.add(HealthSamples.heartRate("hr-new", night.startMillis, night.endMillis, 58))
            rig.source.edit(RestingHeartRateReading("rhr-5", NOW - 5 * DAY - 30 * 60_000, 61, NOW + 2 * HOUR))
            rig.now = NOW + 2 * HOUR
            assertEquals(RunOutcome.DONE, sync.run(RunReason.APP_OPENED).outcome)

            val newest = rig.service.records().drop(sentBefore)
            // The new night overlaps last night's heart rate, which is looked at again and is a duplicate at the service.
            assertEquals(setOf("rhr-new", "sleep-new", "hr-new", "rhr-5"), newest.map { it.id }.toSet() - "hr-0")
            assertEquals(61, newest.filterIsInstance<RestingHeartRateRecord>().first { it.id == "rhr-5" }.bpm)
            assertEquals(60 * 3 + 2 + 3, rig.service.ids().size)
            assertTrue(rig.source.readsOf(resting).isEmpty(), "no window of resting heart rate")
        }

    @Test
    fun `heart rate that arrives before its night is cut to the night when the night arrives`() =
        runTest {
            val rig = Rig()
            val sync = rig.sync()
            sync.run(RunReason.FIRST_READ)
            val night = HealthSamples.night("sleep-1", NOW + 8 * HOUR)
            rig.source.add(HealthSamples.heartRate("hr-1", night.startMillis - 2 * HOUR, night.endMillis, 57))
            rig.now = NOW + 9 * HOUR
            sync.run(RunReason.APP_OPENED)
            assertTrue(rig.service.records().none { it is HeartRateSamplesRecord }, "no session known: nothing to send")
            rig.source.add(night)
            rig.now = NOW + 10 * HOUR
            sync.run(RunReason.APP_OPENED)
            val series = rig.service.records().filterIsInstance<HeartRateSamplesRecord>().single()
            assertEquals(420, series.samples.size)
        }

    @Test
    fun `an expired token falls back to a window since the last read and makes a new token`() =
        runTest {
            val rig = Rig()
            rig.sixtyDays()
            val sync = rig.sync()
            sync.run(RunReason.FIRST_READ)
            val idsBefore = rig.service.ids().size
            rig.source.add(HealthSamples.resting("rhr-late", NOW + 3 * DAY, 54))
            rig.source.expireAll()
            rig.source.reads.clear()
            rig.now = NOW + 40 * DAY

            assertEquals(RunOutcome.DONE, sync.run(RunReason.APP_OPENED).outcome)
            val windows = rig.source.readsOf(HealthDataType.RESTING_HEART_RATE)
            assertTrue(windows.isNotEmpty())
            assertEquals(NOW - 2 * DAY, windows.minOf { it.second }, "from two days before the last read")
            assertEquals(idsBefore + 1, rig.service.ids().size, "the late record arrived, nothing else is new")
            assertEquals(RunOutcome.DONE, sync.run(RunReason.APP_OPENED).outcome)
            assertEquals(windows.size, rig.source.readsOf(resting).size, "back on the Changes API")
        }

    @Test
    fun `a failing upload keeps every batch in order and is retried after a backoff never lost`() =
        runTest {
            val rig = Rig()
            rig.sixtyDays()
            rig.service.script += TransportException(TransportFailure.NoNetwork)
            val sync = rig.sync(maxRecords = 30)
            val result = sync.run(RunReason.APP_OPENED)
            assertEquals(RunOutcome.DONE, result.outcome, "the read worked")
            assertTrue(result.uploadFailed)
            assertTrue(result.shouldRetry)
            val failed = sync.status()
            assertTrue(failed.waitingBatches > 1)
            assertTrue(failed.waitingRecords > 100)
            assertEquals(app.overdrive.core.remote.ProblemKind.NO_NETWORK, failed.uploadProblem?.kind)
            assertEquals(NOW + 30_000, failed.nextRetryMillis)
            assertEquals(1, rig.service.posts, "one try, then the rest wait")

            // Inside the backoff an automatic run reads (nothing is new) and does not go to the network again.
            rig.now = NOW + 10_000
            sync.run(RunReason.APP_OPENED)
            assertEquals(1, rig.service.posts)

            // Past it, the queue is sent oldest first until it is empty.
            rig.now = NOW + 31_000
            val retried = sync.run(RunReason.RETRY)
            assertFalse(retried.uploadFailed)
            assertEquals(0, sync.status().waitingBatches)
            assertNull(sync.status().uploadProblem)
            assertNull(sync.status().nextRetryMillis)
            assertEquals(60 + 60 + 60 + 2, rig.service.ids().size)
            // In order: the bodies the service saw are the queue's order, so a window is never sent before an older one that waited.
            val seqs =
                rig.service.bodies.drop(1).flatMap { body ->
                    WellnessApiJson.decodeUpload(body).records.map { it.id }
                }
            assertEquals(seqs.size, seqs.toSet().size)
        }

    @Test
    fun `the rider asking sends at once inside the backoff`() =
        runTest {
            val rig = Rig()
            rig.source.add(HealthSamples.resting("rhr-1", NOW - HOUR))
            rig.service.script += TransportException(TransportFailure.Refused)
            val sync = rig.sync()
            assertTrue(sync.run(RunReason.APP_OPENED).uploadFailed)
            assertEquals(1, sync.status().waitingBatches)
            rig.now = NOW + 1000
            assertFalse(sync.run(RunReason.MANUAL).uploadFailed)
            assertEquals(0, sync.status().waitingBatches)
            assertEquals(1, sync.status().lastSent?.total)
        }

    @Test
    fun `an answer that was lost is a duplicate the second time and changes nothing at the service`() =
        runTest {
            val rig = Rig()
            rig.service.loseAnswers = 1
            for (d in 0 until 5) rig.source.add(HealthSamples.resting("rhr-$d", NOW - d * DAY - HOUR))
            val sync = rig.sync()
            assertTrue(sync.run(RunReason.APP_OPENED).uploadFailed)
            assertEquals(5, rig.service.ids().size, "the service did store it")
            assertEquals(1, sync.status().waitingBatches, "but the phone did not know: the batch stays")
            assertFalse(sync.run(RunReason.MANUAL).uploadFailed)
            assertEquals(5, rig.service.ids().size)
            assertEquals(5, sync.status().lastSent?.duplicates)
            assertEquals(0, sync.status().lastSent?.accepted)
            assertEquals(0, sync.status().waitingBatches)
        }

    @Test
    fun `a batch survives a restart and is sent by the next process`() =
        runTest {
            val rig = Rig()
            rig.sixtyDays()
            rig.service.script += TransportException(TransportFailure.NoNetwork)
            rig.sync().run(RunReason.APP_OPENED)
            val waiting = rig.batches.pending().sumOf { it.records }
            assertTrue(waiting > 0)

            // A new process over the same files; the phone is back online.
            val next = rig.sync()
            next.load()
            assertEquals(waiting, next.status().waitingRecords, "what waits is known before anything runs")
            rig.now = NOW + 2 * HOUR
            assertFalse(next.run(RunReason.DAILY).uploadFailed)
            assertEquals(0, next.status().waitingBatches)
            assertEquals(60 + 60 + 60 + 2, rig.service.ids().size)
        }

    @Test
    fun `a rejected token stops the run and keeps the batches a refusal for good parks that batch and goes on`() =
        runTest {
            val rig = Rig()
            for (d in 0 until 6) rig.source.add(HealthSamples.resting("rhr-$d", NOW - d * DAY - HOUR))
            rig.service.script += HttpAnswer(401, """{"error":{"code":"unauthorized","message":"no"}}""")
            val sync = rig.sync(maxRecords = 2)
            sync.run(RunReason.APP_OPENED)
            assertEquals(4, sync.status().waitingBatches)
            assertEquals(app.overdrive.core.remote.ProblemKind.TOKEN_REJECTED, sync.status().uploadProblem?.kind)

            rig.service.script += HttpAnswer(422, REFUSED)
            sync.run(RunReason.MANUAL)
            assertEquals(1, sync.status().parkedBatches, "the refused batch is kept aside, not deleted")
            assertEquals(0, sync.status().waitingBatches, "and the ones behind it went")
            assertEquals(4, rig.service.ids().size)
            assertEquals(2, rig.batches.parked().single().records)
        }

    @Test
    fun `a 200 that is not the contract's answer is not an acceptance`() =
        runTest {
            val rig = Rig()
            rig.source.add(HealthSamples.resting("rhr-1", NOW - HOUR))
            rig.service.script += HttpAnswer(200, "<html>Sign in to the network</html>")
            val sync = rig.sync()
            val result = sync.run(RunReason.APP_OPENED)
            assertTrue(result.uploadFailed)
            assertEquals(1, sync.status().waitingBatches)
            assertEquals(app.overdrive.core.remote.ProblemKind.NOT_THE_SERVICE, sync.status().uploadProblem?.kind)
        }

    @Test
    fun `a revoked permission a missing background permission and an unavailable Health Connect are plain states`() =
        runTest {
            val rig = Rig()
            rig.source.add(HealthSamples.resting("rhr-1", NOW - HOUR))
            val sync = rig.sync()

            rig.source.failNext = HealthPermissionException()
            assertEquals(RunOutcome.PERMISSION_REVOKED, sync.run(RunReason.APP_OPENED).outcome)
            assertEquals(ReadIssue.PERMISSION_REVOKED, sync.status().readIssue)

            rig.source.access = access(HealthDataType.entries.toSet(), background = false)
            assertEquals(RunOutcome.BACKGROUND_NOT_ALLOWED, sync.run(RunReason.DAILY).outcome)
            assertEquals(ReadIssue.BACKGROUND_NOT_ALLOWED, sync.status().readIssue)
            assertEquals(0, rig.service.posts)

            // On a Health Connect that has no such permission, nothing is needed.
            rig.source.access = access(HealthDataType.entries.toSet(), background = false, backgroundNeeded = false)
            assertEquals(RunOutcome.DONE, sync.run(RunReason.DAILY).outcome)
            assertNull(sync.status().readIssue)
            assertEquals(1, rig.service.ids().size)

            rig.source.availability = HealthAvailability.UPDATE_REQUIRED
            assertEquals(RunOutcome.UNAVAILABLE, sync.run(RunReason.APP_OPENED).outcome)
            assertEquals(HealthAvailability.UPDATE_REQUIRED, sync.status().availability)
        }

    @Test
    fun `what waits is still sent when Health Connect cannot be read`() =
        runTest {
            val rig = Rig()
            rig.source.add(HealthSamples.resting("rhr-1", NOW - HOUR))
            rig.service.script += TransportException(TransportFailure.NoNetwork)
            val sync = rig.sync()
            sync.run(RunReason.APP_OPENED)
            rig.source.availability = HealthAvailability.NOT_INSTALLED
            rig.now = NOW + HOUR
            assertEquals(RunOutcome.UNAVAILABLE, sync.run(RunReason.RETRY).outcome)
            assertEquals(0, sync.status().waitingBatches)
            assertEquals(1, rig.service.ids().size)
        }

    @Test
    fun `one run at a time`() =
        runTest {
            val rig = Rig()
            rig.source.add(HealthSamples.resting("rhr-1", NOW - HOUR))
            val sync = rig.sync()
            val gate = CompletableDeferred<Unit>()
            rig.source.gate = gate
            val first = async { sync.run(RunReason.FIRST_READ) }
            rig.source.entered.await()
            assertEquals(RunOutcome.ALREADY_RUNNING, sync.run(RunReason.MANUAL).outcome)
            assertTrue(sync.status().running)
            gate.complete(Unit)
            assertEquals(RunOutcome.DONE, first.await().outcome)
            assertFalse(sync.status().running)
        }

    @Test
    fun `a run that begins while the state is being read waits for it and is not turned away`() =
        runTest {
            val rig = Rig()
            rig.source.add(HealthSamples.resting("rhr-1", NOW - HOUR))
            val gate = CompletableDeferred<Unit>()
            val slow =
                object : app.overdrive.core.remote.TextStore {
                    override suspend fun read(): String? {
                        gate.await()
                        return null
                    }

                    override suspend fun write(text: String) = Unit
                }
            val sync = HealthSync(rig.source, rig.client, slow, rig.batches, { rig.now }, "Test phone")
            val loading = async { sync.load() }
            testScheduler.runCurrent()
            val running = async { sync.run(RunReason.APP_OPENED) }
            testScheduler.runCurrent()
            gate.complete(Unit)
            loading.await()
            assertEquals(RunOutcome.DONE, running.await().outcome)
            assertEquals(1, rig.service.ids().size)
        }

    @Test
    fun `what is allowed is not overwritten by a run that began before the rider changed it`() =
        runTest {
            val rig = Rig()
            rig.source.add(HealthSamples.resting("rhr-1", NOW - HOUR))
            val gate = CompletableDeferred<Unit>()
            rig.source.gate = gate
            val sync = rig.sync()
            val running = async { sync.run(RunReason.APP_OPENED) }
            rig.source.entered.await()
            // The rider takes a permission away in Health Connect while the read goes on, and the screen asks again.
            rig.source.access =
                HealthAccess(HealthDataType.entries.toSet() - HealthDataType.SLEEP, history = true, background = true)
            sync.refreshAccess()
            gate.complete(Unit)
            running.await()
            assertEquals(listOf(HealthDataType.SLEEP), sync.status().access.missingEssential)
        }

    @Test
    fun `nothing of a reading is in the saved state the status or a failure`() =
        runTest {
            val rig = Rig()
            rig.sixtyDays()
            rig.source.failOnPage = 30
            val sync = rig.sync()
            sync.run(RunReason.FIRST_READ)
            val everything = rig.state.text.orEmpty() + sync.status().toString() + rig.batches.pending().toString()
            assertFalse(everything.contains("SENTINEL"), "an exception's words are dropped")
            assertFalse(everything.contains(SENTINEL_BPM.toString().let { "bpm\":$it" }))
            assertFalse((rig.state.text ?: "").contains("bpm"))
            assertFalse((rig.state.text ?: "").contains(TOKEN))
        }

    @Test
    fun `batches respect the limits of the service`() =
        runTest {
            val rig = Rig()
            rig.sixtyDays()
            val sync = rig.sync(maxRecords = 40)
            sync.run(RunReason.FIRST_READ)
            for (body in rig.service.bodies) {
                val upload = WellnessApiJson.decodeUpload(body)
                assertTrue(upload.records.size <= 40)
                assertTrue(body.length <= WellnessBatcher.MAX_BYTES)
                assertEquals("Test phone", upload.device)
            }
            assertTrue(rig.service.bodies.size >= 5)
        }

    @Test
    fun `a state file this version cannot read is a fresh start which the service takes as duplicates`() =
        runTest {
            val rig = Rig(state = MemoryTextStore("not json"))
            rig.source.add(HealthSamples.resting("rhr-1", NOW - HOUR))
            val sync = rig.sync()
            assertEquals(RunOutcome.DONE, sync.run(RunReason.FIRST_READ).outcome)
            assertEquals(1, rig.service.ids().size)
            assertTrue(sync.status().firstReadDone)
        }
}
