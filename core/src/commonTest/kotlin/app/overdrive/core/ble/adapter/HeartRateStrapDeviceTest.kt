package app.overdrive.core.ble.adapter

import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.HeartRateUuids
import app.overdrive.core.ble.STRAP_ID
import app.overdrive.core.ble.codec.DecodeErrorKind
import app.overdrive.core.ble.codec.HeartRateContactRule
import app.overdrive.core.ble.codec.SensorContact
import app.overdrive.core.ble.fake.FakeHeartRateStrapOptions
import app.overdrive.core.ble.link.LinkFailureKind
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.core.device.ConnectionState
import app.overdrive.core.device.HeartRateReading
import app.overdrive.core.device.collectAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The heart-rate strap adapter over the fake strap (docs/protocols/ftms-hr.md §6). */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartRateStrapDeviceTest {
    private fun BleRig.runStrap(options: HeartRateOptions = HeartRateOptions()) =
        strap(options).also {
            it.start()
            advance(1_000)
        }

    @Test
    fun `set-up discovers and enables only the Heart Rate Measurement, then it is connected`() =
        runTest {
            val rig = BleRig(this)
            val recorded = rig.recording(model = "HR7")
            val strap = rig.strap(factory = recorded.factory)
            strap.start()
            rig.advance(500)
            rig.settle()
            assertEquals(ConnectionState.CONNECTED, strap.connection.value)
            assertEquals(
                listOf("S - connecting", "S - connected", "D 2A37 180D/N", "D 2A38 180D/R", "C 2A37 0100"),
                recorded.compact(),
            )
            rig.assertNoViolations()
        }

    @Test
    fun `a measurement becomes a reading with the RR intervals converted by the domain rule`() =
        runTest {
            val rig = BleRig(this)
            val strap = rig.runStrap()
            val readings = collectAll(strap.data)
            rig.fakeStrap.bpm = 150
            rig.fakeStrap.nextRrRaw = listOf(900, 1024)
            rig.advance(1_000)
            assertEquals(HeartRateReading(bpm = 150, rrIntervalsMs = listOf(879, 1000)), readings.single())
        }

    @Test
    fun `generated RR intervals add up to about the beats of the second`() =
        runTest {
            val rig = BleRig(this)
            val strap = rig.runStrap()
            val readings = collectAll(strap.data)
            rig.fakeStrap.bpm = 120
            rig.advance(5_000)
            assertEquals(5, readings.size)
            val rr = readings.flatMap { it.rrIntervalsMs }
            assertTrue(rr.all { it in 480..520 }, "120 bpm is a beat every 500 ms: $rr")
            assertEquals(10, rr.size, "two beats a second")
        }

    @Test
    fun `a strap without RR gives an empty list`() =
        runTest {
            val rig = BleRig(this, strapOptions = FakeHeartRateStrapOptions(sendsRr = false))
            val strap = rig.runStrap()
            val readings = collectAll(strap.data)
            rig.advance(1_000)
            assertEquals(HeartRateReading(bpm = 140, rrIntervalsMs = emptyList()), readings.single())
        }

    @Test
    fun `off the chest the strap reads as no signal, 0 bpm, and the contact state says why`() =
        runTest {
            val rig = BleRig(this)
            val strap = rig.runStrap()
            val readings = collectAll(strap.data)
            rig.advance(1_000)
            assertEquals(SensorContact.DETECTED, strap.contact.value)
            assertEquals(140, readings.last().bpm)

            rig.fakeStrap.onChest = false
            rig.advance(1_000)
            assertEquals(HeartRateReading(bpm = 0), readings.last(), "no number, no RR")
            assertEquals(SensorContact.NOT_DETECTED, strap.contact.value)

            rig.fakeStrap.onChest = true
            rig.advance(1_000)
            assertEquals(140, readings.last().bpm)
        }

    @Test
    fun `a strap without a contact sensor is trusted as it is`() =
        runTest {
            val rig = BleRig(this, strapOptions = FakeHeartRateStrapOptions(reportsContact = false))
            val strap = rig.runStrap()
            val readings = collectAll(strap.data)
            rig.advance(1_000)
            assertEquals(140, readings.single().bpm)
            assertEquals(SensorContact.NOT_SUPPORTED, strap.contact.value)
        }

    @Test
    fun `the contact rule and the no-signal behaviour are options, the bench flips for FT 11`() =
        runTest {
            // pycycling's reading: contact status 2 counts as detected, so the number is kept.
            val rig = BleRig(this)
            val strap = rig.runStrap(HeartRateOptions(contactRule = HeartRateContactRule.ANY_NONZERO))
            val readings = collectAll(strap.data)
            rig.fakeStrap.onChest = false
            rig.advance(1_000)
            assertEquals(140, readings.last().bpm)
            assertEquals(SensorContact.DETECTED, strap.contact.value)

            // The default rule but without the no-signal rule: the number is kept, the contact state still says no.
            val rig2 = BleRig(this)
            val strap2 = rig2.runStrap(HeartRateOptions(noSignalWithoutContact = false))
            val readings2 = collectAll(strap2.data)
            rig2.fakeStrap.onChest = false
            rig2.advance(1_000)
            assertEquals(140, readings2.last().bpm)
            assertEquals(SensorContact.NOT_DETECTED, strap2.contact.value)
        }

    @Test
    fun `an implausible rate is a gap, not a number`() =
        runTest {
            val rig = BleRig(this)
            val strap = rig.runStrap()
            val readings = collectAll(strap.data)
            rig.fakeStrap.bpm = 65_535
            rig.advance(1_000)
            assertEquals(0, readings.single().bpm)
        }

    @Test
    fun `a malformed or odd notification is reported and the strap goes on`() =
        runTest {
            val rig = BleRig(this)
            val strap = rig.runStrap()
            val events = collectAll(strap.events)
            val readings = collectAll(strap.data)
            val link = assertNotNull(rig.fakeStrap.connection)
            link.push(HeartRateUuids.MEASUREMENT, byteArrayOf(0x01, 0x48)) // 16-bit rate, one byte
            link.push(HeartRateUuids.MEASUREMENT, byteArrayOf(0x10, 0x50, 0x00, 0x04, 0x01)) // HR11: a stray byte
            rig.settle()
            val malformed = events.filterIsInstance<StrapEvent.MalformedNotification>().single()
            assertEquals(DecodeErrorKind.TRUNCATED, malformed.error.kind)
            assertTrue(StrapEvent.StrayRrByte in events)
            assertEquals(listOf(HeartRateReading(80, listOf(1000))), readings)
        }

    @Test
    fun `a strap that drops is a link event only, and it reconnects by itself`() =
        runTest {
            val rig = BleRig(this)
            val strap = rig.runStrap()
            val readings = collectAll(strap.data)
            rig.advance(2_000)
            assertEquals(2, readings.size)

            rig.fakeStrap.dropLink(status = GattStatus.GATT_ERROR)
            rig.settle()
            assertEquals(ConnectionState.DISCONNECTED, strap.connection.value)
            assertNull(strap.contact.value, "no contact to report while it is away")
            val waiting = assertIs<LinkStatus.WaitingToRetry>(strap.status.value)
            assertEquals(GattStatus.GATT_ERROR, waiting.failure.status)
            rig.advance(1_500)
            assertEquals(ConnectionState.CONNECTED, strap.connection.value)
            rig.advance(2_000)
            assertTrue(readings.size >= 3, "readings resume: ${readings.size}")
            rig.assertNoViolations()
        }

    @Test
    fun `a device that is not a heart-rate strap never connects`() =
        runTest {
            val rig = BleRig(this)
            rig.ble.add(STRAP_ID, rig.fakeTrainer)
            val strap = rig.runStrap()
            assertEquals(
                LinkFailureKind.SERVICE_MISSING,
                assertIs<LinkStatus.WaitingToRetry>(strap.status.value).failure.kind,
            )
        }

    @Test
    fun `a failed subscription is a failed attempt and the retry works`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeStrap.faults.failNextCccdWrites(1)
            val strap = rig.runStrap()
            assertEquals(
                LinkFailureKind.SETUP_FAILED,
                assertIs<LinkStatus.WaitingToRetry>(strap.status.value).failure.kind,
            )
            rig.advance(2_000)
            assertEquals(ConnectionState.CONNECTED, strap.connection.value)
        }

    @Test
    fun `a silent strap is still connected, the engine's own timeout makes the gap`() =
        runTest {
            val rig = BleRig(this)
            val strap = rig.runStrap()
            val readings = collectAll(strap.data)
            rig.fakeStrap.faults.silence(from = 3.seconds, until = 9.seconds)
            rig.advance(11_000)
            assertEquals(ConnectionState.CONNECTED, strap.connection.value)
            assertTrue(readings.size in 4..6, "only the seconds outside the silence: ${readings.size}")
        }
}
