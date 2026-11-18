package app.overdrive.ride.devices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.overdrive.ble.BleStack
import app.overdrive.core.ble.BleEnvironment
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattScanner
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.ble.fake.FakeBle
import app.overdrive.core.ble.fake.FakeClick
import app.overdrive.core.ble.fake.FakeFtmsTrainer
import app.overdrive.core.ble.fake.FakeHeartRateStrap
import app.overdrive.core.ble.link.BackoffPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.testTimeSource
import org.junit.Assert.assertEquals

/** Opaque test ids: nothing here resembles a hardware address. */
internal val TRAINER_ID = DeviceId("test-trainer")
internal val STRAP_ID = DeviceId("test-strap")
internal val OTHER_TRAINER_ID = DeviceId("test-trainer-2")
internal val CLICK_ID = DeviceId("test-click")

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * A DataStore that lives in memory, so a test in virtual time never waits for a disk. The real one has its own test
 * ([DeviceStoreTest]); everything above the store trusts it.
 */
internal class InMemoryPreferences(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

/**
 * A simulated Bluetooth world on a test scheduler with one trainer and one strap in it, and [PairedDevices] over it with
 * exact (jitter-free) backoff, the way the container wires them. [advance] moves virtual time and runs what is due.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BleWorld(
    val test: TestScope,
    val store: DeviceStore = DeviceStore(InMemoryPreferences()),
) {
    val ble = FakeBle(test.backgroundScope, nanoClock = { test.testScheduler.currentTime * NANOS_PER_MILLI })
    val fakeTrainer = FakeFtmsTrainer()
    val fakeStrap = FakeHeartRateStrap()
    val otherTrainer = FakeFtmsTrainer(advertisedName = "Other D500")
    val fakeClick = FakeClick()

    init {
        ble.add(TRAINER_ID, fakeTrainer)
        ble.add(STRAP_ID, fakeStrap)
        ble.add(OTHER_TRAINER_ID, otherTrainer)
        ble.add(CLICK_ID, fakeClick)
    }

    val devices =
        PairedDevices(
            scope = test.backgroundScope,
            store = store,
            environment = ble,
            transports = { ble },
            reconnect = BackoffPolicy.EXACT,
            timeSource = test.testTimeSource,
        )

    /**
     * Pairs the trainer and the strap (and the Click, with [withClick]) in the store and starts, as a launch after the
     * rider had paired them.
     */
    suspend fun launchPaired(withClick: Boolean = false) {
        store.remember(DeviceRole.Trainer, RememberedDevice(TRAINER_ID, "D500"))
        store.remember(DeviceRole.Strap, RememberedDevice(STRAP_ID, "HR7"))
        if (withClick) store.remember(DeviceRole.Click, RememberedDevice(CLICK_ID, "Zwift Click (right)"))
        devices.start()
        advance(2_000)
    }

    fun advance(millis: Long) {
        test.advanceTimeBy(millis)
        test.runCurrent()
    }

    fun settle() = test.runCurrent()

    fun assertNoViolations() = assertEquals(emptyList<String>(), ble.violations)
}

/** [FakeBle] as the [BleStack] the container takes, for tests of the container's wiring. */
internal class FakeBleStack(
    val ble: FakeBle,
    private val nanos: () -> Long = System::nanoTime,
) : BleStack {
    var refreshes = 0
        private set
    var closed = false
        private set

    override val environment: BleEnvironment get() = ble
    override val scanner: GattScanner get() = ble
    override val transports: GattTransportFactory get() = ble
    override val nanoClock: () -> Long get() = nanos

    override fun refresh() {
        refreshes++
    }

    override fun close() {
        closed = true
    }
}
