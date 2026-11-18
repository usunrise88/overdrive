package app.overdrive.core.ble.fake

import app.overdrive.core.ble.Advertisement
import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.BleEnvironment
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattScanner
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.ble.ScanEvent
import app.overdrive.core.ble.ScanFailure
import app.overdrive.core.ble.ScanFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One thing that happened in a [FakeBle], for tests that assert sequence and timing. */
public data class FakeBleEvent(
    val timeMillis: Long,
    val what: String,
)

/**
 * A simulated Bluetooth world (spec §7.5): the radio's [availability], a set of [FakePeripheral]s, a [GattTransportFactory]
 * that connects to them and a [GattScanner] that hears them. Everything is driven by [scope] and its delays, so with a
 * test scheduler a ride over "Bluetooth" runs in virtual time and the same script gives the same result every time.
 *
 * It is **strict like the real stack**, and records what it sees in [violations] (an empty list at the end of a test is
 * part of the assertion):
 * - GATT operations must not overlap (the platform allows one; a second gets `BUSY`);
 * - nothing may be done on a transport that was closed or never connected;
 * - a new transport for a device may be opened only after the previous one was closed (a failed `BluetoothGatt`, such as
 *   after status 133, must not linger);
 * - a peripheral accepts one central at a time, so a connect while a link is up fails with 133;
 * - values reach the central only for characteristics it enabled.
 *
 * @param nanoClock the clock stamped on notifications and events; with a test scheduler, `{ testScheduler.currentTime *
 * 1_000_000 }`. It must be the clock of [scope]'s delays.
 * @param opLatency how long every GATT operation takes.
 * @param connectLatency how long a connect takes.
 */
public class FakeBle(
    internal val scope: CoroutineScope,
    internal val nanoClock: () -> Long,
    internal val opLatency: Duration = 20.milliseconds,
    internal val connectLatency: Duration = 200.milliseconds,
) : BleEnvironment,
    GattTransportFactory,
    GattScanner {
    private val mutableAvailability = MutableStateFlow(BleAvailability.READY)
    private val peripherals = mutableMapOf<DeviceId, FakePeripheral>()
    private val opened = mutableListOf<FakeGattTransport>()
    private val mutableEvents = mutableListOf<FakeBleEvent>()
    private val mutableViolations = mutableListOf<String>()

    override val availability: StateFlow<BleAvailability> = mutableAvailability.asStateFlow()

    /** Every transport opened so far, oldest first. */
    public val transports: List<FakeGattTransport> get() = opened

    /** What happened, oldest first: `open#1`, `connect#1`, `connected#1`, `drop#1(8)`, `close#1`, `bluetooth READY`… */
    public val events: List<FakeBleEvent> get() = mutableEvents

    /** Contract violations by the code under test: empty when it behaved. */
    public val violations: List<String> get() = mutableViolations

    /** Adds [peripheral] under [id] and returns [id]. */
    public fun add(
        id: DeviceId,
        peripheral: FakePeripheral,
    ): DeviceId {
        peripherals[id] = peripheral
        peripheral.world = this
        return id
    }

    /** The peripheral registered under [id]. */
    public fun peripheral(id: DeviceId): FakePeripheral = checkNotNull(peripherals[id]) { "No peripheral for $id" }

    /**
     * Switches Bluetooth, or the permission, to [value]. Anything but ready drops every live link at once, as the stack
     * does, and makes every connect fail with `UNAVAILABLE` until it is ready again.
     */
    public fun setAvailability(value: BleAvailability) {
        if (mutableAvailability.value == value) return
        record("bluetooth $value")
        mutableAvailability.value = value
        if (value != BleAvailability.READY) {
            opened.forEach { it.dropFromWorld() }
        }
    }

    override fun open(device: DeviceId): GattTransport {
        val peripheral = peripheral(device)
        opened.filter { it.device == device && !it.isClosed }.forEach {
            violate("A new transport for $device was opened before #${it.index} was closed")
        }
        val transport = FakeGattTransport(this, device, opened.size + 1, peripheral)
        opened += transport
        record("open#${transport.index}")
        return transport
    }

    override fun scan(filter: ScanFilter): Flow<ScanEvent> =
        flow {
            record("scan started")
            try {
                while (true) {
                    val state = mutableAvailability.value
                    if (state != BleAvailability.READY) {
                        emit(
                            ScanEvent.Failed(
                                if (state ==
                                    BleAvailability.PERMISSION_DENIED
                                ) {
                                    ScanFailure.PERMISSION_DENIED
                                } else {
                                    ScanFailure.BLUETOOTH_OFF
                                },
                            ),
                        )
                        return@flow
                    }
                    for ((id, peripheral) in peripherals) {
                        if (peripheral.powered && filter.accepts(peripheral.advertisedServices)) {
                            emit(
                                ScanEvent.Found(
                                    Advertisement(
                                        id,
                                        peripheral.advertisedName,
                                        peripheral.advertisedServices,
                                        peripheral.rssi,
                                    ),
                                ),
                            )
                        }
                    }
                    delay(ADVERTISE_INTERVAL)
                }
            } finally {
                record("scan stopped")
            }
        }

    internal fun record(what: String) {
        mutableEvents += FakeBleEvent(nanoClock() / NANOS_PER_MILLI, what)
    }

    internal fun violate(problem: String) {
        record("VIOLATION $problem")
        mutableViolations += problem
    }

    private companion object {
        val ADVERTISE_INTERVAL = 1.seconds
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
