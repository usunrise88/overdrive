package app.overdrive.core.ble.fake

import app.overdrive.core.ble.BatteryUuids
import app.overdrive.core.ble.ClickUuids
import app.overdrive.core.ble.DeviceInformationUuids
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattProperty
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.IdentityUuids
import app.overdrive.core.ble.ManufacturerData
import app.overdrive.core.ble.ValueUpdate
import app.overdrive.core.click.ClickBits
import app.overdrive.core.click.ClickCodec
import app.overdrive.core.click.ClickService
import app.overdrive.core.click.ClickSide
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** What a [FakeClick] does about being locked by Zwift (docs/protocols/click-v2.md §6, spec §4.7). */
public enum class FakeClickLock {
    /** Unlocked: it answers the handshake and delivers frames. */
    NONE,

    /** Locked, the service hidden: discovery lists no Zwift service (§6.1). Takes effect at the next connection. */
    HIDDEN,

    /** Locked, silent: it answers the handshake and then sends nothing at all. */
    SILENT,
}

/**
 * The knobs of a [FakeClick]. **The defaults are the right puck of the first bench** (docs/bench-reports/2026-11-18-first-ride.md,
 * synthetic: only the protocol facts of that report are used): it answers the handshake with the bare six bytes `RideOn`,
 * sends **nothing until a button is pressed** (no heartbeat, no battery frame), and offers the battery by read only.
 * [byNotes] is the puck as `docs/protocols/click-v2.md` described it before the bench (`RideOn 02 03`, a battery frame, a
 * heartbeat), and [realPuck] adds the GATT details of the real one.
 */
public class FakeClickOptions(
    /** Which puck it is: sets the first byte of the manufacturer data (§1). */
    public val side: ClickSide = ClickSide.RIGHT,
    /** The first byte of the manufacturer data for the right puck; the notes' `0x0A`. */
    public val rightSideByte: Int = 0x0A,
    /** The first byte of the manufacturer data for the left puck; the notes' `0x0B`. */
    public val leftSideByte: Int = 0x0B,
    /** Which service the characteristics live in (§2). */
    public val service: ClickService = ClickService.CURRENT,
    /**
     * The two bytes after `RideOn` in the reply; null (the default, as the real puck) makes it a bare `RideOn`. The notes
     * expected `0x0203` ([ClickEncoder.DEFAULT_VERSION]).
     */
    public val replyVersion: Int? = null,
    /** The length of the stand-in public key after the version in the reply (§3.1, bench question 3). Needs a [replyVersion]. */
    public val replyTailLength: Int = 0,
    /**
     * The exact bytes of the reply, instead of the ones [replyVersion] and [replyTailLength] make: for a reply the notes do
     * not describe (seven bytes, bytes that are not `RideOn`). Null (the default) uses those two.
     */
    public val rawReply: ByteArray? = null,
    /** Time from the handshake write to the reply. */
    public val replyLatency: Duration = 50.milliseconds,
    /** False: it never answers the handshake (a controller that is not listening). */
    public val answersHandshake: Boolean = true,
    /**
     * Time between idle heartbeats once delivering; null (the default, as the real puck) sends none: the real one sends
     * nothing until a button is pressed (bench question 7 is about exactly this).
     */
    public val heartbeatInterval: Duration? = null,
    /** The battery percentage the Battery Service gives on a read (the real puck: `0x64`); null leaves the service out. */
    public val batteryPercent: Int? = 100,
    /** True: the battery percentage is also sent once after the handshake as a `0x19` frame (the notes' §4; the real puck sends none). */
    public val batteryFrame: Boolean = false,
    /** The firmware revision string the Device Information Service gives; null leaves the characteristic out. */
    public val firmware: String? = "1.2.0",
    /** True: nothing is delivered until `FF 04 00` has been written (a model of bench question 6's "it was needed"). */
    public val needsPostHandshake: Boolean = false,
    /**
     * True: the Zwift service also lists the three vendor characteristics `…0100`, `…0101` and `…0102` that the real puck
     * offers and the app does not use (bench report 1 §1). They accept writes and notify, and do nothing.
     */
    public val vendorExtras: Boolean = false,
    /** True: SYNC_RX is **write without response only**, as on the real puck, so a write with response is refused (CK 2). */
    public val syncRxWithoutResponseOnly: Boolean = false,
    /** The ATT MTU it answers a request with, whatever was asked (the real puck: 251 for the app's 128); null grants what was asked. */
    public val negotiatedMtu: Int? = null,
    /**
     * A frame pushed on ASYNC as soon as the host has enabled its notifications, before the handshake is answered: the real
     * puck did this with an unknown `0x2A` message in 3 of 28 sessions ([ClickEncoder.unknownAnnouncement]). Null sends none.
     */
    public val frameOnSubscribe: ByteArray? = null,
) {
    public companion object {
        /**
         * The puck as the notes described it before the first bench: it answers `RideOn 02 03` (then [replyTailLength] bytes of
         * a stand-in key), sends a battery frame after the handshake and a heartbeat every [heartbeatInterval]. For the tests
         * that exercise the `02 03` path, the heartbeat and the battery frame, which a real Click does not send.
         */
        public fun byNotes(
            replyTailLength: Int = 0,
            heartbeatInterval: Duration? = 5.seconds,
            batteryPercent: Int? = 87,
            needsPostHandshake: Boolean = false,
            service: ClickService = ClickService.CURRENT,
        ): FakeClickOptions =
            FakeClickOptions(
                service = service,
                replyVersion = ClickEncoder.DEFAULT_VERSION,
                replyTailLength = replyTailLength,
                heartbeatInterval = heartbeatInterval,
                batteryPercent = batteryPercent,
                batteryFrame = batteryPercent != null,
                needsPostHandshake = needsPostHandshake,
            )

        /**
         * The default puck with what the first bench saw of the real one's GATT and set-up: the three extra vendor
         * characteristics, write without response only on SYNC_RX, an MTU of 251, and (if [announces]) the unknown `0x2A`
         * message right after the notifications are enabled.
         */
        public fun realPuck(announces: Boolean = true): FakeClickOptions =
            FakeClickOptions(
                vendorExtras = true,
                syncRxWithoutResponseOnly = true,
                negotiatedMtu = REAL_MTU,
                frameOnSubscribe = if (announces) ClickEncoder.unknownAnnouncement() else null,
            )

        private const val REAL_MTU = 251
    }
}

/** One write to the Click's SYNC_RX, as the fake saw it. */
public class FakeClickWrite(
    public val timeNanos: Long,
    bytes: ByteArray,
    /** True for a write with response, false for one without. */
    public val withResponse: Boolean,
) {
    private val copy = bytes.copyOf()

    /** The bytes written. */
    public val bytes: ByteArray get() = copy.copyOf()

    override fun toString(): String = "FakeClickWrite(${copy.size} bytes, withResponse=$withResponse, t=$timeNanos)"
}

/**
 * A simulated Zwift Click v2 (spec §7.5, docs/protocols/click-v2.md): advertises the Zwift service and manufacturer data;
 * after the host subscribes to ASYNC and SYNC_TX and writes `RideOn` it answers on SYNC_TX (by default the bare `RideOn` the
 * right puck of the first bench answered; [FakeClickOptions.byNotes] answers `RideOn 02 03`) and then delivers a button frame
 * on ASYNC for every press and release, **and nothing else until a button is pressed**, as the real puck did. The notes'
 * heartbeat and battery frame are options. **It is written from the notes and one bench report**, so a green test proves
 * consistency with them, not with every real controller.
 *
 * Buttons are pressed by [press] and [release] (a mask, 1 for pressed, [ClickBits]) or by a [script] at world times. Being
 * locked ([lock]) is a model of the two signals of spec §4.7: the service is hidden, or the handshake is answered and then
 * nothing comes. [unlock] ends either. Drops, a peripheral that is off, notification gaps and the other scripted failures are
 * those of [FakePeripheral] and [FakeFaults] (`faults.silence(.., ClickUuids.ASYNC)`).
 *
 * The physical button state ([held]) is independent of the link: a button held while the link drops stays held on the puck,
 * which is how a release gets lost; nothing is sent on reconnect until a button changes.
 */
public class FakeClick(
    private val options: FakeClickOptions = FakeClickOptions(),
    advertisedName: String? = "Click",
) : FakePeripheral(
        advertisedName,
        setOf(if (options.service == ClickService.CURRENT) ClickUuids.SERVICE else ClickUuids.LEGACY_SERVICE),
    ) {
    private val written = mutableListOf<FakeClickWrite>()
    private var handshaken = false
    private var postHandshakeSeen = false
    private var delivering = false

    init {
        val type = if (options.side == ClickSide.RIGHT) options.rightSideByte else options.leftSideByte
        manufacturerData = listOf(ManufacturerData(ClickUuids.MANUFACTURER_ID, byteArrayOf(type.toByte())))
        negotiatedMtu = options.negotiatedMtu
    }

    /** Whether it is locked, and how. Set before a connection to change what discovery shows. */
    public var lock: FakeClickLock = FakeClickLock.NONE

    /** The buttons that are physically down, 1 for pressed. Survives a dropped link. */
    public var held: Int = 0
        private set

    /** Every write to SYNC_RX, oldest first, across connections. */
    public val writes: List<FakeClickWrite> get() = written

    /** How many times `RideOn` was written. */
    public val handshakeCount: Int get() = written.count { it.bytes.contentEquals(ClickCodec.handshake()) }

    /** Whether the current link has been through the handshake. */
    public val isHandshaken: Boolean get() = handshaken

    /** Whether it is delivering frames on the current link. */
    public val isDelivering: Boolean get() = delivering

    override val characteristics: List<GattCharacteristicInfo>
        get() {
            val zwift =
                if (lock == FakeClickLock.HIDDEN) {
                    emptyList()
                } else {
                    val service =
                        if (options.service ==
                            ClickService.CURRENT
                        ) {
                            ClickUuids.SERVICE
                        } else {
                            ClickUuids.LEGACY_SERVICE
                        }
                    val rxProperties =
                        if (options.syncRxWithoutResponseOnly) {
                            setOf(GattProperty.WRITE_WITHOUT_RESPONSE)
                        } else {
                            setOf(GattProperty.WRITE, GattProperty.WRITE_WITHOUT_RESPONSE)
                        }
                    val extras =
                        if (options.vendorExtras) {
                            listOf(
                                GattCharacteristicInfo(service, ClickUuids.VENDOR_0100, VENDOR_EXTRA_PROPERTIES),
                                GattCharacteristicInfo(service, ClickUuids.VENDOR_0101, VENDOR_EXTRA_PROPERTIES),
                                GattCharacteristicInfo(
                                    service,
                                    ClickUuids.VENDOR_0102,
                                    setOf(GattProperty.WRITE_WITHOUT_RESPONSE, GattProperty.NOTIFY),
                                ),
                            )
                        } else {
                            emptyList()
                        }
                    listOf(
                        GattCharacteristicInfo(service, ClickUuids.ASYNC, setOf(GattProperty.NOTIFY)),
                        GattCharacteristicInfo(service, ClickUuids.SYNC_RX, rxProperties),
                        GattCharacteristicInfo(
                            service,
                            ClickUuids.SYNC_TX,
                            setOf(GattProperty.INDICATE, GattProperty.READ),
                        ),
                    ) + extras
                }
            val standard =
                buildList {
                    if (options.firmware != null) {
                        add(
                            GattCharacteristicInfo(
                                DeviceInformationUuids.SERVICE,
                                IdentityUuids.FIRMWARE_REVISION,
                                setOf(GattProperty.READ),
                            ),
                        )
                    }
                    if (options.batteryPercent != null) {
                        add(
                            GattCharacteristicInfo(
                                BatteryUuids.SERVICE,
                                BatteryUuids.LEVEL,
                                setOf(GattProperty.READ, GattProperty.NOTIFY),
                            ),
                        )
                    }
                }
            return zwift + standard
        }

    override fun onConnected(connection: FakeConnection) {
        handshaken = false
        postHandshakeSeen = false
        delivering = false
    }

    override fun onDisconnected() {
        handshaken = false
        postHandshakeSeen = false
        delivering = false
    }

    override fun onSubscribed(
        characteristic: GattUuid,
        mode: ValueUpdate,
    ) {
        val frame = options.frameOnSubscribe ?: return
        val link = connection ?: return
        if (characteristic != ClickUuids.ASYNC) return
        link.scope.launch {
            // Just after the subscription, not at the same instant: a replay pushes at recorded times and must find it enabled.
            delay(ANNOUNCEMENT_DELAY)
            link.push(ClickUuids.ASYNC, frame)
        }
    }

    override fun onRead(characteristic: GattUuid): ByteArray? =
        when (characteristic) {
            IdentityUuids.FIRMWARE_REVISION -> options.firmware?.encodeToByteArray()
            BatteryUuids.LEVEL -> options.batteryPercent?.let { byteArrayOf(it.toByte()) }
            else -> null
        }

    override fun onWrite(
        characteristic: GattUuid,
        value: ByteArray,
    ): Int = handleWrite(characteristic, value, withResponse = true)

    override fun onWriteWithoutResponse(
        characteristic: GattUuid,
        value: ByteArray,
    ): Int = handleWrite(characteristic, value, withResponse = false)

    private fun handleWrite(
        characteristic: GattUuid,
        value: ByteArray,
        withResponse: Boolean,
    ): Int {
        val link = connection ?: return GattStatus.UNKNOWN
        if (characteristic != ClickUuids.SYNC_RX) return WRITE_NOT_PERMITTED
        if (withResponse && options.syncRxWithoutResponseOnly) return WRITE_NOT_PERMITTED
        written += FakeClickWrite(link.nowNanos(), value, withResponse)
        when {
            value.contentEquals(ClickCodec.handshake()) -> {
                if (options.answersHandshake) {
                    link.scope.launch {
                        delay(options.replyLatency)
                        link.push(
                            ClickUuids.SYNC_TX,
                            options.rawReply
                                ?: ClickEncoder.handshakeReply(options.replyVersion, options.replyTailLength),
                        )
                        handshaken = true
                        maybeStartDelivering(link)
                    }
                }
            }

            value.contentEquals(ClickCodec.postHandshake()) -> {
                postHandshakeSeen = true
                maybeStartDelivering(link)
            }
        }
        return GattStatus.SUCCESS
    }

    private fun maybeStartDelivering(link: FakeConnection) {
        if (delivering || !handshaken || lock == FakeClickLock.SILENT) return
        if (options.needsPostHandshake && !postHandshakeSeen) return
        delivering = true
        options.batteryPercent?.takeIf { options.batteryFrame }?.let { percent ->
            link.scope.launch {
                delay(BATTERY_DELAY)
                link.push(ClickUuids.ASYNC, ClickEncoder.battery(percent))
            }
        }
        options.heartbeatInterval?.let { interval ->
            link.scope.launch {
                while (true) {
                    delay(interval)
                    link.push(ClickUuids.ASYNC, ClickEncoder.heartbeat())
                }
            }
        }
    }

    /** Ends the lock: a silent Click that is connected starts delivering; a hidden service shows at the next connection. */
    public fun unlock() {
        lock = FakeClickLock.NONE
        connection?.let { maybeStartDelivering(it) }
    }

    /** Presses the buttons of [mask] (1 for pressed) and tells the host if it is listening. */
    public fun press(mask: Int) {
        held = held or mask
        sendState()
    }

    /** Releases the buttons of [mask] and tells the host if it is listening. */
    public fun release(mask: Int) {
        held = held and mask.inv()
        sendState()
    }

    /** Sends [bytes] on ASYNC as they are, if the link is up and the host subscribed (a frame the codec does not know, a notice). */
    public fun sendFrame(bytes: ByteArray) {
        connection?.push(ClickUuids.ASYNC, bytes)
    }

    private fun sendState() {
        if (delivering) connection?.push(ClickUuids.ASYNC, ClickEncoder.pressed(held))
    }

    /** Schedules things at world times, measured from the world clock's zero (test time 0). Add the peripheral to a [FakeBle] first. */
    public fun script(block: ClickScript.() -> Unit) {
        ClickScript(this).block()
    }

    internal fun worldOrFail(): FakeBle = checkNotNull(world) { "Add the FakeClick to a FakeBle before scripting it" }

    private companion object {
        const val WRITE_NOT_PERMITTED = 3
        val BATTERY_DELAY = 100.milliseconds
        val ANNOUNCEMENT_DELAY = 10.milliseconds
        val VENDOR_EXTRA_PROPERTIES =
            setOf(GattProperty.WRITE, GattProperty.WRITE_WITHOUT_RESPONSE, GattProperty.NOTIFY)
    }
}

/**
 * The little language a test scripts a [FakeClick] with. Every time is **absolute on the world's clock** (test time 0), like
 * `FakePeripheral.dropLinkAt`; `at = 12.seconds` is twelve seconds into the test. A button here is a mask of [ClickBits].
 */
public class ClickScript internal constructor(
    private val click: FakeClick,
) {
    private fun schedule(
        time: Duration,
        action: () -> Unit,
    ) {
        val world = click.worldOrFail()
        world.scope.launch {
            delay((time.inWholeNanoseconds - world.nanoClock()).coerceAtLeast(0).nanoseconds)
            action()
        }
    }

    /** The buttons of [mask] go down at [at]. */
    public fun press(
        mask: Int,
        at: Duration,
    ) {
        schedule(at) { click.press(mask) }
    }

    /** The buttons of [mask] come up at [at]. */
    public fun release(
        mask: Int,
        at: Duration,
    ) {
        schedule(at) { click.release(mask) }
    }

    /** A short press: down at [at], up [held] later (80 ms by default, a quick tap). */
    public fun tap(
        mask: Int,
        at: Duration,
        held: Duration = DEFAULT_TAP,
    ) {
        press(mask, at)
        release(mask, at + held)
    }

    /** A press held for [duration]: down at [at], up at `at + duration`. */
    public fun hold(
        mask: Int,
        at: Duration,
        duration: Duration,
    ) {
        press(mask, at)
        release(mask, at + duration)
    }

    /** The link drops at [at] with [status] (8, the usual lost-link status, by default). */
    public fun drop(
        at: Duration,
        status: Int = FakeConnection.DEFAULT_DROP_STATUS,
    ) {
        click.dropLinkAt(at, status)
    }

    /** The controller powers off at [at] (the link drops and it stops answering). */
    public fun powerOff(at: Duration) {
        schedule(at) { click.powerOff() }
    }

    /** The controller powers on at [at]. */
    public fun powerOn(at: Duration) {
        schedule(at) { click.powerOn() }
    }

    /** No ASYNC frame is delivered from [from] until [until]: a controller that goes quiet, or a gap in the stream. */
    public fun silence(
        from: Duration,
        until: Duration,
    ) {
        click.faults.silence(from, until, ClickUuids.ASYNC)
    }

    /** A battery report of [percent] at [at]. */
    public fun battery(
        percent: Int,
        at: Duration,
    ) {
        schedule(at) { click.sendFrame(ClickEncoder.battery(percent)) }
    }

    /** The disconnect notice `0xFE` at [at]. */
    public fun notice(at: Duration) {
        schedule(at) { click.sendFrame(ClickEncoder.disconnectNotice()) }
    }

    /** The rider unlocks the controller through Zwift at [at]: a silent one starts delivering, a hidden service shows next connect. */
    public fun unlock(at: Duration) {
        schedule(at) { click.unlock() }
    }

    /** The controller becomes [mode] at [at]. */
    public fun lock(
        mode: FakeClickLock,
        at: Duration,
    ) {
        schedule(at) { click.lock = mode }
    }

    /** An arbitrary frame on ASYNC at [at]: one the codec does not know, or a malformed one. */
    public fun frame(
        bytes: ByteArray,
        at: Duration,
    ) {
        schedule(at) { click.sendFrame(bytes) }
    }

    private companion object {
        val DEFAULT_TAP = 80.milliseconds
    }
}
