package app.overdrive.ride.host

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.fake.FakeTrainerCommand
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.core.ride.PauseReason
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import app.overdrive.ride.devices.BleDevicesModel
import app.overdrive.ride.devices.BleWorld
import app.overdrive.ride.devices.DeviceRole
import app.overdrive.ride.devices.OTHER_TRAINER_ID
import app.overdrive.ride.devices.PairingController
import app.overdrive.ride.devices.RememberedDevice
import app.overdrive.ride.devices.TRAINER_ID
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.DeviceSource
import app.overdrive.uikit.DeviceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * The ride host over **real devices** (ADR 0010): [RideHost] and the unchanged ride engine over [BleRideSessionFactory],
 * over the long-lived [app.overdrive.ride.devices.PairedDevices], over core's strict fake radio, in virtual time. These are
 * the scenarios of the brief: an ERG workout, a trainer dropout, a strap dropout, no trainer, the Developer switch, and the
 * rule that a started ride is never replaced and that devices outlive the sessions built over them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleRideHostTest {
    private val erg =
        Workout(
            schema = 1,
            id = "erg",
            name = "ERG",
            steps =
                listOf(
                    SteadyStep(duration = 30, target = 0.8, label = "Steady"),
                    FreeStep(duration = 20, label = "Free"),
                ),
        )
    private val steady = Workout(1, "steady", "Steady", steps = listOf(SteadyStep(duration = 300, target = 0.8)))

    private class Rig(
        val world: BleWorld,
        val host: RideHost,
        val settings: MutableStateFlow<AppSettings>,
        val snapshots: List<RideSnapshot>,
    )

    private fun TestScope.rig(
        world: BleWorld = BleWorld(this),
        settings: MutableStateFlow<AppSettings> = MutableStateFlow(AppSettings(ftp = 250)),
        choice: RideChoice = RideChoice.Planned(erg),
    ): Rig {
        val rows =
            BleDevicesModel(world.devices, world.ble, settings.map { it.simulatedClick }, testTimeSource).ui
        val host =
            RideHost(
                factory =
                    DeviceSourceFactory(
                        BleRideSessionFactory(world.devices, rows),
                        SimulatedRideSessionFactory(),
                    ),
                settings = settings,
                initialChoice = choice,
                dispatcher = StandardTestDispatcher(testScheduler),
                debounceMillis = 100,
                rideTriggers = world.devices.changes,
            )
        val snapshots = mutableListOf<RideSnapshot>()
        backgroundScope.launch { host.snapshots.collect { snapshots += it } }
        return Rig(world, host, settings, snapshots)
    }

    private suspend fun BleWorld.pairTrainerOnly() {
        store.remember(DeviceRole.Trainer, RememberedDevice(TRAINER_ID, "D500"))
        devices.start()
        advance(2_000)
    }

    @Test
    fun withNoTrainerPairedTheRideStaysIdleAndNothingIsSimulated() =
        runTest {
            val world = BleWorld(this)
            world.devices.start()
            val rig = rig(world)
            world.advance(2_000)
            val host = rig.host
            assertEquals(RideState.IDLE, host.snapshots.value.state)
            assertEquals(DeviceStatus.NotPaired, host.devices.value.trainer.status)
            assertFalse("these are the real devices' rows", host.devices.value.simulated)

            // The engine refuses to start, and a minute later nothing has been ridden.
            host.actions.start()
            world.advance(60_000)
            assertEquals(RideState.IDLE, host.snapshots.value.state)
            assertEquals(0, host.snapshots.value.elapsedSeconds)
            assertNull("no power from nowhere", host.snapshots.value.power)
            assertTrue(rig.snapshots.none { it.state != RideState.IDLE })
            assertTrue("no radio was touched", world.ble.transports.isEmpty())
            host.close()
        }

    @Test
    fun aTrainerPairedLaterMakesTheIdleRideReadyByItself() =
        runTest {
            val world = BleWorld(this)
            world.devices.start()
            val rig = rig(world)
            world.advance(2_000)
            val first = rig.host.ui.value.rideNumber
            assertEquals(RideState.IDLE, rig.host.snapshots.value.state)

            world.devices.pair(DeviceRole.Trainer, RememberedDevice(TRAINER_ID, "D500"))
            world.advance(3_000)

            assertEquals(RideState.READY, rig.host.snapshots.value.state)
            assertEquals(first + 1, rig.host.ui.value.rideNumber)
            assertEquals(DeviceStatus.Connected, rig.host.devices.value.trainer.status)
            assertEquals("D500", rig.host.devices.value.trainer.subtitle)
            assertEquals(DeviceStatus.NotPaired, rig.host.devices.value.heartRate.status)
            world.assertNoViolations()
            rig.host.close()
        }

    @Test
    fun anErgWorkoutOverBluetoothTellsTheTrainerEachTargetAndFinishes() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val rig = rig(world)
            world.advance(1_000)
            assertEquals(RideState.READY, rig.host.snapshots.value.state)

            rig.host.actions.start()
            world.advance(15_000)
            assertEquals(RideState.RIDING, rig.host.snapshots.value.state)
            assertEquals(200, rig.host.snapshots.value.targetPower)
            assertEquals(200, world.fakeTrainer.targetPowerWatts)
            assertNotNull("the strap's heart rate is in the ride", rig.host.snapshots.value.heartRate)

            world.advance(40_000)
            assertEquals(RideState.FINISHED, rig.host.snapshots.value.state)
            val targets = world.fakeTrainer.commands.filterIsInstance<FakeTrainerCommand.TargetPower>().map { it.watts }
            assertTrue(targets.toString(), 200 in targets)
            assertEquals(
                50,
                rig.host.ui.value.finished!!
                    .summary.durationSeconds,
            )
            world.assertNoViolations()
            rig.host.close()
        }

    @Test
    fun theTrainerDroppingMidRidePausesItAndTheReconnectResumesIt() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val rig = rig(world, choice = RideChoice.Planned(steady))
            world.advance(1_000)
            rig.host.actions.start()
            world.advance(10_000)
            assertEquals(RideState.RIDING, rig.host.snapshots.value.state)

            world.fakeTrainer.powerOff()
            world.advance(500)
            assertEquals(RideState.PAUSED, rig.host.snapshots.value.state)
            assertEquals(PauseReason.TRAINER_DISCONNECTED, rig.host.snapshots.value.pauseReason)
            assertEquals(DeviceStatus.Lost, rig.host.devices.value.trainer.status)
            assertTrue(
                rig.host.devices.value.trainer.subtitle,
                rig.host.devices.value.trainer.subtitle
                    .startsWith("Connection lost"),
            )
            assertFalse("a ride that is live is not offered pairing", rig.host.devices.value.canChange)

            world.advance(20_000)
            assertEquals("still off: still paused, not ended", RideState.PAUSED, rig.host.snapshots.value.state)
            world.fakeTrainer.powerOn()
            world.advance(40_000)
            assertEquals("the reconnect resumed it", RideState.RIDING, rig.host.snapshots.value.state)
            assertEquals(DeviceStatus.Connected, rig.host.devices.value.trainer.status)
            assertEquals("the target is told again", 200, world.fakeTrainer.targetPowerWatts)
            assertTrue(world.fakeTrainer.hasControl)
            world.assertNoViolations()
            rig.host.close()
        }

    @Test
    fun theStrapDroppingIsAGapInHeartRateAndNothingElse() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val rig = rig(world, choice = RideChoice.Planned(steady))
            world.advance(1_000)
            rig.host.actions.start()
            world.advance(10_000)
            assertNotNull(rig.host.snapshots.value.heartRate)

            world.fakeStrap.powerOff()
            world.advance(20_000)
            assertEquals("a strap never pauses the ride", RideState.RIDING, rig.host.snapshots.value.state)
            assertNull("a gap, not a zero", rig.host.snapshots.value.heartRate)
            assertNotNull(rig.host.snapshots.value.power)
            assertEquals(DeviceStatus.Connected, rig.host.devices.value.trainer.status)
            assertNotEquals(DeviceStatus.Connected, rig.host.devices.value.heartRate.status)

            world.fakeStrap.powerOn()
            // An attempt that was waiting for the strap runs out, then the backoff brings the next one: up to a minute.
            world.advance(90_000)
            assertEquals(RideState.RIDING, rig.host.snapshots.value.state)
            assertNotNull("it came back by itself", rig.host.snapshots.value.heartRate)
            assertEquals(DeviceStatus.Connected, rig.host.devices.value.heartRate.status)
            assertTrue(rig.snapshots.none { it.state == RideState.PAUSED })
            world.assertNoViolations()
            rig.host.close()
        }

    @Test
    fun aRideWithoutAStrapHasNoHeartRateAndIsStillARide() =
        runTest {
            val world = BleWorld(this)
            world.pairTrainerOnly()
            val rig = rig(world, choice = RideChoice.Planned(steady))
            world.advance(1_000)
            assertEquals(RideState.READY, rig.host.snapshots.value.state)
            rig.host.actions.start()
            world.advance(10_000)
            assertEquals(RideState.RIDING, rig.host.snapshots.value.state)
            assertNull(rig.host.snapshots.value.heartRate)
            assertNotNull(rig.host.snapshots.value.power)
            rig.host.close()
        }

    @Test
    fun theDevicesOutliveTheSessionsBuiltOverThem() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val rig = rig(world)
            world.advance(1_000)
            val trainer = world.devices.trainer.value!!.device
            val strap = world.devices.strap.value!!.device
            val opened = world.ble.transports.size
            val first = rig.host.ui.value.rideNumber

            rig.host.select(RideChoice.Free)
            world.advance(1_000)
            rig.host.select(RideChoice.Planned(steady))
            world.advance(1_000)

            assertEquals(first + 2, rig.host.ui.value.rideNumber)
            assertSame(trainer, world.devices.trainer.value!!.device)
            assertSame(strap, world.devices.strap.value!!.device)
            assertEquals("no link was closed and reopened", opened, world.ble.transports.size)
            assertTrue(world.ble.transports.none { it.isClosed })
            assertEquals(RideState.READY, rig.host.snapshots.value.state)
            world.assertNoViolations()

            rig.host.close()
            world.advance(1_000)
            assertEquals("closing the host does not close the devices", opened, world.ble.transports.size)
            assertTrue(world.ble.transports.none { it.isClosed })
        }

    @Test
    fun theDeveloperSwitchChoosesBluetoothOrTheSimulationAndNeverFallsBack() =
        runTest {
            val world = BleWorld(this)
            world.devices.start()
            val rig = rig(world)
            world.advance(2_000)
            assertEquals("Bluetooth is the default", DeviceSource.Bluetooth, rig.settings.value.deviceSource)
            assertEquals("no trainer, no ride", RideState.IDLE, rig.host.snapshots.value.state)

            rig.settings.value = rig.settings.value.copy(deviceSource = DeviceSource.Simulated)
            world.advance(1_000)
            assertEquals(RideState.READY, rig.host.snapshots.value.state)
            assertTrue(rig.host.devices.value.simulated)
            assertTrue("the simulation never touches the radio", world.ble.transports.isEmpty())
            rig.host.actions.start()
            world.advance(5_000)
            assertEquals(RideState.RIDING, rig.host.snapshots.value.state)
            // A ride that is live keeps its devices when the setting is flipped.
            rig.settings.value = rig.settings.value.copy(deviceSource = DeviceSource.Bluetooth)
            world.advance(2_000)
            assertEquals(RideState.RIDING, rig.host.snapshots.value.state)
            assertTrue(rig.host.devices.value.simulated)
            rig.host.actions.finish()
            world.advance(500)
            rig.host.reset()
            world.advance(2_000)

            assertEquals("back to Bluetooth, which has no trainer", RideState.IDLE, rig.host.snapshots.value.state)
            assertFalse(rig.host.devices.value.simulated)
            rig.host.close()
        }

    @Test
    fun aRideThatHasStartedIsNeverReplacedWhenWhatIsPairedChanges() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val rig = rig(world, choice = RideChoice.Planned(steady))
            world.advance(1_000)
            rig.host.actions.start()
            world.advance(5_000)
            val number = rig.host.ui.value.rideNumber

            // Not offered on screen (see below), but even if it happened below the screen the ride is not rebuilt.
            world.devices.pair(DeviceRole.Trainer, RememberedDevice(OTHER_TRAINER_ID, "Other D500"))
            world.advance(5_000)

            assertEquals(number, rig.host.ui.value.rideNumber)
            val state = rig.host.snapshots.value.state
            assertTrue(state.toString(), state in setOf(RideState.RIDING, RideState.PAUSED))
            assertTrue(rig.host.snapshots.value.elapsedSeconds >= 5)
            rig.host.close()
        }

    @Test
    fun pairingAndForgettingAreRefusedWhileARideIsLive() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val rig = rig(world, choice = RideChoice.Planned(steady))
            val canChange = { rig.host.devices.value.canChange }
            val pairing =
                PairingController(backgroundScope, world.ble, world.devices, canChange, timeSource = testTimeSource)
            world.advance(1_000)
            assertTrue("before the start it is offered", rig.host.devices.value.canChange)

            rig.host.actions.start()
            world.advance(3_000)
            assertFalse(rig.host.devices.value.canChange)
            pairing.forget(DeviceRole.Trainer)
            world.advance(1_000)
            assertEquals(
                TRAINER_ID,
                world.devices.trainer.value!!
                    .remembered.id,
            )
            assertEquals(RideState.RIDING, rig.host.snapshots.value.state)

            rig.host.actions.finish()
            world.advance(500)
            assertTrue("after the summary it is offered again", rig.host.devices.value.canChange)
            rig.host.close()
        }

    @Test
    fun retryNowConnectsWithoutWaitingForTheBackoff() =
        runTest {
            val world = BleWorld(this)
            world.fakeTrainer.powered = false
            world.fakeTrainer.absentFailsAfter = 100.milliseconds
            world.launchPaired()
            val rig = rig(world)
            world.advance(20_000) // the waits are 1, 2, 4, 8 s by now: the next attempt is far off
            val status = world.devices.controls(DeviceRole.Trainer)!!.status.value
            assertTrue(status.toString(), status is LinkStatus.WaitingToRetry)
            assertEquals(DeviceStatus.Lost, rig.host.devices.value.trainer.status)

            world.fakeTrainer.powered = true
            rig.host.retryTrainer()
            world.advance(1_500)
            assertEquals(DeviceStatus.Connected, rig.host.devices.value.trainer.status)
            assertEquals(RideState.READY, rig.host.snapshots.value.state)
            rig.host.close()
        }

    @Test
    fun retryOnAStrapThatIsAwayLooksUpTheStrapThatIsPairedNow() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val rig = rig(world)
            world.advance(1_000)
            // Replace the strap's device by forgetting it: a Retry afterwards must not touch anything.
            world.devices.forget(DeviceRole.Strap)
            world.advance(1_000)
            val opened = world.ble.transports.size
            rig.host.retryHeartRate()
            world.advance(5_000)
            assertEquals(opened, world.ble.transports.size)
            world.assertNoViolations()
            rig.host.close()
        }

    @Test
    fun bluetoothOffMakesTheRideWaitAndItsRowsSayWhyAndItRecoversWhenItIsOn() =
        runTest {
            val world = BleWorld(this)
            world.ble.setAvailability(BleAvailability.BLUETOOTH_OFF)
            world.launchPaired()
            val rig = rig(world)
            world.advance(2_000)
            assertEquals(RideState.IDLE, rig.host.snapshots.value.state)
            assertEquals(RadioIssue.BluetoothOff, rig.host.devices.value.radio)
            assertEquals(DeviceStatus.Blocked, rig.host.devices.value.trainer.status)

            world.ble.setAvailability(BleAvailability.READY)
            world.advance(3_000)
            assertEquals(RideState.READY, rig.host.snapshots.value.state)
            assertNull(rig.host.devices.value.radio)
            world.assertNoViolations()
            rig.host.close()
        }

    @Test
    fun theRideRecordNamesTheDevicesWithoutAnAddress() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired()
            val rig = rig(world)
            world.advance(1_000)
            val trainerName =
                world.devices.trainer.value!!
                    .device.name
            val strapName =
                world.devices.strap.value!!
                    .device.name
            assertEquals("D500", trainerName)
            assertEquals("HR7", strapName)
            assertFalse(trainerName.contains(TRAINER_ID.value))
            rig.host.close()
        }
}
