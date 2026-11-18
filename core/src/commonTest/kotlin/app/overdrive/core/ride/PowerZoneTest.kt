package app.overdrive.core.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PowerZoneTest {
    @Test
    fun `zone boundaries at FTP 200 are inclusive at the top`() {
        val expected =
            listOf(
                0 to PowerZone.Z1,
                109 to PowerZone.Z1,
                110 to PowerZone.Z1, // exactly 55%
                111 to PowerZone.Z2, // 55.5%, in the gap of Coggan's whole-percent table
                150 to PowerZone.Z2, // exactly 75%
                151 to PowerZone.Z3,
                180 to PowerZone.Z3, // 90%
                181 to PowerZone.Z4,
                210 to PowerZone.Z4, // 105%
                211 to PowerZone.Z5,
                240 to PowerZone.Z5, // 120%
                241 to PowerZone.Z6,
                300 to PowerZone.Z6, // 150%
                301 to PowerZone.Z7,
                1500 to PowerZone.Z7,
            )
        for ((watts, zone) in expected) {
            assertEquals(zone, PowerZone.of(watts, 200), "$watts W at FTP 200")
        }
    }

    @Test
    fun `every zone boundary holds for every FTP`() {
        for (ftp in 50..500) {
            for (zone in PowerZone.entries) {
                val upper = zone.upperPercentOfFtp ?: continue
                // The highest whole watt that is still at or below the printed upper bound.
                val highest = upper * ftp / 100
                assertEquals(zone, PowerZone.of(highest, ftp), "$highest W at FTP $ftp")
                assertEquals(
                    PowerZone.entries[zone.ordinal + 1],
                    PowerZone.of(highest + 1, ftp),
                    "${highest + 1} W at FTP $ftp",
                )
            }
        }
    }

    @Test
    fun `zone numbers run from 1 to 7 and bounds increase`() {
        assertEquals((1..7).toList(), PowerZone.entries.map { it.number })
        val bounds = PowerZone.entries.mapNotNull { it.upperPercentOfFtp }
        assertEquals(bounds.sorted(), bounds)
        assertEquals(null, PowerZone.Z7.upperPercentOfFtp)
    }

    @Test
    fun `negative power is treated as zero`() {
        assertEquals(PowerZone.Z1, PowerZone.of(-5, 200))
    }

    @Test
    fun `FTP must be positive`() {
        assertFailsWith<IllegalArgumentException> { PowerZone.of(100, 0) }
        assertFailsWith<IllegalArgumentException> { PowerZone.of(100, -1) }
    }
}
