package app.overdrive.core.click

import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.ble.fake.FakeClick
import app.overdrive.core.ble.link.BackoffPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.testTimeSource

/** An opaque test id: nothing here resembles a hardware address. */
internal val CLICK_ID = DeviceId("test-click")

/** Adds a simulated Click to the world of this rig and returns it. */
internal fun BleRig.addClick(fake: FakeClick = FakeClick()): FakeClick {
    ble.add(CLICK_ID, fake)
    return fake
}

/** A [ClickDevice] over this rig's simulated Bluetooth, with exact (jitter-free) backoff on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun BleRig.click(
    options: ClickOptions = ClickOptions(),
    reconnect: BackoffPolicy = BackoffPolicy.EXACT,
    factory: GattTransportFactory = ble,
): ClickDevice =
    ClickDevice(
        scope = testScope.backgroundScope,
        device = CLICK_ID,
        transports = factory,
        environment = ble,
        name = "Click",
        options = options,
        reconnect = reconnect,
        timeSource = testScope.testTimeSource,
    )

/** Starts [this] and lets the first connection come up (a second of virtual time). */
internal fun ClickDevice.startAndSettle(rig: BleRig): ClickDevice {
    start()
    rig.advance(1_000)
    return this
}
