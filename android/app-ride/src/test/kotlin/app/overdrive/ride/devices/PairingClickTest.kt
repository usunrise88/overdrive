package app.overdrive.ride.devices

import app.overdrive.core.ble.ClickUuids
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.ManufacturerData
import app.overdrive.core.ble.fake.FakePeripheral
import app.overdrive.core.click.ClickOptions
import app.overdrive.core.click.ClickSide
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A controller that advertises its manufacturer data and no service: what a scan finds only without the service filter. */
private class ManufacturerOnlyClick(
    sideByte: Int,
) : FakePeripheral(advertisedName = null, advertisedServices = emptySet()) {
    override val characteristics: List<GattCharacteristicInfo> = emptyList()

    init {
        manufacturerData = listOf(ManufacturerData(ClickUuids.MANUFACTURER_ID, byteArrayOf(sideByte.toByte())))
    }
}

/**
 * Pairing the Click from the Devices screen's scan (ADR 0012, spec §4.7): it is told by its manufacturer data and its
 * service, never by its name; its side is part of what the list says; only the right puck is offered unless the developer
 * setting says otherwise; and it is paired, marked and forgotten like the other two, but never during a ride.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingClickTest {
    private fun TestScope.controller(
        world: BleWorld,
        options: () -> ClickOptions = { ClickOptions() },
        canChange: () -> Boolean = { true },
    ) = PairingController(
        backgroundScope,
        world.ble,
        world.devices,
        canChange,
        timeSource = testTimeSource,
        clickOptions = options,
    )

    @Test
    fun aScanListsTheRightClickByItsSideAndNotTheLeftOne() =
        runTest {
            val world = BleWorld(this)
            val pairing = controller(world)
            pairing.scan()
            world.advance(1_500)
            val ui = pairing.state.value
            assertEquals(listOf("Zwift Click (right)"), ui.clicks.map { it.name })
            assertEquals(setOf(DeviceRole.Click), ui.clicks.map { it.role }.toSet())
            assertTrue(
                "the Click is never listed as a trainer or a strap",
                ui.clicks.none {
                    it in
                        ui.trainers + ui.straps
                },
            )
            assertEquals(listOf("D500", "Other D500"), ui.trainers.map { it.name })
            assertTrue(ui.clicks.none { it.paired })
        }

    @Test
    fun theDeveloperSettingForEitherPuckListsTheLeftOneToo() =
        runTest {
            val world = BleWorld(this)
            val pairing =
                controller(world, options = { ClickOptions(acceptedSides = setOf(ClickSide.RIGHT, ClickSide.LEFT)) })
            pairing.scan()
            world.advance(1_500)
            assertEquals(
                listOf("Zwift Click (left)", "Zwift Click (right)"),
                pairing.state.value.clicks
                    .map { it.name },
            )
        }

    @Test
    fun aFoundClickIsPairedMarkedAndForgottenLikeTheOtherDevices() =
        runTest {
            val world = BleWorld(this)
            world.devices.start()
            val pairing = controller(world)
            pairing.scan()
            world.advance(1_500)
            pairing.pair(
                pairing.state.value.clicks
                    .single(),
            )
            world.advance(2_000)
            val paired = checkNotNull(world.devices.click.value)
            assertEquals(CLICK_ID, paired.remembered.id)
            assertEquals("Zwift Click (right)", paired.remembered.name)
            assertEquals(CLICK_ID, world.store.read().click?.id)
            assertTrue(
                "the list marks it",
                pairing.state.value.clicks
                    .single()
                    .paired,
            )
            assertNull("no trainer was paired by it", world.devices.trainer.value)

            pairing.forget(DeviceRole.Click)
            world.advance(500)
            assertNull(world.devices.click.value)
            assertNull(world.store.read().click)
            assertTrue(pairing.state.value.clicks.none { it.paired })
        }

    @Test
    fun aRideThatIsLiveNeverHasItsClickChanged() =
        runTest {
            val world = BleWorld(this)
            world.launchPaired(withClick = true)
            var live = true
            val pairing = controller(world, canChange = { !live })
            pairing.scan()
            world.advance(1_500)
            val before = world.devices.click.value
            pairing.forget(DeviceRole.Click)
            world.advance(500)
            assertEquals("not forgotten during a ride", before, world.devices.click.value)

            live = false
            pairing.forget(DeviceRole.Click)
            world.advance(500)
            assertNull(world.devices.click.value)
        }

    @Test
    fun withoutTheServiceFilterAClickThatAdvertisesOnlyItsManufacturerDataIsStillFound() =
        runTest {
            val world = BleWorld(this)
            world.ble.add(DeviceId("test-click-quiet"), ManufacturerOnlyClick(sideByte = 0x0A))
            // With the service required (the default) a controller that advertises no service is not a Click.
            val strict = controller(world)
            strict.scan()
            world.advance(1_500)
            assertEquals(1, strict.state.value.clicks.size)
            strict.stop()

            val lenient = controller(world, options = { ClickOptions(requireAdvertisedService = false) })
            lenient.scan()
            world.advance(1_500)
            assertEquals(
                listOf("Zwift Click (right)", "Zwift Click (right)"),
                lenient.state.value.clicks
                    .map { it.name },
            )
            lenient.stop()
        }
}
