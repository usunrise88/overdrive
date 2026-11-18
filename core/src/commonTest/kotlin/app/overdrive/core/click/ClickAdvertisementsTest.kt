package app.overdrive.core.click

import app.overdrive.core.ble.Advertisement
import app.overdrive.core.ble.ClickUuids
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.FtmsUuids
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.ManufacturerData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recognising a Click v2 in a scan (docs/protocols/click-v2.md §1, §2). The advertisements are synthetic. */
class ClickAdvertisementsTest {
    private fun advertisement(
        services: Set<GattUuid> = setOf(ClickUuids.SERVICE),
        manufacturer: List<ManufacturerData> = listOf(ManufacturerData(0x094A, byteArrayOf(0x0A))),
        name: String? = "Click",
    ) = Advertisement(DeviceId("test-click"), name, services, -55, manufacturer)

    @Test
    fun `the right puck is the service plus company 0x094A plus 0x0A`() {
        assertEquals(ClickMatch(ClickSide.RIGHT, ClickService.CURRENT), ClickAdvertisements.recognise(advertisement()))
    }

    @Test
    fun `the left puck is recognised but not wanted by default, and wanted when asked`() {
        val left = advertisement(manufacturer = listOf(ManufacturerData(0x094A, byteArrayOf(0x0B))))
        assertNull(ClickAdvertisements.recognise(left))
        val both = ClickOptions(acceptedSides = setOf(ClickSide.RIGHT, ClickSide.LEFT))
        assertEquals(ClickMatch(ClickSide.LEFT, ClickService.CURRENT), ClickAdvertisements.recognise(left, both))
    }

    @Test
    fun `the device-type bytes are options, so a bench that finds them swapped is one change`() {
        val zeroB = advertisement(manufacturer = listOf(ManufacturerData(0x094A, byteArrayOf(0x0B))))
        val swapped = ClickOptions(rightSideByte = 0x0B, leftSideByte = 0x0A)
        assertEquals(ClickSide.RIGHT, ClickAdvertisements.recognise(zeroB, swapped)?.side)
    }

    @Test
    fun `only the first byte of the payload decides, the rest may be anything`() {
        val long = advertisement(manufacturer = listOf(ManufacturerData(0x094A, byteArrayOf(0x0A, 1, 2, 3, 4, 5, 6))))
        assertEquals(ClickSide.RIGHT, ClickAdvertisements.recognise(long)?.side)
    }

    @Test
    fun `other Zwift devices and other companies are not a Click v2`() {
        for (type in listOf(0x09, 0x02, 0x03, 0x07, 0x08, 0x00, 0xFF)) {
            val other = advertisement(manufacturer = listOf(ManufacturerData(0x094A, byteArrayOf(type.toByte()))))
            assertNull(ClickAdvertisements.recognise(other), "type $type")
        }
        assertNull(
            ClickAdvertisements.recognise(
                advertisement(manufacturer = listOf(ManufacturerData(0x004C, byteArrayOf(0x0A)))),
            ),
        )
        assertNull(ClickAdvertisements.recognise(advertisement(manufacturer = emptyList())))
        assertNull(
            ClickAdvertisements.recognise(advertisement(manufacturer = listOf(ManufacturerData(0x094A, ByteArray(0))))),
        )
    }

    @Test
    fun `the name is never used`() {
        assertNull(ClickAdvertisements.recognise(advertisement(name = "Zwift Click", manufacturer = emptyList())))
        assertEquals(ClickSide.RIGHT, ClickAdvertisements.recognise(advertisement(name = null))?.side)
    }

    @Test
    fun `the service is checked first, and the older one is a fallback`() {
        assertNull(ClickAdvertisements.recognise(advertisement(services = setOf(FtmsUuids.SERVICE))))
        assertNull(ClickAdvertisements.recognise(advertisement(services = emptySet())))
        val legacy = advertisement(services = setOf(ClickUuids.LEGACY_SERVICE))
        assertEquals(ClickMatch(ClickSide.RIGHT, ClickService.LEGACY), ClickAdvertisements.recognise(legacy))
        assertNull(ClickAdvertisements.recognise(legacy, ClickOptions(acceptLegacyService = false)))
    }

    @Test
    fun `the current service wins when both are advertised`() {
        val both = advertisement(services = setOf(ClickUuids.LEGACY_SERVICE, ClickUuids.SERVICE))
        assertEquals(ClickService.CURRENT, ClickAdvertisements.recognise(both)?.service)
    }

    @Test
    fun `without the service requirement the manufacturer data alone is enough`() {
        val bare = advertisement(services = emptySet())
        assertNull(ClickAdvertisements.recognise(bare))
        val relaxed = ClickOptions(requireAdvertisedService = false)
        assertEquals(ClickMatch(ClickSide.RIGHT, null), ClickAdvertisements.recognise(bare, relaxed))
    }

    @Test
    fun `the scan filter lists the services of the options, or everything`() {
        assertEquals(
            setOf(ClickUuids.SERVICE, ClickUuids.LEGACY_SERVICE),
            ClickAdvertisements.scanFilter().serviceUuids,
        )
        assertEquals(
            setOf(ClickUuids.SERVICE),
            ClickAdvertisements.scanFilter(ClickOptions(acceptLegacyService = false)).serviceUuids,
        )
        assertTrue(
            ClickAdvertisements.scanFilter(ClickOptions(requireAdvertisedService = false)).serviceUuids.isEmpty(),
        )
    }

    @Test
    fun `the UUIDs are the notes' UUIDs`() {
        assertEquals(GattUuid.parse("0000FC82-0000-1000-8000-00805F9B34FB"), ClickUuids.SERVICE)
        assertEquals(GattUuid.parse("00000001-19CA-4651-86E5-FA29DCDD09D1"), ClickUuids.LEGACY_SERVICE)
        assertEquals(GattUuid.parse("00000002-19CA-4651-86E5-FA29DCDD09D1"), ClickUuids.ASYNC)
        assertEquals(GattUuid.parse("00000003-19CA-4651-86E5-FA29DCDD09D1"), ClickUuids.SYNC_RX)
        assertEquals(GattUuid.parse("00000004-19CA-4651-86E5-FA29DCDD09D1"), ClickUuids.SYNC_TX)
        assertEquals("FC82", ClickUuids.SERVICE.shortForm)
        assertEquals(2378, ClickUuids.MANUFACTURER_ID)
    }

    @Test
    fun `manufacturer data compares by content and never prints its bytes`() {
        val a = ManufacturerData(0x094A, byteArrayOf(0x0A, 0x7F))
        assertEquals(a, ManufacturerData(0x094A, byteArrayOf(0x0A, 0x7F)))
        assertNotEquals(a, ManufacturerData(0x094A, byteArrayOf(0x0A)))
        assertEquals("ManufacturerData(0x94a, 2 bytes)", a.toString())
        assertEquals(0x0A, a.firstByte)
        val source = byteArrayOf(1, 2)
        val copy = ManufacturerData(1, source)
        source[0] = 9
        assertEquals(1, copy.payload[0], "the payload is copied in")
        copy.payload[0] = 7
        assertEquals(1, copy.payload[0], "and copied out")
    }
}
