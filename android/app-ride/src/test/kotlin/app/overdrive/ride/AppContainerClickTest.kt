package app.overdrive.ride

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.overdrive.core.ble.fake.FakeBle
import app.overdrive.core.ble.fake.FakeClick
import app.overdrive.core.ble.fake.FakeClickLock
import app.overdrive.core.ble.fake.FakeFtmsTrainer
import app.overdrive.core.click.ClickBits
import app.overdrive.core.click.ClickCodec
import app.overdrive.core.click.ClickState
import app.overdrive.core.click.UnlockSignal
import app.overdrive.core.control.ButtonMapping
import app.overdrive.core.control.ClickAction
import app.overdrive.core.control.HandlebarButton
import app.overdrive.core.ride.RideState
import app.overdrive.ride.devices.CLICK_ID
import app.overdrive.ride.devices.DeviceRole
import app.overdrive.ride.devices.FakeBleStack
import app.overdrive.ride.devices.OTHER_CLICK_ID
import app.overdrive.ride.devices.RememberedDevice
import app.overdrive.ride.devices.TRAINER_ID
import app.overdrive.ride.host.MediaController
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.rides.RideFixtures
import app.overdrive.ride.settings.ClickKeepAlive
import app.overdrive.uikit.DeviceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/** The media keys of the container, recording what the Click asked of them. */
private class RecordingMedia : MediaController {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun playPause() {
        calls += "playPause"
    }

    override fun volumeUp() {
        calls += "volumeUp"
    }

    override fun volumeDown() {
        calls += "volumeDown"
    }
}

/**
 * The Click inside the real container (ADR 0012), with real threads over a fake radio: no activity is ever created, so a
 * press that reaches the ride has come through the container's own scope, the way it does with a video app in front. The
 * ride is the real engine over a fake trainer; the media keys record. What only the tablet can settle (the foreground
 * service keeping the process alive with the screen off, a media key reaching a video app) is in the bench checklist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppContainerClickTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = RideFixtures.directory()
    private val logs = RideFixtures.directory()
    private val containers = mutableListOf<AppContainer>()
    private val worldScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val media = RecordingMedia()

    private class World(
        scope: CoroutineScope,
    ) {
        val ble = FakeBle(scope, nanoClock = System::nanoTime)
        val trainer = FakeFtmsTrainer()
        val click = FakeClick()
        val otherClick = FakeClick(advertisedName = "Other Click")
        val stack = FakeBleStack(ble)

        init {
            ble.add(TRAINER_ID, trainer)
            ble.add(CLICK_ID, click)
            ble.add(OTHER_CLICK_ID, otherClick)
        }
    }

    @After
    fun cleanUp() {
        containers.forEach { it.close() }
        worldScope.cancel()
        directory.deleteRecursively()
        logs.deleteRecursively()
    }

    private fun container(
        world: World,
        name: String,
    ): AppContainer =
        AppContainer(
            context,
            directory,
            settingsName = name,
            devicesName = "devices-$name",
            bleLogDirectory = File(logs, name),
            bleStack = { _, _ -> world.stack },
            outboxFile = File(logs, "$name-outbox.log"),
            mediaFactory = { media },
            // Robolectric's SystemClock is a fake that stands still; a hold needs real time.
            elapsedMillis = { System.nanoTime() / 1_000_000 },
        ).also { containers += it }

    private fun await(
        what: String,
        timeoutMs: Long = 20_000,
        condition: () -> Boolean,
    ) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun postHandshakes(world: World) =
        world.click.writes.count { it.bytes.contentEquals(ClickCodec.postHandshake()) }

    private fun AppContainer.clickState() =
        pairedDevices.click.value
            ?.device
            ?.state
            ?.value

    /** A trainer and a Click paired, both connected, and a free ride ready. */
    private fun ready(
        world: World,
        name: String,
    ): AppContainer {
        val c = container(world, name)
        await("the store is read") { c.pairedDevices.loaded.value }
        runBlocking {
            c.pairedDevices.pair(DeviceRole.Trainer, RememberedDevice(TRAINER_ID, "D500"))
            c.pairedDevices.pair(DeviceRole.Click, RememberedDevice(CLICK_ID, "Zwift Click (right)"))
        }
        await("the trainer is connected") { c.host.devices.value.trainer.status == DeviceStatus.Connected }
        await("the Click is delivering") { c.clickState() == ClickState.Active }
        c.host.select(RideChoice.Free)
        // READY with a gear is the engine's own first snapshot: a Start before it is not heard.
        await("a free ride is ready") {
            c.host.snapshots.value.state == RideState.READY && c.host.snapshots.value.gear != null
        }
        return c
    }

    private fun start(c: AppContainer) {
        c.host.actions.start()
        await("riding") { c.host.snapshots.value.state == RideState.RIDING }
    }

    private fun tap(
        click: FakeClick,
        mask: Int,
    ) {
        click.press(mask)
        Thread.sleep(80)
        click.release(mask)
        Thread.sleep(40)
    }

    private fun hold(
        click: FakeClick,
        mask: Int,
        millis: Long = 750,
    ) {
        click.press(mask)
        Thread.sleep(millis)
        click.release(mask)
        Thread.sleep(40)
    }

    @Test
    fun aPressOnTheClickReachesTheEngineWithNoActivityOnScreen() {
        val world = World(worldScope)
        val c = ready(world, "chain")
        assertFalse("no activity was ever started: the app is not on screen", c.visibility.visible.value)
        start(c)
        val neutral = checkNotNull(c.host.snapshots.value.gear)

        tap(world.click, ClickBits.PLUS)
        await("one gear harder") { c.host.snapshots.value.gear == neutral + 1 }
        tap(world.click, ClickBits.B)
        tap(world.click, ClickBits.B)
        await("two gears easier") { c.host.snapshots.value.gear == neutral - 1 }

        // The default mapping: a hold of Y pauses the ride and another resumes it.
        hold(world.click, ClickBits.Y)
        await("paused by the Click") { c.host.snapshots.value.state == RideState.PAUSED }
        hold(world.click, ClickBits.Y)
        await("resumed by the Click") { c.host.snapshots.value.state == RideState.RIDING }

        // And the media, which need no ride.
        tap(world.click, ClickBits.A)
        tap(world.click, ClickBits.Z)
        tap(world.click, ClickBits.Y)
        await("the media keys were pressed") { media.calls.size >= 3 }
        assertEquals(listOf("volumeUp", "volumeDown", "playPause"), media.calls.toList())
        assertFalse(c.visibility.visible.value)
        assertEquals(emptyList<String>(), world.ble.violations)
    }

    @Test
    fun theTenActionsAreCarriedOutByTheContainersOneRouter() {
        val world = World(worldScope)
        val c = ready(world, "ten")
        start(c)
        val neutral = checkNotNull(c.host.snapshots.value.gear)

        c.router.dispatch(ClickAction.GEAR_UP)
        await("gear up") { c.host.snapshots.value.gear == neutral + 1 }
        c.router.dispatch(ClickAction.GEAR_DOWN)
        await("gear down") { c.host.snapshots.value.gear == neutral }
        // A free ride has no intervals: the skips are the session's own no-ops.
        c.router.dispatch(ClickAction.NEXT_INTERVAL)
        c.router.dispatch(ClickAction.PREVIOUS_INTERVAL)
        for (action in listOf(ClickAction.PLAY_PAUSE_MEDIA, ClickAction.VOLUME_UP, ClickAction.VOLUME_UP_REPEAT)) {
            c.router.dispatch(action)
        }
        c.router.dispatch(ClickAction.VOLUME_DOWN)
        c.router.dispatch(ClickAction.VOLUME_DOWN_REPEAT)
        assertEquals(
            listOf("playPause", "volumeUp", "volumeUp", "volumeDown", "volumeDown"),
            media.calls.toList(),
        )
        c.router.dispatch(ClickAction.TOGGLE_RIDE_PAUSE)
        await("paused") { c.host.snapshots.value.state == RideState.PAUSED }
        c.router.dispatch(ClickAction.TOGGLE_RIDE_PAUSE)
        await("resumed") { c.host.snapshots.value.state == RideState.RIDING }
        assertEquals("the ride's own gear was never moved by the skips", neutral, c.host.snapshots.value.gear)
        assertEquals("ten actions in all", 10, ClickAction.entries.size)
    }

    @Test
    fun theRideScreensButtonsAndTheClickUseTheSameRule() {
        val world = World(worldScope)
        val c = ready(world, "rule")
        start(c)
        val neutral = checkNotNull(c.host.snapshots.value.gear)
        // The screen's button, then the Click's: both are core's router over the host's session.
        c.host.actions.levelUp()
        await("the screen's gear up") { c.host.snapshots.value.gear == neutral + 1 }
        tap(world.click, ClickBits.PLUS)
        await("the Click's gear up") { c.host.snapshots.value.gear == neutral + 2 }
    }

    @Test
    fun aRideThatEndsAndTheNextOneNeverCloseTheClick() {
        val world = World(worldScope)
        val c = ready(world, "lifetime")
        val device = c.pairedDevices.click.value
        val openClickLinks = { world.ble.transports.count { !it.isClosed } }
        val before = openClickLinks()
        start(c)
        c.host.actions.finish()
        await("the ride is finished") { c.host.snapshots.value.state == RideState.FINISHED }
        assertSame("the same device", device, c.pairedDevices.click.value)
        assertEquals(ClickState.Active, c.clickState())

        // The next ride is a new session over the same devices, and the Click drives it.
        val first = c.host.ui.value.rideNumber
        c.host.select(RideChoice.Free)
        await("a new ride is ready") {
            c.host.ui.value.rideNumber > first && c.host.snapshots.value.state == RideState.READY &&
                c.host.snapshots.value.gear != null
        }
        start(c)
        val neutral = checkNotNull(c.host.snapshots.value.gear)
        tap(world.click, ClickBits.PLUS)
        await("the Click drives the next ride") { c.host.snapshots.value.gear == neutral + 1 }
        assertSame(device, c.pairedDevices.click.value)
        assertEquals("no link was closed or opened by the sessions", before, openClickLinks())
    }

    @Test
    fun aClickThatIsLockedOrMissingNeverStopsTheRide() {
        val world = World(worldScope)
        world.click.lock = FakeClickLock.HIDDEN
        val c = container(world, "locked")
        await("the store is read") { c.pairedDevices.loaded.value }
        runBlocking {
            c.pairedDevices.pair(DeviceRole.Trainer, RememberedDevice(TRAINER_ID, "D500"))
            c.pairedDevices.pair(DeviceRole.Click, RememberedDevice(CLICK_ID, "Zwift Click (right)"))
        }
        await("the Click looks locked") {
            c.host.devices.value.click.locked == UnlockSignal.SERVICE_HIDDEN
        }
        assertEquals(DeviceStatus.Locked, c.host.devices.value.click.status)
        c.host.select(RideChoice.Free)
        await("the ride is ready") {
            c.host.snapshots.value.state == RideState.READY && c.host.snapshots.value.gear != null
        }
        start(c)
        c.host.actions.levelUp()
        c.host.actions.finish()
        await("the ride finished") { c.host.snapshots.value.state == RideState.FINISHED }
    }

    @Test
    fun aRepairedClickDrivesTheRideAndTheOldOneNoLonger() {
        val world = World(worldScope)
        val c = ready(world, "repair")
        start(c)
        val neutral = checkNotNull(c.host.snapshots.value.gear)

        runBlocking { c.pairedDevices.pair(DeviceRole.Click, RememberedDevice(OTHER_CLICK_ID, "Zwift Click (right)")) }
        await("the other Click is delivering") { c.clickState() == ClickState.Active }
        tap(world.otherClick, ClickBits.PLUS)
        await("the new Click drives the ride") { c.host.snapshots.value.gear == neutral + 1 }
        tap(world.click, ClickBits.PLUS)
        Thread.sleep(300)
        assertEquals("the old Click is not listened to any more", neutral + 1, c.host.snapshots.value.gear)
    }

    @Test
    fun aClickIsForgottenAndTheRideCarriesOn() {
        val world = World(worldScope)
        val c = ready(world, "forget")
        start(c)
        val neutral = checkNotNull(c.host.snapshots.value.gear)
        runBlocking { c.pairedDevices.forget(DeviceRole.Click) }
        await("the row is not paired") { c.host.devices.value.click.status == DeviceStatus.NotPaired }
        assertNull(c.pairedDevices.click.value)
        c.host.actions.levelUp()
        await("the on-screen button still works") { c.host.snapshots.value.gear == neutral + 1 }
        assertEquals(RideState.RIDING, c.host.snapshots.value.state)
    }

    // ---- Learn buttons through the container ----------------------------------------------------

    @Test
    fun learningTakesTheFiveButtonsKeepsTheRideStillAndTheMappingSurvivesARestart() {
        val world = World(worldScope)
        val first = ready(world, "learn")
        val neutral = checkNotNull(first.host.snapshots.value.gear)

        first.learning.begin()
        assertNotNull(first.learning.ui.value)
        // The default Plus is gear up: while learning it must not move the (ready) ride.
        val order = listOf(ClickBits.Y, ClickBits.Z, ClickBits.A, ClickBits.B, ClickBits.PLUS)
        for ((index, mask) in order.withIndex()) {
            tap(world.click, mask)
            if (index < order.lastIndex) await("step ${index + 2}") { first.learning.ui.value?.step == index + 2 }
        }
        await("all five learned") { first.learning.ui.value?.done == true }
        assertEquals("the ride did not move", neutral, first.host.snapshots.value.gear)
        val learned = checkNotNull(ButtonMapping.of(HandlebarButton.entries.zip(order).toMap()))
        await("the mapping is saved") { first.settings.value.buttonMapping == learned }

        // Done: the learned buttons work at once (Y is gear up now).
        first.learning.cancel()
        start(first)
        tap(world.click, ClickBits.Y)
        await("the learned Plus-role button is gear up") { first.host.snapshots.value.gear == neutral + 1 }
        first.close()
        containers.remove(first)

        // A restart: the settings file as it was left, under the name the next container uses.
        val datastore = File(context.filesDir, "datastore")
        File(datastore, "learn.preferences_pb").copyTo(File(datastore, "learn-again.preferences_pb"), overwrite = true)
        val second = container(world, "learn-again")
        await("the saved mapping is read") { second.settings.value.buttonMapping == learned }
    }

    @Test
    fun aRideThatGoesLiveEndsTheLesson() {
        val world = World(worldScope)
        val c = ready(world, "lesson")
        c.learning.begin()
        assertNotNull(c.learning.ui.value)
        c.host.actions.start()
        await("the lesson ended") { c.learning.ui.value == null }
        assertFalse(c.learning.active)
        // And it is not offered during the ride.
        c.learning.begin()
        assertNull(c.learning.ui.value)
    }

    // ---- Developer settings reach the Click ---------------------------------------------------------

    @Test
    fun aDeveloperSettingReplacesTheClickAndTheNewTimingGovernsTheGestures() {
        val world = World(worldScope)
        val c = ready(world, "options")
        start(c)
        val neutral = checkNotNull(c.host.snapshots.value.gear)
        val device = c.pairedDevices.click.value?.device
        assertEquals("FF 04 00 was written once", 1, postHandshakes(world))

        c.updateSettings {
            val changed =
                it.click.copy(sendPostHandshake = false, keepAlive = ClickKeepAlive.Every10, holdThresholdMillis = 1000)
            it.copy(click = changed)
        }
        await("a new Click device is connected") {
            val now = c.pairedDevices.click.value?.device
            now != null && now !== device && c.clickState() == ClickState.Active
        }
        assertEquals("the new connection did not write FF 04 00 again", 1, postHandshakes(world))
        assertEquals(2, world.click.handshakeCount)

        // A 750 ms press is a hold with the default 500 ms threshold and a tap with 1000 ms.
        hold(world.click, ClickBits.PLUS, millis = 750)
        await("the 750 ms press was a tap: gear up") { c.host.snapshots.value.gear == neutral + 1 }
    }
}
