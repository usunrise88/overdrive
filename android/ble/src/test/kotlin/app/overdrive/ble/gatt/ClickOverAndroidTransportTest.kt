package app.overdrive.ble.gatt

import app.overdrive.ble.AndroidGattTransportFactory
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.fake.FakeBle
import app.overdrive.core.ble.fake.FakeClick
import app.overdrive.core.ble.fake.FakeClickLock
import app.overdrive.core.ble.link.BackoffPolicy
import app.overdrive.core.click.ButtonChange
import app.overdrive.core.click.ClickBits
import app.overdrive.core.click.ClickCodec
import app.overdrive.core.click.ClickDevice
import app.overdrive.core.click.ClickOptions
import app.overdrive.core.click.ClickState
import app.overdrive.core.click.UnlockSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * **The Click through our transport** (ADR 0012): the real `ClickDevice` and `LinkSupervisor` over [AndroidGattTransport]
 * over [CorePlatform] over core's simulated Click. What this adds to core's own Click tests is the Android side of the
 * handshake: `RideOn` must go out **without response** (the notes say so, a with-response fallback may be rejected by a
 * real Click) all the way through the platform shim, and the rest of M3's contract (one operation at a time, a fresh object
 * after a drop, nothing after close) holds for a device with an indicate characteristic and a write-without-response one.
 * The fake is written from the same notes as the adapter: a green test proves consistency with them, not with a Click.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClickOverAndroidTransportTest {
    private val clickId = DeviceId("scenario-click")

    private class Rig(
        val test: TestScope,
        val clickId: DeviceId,
        val fake: FakeClick,
    ) {
        private val clock = { test.testScheduler.currentTime * 1_000_000 }
        val world = FakeBle(test.backgroundScope, clock).also { it.add(clickId, fake) }
        val platform = CorePlatform(world, test.backgroundScope)
        val transports = AndroidGattTransportFactory(platform, test.backgroundScope, clock, GattTimings(), BleLog.None)

        fun click(options: ClickOptions = ClickOptions()) =
            ClickDevice(
                scope = test.backgroundScope,
                device = clickId,
                transports = transports,
                environment = world,
                name = "Zwift Click (right)",
                options = options,
                reconnect = BackoffPolicy.EXACT,
                timeSource = test.testTimeSource,
            )

        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }

        fun assertLegal() = assertEquals("the fake saw an illegal client", emptyList<String>(), world.violations)
    }

    private fun TestScope.rig(fake: FakeClick = FakeClick()) = Rig(this, clickId, fake)

    @Test
    fun `the handshake is written without response, then the click delivers and a press reaches the adapter`() =
        runTest {
            val rig = rig()
            val click = rig.click()
            val changes = mutableListOf<ButtonChange>()
            backgroundScope.launch { click.buttons.collect { changes += it } }
            click.start()
            rig.advance(3_000)
            assertEquals(ClickState.Active, click.state.value)

            val handshake = rig.fake.writes.first { it.bytes.contentEquals(ClickCodec.handshake()) }
            assertFalse("RideOn must go out without response", handshake.withResponse)
            assertEquals(
                "the post-handshake bytes too",
                listOf(false, false),
                rig.fake.writes.map { it.withResponse },
            )

            rig.fake.press(ClickBits.PLUS)
            rig.advance(100)
            rig.fake.release(ClickBits.PLUS)
            rig.advance(100)
            assertEquals(listOf(ClickBits.PLUS, 0), changes.map { it.held })
            assertEquals("read at set-up: the real puck sends no battery frame", 100, click.battery.value)
            rig.assertLegal()
        }

    @Test
    fun `with the write-with-response option the same bytes go out with a response`() =
        runTest {
            val rig = rig()
            val click = rig.click(ClickOptions(writeWithResponse = true))
            click.start()
            rig.advance(3_000)
            assertEquals(ClickState.Active, click.state.value)
            assertTrue(rig.fake.writes.isNotEmpty())
            assertTrue(rig.fake.writes.all { it.withResponse })
            rig.assertLegal()
        }

    @Test
    fun `a click that drops with a button down is released by the adapter and reconnects on a fresh transport`() =
        runTest {
            val rig = rig()
            val click = rig.click()
            val changes = mutableListOf<ButtonChange>()
            backgroundScope.launch { click.buttons.collect { changes += it } }
            click.start()
            rig.advance(3_000)
            rig.fake.press(ClickBits.A)
            rig.advance(100)
            rig.fake.dropLinkAt(rig.test.testScheduler.currentTime.milliseconds, 8)
            rig.advance(100)
            assertTrue("a synthetic release was made: $changes", changes.last().synthetic)
            assertEquals(0, changes.last().held)
            rig.advance(10_000)
            assertEquals(ClickState.Active, click.state.value)
            assertEquals("a new transport for the second link", 2, rig.world.transports.size)
            assertEquals(2, rig.fake.handshakeCount)
            rig.assertLegal()
        }

    @Test
    fun `a locked click is explained, not failed, and an unlock is picked up`() =
        runTest {
            val rig = rig(FakeClick().also { it.lock = FakeClickLock.SILENT })
            val click = rig.click(ClickOptions(silentMeansLocked = true, noEventTimeout = 5.seconds))
            click.start()
            rig.advance(3_000)
            rig.advance(6_000)
            assertEquals(ClickState.NeedsUnlock(UnlockSignal.NO_EVENTS), click.state.value)
            rig.fake.unlock()
            rig.fake.press(ClickBits.A) // unlocked, a real Click still says nothing until a button is pressed
            rig.advance(6_000)
            assertEquals(ClickState.Active, click.state.value)
            rig.assertLegal()
        }
}
