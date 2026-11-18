package app.overdrive.ride

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.ble.fake.FakeBle
import app.overdrive.core.ble.fake.FakeFtmsTrainer
import app.overdrive.core.ble.fake.FakeHeartRateStrap
import app.overdrive.core.ride.RideState
import app.overdrive.ride.devices.DeviceRole
import app.overdrive.ride.devices.FakeBleStack
import app.overdrive.ride.devices.RememberedDevice
import app.overdrive.ride.devices.STRAP_ID
import app.overdrive.ride.devices.TRAINER_ID
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.rides.RideFixtures
import app.overdrive.ride.settings.DeviceSource
import app.overdrive.uikit.DeviceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The container's Bluetooth wiring (ADR 0010) with real threads over a fake radio: the remembered trainer and strap connect
 * when the app starts without any tap, the host rides over them, no trainer means no ride (the simulation is a Developer
 * choice, never a fallback), and what is paired outlives the process: the next container finds it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppContainerBleTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = RideFixtures.directory()
    private val logs = RideFixtures.directory()
    private val containers = mutableListOf<AppContainer>()
    private val worldScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class World(
        scope: CoroutineScope,
    ) {
        val ble = FakeBle(scope, nanoClock = System::nanoTime)
        val trainer = FakeFtmsTrainer()
        val strap = FakeHeartRateStrap()
        val stack = FakeBleStack(ble)

        init {
            ble.add(TRAINER_ID, trainer)
            ble.add(STRAP_ID, strap)
        }
    }

    @After
    fun cleanUp() {
        containers.forEach { it.close() }
        worldScope.cancel()
        directory.deleteRecursively()
        logs.deleteRecursively()
    }

    private fun container(
        world: World,
        name: String,
    ): AppContainer =
        AppContainer(
            context,
            directory,
            settingsName = name,
            devicesName = "devices-of-the-test",
            bleLogDirectory = File(logs, name),
            bleStack = { _, _ -> world.stack },
        ).also { containers += it }

    private fun await(
        what: String,
        timeoutMs: Long = 20_000,
        condition: () -> Boolean,
    ) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    @Test
    fun withNothingPairedTheRideIsIdleAndTheScreensSayNoTrainer() {
        val world = World(worldScope)
        val container = container(world, "empty")
        await("the store is read") { container.pairedDevices.loaded.value }
        await("the ride is built") { container.host.ui.value.rideNumber >= 1 }
        container.host.select(RideChoice.Free)
        Thread.sleep(500)
        assertEquals(RideState.IDLE, container.host.snapshots.value.state)
        await(
            "the rows know nothing is paired",
        ) { container.host.devices.value.trainer.status == DeviceStatus.NotPaired }
        assertFalse(container.host.devices.value.simulated)
        assertTrue("no radio use without a paired device", world.ble.transports.isEmpty())
        assertEquals(DeviceSource.Bluetooth, container.settings.value.deviceSource)
    }

    @Test
    fun theSimulationIsAChoiceInTheDeveloperSettingsAndNeverAFallback() {
        val world = World(worldScope)
        val container = container(world, "simulated")
        await("the ride is built") { container.host.ui.value.rideNumber >= 1 }
        assertEquals(RideState.IDLE, container.host.snapshots.value.state)

        container.updateSettings { it.copy(deviceSource = DeviceSource.Simulated) }
        await("a simulated ride is ready") { container.host.snapshots.value.state == RideState.READY }
        await("the rows are the simulated ones") { container.host.devices.value.simulated }
        assertTrue(world.ble.transports.isEmpty())

        container.updateSettings { it.copy(deviceSource = DeviceSource.Bluetooth) }
        await("back to waiting for a trainer") { container.host.snapshots.value.state == RideState.IDLE }
    }

    @Test
    fun aPairedTrainerAndStrapConnectAtLaunchAndTheRideIsReadyWithoutATap() {
        val world = World(worldScope)
        // The first run: the rider pairs both on the Devices screen. Then the process ends.
        val first = container(world, "first")
        await("the store is read") { first.pairedDevices.loaded.value }
        runBlocking {
            first.pairedDevices.pair(DeviceRole.Trainer, RememberedDevice(TRAINER_ID, "D500"))
            first.pairedDevices.pair(DeviceRole.Strap, RememberedDevice(STRAP_ID, "HR7"))
        }
        await("connected") { first.host.devices.value.trainer.status == DeviceStatus.Connected }
        first.close()
        containers.remove(first)
        await("the radio saw the link close") { world.ble.transports.all { it.isClosed } }
        val before = world.ble.transports.size

        // The next launch: nothing is tapped.
        val second = container(world, "second")
        await("the trainer is connected") { second.host.devices.value.trainer.status == DeviceStatus.Connected }
        await("the strap is connected") { second.host.devices.value.heartRate.status == DeviceStatus.Connected }
        await("the ride is ready") { second.host.snapshots.value.state == RideState.READY }
        assertEquals("D500", second.host.devices.value.trainer.subtitle)
        assertTrue("new links were opened", world.ble.transports.size > before)
        assertTrue(world.trainer.hasControl)
        assertEquals(emptyList<String>(), world.ble.violations)
    }

    @Test
    fun scanningAndPairingThroughTheContainerStartsTheRide() {
        val world = World(worldScope)
        val container = container(world, "pairing")
        await("the store is read") { container.pairedDevices.loaded.value }

        container.pairing.scan()
        await("both are found") {
            container.pairing.state.value.trainers.size == 1 && container.pairing.state.value.straps.size == 1
        }
        container.pairing.pair(
            container.pairing.state.value.trainers
                .single(),
        )
        container.pairing.pair(
            container.pairing.state.value.straps
                .single(),
        )
        container.pairing.stop()

        await("the ride is ready over the new devices") { container.host.snapshots.value.state == RideState.READY }
        assertEquals(DeviceStatus.Connected, container.host.devices.value.trainer.status)
        assertEquals(DeviceStatus.Connected, container.host.devices.value.heartRate.status)
        assertNotNull(container.pairedDevices.trainer.value)
        assertEquals(emptyList<String>(), world.ble.violations)
    }

    @Test
    fun closingTheContainerClosesTheStackAndTheLinksButForgetsNothing() {
        val world = World(worldScope)
        val container = container(world, "closing")
        await("the store is read") { container.pairedDevices.loaded.value }
        runBlocking { container.pairedDevices.pair(DeviceRole.Trainer, RememberedDevice(TRAINER_ID, "D500")) }
        await("connected") { container.host.devices.value.trainer.status == DeviceStatus.Connected }
        container.close()
        containers.remove(container)
        assertTrue(world.stack.closed)
        await("the link is closed") { world.ble.transports.all { it.isClosed } }
        assertNull(container.pairedDevices.trainer.value)
    }

    @Test
    fun aSessionOfThePairedTrainerIsRecordedRedactedAndTheDeveloperSwitchTurnsItOff() {
        val world = World(worldScope)
        val container = container(world, "logs")
        assertTrue("on by default in the early builds", container.settings.value.recordBleSessions)
        assertEquals(0, container.bleLogs.summary().files)
        await("the store is read") { container.pairedDevices.loaded.value }

        runBlocking { container.pairedDevices.pair(DeviceRole.Trainer, RememberedDevice(TRAINER_ID, "D500")) }
        await("connected") { container.host.devices.value.trainer.status == DeviceStatus.Connected }
        await("a log file is written") { container.bleLogs.files().any { it.length() > 0 } }
        val file = container.bleLogs.files().single()
        assertTrue(file.name, Regex("ble-\\d{8}T\\d{6}Z-trainer\\.txt").matches(file.name))
        assertEquals(File(logs, "logs"), file.parentFile)
        val text = file.readText()
        assertTrue(text, text.startsWith("# overdrive-ble-session 1"))
        assertFalse("no device id in the file", text.contains(TRAINER_ID.value))

        // Off: the next connection of the strap writes nothing.
        container.updateSettings { it.copy(recordBleSessions = false) }
        await("the setting is read") { !container.settings.value.recordBleSessions }
        runBlocking { container.pairedDevices.pair(DeviceRole.Strap, RememberedDevice(STRAP_ID, "HR7")) }
        await("the strap is connected") { container.host.devices.value.heartRate.status == DeviceStatus.Connected }
        Thread.sleep(300)
        assertTrue(container.bleLogs.files().none { it.name.endsWith("-strap.txt") })
    }
}
