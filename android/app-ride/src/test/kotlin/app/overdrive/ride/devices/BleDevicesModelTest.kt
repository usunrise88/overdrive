package app.overdrive.ride.devices

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.link.LinkFailure
import app.overdrive.core.ble.link.LinkFailureKind
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.ride.host.DeviceRowUi
import app.overdrive.ride.host.DevicesUi
import app.overdrive.ride.host.RadioIssue
import app.overdrive.ride.settings.SimulatedClick
import app.overdrive.uikit.DeviceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/** The link states of `core` in the words of the Devices screen (design/system.md §7.7), as pure mappings. */
class BleDeviceRowsTest {
    private fun waiting(
        kind: LinkFailureKind,
        retryInMillis: Long = 4_000,
    ) = LinkStatus.WaitingToRetry(failures = 1, retryInMillis = retryInMillis, failure = LinkFailure(kind))

    private fun row(
        status: LinkStatus?,
        name: String? = "D500",
        availability: BleAvailability = BleAvailability.READY,
        seconds: Int? = null,
        role: DeviceRole = DeviceRole.Trainer,
    ) = BleDeviceRows.row(role, name, status, availability, seconds)

    @Test
    fun nothingPairedIsNotPairedWithoutForget() {
        assertEquals(DeviceRowUi(DeviceStatus.NotPaired, "Not paired"), row(null, name = null))
        assertEquals(DeviceRowUi(DeviceStatus.NotPaired, "Not paired"), row(LinkStatus.Connected, name = null))
    }

    @Test
    fun aConnectedDeviceShowsItsNameAndCanBeForgotten() {
        assertEquals(DeviceRowUi(DeviceStatus.Connected, "D500", canForget = true), row(LinkStatus.Connected))
    }

    @Test
    fun aDeviceBeingConnectedSearches() {
        for (status in listOf(LinkStatus.Connecting(1), LinkStatus.Stopped, null)) {
            assertEquals(DeviceRowUi(DeviceStatus.Searching, "Connecting to D500…", canForget = true), row(status))
        }
    }

    @Test
    fun aLostLinkSaysWhyAndHowLongTheRetryWaits() {
        val lost = row(waiting(LinkFailureKind.LINK_LOST), seconds = 4)
        assertEquals(DeviceStatus.Lost, lost.status)
        assertEquals("Connection lost · retrying in 4 s", lost.subtitle)
        assertTrue(lost.canForget)
        assertEquals("Not found · retrying in 2 s", row(waiting(LinkFailureKind.CONNECT_FAILED), seconds = 2).subtitle)
        assertEquals("Not found · retrying in 1 s", row(waiting(LinkFailureKind.TIMED_OUT), seconds = 1).subtitle)
        assertEquals("Could not connect · retrying…", row(waiting(LinkFailureKind.SETUP_FAILED), seconds = 0).subtitle)
        assertEquals("Waiting for Bluetooth · retrying…", row(waiting(LinkFailureKind.UNAVAILABLE)).subtitle)
    }

    @Test
    fun aDeviceThatIsNotTheRightKindSaysSoPerRole() {
        assertEquals(
            "Not a trainer · check the pairing",
            row(waiting(LinkFailureKind.SERVICE_MISSING)).subtitle,
        )
        assertEquals(
            "Not a heart-rate strap · check the pairing",
            row(waiting(LinkFailureKind.SERVICE_MISSING), name = "HR7", role = DeviceRole.Strap).subtitle,
        )
    }

    @Test
    fun bluetoothThatCannotBeUsedComesBeforeAnythingAboutTheLink() {
        val off = row(LinkStatus.Connected, availability = BleAvailability.BLUETOOTH_OFF)
        assertEquals(DeviceRowUi(DeviceStatus.Blocked, "D500 · Bluetooth is off", canForget = true), off)
        val denied = row(waiting(LinkFailureKind.LINK_LOST), availability = BleAvailability.PERMISSION_DENIED)
        assertEquals(DeviceRowUi(DeviceStatus.Blocked, "D500 · needs Nearby devices", canForget = true), denied)
        val none = row(null, availability = BleAvailability.UNSUPPORTED)
        assertEquals(DeviceRowUi(DeviceStatus.Blocked, "D500 · no Bluetooth on this tablet", canForget = true), none)
        // Nothing paired stays "Not paired": there is nothing to be blocked.
        assertEquals(
            DeviceStatus.NotPaired,
            row(null, name = null, availability = BleAvailability.BLUETOOTH_OFF).status,
        )
    }

    @Test
    fun theRadioIssueIsTheOneThingToFix() {
        assertNull(BleDeviceRows.radioIssue(BleAvailability.READY))
        assertEquals(RadioIssue.BluetoothOff, BleDeviceRows.radioIssue(BleAvailability.BLUETOOTH_OFF))
        assertEquals(RadioIssue.PermissionNeeded, BleDeviceRows.radioIssue(BleAvailability.PERMISSION_DENIED))
        assertEquals(RadioIssue.Unsupported, BleDeviceRows.radioIssue(BleAvailability.UNSUPPORTED))
    }
}

/** The rows for the paired devices of a world, live: link changes, the radio, the countdown that ticks only while waiting. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleDevicesModelTest {
    private fun TestScope.model(
        world: BleWorld,
        click: MutableStateFlow<SimulatedClick> = MutableStateFlow(SimulatedClick.Connected),
    ) = BleDevicesModel(world.devices, world.ble, click, testTimeSource)

    private fun TestScope.collect(model: BleDevicesModel): List<DevicesUi> {
        val seen = mutableListOf<DevicesUi>()
        backgroundScope.launch { model.ui.collect { seen += it } }
        return seen
    }

    @Test
    fun theRowsAreUnknownUntilTheStoreHasBeenRead() =
        runTest {
            val world = BleWorld(this)
            val seen = collect(model(world))
            world.settle()
            assertEquals(listOf(DevicesUi.Unknown), seen)
            world.devices.start()
            world.advance(100)
            assertEquals(DeviceStatus.NotPaired, seen.last().trainer.status)
            assertEquals(DeviceStatus.NotPaired, seen.last().heartRate.status)
        }

    @Test
    fun pairedDevicesGoFromConnectingToConnected() =
        runTest {
            val world = BleWorld(this)
            val seen = collect(model(world))
            world.launchPaired()
            val last = seen.last()
            assertEquals(
                DevicesUi(
                    trainer = DeviceRowUi(DeviceStatus.Connected, "D500", canForget = true),
                    heartRate = DeviceRowUi(DeviceStatus.Connected, "HR7", canForget = true),
                    click = DeviceRowUi(DeviceStatus.Connected, "Simulated Click"),
                ),
                last,
            )
            assertTrue("it went through connecting", seen.any { it.trainer.status == DeviceStatus.Searching })
            assertFalse(last.simulated)
            assertTrue(last.canChange)
            assertNull(last.radio)
        }

    @Test
    fun aTrainerThatIsOffCountsDownItsRetryOncePerSecondAndOnlyWhileItWaits() =
        runTest {
            val world = BleWorld(this)
            world.fakeTrainer.powered = false
            world.fakeTrainer.absentFailsAfter = 100.milliseconds
            val seen = collect(model(world))
            world.launchPaired()
            // Attempts fail fast with 133 and the waits grow 1 s, 2 s, 4 s: watch the third.
            world.advance(6_000)
            val texts = seen.map { it.trainer.subtitle }
            assertTrue(texts.toString(), texts.contains("Not found · retrying in 2 s"))
            assertTrue(texts.toString(), texts.contains("Not found · retrying in 1 s"))
            // The first wait is 1 s long; the second counts 2, then 1.
            assertTrue(
                texts.toString(),
                texts.indexOf("Not found · retrying in 2 s") < texts.lastIndexOf("Not found · retrying in 1 s"),
            )
            assertTrue("a few emissions a second at most while waiting: ${seen.size}", seen.size < 30)

            // A device that is connected does not tick.
            world.fakeTrainer.powered = true
            world.advance(30_000)
            val connected = seen.size
            assertEquals(DeviceStatus.Connected, seen.last().trainer.status)
            world.advance(120_000)
            assertEquals("nothing is emitted at rest", connected, seen.size)
        }

    @Test
    fun bluetoothOffTurnsPairedRowsIntoBlockedOnesAndSetsTheOneNotice() =
        runTest {
            val world = BleWorld(this)
            val seen = collect(model(world))
            world.launchPaired()
            world.ble.setAvailability(BleAvailability.BLUETOOTH_OFF)
            world.advance(500)
            assertEquals(RadioIssue.BluetoothOff, seen.last().radio)
            assertEquals(DeviceStatus.Blocked, seen.last().trainer.status)
            assertEquals(DeviceStatus.Blocked, seen.last().heartRate.status)

            world.ble.setAvailability(BleAvailability.PERMISSION_DENIED)
            world.advance(500)
            assertEquals(RadioIssue.PermissionNeeded, seen.last().radio)

            world.ble.setAvailability(BleAvailability.READY)
            world.advance(2_000)
            assertNull(seen.last().radio)
            assertEquals(DeviceStatus.Connected, seen.last().trainer.status)
        }

    @Test
    fun theClickRowFollowsTheDeveloperSettingUntilM4() =
        runTest {
            val world = BleWorld(this)
            val click = MutableStateFlow(SimulatedClick.Connected)
            val seen = collect(model(world, click))
            world.launchPaired()
            click.value = SimulatedClick.Locked
            world.advance(100)
            assertEquals(DeviceStatus.Locked, seen.last().click.status)
            click.value = SimulatedClick.NotPaired
            world.advance(100)
            assertEquals(DeviceStatus.NotPaired, seen.last().click.status)
        }

    @Test
    fun aForgottenDeviceBecomesNotPairedAtOnce() =
        runTest {
            val world = BleWorld(this)
            val seen = collect(model(world))
            world.launchPaired()
            world.devices.forget(DeviceRole.Strap)
            world.advance(100)
            assertEquals(DeviceRowUi(DeviceStatus.NotPaired, "Not paired"), seen.last().heartRate)
            assertEquals(DeviceStatus.Connected, seen.last().trainer.status)
        }
}
