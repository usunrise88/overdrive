package app.overdrive.core.ble

import app.overdrive.core.ble.adapter.FtmsOptions
import app.overdrive.core.ble.adapter.FtmsTrainerDevice
import app.overdrive.core.ble.adapter.HeartRateOptions
import app.overdrive.core.ble.adapter.HeartRateStrapDevice
import app.overdrive.core.ble.fake.FakeBle
import app.overdrive.core.ble.fake.FakeFtmsTrainer
import app.overdrive.core.ble.fake.FakeFtmsTrainerOptions
import app.overdrive.core.ble.fake.FakeHeartRateStrap
import app.overdrive.core.ble.fake.FakeHeartRateStrapOptions
import app.overdrive.core.ble.link.BackoffPolicy
import app.overdrive.core.ble.recording.BleSessionFormat
import app.overdrive.core.ble.recording.BleSessionRecorder
import app.overdrive.core.ble.recording.recording
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.assertEquals

/** Opaque test ids: nothing here resembles a hardware address. */
internal val TRAINER_ID = DeviceId("test-trainer")
internal val STRAP_ID = DeviceId("test-strap")

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * A simulated Bluetooth world on a test scheduler, with one trainer and one strap in it, and helpers to build the
 * adapters over it. [advance] moves virtual time and lets everything due run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BleRig(
    val testScope: TestScope,
    trainerOptions: FakeFtmsTrainerOptions = FakeFtmsTrainerOptions(),
    strapOptions: FakeHeartRateStrapOptions = FakeHeartRateStrapOptions(),
) {
    val ble = FakeBle(testScope.backgroundScope, nanoClock = { testScope.testScheduler.currentTime * NANOS_PER_MILLI })
    val fakeTrainer = FakeFtmsTrainer(trainerOptions)
    val fakeStrap = FakeHeartRateStrap(strapOptions)

    init {
        ble.add(TRAINER_ID, fakeTrainer)
        ble.add(STRAP_ID, fakeStrap)
    }

    val nowMillis: Long get() = testScope.testScheduler.currentTime

    fun trainer(
        options: FtmsOptions = FtmsOptions(),
        reconnect: BackoffPolicy = BackoffPolicy.EXACT,
        factory: GattTransportFactory = ble,
    ) = FtmsTrainerDevice(
        scope = testScope.backgroundScope,
        device = TRAINER_ID,
        transports = factory,
        environment = ble,
        name = "D500",
        options = options,
        reconnect = reconnect,
        timeSource = testScope.testTimeSource,
    )

    fun strap(
        options: HeartRateOptions = HeartRateOptions(),
        reconnect: BackoffPolicy = BackoffPolicy.EXACT,
        factory: GattTransportFactory = ble,
    ) = HeartRateStrapDevice(
        scope = testScope.backgroundScope,
        device = STRAP_ID,
        transports = factory,
        environment = ble,
        name = "HR7",
        options = options,
        reconnect = reconnect,
        timeSource = testScope.testTimeSource,
    )

    /** Advances virtual time by [millis] and runs everything due at or before then. */
    fun advance(millis: Long) {
        testScope.advanceTimeBy(millis)
        testScope.runCurrent()
    }

    fun settle() = testScope.runCurrent()

    /** The times (ms) at which each transport was opened, oldest first. */
    fun openTimes(): List<Long> = ble.events.filter { it.what.startsWith("open#") }.map { it.timeMillis }

    fun assertNoViolations() = assertEquals(emptyList(), ble.violations)

    /**
     * A transport factory that records everything crossing it, and the lines it wrote. Call [settle] before reading
     * [RecordedLines.lines], so the writer coroutine has run.
     */
    fun recording(model: String? = null): RecordedLines {
        val lines = mutableListOf<String>()
        val recorder =
            BleSessionRecorder(
                scope = testScope.backgroundScope,
                sink = { lines += it },
                nanoClock = { testScope.testScheduler.currentTime * NANOS_PER_MILLI },
                model = model,
            )
        return RecordedLines(ble.recording(recorder, testScope.backgroundScope), recorder, lines)
    }
}

/** What [BleRig.recording] returns: the factory to hand to the adapter, the recorder, and what it has written. */
internal class RecordedLines(
    val factory: GattTransportFactory,
    val recorder: BleSessionRecorder,
    val lines: List<String>,
) {
    /** The events written so far, parsed, header lines skipped. */
    fun events() = lines.mapNotNull { BleSessionFormat.parseLine(it) }

    /** The compact form `dir char data` of each event, for sequence assertions. */
    fun compact(): List<String> = events().map { "${it.direction.code} ${it.characteristic ?: "-"} ${it.data}" }
}
