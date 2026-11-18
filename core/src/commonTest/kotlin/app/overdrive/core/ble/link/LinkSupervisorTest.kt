package app.overdrive.core.ble.link

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.GattStatus
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The reconnect supervisor, over a session that does nothing, so only the link policy is under test. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkSupervisorTest {
    private class NoopSession(
        val transport: GattTransport,
        private val outcome: SessionOutcome = SessionOutcome.Ready,
    ) : PeripheralSession {
        var lost = 0

        override suspend fun establish(): SessionOutcome = outcome

        override fun linkLost() {
            lost++
        }
    }

    private fun BleRig.supervisor(
        policy: BackoffPolicy = BackoffPolicy.EXACT,
        outcome: () -> SessionOutcome = { SessionOutcome.Ready },
        sessions: MutableList<NoopSession> = mutableListOf(),
    ): Pair<LinkSupervisor<NoopSession>, List<NoopSession>> {
        val supervisor =
            LinkSupervisor(
                scope = testScope.backgroundScope,
                device = TRAINER_ID,
                transports = ble,
                environment = ble,
                sessions = { transport: GattTransport, _: CoroutineScope ->
                    NoopSession(transport, outcome()).also {
                        sessions +=
                            it
                    }
                },
                policy = policy,
                timeSource = testScope.testTimeSource,
            )
        return supervisor to sessions
    }

    @Test
    fun `it starts disconnected and does nothing until started`() =
        runTest {
            val rig = BleRig(this)
            val (supervisor, _) = rig.supervisor()
            rig.advance(5_000)
            assertEquals(ConnectionState.DISCONNECTED, supervisor.connection.value)
            assertEquals(LinkStatus.Stopped, supervisor.status.value)
            assertTrue(rig.ble.transports.isEmpty())
        }

    @Test
    fun `it connects, publishes the session, and reports connecting then connected`() =
        runTest {
            val rig = BleRig(this)
            val (supervisor, _) = rig.supervisor()
            val states = collectAll(supervisor.connection)
            supervisor.start()
            rig.advance(100)
            assertEquals(ConnectionState.CONNECTING, supervisor.connection.value)
            assertEquals(LinkStatus.Connecting(1), supervisor.status.value)
            assertNull(supervisor.session.value)
            rig.advance(200)
            assertEquals(ConnectionState.CONNECTED, supervisor.connection.value)
            assertEquals(LinkStatus.Connected, supervisor.status.value)
            assertNotNull(supervisor.session.value)
            assertEquals(
                listOf(ConnectionState.DISCONNECTED, ConnectionState.CONNECTING, ConnectionState.CONNECTED),
                states,
            )
            rig.assertNoViolations()
        }

    @Test
    fun `a dropped link is retried after one second, with a fresh transport and the old one closed`() =
        runTest {
            val rig = BleRig(this)
            val (supervisor, sessions) = rig.supervisor()
            supervisor.start()
            rig.advance(300)
            rig.fakeTrainer.dropLink(status = 8)
            rig.settle()
            assertEquals(ConnectionState.DISCONNECTED, supervisor.connection.value)
            assertNull(supervisor.session.value)
            val waiting = assertIs<LinkStatus.WaitingToRetry>(supervisor.status.value)
            assertEquals(1_000, waiting.retryInMillis)
            assertEquals(LinkFailureKind.LINK_LOST, waiting.failure.kind)
            assertEquals(8, waiting.failure.status)
            assertEquals(1, sessions.single().lost)
            assertTrue(rig.ble.transports.single().isClosed, "closed before anything else is tried")

            rig.advance(999)
            assertEquals(1, rig.ble.transports.size)
            rig.advance(1)
            assertEquals(2, rig.ble.transports.size, "the retry opens a second, fresh transport")
            rig.advance(300)
            assertEquals(ConnectionState.CONNECTED, supervisor.connection.value)
            assertEquals(2, sessions.size)
            rig.assertNoViolations()
        }

    @Test
    fun `repeated failures wait one, two, four, eight, sixteen and then thirty seconds`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeTrainer.faults.failNextConnects(8)
            val (supervisor, _) = rig.supervisor()
            supervisor.start()
            rig.advance(400_000)
            // Each attempt takes 200 ms to fail; the wait is counted from the failure.
            val opens = rig.openTimes()
            val gaps = opens.zipWithNext { a, b -> b - a - 200 }
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), gaps.take(7))
            assertEquals(ConnectionState.CONNECTED, supervisor.connection.value, "the ninth attempt works")
            assertEquals(9, opens.size)
            rig.assertNoViolations()
        }

    @Test
    fun `status 133 closes the failed transport and the next attempt gets a new one`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeTrainer.faults.failNextConnects(1, GattStatus.GATT_ERROR)
            val (supervisor, _) = rig.supervisor()
            supervisor.start()
            rig.advance(250)
            val waiting = assertIs<LinkStatus.WaitingToRetry>(supervisor.status.value)
            assertEquals(LinkFailureKind.CONNECT_FAILED, waiting.failure.kind)
            assertEquals(GattStatus.GATT_ERROR, waiting.failure.status)
            assertTrue(rig.ble.transports[0].isClosed)
            rig.advance(1_000)
            rig.advance(300)
            assertEquals(2, rig.ble.transports.size)
            assertTrue(rig.ble.transports[0] !== rig.ble.transports[1])
            assertEquals(ConnectionState.CONNECTED, supervisor.connection.value)
            val order = rig.ble.events.map { it.what }.filter { it.startsWith("open#") || it.startsWith("close#") }
            assertEquals(listOf("open#1", "close#1", "open#2"), order, "never open a second before the first is closed")
            rig.assertNoViolations()
        }

    @Test
    fun `retry now cuts the wait short and starts the backoff over`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeTrainer.faults.failNextConnects(5)
            val (supervisor, _) = rig.supervisor()
            supervisor.start()
            rig.advance(8_000) // four failures (at 0.2, 1.4, 3.6, 7.8 s): now waiting 8 s for the fifth attempt
            val waiting = assertIs<LinkStatus.WaitingToRetry>(supervisor.status.value)
            assertEquals(8_000, waiting.retryInMillis)
            val before = rig.ble.transports.size

            supervisor.retryNow()
            rig.settle()
            assertEquals(before + 1, rig.ble.transports.size, "the attempt starts at once")
            // That attempt fails too (the fifth scripted failure); the wait is the initial one again.
            rig.advance(250)
            val next = assertIs<LinkStatus.WaitingToRetry>(supervisor.status.value)
            assertEquals(1_000, next.retryInMillis)
            assertEquals(1, next.failures)
        }

    @Test
    fun `retry now while connected or connecting does nothing`() =
        runTest {
            val rig = BleRig(this)
            val (supervisor, _) = rig.supervisor()
            supervisor.start()
            rig.advance(100)
            supervisor.retryNow()
            rig.advance(300)
            supervisor.retryNow()
            rig.advance(5_000)
            assertEquals(1, rig.ble.transports.size)
            assertEquals(ConnectionState.CONNECTED, supervisor.connection.value)
            // A stale press must not shorten the next wait either.
            rig.fakeTrainer.dropLink()
            rig.settle()
            assertEquals(1_000, assertIs<LinkStatus.WaitingToRetry>(supervisor.status.value).retryInMillis)
            rig.advance(900)
            assertEquals(1, rig.ble.transports.size)
        }

    @Test
    fun `a link that held for thirty seconds restarts the backoff, one that did not keeps growing`() =
        runTest {
            val rig = BleRig(this)
            val (supervisor, _) = rig.supervisor()
            supervisor.start()
            rig.advance(300)
            rig.advance(10_000)
            rig.fakeTrainer.dropLink()
            rig.settle()
            assertEquals(1_000, assertIs<LinkStatus.WaitingToRetry>(supervisor.status.value).retryInMillis)
            rig.advance(1_300)
            rig.advance(5_000)
            rig.fakeTrainer.dropLink()
            rig.settle()
            assertEquals(
                2_000,
                assertIs<LinkStatus.WaitingToRetry>(supervisor.status.value).retryInMillis,
                "5 s is not stable",
            )
            rig.advance(2_300)
            rig.advance(31_000)
            rig.fakeTrainer.dropLink()
            rig.settle()
            assertEquals(
                1_000,
                assertIs<LinkStatus.WaitingToRetry>(supervisor.status.value).retryInMillis,
                "31 s was stable",
            )
        }

    @Test
    fun `Bluetooth off is its own state, nothing is tried, and it connects the moment it is back`() =
        runTest {
            val rig = BleRig(this)
            rig.ble.setAvailability(BleAvailability.BLUETOOTH_OFF)
            val (supervisor, _) = rig.supervisor()
            supervisor.start()
            rig.advance(60_000)
            assertEquals(LinkStatus.BluetoothUnavailable(BleAvailability.BLUETOOTH_OFF), supervisor.status.value)
            assertEquals(ConnectionState.DISCONNECTED, supervisor.connection.value)
            assertTrue(rig.ble.transports.isEmpty(), "no attempt while Bluetooth is off")

            rig.ble.setAvailability(BleAvailability.READY)
            rig.settle()
            assertEquals(1, rig.ble.transports.size, "no backoff: the attempt starts as soon as it is back")
            rig.advance(300)
            assertEquals(ConnectionState.CONNECTED, supervisor.connection.value)
        }

    @Test
    fun `a revoked permission mid-ride drops the link, says why, and resumes without backoff`() =
        runTest {
            val rig = BleRig(this)
            val (supervisor, sessions) = rig.supervisor()
            supervisor.start()
            rig.advance(300)
            rig.ble.setAvailability(BleAvailability.PERMISSION_DENIED)
            rig.settle()
            assertEquals(ConnectionState.DISCONNECTED, supervisor.connection.value)
            assertEquals(LinkStatus.BluetoothUnavailable(BleAvailability.PERMISSION_DENIED), supervisor.status.value)
            assertEquals(1, sessions.single().lost)
            assertTrue(rig.ble.transports.single().isClosed)

            rig.advance(10_000)
            assertEquals(1, rig.ble.transports.size)
            rig.ble.setAvailability(BleAvailability.READY)
            rig.settle()
            assertEquals(2, rig.ble.transports.size)
            rig.advance(300)
            assertEquals(ConnectionState.CONNECTED, supervisor.connection.value)
        }

    @Test
    fun `an unavailable failure while the environment says ready backs off instead of spinning`() =
        runTest {
            val rig = BleRig(this)
            val (supervisor, _) =
                rig.supervisor(
                    outcome = { SessionOutcome.Failed(LinkFailure(LinkFailureKind.UNAVAILABLE)) },
                )
            supervisor.start()
            rig.advance(10_000)
            assertTrue(
                rig.ble.transports.size in 2..5,
                "a handful of attempts in ten seconds, not a loop: ${rig.ble.transports.size}",
            )
        }

    @Test
    fun `a peripheral that never answers is given up on after the establish timeout`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeTrainer.powered = false
            val (supervisor, _) = rig.supervisor()
            supervisor.start()
            rig.advance(44_000)
            assertEquals(ConnectionState.CONNECTING, supervisor.connection.value)
            rig.advance(1_500) // the attempt is abandoned at 45 s and the next one waits a second
            val waiting = assertIs<LinkStatus.WaitingToRetry>(supervisor.status.value)
            assertEquals(LinkFailureKind.TIMED_OUT, waiting.failure.kind)
            assertTrue(rig.ble.transports.single().isClosed)

            rig.fakeTrainer.powerOn()
            rig.advance(500 + 300)
            assertEquals(ConnectionState.CONNECTED, supervisor.connection.value, "found as soon as it is on")
        }

    @Test
    fun `a failed set-up closes the transport and backs off like a failed connect`() =
        runTest {
            val rig = BleRig(this)
            var attempts = 0
            val (supervisor, sessions) =
                rig.supervisor(
                    outcome = {
                        if (attempts++ ==
                            0
                        ) {
                            SessionOutcome.Failed(LinkFailure(LinkFailureKind.SETUP_FAILED, 133))
                        } else {
                            SessionOutcome.Ready
                        }
                    },
                )
            supervisor.start()
            rig.advance(300)
            assertEquals(
                LinkFailureKind.SETUP_FAILED,
                assertIs<LinkStatus.WaitingToRetry>(supervisor.status.value).failure.kind,
            )
            assertEquals(1, sessions.single().lost)
            assertTrue(rig.ble.transports.single().isClosed)
            rig.advance(1_300)
            assertEquals(ConnectionState.CONNECTED, supervisor.connection.value)
        }

    @Test
    fun `close stops everything for good`() =
        runTest {
            val rig = BleRig(this)
            val (supervisor, sessions) = rig.supervisor()
            supervisor.start()
            rig.advance(300)
            supervisor.close()
            rig.settle()
            assertEquals(ConnectionState.DISCONNECTED, supervisor.connection.value)
            assertEquals(LinkStatus.Stopped, supervisor.status.value)
            assertNull(supervisor.session.value)
            assertTrue(rig.ble.transports.single().isClosed)
            assertEquals(1, sessions.single().lost)
            rig.advance(60_000)
            assertEquals(1, rig.ble.transports.size, "no further attempts")
            rig.assertNoViolations()
        }

    @Test
    fun `start twice is one loop`() =
        runTest {
            val rig = BleRig(this)
            val (supervisor, _) = rig.supervisor()
            supervisor.start()
            supervisor.start()
            rig.advance(1_000)
            assertEquals(1, rig.ble.transports.size)
        }
}
