package app.overdrive.core.ble.link

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.TRAINER_ID
import app.overdrive.core.device.ConnectionState
import app.overdrive.core.device.collectAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hybrid connection strategy of docs/adr/0040: the first attempt is active and short, then the supervisor waits for a
 * sleeping device with a background connect that the (fake) controller completes the moment the device is on. Virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundConnectTest {
    private class NoopSession(
        private val outcome: SessionOutcome = SessionOutcome.Ready,
    ) : PeripheralSession {
        override suspend fun establish(): SessionOutcome = outcome

        override fun linkLost() = Unit
    }

    private fun BleRig.supervisor(
        strategy: () -> ConnectStrategy = { ConnectStrategy.HYBRID },
        outcome: () -> SessionOutcome = { SessionOutcome.Ready },
    ) = LinkSupervisor(
        scope = testScope.backgroundScope,
        device = TRAINER_ID,
        transports = ble,
        environment = ble,
        sessions = { _: GattTransport, _: CoroutineScope -> NoopSession(outcome()) },
        policy = BackoffPolicy.EXACT,
        timeSource = testScope.testTimeSource,
        strategy = strategy,
    )

    private fun BleRig.opens(): List<String> = ble.events.map { it.what }.filter { it.startsWith("open#") }

    private fun BleRig.backgroundOpens(): Int = opens().count { it.endsWith("(background)") }

    private fun BleRig.asleep() {
        fakeTrainer.powered = false
    }

    // region the first attempt

    @Test
    fun `a device that is awake connects on the first, active attempt`() =
        runTest {
            val rig = BleRig(this)
            val supervisor = rig.supervisor()
            supervisor.start()
            rig.advance(1_000)
            assertEquals(LinkStatus.Connected, supervisor.status.value)
            assertEquals(listOf("open#1"), rig.opens(), "one transport, not a background one")
            rig.assertNoViolations()
        }

    @Test
    fun `a sleeping device ends the active attempt after ten seconds and goes to a background connect`() =
        runTest {
            val rig = BleRig(this)
            rig.asleep()
            val supervisor = rig.supervisor()
            val statuses = collectAll(supervisor.status)
            supervisor.start()
            rig.advance(9_900)
            assertEquals(LinkStatus.Connecting(1), supervisor.status.value)
            assertEquals(listOf("open#1"), rig.opens())

            rig.advance(100) // 10 s: the active attempt is over, the old transport is closed
            assertTrue(rig.ble.transports.single().isClosed)
            assertEquals(LinkStatus.WaitingForDevice, supervisor.status.value, "not a failure, from the first moment")
            assertEquals(ConnectionState.CONNECTING, supervisor.connection.value)
            rig.advance(1_000) // the settle pause
            assertEquals(listOf("open#1", "open#2(background)"), rig.opens())
            assertEquals(LinkStatus.WaitingForDevice, supervisor.status.value)
            assertTrue(statuses.none { it is LinkStatus.WaitingToRetry }, "no failure was ever published: $statuses")
            rig.assertNoViolations()
        }

    // endregion

    // region waking up

    @Test
    fun `a device that wakes after five minutes is connected at once, by the pending connect`() =
        runTest {
            val rig = BleRig(this)
            rig.asleep()
            val supervisor = rig.supervisor()
            supervisor.start()
            rig.advance(300_000)
            assertEquals(LinkStatus.WaitingForDevice, supervisor.status.value)
            val transportsBefore = rig.ble.transports.size
            assertEquals(2, transportsBefore, "nothing was churned while it slept")

            rig.fakeTrainer.powerOn()
            rig.advance(500)
            assertEquals(LinkStatus.Connected, supervisor.status.value, "within half a second of its advertisement")
            assertEquals(ConnectionState.CONNECTED, supervisor.connection.value)
            assertEquals(transportsBefore, rig.ble.transports.size, "the pending transport itself connected")
            rig.assertNoViolations()
        }

    @Test
    fun `a link that drops goes straight to a background connect and a wake brings it back`() =
        runTest {
            val rig = BleRig(this)
            val supervisor = rig.supervisor()
            val statuses = collectAll(supervisor.status)
            supervisor.start()
            rig.advance(1_000)
            assertEquals(LinkStatus.Connected, supervisor.status.value)

            rig.fakeTrainer.powerOff(status = 8) // the Click goes to sleep: status 8, 19 or 22 on a real stack
            rig.settle()
            assertEquals(LinkStatus.WaitingForDevice, supervisor.status.value, "a sleeping device is not an error")
            rig.advance(1_000)
            assertEquals(listOf("open#1", "open#2(background)"), rig.opens(), "no active attempt in between")
            assertTrue(statuses.none { it is LinkStatus.WaitingToRetry })

            rig.advance(120_000)
            rig.fakeTrainer.powerOn()
            rig.advance(500)
            assertEquals(LinkStatus.Connected, supervisor.status.value)
            rig.assertNoViolations()
        }

    // endregion

    // region the pitfalls of autoConnect

    @Test
    fun `a pending background connect is closed and opened again after the watchdog, one at a time`() =
        runTest {
            val rig = BleRig(this)
            rig.asleep()
            val supervisor = rig.supervisor()
            supervisor.start()
            rig.advance(11_000 + 25 * 60_000)
            // The active attempt, then a background connect each 10 minutes and one second: three by minute 25.
            assertEquals(3, rig.backgroundOpens(), "recycled twice: ${rig.opens()}")
            assertEquals(LinkStatus.WaitingForDevice, supervisor.status.value)
            val open = rig.ble.transports.filter { !it.isClosed }
            assertEquals(1, open.size, "exactly one outstanding connect for the device")

            rig.fakeTrainer.powerOn()
            rig.advance(500)
            assertEquals(LinkStatus.Connected, supervisor.status.value)
            rig.assertNoViolations()
        }

    @Test
    fun `forgetting the device cancels a pending background connect for good`() =
        runTest {
            val rig = BleRig(this)
            rig.asleep()
            val supervisor = rig.supervisor()
            supervisor.start()
            rig.advance(60_000)
            assertEquals(LinkStatus.WaitingForDevice, supervisor.status.value)
            val pending = rig.ble.transports.last()
            assertFalse(pending.isClosed)

            supervisor.close()
            rig.settle()
            assertTrue(pending.isClosed, "the pending connect is cancelled, which on Android is disconnect and close")
            assertEquals(LinkStatus.Stopped, supervisor.status.value)
            val opened = rig.opens().size
            rig.fakeTrainer.powerOn()
            rig.advance(30 * 60_000)
            assertEquals(opened, rig.opens().size, "nothing opens again")
            assertTrue(rig.ble.transports.all { it.isClosed })
            rig.assertNoViolations()
        }

    @Test
    fun `retry now replaces a pending background connect by an active attempt at once`() =
        runTest {
            val rig = BleRig(this)
            rig.asleep()
            val supervisor = rig.supervisor()
            supervisor.start()
            rig.advance(60_000)
            assertEquals(2, rig.opens().size)

            supervisor.retryNow()
            rig.settle()
            assertTrue(rig.ble.transports[1].isClosed, "the pending connect was closed")
            assertEquals(listOf("open#1", "open#2(background)", "open#3"), rig.opens(), "an active attempt, no waiting")
            assertEquals(LinkStatus.Connecting(1), supervisor.status.value)

            rig.advance(10_000) // it finds nobody too: back to the background
            rig.advance(1_000)
            assertEquals(4, rig.opens().size)
            assertEquals("open#4(background)", rig.opens().last())
            rig.assertNoViolations()
        }

    @Test
    fun `retry now during the first active attempt changes nothing`() =
        runTest {
            val rig = BleRig(this)
            rig.asleep()
            val supervisor = rig.supervisor()
            supervisor.start()
            rig.advance(3_000)
            supervisor.retryNow()
            rig.advance(1_000)
            assertEquals(listOf("open#1"), rig.opens())
            rig.advance(6_000)
            assertEquals(LinkStatus.WaitingForDevice, supervisor.status.value)
            rig.assertNoViolations()
        }

    @Test
    fun `a pending background connect ends when Bluetooth goes off and the next attempt after it is active`() =
        runTest {
            val rig = BleRig(this)
            rig.asleep()
            val supervisor = rig.supervisor()
            supervisor.start()
            rig.advance(60_000)
            val pending = rig.ble.transports.last()

            rig.ble.setAvailability(BleAvailability.BLUETOOTH_OFF)
            rig.settle()
            assertTrue(pending.isClosed)
            assertEquals(LinkStatus.BluetoothUnavailable(BleAvailability.BLUETOOTH_OFF), supervisor.status.value)
            rig.advance(60_000)
            assertEquals(2, rig.opens().size, "nothing is tried while Bluetooth is off")

            rig.fakeTrainer.powerOn()
            rig.ble.setAvailability(BleAvailability.READY)
            rig.advance(1_000)
            assertEquals(LinkStatus.Connected, supervisor.status.value)
            assertEquals("open#3", rig.opens().last(), "an active attempt: the device may well be awake now")
            rig.assertNoViolations()
        }

    // endregion

    // region failures

    @Test
    fun `status 133 on the first attempt closes the transport and the next one waits in the background`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeTrainer.faults.failNextConnects(1)
            val supervisor = rig.supervisor()
            val statuses = collectAll(supervisor.status)
            supervisor.start()
            rig.advance(5_000)
            assertEquals(listOf("open#1", "open#2(background)"), rig.opens())
            assertTrue(rig.ble.transports[0].isClosed, "a failed GATT object is replaced, never reused")
            assertEquals(LinkStatus.Connected, supervisor.status.value)
            assertTrue(statuses.none { it is LinkStatus.WaitingToRetry }, "a device that is not there is not an error")
            rig.assertNoViolations()
        }

    @Test
    fun `a set-up that fails is still reported as a failure with its backoff`() =
        runTest {
            val rig = BleRig(this)
            val supervisor =
                rig.supervisor(
                    outcome = { SessionOutcome.Failed(LinkFailure(LinkFailureKind.SERVICE_MISSING)) },
                )
            supervisor.start()
            rig.advance(1_000)
            val waiting = assertIs<LinkStatus.WaitingToRetry>(supervisor.status.value)
            assertEquals(LinkFailureKind.SERVICE_MISSING, waiting.failure.kind)
            assertEquals(1_000, waiting.retryInMillis)
            rig.advance(1_000)
            assertEquals("open#2(background)", rig.opens().last())
            assertNotNull(supervisor.status.value)
        }

    // endregion

    // region the old behaviour

    @Test
    fun `with the strategy off a sleeping device is only ever tried actively, with backoff in between`() =
        runTest {
            val rig = BleRig(this)
            rig.asleep()
            val supervisor = rig.supervisor(strategy = { ConnectStrategy.LEGACY })
            supervisor.start()
            rig.advance(5 * 60_000)
            assertEquals(0, rig.backgroundOpens())
            assertTrue(rig.opens().size >= 3, "attempt, wait, attempt, wait… as in 0.4.0: ${rig.opens()}")
            assertNull(supervisor.session.value)
            rig.assertNoViolations()
        }

    @Test
    fun `the strategy is read at every attempt, so the setting works while the app runs`() =
        runTest {
            val rig = BleRig(this)
            rig.asleep()
            var background = true
            val supervisor =
                rig.supervisor(
                    strategy = { if (background) ConnectStrategy.HYBRID else ConnectStrategy.LEGACY },
                )
            supervisor.start()
            rig.advance(60_000)
            assertEquals(1, rig.backgroundOpens())

            background = false
            rig.fakeTrainer.powerOn() // the pending connect completes; then the link drops
            rig.advance(1_000)
            rig.fakeTrainer.powerOff(status = 8)
            rig.advance(5_000)
            assertEquals(1, rig.backgroundOpens(), "after the switch no background connect is opened: ${rig.opens()}")
        }

    // endregion
}
