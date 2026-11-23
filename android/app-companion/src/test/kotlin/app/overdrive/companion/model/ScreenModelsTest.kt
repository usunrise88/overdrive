package app.overdrive.companion.model

import app.overdrive.companion.Samples
import app.overdrive.companion.WatchSamples
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.companion.wellness.RecoveryFactData
import app.overdrive.companion.wellness.RecoveryLevel
import app.overdrive.companion.wellness.RecoveryState
import app.overdrive.core.health.HealthAccess
import app.overdrive.core.health.HealthDataType
import app.overdrive.core.health.HealthStatus
import app.overdrive.core.metrics.MetricsResponse
import app.overdrive.core.profile.ProfileState
import app.overdrive.core.remote.FtpAnswers
import app.overdrive.core.remote.InsightsList
import app.overdrive.core.remote.InsightsView
import app.overdrive.core.remote.MetricsRange
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.RidesList
import app.overdrive.core.remote.RidesView
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.core.wellness.RecoveryResponse
import app.overdrive.uikit.RecoveryLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screens' words and numbers from the service's answers (design/companion.md §4, §5), without Compose. */
class ScreenModelsTest {
    private val words = TestClock.words
    private val host = "overdrive.example.net"

    private fun <T> ScreenUi<T>.content(): T = (body as Body.Content).value

    // region states

    @Test
    fun `nothing cached and nothing asked is loading, a failure with nothing cached is the error block`() {
        val loading = FitnessUi.of(ResourceState(loaded = true), MetricsRange.SIX_MONTHS, words, host)
        assertEquals(Body.Loading("Loading your fitness…"), loading.body)
        val problem =
            ServiceProblem(ProblemKind.NAME_NOT_FOUND, "Cannot find the service address (DNS)", "UnknownHostException")
        val failed =
            FitnessUi.of(
                ResourceState(loaded = true, problem = problem, asked = true),
                MetricsRange.SIX_MONTHS,
                words,
                host,
            )
        val block = (failed.body as Body.Failed).problem
        assertEquals("Could not reach your service", block.title)
        assertEquals(
            "The phone could not find overdrive.example.net. Check the address in Settings, or try again on another network.",
            block.cause,
        )
        assertEquals("UnknownHostException · DNS", block.hint)
        assertNull(failed.status)
    }

    @Test
    fun `a stale answer keeps its content and adds the status line with its own time`() {
        val metrics = Samples.metrics("2026-05-25")
        val offline = ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")
        val ui = FitnessUi.of(fresh(metrics).copy(problem = offline), MetricsRange.SIX_MONTHS, words, host)
        assertEquals("Offline · updated 07:40", ui.status?.title)
        assertEquals("Updated 07:40", ui.subtitle)
        assertTrue(ui.body is Body.Content)
    }

    @Test
    fun `refreshing is said by the header and leaves the content`() {
        val ui =
            FitnessUi.of(
                fresh(Samples.metrics("2026-05-25")).copy(refreshing = true),
                MetricsRange.SIX_MONTHS,
                words,
                host,
            )
        assertTrue(ui.refreshing)
        assertTrue(ui.body is Body.Content)
    }

    @Test
    fun `no load yet is the empty state with the design's sentence`() {
        val ui = FitnessUi.of(fresh(Samples.metrics("2026-05-25", noLoad = true)), MetricsRange.SIX_MONTHS, words, host)
        assertEquals(
            Body.Empty(
                "No load yet",
                "Fitness and fatigue start with your first ride on the tablet or your first activity on the watch.",
            ),
            ui.body,
        )
    }

    // region fitness

    @Test
    fun `the fitness tiles are today with the change over four weeks and seven days, and the form word`() {
        val metrics = Samples.metrics("2026-05-25")
        val content = FitnessUi.of(fresh(metrics), MetricsRange.SIX_MONTHS, words, host).content()
        val today = metrics.daily.last()
        val fitness = content.tiles[0]
        assertEquals("Fitness", fitness.label)
        assertEquals(Math.round(today.ctl).toString(), fitness.value)
        val ago28 = metrics.daily[metrics.daily.size - 29]
        assertEquals(
            "${words.change((Math.round(today.ctl) - Math.round(ago28.ctl)).toInt())} in 4 weeks",
            fitness.caption,
        )
        val ago7 = metrics.daily[metrics.daily.size - 8]
        assertEquals(
            "${words.change((Math.round(today.atl) - Math.round(ago7.atl)).toInt())} in 7 days",
            content.tiles[1].caption,
        )
        assertEquals("Form", content.tiles[2].label)
        assertEquals(
            app.overdrive.core.metrics.FormWord
                .of(today.tsb)
                .label,
            content.tiles[2].caption,
        )
    }

    @Test
    fun `the readout follows the selected day and rests on today`() {
        val metrics = Samples.metrics("2026-05-25")
        val content = FitnessUi.of(fresh(metrics), MetricsRange.SIX_MONTHS, words, host).content()
        val rest = content.fitness.readoutAt(null)
        assertEquals("Wed 18 Nov", rest.x)
        assertEquals(listOf("Fitness", "Fatigue"), rest.entries.map { it.name })
        val past = content.fitness.readoutAt(content.fitness.spec.snapXs[10])
        assertEquals(words.day(metrics.daily[10].date), past.x)
        assertEquals(Math.round(metrics.daily[10].ctl).toString(), past.entries[0].value)
        assertTrue(
            content.form
                .readoutAt(null)
                .entries
                .single()
                .value
                .contains(" · "),
        )
    }

    @Test
    fun `the charts draw one point per day, today last, at most 366`() {
        for (range in MetricsRange.entries) {
            val metrics = Samples.metrics(range.from(Samples.TODAY))
            val content = FitnessUi.of(fresh(metrics), range, words, host).content()
            assertEquals(metrics.daily.size, content.fitness.spec.snapXs.size)
            assertTrue(content.fitness.spec.snapXs.size <= 366)
            assertEquals(content.fitness.spec.xMax, content.fitness.spec.restX, 0.0)
        }
    }

    @Test
    fun `the form chart always shows its Training hard band, whatever the data`() {
        val content = FitnessUi.of(fresh(Samples.metrics("2026-05-25")), MetricsRange.SIX_MONTHS, words, host).content()
        val spec = content.form.spec
        assertTrue(spec.yMin <= -30.0 && spec.yMax >= 10.0)
        assertEquals("Training hard", spec.bands.single().label)
        assertTrue(content.form.note!!.startsWith("Fitness minus fatigue. Below −10"))
    }

    @Test
    fun `date ticks follow the range and keep three days from either end`() {
        val six = DateAxis.ticks(MetricsRange.SIX_WEEKS, "2026-10-12", "2026-11-18", words)
        assertEquals(listOf("26 Oct", "9 Nov"), six.map { it.label })
        val months = DateAxis.ticks(MetricsRange.SIX_MONTHS, "2026-05-25", "2026-11-18", words)
        assertEquals(listOf("Jun", "Jul", "Aug", "Sep", "Oct", "Nov"), months.map { it.label })
        val year = DateAxis.ticks(MetricsRange.ONE_YEAR, "2025-11-24", "2026-11-18", words)
        assertEquals(listOf("Jan", "Apr", "Jul", "Oct"), year.map { it.label })
        // A month that starts within three days of the end has no label.
        assertEquals(
            listOf(
                "Jun",
                "Jul",
                "Aug",
                "Sep",
                "Oct",
            ),
            DateAxis.ticks(MetricsRange.SIX_MONTHS, "2026-05-25", "2026-11-02", words).map {
                it.label
            },
        )
    }

    // region power

    private fun power(
        metrics: MetricsResponse,
        answers: FtpAnswers = FtpAnswers(),
        profile: ProfileState = ProfileState(ready = true, profile = Samples.profile()),
    ) = PowerUi.of(fresh(metrics), profile, answers, MetricsRange.SIX_MONTHS, words, host)

    @Test
    fun `the FTP card shows the suggestion with its basis, and Use and Keep answer it`() {
        val card = power(Samples.metrics("2026-05-25")).content().ftp
        assertEquals(250, card.current)
        assertEquals("Set 2 Oct", card.setLine)
        val suggestion = card.answer as FtpUi.Suggestion
        assertEquals(256, suggestion.watts)
        assertEquals("from 3 rides in 6 weeks", suggestion.basis)
        assertEquals(7, suggestion.id)
    }

    @Test
    fun `a suggestion the rider kept, or that the profile already has, is not offered again`() {
        val metrics = Samples.metrics("2026-05-25")
        assertNull(power(metrics, FtpAnswers(setOf(7))).content().ftp.answer)
        val used = ProfileState(ready = true, profile = Samples.profile(ftp = 256))
        assertNull(power(metrics, profile = used).content().ftp.answer)
        assertEquals(256, power(metrics, profile = used).content().ftp.current)
        assertEquals(FtpUi.Queued, power(metrics, FtpAnswers(setOf(7), waiting = 1)).content().ftp.answer)
    }

    @Test
    fun `without a suggestion the card says set and estimated, without an FTP it says so`() {
        val none = power(Samples.metrics("2026-05-25", suggestion = null)).content().ftp
        assertNull(none.answer)
        assertEquals(256, none.estimate)
        val unset =
            PowerUi.content(
                Samples.metrics("2026-05-25", suggestion = null, ftp = null),
                ProfileState(ready = true),
                FtpAnswers(),
                MetricsRange.SIX_MONTHS,
                words,
            )
        assertNull(unset.ftp.current)
    }

    @Test
    fun `the power curve has the 25 durations on a log axis, rests at 20 minutes and reads both curves`() {
        val curve = power(Samples.metrics("2026-05-25")).content().curve!!
        assertEquals(25, curve.spec.snapXs.size)
        assertTrue(curve.spec.xLogarithmic)
        assertEquals(1200.0, curve.spec.restX, 0.0)
        val rest = curve.readoutAt(null)
        assertEquals("20 min", rest.x)
        assertEquals(listOf("6 months", "Best ever"), rest.entries.map { it.name })
        assertTrue(rest.entries.all { it.value.endsWith(" W") })
        // A duration longer than the longest range ride: the range line has no value there (the line ends).
        val last = curve.readoutAt(7200.0)
        assertEquals("—", last.entries[0].value)
        assertEquals("FTP 250", curve.spec.refLines.single().label)
    }

    @Test
    fun `best efforts list the five durations that exist, each with its ride`() {
        val efforts = power(Samples.metrics("2026-05-25")).content().bestEfforts
        assertEquals(listOf("5 s", "1 min", "5 min", "20 min", "1 h"), efforts.map { it.duration })
        assertTrue(efforts.all { it.rideId == "ride-sweet" })
    }

    @Test
    fun `the FTP history is a step line per day with the estimates as dots and reads the estimate before the day`() {
        val history = power(Samples.metrics("2026-05-25")).content().ftpHistory
        val line = history.spec.lines.single()
        assertTrue(line.stepped)
        assertEquals(history.spec.snapXs.size, line.xs.size)
        assertEquals(250.0, line.ys.last(), 0.0)
        assertEquals(238.0, line.ys.first(), 0.0)
        val rest = history.readoutAt(null)
        assertEquals(listOf("FTP", "Estimated"), rest.entries.map { it.name })
        assertEquals("250 W", rest.entries[0].value)
    }

    @Test
    fun `a chart with a single point still builds, with one thing to select`() {
        val full = Samples.metrics("2026-05-25")
        val one =
            full.copy(
                powerCurve =
                    app.overdrive.core.metrics.PowerCurves(
                        full.powerCurve.range.take(1),
                        full.powerCurve.allTime.take(1),
                    ),
            )
        val content = power(one).content()
        assertEquals(1, content.curve!!.spec.snapXs.size)
        assertEquals("no listed duration was ridden long enough", 0, content.bestEfforts.size)
        assertEquals("1 s", content.curve!!.readoutAt(null).x)
    }

    // region rides

    @Test
    fun `rides are grouped by month with the month's load, rows of two lines and a card position`() {
        val view = RidesView(fresh(Samples.ridesPage()), RidesList(Samples.rideItems()))
        val content = RidesUi.of(view, words, host).content()
        val months = content.entries.filterIsInstance<RidesEntry.Month>()
        assertEquals(listOf("November", "October"), months.map { it.month })
        assertEquals("Load ${words.load(74.0 + 97.0 + 38.0 + 81.0 + 68.0 + 27.0 + 74.0)}", months[0].total)
        val rows = content.entries.filterIsInstance<RidesEntry.Row>().map { it.row }
        assertEquals("Sweet spot 3×10", rows[0].title)
        assertEquals("Wed 18 · 1:05:12", rows[0].subtitle)
        assertEquals("74", rows[0].load)
        assertEquals("196 W", rows[0].caption)
        assertEquals(CardPosition.First, rows[0].position)
        assertEquals(CardPosition.Middle, rows[1].position)
        assertEquals(CardPosition.Last, rows[6].position)
        assertEquals(CardPosition.First, rows[7].position)
    }

    @Test
    fun `a watch activity says Watch, shows heart rate in bpm, and is marked by words, not colour`() {
        val view = RidesView(fresh(Samples.ridesPage()), RidesList(Samples.rideItems()))
        val activity =
            RidesUi
                .of(view, words, host)
                .content()
                .entries
                .filterIsInstance<RidesEntry.Row>()
                .map {
                    it.row
                }.first { it.watch }
        assertEquals("Beat Saber", activity.title)
        assertEquals("Watch · Sun 15 · 42:10", activity.subtitle)
        assertEquals("142 bpm", activity.caption)
    }

    @Test
    fun `no rides is the empty state and a single ride in a month is a card of one`() {
        val empty = RidesUi.of(RidesView(fresh(Samples.ridesPage(emptyList())), RidesList()), words, host)
        assertEquals("No rides yet", (empty.body as Body.Empty).heading)
        val one =
            RidesView(fresh(Samples.ridesPage(Samples.rideItems().take(1))), RidesList(Samples.rideItems().take(1)))
        assertEquals(
            CardPosition.Only,
            RidesUi
                .of(one, words, host)
                .content()
                .entries
                .filterIsInstance<RidesEntry.Row>()
                .single()
                .row.position,
        )
    }

    // region ride detail

    @Test
    fun `the ride's tiles, planned workout, laps and zones come from the summary and the plan`() {
        val ride = RideDetailUi.of(fresh(Samples.rideDetail()), words, host).content()
        assertEquals("VO2 5×3", ride.name)
        assertEquals("Thu 12 Nov · 18:02–18:58", ride.whenLine)
        assertEquals(
            listOf(
                "Duration",
                "Normalised",
                "Load",
                "Average",
                "Intensity",
                "Work",
                "Avg heart",
                "Max heart",
                "Moving",
            ),
            ride.tiles.map {
                it.label
            },
        )
        assertEquals("56:00", ride.tiles[0].value)
        assertEquals("211", ride.tiles[1].value)
        assertEquals("0.85", ride.tiles[4].value)
        assertEquals("635", ride.tiles[5].value)
        assertEquals("VO2 5×3 · all 15 steps ridden", ride.plan?.title)
        assertEquals("Efforts 287–291 W against 288 W", ride.plan?.efforts)
        assertEquals(15, ride.laps!!.size)
        assertEquals("ramp", ride.laps!![0].target)
        assertEquals("of 288", ride.laps!![3].target)
        assertEquals(7, ride.zones.size)
    }

    @Test
    fun `a ride without a plan has no planned card and no lap table, a missing reading is the dash`() {
        val ride =
            RideDetailUi
                .of(
                    fresh(Samples.rideDetail(withPlan = false, withHeartRate = false)),
                    words,
                    host,
                ).content()
        assertNull(ride.plan)
        assertNull(ride.laps)
        assertEquals("—", ride.tiles[6].value ?: "—")
        assertTrue(ride.chart!!.noHeartRate)
        assertNull(ride.chart!!.spec.plan)
    }

    @Test
    fun `the ride readout rests on the whole ride and names the step of a selected second`() {
        val chart = RideDetailUi.of(fresh(Samples.rideDetail()), words, host).content().chart!!
        val rest = chart.readoutAt(null)
        assertEquals("Whole ride", rest.x)
        assertEquals(listOf("Power avg", "Heart avg", "W′ lowest"), rest.entries.map { it.name })
        assertEquals("7.2 kJ at 48:50", rest.entries[2].value)
        val inEffort = chart.readoutAt(1100.0)
        assertEquals("18:20", inEffort.x)
        assertTrue(inEffort.entries.last().value, inEffort.entries.last().value.startsWith("VO2 1 of 5 · Z5"))
        assertEquals(chart.spec.seconds.size, chart.table.rows.size)
    }

    @Test
    fun `a ride with no samples has no chart and does not crash`() {
        val bare = Samples.rideDetail().copy(series = emptyList(), laps = emptyList())
        val ride = RideDetailUi.of(fresh(bare), words, host).content()
        assertNull(ride.chart)
        assertEquals(9, ride.tiles.size)
    }

    @Test
    fun `an activity says what it knows and that it counts toward fatigue`() {
        val activity = ActivityWords.of(Samples.rideItems()[2], words)
        assertEquals("Beat Saber", activity.name)
        assertEquals("Sun 15 Nov · from the watch", activity.whenLine)
        assertEquals("38", activity.load.value)
        assertEquals(
            "Counts toward fatigue, form and recovery. Not used for the power curve, FTP or aerobic drift.",
            activity.countsLine,
        )
    }

    // region today

    private val noRecovery = ResourceState<RecoveryResponse>(loaded = true, asked = true)

    private fun recovery(state: app.overdrive.core.wellness.RecoveryState) = fresh(WatchSamples.recovery(state))

    private val connected =
        HealthStatus(
            checked = true,
            access = HealthAccess(HealthDataType.entries.toSet(), history = true, background = true),
        )
    private val declined = HealthStatus(checked = true)

    private fun today(
        recovery: ResourceState<RecoveryResponse> = noRecovery,
        metrics: ResourceState<MetricsResponse> = fresh(Samples.metrics("2026-05-25")),
        workouts: ResourceState<app.overdrive.core.plan.WorkoutsResponse> = fresh(Samples.workouts()),
        rides: RidesView = RidesView(),
        answers: FtpAnswers = FtpAnswers(),
        justUsed: Int? = null,
        insights: InsightsView = InsightsView(),
        watch: HealthStatus = HealthStatus(),
    ) = TodayUi.of(
        recovery,
        metrics,
        workouts,
        rides,
        ProfileState(ready = true, profile = Samples.profile()),
        answers,
        justUsed,
        Samples.TODAY,
        words,
        host,
        insights,
        watch,
    )

    @Test
    fun `today's header is the date and when it was updated`() {
        assertEquals("Wednesday 18 November · updated 07:40", today().subtitle)
        val failing =
            today(
                metrics =
                    ResourceState(
                        loaded = true,
                        problem = ServiceProblem(ProblemKind.NO_NETWORK, "x"),
                        asked = true,
                    ),
                workouts =
                    ResourceState(
                        loaded = true,
                        problem = ServiceProblem(ProblemKind.NO_NETWORK, "x"),
                        asked = true,
                    ),
            )
        assertEquals("Wednesday 18 November", failing.subtitle)
        assertEquals("Could not reach your service", (failing.body as Body.Failed).problem.title)
    }

    @Test
    fun `the planned card has the name, the duration, the load and the intensity, and a profile`() {
        val planned = today().content().planned!!.single()
        assertEquals("Sweet spot 3×10", planned.name)
        assertTrue(planned.subtitle, Regex("""(\d+:)?\d\d:\d\d · load \d+ · IF 0\.\d\d""").matches(planned.subtitle))
        assertNotNull(planned.profile)
        assertNull(planned.done)
    }

    @Test
    fun `the planned card says done, with the end time, when a ride of that name was ridden today`() {
        val ridden = Samples.ride("ride-today", Samples.TODAY, "Sweet spot 3×10", 3900, 74.0, 196.0)
        val rides = RidesView(fresh(Samples.ridesPage(listOf(ridden))), RidesList(listOf(ridden)))
        val planned = today(rides = rides).content().planned!!.single()
        assertEquals("Done 18:05", planned.done)
        assertEquals("ride-today", planned.rideId)
    }

    @Test
    fun `nothing planned today is an empty list, and no plan source is no card`() {
        assertEquals(
            emptyList<PlannedRowUi>(),
            today(workouts = fresh(Samples.workouts(withToday = false))).content().planned,
        )
        assertNull(today(workouts = fresh(Samples.workouts(configured = false))).content().planned)
    }

    @Test
    fun `the FTP suggestion is offered on today with its words, and Use then says what happened`() {
        val ftp = today().content().ftp as FtpUi.Suggestion
        assertEquals(256, ftp.watts)
        assertEquals(6, ftp.windowWeeks)
        assertEquals(FtpUi.Used(256, waiting = false), today(justUsed = 256).content().ftp)
        assertNull(today(metrics = fresh(Samples.metrics("2026-05-25", suggestion = null))).content().ftp)
    }

    // region recovery

    @Test
    fun `with Health Connect declined the recovery card says where to allow it, and never a state`() {
        val recovery = today(watch = declined).content().recovery!!
        assertEquals(RecoveryLook.Learning, recovery.look)
        assertEquals("Learning your usual range", recovery.heading)
        val allow = "Allow Health Connect in Settings to rate recovery."
        assertEquals("Overdrive rates recovery after 14 nights with the watch. $allow", recovery.reason)
        assertEquals(0f, recovery.progress)
    }

    @Test
    fun `while the rating has not come and Health Connect is allowed there is no card yet`() {
        assertNull(today(watch = connected).content().recovery)
        assertNull(today().content().recovery)
    }

    @Test
    fun `ready, caution and rest are the service's words with its guidance, and the facts in its order`() {
        val ready =
            today(
                recovery(app.overdrive.core.wellness.RecoveryState.READY),
                watch = connected,
            ).content().recovery!!
        assertEquals("Recovery · this morning", ready.label)
        assertEquals(RecoveryLook.Ready, ready.look)
        assertEquals("All four signals are in your usual range. Train as planned.", ready.reason)
        assertEquals(
            listOf("Resting heart rate", "Sleep", "Respiratory rate", "Oxygen saturation"),
            ready.facts.map { it.name },
        )
        assertEquals(listOf("52", "7:40", "14.2", "96"), ready.facts.map { it.value })
        assertEquals(listOf("bpm", null, "/min", "%"), ready.facts.map { it.unit })
        assertEquals(
            listOf("usual 50–56", "usual 6:50–7:50", "usual 13.6–14.6", "usual 95–98"),
            ready.facts.map { it.caption },
        )
        assertTrue(ready.facts.none { it.outside })

        val caution =
            today(
                recovery(app.overdrive.core.wellness.RecoveryState.CAUTION),
                watch = connected,
            ).content().recovery!!
        assertEquals(RecoveryLook.Caution, caution.look)
        val reason = "Resting heart rate is above your usual range for the second morning."
        assertEquals("$reason Train, but keep the hard parts short.", caution.reason)
        assertEquals("above usual 50–56", caution.facts.first().caption)
        assertTrue(caution.facts.first().outside)
        assertEquals("restingHr", caution.facts.first().key)

        val rest =
            today(
                recovery(app.overdrive.core.wellness.RecoveryState.REST),
                watch = connected,
            ).content().recovery!!
        assertEquals(RecoveryLook.Rest, rest.look)
        assertEquals("5:30", rest.facts[1].value)
        assertEquals("below usual 6:50–7:50", rest.facts[1].caption)
        assertEquals("above usual 13.6–14.6", rest.facts[2].caption)
    }

    @Test
    fun `an older rating says its day so an old verdict never passes as this morning's`() {
        val yesterday = WatchSamples.recovery(app.overdrive.core.wellness.RecoveryState.READY, date = "2026-11-17")
        assertEquals("Recovery · yesterday", today(fresh(yesterday), watch = connected).content().recovery!!.label)
    }

    @Test
    fun `learning shows the nights so far, a bar and the facts as last night's figures`() {
        val learning =
            today(
                recovery(app.overdrive.core.wellness.RecoveryState.LEARNING),
                watch = connected,
            ).content().recovery!!
        assertEquals(RecoveryLook.Learning, learning.look)
        assertEquals("Learning your usual range", learning.heading)
        assertEquals("Overdrive rates recovery after 14 nights with the watch. 9 nights so far.", learning.reason)
        assertEquals(9f / 14f, learning.progress!!, 1e-6f)
        assertEquals(listOf("last night", "last night", "last night", "no reading"), learning.facts.map { it.caption })
        assertNull(learning.facts.last().value)
    }

    @Test
    fun `no data last night is its own card`() {
        val none =
            today(
                recovery(app.overdrive.core.wellness.RecoveryState.NO_DATA),
                watch = connected,
            ).content().recovery!!
        assertEquals(RecoveryLook.NoData, none.look)
        assertEquals("No reading from last night", none.heading)
        assertEquals("Wear the watch to sleep, then open Overdrive in the morning.", none.reason)
    }

    @Test
    fun `a bedtime fact is a clock time and says later or earlier than usual`() {
        val fact =
            app.overdrive.core.wellness.RecoveryFact(
                app.overdrive.core.wellness.RecoveryMetric.BEDTIME_MINUTES,
                "Bedtime",
                -10.0,
                app.overdrive.core.wellness.RecoveryUnit.MINUTES,
                -70.0,
                -20.0,
                app.overdrive.core.wellness.Deviation.ABOVE,
                1,
            )
        val data = RecoveryWords.fact(fact)
        assertEquals("23:50", data.value)
        assertNull(data.unit)
        assertEquals("later than usual 22:50–23:40", data.caption)
        assertTrue(data.outside)
    }

    @Test
    fun `a watch that stopped reading says so on today, and a fresh read says nothing`() {
        val read = TestClock.now - 3_600_000
        assertNull(today(watch = connected.copy(firstReadDone = true, lastReadMillis = read)).status)
        val late =
            today(
                watch = connected.copy(firstReadDone = true, lastReadMillis = TestClock.now - 40 * 3_600_000),
            ).status
        assertEquals("The phone stopped this morning's read. The figures are from yesterday.", late?.cause)
        val revoked =
            today(
                watch = connected.copy(readIssue = app.overdrive.core.health.ReadIssue.PERMISSION_REVOKED),
            ).status
        assertEquals("Overdrive can no longer read your watch data. Allow it in Settings.", revoked?.cause)
        assertEquals(ProblemAction.Settings, revoked?.action)
    }

    // region notes

    private fun emptyNotes() = view(WatchSamples.insights().copy(insights = emptyList()))

    private fun view(response: app.overdrive.core.insights.InsightsResponse) =
        InsightsView(fresh(response), InsightsList(response.insights))

    @Test
    fun `today's note is the latest one with its time, and All notes is offered`() {
        val card = today(insights = view(WatchSamples.insights()), watch = connected).content().note!!
        assertEquals("Morning note · 07:30", card.note!!.label)
        assertTrue(card.note!!.text.startsWith("You slept 7 hours 40"))
        assertTrue(card.hasAll)
        assertNull(card.waiting)
    }

    @Test
    fun `an earlier note shows with its day, and before ten with none yet today it says when it is written`() {
        val earlier = view(WatchSamples.insights(withToday = false))
        val before = today(insights = earlier, watch = connected).content().note!!
        assertNull(before.note)
        assertEquals(NotesUi.NOT_YET, before.waiting)
        assertTrue(NotesUi.NOT_YET.startsWith("No note yet today."))
        assertTrue(before.hasAll)
        // Without the watch no morning note is coming: the latest note is shown with its day.
        val without = today(insights = earlier, watch = declined).content().note!!
        assertEquals("Heads-up · yesterday 07:10", without.note!!.label)
        assertTrue(without.note!!.alert)
    }

    @Test
    fun `never a note is no card`() {
        assertNull(today(insights = emptyNotes(), watch = declined).content().note)
    }

    @Test
    fun `the notes screen groups by day, labels each kind, links the ride and carries the open topic`() {
        val ui = NotesUi.screen(view(WatchSamples.insights()), RidesView(), Samples.TODAY, words, host)
        val content = ui.content()
        val headings = listOf("Today", "Yesterday", "Monday 16 November", "Tuesday 10 November")
        assertEquals(headings, content.groups.map { it.heading })
        val labels =
            listOf(
                "Morning note · 07:30",
                "Heads-up · 07:10",
                "After your ride · 19:42",
                "Weekly review · 08:00",
                "Plan check · 07:00",
            )
        assertEquals(labels, content.groups.flatMap { it.notes }.map { it.label })
        val ride = content.groups[2].notes.first()
        assertEquals("ride-1", ride.ride?.rideId)
        assertEquals("the ride's name is not known when the list was not fetched", "Open the ride", ride.ride?.name)
        assertEquals(WatchSamples.topic().openingQuestion, ride.topic?.question)
        assertNull(content.groups[0].notes.single().topic)
    }

    @Test
    fun `a ride note names its ride from the rides list, and a closed topic is not shown`() {
        val threshold = Samples.ride("ride-1", "2026-11-16", "Threshold 2×20", 3900, 74.0, 196.0)
        val rides = RidesView(fresh(Samples.ridesPage(listOf(threshold))), RidesList(listOf(threshold)))
        val note =
            NotesUi.note(
                WatchSamples.insights().insights.first { it.rideId != null },
                rides,
                words,
                withDay = false,
            )
        assertEquals("Threshold 2×20", note.ride?.name)
        val closed =
            WatchSamples
                .insights()
                .insights
                .first {
                    it.rideId != null
                }.copy(topic = WatchSamples.topic(status = app.overdrive.core.insights.TopicStatus.DISCUSSED))
        assertNull(NotesUi.note(closed, rides, words, withDay = false).topic)
    }

    @Test
    fun `the notes screen has its own empty, loading and error states`() {
        fun screen(view: InsightsView) = NotesUi.screen(view, RidesView(), Samples.TODAY, words, host).body
        val text = "Notes are written after your morning's watch data and after each ride."
        assertEquals(Body.Empty("No notes yet", text), screen(emptyNotes()))
        assertEquals(Body.Loading("Loading your notes…"), screen(InsightsView(ResourceState(loaded = true))))
        val offline = ServiceProblem(ProblemKind.NO_NETWORK, "x")
        val failed = screen(InsightsView(ResourceState(loaded = true, problem = offline, asked = true)))
        assertEquals("Could not reach your service", (failed as Body.Failed).problem.title)
    }
}
