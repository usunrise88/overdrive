package app.overdrive.ride.devices

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.ScanFailure
import app.overdrive.core.ble.fake.FakeFtmsTrainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scanning, listing and pairing on the Devices screen (spec §4.1 "Pairing"), over core's fake radio in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingControllerTest {
    private fun TestScope.controller(
        world: BleWorld,
        canChange: () -> Boolean = { true },
    ) = PairingController(backgroundScope, world.ble, world.devices, canChange, timeSource = testTimeSource)

    @Test
    fun beforeAnyScanThereIsNothingAndNothingWasLookedFor() =
        runTest {
            val world = BleWorld(this)
            val pairing = controller(world)
            world.settle()
            assertEquals(PairingUi(), pairing.state.value)
            assertFalse(pairing.state.value.scanned)
            assertTrue(pairing.state.value.isEmpty)
        }

    @Test
    fun aScanListsTrainersAndStrapsApartByTheirAdvertisedServices() =
        runTest {
            val world = BleWorld(this)
            val pairing = controller(world)
            pairing.scan()
            world.advance(1_500)
            val ui = pairing.state.value
            assertTrue(ui.scanning)
            assertTrue(ui.scanned)
            assertEquals(listOf("D500", "Other D500"), ui.trainers.map { it.name })
            assertEquals(listOf("HR7"), ui.straps.map { it.name })
            assertEquals(setOf(DeviceRole.Trainer), ui.trainers.map { it.role }.toSet())
            assertEquals(setOf(DeviceRole.Strap), ui.straps.map { it.role }.toSet())
            assertTrue("nothing is marked paired yet", (ui.trainers + ui.straps).none { it.paired })
        }

    @Test
    fun theSignalIsShownInStepsOfFiveSoTheListDoesNotFlicker() =
        runTest {
            val world = BleWorld(this)
            val pairing = controller(world)
            world.fakeTrainer.rssi = -63
            pairing.scan()
            world.advance(1_500)
            assertEquals(
                -65,
                pairing.state.value.trainers
                    .first { it.name == "D500" }
                    .rssi,
            )
            world.fakeTrainer.rssi = -61
            world.advance(1_000)
            assertEquals(
                -60,
                pairing.state.value.trainers
                    .first { it.name == "D500" }
                    .rssi,
            )
        }

    @Test
    fun aDeviceThatSentNoNameIsListedUnderItsRolesPlainName() =
        runTest {
            val world = BleWorld(this)
            world.ble.add(DeviceId("nameless"), FakeFtmsTrainer(advertisedName = null))
            val pairing = controller(world)
            pairing.scan()
            world.advance(1_500)
            assertTrue(pairing.state.value.trainers.any { it.name == DeviceRole.Trainer.fallbackName })
        }

    @Test
    fun thePairedDeviceIsMarkedAndTheMarkFollowsAPairing() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val pairing = controller(world)
            pairing.scan()
            world.advance(1_500)
            assertEquals(
                listOf("D500"),
                pairing.state.value.trainers
                    .filter { it.paired }
                    .map { it.name },
            )
            assertEquals(
                listOf("HR7"),
                pairing.state.value.straps
                    .filter { it.paired }
                    .map { it.name },
            )

            pairing.pair(pairing.state.value.trainers.first { it.name == "Other D500" })
            world.advance(2_000)
            assertEquals(
                listOf("Other D500"),
                pairing.state.value.trainers
                    .filter { it.paired }
                    .map { it.name },
            )
            assertEquals(
                OTHER_TRAINER_ID,
                world.devices.trainer.value!!
                    .remembered.id,
            )
            world.assertNoViolations()
        }

    @Test
    fun aScanEndsAfterItsTimeoutAndKeepsWhatItFound() =
        runTest {
            val world = BleWorld(this)
            val pairing = controller(world)
            pairing.scan()
            world.advance(31_000)
            val ui = pairing.state.value
            assertFalse(ui.scanning)
            assertTrue(ui.timedOut)
            assertFalse("still hearing them: they stay listed", ui.isEmpty)
            assertTrue(world.ble.events.any { it.what == "scan stopped" })
        }

    @Test
    fun stopEndsTheScanAndTheListStaysUntilTheNextScan() =
        runTest {
            val world = BleWorld(this)
            val pairing = controller(world)
            pairing.scan()
            world.advance(1_500)
            pairing.stop()
            world.advance(100)
            assertFalse(pairing.state.value.scanning)
            assertFalse(pairing.state.value.timedOut)
            assertFalse(pairing.state.value.isEmpty)
            assertTrue(world.ble.events.any { it.what == "scan stopped" })

            world.fakeTrainer.powerOff()
            world.otherTrainer.powerOff()
            pairing.scan()
            world.settle()
            assertTrue(
                "a new scan starts from an empty list: the old trainers are not in it",
                pairing.state.value.trainers.isEmpty(),
            )
            assertTrue(pairing.state.value.scanning)
            world.advance(1_500)
            assertEquals(listOf("HR7"), pairing.state.value.straps.map { it.name })
            assertTrue(pairing.state.value.trainers.isEmpty())
        }

    @Test
    fun onlyOneScanRunsAtATime() =
        runTest {
            val world = BleWorld(this)
            val pairing = controller(world)
            pairing.scan()
            pairing.scan()
            world.advance(1_500)
            assertEquals(1, world.ble.events.count { it.what == "scan started" })
        }

    @Test
    fun aDevicePoweredOffLeavesTheListOnceItHasBeenQuietForLongEnough() =
        runTest {
            val world = BleWorld(this)
            val pairing = controller(world)
            pairing.scan()
            world.advance(1_500)
            assertEquals(2, pairing.state.value.trainers.size)

            world.otherTrainer.powerOff()
            world.advance(8_000)
            assertEquals("not yet: it may only have missed a few advertisements", 2, pairing.state.value.trainers.size)
            world.advance(8_000)
            assertEquals(listOf("D500"), pairing.state.value.trainers.map { it.name })
        }

    @Test
    fun aScanWithBluetoothOffEndsWithTheReason() =
        runTest {
            val world = BleWorld(this)
            world.ble.setAvailability(BleAvailability.BLUETOOTH_OFF)
            val pairing = controller(world)
            pairing.scan()
            world.advance(1_000)
            val ui = pairing.state.value
            assertFalse(ui.scanning)
            assertEquals(ScanFailure.BLUETOOTH_OFF, ui.failure)
            assertTrue(ui.scanned)
            assertTrue(ui.isEmpty)
        }

    @Test
    fun forgettingGoesThroughAndTheListUnmarksIt() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val pairing = controller(world)
            pairing.scan()
            world.advance(1_500)
            pairing.forget(DeviceRole.Trainer)
            world.advance(1_000)
            assertNull(world.devices.trainer.value)
            assertTrue(pairing.state.value.trainers.none { it.paired })
            assertEquals(
                "the strap stays",
                STRAP_ID,
                world.devices.strap.value!!
                    .remembered.id,
            )
        }

    @Test
    fun aRideThatIsLiveNeverHasItsDevicesChanged() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val pairing = controller(world, canChange = { false })
            pairing.scan()
            world.advance(1_500)
            pairing.pair(pairing.state.value.trainers.first { it.name == "Other D500" })
            pairing.forget(DeviceRole.Strap)
            world.advance(2_000)
            assertEquals(
                TRAINER_ID,
                world.devices.trainer.value!!
                    .remembered.id,
            )
            assertEquals(
                STRAP_ID,
                world.devices.strap.value!!
                    .remembered.id,
            )
            world.assertNoViolations()
        }

    @Test
    fun pairingAFoundDeviceThatIsAlreadyPairedDoesNothing() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val pairing = controller(world)
            pairing.scan()
            world.advance(1_500)
            val changes = world.devices.changes.value
            pairing.pair(pairing.state.value.trainers.first { it.paired })
            world.advance(2_000)
            assertEquals(changes, world.devices.changes.value)
        }
}
