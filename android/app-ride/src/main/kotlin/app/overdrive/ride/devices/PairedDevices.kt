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
import app.overdrive.core.click.ButtonChange
import app.overdrive.core.click.ClickDevice
import app.overdrive.core.click.ClickOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * The trainer, the strap and the Click the rider paired, as **long-lived devices** (ADR 0010, ADR 0012): each is a
 * `FtmsTrainerDevice`, `HeartRateStrapDevice` or `ClickDevice` with its own reconnecting `LinkSupervisor`, owned by the
 * container's scope and **not by any ride**. They connect when the app starts (the supervisors wait by themselves until Bluetooth is on and the permission is granted,
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
    /**
     * The Click's connection options, which the developer settings change at the bench (ADR 0012): a new value replaces
     * the Click's device, so it reconnects with the new options; it does not touch a ride. The first value is waited for
     * before the remembered Click starts, so it never connects once with the wrong ones.
     */
    private val clickOptions: Flow<ClickOptions> = flowOf(ClickOptions()),
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

    /** A paired Click: what the rider chose and the device that is connecting to it. */
    class PairedClick(
        val remembered: RememberedDevice,
        val device: ClickDevice,
    )

    private val lock = Mutex()
    private val mutableTrainer = MutableStateFlow<PairedTrainer?>(null)
    private val mutableStrap = MutableStateFlow<PairedStrap?>(null)
    private val mutableClick = MutableStateFlow<PairedClick?>(null)
    private val mutableLoaded = MutableStateFlow(false)
    private val mutableChanges = MutableStateFlow(0)
    private val mutableClickButtons =
        MutableSharedFlow<ButtonChange>(
            extraBufferCapacity = BUTTON_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    // Touched under [lock] only, except [clickHeld], which the forwarder writes and a swap reads.
    private var currentClickOptions: ClickOptions = ClickOptions()
    private var clickForwarder: Job? = null

    @Volatile
    private var clickHeld = 0

    /** The paired trainer, or null. It is started: it is connecting, connected or waiting to retry. */
    val trainer: StateFlow<PairedTrainer?> = mutableTrainer.asStateFlow()

    /** The paired strap, or null. */
    val strap: StateFlow<PairedStrap?> = mutableStrap.asStateFlow()

    /**
     * The paired Click, or null. It is started like the others, **but it is not a ride input**: it never counts in
     * [changes], because no ride is built from it (spec §4.7: the ride never depends on the Click).
     */
    val click: StateFlow<PairedClick?> = mutableClick.asStateFlow()

    /**
     * The button changes of **whichever Click is paired**, one hot flow for the life of the container: it is fed from the
     * current device, and a device that is replaced, forgotten or closed while buttons were down releases them first
     * (a synthetic change), so the gesture layer, which collects this once, never has to follow the device. A change
     * carries the whole button state, so a collector that missed one recovers from the next.
     */
    val clickButtons: SharedFlow<ButtonChange> = mutableClickButtons.asSharedFlow()

    /** False until the remembered devices have been read from the store: "paired or not" is not known before. */
    val loaded: StateFlow<Boolean> = mutableLoaded.asStateFlow()

    /** Goes up by one at every change of what is paired, the first load included. */
    val changes: StateFlow<Int> = mutableChanges.asStateFlow()

    /** The link controls of a role's device (Retry), or null when nothing is paired. */
    fun controls(role: DeviceRole): LinkControls? =
        when (role) {
            DeviceRole.Trainer -> mutableTrainer.value?.device
            DeviceRole.Strap -> mutableStrap.value?.device
            DeviceRole.Click -> mutableClick.value?.device
        }

    /**
     * Reads the store and starts a device for everything remembered: the "connect on launch without user action". Also
     * follows the Click's options for as long as the scope lives.
     */
    fun start() {
        scope.launch {
            // The first options before anything is started, so the Click never connects once with the wrong ones.
            val first = clickOptions.first()
            lock.withLock {
                currentClickOptions = first
                val remembered = store.read()
                applyTrainer(remembered.trainer)
                applyStrap(remembered.strap)
                applyClick(remembered.click)
                mutableLoaded.value = true
                mutableChanges.value++
            }
            clickOptions.drop(1).collect { options ->
                lock.withLock {
                    currentClickOptions = options
                    // The same device, started again with the new options: a reconnect, never a ride change.
                    mutableClick.value?.let { applyClick(it.remembered, restart = true) }
                }
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
            when (role) {
                DeviceRole.Trainer -> if (applyTrainer(clean)) mutableChanges.value++
                DeviceRole.Strap -> if (applyStrap(clean)) mutableChanges.value++
                DeviceRole.Click -> applyClick(clean)
            }
        }
    }

    /** Forgets the device of [role] and closes it. */
    suspend fun forget(role: DeviceRole) {
        lock.withLock {
            store.forget(role)
            when (role) {
                DeviceRole.Trainer -> if (applyTrainer(null)) mutableChanges.value++
                DeviceRole.Strap -> if (applyStrap(null)) mutableChanges.value++
                DeviceRole.Click -> applyClick(null)
            }
        }
    }

    /** Closes the devices. Nothing is forgotten: the next launch connects to them again. */
    fun close() {
        mutableTrainer.getAndUpdate { null }?.device?.close()
        mutableStrap.getAndUpdate { null }?.device?.close()
        mutableClick.getAndUpdate { null }?.device?.close()
        endClickForwarding()
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
     * Makes the paired Click [remembered]: starts a device for it, closes the old one when it is a different puck (or
     * [restart], for new options), or removes it. Never touches [changes]: a ride does not depend on the Click.
     */
    private fun applyClick(
        remembered: RememberedDevice?,
        restart: Boolean = false,
    ) {
        val current = mutableClick.value
        if (current == null && remembered == null) return
        if (!restart && current != null && current.remembered.id == remembered?.id) {
            if (current.remembered != remembered) mutableClick.value = PairedClick(remembered, current.device)
            return
        }
        // The old device goes first: its transport is closed before a new one is opened, and a button it held is released.
        current?.device?.close()
        endClickForwarding()
        mutableClick.value =
            remembered?.let {
                val device =
                    ClickDevice(
                        scope = scope,
                        device = it.id,
                        transports = transports(DeviceRole.Click),
                        environment = environment,
                        name = shortName(it, DeviceRole.Click),
                        options = currentClickOptions,
                        reconnect = reconnect,
                        timeSource = timeSource,
                        random = random,
                    )
                // Collecting starts before the device does, so not one frame is missed.
                clickForwarder =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        device.buttons.collect {
                            clickHeld = it.held
                            mutableClickButtons.tryEmit(it)
                        }
                    }
                device.start()
                PairedClick(it, device)
            }
    }

    /** Stops following the old Click; a button it held down is released, because its release will never come. */
    private fun endClickForwarding() {
        clickForwarder?.cancel()
        clickForwarder = null
        val held = clickHeld
        clickHeld = 0
        if (held != 0) {
            mutableClickButtons.tryEmit(
                ButtonChange(pressed = 0, released = held, held = 0, timestampNanos = 0L, synthetic = true),
            )
        }
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

    private companion object {
        const val BUTTON_BUFFER = 64
    }
}
