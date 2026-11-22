package app.overdrive.core.ble.fake

import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.ManufacturerData
import app.overdrive.core.ble.ValueUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * What a peripheral does on one live link: send values, and end the link. Handed to [FakePeripheral.onConnected].
 */
public class FakeConnection internal constructor(
    private val transport: FakeGattTransport,
    /** A scope that ends when the link does: launch the peripheral's own loops here. */
    public val scope: CoroutineScope,
) {
    /** The world's clock, in nanoseconds. */
    public fun nowNanos(): Long = transport.world.nanoClock()

    /** Whether the link is up. */
    public val isUp: Boolean get() = transport.isLinkUp

    /**
     * Pushes [value] on [characteristic] as the central subscribed to it (a notification, or an indication if it enabled
     * indications). Returns false, and sends nothing, when the central did not subscribe, the link is down, or a scripted
     * [FakeFaults.silence] covers now.
     */
    public fun push(
        characteristic: GattUuid,
        value: ByteArray,
    ): Boolean = transport.deliver(characteristic, value)

    /** Ends the link as a peripheral going out of range or powering off does, with the platform [status]. */
    public fun drop(status: Int = DEFAULT_DROP_STATUS) {
        transport.dropLink(status)
    }

    public companion object {
        /** HCI "connection timeout" (0x08), the usual status of a link that is lost. */
        public const val DEFAULT_DROP_STATUS: Int = 8
    }
}

/**
 * A simulated Bluetooth peripheral behind [FakeBle]: override the hooks to give it a behaviour ([FakeFtmsTrainer],
 * [FakeHeartRateStrap], [ReplayPeripheral]), script its failures with [faults], and drop its link or power it off from a
 * test. All hooks run on the world's scope, so in virtual time they are deterministic.
 */
public abstract class FakePeripheral(
    /** The advertised local name, such as `D500`. */
    public val advertisedName: String?,
    /** The services in its advertisement. */
    public val advertisedServices: Set<GattUuid>,
) {
    /** Scripted failures. */
    public val faults: FakeFaults = FakeFaults()

    /** Everything this peripheral offers, as service discovery reports it. */
    public abstract val characteristics: List<GattCharacteristicInfo>

    /** The manufacturer-specific data in its advertisement; empty for most peripherals. */
    public var manufacturerData: List<ManufacturerData> = emptyList()

    /** Signal strength shown in scans. */
    public var rssi: Int = DEFAULT_RSSI

    /**
     * Whether it is on. A peripheral that is off does not advertise and a connect attempt gets no answer: it hangs until
     * the caller gives up, or fails with 133 after [absentFailsAfter].
     */
    public var powered: Boolean
        get() = poweredState.value
        set(value) {
            poweredState.value = value
        }

    /** [powered] as a flow: a background connect (`ConnectMode.BACKGROUND`) waits on it, as the controller waits for an advertisement. */
    internal val poweredState = kotlinx.coroutines.flow.MutableStateFlow(true)

    /** How long a connect to an absent peripheral takes to fail with 133; null (the default) means it never answers. */
    public var absentFailsAfter: Duration? = null

    /**
     * The ATT MTU this peripheral answers a request with, whatever was asked, or null to grant what was asked. Android lets
     * the peripheral decide: the first bench asked for 128 and got 251 from a Click and from a trainer.
     */
    public var negotiatedMtu: Int? = null

    /** The live link, or null. */
    public var connection: FakeConnection? = null
        internal set

    internal var world: FakeBle? = null

    /** A connect attempt reached the peripheral: return a GATT status to make it fail, or null to accept it. */
    public open fun onConnectAttempt(): Int? = null

    /** The link is up: start the peripheral's behaviour on [connection]. */
    public open fun onConnected(connection: FakeConnection) {}

    /** The link ended, for any reason. */
    public open fun onDisconnected() {}

    /** A read of [characteristic]; null means it cannot be read. */
    public open fun onRead(characteristic: GattUuid): ByteArray? = null

    /** A write with response of [characteristic]; return the GATT status to acknowledge it with. */
    public open fun onWrite(
        characteristic: GattUuid,
        value: ByteArray,
    ): Int = GattStatus.SUCCESS

    /**
     * A write **without response** of [characteristic]; return the GATT status the stack would report. By default it is
     * handled like a write with response, so a peripheral that does not care need not override it.
     */
    public open fun onWriteWithoutResponse(
        characteristic: GattUuid,
        value: ByteArray,
    ): Int = onWrite(characteristic, value)

    /** The central wrote the CCCD of [characteristic]. */
    public open fun onSubscribed(
        characteristic: GattUuid,
        mode: ValueUpdate,
    ) {}

    /** Ends the live link now, if there is one. */
    public fun dropLink(status: Int = FakeConnection.DEFAULT_DROP_STATUS) {
        connection?.drop(status)
    }

    /** Ends the live link at world time [at] (measured from the world clock's zero). */
    public fun dropLinkAt(
        at: Duration,
        status: Int = FakeConnection.DEFAULT_DROP_STATUS,
    ) {
        val fake = checkNotNull(world) { "Add the peripheral to a FakeBle first" }
        fake.scope.launch {
            delay((at.inWholeNanoseconds - fake.nanoClock()).coerceAtLeast(0).nanoseconds)
            dropLink(status)
        }
    }

    /** Powers it off: the link drops now and it stops answering. */
    public fun powerOff(status: Int = FakeConnection.DEFAULT_DROP_STATUS) {
        powered = false
        dropLink(status)
    }

    /** Powers it on again. */
    public fun powerOn() {
        powered = true
    }

    private companion object {
        const val DEFAULT_RSSI = -60
    }
}
