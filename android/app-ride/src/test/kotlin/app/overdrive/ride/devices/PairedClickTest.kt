package app.overdrive.ride.devices

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.fake.FakeClick
import app.overdrive.core.click.ButtonChange
import app.overdrive.core.click.ClickBits
import app.overdrive.core.click.ClickCodec
import app.overdrive.core.click.ClickOptions
import app.overdrive.core.click.ClickState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The paired Click as the container owns it (ADR 0012): a long-lived device beside the trainer and the strap, connected at
 * launch, never a ride input, replaced cleanly by a new pairing, reconnected when Bluetooth comes back, and the one hot flow
 * of its buttons that the handlebar listens to. In virtual time over core's strict fake radio, which records every contract
 * violation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairedClickTest {
    private fun TestScope.collectButtons(world: BleWorld): MutableList<ButtonChange> {
        val seen = mutableListOf<ButtonChange>()
        backgroundScope.launch { world.devices.clickButtons.collect { seen += it } }
        return seen
    }

    private fun FakeClick.count(bytes: ByteArray) = writes.count { it.bytes.contentEquals(bytes) }

    @Test
    fun aRememberedClickConnectsAtLaunchAndDeliversItsButtons() =
        runTest {
            val world = BleWorld(this)
            val seen = collectButtons(world)
            world.launchPaired(withClick = true)
            world.advance(1_000)
            val click = checkNotNull(world.devices.click.value)
            assertEquals("Zwift Click (right)", click.remembered.name)
            assertEquals(ClickState.Active, click.device.state.value)
            assertEquals("read at set-up: 0x64, the real puck sends no battery frame", 100, click.device.battery.value)
            assertNotNull(world.devices.controls(DeviceRole.Click))

            world.fakeClick.press(ClickBits.PLUS)
            world.advance(50)
            world.fakeClick.release(ClickBits.PLUS)
            world.advance(50)
            assertEquals(listOf(ClickBits.PLUS, 0), seen.map { it.held })
            world.assertNoViolations()
        }

    @Test
    fun theClickIsNeverARideInputSoItNeverCountsInTheChanges() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val before = world.devices.changes.value

            world.devices.pair(DeviceRole.Click, RememberedDevice(CLICK_ID, "Zwift Click (right)"))
            world.advance(1_000)
            assertEquals("pairing a Click does not rebuild a ride", before, world.devices.changes.value)
            world.devices.pair(DeviceRole.Click, RememberedDevice(OTHER_CLICK_ID, "Other Click"))
            world.advance(1_000)
            world.devices.forget(DeviceRole.Click)
            world.advance(100)
            assertEquals(before, world.devices.changes.value)

            // The trainer still counts, as before.
            world.devices.forget(DeviceRole.Strap)
            assertEquals(before + 1, world.devices.changes.value)
        }

    @Test
    fun theClickIsLeftAloneWhenTheRideDevicesChange() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired(withClick = true)
            world.advance(1_000)
            val click = checkNotNull(world.devices.click.value)

            // What a new ride session does: the other devices are replaced or forgotten under it.
            world.devices.pair(DeviceRole.Trainer, RememberedDevice(OTHER_TRAINER_ID, "Other D500"))
            world.devices.forget(DeviceRole.Strap)
            world.advance(2_000)
            assertSame("the same device object", click, world.devices.click.value)
            assertEquals(ClickState.Active, click.device.state.value)
            // Open now: the new trainer and the Click. The old trainer's and the strap's links were closed, never the Click's.
            assertEquals(2, world.ble.transports.count { !it.isClosed })
        }

    @Test
    fun aClickReconnectsWhenBluetoothComesBackAndDeliversAgain() =
        runTest {
            val world = BleWorld(this)
            val seen = collectButtons(world)
            world.launchPaired(withClick = true)
            world.advance(1_000)

            world.ble.setAvailability(BleAvailability.BLUETOOTH_OFF)
            world.advance(500)
            val off = checkNotNull(world.devices.click.value).device.state.value
            assertTrue(off.toString(), off is ClickState.BluetoothUnavailable)

            world.ble.setAvailability(BleAvailability.READY)
            world.advance(5_000)
            assertEquals(
                ClickState.Active,
                world.devices.click.value
                    ?.device
                    ?.state
                    ?.value,
            )
            seen.clear()
            world.fakeClick.press(ClickBits.Y)
            world.advance(50)
            assertEquals(listOf(ClickBits.Y), seen.map { it.held })
            world.assertNoViolations()
        }

    @Test
    fun aClickSwitchedOffAfterLaunchIsFoundAgainByItself() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired(withClick = true)
            world.advance(1_000)
            val states = mutableListOf<ClickState>()
            backgroundScope.launch { checkNotNull(world.devices.click.value).device.state.collect { states += it } }
            world.fakeClick.powerOff()
            world.advance(5_000)
            assertTrue(states.toString(), states.any { it is ClickState.NotFound && it.wasConnected })
            assertTrue("not delivering while it is off", states.last() != ClickState.Active)

            world.fakeClick.powerOn()
            world.advance(90_000)
            assertEquals(
                ClickState.Active,
                world.devices.click.value
                    ?.device
                    ?.state
                    ?.value,
            )
        }

    @Test
    fun pairingADifferentClickReplacesTheOldOneCleanlyAndReleasesWhatWasHeld() =
        runTest {
            val world = BleWorld(this)
            val seen = collectButtons(world)
            world.launchPaired(withClick = true)
            world.advance(1_000)
            val old = checkNotNull(world.devices.click.value)

            // A button is down on the old puck when the rider pairs another: its release will never come.
            world.fakeClick.press(ClickBits.A)
            world.advance(50)
            world.devices.pair(DeviceRole.Click, RememberedDevice(OTHER_CLICK_ID, "Other Click"))
            world.advance(2_000)
            val synthetic = seen.last { it.synthetic }
            assertEquals(ClickBits.A, synthetic.released)
            assertEquals(0, synthetic.held)

            val new = checkNotNull(world.devices.click.value)
            assertNotSame(old, new)
            assertEquals("Other Click", new.remembered.name)
            assertEquals(ClickState.Active, new.device.state.value)
            assertEquals("the old Click's link was closed", 1, world.ble.transports.count { it.isClosed })

            // The new puck's buttons arrive on the same flow; the old one's no longer do.
            seen.clear()
            world.otherClick.press(ClickBits.B)
            world.advance(50)
            world.otherClick.release(ClickBits.B)
            world.fakeClick.press(ClickBits.Z)
            world.advance(50)
            assertEquals(listOf(ClickBits.B, 0), seen.map { it.held })
            world.assertNoViolations()
        }

    @Test
    fun forgettingTheClickClosesItReleasesWhatWasHeldAndRemovesItFromTheStore() =
        runTest {
            val world = BleWorld(this)
            val seen = collectButtons(world)
            world.launchPaired(withClick = true)
            world.advance(1_000)
            world.fakeClick.press(ClickBits.Y)
            world.advance(50)

            world.devices.forget(DeviceRole.Click)
            world.advance(500)
            assertNull(world.devices.click.value)
            assertNull(world.devices.controls(DeviceRole.Click))
            assertTrue(seen.last().synthetic)
            assertNull(world.store.read().click)
            assertEquals(
                "the Click's link is closed, the trainer's and the strap's are not",
                2,
                world.ble.transports
                    .count {
                        !it.isClosed
                    },
            )
        }

    @Test
    fun pairingTheSameClickAgainChangesNothing() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired(withClick = true)
            world.advance(1_000)
            val first = checkNotNull(world.devices.click.value)
            val links = world.ble.transports.size

            world.devices.pair(DeviceRole.Click, RememberedDevice(CLICK_ID, "Zwift Click (right)"))
            world.advance(1_000)
            assertSame(first, world.devices.click.value)
            assertEquals(links, world.ble.transports.size)
        }

    @Test
    fun closingKeepsTheRememberedClickSoTheNextLaunchConnectsAgain() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired(withClick = true)
            world.advance(1_000)
            world.devices.close()
            assertNull(world.devices.click.value)
            assertEquals(CLICK_ID, world.store.read().click?.id)
        }

    @Test
    fun aClickWithNoDevelopmentOptionsConnectsTheWayTheNotesRecommend() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired(withClick = true)
            world.advance(1_000)
            assertEquals(1, world.fakeClick.handshakeCount)
            assertEquals("FF 04 00 is written by default (CK 6)", 1, world.fakeClick.count(ClickCodec.postHandshake()))
            assertTrue(
                "the handshake is written without response by default (CK 2)",
                world.fakeClick.writes
                    .first()
                    .withResponse
                    .not(),
            )
        }

    @Test
    fun theOptionsAreWaitedForBeforeAnythingIsStartedSoTheClickNeverConnectsWithTheWrongOnes() =
        runTest {
            val late =
                flow {
                    delay(2.seconds)
                    emit(ClickOptions(sendPostHandshake = false))
                }
            val world = BleWorld(this, clickOptions = late)
            world.launchPaired(withClick = true)
            // launchPaired advanced 2 s: the first value has just arrived.
            world.advance(3_000)
            assertEquals(
                ClickState.Active,
                world.devices.click.value
                    ?.device
                    ?.state
                    ?.value,
            )
            assertEquals(1, world.fakeClick.handshakeCount)
            assertEquals("never with the default options", 0, world.fakeClick.count(ClickCodec.postHandshake()))
        }

    @Test
    fun aChangedOptionReplacesTheClickAndOnlyTheClick() =
        runTest {
            val options = MutableStateFlow(ClickOptions())
            val world = BleWorld(this, clickOptions = options)
            world.launchPaired(withClick = true)
            world.advance(1_000)
            val trainer = world.devices.trainer.value
            val click = checkNotNull(world.devices.click.value)
            val changes = world.devices.changes.value
            assertEquals(1, world.fakeClick.count(ClickCodec.postHandshake()))

            options.value = ClickOptions(sendPostHandshake = false, writeWithResponse = true)
            world.advance(5_000)
            val again = checkNotNull(world.devices.click.value)
            assertNotSame("a new device with the new options", click.device, again.device)
            assertEquals("the same pairing", click.remembered, again.remembered)
            assertSame("the trainer was not touched", trainer, world.devices.trainer.value)
            assertEquals("no ride is rebuilt", changes, world.devices.changes.value)
            assertEquals(2, world.fakeClick.handshakeCount)
            assertEquals("FF 04 00 only the first time", 1, world.fakeClick.count(ClickCodec.postHandshake()))
            assertTrue("the second handshake was written with a response", world.fakeClick.writes.last().withResponse)
            assertEquals(ClickState.Active, again.device.state.value)
            world.assertNoViolations()
        }
}
