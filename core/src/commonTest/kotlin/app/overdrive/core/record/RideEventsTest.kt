package app.overdrive.core.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The events of a ride (spec §11.1: the stall guard fired) through the builder, the crash-safe log and the wire format. */
class RideEventsTest {
    private val header = RideHeader("e", 1_794_996_000_000L, ftp = 200)
    private val guard = RideEvent(RideEventType.STALL_GUARD, timeOffsetSeconds = 2, stepIndex = 0)

    private fun samples(count: Int) = (1..count).map { RideSample(it, power = 150, cadence = 40, stepIndex = 0) }

    @Test
    fun `an event is kept by the builder in order after its sample`() {
        val builder = RideRecordBuilder(header)
        samples(3).forEach(builder::addSample)
        builder.addEvent(guard)
        builder.addEvent(RideEvent(RideEventType.STALL_GUARD, 3, 0))
        assertEquals(listOf(2, 3), builder.build().events.map { it.timeOffsetSeconds })
    }

    @Test
    fun `the builder refuses an event before its sample or out of order`() {
        val builder = RideRecordBuilder(header)
        builder.addSample(samples(1).single())
        assertFailsWith<IllegalArgumentException> { builder.addEvent(guard) }
        builder.addSample(RideSample(2))
        builder.addSample(RideSample(3))
        builder.addEvent(RideEvent(RideEventType.STALL_GUARD, 3))
        assertFailsWith<IllegalArgumentException> { builder.addEvent(guard) }
    }

    @Test
    fun `an in-memory recorder collects them and a record without events has none`() {
        val recorder = InMemoryRideRecorder()
        recorder.onRideStarted(header)
        samples(2).forEach(recorder::onSample)
        assertTrue(checkNotNull(recorder.record).events.isEmpty())
        recorder.onEvent(guard)
        assertEquals(listOf(guard), checkNotNull(recorder.record).events)
    }

    @Test
    fun `the wire format carries them and leaves the key out when there are none`() {
        val builder = RideRecordBuilder(header)
        samples(3).forEach(builder::addSample)
        val without = RideRecordJson.encode(builder.build())
        assertFalse("events" in without, "an old record's JSON is unchanged")
        builder.addEvent(guard)
        val with = RideRecordJson.encode(builder.build())
        assertTrue("\"events\":[{\"type\":\"stall-guard\",\"timeOffsetSeconds\":2,\"stepIndex\":0}]" in with, with)
        assertEquals(listOf(guard), RideRecordJson.decode(with).events)
        assertEquals(emptyList(), RideRecordJson.decode(without).events)
    }

    @Test
    fun `the reader rejects an event after the end of the ride`() {
        val builder = RideRecordBuilder(header)
        samples(3).forEach(builder::addSample)
        builder.addEvent(RideEvent(RideEventType.STALL_GUARD, 3))
        val text = RideRecordJson.encode(builder.build())
        val late = text.replace("\"stall-guard\",\"timeOffsetSeconds\":3", "\"stall-guard\",\"timeOffsetSeconds\":9")
        assertTrue(late != text, "the test changed the event")
        assertFailsWith<IllegalArgumentException> { RideRecordJson.decode(late) }
    }

    @Test
    fun `the crash-safe log writes an event line and recovery brings it back`() {
        val store = InMemoryByteStore()
        val log = RideLogRecorder(store)
        log.onRideStarted(header)
        log.onSample(samples(2)[0])
        log.onSample(samples(2)[1])
        log.onEvent(guard)
        log.onSample(RideSample(3, power = 150))
        val text = store.readAll().decodeToString()
        assertTrue(text.lines().any { " event {" in it }, text)

        val recovered = assertNotNull(RideLog.recover(store.readAll()))
        assertEquals(listOf(guard), recovered.events)
        assertEquals(3, recovered.samples.size)
        assertEquals(listOf(guard), recovered.toRecord().events)
        assertNull(recovered.endEpochMillis)
    }

    @Test
    fun `a log cut in the middle of an event line recovers everything before it`() {
        val store = InMemoryByteStore()
        val log = RideLogRecorder(store)
        log.onRideStarted(header)
        samples(2).forEach(log::onSample)
        log.onEvent(guard)
        val bytes = store.readAll()
        val cut = bytes.copyOf(bytes.size - 5)
        val recovered = assertNotNull(RideLog.recover(cut))
        assertEquals(2, recovered.samples.size)
        assertTrue(recovered.events.isEmpty())
    }

    @Test
    fun `an event that names a second with no sample ends the log there`() {
        val store = InMemoryByteStore()
        store.append(RideLogFormat.header(header))
        store.append(RideLogFormat.sample(RideSample(1)))
        store.append(RideLogFormat.event(RideEvent(RideEventType.STALL_GUARD, 5)))
        store.append(RideLogFormat.sample(RideSample(2)))
        val recovered = assertNotNull(RideLog.recover(store.readAll()))
        assertEquals(1, recovered.samples.size)
        assertTrue(recovered.events.isEmpty())
    }

    @Test
    fun `the heart-rate target of a sample survives the log and the wire format`() {
        val sample = RideSample(1, power = 150, heartRate = 128, targetHeartRate = 130, stepIndex = 0)
        val line = RideLogFormat.sample(sample).decodeToString().trim()
        assertEquals(sample, RideLogFormat.decodeSample(line.substringAfter("sample ")))
        assertFailsWith<IllegalArgumentException> { RideSample(1, targetHeartRate = 0) }
    }
}
