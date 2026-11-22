package app.overdrive.core.click

import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.link.ConnectStrategy
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.core.device.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The three devices with the hybrid connection strategy (docs/adr/0040): a woken device connects by itself, and says what it waits for. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundConnectDevicesTest {
    private val hybrid = { ConnectStrategy.HYBRID }

    @Test
    fun `a Click that sleeps is waited for, not failed, and a press that wakes it connects it at once`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.addClick()
            fake.powerOff()
            val click = rig.click(connectStrategy = hybrid)
            click.start()
            rig.advance(11_000)
            assertEquals(
                ClickState.WaitingForDevice,
                click.state.value,
                "the row says: waiting for the Click to wake up",
            )

            rig.advance(10 * 60_000 + 30_000) // idle for ten minutes, and the stale connect was recycled meanwhile
            assertEquals(ClickState.WaitingForDevice, click.state.value)
            fake.powerOn() // the press
            rig.advance(1_500)
            assertTrue(
                click.state.value is ClickState.Waiting || click.state.value is ClickState.Active,
                "${click.state.value}",
            )
            rig.assertNoViolations()
        }

    @Test
    fun `the heart-rate strap and the trainer connect in the background the same way`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeStrap.powerOff()
            rig.fakeTrainer.powerOff()
            val strap = rig.strap(connectStrategy = hybrid)
            val trainer = rig.trainer(connectStrategy = hybrid)
            strap.start()
            trainer.start()
            rig.advance(30_000)
            assertEquals(LinkStatus.WaitingForDevice, strap.status.value)
            assertEquals(LinkStatus.WaitingForDevice, trainer.status.value)
            assertEquals(ConnectionState.CONNECTING, strap.connection.value)

            rig.advance(5 * 60_000)
            rig.fakeStrap.powerOn()
            rig.advance(1_500)
            assertEquals(ConnectionState.CONNECTED, strap.connection.value)
            assertEquals(LinkStatus.WaitingForDevice, trainer.status.value, "the other device still waits")
            rig.fakeTrainer.powerOn()
            rig.advance(2_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            rig.assertNoViolations()
        }

    @Test
    fun `the session log says which connects were background ones`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeStrap.powerOff()
            val recorded = rig.recording(model = "HR")
            val strap = rig.strap(factory = recorded.factory, connectStrategy = hybrid)
            strap.start()
            rig.advance(20_000)
            rig.settle()
            val states = recorded.events().filter { it.direction.code == 'S' }.map { it.data }
            assertTrue("connecting" in states, "the active attempt: $states")
            assertTrue("connecting-background" in states, "and the background connect: $states")
        }
}
