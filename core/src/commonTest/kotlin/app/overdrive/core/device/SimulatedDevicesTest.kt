package app.overdrive.core.device

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Collects everything [this] flow emits from now on into a list. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> TestScope.collectAll(flow: Flow<T>): List<T> {
    val seen = mutableListOf<T>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
    return seen
}

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedDevicesTest {
    private val flat = SimulationParameters(gradePercent = 0.0, crr = 0.004, windResistance = 0.2144)

    @Test
    fun `a trainer starts disconnected and refuses every command`() =
        runTest {
            val trainer = SimulatedTrainerDevice()
            assertEquals(ConnectionState.DISCONNECTED, trainer.connection.value)
            assertFailsWith<IllegalStateException> { trainer.requestControl() }
            assertFailsWith<IllegalStateException> { trainer.setTargetPower(100) }
            assertEquals(2, trainer.refusedCommands)
            assertTrue(trainer.received.isEmpty())
        }

    @Test
    fun `a trainer wants control before a target, like FTMS`() =
        runTest {
            val trainer = SimulatedTrainerDevice(clock = { testScheduler.currentTime })
            trainer.connect()
            assertFailsWith<IllegalStateException> { trainer.setTargetPower(150) }
            assertFailsWith<IllegalStateException> { trainer.setSimulation(flat) }
            assertFalse(trainer.hasControl)

            trainer.requestControl()
            trainer.setTargetPower(150)
            assertEquals(150, trainer.targetPowerWatts)
            assertNull(trainer.simulation)

            trainer.setSimulation(flat)
            assertEquals(flat, trainer.simulation)
            assertNull(trainer.targetPowerWatts)

            assertEquals(
                listOf(
                    TrainerCommand.RequestControl,
                    TrainerCommand.TargetPower(150),
                    TrainerCommand.Simulation(flat),
                ),
                trainer.received.map { it.command },
            )
        }

    @Test
    fun `received commands are stamped with the clock`() =
        runTest {
            val trainer = SimulatedTrainerDevice(clock = { testScheduler.currentTime })
            trainer.connect()
            trainer.requestControl()
            testScheduler.advanceTimeBy(2500)
            trainer.setTargetPower(200)
            assertEquals(listOf(0L, 2500L), trainer.received.map { it.timeMillis })
        }

    @Test
    fun `a disconnect drops control and targets, and a reconnect starts over`() =
        runTest {
            val trainer = SimulatedTrainerDevice()
            trainer.connect()
            trainer.requestControl()
            trainer.setTargetPower(200)

            trainer.disconnect()
            assertEquals(ConnectionState.DISCONNECTED, trainer.connection.value)
            assertFalse(trainer.hasControl)
            assertNull(trainer.targetPowerWatts)
            assertFailsWith<IllegalStateException> { trainer.setTargetPower(210) }

            trainer.connect()
            assertFailsWith<IllegalStateException> { trainer.setTargetPower(210) }
            trainer.requestControl()
            trainer.setTargetPower(210)
            assertEquals(210, trainer.targetPowerWatts)
        }

    @Test
    fun `a powered-off trainer ignores connect until it is powered on`() =
        runTest {
            val trainer = SimulatedTrainerDevice()
            trainer.connect()
            trainer.powerOff()
            assertEquals(ConnectionState.DISCONNECTED, trainer.connection.value)
            trainer.connect()
            assertEquals(ConnectionState.DISCONNECTED, trainer.connection.value)
            trainer.powerOn()
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
        }

    @Test
    fun `connecting is a state of its own`() =
        runTest {
            val trainer = SimulatedTrainerDevice()
            val states = collectAll(trainer.connection)
            trainer.startConnecting()
            trainer.connect()
            assertEquals(
                listOf(ConnectionState.DISCONNECTED, ConnectionState.CONNECTING, ConnectionState.CONNECTED),
                states,
            )
        }

    @Test
    fun `readings reach collectors only while connected`() =
        runTest {
            val trainer = SimulatedTrainerDevice()
            val seen = collectAll(trainer.data)
            trainer.emit(power = 100, cadence = 80)
            assertTrue(seen.isEmpty(), "dropped while disconnected")

            trainer.connect()
            trainer.emit(power = 210, cadence = 91, speedKmh = 30.5)
            trainer.emit(power = null, cadence = 90)
            assertEquals(
                listOf(TrainerReading(210, 91, 30.5), TrainerReading(null, 90, null)),
                seen,
            )
        }

    @Test
    fun `commands can be made to fail a few times`() =
        runTest {
            val trainer = SimulatedTrainerDevice()
            trainer.connect()
            trainer.failNextCommands(2)
            assertFailsWith<IllegalStateException> { trainer.requestControl() }
            assertFailsWith<IllegalStateException> { trainer.requestControl() }
            trainer.requestControl()
            assertTrue(trainer.hasControl)
            assertEquals(2, trainer.refusedCommands)
            assertEquals(1, trainer.received.size)
        }

    @Test
    fun `a strap delivers heart rate and RR intervals while connected`() =
        runTest {
            val strap = SimulatedHeartRateDevice()
            val seen = collectAll(strap.data)
            strap.emit(60)
            strap.connect()
            strap.emit(131, listOf(455, 460))
            strap.emit(132)
            assertEquals(listOf(HeartRateReading(131, listOf(455, 460)), HeartRateReading(132)), seen)

            strap.powerOff()
            strap.connect()
            assertEquals(ConnectionState.DISCONNECTED, strap.connection.value)
            strap.powerOn()
            assertEquals(ConnectionState.CONNECTED, strap.connection.value)
        }

    @Test
    fun `readings and parameters are validated`() {
        assertFailsWith<IllegalArgumentException> { TrainerReading(power = -1) }
        assertFailsWith<IllegalArgumentException> { TrainerReading(cadence = -1) }
        assertFailsWith<IllegalArgumentException> { HeartRateReading(bpm = -1) }
        assertFailsWith<IllegalArgumentException> { HeartRateReading(bpm = 60, rrIntervalsMs = listOf(0)) }
        assertFailsWith<IllegalArgumentException> { SimulationParameters(201.0, 0.004, 0.2) }
        assertFailsWith<IllegalArgumentException> { SimulationParameters(0.0, 0.03, 0.2) }
        assertFailsWith<IllegalArgumentException> { SimulationParameters(0.0, 0.004, 3.0) }
        SimulationParameters(-15.0, 0.0, 0.0)
    }
}
