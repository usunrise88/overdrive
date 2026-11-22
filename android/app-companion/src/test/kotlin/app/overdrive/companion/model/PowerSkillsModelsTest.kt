package app.overdrive.companion.model

import app.overdrive.companion.ChartScreenSamples
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.core.powerskills.PowerSkillId
import app.overdrive.core.remote.PowerSkillsWindow
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.uikit.tokens.OverdriveColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * The Power skills card as pure functions (design/companion.md §4.14, §6.11, §7.8, §8): the radii in both scales, the words of the levels,
 * the rows, the sheets, the window rules, the Custom range's picker and every state of the card, from the synthetic answers.
 */
class PowerSkillsModelsTest {
    private val words = TestClock.words
    private val eight = PowerSkillsWindow.EightWeeks

    private fun card(
        response: app.overdrive.core.powerskills.PowerSkillsResponse?,
        window: PowerSkillsWindow = eight,
        problem: ServiceProblem? = null,
    ): PowerSkillsCardUi {
        val state =
            if (response == null) {
                ResourceState(loaded = true, asked = true, problem = problem)
            } else {
                fresh(response).copy(problem = problem)
            }
        return PowerSkillsUi.of(state, window, words)
    }

    private fun content(
        response: app.overdrive.core.powerskills.PowerSkillsResponse = ChartScreenSamples.skills(),
        window: PowerSkillsWindow = eight,
    ): PowerSkillsContent = (card(response, window).body as PowerSkillsBody.Radar).content

    // region words

    @Test
    fun `durations are never written in hours and the spans follow the skill`() {
        assertEquals(
            listOf(
                "15 s",
                "30 s",
                "1 min",
                "2 min",
                "3 min",
                "5 min",
                "10 min",
                "15 min",
                "20 min",
                "30 min",
                "45 min",
                "60 min",
            ),
            app.overdrive.core.powerskills.POWER_SKILL_DURATIONS
                .map(SkillWords::duration),
        )
        assertEquals(
            listOf("15–30 s", "1–3 min", "5–10 min", "15–30 min", "45–60 min"),
            PowerSkillId.entries.map(SkillWords::span),
        )
        assertEquals("1 minute", SkillWords.spoken(60))
        assertEquals("15 seconds", SkillWords.spoken(15))
        assertEquals("45 minutes", SkillWords.spoken(2700))
        assertEquals("5 to 10 minutes", SkillWords.spokenSpan(PowerSkillId.CLIMB))
        assertEquals("15 to 30 seconds", SkillWords.spokenSpan(PowerSkillId.SPRINT))
    }

    @Test
    fun `watts carry a thousands comma and watts per kilogram one decimal`() {
        assertEquals("930", SkillWords.watts(930.4))
        assertEquals("1,010", SkillWords.watts(1010.0))
        assertEquals("4.4 W/kg", SkillWords.perKg(4.39))
        assertEquals("72.0 kg", SkillWords.kg(72.0))
    }

    @Test
    fun `the nine level words are written with their number`() {
        assertEquals("Strong · level 5 of 9", SkillWords.levelLine(5))
        assertEquals("Starting · level 1 of 9", SkillWords.levelLine(1))
        assertEquals("World class · level 9 of 9", SkillWords.levelLine(9))
        assertEquals("Fit · 4 of 9", SkillWords.levelShort(4))
        assertEquals(
            listOf("Starting", "Casual", "Regular", "Fit", "Strong", "Very strong", "Racer", "Elite", "World class"),
            (1..9).map(SkillWords::levelWord),
        )
    }

    @Test
    fun `each skill takes the zone colour its efforts are ridden in`() {
        assertEquals(
            listOf(OverdriveColors.z7, OverdriveColors.z6, OverdriveColors.z5, OverdriveColors.z4, OverdriveColors.z3),
            PowerSkillId.entries.map(SkillWords::color),
        )
        assertEquals(listOf(15, 60, 300, 1200, 3600), PowerSkillId.entries.map(SkillWords::keyDuration))
    }

    // region the radar

    @Test
    fun `with a weight the radius is the score over nine, held between the centre and the edge`() {
        val sample = ChartScreenSamples.skills()
        val spec = content(sample).spec
        for (i in 0 until 12) {
            assertEquals(sample.durations[i].window!!.score!! / 9.0, spec.main[i], 1e-9)
            assertEquals(sample.durations[i].allTime!!.score!! / 9.0, spec.reference!![i], 1e-9)
        }
        assertEquals(listOf(1f / 3f, 2f / 3f), spec.rings)
        assertTrue(spec.main.all { it in 0.0..1.0 })
    }

    @Test
    fun `without a weight the radius is the share of the best ever, 50 percent at the centre and 100 at the edge`() {
        val spec = content(ChartScreenSamples.skills(weight = false)).spec
        val sample = ChartScreenSamples.skills(weight = false)
        // 930 of 1010 W: a share of 0.9208 is (0.9208 - 0.5) / 0.5 of the radius.
        assertEquals((930.0 / 1010.0 - 0.5) / 0.5, spec.main[0], 1e-9)
        assertEquals((236.0 / 247.0 - 0.5) / 0.5, spec.main[11], 1e-9)
        // The all-time outline is the edge itself and is not drawn twice; the one inner ring is 75%.
        assertNull(spec.reference)
        assertEquals(listOf(0.5f), spec.rings)
        assertEquals(1.0, PowerSkillsUi.shareRadius(sample.durations[0].allTime, sample.durations[0].allTime), 1e-9)
        assertEquals(
            0.0,
            PowerSkillsUi.shareRadius(
                sample.durations[0].window!!.let { e ->
                    e.copy(watts = 400.0)
                },
                sample.durations[0].allTime,
            ),
            1e-9,
        )
    }

    @Test
    fun `a score is clamped for the radius, no data is NaN`() {
        assertTrue(PowerSkillsUi.levelRadius(null).isNaN())
        assertTrue(PowerSkillsUi.shareRadius(null, null).isNaN())
        val sample = ChartScreenSamples.skills().durations[0].window!!
        assertEquals(1.0, PowerSkillsUi.levelRadius(sample.copy(score = 9.0)), 0.0)
        assertEquals(0.0, PowerSkillsUi.levelRadius(sample.copy(score = 0.0)), 0.0)
    }

    @Test
    fun `the wash is drawn only when all twelve durations have data, and a gap is NaN in the outline`() {
        assertTrue(content().spec.wash)
        val custom = content(ChartScreenSamples.customSkills(), PowerSkillsWindow.Custom("2026-11-02", "2026-11-15"))
        assertFalse(custom.spec.wash)
        assertTrue(custom.spec.main[10].isNaN() && custom.spec.main[11].isNaN())
        assertTrue(custom.spec.main[9].isFinite())
        assertEquals(null, custom.spec.labels[10].watts)
        assertEquals("45 min", custom.spec.labels[10].duration)
        // The all-time outline stays complete under the window's gaps.
        assertTrue(custom.spec.reference!!.all { it.isFinite() })
    }

    @Test
    fun `the labels print the window's watts over the duration`() {
        val labels = content().spec.labels
        assertEquals(
            listOf(
                "15 s",
                "30 s",
                "1 min",
                "2 min",
                "3 min",
                "5 min",
                "10 min",
                "15 min",
                "20 min",
                "30 min",
                "45 min",
                "60 min",
            ),
            labels.map {
                it.duration
            },
        )
        assertEquals(
            listOf(
                "930",
                "705",
                "478",
                "372",
                "341",
                "316",
                "289",
                "279",
                "273",
                "259",
                "244",
                "236",
            ),
            labels.map {
                it.watts
            },
        )
        assertTrue(labels.all { it.unit == "W" })
    }

    @Test
    fun `the ring is split into the five skills, each from its first duration to its last`() {
        val segments = content().spec.segments
        assertEquals(listOf(0 to 1, 2 to 4, 5 to 6, 7 to 9, 10 to 11), segments.map { it.firstAxis to it.lastAxis })
        assertEquals(PowerSkillId.entries.map(SkillWords::color), segments.map { it.color })
    }

    @Test
    fun `All time as the window has one outline and one key`() {
        val all = content(ChartScreenSamples.skills(window = ChartScreenSamples.bestEver), PowerSkillsWindow.AllTime)
        assertNull(all.spec.reference)
        assertEquals(listOf(LegendKey("All time", true)), all.legend)
        assertEquals(listOf(LegendKey("8 weeks", true), LegendKey("All time", false)), content().legend)
        assertEquals(
            listOf(LegendKey("2 Nov – 15 Nov", true), LegendKey("All time", false)),
            content(ChartScreenSamples.customSkills(), PowerSkillsWindow.Custom("2026-11-02", "2026-11-15")).legend,
        )
    }

    // region rows

    @Test
    fun `the five rows have the level, the level bar and the key duration's best`() {
        val rows = content().rows
        assertEquals(listOf("Sprint", "Attack", "Climb", "Time trial", "Endurance"), rows.map { it.name })
        assertEquals(
            listOf(
                "Fit · level 4 of 9",
                "Casual · level 2 of 9",
                "Fit · level 4 of 9",
                "Regular · level 3 of 9",
                "Regular · level 3 of 9",
            ),
            rows.map {
                it.levelLine
            },
        )
        assertEquals(listOf(4, 2, 4, 3, 3), rows.map { it.level })
        assertEquals(listOf("930", "478", "316", "273", "236"), rows.map { it.watts })
        assertEquals(
            listOf(
                "12.9 W/kg · 15 s",
                "6.6 W/kg · 1 min",
                "4.4 W/kg · 5 min",
                "3.8 W/kg · 20 min",
                "3.3 W/kg · 60 min",
            ),
            rows.map { it.caption },
        )
        assertEquals("15–30 s", rows[0].span)
        assertTrue(rows.all { it.noData == null })
    }

    @Test
    fun `without a weight a row has the watts and the duration only, no level and no bar`() {
        val rows = content(ChartScreenSamples.skills(weight = false)).rows
        assertTrue(rows.all { it.levelLine == null && it.level == 0 })
        assertEquals(listOf("15 s", "1 min", "5 min", "20 min", "60 min"), rows.map { it.caption })
        assertEquals("930", rows[0].watts)
    }

    @Test
    fun `a skill with no ride in the window says which ride is missing and has no bar`() {
        val row =
            content(
                ChartScreenSamples.customSkills(),
                PowerSkillsWindow.Custom("2026-11-02", "2026-11-15"),
            ).rows.last()
        assertEquals("Endurance", row.name)
        assertEquals("No ride of 45 min or longer", row.noData)
        assertNull(row.watts)
        assertEquals(0, row.level)
        assertNull(row.levelLine)
        assertEquals("60 min", row.caption)
    }

    @Test
    fun `a row falls back to the next duration of its skill when the key duration has no data`() {
        // No 5 min effort: Climb reads at 10 min.
        val window = ChartScreenSamples.eightWeeks.mapIndexed { i, best -> if (i == 5) null else best }
        val response = ChartScreenSamples.skills(window = window)
        assertEquals(600, PowerSkillsUi.rowDuration(response, PowerSkillId.CLIMB)!!.seconds)
        val row = content(response).rows[2]
        assertEquals("289", row.watts)
        assertTrue(row.caption!!.endsWith("10 min"))
        // The level comes from the durations that have data.
        assertTrue(row.level > 0)
    }

    @Test
    fun `the closing note is the approximation with the weight, or the missing weight`() {
        assertEquals(
            "Levels are approximate: your watts per kilogram (72.0 kg, 7-day average) " +
                "against published power-profile tables. " +
                "Outdoor rides have no power, so only rides from the tablet count.",
            content().closing,
        )
        assertEquals(
            "Levels need your weight. Weigh yourself with a scale that writes to Health Connect " +
                "and they appear here. " +
                "Outdoor rides have no power, so only rides from the tablet count.",
            content(ChartScreenSamples.skills(weight = false)).closing,
        )
        assertEquals("Rings mark levels 3, 6 and 9. Tap a duration or a skill for its rides.", content().note)
        assertEquals(
            "Each axis is a share of your best ever: the ring is your best, the inner ring 75%, the centre 50%.",
            content(ChartScreenSamples.skills(weight = false)).note,
        )
    }

    // region the window

    @Test
    fun `the window control has three segments and follows the window`() {
        assertEquals(listOf("8 weeks", "All time", "Custom"), SkillWindows.labels)
        assertEquals(0, SkillWindows.index(PowerSkillsWindow.EightWeeks))
        assertEquals(1, SkillWindows.index(PowerSkillsWindow.AllTime))
        assertEquals(2, SkillWindows.index(PowerSkillsWindow.Custom("2026-11-02", "2026-11-15")))
    }

    @Test
    fun `the range line names the range with its year and the rides`() {
        val range = PowerSkillsWindow.Custom("2026-11-02", "2026-11-15")
        assertEquals("2 Nov – 15 Nov 2026 · 9 rides", SkillWindows.rangeLine(range, 9, words))
        assertEquals("2 Nov – 15 Nov 2026 · 1 ride", SkillWindows.rangeLine(range, 1, words))
        assertEquals("2 Nov – 15 Nov 2026 · no rides", SkillWindows.rangeLine(range, 0, words))
        assertEquals("2 Nov – 15 Nov 2026", SkillWindows.rangeLine(range, null, words))
        assertEquals("28 Dec 2025 – 3 Jan 2026", SkillWindows.long("2025-12-28", "2026-01-03", words))
        // The legend leaves the year out inside the present year and keeps it otherwise.
        assertEquals("2 Nov – 15 Nov", SkillWindows.short("2026-11-02", "2026-11-15", words))
        assertEquals("2 Nov 2025 – 15 Nov 2025", SkillWindows.short("2025-11-02", "2025-11-15", words))
        assertEquals("1 ride", SkillWindows.rides(1))
    }

    @Test
    fun `the card shows the range line only while Custom is on, with the count once it is known`() {
        assertNull(card(ChartScreenSamples.skills()).rangeLine)
        assertNull(
            card(ChartScreenSamples.skills(window = ChartScreenSamples.bestEver), PowerSkillsWindow.AllTime).rangeLine,
        )
        val range = PowerSkillsWindow.Custom("2026-11-02", "2026-11-15")
        assertEquals("2 Nov – 15 Nov 2026 · 9 rides", card(ChartScreenSamples.customSkills(), range).rangeLine)
        assertEquals("2 Nov – 15 Nov 2026", card(null, range).rangeLine)
    }

    // region states

    @Test
    fun `loading with nothing cached is the spinner line, and a failure with nothing cached says it is not loaded`() {
        assertEquals(PowerSkillsBody.Loading, card(null).body)
        assertEquals(
            PowerSkillsBody.NotLoaded,
            card(null, problem = ServiceProblem(ProblemKind.NO_NETWORK, "No network")).body,
        )
        // The window control is there in both.
        assertEquals(0, card(null).selected)
    }

    @Test
    fun `an offline refresh leaves the cached card unchanged`() {
        val online = card(ChartScreenSamples.skills())
        val offline = card(ChartScreenSamples.skills(), problem = ServiceProblem(ProblemKind.NO_NETWORK, "No network"))
        assertTrue(offline.body is PowerSkillsBody.Radar)
        assertEquals(
            (online.body as PowerSkillsBody.Radar).content.rows,
            (offline.body as PowerSkillsBody.Radar).content.rows,
        )
    }

    @Test
    fun `no ride with power ever is the empty card in the 8 weeks and in All time`() {
        assertEquals(PowerSkillsBody.NoRidesYet, card(ChartScreenSamples.noRidesEver()).body)
        assertEquals(PowerSkillsBody.NoRidesYet, card(ChartScreenSamples.noRidesEver(), PowerSkillsWindow.AllTime).body)
    }

    @Test
    fun `fewer than three durations is a list of the durations with data, not a radar`() {
        val body = card(ChartScreenSamples.fewDurations()).body as PowerSkillsBody.Few
        assertEquals("Ride longer to see the shape: the radar needs at least three durations.", body.text)
        assertEquals(listOf("15 s", "30 s"), body.rows.map { it.duration })
        assertEquals(listOf("930", "705"), body.rows.map { it.watts })
        assertEquals("Mon 9 Nov", body.rows[0].date)
        assertTrue(body.rows[0].rideId.startsWith("ride-2026-11-09"))
        // Exactly three is a radar.
        val three =
            ChartScreenSamples.skills(
                window =
                    ChartScreenSamples.eightWeeks.mapIndexed { i, b ->
                        b.takeIf {
                            i <
                                3
                        }
                    },
            )
        assertTrue(card(three).body is PowerSkillsBody.Radar)
        // The Values table still has all twelve rows.
        assertEquals(12, body.table.rows.size)
    }

    @Test
    fun `a custom range with no rides says so in one sentence`() {
        val range = PowerSkillsWindow.Custom("2026-08-02", "2026-08-09")
        val ui = card(ChartScreenSamples.emptyWindow("2026-08-02", "2026-08-09"), range)
        assertEquals("2 Aug – 9 Aug 2026 · no rides", ui.rangeLine)
        assertEquals(
            PowerSkillsBody.EmptyWindow("No rides on the tablet between 2 Aug and 9 Aug. Choose another range."),
            ui.body,
        )
        // No rides in the last 8 weeks but older ones: its own sentence, not "No rides yet".
        val older = ChartScreenSamples.emptyWindow("2026-09-24", "2026-11-18")
        assertTrue(card(older).body is PowerSkillsBody.EmptyWindow)
    }

    @Test
    fun `a custom range with rides but none ever before is empty, not the no-rides-yet card`() {
        val range = PowerSkillsWindow.Custom("2026-08-02", "2026-08-09")
        val none =
            ChartScreenSamples.skills(
                "2026-08-02",
                "2026-08-09",
                List(12) { null },
                List(12) { null },
                rideCount = 0,
            )
        assertTrue(card(none, range).body is PowerSkillsBody.EmptyWindow)
    }

    // region sheets

    @Test
    fun `the sheet of a duration has the window's value and the best ever, each with its ride, and the level`() {
        val detail = content().axes[5]
        assertEquals("5 min", detail.title)
        assertEquals("Climb", detail.skill)
        assertEquals(listOf("Last 8 weeks", "All time"), detail.rows.map { it.label })
        val window = detail.rows[0].cell!!
        assertEquals("316", window.watts)
        assertEquals("4.4 W/kg", window.perKg)
        assertEquals("Thu 12 Nov · VO2 5×3", window.ride.line)
        assertTrue(window.ride.rideId.startsWith("ride-2026-11-12"))
        assertEquals("330", detail.rows[1].cell!!.watts)
        assertEquals("Sat 3 Oct · Climb repeats", detail.rows[1].cell!!.ride.line)
        assertEquals(LevelRow("Level, 8 weeks", "Approximate, at 72.0 kg", "Fit · 4 of 9"), detail.level)
    }

    @Test
    fun `without a weight a duration sheet has no level and no watts per kilogram`() {
        val detail = content(ChartScreenSamples.skills(weight = false)).axes[5]
        assertNull(detail.level)
        assertNull(detail.rows[0].cell!!.perKg)
    }

    @Test
    fun `a duration without a ride in the window says so and keeps the all-time row`() {
        val range = PowerSkillsWindow.Custom("2026-11-02", "2026-11-15")
        val detail = content(ChartScreenSamples.customSkills(), range).axes[11]
        assertEquals("60 min", detail.title)
        assertNull(detail.rows[0].cell)
        assertEquals("No ride of 60 min or longer in this range", detail.rows[0].emptyText)
        assertEquals("All time", detail.rows[1].label)
        assertEquals("247", detail.rows[1].cell!!.watts)
        // The eight weeks name themselves.
        val eight =
            content(
                ChartScreenSamples.skills(
                    window =
                        ChartScreenSamples.eightWeeks.mapIndexed { i, b ->
                            if (i ==
                                11
                            ) {
                                null
                            } else {
                                b
                            }
                        },
                ),
            ).axes[11]
        assertEquals("No ride of 60 min or longer in these 8 weeks", eight.rows[0].emptyText)
    }

    @Test
    fun `when All time is the window its sheet has one value row`() {
        val detail =
            content(
                ChartScreenSamples.skills(window = ChartScreenSamples.bestEver),
                PowerSkillsWindow.AllTime,
            ).axes[5]
        assertEquals(listOf("All time"), detail.rows.map { it.label })
        assertEquals("Level, all time", detail.level!!.label)
    }

    @Test
    fun `the sheet of a skill has one row per duration with both windows and one sentence of watts per kilogram`() {
        val detail = content().skills[2]
        assertEquals("Climb", detail.title)
        assertEquals("5–10 min · Fit, level 4 of 9 in 8 weeks", detail.subtitle)
        assertEquals("8 weeks", detail.windowColumn)
        assertEquals("All time", detail.allTimeColumn)
        assertEquals(listOf("5 min", "10 min"), detail.rows.map { it.duration })
        assertEquals("316", detail.rows[0].window!!.watts)
        assertEquals("12 Nov", detail.rows[0].window!!.date)
        assertEquals("330", detail.rows[0].allTime!!.watts)
        assertEquals("4.4 W/kg for 5 min and 4.0 W/kg for 10 min at 72.0 kg.", detail.perKgSentence)
        assertEquals("Tap a value to open its ride.", detail.hint)
        assertEquals(PowerSkillId.CLIMB, detail.id)
        // Three durations are a list with "and" before the last.
        assertEquals(
            "3.9 W/kg for 15 min, 3.8 W/kg for 20 min and 3.6 W/kg for 30 min at 72.0 kg.",
            content().skills[3].perKgSentence,
        )
    }

    @Test
    fun `without a weight a skill sheet has no level and no sentence of watts per kilogram`() {
        val detail = content(ChartScreenSamples.skills(weight = false)).skills[2]
        assertEquals("5–10 min", detail.subtitle)
        assertNull(detail.perKgSentence)
    }

    @Test
    fun `the Values table has the twelve durations in axis order and a dash for no data`() {
        val table = content().table
        assertEquals("Duration", table.firstColumn)
        assertEquals(listOf("8 weeks", "All time", "W/kg"), table.columns)
        assertEquals(12, table.rows.size)
        assertEquals(listOf("930", "1,010", "12.9"), table.rows[0].cells)
        val custom =
            content(
                ChartScreenSamples.customSkills(),
                PowerSkillsWindow.Custom("2026-11-02", "2026-11-15"),
            ).table
        assertEquals("Range", custom.columns[0])
        assertEquals(listOf("—", "247", "—"), custom.rows[11].cells)
        val noWeight = content(ChartScreenSamples.skills(weight = false)).table
        assertEquals(listOf("8 weeks", "All time"), noWeight.columns)
        val all =
            content(
                ChartScreenSamples.skills(window = ChartScreenSamples.bestEver),
                PowerSkillsWindow.AllTime,
            ).table
        assertEquals(listOf("All time", "W/kg"), all.columns)
    }

    // region TalkBack

    @Test
    fun `the radar's text alternative reads the values in axis order and says what the rings mean`() {
        val text = content().description
        val head = "Power skills, last 8 weeks against all time. 15 seconds 930 watts, all time 1,010."
        assertTrue(text, text.startsWith(head))
        assertTrue(text, text.contains("1 minute 478 watts, all time 496."))
        assertTrue(text, text.contains("60 minutes 236 watts, all time 247."))
        assertTrue(text, text.endsWith("Rings mark levels 3, 6 and 9."))
        val custom =
            content(
                ChartScreenSamples.customSkills(),
                PowerSkillsWindow.Custom("2026-11-02", "2026-11-15"),
            ).description
        assertTrue(custom, custom.contains("45 minutes, no ride."))
        assertTrue(custom, custom.startsWith("Power skills, 2 Nov – 15 Nov against all time."))
        val noWeight = content(ChartScreenSamples.skills(weight = false)).description
        assertTrue(noWeight, noWeight.contains("15 seconds 930 watts, 92% of your best."))
        assertFalse(noWeight.contains("levels 3, 6"))
        assertTrue(
            content(
                ChartScreenSamples.skills(window = ChartScreenSamples.bestEver),
                PowerSkillsWindow.AllTime,
            ).description.startsWith("Power skills, all time."),
        )
    }

    @Test
    fun `a skill row reads as one button with the level and the best`() {
        assertEquals(
            "Climb, 5 to 10 minutes. Fit · level 4 of 9. " +
                "Best 316 watts, 4.4 watts per kilogram, at 5 minutes. Opens details.",
            content().rows[2].description,
        )
        assertEquals(
            "Endurance, 45 to 60 minutes. No ride of 45 min or longer. Opens details.",
            content(
                ChartScreenSamples.customSkills(),
                PowerSkillsWindow.Custom("2026-11-02", "2026-11-15"),
            ).rows[4].description,
        )
    }

    // region the picker

    private val today = LocalDate.of(2026, 11, 18)
    private val rideDays = setOf(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 16))

    private fun day(n: Int) = LocalDate.of(2026, 11, n)

    @Test
    fun `the picker opens on this month, or on the month of the current custom end`() {
        assertEquals(YearMonth.of(2026, 11), RangePicker.open(today, null).month)
        assertEquals(YearMonth.of(2026, 8), RangePicker.open(today, LocalDate.of(2026, 8, 9)).month)
        assertNull(RangePicker.open(today, null).first)
    }

    @Test
    fun `the first tap is the start, the second the end, and the same day may be both`() {
        var state = RangePicker.open(today, null)
        state = RangePicker.tap(state, day(2), today)
        assertEquals(day(2), state.first)
        assertNull(state.last)
        assertFalse(state.complete)
        state = RangePicker.tap(state, day(15), today)
        assertEquals(day(2) to day(15), state.first to state.last)
        assertTrue(state.complete)
        val one = RangePicker.tap(RangePicker.tap(RangePicker.open(today, null), day(9), today), day(9), today)
        assertEquals(day(9) to day(9), one.first to one.last)
    }

    @Test
    fun `a tap before the start makes that day the new start, and a tap after a complete range starts again`() {
        val started = RangePicker.tap(RangePicker.open(today, null), day(10), today)
        val earlier = RangePicker.tap(started, day(4), today)
        assertEquals(day(4), earlier.first)
        assertNull(earlier.last)
        val done = RangePicker.tap(started, day(12), today)
        val again = RangePicker.tap(done, day(6), today)
        assertEquals(day(6), again.first)
        assertNull(again.last)
    }

    @Test
    fun `days after today cannot be chosen and a range longer than three years starts again`() {
        val state = RangePicker.open(today, null)
        assertEquals(state, RangePicker.tap(state, day(19), today))
        assertEquals(state, RangePicker.tap(state, day(30), today))
        val old = PickerState(YearMonth.of(2022, 1), LocalDate.of(2022, 1, 1))
        val tooLong = RangePicker.tap(old, LocalDate.of(2026, 1, 1), today)
        assertEquals(LocalDate.of(2026, 1, 1), tooLong.first)
        assertNull(tooLong.last)
        // Exactly 1096 days is allowed.
        val limit = RangePicker.tap(old, LocalDate.of(2022, 1, 1).plusDays(1095), today)
        assertTrue(limit.complete)
    }

    @Test
    fun `the month grid is Monday first with the rides dotted, today ringed and the future off`() {
        val state = PickerState(YearMonth.of(2026, 11), day(2), day(15))
        val grid = RangePicker.grid(state, today, rideDays)
        // 1 November 2026 is a Sunday: six empty cells before it, then 30 days over 6 rows.
        assertEquals(6, grid.size)
        assertTrue(grid.all { it.size == 7 })
        assertEquals(List(6) { null }, grid[0].take(6))
        assertEquals(day(1), grid[0][6]!!.date)
        val cells = grid.flatten().filterNotNull()
        assertEquals(30, cells.size)
        assertEquals(listOf(2, 16), cells.filter { it.hasRide }.map { it.date.dayOfMonth })
        assertEquals(listOf(18), cells.filter { it.isToday }.map { it.date.dayOfMonth })
        assertEquals((19..30).toList(), cells.filter { it.future }.map { it.date.dayOfMonth })
        assertEquals(listOf(2), cells.filter { it.isFirst }.map { it.date.dayOfMonth })
        assertEquals(listOf(15), cells.filter { it.isLast }.map { it.date.dayOfMonth })
        assertEquals((3..14).toList(), cells.filter { it.inBetween }.map { it.date.dayOfMonth })
    }

    @Test
    fun `the chevrons move by month and the right one is off on the current month`() {
        var state = RangePicker.open(today, null)
        assertFalse(RangePicker.canGoNext(state, today))
        assertEquals(state, RangePicker.next(state, today))
        state = RangePicker.previous(state)
        assertEquals(YearMonth.of(2026, 10), state.month)
        assertTrue(RangePicker.canGoNext(state, today))
        assertEquals(YearMonth.of(2026, 11), RangePicker.next(state, today).month)
    }

    @Test
    fun `the choice line walks from the first day to the range with its days and rides`() {
        val dates = (listOf(2, 3, 5, 7, 9, 10, 12, 14, 15, 16)).map(::day)
        var state = RangePicker.open(today, null)
        assertEquals("Choose the first day", RangePicker.choice(state, 0, words))
        assertEquals("Show", RangePicker.showLabel(state, words))
        state = RangePicker.tap(state, day(2), today)
        assertEquals("From 2 Nov · choose the last day", RangePicker.choice(state, 0, words))
        state = RangePicker.tap(state, day(15), today)
        assertEquals(9, RangePicker.ridesIn(state, dates))
        assertEquals("2 Nov – 15 Nov · 14 days · 9 rides", RangePicker.choice(state, 9, words))
        assertEquals("Show 2 Nov – 15 Nov", RangePicker.showLabel(state, words))
        val single = RangePicker.tap(RangePicker.tap(RangePicker.open(today, null), day(9), today), day(9), today)
        assertEquals("9 Nov – 9 Nov · 1 day · no rides", RangePicker.choice(single, 0, words))
    }

    @Test
    fun `a day is described for TalkBack with its ride, its place in the range and whether it can be chosen`() {
        val state = PickerState(YearMonth.of(2026, 11), day(2), day(15))
        val cells =
            RangePicker
                .grid(
                    state,
                    today,
                    rideDays,
                ).flatten()
                .filterNotNull()
                .associateBy { it.date.dayOfMonth }
        assertEquals("Monday 2 November, has a ride, first day", cells.getValue(2).description(words))
        assertEquals("Monday 16 November, has a ride", cells.getValue(16).description(words))
        assertEquals("Wednesday 18 November, today", cells.getValue(18).description(words))
        assertEquals("Sunday 15 November, last day", cells.getValue(15).description(words))
        assertEquals("Thursday 19 November, not available", cells.getValue(19).description(words))
    }
}
