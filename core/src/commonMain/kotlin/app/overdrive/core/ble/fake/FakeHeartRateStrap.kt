package app.overdrive.core.ble.fake

import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattProperty
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.HeartRateUuids
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The knobs of a [FakeHeartRateStrap]. */
public class FakeHeartRateStrapOptions(
    /** Time between notifications. */
    public val notifyInterval: Duration = 1.seconds,
    /** Whether it sends RR intervals (the HR7 is expected to, spec §9 / FT 10). */
    public val sendsRr: Boolean = true,
    /** Whether it reports skin contact: then [FakeHeartRateStrap.onChest] decides between 3 (detected) and 2 (not). */
    public val reportsContact: Boolean = true,
)

/**
 * A simulated heart-rate strap (spec §7.5): a Heart Rate Measurement every [FakeHeartRateStrapOptions.notifyInterval] at
 * [bpm], with the RR intervals of about one beat per 60/bpm seconds in 1/1024 s and a small repeating variation, and
 * the contact bits following [onChest]. Set [bpm] to follow a ride; take it [onChest] = false for "strap off".
 */
public class FakeHeartRateStrap(
    private val options: FakeHeartRateStrapOptions = FakeHeartRateStrapOptions(),
    advertisedName: String? = "HR7",
) : FakePeripheral(advertisedName, setOf(HeartRateUuids.SERVICE)) {
    private var beat = 0

    /** The heart rate it reports. */
    public var bpm: Int = DEFAULT_BPM

    /** Whether the strap is on the chest (contact detected) or not (supported, not detected). */
    public var onChest: Boolean = true

    /** RR intervals (1/1024 s) to put in the next notification instead of the generated ones; consumed once. */
    public var nextRrRaw: List<Int>? = null

    override val characteristics: List<GattCharacteristicInfo> =
        listOf(
            GattCharacteristicInfo(HeartRateUuids.SERVICE, HeartRateUuids.MEASUREMENT, setOf(GattProperty.NOTIFY)),
            GattCharacteristicInfo(
                HeartRateUuids.SERVICE,
                HeartRateUuids.BODY_SENSOR_LOCATION,
                setOf(GattProperty.READ),
            ),
        )

    override fun onRead(characteristic: GattUuid): ByteArray? =
        if (characteristic == HeartRateUuids.BODY_SENSOR_LOCATION) byteArrayOf(SENSOR_LOCATION_CHEST) else null

    override fun onConnected(connection: FakeConnection) {
        connection.scope.launch {
            while (true) {
                delay(options.notifyInterval)
                connection.push(HeartRateUuids.MEASUREMENT, measurement())
            }
        }
    }

    private fun measurement(): ByteArray {
        val contactBits =
            when {
                !options.reportsContact -> 0
                onChest -> CONTACT_DETECTED
                else -> CONTACT_NOT_DETECTED
            }
        val rr =
            when {
                !options.sendsRr -> null
                nextRrRaw != null -> nextRrRaw.also { nextRrRaw = null }
                bpm > 0 -> generatedRr()
                else -> emptyList()
            }
        return HeartRateEncoder.encode(bpm, contactBits, rr)
    }

    /** About one second's worth of beats, each 60/bpm s long in 1/1024 s, varying by a few counts in a fixed pattern. */
    private fun generatedRr(): List<Int> {
        val beats = (bpm / SECONDS_PER_MINUTE).roundToInt().coerceAtLeast(1)
        val nominal = (SECONDS_PER_MINUTE / bpm * UNITS_PER_SECOND).roundToInt()
        return List(beats) { nominal + VARIATION[(beat++) % VARIATION.size] }
    }

    private companion object {
        const val DEFAULT_BPM = 140
        const val SECONDS_PER_MINUTE = 60.0
        const val UNITS_PER_SECOND = 1024
        const val CONTACT_DETECTED = 3
        const val CONTACT_NOT_DETECTED = 2
        const val SENSOR_LOCATION_CHEST: Byte = 1
        val VARIATION = intArrayOf(0, 6, -4, 9, -7, 2)
    }
}
