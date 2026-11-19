package app.overdrive.companion.model

import app.overdrive.companion.RepeatSamples
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.core.repeats.RepeatChange
import app.overdrive.core.repeats.RepeatDefinition
import app.overdrive.uikit.UiFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words and numbers of the repeated sessions (spec §11.7, ADR 0028, design/companion.md §4.12, §6.10): the list's one plain line, the
 * trend in words (only the service's own direction, "fitter" only for a falling heart rate with enough attempts), the charts' geometry,
 * readouts and tables, the attempts, the step table, and every state. Every sentence is a unit test; every figure is the service's.
 */
class RepeatsModelsTest {
    private val words = TestClock.words
    private val host = "overdrive.example.net"

    private fun content(
        overall: List<Double?>,
        drift: List<Double?> = overall.map { 3.5 },
        compliance: List<Double?> = overall.map { 100.0 },
        powers: List<Double?> = overall.indices.map { 181.0 + it },
    ) = RepeatDetailUi.content(RepeatSamples.detail(overall, drift, compliance, powers = powers), words)

    // region the list

    @Test
    fun `a row says how often and when, and the latest attempt against the one before in one plain line`() {
        val rows = RepeatsUi.content(RepeatSamples.twoSessions(), words).rows
        assertEquals(listOf(RepeatSamples.NAME, "Threshold 4×8"), rows.map { it.title })
        assertEquals("5 attempts · last Wed 18 Nov", rows[0].subtitle)
        assertEquals("2 attempts · last Thu 12 Nov", rows[1].subtitle)
        assertEquals("Heart rate at the same power 144 bpm, 2 lower than last time", rows[0].line)
        assertEquals("Heart rate at the same power 158 bpm, 3 lower than last time", rows[1].line)
        assertEquals(listOf(CardPosition.First, CardPosition.Last), rows.map { it.position })
        assertEquals(listOf(RepeatSamples.HASH, RepeatSamples.OTHER_HASH), rows.map { it.hash })
    }

    @Test
    fun `higher, the same and a missing figure are each said plainly, and none says fitter`() {
        fun line(
            latest: Double?,
            previous: Double?,
        ) = RepeatsUi.line(
            if (latest != null && previous != null) {
                RepeatChange(latest, previous, latest - previous, null)
            } else {
                RepeatChange(latest, previous, null, "the latest attempt has none: step 2: only 90 s")
            },
        )
        assertEquals("Heart rate at the same power 150 bpm, 4 higher than last time", line(150.0, 146.0))
        assertEquals("Heart rate at the same power 150 bpm, the same as last time", line(150.0, 149.8))
        assertEquals("Heart rate at the same power 148 bpm, 2 lower than last time", line(148.0, 150.0))
        assertEquals("Heart rate at the same power: the latest attempt has none: step 2: only 90 s", line(null, 150.0))
        for (text in listOf(line(140.0, 150.0), line(150.0, 140.0), line(null, null))) {
            assertFalse(
                text,
                "fitter" in text,
            )
        }
    }

    @Test
    fun `the list has a state for loading, error, stale and empty, in the screens' words`() {
        val loading = RepeatsUi.of(ResourceState(loaded = true), words, host)
        assertEquals(Body.Loading("Loading your repeated sessions…"), loading.body)
        val offline = ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")
        val failed = RepeatsUi.of(ResourceState(loaded = true, problem = offline, asked = true), words, host)
        assertTrue(failed.body is Body.Failed)
        val stale = RepeatsUi.of(fresh(RepeatSamples.twoSessions()).copy(problem = offline), words, host)
        assertTrue(stale.body is Body.Content)
        assertEquals("Offline · updated 07:40", stale.status?.title)
        assertEquals("Updated 07:40", stale.subtitle)
        val empty = RepeatsUi.of(fresh(RepeatSamples.list()), words, host)
        assertEquals(
            Body.Empty(
                "No repeated sessions yet",
                "Ride the same workout twice on the tablet and it appears here, with every attempt side by side.",
            ),
            empty.body,
        )
    }

    // region the trend in words

    @Test
    fun `a falling heart rate over at least three attempts is trending down, fitter, and says by how much`() {
        val trend = checkNotNull(content(listOf(152.0, 150.0, 147.0, 146.0, 143.0)).heartRate.trend)
        assertEquals("Heart rate at the same power is trending down: fitter", trend.text)
        assertTrue(trend.detail, trend.detail.startsWith("Down "))
        assertTrue(trend.detail, trend.detail.endsWith(" bpm over 5 attempts"))
    }

    @Test
    fun `three attempts are enough and two are not`() {
        assertEquals(
            "Heart rate at the same power is trending down: fitter",
            content(listOf(150.0, 146.0, 142.0)).heartRate.trend?.text,
        )
        val two = checkNotNull(content(listOf(150.0, 142.0)).heartRate.trend)
        assertEquals("Not enough attempts yet", two.text)
        assertEquals("2 of 3 attempts have a figure", two.detail)
    }

    @Test
    fun `attempts with no figure do not count towards the three`() {
        val trend = checkNotNull(content(listOf(150.0, null, 142.0)).heartRate.trend)
        assertEquals("Not enough attempts yet", trend.text)
        assertEquals("2 of 3 attempts have a figure", trend.detail)
        assertEquals(
            "Heart rate at the same power is trending down: fitter",
            content(listOf(150.0, null, 146.0, 142.0)).heartRate.trend?.text,
        )
    }

    @Test
    fun `a rising heart rate is trending up and is not called fitter or less fit`() {
        val trend = checkNotNull(content(listOf(141.0, 144.0, 148.0, 152.0)).heartRate.trend)
        assertEquals("Heart rate at the same power is trending up", trend.text)
        assertTrue(trend.detail.startsWith("Up "))
        assertFalse("fitter" in trend.text)
    }

    @Test
    fun `a change inside the band is steady and says the band`() {
        val trend = checkNotNull(content(listOf(145.0, 146.0, 145.0, 146.0)).heartRate.trend)
        assertEquals("Heart rate at the same power is steady", trend.text)
        assertEquals("Within 2 bpm over 4 attempts", trend.detail)
    }

    @Test
    fun `drift and compliance have their own words, and only the heart rate at the same power can be fitter`() {
        val falling =
            content(listOf(150.0, 149.0, 148.0), drift = listOf(6.0, 4.5, 3.0), compliance = listOf(60.0, 80.0, 100.0))
        assertEquals("Aerobic drift is trending down", falling.drift.trend?.text)
        assertEquals("Down 3.0% over 3 attempts", falling.drift.trend?.detail)
        assertEquals("Compliance is trending up", falling.compliance.trend?.text)
        assertEquals("Up 40% over 3 attempts", falling.compliance.trend?.detail)
        val worse =
            content(listOf(150.0, 149.0, 148.0), drift = listOf(3.0, 4.5, 6.0), compliance = listOf(100.0, 80.0, 60.0))
        assertEquals("Aerobic drift is trending up", worse.drift.trend?.text)
        assertEquals("Compliance is trending down", worse.compliance.trend?.text)
        for (card in listOf(falling.drift, falling.compliance, worse.drift, worse.compliance)) {
            assertFalse(card.trend?.text.orEmpty(), "fitter" in card.trend?.text.orEmpty())
        }
        assertNull("the service has no trend for power and duration", falling.power.trend)
        assertNull(falling.duration.trend)
    }

    @Test
    fun `the service's caution is shown under the heart-rate chart as it wrote it`() {
        val chart = checkNotNull(content(listOf(150.0, 148.0, 146.0)).heartRate.chart)
        assertEquals(RepeatDefinition.NOTE, chart.note)
        assertTrue(chart.note.orEmpty().contains("heat, sleep and caffeine"))
    }

    // region the charts

    @Test
    fun `a chart has one dot per attempt, the attempt as x, and the latest at rest`() {
        val chart = checkNotNull(content(listOf(150.0, 148.0, 149.0, 146.0, 144.0)).heartRate.chart)
        val spec = chart.spec
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0), spec.snapXs.toList())
        assertEquals(5.0, spec.restX, 0.0)
        assertEquals(0.5, spec.xMin, 0.0)
        assertEquals(5.5, spec.xMax, 0.0)
        assertEquals(listOf("1", "2", "3", "4", "5"), spec.xTicks.map { it.label })
        assertTrue("the axis holds every figure", spec.yMin <= 144.0 && spec.yMax >= 150.0)
        assertEquals(listOf(150.0, 148.0, 149.0, 146.0, 144.0), spec.dots.single().ys.toList())
        assertTrue(spec.dots.single().marked)
        assertEquals("Heart rate at the same power, bpm", chart.heading)
    }

    @Test
    fun `an attempt with no figure is a gap and reads as a dash, never a zero`() {
        val chart = checkNotNull(content(listOf(150.0, null, 146.0, 144.0)).heartRate.chart)
        assertTrue(
            chart.spec.lines
                .single()
                .ys[1]
                .isNaN(),
        )
        val readout = chart.readoutAt(2.0)
        assertEquals("Attempt 2 · ${words.day(RepeatSamples.date(1))}", readout.x)
        assertEquals(UiFormat.EMPTY, readout.entries.single().value)
        assertEquals("144 bpm", chart.readoutAt(null).entries.single().value)
        assertEquals("Attempt 4 · ${words.day(RepeatSamples.date(3))}", chart.readoutAt(null).x)
        assertEquals("146 bpm", chart.readoutAt(3.0).entries.single().value)
    }

    @Test
    fun `the readout is the legend, it speaks, and the Values table is newest first`() {
        val chart = checkNotNull(content(listOf(150.0, 148.0, 146.0)).heartRate.chart)
        val readout = chart.readoutAt(null)
        assertEquals("Heart rate", readout.entries.single().name)
        assertEquals("Attempt 3 · ${words.day(RepeatSamples.date(2))}, Heart rate 146 bpm", readout.spoken())
        assertEquals("Heart rate at the same power over 3 attempts", chart.summary)
        val table = chart.table
        assertEquals("Heart rate at the same power", table.title)
        assertEquals("Attempt", table.firstColumn)
        assertEquals(listOf("bpm"), table.columns)
        assertEquals(
            listOf(
                "#3 · ${words.dayShort(RepeatSamples.date(2))}",
                "#2 · ${words.dayShort(RepeatSamples.date(1))}",
                "#1 · ${words.dayShort(RepeatSamples.date(0))}",
            ),
            table.rows.map {
                it.label
            },
        )
        assertEquals(listOf("146", "148", "150"), table.rows.map { it.cells.single() })
    }

    @Test
    fun `drift, compliance, power and duration are charts of their own with their own axes`() {
        val c =
            content(
                listOf(150.0, 148.0, 146.0),
                drift = listOf(6.2, 4.4, 3.1),
                compliance = listOf(100.0, 100.0, 92.0),
                powers = listOf(181.0, 183.0, 190.0),
            )
        val drift = checkNotNull(c.drift.chart)
        assertEquals(0.0, drift.spec.yMin, 0.0)
        assertEquals(10.0, drift.spec.yMax, 0.0)
        assertEquals(listOf("0", "5", "10"), drift.spec.yTicks.map { it.label })
        assertEquals("6.2%", drift.readoutAt(1.0).entries.single().value)
        assertEquals("Drift", drift.readoutAt(1.0).entries.single().name)
        val compliance = checkNotNull(c.compliance.chart)
        assertEquals(listOf("0", "50", "100"), compliance.spec.yTicks.map { it.label })
        assertEquals("92%", compliance.readoutAt(null).entries.single().value)
        val power = checkNotNull(c.power.chart)
        assertEquals("190 W", power.readoutAt(null).entries.single().value)
        assertEquals("Average power, W", power.heading)
        val duration = checkNotNull(c.duration.chart)
        assertEquals("Duration, min", duration.heading)
        assertEquals(words.duration(3600 + 2 * 12), duration.readoutAt(null).entries.single().value)
        assertTrue("minutes on the axis", duration.spec.yMax in 55.0..70.0)
    }

    @Test
    fun `a drift above ten percent widens the axis in steps of five`() {
        val chart = checkNotNull(content(listOf(150.0, 148.0, 146.0), drift = listOf(12.5, 9.0, 8.0)).drift.chart)
        assertEquals(15.0, chart.spec.yMax, 0.0)
        assertEquals(listOf("0", "5", "10", "15"), chart.spec.yTicks.map { it.label })
    }

    @Test
    fun `many attempts keep the labels few and the last one named`() {
        val figures = (0 until 20).map { 150.0 - it * 0.5 }
        val ticks = checkNotNull(content(figures).heartRate.chart).spec.xTicks.map { it.label }
        assertTrue(ticks.toString(), ticks.size <= 9)
        assertEquals("1", ticks.first())
        assertEquals("20", ticks.last())
    }

    @Test
    fun `a card with no figure in any attempt says so in one sentence and draws no chart`() {
        val c =
            content(
                listOf(null, null, null),
                drift = listOf(null, null, null),
                compliance = listOf(null, null, null),
                powers = listOf(null, null, null),
            )
        assertNull(c.heartRate.chart)
        assertEquals(
            "No attempt has a heart rate at the same power yet. A step needs 120 s within 3% of its target power, after its first 60 s.",
            c.heartRate.emptyText,
        )
        assertNull(c.drift.chart)
        assertEquals(
            "No attempt has an aerobic drift figure yet. Drift is measured on steady stretches of 40 minutes or more with heart rate.",
            c.drift.emptyText,
        )
        assertNull(c.compliance.chart)
        assertNull(c.power.chart)
        assertNotNull("duration is always there", c.duration.chart)
        assertNull("no figure, no step table", c.steps)
    }

    @Test
    fun `one attempt with a figure still draws its dot`() {
        val chart = checkNotNull(content(listOf(null, 148.0, null), drift = listOf(null, 3.0, null)).heartRate.chart)
        assertEquals(
            1,
            chart.spec.dots
                .single()
                .ys
                .count { !it.isNaN() },
        )
        assertEquals(3, chart.spec.snapXs.size)
    }

    // region the attempts and the steps

    @Test
    fun `attempts are listed newest first with what they did, and a missing figure says why in the service's words`() {
        val rows = content(listOf(150.0, null, 146.0)).attempts
        assertEquals(
            listOf(
                "Attempt 3 · ${words.day(RepeatSamples.date(2))}",
                "Attempt 2 · ${words.day(RepeatSamples.date(1))}",
                "Attempt 1 · ${words.day(RepeatSamples.date(0))}",
            ),
            rows.map {
                it.title
            },
        )
        assertEquals(
            listOf(RepeatSamples.rideId(2), RepeatSamples.rideId(1), RepeatSamples.rideId(0)),
            rows.map { it.rideId },
        )
        assertEquals("1:00:24 · 183 W · compliance 100%", rows[0].subtitle)
        assertEquals("146", rows[0].value)
        assertEquals("bpm at power", rows[0].caption)
        assertNull(rows[0].reason)
        assertEquals(UiFormat.EMPTY, rows[1].value)
        assertEquals("no figure", rows[1].caption)
        assertEquals(
            "Step 2: only 90 s were within 3% of the target power after the first 60 s; 120 s are needed",
            rows[1].reason,
        )
        assertTrue(rows[1].description.contains("Attempt 2"))
        assertTrue(rows[0].description.contains("146 bpm"))
    }

    @Test
    fun `the step table puts every attempt side by side, the latest last, and says what a dash means`() {
        val table = checkNotNull(content(listOf(150.0, null, 146.0)).steps)
        assertEquals("Heart rate by step, bpm", table.heading)
        assertEquals(listOf("#1", "#2", "#3"), table.headers)
        assertEquals(listOf("Sweet spot", "Recover"), table.rows.map { it.label })
        assertEquals(listOf("154", UiFormat.EMPTY, "150"), table.rows[0].cells)
        assertEquals(listOf("130", UiFormat.EMPTY, "126"), table.rows[1].cells)
        assertEquals("220 W · 10:00", table.rows[0].caption)
        assertEquals("125 W · 5:00", table.rows[1].caption)
        assertEquals("A dash means fewer than 120 s within 3% of the step's power, after its first 60 s.", table.note)
        assertTrue(table.rows[0].description.contains("attempt 2 no figure"))
        assertNull("no dash, no note", checkNotNull(content(listOf(150.0, 148.0, 146.0)).steps).note)
    }

    @Test
    fun `the page says how many attempts over which days`() {
        val c = content(listOf(150.0, 148.0, 146.0))
        assertEquals("3 attempts · 8 Oct to 22 Oct", c.whenLine)
        assertEquals(RepeatSamples.NAME, c.name)
    }

    @Test
    fun `the page has a state for loading, error, stale, and never an empty one`() {
        val detail = RepeatSamples.detail(listOf(150.0, 148.0, 146.0))
        assertEquals(
            Body.Loading("Loading the session…"),
            RepeatDetailUi.of(ResourceState(loaded = true), words, host).body,
        )
        val gone = ServiceProblem(ProblemKind.NO_SUCH_ROUTE, "The service does not have this answer yet")
        val failed = RepeatDetailUi.of(ResourceState(loaded = true, problem = gone, asked = true), words, host)
        assertTrue(failed.body is Body.Failed)
        val stale =
            RepeatDetailUi.of(
                fresh(detail).copy(problem = ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")),
                words,
                host,
            )
        assertTrue(stale.body is Body.Content)
        assertEquals("Offline · updated 07:40", stale.status?.title)
    }
}
