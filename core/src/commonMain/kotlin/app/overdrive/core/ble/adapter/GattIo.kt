package app.overdrive.core.ble.adapter

import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattFailure
import app.overdrive.core.ble.GattFailureKind
import app.overdrive.core.ble.GattProperty
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.ValueUpdate
import app.overdrive.core.ble.link.LinkFailure
import app.overdrive.core.ble.link.LinkFailureKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Runs every GATT operation of one device **one at a time** and bounds each with a timeout, whoever asks: the platform
 * allows a single outstanding operation (docs/protocols/ftms-hr.md §7), and a callback that never comes must not hang a
 * set-up forever. The transport's own `BUSY` answer is therefore never provoked by an adapter.
 */
internal class GattIo(
    val transport: GattTransport,
    private val timeout: Duration,
) {
    private val lock = Mutex()

    suspend fun <T> run(block: suspend GattTransport.() -> GattResult<T>): GattResult<T> =
        lock.withLock {
            withTimeoutOrNull(timeout) { transport.block() } ?: GattResult.Failed(GattFailure(GattFailureKind.TIMEOUT))
        }
}

/** A failed set-up step as the supervisor reports it. */
internal fun setupFailure(
    step: String,
    failure: GattFailure,
): LinkFailure =
    LinkFailure(
        kind =
            when (failure.kind) {
                GattFailureKind.UNAVAILABLE -> LinkFailureKind.UNAVAILABLE
                else -> LinkFailureKind.SETUP_FAILED
            },
        status = failure.status.takeIf { it >= 0 },
        detail = "$step: ${failure.kind}",
    )

/**
 * How to subscribe to [characteristic]: the way the specification says ([preferred]), unless the discovered properties
 * offer only the other one, which some devices do (docs/protocols/ftms-hr.md §4.1).
 */
internal fun updateModeFor(
    characteristic: GattCharacteristicInfo,
    preferred: ValueUpdate,
): ValueUpdate {
    val properties = characteristic.properties
    val canNotify = GattProperty.NOTIFY in properties
    val canIndicate = GattProperty.INDICATE in properties
    return when {
        properties.isEmpty() -> preferred
        preferred == ValueUpdate.NOTIFICATION && !canNotify && canIndicate -> ValueUpdate.INDICATION
        preferred == ValueUpdate.INDICATION && !canIndicate && canNotify -> ValueUpdate.NOTIFICATION
        else -> preferred
    }
}
