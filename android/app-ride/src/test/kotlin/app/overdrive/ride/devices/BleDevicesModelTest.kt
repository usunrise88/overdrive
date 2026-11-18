package app.overdrive.ride.devices

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.fake.FakeClickLock
import app.overdrive.core.ble.link.LinkFailure
import app.overdrive.core.ble.link.LinkFailureKind
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.core.click.ClickOptions
import app.overdrive.core.click.ClickState
import app.overdrive.core.click.UnlockSignal
import app.overdrive.ride.host.DeviceRowUi
import app.overdrive.ride.host.DevicesUi
import app.overdrive.ride.host.RadioIssue
import app.overdrive.uikit.DeviceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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

    private fun clickRow(
        state: ClickState?,
        name: String? = "Zwift Click (right)",
        battery: Int? = null,
        availability: BleAvailability = BleAvailability.READY,
        seconds: Int? = null,
    ) = BleDeviceRows.clickRow(name, state, battery, availability, seconds)

    @Test
    fun aClickNotPairedIsNotPairedAndABlockedRadioComesFirst() {
        assertEquals(DeviceRowUi(DeviceStatus.NotPaired, "Not paired"), clickRow(ClickState.Active, name = null))
        val off = clickRow(ClickState.Active, availability = BleAvailability.BLUETOOTH_OFF)
        assertEquals(
            DeviceRowUi(DeviceStatus.Blocked, "Zwift Click (right) · Bluetooth is off", canForget = true),
            off,
        )
        val denied = clickRow(ClickState.Waiting, availability = BleAvailability.PERMISSION_DENIED)
        assertEquals(DeviceStatus.Blocked, denied.status)
        assertEquals("Zwift Click (right) · needs Nearby devices", denied.subtitle)
    }

    @Test
    fun anActiveClickIsConnectedAndShowsItsBatteryOnceKnown() {
        assertEquals(
            DeviceRowUi(DeviceStatus.Connected, "Zwift Click (right)", canForget = true),
            clickRow(ClickState.Active),
        )
        assertEquals("Zwift Click (right) · battery 87%", clickRow(ClickState.Active, battery = 87).subtitle)
        assertEquals("Zwift Click (right) · battery 0%", clickRow(ClickState.Active, battery = 0).subtitle)
        // A number that is not a percentage is not shown.
        assertEquals("Zwift Click (right)", clickRow(ClickState.Active, battery = 255).subtitle)
        assertEquals("Zwift Click (right)", clickRow(ClickState.Active, battery = -1).subtitle)
    }

    @Test
    fun aClickThatIsConnectingOrWaitingForItsFirstMessageSearches() {
        for (state in listOf(
            ClickState.Connecting(1),
            ClickState.Stopped,
            ClickState.BluetoothUnavailable(BleAvailability.READY),
            null,
        )) {
            assertEquals(
                DeviceRowUi(DeviceStatus.Searching, "Connecting to Zwift Click (right)…", canForget = true),
                clickRow(state),
            )
        }
        assertEquals(
            DeviceRowUi(DeviceStatus.Searching, "Zwift Click (right) · press any button", canForget = true),
            clickRow(ClickState.Waiting),
        )
    }

    @Test
    fun aClickThatIsNotThereSaysWhyAndHowLongTheRetryWaits() {
        val notFound =
            clickRow(ClickState.NotFound(failures = 1, retryInMillis = 8_000, wasConnected = false), seconds = 8)
        assertEquals(DeviceRowUi(DeviceStatus.Lost, "Not found · retrying in 8 s", canForget = true), notFound)
        val lost = clickRow(ClickState.NotFound(failures = 1, retryInMillis = 1_000, wasConnected = true), seconds = 1)
        assertEquals("Connection lost · retrying in 1 s", lost.subtitle)
        val failure = LinkFailure(LinkFailureKind.SETUP_FAILED)
        val setup = clickRow(ClickState.SetupFailed(failure, retryInMillis = 2_000), seconds = 0)
        assertEquals(DeviceRowUi(DeviceStatus.Lost, "Could not connect · retrying…", canForget = true), setup)
    }

    @Test
    fun aLockedClickIsALockedRowThatCarriesTheSignalForTheNotice() {
        for (signal in UnlockSignal.entries) {
            assertEquals(
                DeviceRowUi(DeviceStatus.Locked, "Zwift Click (right) · locked", canForget = true, locked = signal),
                clickRow(ClickState.NeedsUnlock(signal)),
            )
        }
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
    private fun TestScope.model(world: BleWorld) = BleDevicesModel(world.devices, world.ble, testTimeSource)

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
                    click = DeviceRowUi(DeviceStatus.NotPaired, "Not paired"),
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
    fun aPairedClickGoesFromConnectingToConnectedWithItsBattery() =
        runTest {
            val world = BleWorld(this)
            val seen = collect(model(world))
            world.launchPaired(withClick = true)
            world.advance(1_000)
            val last = seen.last().click
            assertEquals(DeviceStatus.Connected, last.status)
            assertEquals("Zwift Click (right) · battery 100%", last.subtitle)
            assertTrue("it went through connecting", seen.any { it.click.status == DeviceStatus.Searching })
            assertEquals(DeviceStatus.Connected, seen.last().trainer.status)
        }

    @Test
    fun aClickThatIsOffCountsDownItsRetryAndComesBackWhenItIsSwitchedOn() =
        runTest {
            val world = BleWorld(this)
            world.fakeClick.powered = false
            world.fakeClick.absentFailsAfter = 100.milliseconds
            val seen = collect(model(world))
            world.launchPaired(withClick = true)
            world.advance(6_000)
            val texts = seen.map { it.click.subtitle }
            assertTrue(texts.toString(), texts.any { it.startsWith("Not found · retrying in ") })
            assertTrue(texts.toString(), texts.contains("Not found · retrying in 1 s"))

            world.fakeClick.powered = true
            world.advance(30_000)
            assertEquals(DeviceStatus.Connected, seen.last().click.status)
            val connected = seen.size
            world.advance(120_000)
            assertEquals("nothing is emitted at rest", connected, seen.size)
        }

    @Test
    fun aClickThatSaysNothingIsConnectedByDefaultBecauseARealOneWaitsForAPress() =
        runTest {
            val world = BleWorld(this)
            world.fakeClick.lock = FakeClickLock.SILENT
            val seen = collect(model(world))
            world.launchPaired(withClick = true)
            world.advance(35_000)
            assertEquals(DeviceStatus.Connected, seen.last().click.status)
            assertNull(seen.last().click.locked)
            assertTrue(seen.none { it.click.subtitle.contains("press any button") })
        }

    @Test
    fun withTheSilenceRuleOnALockedClickShowsTheSignalThatGaveItAway() =
        runTest {
            val world = BleWorld(this, clickOptions = flowOf(ClickOptions(silentMeansLocked = true)))
            world.fakeClick.lock = FakeClickLock.SILENT
            val seen = collect(model(world))
            world.launchPaired(withClick = true)
            world.advance(5_000)
            assertEquals(DeviceStatus.Searching, seen.last().click.status)
            assertEquals("Zwift Click (right) · press any button", seen.last().click.subtitle)
            // Past the no-event time (30 s by default): until then the row says to press a button.
            world.advance(30_000)
            assertEquals(DeviceStatus.Locked, seen.last().click.status)
            assertEquals(UnlockSignal.NO_EVENTS, seen.last().click.locked)

            // Unlocked through Zwift: a late frame turns the row into a connected one by itself.
            world.fakeClick.unlock()
            world.advance(2_000)
            world.fakeClick.press(1)
            world.fakeClick.release(1)
            world.advance(1_000)
            assertEquals(DeviceStatus.Connected, seen.last().click.status)
            assertNull(seen.last().click.locked)
        }

    @Test
    fun aClickWithItsServiceHiddenIsLockedWithTheOtherSignal() =
        runTest {
            val world = BleWorld(this)
            world.fakeClick.lock = FakeClickLock.HIDDEN
            val seen = collect(model(world))
            world.launchPaired(withClick = true)
            world.advance(3_000)
            assertEquals(DeviceStatus.Locked, seen.last().click.status)
            assertEquals(UnlockSignal.SERVICE_HIDDEN, seen.last().click.locked)
        }

    @Test
    fun theClickRowIsBlockedWithTheOthersWhenBluetoothIsOff() =
        runTest {
            val world = BleWorld(this)
            val seen = collect(model(world))
            world.launchPaired(withClick = true)
            world.ble.setAvailability(BleAvailability.BLUETOOTH_OFF)
            world.advance(500)
            assertEquals(DeviceStatus.Blocked, seen.last().click.status)
            assertEquals(RadioIssue.BluetoothOff, seen.last().radio)
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
