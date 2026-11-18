package app.overdrive.ble.gatt

import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattProperty
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.ValueUpdate

/** A made-up address: tests assert that it never reaches a log line. */
internal const val TEST_ADDRESS = "AA:BB:CC:DD:EE:01"
internal val TEST_DEVICE = DeviceId(TEST_ADDRESS)

internal val SERVICE = GattUuid.short(0x1826)
internal val INDOOR_BIKE = GattUuid.short(0x2AD2)
internal val CONTROL_POINT = GattUuid.short(0x2AD9)

/** The Click's SYNC_RX, `0003` in the test names, which takes a write with and without response. */
internal val CLICK_SYNC_RX = GattUuid.short(0x0003)
internal val CLICK_SYNC_RX_INFO =
    GattCharacteristicInfo(SERVICE, CLICK_SYNC_RX, setOf(GattProperty.WRITE, GattProperty.WRITE_WITHOUT_RESPONSE))

internal val INDOOR_BIKE_INFO = GattCharacteristicInfo(SERVICE, INDOOR_BIKE, setOf(GattProperty.NOTIFY))
internal val CONTROL_POINT_INFO =
    GattCharacteristicInfo(SERVICE, CONTROL_POINT, setOf(GattProperty.WRITE, GattProperty.INDICATE))

/**
 * A platform that does what the test says, when it says it: every start is recorded in [gatt] and answered with
 * [Start.STARTED] (or whatever the test set), and no result arrives until the test calls [emit], exactly as a callback
 * arrives later on Android. It is the shim of the transport tests; [CorePlatform] is the one that plays a whole
 * simulated radio.
 */
internal class ScriptedPlatform : PlatformGattOpener {
    val gatt = ScriptedGatt()
    var listener: PlatformListener? = null
        private set
    var opened = 0
        private set

    /** What `open` throws instead of returning, or null. */
    var openThrows: Exception? = null

    /** `open` returns null, as `connectGatt` can. */
    var openReturnsNull = false

    /** Events to deliver from inside `open`, before it returns (a callback that beats `connectGatt`). */
    val eventsDuringOpen = mutableListOf<PlatformEvent>()

    override fun open(
        device: DeviceId,
        listener: PlatformListener,
    ): PlatformGatt? {
        opened++
        this.listener = listener
        openThrows?.let { throw it }
        if (openReturnsNull) return null
        eventsDuringOpen.forEach(listener::onEvent)
        return gatt
    }

    fun emit(event: PlatformEvent) = checkNotNull(listener) { "connect() was not called" }.onEvent(event)

    fun connected() = emit(PlatformEvent.ConnectionChanged(status = 0, connected = true))

    fun dropped(status: Int = 8) = emit(PlatformEvent.ConnectionChanged(status, connected = false))
}

/** The platform handle of [ScriptedPlatform]: records the calls, answers them as the test says. */
internal class ScriptedGatt : PlatformGatt {
    /**
     * Every call, in order: `discover`, `mtu 64`, `read 2ACC`, `write 2AD9 0102`, `writenr 2AD9 0102` (without response),
     * `enable 2AD2 NOTIFICATION`, `disconnect`, `close`.
     */
    val calls = mutableListOf<String>()

    /** What the next starts answer with; reset to [Start.STARTED] after each use unless [sticky]. */
    var answer: Start = Start.STARTED
    var sticky = false

    /** Thrown by the named call instead of answering (the call string's first word), e.g. `"read"`. */
    val throwing = mutableMapOf<String, Exception>()

    private fun start(call: String): Start {
        calls += call
        throwing[call.substringBefore(' ')]?.let { throw it }
        return answer.also { if (!sticky) answer = Start.STARTED }
    }

    override fun discoverServices() = start("discover")

    override fun requestMtu(mtu: Int) = start("mtu $mtu")

    override fun read(
        service: GattUuid,
        characteristic: GattUuid,
    ) = start("read $characteristic")

    override fun write(
        service: GattUuid,
        characteristic: GattUuid,
        value: ByteArray,
    ) = start("write $characteristic ${value.joinToString("") { "%02X".format(it) }}")

    override fun writeWithoutResponse(
        service: GattUuid,
        characteristic: GattUuid,
        value: ByteArray,
    ) = start("writenr $characteristic ${value.joinToString("") { "%02X".format(it) }}")

    override fun enable(
        service: GattUuid,
        characteristic: GattUuid,
        mode: ValueUpdate,
    ) = start("enable $characteristic $mode")

    override fun disconnect() {
        calls += "disconnect"
        throwing["disconnect"]?.let { throw it }
    }

    override fun close() {
        calls += "close"
        throwing["close"]?.let { throw it }
    }

    fun count(prefix: String) = calls.count { it.startsWith(prefix) }
}
