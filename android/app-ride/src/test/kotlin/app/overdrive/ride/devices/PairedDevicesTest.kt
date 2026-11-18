package app.overdrive.ride.devices

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.link.BackoffPolicy
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.core.device.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paired devices as the container owns them (ADR 0010): connected on launch without a tap, kept between rides,
 * reconnected when the trainer or Bluetooth comes back, replaced cleanly by a new pairing. All in virtual time over core's
 * strict fake radio, which records every contract violation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairedDevicesTest {
    @Test
    fun nothingPairedMeansNoDevicesAndLoadedAnyway() =
        runTest {
            val world = BleWorld(this)
            assertFalse(world.devices.loaded.value)
            world.devices.start()
            world.settle()
            assertTrue(world.devices.loaded.value)
            assertNull(world.devices.trainer.value)
            assertNull(world.devices.strap.value)
            assertNull(world.devices.controls(DeviceRole.Trainer))
            assertTrue("no radio was touched", world.ble.transports.isEmpty())
        }

    @Test
    fun rememberedDevicesConnectOnLaunchWithoutAnyUserAction() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            assertEquals(
                ConnectionState.CONNECTED,
                world.devices.trainer.value!!
                    .device.connection.value,
            )
            assertEquals(
                ConnectionState.CONNECTED,
                world.devices.strap.value!!
                    .device.connection.value,
            )
            assertEquals(LinkStatus.Connected, world.devices.controls(DeviceRole.Trainer)!!.status.value)
            assertEquals(1, world.devices.changes.value)
            assertTrue("a request for control reached the trainer", world.fakeTrainer.hasControl)
            world.assertNoViolations()
        }

    @Test
    fun theDevicesAreStartedBeforeAnyRideAsksForThem() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            // Nobody called connect on the devices: only start() of the container's PairedDevices did.
            assertEquals(listOf("open#1", "connect#1"), world.ble.events.take(2).map { it.what.substringBefore(' ') })
        }

    @Test
    fun pairingADifferentTrainerReplacesTheOldOneCleanly() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val old = world.devices.trainer.value!!
            val changes = world.devices.changes.value

            world.devices.pair(DeviceRole.Trainer, RememberedDevice(OTHER_TRAINER_ID, "Other D500"))
            world.advance(2_000)

            val now = world.devices.trainer.value!!
            assertNotSame(old.device, now.device)
            assertEquals(OTHER_TRAINER_ID, now.remembered.id)
            assertEquals(ConnectionState.CONNECTED, now.device.connection.value)
            assertEquals("the old device is closed", ConnectionState.DISCONNECTED, old.device.connection.value)
            assertEquals(changes + 1, world.devices.changes.value)
            assertEquals(OTHER_TRAINER_ID, world.store.read().trainer?.id)
            assertEquals(
                "the strap is untouched",
                ConnectionState.CONNECTED,
                world.devices.strap.value!!
                    .device.connection.value,
            )
            world.assertNoViolations()
        }

    @Test
    fun pairingTheSameDeviceAgainChangesNothing() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val device = world.devices.trainer.value!!.device
            val changes = world.devices.changes.value
            val opened = world.ble.transports.size

            world.devices.pair(DeviceRole.Trainer, RememberedDevice(TRAINER_ID, "D500"))
            world.advance(5_000)

            assertSame(device, world.devices.trainer.value!!.device)
            assertEquals(changes, world.devices.changes.value)
            assertEquals(opened, world.ble.transports.size)
        }

    @Test
    fun forgettingClosesTheDeviceAndRemovesItFromTheStore() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val trainer = world.devices.trainer.value!!.device

            world.devices.forget(DeviceRole.Trainer)
            world.advance(1_000)

            assertNull(world.devices.trainer.value)
            assertNull(world.store.read().trainer)
            assertNotNull("the strap stays", world.store.read().strap)
            assertEquals(ConnectionState.DISCONNECTED, trainer.connection.value)
            assertTrue(
                "every transport of the trainer is closed",
                world.ble.transports
                    .filter {
                        it.device == TRAINER_ID
                    }.all { it.isClosed },
            )
            assertEquals(2, world.devices.changes.value)
            world.assertNoViolations()
        }

    @Test
    fun forgettingWhatIsNotPairedIsNotAChange() =
        runTest {
            val world = BleWorld(this)
            world.devices.start()
            world.settle()
            val changes = world.devices.changes.value
            world.devices.forget(DeviceRole.Strap)
            assertEquals(changes, world.devices.changes.value)
        }

    @Test
    fun aTrainerPowerCycledAfterTheLaunchIsFoundAgainByItself() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val trainer = world.devices.trainer.value!!.device

            world.fakeTrainer.powerOff()
            world.advance(500)
            assertNotEquals(ConnectionState.CONNECTED, trainer.connection.value)
            assertTrue(world.devices.controls(DeviceRole.Trainer)!!.status.value is LinkStatus.WaitingToRetry)

            world.fakeTrainer.powerOn()
            world.advance(40_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            assertEquals(
                ConnectionState.CONNECTED,
                world.devices.strap.value!!
                    .device.connection.value,
            )
            world.assertNoViolations()
        }

    @Test
    fun theDevicesWaitForBluetoothAndConnectTheMomentItIsOn() =
        runTest {
            val world = BleWorld(this)
            world.ble.setAvailability(BleAvailability.BLUETOOTH_OFF)
            world.launchPaired()
            val trainer = world.devices.trainer.value!!.device
            assertEquals(ConnectionState.DISCONNECTED, trainer.connection.value)
            assertTrue(world.devices.controls(DeviceRole.Trainer)!!.status.value is LinkStatus.BluetoothUnavailable)
            assertTrue("nothing was opened while it was off", world.ble.transports.isEmpty())

            world.ble.setAvailability(BleAvailability.READY)
            world.advance(2_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            assertEquals(
                ConnectionState.CONNECTED,
                world.devices.strap.value!!
                    .device.connection.value,
            )
            world.assertNoViolations()
        }

    @Test
    fun closingKeepsWhatIsRememberedSoTheNextLaunchConnectsAgain() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            world.devices.close()
            world.settle()
            assertNull(world.devices.trainer.value)
            assertNotNull("still remembered", world.store.read().trainer)

            val nextLaunch =
                PairedDevices(
                    scope = backgroundScope,
                    store = world.store,
                    environment = world.ble,
                    transports = { world.ble },
                    reconnect = BackoffPolicy.EXACT,
                    timeSource = testTimeSource,
                )
            nextLaunch.start()
            world.advance(5_000)
            assertEquals(
                ConnectionState.CONNECTED,
                nextLaunch.trainer.value!!
                    .device.connection.value,
            )
            world.assertNoViolations()
        }

    @Test
    fun theNamesThatReachTheRideRecordAreSanitisedAndNeverAnAddress() =
        runTest {
            val world = BleWorld(this)
            world.devices.start()
            world.devices.pair(DeviceRole.Trainer, RememberedDevice(TRAINER_ID, "Rider's D500 SN 12345678"))
            world.advance(2_000)
            val name =
                world.devices.trainer.value!!
                    .device.name
            assertFalse(name.contains("12345678"))
            assertFalse(name.contains(TRAINER_ID.value))
        }
}
