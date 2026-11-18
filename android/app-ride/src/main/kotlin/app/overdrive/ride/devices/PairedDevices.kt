package app.overdrive.ride.devices

import app.overdrive.core.ble.BleEnvironment
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.ble.adapter.FtmsOptions
import app.overdrive.core.ble.adapter.FtmsTrainerDevice
import app.overdrive.core.ble.adapter.HeartRateOptions
import app.overdrive.core.ble.adapter.HeartRateStrapDevice
import app.overdrive.core.ble.link.BackoffPolicy
import app.overdrive.core.ble.link.LinkControls
import app.overdrive.core.ble.recording.BleRecordingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * The trainer and the strap the rider paired, as **long-lived devices** (ADR 0010): each is a `FtmsTrainerDevice` or
 * `HeartRateStrapDevice` with its own reconnecting `LinkSupervisor`, owned by the container's scope and **not by any ride**.
 * They connect when the app starts (the supervisors wait by themselves until Bluetooth is on and the permission is granted,
 * and connect at once when it is), keep their links between rides, and survive a ride session being thrown away: a session
 * only *uses* them, and closing a session never closes a device. Only [forget], [pair] and [close] do.
 *
 * Pairing a device replaces the old one of its role cleanly: the old device is closed (its transport and its platform
 * object with it) and a new one is started for the new id. Pairing the device that is already paired changes nothing.
 *
 * [changes] counts every change of what is paired, including the first load from the store, so the ride host can rebuild a
 * session that was prepared over the previous devices (`RideHost`'s ride triggers).
 *
 * Everything runs on [scope], which is never the main thread. [pair] and [forget] are safe from any thread; they return
 * once the store has been written and the devices swapped.
 *
 * @param transports the transport factory of a role: the recording decorator (ADR 0010) is a different one for each.
 */
class PairedDevices(
    private val scope: CoroutineScope,
    private val store: DeviceStore,
    private val environment: BleEnvironment,
    private val transports: (DeviceRole) -> GattTransportFactory,
    private val ftmsOptions: FtmsOptions = FtmsOptions(),
    private val heartRateOptions: HeartRateOptions = HeartRateOptions(),
    private val reconnect: BackoffPolicy = BackoffPolicy(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val random: Random = Random.Default,
) {
    /** A paired trainer: what the rider chose and the device that is connecting to it. */
    class PairedTrainer(
        val remembered: RememberedDevice,
        val device: FtmsTrainerDevice,
    )

    /** A paired strap. */
    class PairedStrap(
        val remembered: RememberedDevice,
        val device: HeartRateStrapDevice,
    )

    private val lock = Mutex()
    private val mutableTrainer = MutableStateFlow<PairedTrainer?>(null)
    private val mutableStrap = MutableStateFlow<PairedStrap?>(null)
    private val mutableLoaded = MutableStateFlow(false)
    private val mutableChanges = MutableStateFlow(0)

    /** The paired trainer, or null. It is started: it is connecting, connected or waiting to retry. */
    val trainer: StateFlow<PairedTrainer?> = mutableTrainer.asStateFlow()

    /** The paired strap, or null. */
    val strap: StateFlow<PairedStrap?> = mutableStrap.asStateFlow()

    /** False until the remembered devices have been read from the store: "paired or not" is not known before. */
    val loaded: StateFlow<Boolean> = mutableLoaded.asStateFlow()

    /** Goes up by one at every change of what is paired, the first load included. */
    val changes: StateFlow<Int> = mutableChanges.asStateFlow()

    /** The link controls of a role's device (Retry), or null when nothing is paired. */
    fun controls(role: DeviceRole): LinkControls? =
        when (role) {
            DeviceRole.Trainer -> mutableTrainer.value?.device
            DeviceRole.Strap -> mutableStrap.value?.device
        }

    /** Reads the store and starts a device for everything remembered: the "connect on launch without user action". */
    fun start() {
        scope.launch {
            lock.withLock {
                val remembered = store.read()
                applyTrainer(remembered.trainer)
                applyStrap(remembered.strap)
                mutableLoaded.value = true
                mutableChanges.value++
            }
        }
    }

    /** Remembers [device] for [role] and starts connecting to it; the previous device of that role is closed. */
    suspend fun pair(
        role: DeviceRole,
        device: RememberedDevice,
    ) {
        lock.withLock {
            val clean = device.copy(name = cleanDeviceName(device.name, role))
            store.remember(role, clean)
            val changed = if (role == DeviceRole.Trainer) applyTrainer(clean) else applyStrap(clean)
            if (changed) mutableChanges.value++
        }
    }

    /** Forgets the device of [role] and closes it. */
    suspend fun forget(role: DeviceRole) {
        lock.withLock {
            store.forget(role)
            val changed = if (role == DeviceRole.Trainer) applyTrainer(null) else applyStrap(null)
            if (changed) mutableChanges.value++
        }
    }

    /** Closes both devices. Nothing is forgotten: the next launch connects to them again. */
    fun close() {
        mutableTrainer.getAndUpdate { null }?.device?.close()
        mutableStrap.getAndUpdate { null }?.device?.close()
    }

    /** Makes the paired trainer [remembered]. Returns whether the device behind it changed (added, replaced or removed). */
    private fun applyTrainer(remembered: RememberedDevice?): Boolean {
        val current = mutableTrainer.value
        if (current == null && remembered == null) return false
        if (current != null && current.remembered.id == remembered?.id) {
            if (current.remembered != remembered) mutableTrainer.value = PairedTrainer(remembered, current.device)
            return false
        }
        current?.device?.close()
        mutableTrainer.value =
            remembered?.let {
                val device =
                    FtmsTrainerDevice(
                        scope = scope,
                        device = it.id,
                        transports = transports(DeviceRole.Trainer),
                        environment = environment,
                        name = shortName(it, DeviceRole.Trainer),
                        options = ftmsOptions,
                        reconnect = reconnect,
                        timeSource = timeSource,
                        random = random,
                    )
                device.start()
                PairedTrainer(it, device)
            }
        return true
    }

    /** As [applyTrainer], for the strap. */
    private fun applyStrap(remembered: RememberedDevice?): Boolean {
        val current = mutableStrap.value
        if (current == null && remembered == null) return false
        if (current != null && current.remembered.id == remembered?.id) {
            if (current.remembered != remembered) mutableStrap.value = PairedStrap(remembered, current.device)
            return false
        }
        current?.device?.close()
        mutableStrap.value =
            remembered?.let {
                val device =
                    HeartRateStrapDevice(
                        scope = scope,
                        device = it.id,
                        transports = transports(DeviceRole.Strap),
                        environment = environment,
                        name = shortName(it, DeviceRole.Strap),
                        options = heartRateOptions,
                        reconnect = reconnect,
                        timeSource = timeSource,
                        random = random,
                    )
                device.start()
                PairedStrap(it, device)
            }
        return true
    }

    /**
     * The name the ride record's device list holds: the advertised name made safe by core's recording policy (ASCII, no
     * serial-like word, 24 characters at most), or the role's plain name. Never the address, which is not in
     * [RememberedDevice.name] anyway.
     */
    private fun shortName(
        device: RememberedDevice,
        role: DeviceRole,
    ): String = BleRecordingPolicy.sanitizeModel(device.name) ?: role.fallbackName
}
