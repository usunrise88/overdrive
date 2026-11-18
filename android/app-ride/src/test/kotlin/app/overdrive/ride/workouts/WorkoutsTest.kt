package app.overdrive.ride.workouts

import app.overdrive.core.ride.PowerZone
import app.overdrive.core.ride.flatSteps
import app.overdrive.core.workout.WorkoutImport
import app.overdrive.uikit.Zones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reading workout files, describing workouts for the Ready screen, and the bundled assets. */
class WorkoutsTest {
    private val moduleDir = File(System.getProperty("user.dir"))
    private val assets = File(moduleDir, "src/main/assets/workouts")
    private val fixtures = File(moduleDir, "../../contracts/fixtures")

    private fun asset(name: String) = File(assets, name).readText()

    private fun load(name: String) = (WorkoutFiles.parse(asset(name), name) as WorkoutFileResult.Loaded).workout

    @Test
    fun bundledAssetsAreTheContractFixturesWhereTheyCopyOne() {
        // The JSON asset is re-formatted by the repository's formatter, so compare what it means, not its bytes.
        val fixture = File(fixtures, "workouts/valid/sweet-spot-3x10.json").readText()
        assertEquals(WorkoutImport.fromJson(fixture), WorkoutImport.fromJson(asset("sweet-spot-3x10.json")))
        assertEquals(File(fixtures, "zwo/valid/all-elements.zwo").readText(), asset("all-elements.zwo"))
    }

    @Test
    fun everyBundledWorkoutParsesAndTheTestRideIsTenMinutes() {
        for (name in listOf("sweet-spot-3x10.json", "test-10-minutes.json", "all-elements.zwo")) load(name)
        assertEquals(600, load("test-10-minutes.json").flatSteps().sumOf { it.durationSeconds })
        assertEquals(3900, load("sweet-spot-3x10.json").flatSteps().sumOf { it.durationSeconds })
    }

    @Test
    fun jsonAndZwoAreToldApartByTheirFirstCharacter() {
        assertTrue(WorkoutFiles.parse(asset("sweet-spot-3x10.json"), "a.json") is WorkoutFileResult.Loaded)
        assertTrue(WorkoutFiles.parse("  \n" + asset("all-elements.zwo"), "a.zwo") is WorkoutFileResult.Loaded)
    }

    @Test
    fun invalidFilesGiveAClearErrorNamingTheFile() {
        val empty = WorkoutFiles.parse("   ", "empty.json") as WorkoutFileResult.Failed
        assertEquals("empty.json is not a workout", empty.error.title)
        assertTrue(empty.error.text.contains("empty"))

        val notJson = WorkoutFiles.parse("hello", "notes.txt") as WorkoutFileResult.Failed
        assertTrue(notJson.error.text.endsWith("Fix the file or pick another one."))

        val badZwo =
            WorkoutFiles.parse(
                File(fixtures, "zwo/invalid/unknown-step.zwo").readText(),
                "x.zwo",
            ) as WorkoutFileResult.Failed
        assertTrue(badZwo.error.text.isNotBlank())

        val unnamed = WorkoutFiles.parse("{}", null) as WorkoutFileResult.Failed
        assertEquals("That file is not a workout", unnamed.error.title)
    }

    @Test
    fun everyInvalidContractFixtureIsRefusedNeverCrashes() {
        for (dir in listOf("workouts/invalid", "zwo/invalid")) {
            for (file in File(fixtures, dir).listFiles().orEmpty()) {
                val result = WorkoutFiles.parse(file.readText(), file.name)
                assertTrue("${file.name} must be refused", result is WorkoutFileResult.Failed)
            }
        }
    }

    @Test
    fun wattsRoundHalvesUpLikeThePlayer() {
        assertEquals(113, WorkoutDescriber.watts(0.45, 250)) // 112.5
        assertEquals(175, WorkoutDescriber.watts(0.70, 250))
        assertEquals(131, WorkoutDescriber.watts(0.5, 250, 105)) // 131.25
        assertEquals(220, WorkoutDescriber.watts(0.88, 250))
    }

    @Test
    fun theSweetSpotIsDescribedLikeTheMockUp() {
        val description = WorkoutDescriber.describe(load("sweet-spot-3x10.json"), ftp = 250)
        assertEquals("Sweet spot 3x10", description.title)
        assertEquals(3900, description.durationSeconds)
        assertEquals(220, description.hardestWatts)
        assertEquals(3, description.hardestZone)
        assertEquals("ERG + gears", description.mode)
        assertEquals("65 min · steady, ramp, intervals, free", description.meta)
        val rows = description.rows
        assertEquals(listOf("Warm-up", "Sweet spot ×3", "Recover ×3", "Free ride", "Cool-down"), rows.map { it.name })
        assertEquals("45–70%", rows[0].percentText)
        assertEquals("113–175 W", rows[0].wattsText)
        assertEquals(2, rows[0].zone)
        assertEquals("88%", rows[1].percentText)
        assertEquals("220 W", rows[1].wattsText)
        assertEquals(3, rows[1].zone)
        assertEquals("125 W", rows[2].wattsText)
        assertEquals("Gears", rows[3].percentText)
        assertEquals("", rows[3].wattsText)
        assertNull(rows[3].zone)
        assertEquals("138–100 W", rows[4].wattsText)
        assertEquals(1, rows[4].zone)
    }

    @Test
    fun aWorkoutOfOnlyFreeStepsIsGearsMode() {
        val workout =
            WorkoutImport.fromJson(
                """{"schema":1,"id":"f","name":"F","steps":[{"type":"free","duration":60}]}""",
            )
        val description = WorkoutDescriber.describe(workout, 200)
        assertEquals("Gears", description.mode)
        assertNull(description.hardestWatts)
        assertEquals("1 min · free", description.meta)
    }

    @Test
    fun uiKitZonesAgreeWithCoreAtEveryWholePercentAndTheBoundaries() {
        for (pct in 0..200) {
            assertEquals("at $pct%", PowerZone.of(pct, 100).number, Zones.ofFraction(pct / 100.0))
        }
        // The edges Coggan prints are inclusive: 55% is Z1, just above it is Z2.
        assertEquals(1, Zones.ofFraction(0.55))
        assertEquals(2, Zones.ofFraction(0.5501))
        assertEquals(6, Zones.ofFraction(1.5))
        assertEquals(7, Zones.ofFraction(1.5001))
    }
}
