package app.overdrive.core.click.integration

import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.fake.FakeClick
import app.overdrive.core.ble.integration.BleRide
import app.overdrive.core.click.ClickDevice
import app.overdrive.core.click.ClickOptions
import app.overdrive.core.click.addClick
import app.overdrive.core.click.click
import app.overdrive.core.control.ActionRouter
import app.overdrive.core.control.ActionRouterTest
import app.overdrive.core.control.ButtonMapping
import app.overdrive.core.control.ClickAction
import app.overdrive.core.control.GestureEvent
import app.overdrive.core.control.GestureTiming
import app.overdrive.core.control.HandlebarControls
import app.overdrive.core.device.collectAll
import app.overdrive.core.ride.RideSettings
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope

/** Four one-minute steps: ERG at 50 % FTP, ERG at 80 % FTP, then two free-ride steps. At FTP 200: 100 W, 160 W, gears. */
internal val clickWorkout =
    Workout(
        schema = 1,
        id = "click-ride",
        name = "Click ride",
        steps =
            listOf(
                SteadyStep(duration = 60, target = 0.5, label = "A"),
                SteadyStep(duration = 60, target = 0.8, label = "B"),
                FreeStep(duration = 60, label = "C"),
                FreeStep(duration = 60, label = "D"),
            ),
    )

/**
 * The whole chain of spec §4.7 over the simulated Bluetooth: the real `RideEngine` over a fake trainer and strap, **and** a
 * fake Click through `ClickDevice`, `HandlebarControls` (mapping, gestures) and `ActionRouter` into the engine, with a media
 * recorder for the keys. Everything but the Android transport and the Android media keys, in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ClickRide(
    testScope: TestScope,
    workout: Workout? = clickWorkout,
    val fake: FakeClick = FakeClick(),
    clickOptions: ClickOptions = ClickOptions(),
    var mapping: ButtonMapping = ButtonMapping.DEFAULT,
    timing: GestureTiming = GestureTiming(),
) {
    val ride = BleRide(testScope, BleRig(testScope), workout, RideSettings(ftp = 200))
    val rig: BleRig get() = ride.rig
    val media = ActionRouterTest.RecordingMedia()
    val click: ClickDevice

    init {
        rig.addClick(fake)
        click = rig.click(clickOptions)
    }

    private val router = ActionRouter(ride.engine, media)
    val controls =
        HandlebarControls(
            scope = testScope.backgroundScope,
            changes = click.buttons,
            mapping = { mapping },
            onAction = { router.dispatch(it) },
            timing = timing,
            nowMillis = { testScope.testScheduler.currentTime },
        )

    /** Every action the gesture layer decided on, in order. */
    val gestures: List<GestureEvent> = testScope.collectAll(controls.actions)

    init {
        controls.start()
        click.start()
        rig.advance(1_000)
    }

    val actions: List<ClickAction> get() = gestures.map { it.action }

    /** A quick press and release of the buttons of [mask], then [afterMs] for everything to settle. */
    fun tap(
        mask: Int,
        heldMs: Long = 80,
        afterMs: Long = 400,
    ) {
        fake.press(mask)
        rig.advance(heldMs)
        fake.release(mask)
        rig.advance(afterMs)
    }

    /** A press held for [heldMs] (past the threshold by default), then [afterMs]. */
    fun hold(
        mask: Int,
        heldMs: Long = 700,
        afterMs: Long = 400,
    ) = tap(mask, heldMs, afterMs)
}
