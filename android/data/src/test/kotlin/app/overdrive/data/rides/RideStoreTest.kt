package app.overdrive.data.rides

import app.overdrive.core.record.ByteSink
import app.overdrive.core.record.RideRecordJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** The ride files of ADR 0008, on a real temporary directory. */
class RideStoreTest {
    private val directory = TestRides.tempDirectory()
    private val store = RideStore(directory)

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun names() = directory.list().orEmpty().sorted()

    private fun <T> StoreResult<T>.ok(): T =
        when (this) {
            is StoreResult.Ok -> value
            is StoreResult.Failed -> throw AssertionError("Expected success, got $cause")
        }

    private fun <T> StoreResult<T>.failed(): StoreResult.Failed =
        this as? StoreResult.Failed ?: throw AssertionError("Expected a failure")

    // ---- Lifecycle -------------------------------------------------------------------------------------------------

    @Test
    fun aRideGoesFromLogToCompleteAndTheLogIsGone() {
        val record = TestRides.writeRide(store, seconds = 90, finished = true)
        assertEquals(listOf("ride-1.meta", "ride-1.ridelog"), names())
        val during = store.list().single()
        assertEquals(StoredRideStatus.Recording, during.status)

        val saved = store.finalize(record, "Test ride", utcOffsetSeconds = 3600).ok()
        assertEquals(listOf("ride-1.fit", "ride-1.json", "ride-1.meta"), names())
        assertEquals(StoredRideStatus.Saved, saved.status)
        assertEquals("Test ride", saved.title)
        assertEquals(90, saved.durationSeconds)
        assertEquals(TestRides.START, saved.startEpochMillis)
        assertEquals(store.fitFile("ride-1").length(), saved.fitBytes)
        assertTrue(saved.hasFit)

        assertEquals(record, RideRecordJson.decode(store.recordFile("ride-1").readText()))
        FitFileCheck.verify(store.fitFile("ride-1").readBytes())
        assertEquals(listOf(saved), store.list())
    }

    @Test
    fun theFitFileCarriesTheDevicesUtcOffset() {
        val record = TestRides.record(60)
        val east = store.finalize(record, "East", utcOffsetSeconds = 7200).ok()
        val eastBytes = store.fitFile(east.id).readBytes()
        directory.listFiles()!!.forEach { it.delete() }
        store.finalize(record, "West", utcOffsetSeconds = -18_000).ok()
        val westBytes = store.fitFile("ride-1").readBytes()
        assertNotEquals(
            "the local time is in the file, so the offset changes it",
            eastBytes.toList(),
            westBytes.toList(),
        )
        assertEquals(eastBytes.size, westBytes.size)
        FitFileCheck.verify(eastBytes)
        FitFileCheck.verify(westBytes)
    }

    @Test
    fun finalizingTwiceChangesNothing() {
        val record = TestRides.writeRide(store, 61, finished = true)
        val first = store.finalize(record, "Test ride", 0).ok()
        val fit = store.fitFile("ride-1").readBytes()
        val second = store.finalize(record, "Test ride", 0).ok()
        assertEquals(first, second)
        assertTrue(fit.contentEquals(store.fitFile("ride-1").readBytes()))
        assertEquals(listOf("ride-1.fit", "ride-1.json", "ride-1.meta"), names())
    }

    @Test
    fun aRideWithoutSamplesKeepsNothing() {
        TestRides.writeRide(store, 0, finished = true)
        val result = store.finalize(TestRides.record(0), "Test ride", 0).failed()
        assertFalse(result.retryable)
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun aRideTheFitFormatCannotDateFailsForGoodAndKeepsItsLog() {
        val record = TestRides.writeRide(store, 61, finished = true, start = 1_000L)
        val result = store.finalize(record, "Test ride", 0).failed()
        assertFalse("retrying cannot make 1970 a date FIT knows", result.retryable)
        assertTrue("the log is still the only copy", store.logFile("ride-1").exists())
        assertFalse(store.fitFile("ride-1").exists())
    }

    // ---- Atomic finalize -------------------------------------------------------------------------------------------

    @Test
    fun theProcessCanStopAfterAnyStepAndTheRideIsStillThereAndCompletesLater() {
        val clean = TestRides.tempDirectory()
        val reference = RideStore(clean)
        val expected = TestRides.writeRide(reference, 75, finished = true)
        reference.finalize(expected, "Test ride", 3600).ok()
        val expectedFit = reference.fitFile("ride-1").readBytes()
        clean.deleteRecursively()

        for (stopAfter in listOf(FinalizeStep.Meta, FinalizeStep.Record, FinalizeStep.Fit)) {
            val dir = TestRides.tempDirectory()
            try {
                val crashing =
                    RideStore(dir, afterStep = {
                        if (it ==
                            stopAfter
                        ) {
                            throw IOException("the process died here")
                        }
                    })
                TestRides.writeRide(crashing, 75, finished = true)
                crashing.finalize(expected, "Test ride", 3600).failed()
                assertTrue("after $stopAfter the log is still there", crashing.logFile("ride-1").exists())

                // The next start finds the ride and completes it.
                val next = RideStore(dir)
                val found = next.scan().single()
                assertEquals(75, found.durationSeconds)
                if (stopAfter == FinalizeStep.Fit) {
                    // Both files were stored, so the ride is complete and the log is only a leftover.
                    assertEquals(StoredRideStatus.Saved, found.status)
                    assertFalse(next.logFile("ride-1").exists())
                } else {
                    assertEquals("after $stopAfter", StoredRideStatus.Unsaved, found.status)
                    assertEquals(StoredRideStatus.Saved, next.finalizeStored(found.id) { 3600 }.ok().status)
                    assertFalse(next.logFile("ride-1").exists())
                }
                assertEquals(expected, RideRecordJson.decode(next.recordFile("ride-1").readText()))
                assertTrue("after $stopAfter", expectedFit.contentEquals(next.fitFile("ride-1").readBytes()))
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun aStopAfterTheLogWasDeletedLeavesACompleteRide() {
        val crashing = RideStore(directory, afterStep = { if (it == FinalizeStep.DeleteLog) throw IOException("died") })
        val record = TestRides.writeRide(crashing, 70, finished = true)
        crashing.finalize(record, "Test ride", 0).failed()
        val next = RideStore(directory).scan().single()
        assertEquals(StoredRideStatus.Saved, next.status)
    }

    @Test
    fun aStopWhileWritingAFileLeavesTheOldOneWhole() {
        val record = TestRides.record(80)
        store.finalize(record, "Test ride", 0).ok()
        val before = store.fitFile("ride-1").readBytes()
        // A temporary file that a killed process left half written next to the real one.
        File(directory, "ride-1.fit.tmp").writeBytes(byteArrayOf(1, 2, 3))
        File(directory, "ride-1.json.tmp").writeText("{\"half\":")
        assertTrue(before.contentEquals(store.fitFile("ride-1").readBytes()))
        val list = RideStore(directory).scan()
        assertEquals(StoredRideStatus.Saved, list.single().status)
        assertEquals(listOf("ride-1.fit", "ride-1.json", "ride-1.meta"), names())
    }

    @Test
    fun aFailedRenameLeavesNoTemporaryFile() {
        val record = TestRides.record(80)
        // A directory where the FIT file should go: the rename cannot succeed.
        File(directory, "ride-1.fit").mkdirs()
        File(directory, "ride-1.fit/blocker").writeText("x")
        store.finalize(record, "Test ride", 0).failed()
        assertFalse(directory.list().orEmpty().any { it.endsWith(".tmp") })
    }

    // ---- Recovery --------------------------------------------------------------------------------------------------

    @Test
    fun aRideThatWasNeverFinishedIsInterruptedForTheNextProcess() {
        TestRides.writeRide(store, 130, finished = false, title = "Sweet spot")
        val next = RideStore(directory)
        val found = next.scan().single()
        assertEquals(StoredRideStatus.Interrupted, found.status)
        assertTrue(found.isInterrupted)
        assertEquals(130, found.durationSeconds)
        assertEquals("Sweet spot", found.title)
        assertNull(found.fitBytes)
    }

    @Test
    fun savingAnInterruptedRideEndsItWhereTheSamplesEnd() {
        val record = TestRides.writeRide(store, 130, finished = false)
        val next = RideStore(directory)
        val saved = next.finalizeStored("ride-1") { 0 }.ok()
        assertEquals(StoredRideStatus.Saved, saved.status)
        assertEquals(130, saved.durationSeconds)
        val stored = RideRecordJson.decode(next.recordFile("ride-1").readText())
        assertEquals(TestRides.START + 130_000L, stored.ride.endEpochMillis)
        assertEquals("the recovered samples are exactly the ones written", record.samples, stored.samples)
        assertEquals(record.rrIntervals, stored.rrIntervals)
        FitFileCheck.verify(next.fitFile("ride-1").readBytes())
        assertFalse(next.logFile("ride-1").exists())
    }

    @Test
    fun aTornLastLineIsDroppedAndTheRestRecovered() {
        TestRides.writeRide(store, 100, finished = false)
        val log = store.logFile("ride-1")
        val whole = log.readBytes()
        log.writeBytes(whole.copyOf(whole.size - 17)) // cut inside the last line
        val found = RideStore(directory).scan().single()
        assertEquals(StoredRideStatus.Interrupted, found.status)
        assertTrue(
            "at most the last sample is lost (the last line here is an rr line)",
            found.durationSeconds in 99..100,
        )
    }

    @Test
    fun aLogWithoutAValidHeaderIsDeletedByTheScan() {
        File(directory, "ride-2.ridelog").writeBytes(ByteArray(0))
        File(directory, "ride-3.ridelog").writeText("not a ride log at all\n")
        File(directory, "ride-4.ridelog").writeBytes(ByteArray(300)) // zero-filled after a power cut
        File(directory, "ride-4.meta").writeText("title=Lost\nstartEpochMillis=1\n")
        TestRides.writeRide(store, 90, finished = false, id = "ride-5")
        val list = RideStore(directory).scan()
        assertEquals(listOf("ride-5"), list.map { it.id })
        assertEquals(listOf("ride-5.meta", "ride-5.ridelog"), names())
    }

    @Test
    fun theScanRemovesALogWhoseRideIsAlreadyComplete() {
        val record = TestRides.writeRide(store, 70, finished = true)
        val log = store.logFile("ride-1").readBytes()
        store.finalize(record, "Test ride", 0).ok()
        // A crash before the log was deleted: put it back.
        store.logFile("ride-1").writeBytes(log)
        val list = RideStore(directory).scan()
        assertEquals(StoredRideStatus.Saved, list.single().status)
        assertFalse(store.logFile("ride-1").exists())
    }

    @Test
    fun aMissingFitFileIsWrittenAgainFromTheStoredRecord() {
        val record = TestRides.record(70)
        store.finalize(record, "Test ride", 0).ok()
        val fit = store.fitFile("ride-1").readBytes()
        store.fitFile("ride-1").delete()
        val found = RideStore(directory).scan().single()
        assertEquals(StoredRideStatus.Unsaved, found.status)
        assertFalse(found.hasFit)
        val saved = RideStore(directory).finalizeStored("ride-1") { 0 }.ok()
        assertEquals(StoredRideStatus.Saved, saved.status)
        assertTrue(fit.contentEquals(store.fitFile("ride-1").readBytes()))
    }

    @Test
    fun theMetaFileCarriesTheTitleThroughACrashAndItsAbsenceIsSurvivable() {
        TestRides.writeRide(store, 90, finished = false, title = "Sweet spot 3×10 \"hard\"")
        assertEquals("Sweet spot 3×10 \"hard\"", RideStore(directory).list().single().title)
        File(directory, "ride-1.meta").delete()
        assertEquals("Free ride", RideStore(directory).list().single().title)
    }

    // ---- Delete, ids, listing --------------------------------------------------------------------------------------

    @Test
    fun deleteRemovesEverythingOfARideButNotOneThatIsBeingWritten() {
        TestRides.writeRide(store, 90, finished = false, id = "ride-1")
        assertFalse("ride-1 is being written by this store", store.delete("ride-1"))
        assertTrue(store.logFile("ride-1").exists())

        TestRides
            .record(
                80,
                id = "ride-2",
                start = TestRides.START + 10_000_000,
            ).let { store.finalize(it, "Other", 0).ok() }
        assertTrue(store.delete("ride-2"))
        assertEquals(listOf("ride-1.meta", "ride-1.ridelog"), names())

        assertTrue("an interrupted ride is deletable from the next process", RideStore(directory).delete("ride-1"))
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun idsThatAreNotSafeFileNamesAreMadeSafeAndStayDistinct() {
        assertEquals("ride-1794996000000", RideStore.idFor("ride-1794996000000"))
        val tricky = listOf("../../etc/passwd", "a/b", "a b", "ride.1", "ünï", "")
        val ids = tricky.map { RideStore.idFor(it) }
        assertEquals(tricky.size, ids.toSet().size)
        for (id in ids) {
            assertTrue(id, Regex("[A-Za-z0-9_-]{1,80}").matches(id))
        }
        assertEquals(RideStore.idFor("a/b"), RideStore.idFor("a/b"))
        assertNotEquals(RideStore.idFor("a/b"), RideStore.idFor("a_b"))
    }

    @Test
    fun aRideWithAnUnsafeIdIsStoredUnderItsSafeName() {
        val record = TestRides.record(70, id = "ride/2026 11 18")
        val saved = store.finalize(record, "Odd id", 0).ok()
        assertEquals(RideStore.idFor("ride/2026 11 18"), saved.id)
        assertEquals(1, directory.list()!!.count { it.endsWith(".fit") })
        assertTrue(directory.list()!!.none { it.contains('/') || it.contains(' ') })
    }

    @Test
    fun theListIsNewestFirst() {
        for ((index, id) in listOf("a", "b", "c").withIndex()) {
            store
                .finalize(
                    TestRides.record(70, id = id, start = TestRides.START + index * 86_400_000L),
                    "Ride $id",
                    0,
                ).ok()
        }
        assertEquals(listOf("c", "b", "a"), store.list().map { it.id })
    }

    // ---- Failures --------------------------------------------------------------------------------------------------

    @Test
    fun aRideIdThatIsAlreadyStoredIsRefusedSoALogIsNeverAppendedToTwice() {
        val record = TestRides.writeRide(store, 70, finished = true)
        store.finalize(record, "Test ride", 0).ok()
        val again = RideStore(directory).openLog(TestRides.header(), "Again")
        assertNotNull(again.failed())
        assertEquals(listOf("ride-1.fit", "ride-1.json", "ride-1.meta"), names())
    }

    @Test
    fun aLogThatCannotBeOpenedIsAFailureNotAnException() {
        val failing = RideStore(directory, openSink = { throw IOException("Read-only file system") })
        val result = failing.openLog(TestRides.header(), "Test ride")
        assertTrue(result.failed().cause is IOException)
        assertTrue("nothing keeps the ride id busy afterwards", failing.delete("ride-1"))
        assertFalse(failing.logFile("ride-1").exists())
    }

    @Test
    fun aDirectoryThatCannotBeCreatedIsAFailure() {
        val blocker = File(directory, "blocker").also { it.writeText("a file where the directory should be") }
        val broken = RideStore(File(blocker, "rides"))
        assertTrue(broken.openLog(TestRides.header(), "Test ride") is StoreResult.Failed)
        assertTrue(broken.finalize(TestRides.record(70), "Test ride", 0) is StoreResult.Failed)
        assertEquals(emptyList<Any>(), broken.list())
        assertEquals(emptyList<Any>(), broken.scan())
    }

    @Test
    fun aSinkThatFailsMidRideLeavesACleanPrefix() {
        var appends = 0
        val flaky =
            RideStore(directory, openSink = {
                val real = app.overdrive.core.record.FileByteSink(it)
                object : ByteSink {
                    override fun append(bytes: ByteArray) {
                        if (++appends > 40) throw IOException("No space left on device")
                        real.append(bytes)
                    }

                    override fun sync() = real.sync()

                    override fun close() = real.close()
                }
            })
        val reports = mutableListOf<String>()
        val writer = RideLogWriter(flaky, "Test ride") { note, _ -> reports += note }
        writer.onRideStarted(TestRides.header())
        for (second in 1..100) writer.onSample(TestRides.sample(second))
        assertTrue(writer.failed.value)
        assertEquals("the cause is reported once", 1, reports.size)
        val found = RideStore(directory).scan().single()
        assertEquals(StoredRideStatus.Interrupted, found.status)
        assertEquals(39, found.durationSeconds)
    }
}
