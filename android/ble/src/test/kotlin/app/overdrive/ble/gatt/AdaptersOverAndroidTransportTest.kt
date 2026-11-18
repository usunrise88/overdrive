package app.overdrive.ble.gatt

import app.overdrive.ble.AndroidGattTransportFactory
import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.adapter.FtmsTrainerDevice
import app.overdrive.core.ble.adapter.HeartRateStrapDevice
import app.overdrive.core.ble.adapter.TrainerCommandException
import app.overdrive.core.ble.fake.FakeBle
import app.overdrive.core.ble.fake.FakeFtmsTrainer
import app.overdrive.core.ble.fake.FakeHeartRateStrap
import app.overdrive.core.ble.link.BackoffPolicy
import app.overdrive.core.ble.link.LinkFailureKind
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.core.device.ConnectionState
import app.overdrive.core.device.HeartRateReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **Core's scenarios, through our transport.** The real `FtmsTrainerDevice`, `HeartRateStrapDevice` and
 * `LinkSupervisor` run
 * over [AndroidGattTransport] over [CorePlatform] over core's simulated radio (`FakeBle`), in virtual time: a trimmed
 * copy of the M3-1 scenario list (`LinkSupervisorTest`, `FtmsTrainerDeviceTest`, `BleRideTest`). If the Android
 * transport broke a rule the fake enforces (one operation at a time, a fresh object after a failure, nothing after
 * close), `violations` would say so; the fake is as strict as the platform is believed to be.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdaptersOverAndroidTransportTest {
    private val trainerId = DeviceId("scenario-trainer")
    private val strapId = DeviceId("scenario-strap")

    private class Rig(
        val test: TestScope,
        val trainerId: DeviceId,
        val strapId: DeviceId,
    ) {
        private val clock = { test.testScheduler.currentTime * 1_000_000 }
        val world = FakeBle(test.backgroundScope, clock)
        val fakeTrainer = FakeFtmsTrainer().also { world.add(trainerId, it) }
        val fakeStrap = FakeHeartRateStrap().also { world.add(strapId, it) }
        val platform = CorePlatform(world, test.backgroundScope)
        val transports = AndroidGattTransportFactory(platform, test.backgroundScope, clock, GattTimings(), BleLog.None)

        fun trainer() =
            FtmsTrainerDevice(
                scope = test.backgroundScope,
                device = trainerId,
                transports = transports,
                environment = world,
                name = "D500",
                reconnect = BackoffPolicy.EXACT,
                timeSource = test.testTimeSource,
            )

        fun strap() =
            HeartRateStrapDevice(
                scope = test.backgroundScope,
                device = strapId,
                transports = transports,
                environment = world,
                name = "HR7",
                reconnect = BackoffPolicy.EXACT,
                timeSource = test.testTimeSource,
            )

        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }

        fun assertLegal() = assertEquals("the fake saw an illegal client", emptyList<String>(), world.violations)
    }

    private fun TestScope.rig() = Rig(this, trainerId, strapId)

    @Test
    fun `a trainer connects through our transport, is set up, and obeys an ERG target and a simulation`() =
        runTest {
            val rig = rig()
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(100)
            assertEquals(ConnectionState.CONNECTING, trainer.connection.value)
            rig.advance(3_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            assertEquals(LinkStatus.Connected, trainer.status.value)
            assertTrue("control was taken as part of the set-up", rig.fakeTrainer.hasControl)
            assertTrue(rig.fakeTrainer.started)

            val readings = mutableListOf<Int?>()
            backgroundScope.launch { trainer.data.collect { readings += it.power } }
            rig.advance(100)
            trainer.setTargetPower(160)
            rig.advance(3_000)
            assertEquals(160, rig.fakeTrainer.targetPowerWatts)
            assertTrue("the notifications reached the adapter: $readings", readings.contains(160))
            rig.assertLegal()
        }

    @Test
    fun `the strap connects and its heart rate arrives with RR intervals`() =
        runTest {
            val rig = rig()
            rig.fakeStrap.bpm = 141
            val strap = rig.strap()
            val readings = mutableListOf<HeartRateReading>()
            backgroundScope.launch { strap.data.collect { readings += it } }
            strap.start()
            rig.advance(4_000)
            assertEquals(ConnectionState.CONNECTED, strap.connection.value)
            assertTrue(readings.isNotEmpty())
            assertTrue(readings.all { it.bpm == 141 })
            assertTrue(readings.any { it.rrIntervalsMs.isNotEmpty() })
            rig.assertLegal()
        }

    @Test
    fun `the trainer loses power, the link drops, the supervisor retries with fresh objects, the trainer returns`() =
        runTest {
            val rig = rig()
            rig.fakeTrainer.absentFailsAfter = 2.seconds
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(3_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)

            rig.fakeTrainer.powerOff()
            rig.advance(10)
            assertEquals(ConnectionState.DISCONNECTED, trainer.connection.value)
            assertTrue(trainer.status.value is LinkStatus.WaitingToRetry)
            assertEquals(
                LinkFailureKind.LINK_LOST,
                (trainer.status.value as LinkStatus.WaitingToRetry).failure.kind,
            )

            rig.advance(8_000)
            rig.fakeTrainer.powerOn()
            rig.advance(20_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            assertTrue("control was taken again", rig.fakeTrainer.hasControl)
            trainer.setTargetPower(120)
            rig.advance(2_000)
            assertEquals(120, rig.fakeTrainer.targetPowerWatts)

            val transports = rig.world.transports
            assertTrue("a new transport per attempt: ${transports.size}", transports.size >= 4)
            assertTrue("every dead transport was closed", transports.dropLast(1).all { it.isClosed })
            assertEquals(transports.size, rig.platform.handles.size)
            assertTrue("and its platform object released", rig.platform.handles.dropLast(1).all { it.released })
            rig.assertLegal()
        }

    @Test
    fun `status 133 on two connects in a row is retried with a fresh transport each time and then works`() =
        runTest {
            val rig = rig()
            rig.fakeTrainer.faults.failNextConnects(2, GattStatus.GATT_ERROR)
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(500)
            val waiting = trainer.status.value as LinkStatus.WaitingToRetry
            assertEquals(LinkFailureKind.CONNECT_FAILED, waiting.failure.kind)
            assertEquals(133, waiting.failure.status)
            assertEquals(1_000L, waiting.retryInMillis)

            rig.advance(20_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            val transports = rig.world.transports.filter { it.device == trainerId }
            assertEquals(3, transports.size)
            assertTrue(transports.dropLast(1).all { it.isClosed })
            assertEquals(transports.size, transports.toSet().size)
            rig.assertLegal()
        }

    @Test
    fun `Retry now cuts the backoff short`() =
        runTest {
            val rig = rig()
            rig.fakeTrainer.faults.failNextConnects(3, GattStatus.GATT_ERROR)
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(2_000) // attempt 1 failed at 0.2 s, waiting 1 s; attempt 2 at 1.2 s failed, waiting 2 s
            assertTrue(trainer.status.value is LinkStatus.WaitingToRetry)
            val before = rig.world.transports.size
            trainer.retryNow()
            rig.advance(10)
            assertEquals("the next attempt started at once", before + 1, rig.world.transports.size)
        }

    @Test
    fun `Bluetooth off drops the link without a failure and the devices return the moment it is on`() =
        runTest {
            val rig = rig()
            val trainer = rig.trainer()
            val strap = rig.strap()
            trainer.start()
            strap.start()
            rig.advance(4_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)

            rig.world.setAvailability(BleAvailability.BLUETOOTH_OFF)
            rig.advance(100)
            assertEquals(LinkStatus.BluetoothUnavailable(BleAvailability.BLUETOOTH_OFF), trainer.status.value)
            assertEquals(LinkStatus.BluetoothUnavailable(BleAvailability.BLUETOOTH_OFF), strap.status.value)
            val attempts = rig.world.transports.size
            rig.advance(60_000)
            assertEquals("nothing is tried while it is off", attempts, rig.world.transports.size)

            rig.world.setAvailability(BleAvailability.READY)
            rig.advance(4_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            assertEquals(ConnectionState.CONNECTED, strap.connection.value)
            rig.assertLegal()
        }

    @Test
    fun `a permission that is revoked is a state like Bluetooth off`() =
        runTest {
            val rig = rig()
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(3_000)
            rig.world.setAvailability(BleAvailability.PERMISSION_DENIED)
            rig.advance(100)
            assertEquals(LinkStatus.BluetoothUnavailable(BleAvailability.PERMISSION_DENIED), trainer.status.value)
            rig.world.setAvailability(BleAvailability.READY)
            rig.advance(4_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            rig.assertLegal()
        }

    @Test
    fun `a failed discovery and a failed subscription each end the attempt and the next one works`() =
        runTest {
            val rig = rig()
            rig.fakeTrainer.faults.failNextDiscoveries(1)
            rig.fakeTrainer.faults.failNextCccdWrites(1)
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(500)
            assertEquals(LinkFailureKind.SETUP_FAILED, (trainer.status.value as LinkStatus.WaitingToRetry).failure.kind)
            rig.advance(30_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            assertTrue(rig.world.transports.dropLast(1).all { it.isClosed })
            rig.assertLegal()
        }

    @Test
    fun `a Control Point that stays silent fails the command with a timeout and the link survives`() =
        runTest {
            val rig = rig()
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(3_000)
            rig.fakeTrainer.faults.noControlPointResponse(1)
            var thrown: Throwable? = null
            backgroundScope.launch {
                try {
                    trainer.setTargetPower(150)
                } catch (e: TrainerCommandException) {
                    thrown = e
                }
            }
            rig.advance(10_000)
            assertNotNull(thrown)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            // The engine would start over with Request Control; it works, and so does the target.
            trainer.requestControl()
            trainer.setTargetPower(150)
            rig.advance(1_000)
            assertEquals(150, rig.fakeTrainer.targetPowerWatts)
            rig.assertLegal()
        }

    @Test
    fun `the strap taken off the chest reads as no signal and a dropped strap is only a gap`() =
        runTest {
            val rig = rig()
            val strap = rig.strap()
            val readings = mutableListOf<HeartRateReading>()
            backgroundScope.launch { strap.data.collect { readings += it } }
            strap.start()
            rig.advance(4_000)
            rig.fakeStrap.onChest = false
            rig.advance(3_000)
            assertEquals(0, readings.last().bpm)
            rig.fakeStrap.onChest = true
            rig.fakeStrap.absentFailsAfter = 1.seconds
            rig.fakeStrap.powerOff()
            rig.advance(100)
            assertEquals(ConnectionState.DISCONNECTED, strap.connection.value)
            rig.fakeStrap.powerOn()
            rig.advance(20_000)
            assertEquals(ConnectionState.CONNECTED, strap.connection.value)
            assertTrue(readings.last().bpm > 0)
            rig.assertLegal()
        }

    @Test
    fun `closing a device closes its transport and its platform object`() =
        runTest {
            val rig = rig()
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(3_000)
            trainer.close()
            rig.advance(5_000)
            assertTrue(rig.world.transports.all { it.isClosed })
            assertTrue(rig.platform.handles.all { it.released })
            assertNull((trainer.status.value as? LinkStatus.WaitingToRetry))
            rig.assertLegal()
            if (rig.world.transports.size != 1) fail("one transport, closed once: ${rig.world.transports.size}")
        }
}
