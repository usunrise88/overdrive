package app.overdrive.core.ble.adapter

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.FtmsUuids
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.ble.TRAINER_ID
import app.overdrive.core.ble.adapter.TrainerCommandException.Kind
import app.overdrive.core.ble.codec.DecodeErrorKind
import app.overdrive.core.ble.codec.FitnessMachineStatus
import app.overdrive.core.ble.codec.IndoorBikeFlagMap
import app.overdrive.core.ble.codec.RequiredFeature
import app.overdrive.core.ble.fake.FakeFtmsTrainer
import app.overdrive.core.ble.fake.FakeFtmsTrainerOptions
import app.overdrive.core.ble.fake.FakeTrainerCommand
import app.overdrive.core.ble.link.LinkFailureKind
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.core.device.ConnectionState
import app.overdrive.core.device.SimulationParameters
import app.overdrive.core.device.TrainerReading
import app.overdrive.core.device.collectAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The FTMS trainer adapter over the fake peripheral: set-up in the order of docs/protocols/ftms-hr.md §4.1, the Control
 * Point procedure, the Response Code handling of §4.4, and each bench-flippable choice of `FtmsOptions`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FtmsTrainerDeviceTest {
    private val flat = SimulationParameters(gradePercent = 0.0, crr = 0.004, windResistance = 0.2144)

    /** The kind of the [TrainerCommandException] [block] throws; fails if it throws nothing else. */
    private suspend fun failureKind(block: suspend () -> Unit): Kind {
        try {
            block()
        } catch (e: TrainerCommandException) {
            return e.kind
        }
        throw AssertionError("expected a TrainerCommandException")
    }

    private fun BleRig.runTrainer(
        options: FtmsOptions = FtmsOptions(),
        factory: GattTransportFactory = ble,
    ) = trainer(options, factory = factory).also {
        it.start()
        advance(1_000)
    }

    private val FakeFtmsTrainer.opcodes: List<String>
        get() =
            commands.map {
                when (it) {
                    is FakeTrainerCommand.RequestControl -> "RC"
                    is FakeTrainerCommand.Reset -> "RESET"
                    is FakeTrainerCommand.StartOrResume -> "START"
                    is FakeTrainerCommand.StopOrPause -> "STOP"
                    is FakeTrainerCommand.TargetPower -> "TARGET ${it.watts}"
                    is FakeTrainerCommand.Simulation -> "SIM ${it.gradePercent}"
                    is FakeTrainerCommand.Unknown -> "?"
                }
            }

    // region set-up (§4.1)

    @Test
    fun `set-up discovers, reads Feature, enables the three CCCDs one at a time, then takes control`() =
        runTest {
            val rig = BleRig(this)
            val recorded = rig.recording(model = "D500")
            val trainer = rig.runTrainer(factory = recorded.factory)
            rig.settle()

            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            assertEquals(LinkStatus.Connected, trainer.status.value)
            assertEquals(
                listOf(
                    "S - connecting",
                    "S - connected",
                    "D 2ACC 1826/R",
                    "D 2AD2 1826/N",
                    "D 2AD9 1826/WI",
                    "D 2ADA 1826/N",
                    "S - mtu=64",
                    "R 2ACC 0654000008200000",
                    "C 2AD9 0200",
                    "C 2AD2 0100",
                    "C 2ADA 0100",
                    "W 2AD9 00",
                    "I 2AD9 800001",
                    "W 2AD9 07",
                    "N 2ADA 04",
                    "I 2AD9 800701",
                ),
                recorded.compact(),
            )
            rig.assertNoViolations()
        }

    @Test
    fun `the CCCD writes are at least one operation apart, never overlapping`() =
        runTest {
            val rig = BleRig(this)
            val recorded = rig.recording()
            rig.runTrainer(factory = recorded.factory)
            rig.settle()
            val cccd = recorded.events().filter { it.direction.code == 'C' }
            assertEquals(3, cccd.size)
            assertTrue(cccd.zipWithNext().all { (a, b) -> b.timeNanos - a.timeNanos >= 20_000_000 }, "$cccd")
            rig.assertNoViolations()
        }

    @Test
    fun `it is connected only once set-up has finished, and the engine's request control is then free`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.trainer()
            val states = collectAll(trainer.connection)
            trainer.start()
            rig.advance(300)
            assertEquals(ConnectionState.CONNECTING, trainer.connection.value, "control is not taken yet")
            rig.advance(500)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            assertTrue(rig.fakeTrainer.hasControl)
            assertEquals(listOf("RC", "START"), rig.fakeTrainer.opcodes)

            trainer.requestControl()
            assertEquals(listOf("RC", "START"), rig.fakeTrainer.opcodes, "control is held: no second request")
            assertEquals(
                listOf(ConnectionState.DISCONNECTED, ConnectionState.CONNECTING, ConnectionState.CONNECTED),
                states,
            )
        }

    @Test
    fun `Start or Resume can be turned off, the one-line flip for bench question FT 7`() =
        runTest {
            val rig = BleRig(this)
            rig.runTrainer(FtmsOptions(startAfterRequestControl = false))
            assertEquals(listOf("RC"), rig.fakeTrainer.opcodes)
        }

    @Test
    fun `the Feature word is read and published, and missing features are reported but do not stop a command`() =
        runTest {
            val noPowerTarget = byteArrayOf(0x06, 0x54, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00)
            val rig = BleRig(this, trainerOptions = FakeFtmsTrainerOptions(featureBytes = noPowerTarget))
            val trainer = rig.trainer()
            val events = collectAll(trainer.events)
            trainer.start()
            rig.advance(1_000)
            val features = assertNotNull(trainer.features.value)
            assertTrue(features.powerMeasurementSupported && !features.powerTargetSettingSupported)
            assertTrue(TrainerEvent.FeaturesMissing(listOf(RequiredFeature.POWER_TARGET_SETTING)) in events)

            trainer.setTargetPower(150) // enforceFeatureBits is off: the trainer's Response Code decides
            assertEquals("TARGET 150", rig.fakeTrainer.opcodes.last())
        }

    @Test
    fun `with enforceFeatureBits a command the trainer lacks fails without any traffic`() =
        runTest {
            val noPowerTarget = byteArrayOf(0x06, 0x54, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00)
            val rig = BleRig(this, trainerOptions = FakeFtmsTrainerOptions(featureBytes = noPowerTarget))
            val trainer = rig.runTrainer(FtmsOptions(enforceFeatureBits = true))
            val before = rig.fakeTrainer.commands.size
            val error = assertFailsWith<TrainerCommandException> { trainer.setTargetPower(150) }
            assertEquals(Kind.FEATURE_MISSING, error.kind)
            assertEquals(before, rig.fakeTrainer.commands.size)
            trainer.setSimulation(flat) // simulation is supported
            assertEquals(before + 1, rig.fakeTrainer.commands.size)
        }

    @Test
    fun `a peripheral without the Fitness Machine Service is not a trainer and never connects`() =
        runTest {
            val rig = BleRig(this)
            // The strap registered under the trainer's id: it has no Indoor Bike Data.
            rig.ble.add(TRAINER_ID, rig.fakeStrap)
            val trainer = rig.runTrainer()
            val waiting = assertIs<LinkStatus.WaitingToRetry>(trainer.status.value)
            assertEquals(LinkFailureKind.SERVICE_MISSING, waiting.failure.kind)
            assertEquals(ConnectionState.DISCONNECTED, trainer.connection.value)
            assertTrue(rig.ble.transports.first().isClosed, "the failed attempt's transport is closed")
        }

    @Test
    fun `a failed Indoor Bike Data subscription closes the transport and the retry sets up again`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeTrainer.faults.failNextCccdWrites(1, GattStatus.GATT_ERROR, FtmsUuids.INDOOR_BIKE_DATA)
            val trainer = rig.runTrainer()
            val waiting = assertIs<LinkStatus.WaitingToRetry>(trainer.status.value)
            assertEquals(LinkFailureKind.SETUP_FAILED, waiting.failure.kind)
            assertEquals(GattStatus.GATT_ERROR, waiting.failure.status)
            assertTrue(rig.ble.transports.single().isClosed)

            rig.advance(2_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            assertEquals(2, rig.ble.transports.size)
            rig.assertNoViolations()
        }

    @Test
    fun `a failed Control Point subscription is just as fatal`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeTrainer.faults.failNextCccdWrites(1, GattStatus.GATT_ERROR, FtmsUuids.CONTROL_POINT)
            val trainer = rig.runTrainer()
            assertIs<LinkStatus.WaitingToRetry>(trainer.status.value)
            rig.advance(2_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
        }

    @Test
    fun `a failed Status subscription is tolerated and reported`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeTrainer.faults.failNextCccdWrites(1, GattStatus.GATT_ERROR, FtmsUuids.STATUS)
            val trainer = rig.trainer()
            val events = collectAll(trainer.events)
            trainer.start()
            rig.advance(1_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            assertTrue(events.any { it is TrainerEvent.OptionalStepFailed && it.step.contains("Status") })
        }

    @Test
    fun `a failed discovery is a failed attempt`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeTrainer.faults.failNextDiscoveries(1)
            val trainer = rig.runTrainer()
            val waiting = assertIs<LinkStatus.WaitingToRetry>(trainer.status.value)
            assertEquals(LinkFailureKind.SETUP_FAILED, waiting.failure.kind)
            rig.advance(2_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
        }

    // endregion

    // region data

    @Test
    fun `Indoor Bike Data becomes readings with power, cadence rounded to whole rpm, and the trainer's own speed`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            val readings = collectAll(trainer.data)
            rig.advance(2_000)
            assertEquals(2, readings.size)
            assertEquals(TrainerReading(power = 110, cadence = 90, speedKmh = 27.0), readings.last())
        }

    @Test
    fun `a half rpm rounds up and a negative power is a zero, never an exception`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            val readings = collectAll(trainer.data)
            val events = collectAll(trainer.events)
            val link = assertNotNull(rig.fakeTrainer.connection)
            // flags 0x0004: speed, cadence 181 half-rpm = 90.5 rpm.
            link.push(FtmsUuids.INDOOR_BIKE_DATA, byteArrayOf(0x04, 0x00, 0xC4.toByte(), 0x09, 0xB5.toByte(), 0x00))
            // IBD6: More Data, power -20 W.
            link.push(FtmsUuids.INDOOR_BIKE_DATA, byteArrayOf(0x41, 0x00, 0xEC.toByte(), 0xFF.toByte()))
            rig.settle()
            assertEquals(TrainerReading(power = null, cadence = 91, speedKmh = 25.0), readings[0])
            assertEquals(TrainerReading(power = 0, cadence = null, speedKmh = null), readings[1])
            assertTrue(TrainerEvent.MoreDataSeen in events)
        }

    @Test
    fun `a malformed notification is dropped and reported, and the stream goes on`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            val readings = collectAll(trainer.data)
            val events = collectAll(trainer.events)
            assertNotNull(rig.fakeTrainer.connection).push(FtmsUuids.INDOOR_BIKE_DATA, byteArrayOf(0x44))
            rig.advance(1_100)
            assertTrue(readings.size == 1, "only the real notification of the second: $readings")
            val malformed = events.filterIsInstance<TrainerEvent.MalformedNotification>().single()
            assertEquals(FtmsUuids.INDOOR_BIKE_DATA, malformed.characteristic)
            assertEquals(DecodeErrorKind.TRUNCATED, malformed.error.kind)
        }

    @Test
    fun `the disputed flag bits flip with one option, as a bench result for FT 1 would`() =
        runTest {
            // A trainer that follows the SIG XML labels: bit 1 is cadence.
            val rig = BleRig(this, trainerOptions = FakeFtmsTrainerOptions(flagMap = IndoorBikeFlagMap.SIG_XML_LABELS))
            val decoders = rig.runTrainer()
            val seen = collectAll(decoders.data)
            rig.advance(1_000)
            assertNull(seen.last().cadence, "with the default map the cadence is lost")
            decoders.close()
            rig.settle()

            val rig2 = BleRig(this, trainerOptions = FakeFtmsTrainerOptions(flagMap = IndoorBikeFlagMap.SIG_XML_LABELS))
            val flipped = rig2.runTrainer(FtmsOptions(flagMap = IndoorBikeFlagMap.SIG_XML_LABELS))
            val seen2 = collectAll(flipped.data)
            rig2.advance(1_000)
            assertEquals(90, seen2.last().cadence)
        }

    // endregion

    // region commands (§4.2, §4.3)

    @Test
    fun `a target power reaches the trainer as Set Target Power and the rider's power follows it`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            val readings = collectAll(trainer.data)
            trainer.setTargetPower(220)
            assertEquals(220, rig.fakeTrainer.targetPowerWatts)
            rig.advance(2_000)
            assertEquals(220, readings.last().power)
            trainer.setTargetPower(0)
            rig.advance(1_000)
            assertEquals(0, readings.last().power)
            assertEquals(listOf("RC", "START", "TARGET 220", "TARGET 0"), rig.fakeTrainer.opcodes)
        }

    @Test
    fun `simulation parameters reach the trainer as the raw values of the notes`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            trainer.setSimulation(SimulationParameters(gradePercent = -2.0, crr = 0.004, windResistance = 0.51))
            val sim = assertNotNull(rig.fakeTrainer.simulation)
            assertEquals(-200, sim.gradeRaw)
            assertEquals(40, sim.crrRaw)
            assertEquals(51, sim.windResistanceRaw)
            assertEquals(0, sim.windRaw)
            assertNull(rig.fakeTrainer.targetPowerWatts, "simulation replaces the ERG target")
        }

    @Test
    fun `a command before the first connection is a typed failure the engine can retry`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.trainer()
            assertEquals(Kind.NOT_CONNECTED, failureKind { trainer.requestControl() })
            assertEquals(Kind.NOT_CONNECTED, failureKind { trainer.setTargetPower(100) })
            assertEquals(Kind.NOT_CONNECTED, failureKind { trainer.setSimulation(flat) })
        }

    @Test
    fun `a target above the encodable range is refused at the door, not sent`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            assertFailsWith<IllegalArgumentException> { trainer.setTargetPower(40_000) }
            assertFailsWith<IllegalArgumentException> { trainer.setTargetPower(-1) }
        }

    // endregion

    // region response codes (§4.4)

    @Test
    fun `operation failed is retried twice and then the command succeeds`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            rig.fakeTrainer.faults.controlPointResult(result = 0x04, count = 2, opcode = 0x05)
            val call = async { trainer.setTargetPower(180) }
            rig.advance(3_000)
            call.await()
            assertEquals(listOf("RC", "START", "TARGET 180", "TARGET 180", "TARGET 180"), rig.fakeTrainer.opcodes)
            assertEquals(180, rig.fakeTrainer.targetPowerWatts)
        }

    @Test
    fun `operation failed three times in a row gives up with a rejection`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            rig.fakeTrainer.faults.controlPointResult(result = 0x04, count = 3, opcode = 0x05)
            val call = async { runCatching { trainer.setTargetPower(180) } }
            rig.advance(3_000)
            val error = assertIs<TrainerCommandException>(call.await().exceptionOrNull())
            assertEquals(Kind.REJECTED, error.kind)
            assertEquals(0x04, error.resultCode)
            assertEquals(3, rig.fakeTrainer.opcodes.count { it == "TARGET 180" })
        }

    @Test
    fun `an invalid parameter is not retried`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            rig.fakeTrainer.faults.controlPointResult(result = 0x03, count = 1, opcode = 0x05)
            val error = assertFailsWith<TrainerCommandException> { trainer.setTargetPower(180) }
            assertEquals(Kind.REJECTED, error.kind)
            assertEquals(1, rig.fakeTrainer.opcodes.count { it == "TARGET 180" })
        }

    @Test
    fun `an op code the trainer does not support fails, and later tries fail at once without traffic`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            val events = collectAll(trainer.events)
            rig.fakeTrainer.faults.controlPointResult(result = 0x02, count = 1, opcode = 0x05)
            assertEquals(Kind.UNSUPPORTED, failureKind { trainer.setTargetPower(180) })
            val sent = rig.fakeTrainer.commands.size
            assertEquals(Kind.UNSUPPORTED, failureKind { trainer.setTargetPower(190) })
            assertEquals(sent, rig.fakeTrainer.commands.size, "no traffic for a command known to be unsupported")
            trainer.setSimulation(flat) // other commands are unaffected
            assertTrue(TrainerEvent.CommandRejected(0x05, 0x02) in events)
        }

    @Test
    fun `control not permitted takes control again once and re-sends`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            rig.fakeTrainer.faults.controlPointResult(result = 0x05, count = 1, opcode = 0x05)
            val call = async { trainer.setTargetPower(210) }
            rig.advance(1_000)
            call.await()
            assertEquals(listOf("RC", "START", "TARGET 210", "RC", "START", "TARGET 210"), rig.fakeTrainer.opcodes)
            assertEquals(210, rig.fakeTrainer.targetPowerWatts)
        }

    @Test
    fun `control not permitted twice means not under control`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            rig.fakeTrainer.faults.controlPointResult(result = 0x05, count = 2, opcode = 0x05)
            val call = async { runCatching { trainer.setTargetPower(210) } }
            rig.advance(1_000)
            assertEquals(Kind.NOT_UNDER_CONTROL, assertIs<TrainerCommandException>(call.await().exceptionOrNull()).kind)
        }

    @Test
    fun `a silent trainer times out after three seconds and the retry starts over with Request Control`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            rig.fakeTrainer.faults.noControlPointResponse(count = 1, opcode = 0x05)
            val call = async { runCatching { trainer.setTargetPower(200) } }
            rig.advance(2_900)
            assertTrue(call.isActive, "still waiting for the indication")
            rig.advance(300)
            val error = assertIs<TrainerCommandException>(call.await().exceptionOrNull())
            assertEquals(Kind.TIMEOUT, error.kind)

            // What the engine does a second later: request control, then the command.
            trainer.requestControl()
            trainer.setTargetPower(200)
            assertEquals(listOf("RC", "START", "TARGET 200", "RC", "START", "TARGET 200"), rig.fakeTrainer.opcodes)
            assertEquals(200, rig.fakeTrainer.targetPowerWatts)
        }

    @Test
    fun `the timeout is an option, the bench value for FT 6`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer(FtmsOptions(controlPointTimeout = 500.milliseconds))
            rig.fakeTrainer.faults.noControlPointResponse(count = 1, opcode = 0x05)
            val call = async { runCatching { trainer.setTargetPower(200) } }
            rig.advance(700)
            assertEquals(Kind.TIMEOUT, assertIs<TrainerCommandException>(call.await().exceptionOrNull()).kind)
        }

    @Test
    fun `a failed Control Point write is a write failure and the next command starts over`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            rig.fakeTrainer.faults.failNextWrites(1, GattStatus.GATT_ERROR, FtmsUuids.CONTROL_POINT)
            assertEquals(Kind.WRITE_FAILED, failureKind { trainer.setTargetPower(200) })
            trainer.setTargetPower(200)
            assertEquals(200, rig.fakeTrainer.targetPowerWatts)
            assertEquals(listOf("RC", "START", "RC", "START", "TARGET 200"), rig.fakeTrainer.opcodes)
        }

    @Test
    fun `a link that drops while a command waits fails it as not connected`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            rig.fakeTrainer.faults.noControlPointResponse(count = 1, opcode = 0x05)
            val call = async { runCatching { trainer.setTargetPower(200) } }
            rig.advance(1_000)
            rig.fakeTrainer.dropLink()
            rig.settle()
            assertEquals(Kind.NOT_CONNECTED, assertIs<TrainerCommandException>(call.await().exceptionOrNull()).kind)
        }

    @Test
    fun `one Control Point command is in flight at a time, the second waits for the first`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            val first = async { trainer.setTargetPower(150) }
            val second = async { trainer.setSimulation(flat) }
            rig.advance(1_000)
            first.await()
            second.await()
            assertEquals(listOf("RC", "START", "TARGET 150", "SIM 0.0"), rig.fakeTrainer.opcodes)
            rig.assertNoViolations()
        }

    // endregion

    // region status notifications (§5)

    @Test
    fun `control lost is taken back and the last target re-sent without the engine's help`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            val events = collectAll(trainer.events)
            trainer.setTargetPower(200)
            rig.fakeTrainer.revokeControl()
            rig.advance(500)
            val lost = events.filterIsInstance<TrainerEvent.ControlLost>().single()
            assertEquals(FitnessMachineStatus.ControlPermissionLost, lost.status)
            assertEquals(3, rig.fakeTrainer.commands.size, "nothing yet: the recovery waits a second")
            rig.advance(1_000)
            assertEquals(listOf("RC", "START", "TARGET 200", "RC", "START", "TARGET 200"), rig.fakeTrainer.opcodes)
            assertTrue(rig.fakeTrainer.hasControl)
        }

    @Test
    fun `a target the trainer altered is reported, one it kept is not`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            val events = collectAll(trainer.events)
            trainer.setTargetPower(200)
            rig.advance(100)
            assertTrue(events.none { it is TrainerEvent.TargetPowerAltered })
            assertNotNull(rig.fakeTrainer.connection).push(FtmsUuids.STATUS, byteArrayOf(0x08, 0xDC.toByte(), 0x00))
            rig.settle()
            assertTrue(TrainerEvent.TargetPowerAltered(sent = 200, reported = 220) in events)
        }

    @Test
    fun `without the status echo of the trainer nothing breaks, bench question FT 4`() =
        runTest {
            val rig = BleRig(this, trainerOptions = FakeFtmsTrainerOptions(sendsStatus = false))
            val trainer = rig.runTrainer()
            trainer.setTargetPower(200)
            assertEquals(200, rig.fakeTrainer.targetPowerWatts)
        }

    // endregion

    // region reconnect (§4.1)

    @Test
    fun `after a dropout it reconnects by itself and takes control again before the engine asks`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            trainer.setTargetPower(200)
            rig.fakeTrainer.dropLink(status = 8)
            rig.settle()
            assertEquals(ConnectionState.DISCONNECTED, trainer.connection.value)
            assertEquals(false, rig.fakeTrainer.hasControl)
            rig.advance(2_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            assertTrue(rig.fakeTrainer.hasControl)
            assertEquals(listOf("RC", "START", "TARGET 200", "RC", "START"), rig.fakeTrainer.opcodes)
            assertNull(rig.fakeTrainer.targetPowerWatts, "the trainer forgot the target; the engine sends it")
            rig.assertNoViolations()
        }

    @Test
    fun `a status 133 drop closes that GATT object and the reconnect uses a fresh one`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            rig.fakeTrainer.dropLink(status = GattStatus.GATT_ERROR)
            rig.settle()
            assertTrue(rig.ble.transports[0].isClosed)
            val waiting = assertIs<LinkStatus.WaitingToRetry>(trainer.status.value)
            assertEquals(GattStatus.GATT_ERROR, waiting.failure.status)
            rig.advance(2_000)
            assertEquals(2, rig.ble.transports.size)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            rig.assertNoViolations()
        }

    @Test
    fun `retry now reconnects without waiting for the backoff`() =
        runTest {
            val rig = BleRig(this)
            rig.fakeTrainer.powered = false
            rig.fakeTrainer.absentFailsAfter = 1.seconds
            val trainer = rig.runTrainer()
            rig.advance(3_000) // fails at 1.2 s, waits 1 s, fails again at ~3.4 s: now waiting 2 s
            rig.fakeTrainer.powerOn()
            trainer.retryNow()
            rig.advance(1_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
        }

    @Test
    fun `Bluetooth switched off is reported and a ride resumes when it is back on`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            rig.ble.setAvailability(BleAvailability.BLUETOOTH_OFF)
            rig.settle()
            assertEquals(ConnectionState.DISCONNECTED, trainer.connection.value)
            assertEquals(LinkStatus.BluetoothUnavailable(BleAvailability.BLUETOOTH_OFF), trainer.status.value)
            rig.ble.setAvailability(BleAvailability.READY)
            rig.advance(1_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
        }

    @Test
    fun `close ends the link and the supervisor for good`() =
        runTest {
            val rig = BleRig(this)
            val trainer = rig.runTrainer()
            trainer.close()
            rig.settle()
            assertEquals(ConnectionState.DISCONNECTED, trainer.connection.value)
            assertEquals(LinkStatus.Stopped, trainer.status.value)
            rig.advance(30_000)
            assertEquals(1, rig.ble.transports.size)
        }

    // endregion
}
