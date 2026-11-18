package app.overdrive.core.device

import app.overdrive.core.ride.SignalDropout
import app.overdrive.core.ride.SimulatedSignal
import app.overdrive.core.ride.SimulationScenario
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedBikeTest {
    private val quiet = SimulationScenario(noise = 0.0)

    private fun TestScope.bike(
        scenario: SimulationScenario = quiet,
        events: List<BikeEvent> = emptyList(),
        seed: Long = 1L,
        ftp: Int = 200,
    ) = SimulatedBike(backgroundScope, ftp = ftp, seed = seed, scenario = scenario, events = events)

    private fun TestScope.seconds(n: Int) {
        advanceTimeBy(n * 1000L)
        runCurrent()
    }

    private val flat = SimulationParameters(0.0, 0.004, 0.2144)

    @Test
    fun `nothing happens until it is started, then it connects and ticks once a second`() =
        runTest {
            val bike = bike()
            val readings = collectAll(bike.trainer.data)
            val straps = collectAll(bike.heartRate.data)
            seconds(5)
            assertTrue(readings.isEmpty())
            assertEquals(ConnectionState.DISCONNECTED, bike.trainer.connection.value)

            bike.start()
            runCurrent()
            assertEquals(ConnectionState.CONNECTED, bike.trainer.connection.value)
            assertEquals(ConnectionState.CONNECTED, bike.heartRate.connection.value)
            seconds(3)
            assertEquals(3, readings.size)
            assertEquals(3, straps.size)
        }

    @Test
    fun `in ERG the rider settles on the target power`() =
        runTest {
            val bike = bike()
            val readings = collectAll(bike.trainer.data)
            bike.start()
            bike.trainer.requestControl()
            bike.trainer.setTargetPower(180)
            seconds(30)
            assertEquals(180, readings.last().power)
            assertEquals(90, readings.last().cadence)
            assertTrue(readings.first().power!! in 1..179, "power approaches the target with a lag")
        }

    @Test
    fun `in simulation the rider floats at 55 percent of FTP and works harder on a climb`() =
        runTest {
            val bike = bike(ftp = 200)
            val readings = collectAll(bike.trainer.data)
            bike.start()
            bike.trainer.requestControl()
            bike.trainer.setSimulation(flat)
            seconds(40)
            assertEquals(110, readings.last().power)

            bike.trainer.setSimulation(flat.copy(gradePercent = 5.0)) // five gears up: 5 * 4% more
            seconds(40)
            assertEquals(132, readings.last().power)
        }

    @Test
    fun `the trainer's own speed follows cadence, not power`() =
        runTest {
            val bike = bike()
            val readings = collectAll(bike.trainer.data)
            bike.start()
            bike.trainer.requestControl()
            bike.trainer.setTargetPower(100)
            seconds(30)
            val low = readings.last()
            bike.trainer.setTargetPower(300)
            seconds(30)
            val high = readings.last()
            assertTrue(high.power!! > low.power!! + 100)
            assertEquals(low.speedKmh, high.speedKmh)
        }

    @Test
    fun `the heart rate follows power and RR intervals add up to about a second`() =
        runTest {
            val bike = bike()
            val straps = collectAll(bike.heartRate.data)
            bike.start()
            bike.trainer.requestControl()
            bike.trainer.setTargetPower(200) // FTP: heart rate heads for 170
            seconds(150)
            assertEquals(170, straps.last().bpm)
            assertTrue(straps.first().bpm < 100)
            for (reading in straps.takeLast(60)) {
                assertTrue(reading.rrIntervalsMs.isNotEmpty())
                assertTrue(reading.rrIntervalsMs.sum() in 800..1250, "${reading.rrIntervalsMs}")
            }
        }

    @Test
    fun `a strap without RR intervals sends none`() =
        runTest {
            val bike = SimulatedBike(backgroundScope, scenario = quiet, heartRateSendsRr = false)
            val straps = collectAll(bike.heartRate.data)
            bike.start()
            seconds(5)
            assertTrue(straps.all { it.rrIntervalsMs.isEmpty() })
        }

    @Test
    fun `the same seed gives the same readings and another seed gives other ones`() {
        fun run(seed: Long): Pair<List<TrainerReading>, List<HeartRateReading>> {
            var result: Pair<List<TrainerReading>, List<HeartRateReading>>? = null
            runTest {
                val bike = bike(scenario = SimulationScenario(), seed = seed)
                val readings = collectAll(bike.trainer.data)
                val straps = collectAll(bike.heartRate.data)
                bike.start()
                bike.trainer.requestControl()
                bike.trainer.setTargetPower(200)
                seconds(120)
                result = readings.toList() to straps.toList()
            }
            return checkNotNull(result)
        }
        assertEquals(run(7), run(7))
        assertNotEquals(run(7), run(8))
    }

    @Test
    fun `a trainer disconnect drops the link, the commands and the readings, and it reconnects by itself`() =
        runTest {
            val bike =
                bike(events = listOf(DeviceDisconnect(SimulatedDevice.TRAINER, startSecond = 5, durationSeconds = 3)))
            val states = collectAll(bike.trainer.connection)
            val readings = collectAll(bike.trainer.data)
            bike.start()
            bike.trainer.requestControl()
            bike.trainer.setTargetPower(150)
            seconds(4)
            assertEquals(4, readings.size)

            seconds(1) // second 5: gone
            assertEquals(ConnectionState.DISCONNECTED, bike.trainer.connection.value)
            assertNull(bike.trainer.targetPowerWatts)
            seconds(2) // seconds 6 and 7
            assertEquals(4, readings.size)

            seconds(1) // second 8: back
            assertEquals(ConnectionState.CONNECTED, bike.trainer.connection.value)
            assertEquals(5, readings.size)
            assertEquals(
                listOf(
                    ConnectionState.DISCONNECTED, // before start
                    ConnectionState.CONNECTED,
                    ConnectionState.DISCONNECTED,
                    ConnectionState.CONNECTED,
                ),
                states,
            )
        }

    @Test
    fun `a disconnect without a duration is a power-off for good`() =
        runTest {
            val bike = bike(events = listOf(DeviceDisconnect(SimulatedDevice.TRAINER, startSecond = 2)))
            val readings = collectAll(bike.trainer.data)
            bike.start()
            seconds(100)
            assertEquals(1, readings.size)
            assertEquals(ConnectionState.DISCONNECTED, bike.trainer.connection.value)
            bike.trainer.connect()
            assertEquals(ConnectionState.DISCONNECTED, bike.trainer.connection.value, "it is off")
        }

    @Test
    fun `a silent device stays connected but sends nothing`() =
        runTest {
            val bike =
                bike(events = listOf(DeviceSilence(SimulatedDevice.HEART_RATE, startSecond = 3, durationSeconds = 4)))
            val straps = collectAll(bike.heartRate.data)
            bike.start()
            seconds(10)
            assertEquals(6, straps.size)
            assertEquals(ConnectionState.CONNECTED, bike.heartRate.connection.value)
        }

    @Test
    fun `a signal dropout blanks one reading and a strap dropout reads 0 bpm`() =
        runTest {
            val scenario =
                quiet.copy(
                    dropouts =
                        listOf(
                            SignalDropout(SimulatedSignal.POWER, startSecond = 3, durationSeconds = 2),
                            SignalDropout(SimulatedSignal.HEART_RATE, startSecond = 3, durationSeconds = 2),
                        ),
                )
            val bike = bike(scenario = scenario)
            val readings = collectAll(bike.trainer.data)
            val straps = collectAll(bike.heartRate.data)
            bike.start()
            seconds(6)
            assertEquals(listOf(false, false, true, true, false, false), readings.map { it.power == null })
            assertTrue(readings.all { it.cadence != null })
            assertEquals(listOf(false, false, true, true, false, false), straps.map { it.bpm == 0 })
            assertTrue(straps.filter { it.bpm == 0 }.all { it.rrIntervalsMs.isEmpty() })
        }

    @Test
    fun `a rider who stops pedalling reads 0 rpm and 0 W, by event or by hand`() =
        runTest {
            val bike = bike(events = listOf(RiderStop(startSecond = 20, durationSeconds = 10)))
            val readings = collectAll(bike.trainer.data)
            bike.start()
            bike.trainer.requestControl()
            bike.trainer.setTargetPower(150)
            seconds(19)
            assertTrue(readings.last().cadence!! > 0)
            seconds(1)
            assertEquals(0, readings.last().cadence)
            seconds(10)
            assertEquals(0, readings[28].cadence)
            assertTrue(readings.last().cadence!! > 0, "carries on after the stop")

            bike.pedalling = false
            seconds(1)
            assertEquals(0, readings.last().cadence)
        }

    @Test
    fun `without a started connection the devices wait to be connected`() =
        runTest {
            val bike = bike()
            val readings = collectAll(bike.trainer.data)
            bike.start(connect = false)
            seconds(3)
            assertTrue(readings.isEmpty())
            bike.trainer.connect()
            seconds(2)
            assertEquals(2, readings.size)
        }
}
