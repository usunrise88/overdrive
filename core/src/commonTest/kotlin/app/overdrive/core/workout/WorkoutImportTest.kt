package app.overdrive.core.workout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutImportTest {
    private fun zwo(
        steps: String,
        name: String = "Test",
    ) = "<workout_file><name>$name</name><workout>$steps</workout></workout_file>"

    @Test
    fun `steady ramps intervals and free ride convert to the native steps`() {
        val workout =
            WorkoutImport.fromZwo(
                zwo(
                    """
                    <Warmup Duration="300" PowerLow="0.4" PowerHigh="0.7"/>
                    <SteadyState Duration="120" Power="0.9"/>
                    <Ramp Duration="60" PowerLow="0.9" PowerHigh="1.1"/>
                    <IntervalsT Repeat="3" OnDuration="30" OnPower="1.2" OffDuration="60" OffPower="0.5"/>
                    <FreeRide Duration="90"/>
                    <Cooldown Duration="200" PowerLow="0.6" PowerHigh="0.3"/>
                    """,
                ),
            )
        assertEquals(
            listOf(
                RampStep(duration = 300, from = 0.4, to = 0.7, label = "Warm-up"),
                SteadyStep(duration = 120, target = 0.9),
                RampStep(duration = 60, from = 0.9, to = 1.1),
                RepeatStep(
                    count = 3,
                    steps =
                        listOf(
                            SteadyStep(duration = 30, target = 1.2, label = "On"),
                            SteadyStep(duration = 60, target = 0.5, label = "Off"),
                        ),
                ),
                FreeStep(duration = 90),
                RampStep(duration = 200, from = 0.6, to = 0.3, label = "Cool-down"),
            ),
            workout.steps,
        )
    }

    @Test
    fun `a ramp runs from PowerLow to PowerHigh in file order so a cooldown can ramp down`() {
        val steps = WorkoutImport.fromZwo(zwo("""<Cooldown Duration="60" PowerLow="0.75" PowerHigh="0.25"/>""")).steps
        val ramp = assertIs<RampStep>(steps.single())
        assertEquals(0.75, ramp.from)
        assertEquals(0.25, ramp.to)
    }

    @Test
    fun `text events are ignored wherever they are`() {
        val workout =
            WorkoutImport.fromZwo(
                zwo(
                    """
                    <textevent timeoffset="0" message="hello"/>
                    <SteadyState Duration="60" Power="0.5"><textevent timeoffset="5" message="go"/></SteadyState>
                    """,
                ),
            )
        assertEquals(listOf<Step>(SteadyStep(duration = 60, target = 0.5)), workout.steps)
    }

    @Test
    fun `cadence becomes the display cadence resting cadence goes to the off step and zero is dropped`() {
        val steps =
            WorkoutImport
                .fromZwo(
                    zwo(
                        """
                        <SteadyState Duration="60" Power="0.5" Cadence="90"/>
                        <SteadyState Duration="60" Power="0.5" Cadence="0"/>
                        <IntervalsT Repeat="2" OnDuration="30" OnPower="1" OffDuration="30" OffPower="0.5" Cadence="100" CadenceResting="75"/>
                        """,
                    ),
                ).steps
        assertEquals(90.0, assertIs<SteadyStep>(steps[0]).cadence)
        assertNull(assertIs<SteadyStep>(steps[1]).cadence)
        val repeat = assertIs<RepeatStep>(steps[2])
        assertEquals(100.0, repeat.steps[0].cadence)
        assertEquals(75.0, repeat.steps[1].cadence)
    }

    @Test
    fun `a steady state given as a power range is a ramp`() {
        val step =
            WorkoutImport.fromZwo(zwo("""<SteadyState Duration="60" PowerLow="0.5" PowerHigh="0.6"/>""")).steps.single()
        assertEquals(RampStep(duration = 60, from = 0.5, to = 0.6), step)
    }

    @Test
    fun `max effort is a free step labelled as such`() {
        val step = WorkoutImport.fromZwo(zwo("""<MaxEffort Duration="15"/>""")).steps.single()
        assertEquals(FreeStep(duration = 15, label = "Max effort"), step)
    }

    @Test
    fun `durations may be decimals and are rounded to whole seconds`() {
        val step = WorkoutImport.fromZwo(zwo("""<SteadyState Duration="59.6" Power="0.5"/>""")).steps.single()
        assertEquals(60, assertIs<SteadyStep>(step).duration)
    }

    @Test
    fun `name description and id`() {
        val workout =
            WorkoutImport.fromZwo(
                "<workout_file><name>  FTP test: 20 min!  </name><description>Go hard.</description>" +
                    """<workout><FreeRide Duration="60"/></workout></workout_file>""",
            )
        assertEquals("FTP test: 20 min!", workout.name)
        assertEquals("Go hard.", workout.description)
        assertEquals("zwo-ftp-test-20-min", workout.id)
        assertEquals(1, workout.schema)

        assertEquals("mine", WorkoutImport.fromZwo(zwo("""<FreeRide Duration="60"/>"""), id = "mine").id)
    }

    @Test
    fun `a missing or blank name gets a default and an empty slug a default id`() {
        val unnamed =
            WorkoutImport.fromZwo(
                """<workout_file><workout><FreeRide Duration="60"/></workout></workout_file>""",
            )
        assertEquals("Zwift workout", unnamed.name)
        assertEquals("zwo-zwift-workout", unnamed.id)
        assertNull(unnamed.description)

        val symbols = WorkoutImport.fromZwo(zwo("""<FreeRide Duration="60"/>""", name = "***"))
        assertEquals("zwo-workout", symbols.id)
    }

    @Test
    fun `crlf line endings and odd formatting are fine`() {
        val text =
            "<?xml version=\"1.0\"?>\r\n<workout_file>\r\n<name>CRLF</name>\r\n<workout>\r\n" +
                "<SteadyState\r\n   Duration=\"60\"\r\n   Power=\"0.5\"\r\n/>\r\n</workout>\r\n</workout_file>\r\n"
        assertEquals(listOf<Step>(SteadyStep(duration = 60, target = 0.5)), WorkoutImport.fromZwo(text).steps)
    }

    @Test
    fun `problems name the step`() {
        val unknown =
            assertFailsWith<WorkoutParseException> {
                WorkoutImport.fromZwo(zwo("""<SteadyState Duration="5" Power="1"/><Teleport/>"""))
            }
        assertTrue("step 2" in unknown.message!! && "teleport" in unknown.message!!, unknown.message)

        val duration =
            assertFailsWith<WorkoutParseException> {
                WorkoutImport.fromZwo(
                    zwo("""<SteadyState Duration="0.4" Power="1"/>"""),
                )
            }
        assertTrue("step 1" in duration.message!! && "duration" in duration.message!!, duration.message)

        val power =
            assertFailsWith<WorkoutParseException> {
                WorkoutImport.fromZwo(
                    zwo("""<SteadyState Duration="30" Power="88"/>"""),
                )
            }
        assertTrue("Step 1" in power.message!!, power.message)

        val number =
            assertFailsWith<WorkoutParseException> {
                WorkoutImport.fromZwo(
                    zwo("""<SteadyState Duration="abc" Power="1"/>"""),
                )
            }
        assertTrue("duration" in number.message!!, number.message)

        val missing =
            assertFailsWith<WorkoutParseException> {
                WorkoutImport.fromZwo(zwo("""<IntervalsT Repeat="2" OnDuration="30" OnPower="1"/>"""))
            }
        assertTrue("offduration" in missing.message!!.lowercase(), missing.message)
    }

    @Test
    fun `the wrong kind of document is rejected`() {
        assertFailsWith<WorkoutParseException> { WorkoutImport.fromZwo("<html/>") }
        assertFailsWith<WorkoutParseException> { WorkoutImport.fromZwo("<workout_file/>") }
        assertFailsWith<WorkoutParseException> { WorkoutImport.fromZwo("<workout_file><workout/></workout_file>") }
        assertFailsWith<WorkoutParseException> { WorkoutImport.fromZwo("nonsense") }
    }

    @Test
    fun `native json is read and validated`() {
        val workout =
            WorkoutImport.fromJson(
                """{"schema":1,"id":"a","name":"A","steps":[{"type":"steady","duration":60,"target":0.5}]}""",
            )
        assertEquals(listOf<Step>(SteadyStep(duration = 60, target = 0.5)), workout.steps)

        assertFailsWith<WorkoutParseException> { WorkoutImport.fromJson("{") }
        assertFailsWith<WorkoutParseException> {
            WorkoutImport.fromJson(
                """{"schema":2,"id":"a","name":"A","steps":[]}""",
            )
        }
        assertFailsWith<WorkoutParseException> {
            WorkoutImport.fromJson(
                """{"schema":1,"id":"a","name":"A","steps":[{"type":"steady","duration":0,"target":0.5}]}""",
            )
        }
    }

    @Test
    fun `parse tells the formats apart by their first character`() {
        val json = """{"schema":1,"id":"a","name":"A","steps":[{"type":"free","duration":60}]}"""
        assertEquals("a", WorkoutImport.parse("  \n$json").id)
        assertEquals("a", WorkoutImport.parse("$json").id)
        assertEquals("zwo-test", WorkoutImport.parse("\n  " + zwo("""<FreeRide Duration="60"/>""")).id)
        assertFailsWith<WorkoutParseException> { WorkoutImport.parse("") }
        assertFailsWith<WorkoutParseException> { WorkoutImport.parse("name,duration\nA,60") }
    }
}
