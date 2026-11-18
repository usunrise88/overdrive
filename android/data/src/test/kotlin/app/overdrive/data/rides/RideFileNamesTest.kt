package app.overdrive.data.rides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class RideFileNamesTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun theNameIsTheLocalStartThenTheTitle() {
        assertEquals("2026-11-18 10-00 Sweet spot 3×10.fit", RideFileNames.fit("Sweet spot 3×10", TestRides.START, utc))
        assertEquals(
            "2026-11-18 11-00 Sweet spot 3×10.fit",
            RideFileNames.fit("Sweet spot 3×10", TestRides.START, ZoneId.of("Europe/Berlin")),
        )
    }

    @Test
    fun charactersFileSystemsRefuseBecomeHyphens() {
        val name = RideFileNames.fit("Tempo: 2/3 \"hard\" <now>?*|\\", TestRides.START, utc)
        assertEquals("2026-11-18 10-00 Tempo- 2-3 -hard- -now.fit", name)
        for (forbidden in "/\\:*?\"<>|") assertTrue(forbidden.toString(), forbidden !in name)
    }

    @Test
    fun anEmptyOrLongTitleStillGivesAUsableName() {
        assertEquals("2026-11-18 10-00 Ride.fit", RideFileNames.fit("  ..  ", TestRides.START, utc))
        val long = RideFileNames.fit("x".repeat(200), TestRides.START, utc)
        assertEquals("2026-11-18 10-00 ".length + 60 + ".fit".length, long.length)
    }

    @Test
    fun runsOfSpacesAreOneSpace() {
        assertEquals("2026-11-18 10-00 A B.fit", RideFileNames.fit("A    B", TestRides.START, utc))
    }
}
