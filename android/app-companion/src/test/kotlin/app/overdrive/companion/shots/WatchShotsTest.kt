package app.overdrive.companion.shots

import app.overdrive.companion.RationaleView
import app.overdrive.companion.Samples
import app.overdrive.companion.WatchSamples
import app.overdrive.companion.model.Body
import app.overdrive.companion.model.HealthWords
import app.overdrive.companion.model.HeartRateWords
import app.overdrive.companion.model.NotesUi
import app.overdrive.companion.model.TestClock
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.companion.model.WatchAccess
import app.overdrive.companion.model.WellnessContent
import app.overdrive.companion.model.WellnessUi
import app.overdrive.companion.ui.BackgroundStepState
import app.overdrive.companion.ui.BackgroundStepView
import app.overdrive.companion.ui.HealthStepView
import app.overdrive.companion.ui.NotesActions
import app.overdrive.companion.ui.NotesView
import app.overdrive.companion.ui.ReadingStepView
import app.overdrive.companion.ui.SettingsActions
import app.overdrive.companion.ui.SettingsUiState
import app.overdrive.companion.ui.SettingsView
import app.overdrive.companion.ui.WellnessActions
import app.overdrive.companion.ui.WellnessView
import app.overdrive.companion.ui.healthStepState
import app.overdrive.core.health.HealthAccess
import app.overdrive.core.health.HealthAvailability
import app.overdrive.core.health.HealthDataType
import app.overdrive.core.health.HealthStatus
import app.overdrive.core.health.ReadIssue
import app.overdrive.core.health.ReadProgress
import app.overdrive.core.health.SentCounts
import app.overdrive.core.remote.InsightsList
import app.overdrive.core.remote.InsightsView
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.RidesList
import app.overdrive.core.remote.RidesView
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.core.remote.WellnessData
import app.overdrive.core.remote.WellnessRange
import app.overdrive.uikit.chart.ChartSelection
import org.junit.Test

/**
 * The watch data's screens and states (design/companion.md §4.1, §4.2, §4.5, §4.9, §4.10, §5) at 412 dp wide, from synthetic answers,
 * into build/shots: Wellness in every range and state, Notes, Settings' Health Connect and Heart rate groups, the three steps of the
 * first run and the first read.
 */
class WatchShotsTest : ShotTest() {
    private val words = TestClock.words
    private val host = "overdrive.example.net"
    private val none = {}
    private val actions = WellnessActions(none, none, none, {}, none)
    private val offline = ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")
    private val dns =
        ServiceProblem(ProblemKind.NAME_NOT_FOUND, "Cannot find the service address (DNS)", "UnknownHostException")

    // region wellness

    private fun wellness(
        state: ResourceState<WellnessData> = fresh(WatchSamples.wellnessData()),
        range: WellnessRange = WellnessRange.FOUR_WEEKS,
        access: WatchAccess = WatchAccess.Some,
    ) = WellnessUi.of(state, range, access, words, host)

    private fun content(state: ResourceState<WellnessData> = fresh(WatchSamples.wellnessData())) =
        (wellness(state).body as Body.Content<WellnessContent>).value

    @Test
    fun wellnessFourWeeks() =
        shot("wellness--four-weeks") { WellnessView(wellness(), WellnessRange.FOUR_WEEKS, actions) }

    @Test
    fun wellnessPastDaySelectedWithoutOxygen() {
        val state = fresh(WatchSamples.wellnessData(withSpo2 = false))
        val selection = ChartSelection()
        selection.select(content(state).lastDay - 3)
        shot("wellness--selected") {
            WellnessView(wellness(state), WellnessRange.FOUR_WEEKS, actions, selection = selection)
        }
    }

    @Test
    fun wellnessOutsideTheUsualRangeForDays() =
        shot("wellness--above-usual") {
            WellnessView(
                wellness(fresh(WatchSamples.wellnessData(aboveRun = 3, withSleepingHr = true, withWeight = true))),
                WellnessRange.FOUR_WEEKS,
                actions,
            )
        }

    @Test
    fun wellnessThreeMonthsAndAYear() {
        shot("wellness--three-months") {
            WellnessView(
                wellness(fresh(WatchSamples.wellnessData(WellnessRange.THREE_MONTHS)), WellnessRange.THREE_MONTHS),
                WellnessRange.THREE_MONTHS,
                actions,
            )
        }
    }

    @Test
    fun wellnessOneYear() =
        shot("wellness--year") {
            WellnessView(
                wellness(
                    fresh(WatchSamples.wellnessData(WellnessRange.ONE_YEAR, withWeight = true)),
                    WellnessRange.ONE_YEAR,
                ),
                WellnessRange.ONE_YEAR,
                actions,
            )
        }

    @Test
    fun wellnessLearning() =
        shot("wellness--learning") {
            WellnessView(wellness(fresh(WatchSamples.wellnessData(learning = true))), WellnessRange.FOUR_WEEKS, actions)
        }

    @Test
    fun wellnessAGapDay() =
        shot("wellness--gap") {
            WellnessView(
                wellness(fresh(WatchSamples.wellnessData(gapDay = "2026-11-10"))),
                WellnessRange.FOUR_WEEKS,
                actions,
            )
        }

    @Test
    fun wellnessValuesSheet() {
        val ui = wellness()
        val table = (ui.body as Body.Content<WellnessContent>).value.cards.first().table
        shot("wellness--values") { WellnessView(ui, WellnessRange.FOUR_WEEKS, actions, initialSheet = table) }
    }

    @Test
    fun wellnessNoAccess() =
        shot("wellness--no-access") {
            WellnessView(wellness(noReadingsAtAll(), access = WatchAccess.None), WellnessRange.FOUR_WEEKS, actions)
        }

    private fun noReadingsAtAll(): ResourceState<WellnessData> {
        val response = WatchSamples.wellnessResponse("2026-10-22", Samples.TODAY)
        return fresh(WellnessData.of(response.copy(days = response.days.map { noReadings(it) })))
    }

    private fun noReadings(day: app.overdrive.core.wellness.WellnessDay): app.overdrive.core.wellness.WellnessDay {
        val none = app.overdrive.core.wellness.Reading(null, null, null, null, null, 0)
        return day.copy(
            restingHr = none,
            sleepSeconds = none,
            bedtimeMinutes = none,
            wakeMinutes = none,
            respiratoryRate = none,
            spo2 = none,
            sleepingHr = none,
            weightKg = null,
            weightKg7d = null,
        )
    }

    @Test
    fun wellnessEmptyWhenConnected() =
        shot("wellness--empty") {
            WellnessView(wellness(noReadingsAtAll()), WellnessRange.FOUR_WEEKS, actions)
        }

    @Test
    fun wellnessLoading() =
        shot("wellness--loading") {
            WellnessView(wellness(ResourceState(loaded = true, refreshing = true)), WellnessRange.FOUR_WEEKS, actions)
        }

    @Test
    fun wellnessError() =
        shot("wellness--error") {
            WellnessView(
                wellness(ResourceState(loaded = true, problem = dns, asked = true)),
                WellnessRange.FOUR_WEEKS,
                actions,
            )
        }

    @Test
    fun wellnessStaleOffline() =
        shot("wellness--stale") {
            WellnessView(
                wellness(fresh(WatchSamples.wellnessData()).copy(problem = offline)),
                WellnessRange.FOUR_WEEKS,
                actions,
            )
        }

    // region notes

    private val notesActions = NotesActions(none, none, none, none, {}, {})

    private fun notes(
        view: InsightsView,
        rides: RidesView = RidesView(),
    ) = NotesUi.screen(view, rides, Samples.TODAY, words, host)

    private fun view() =
        WatchSamples.insights().let { InsightsView(fresh(it), InsightsList(it.insights, hasMore = true)) }

    @Test
    fun notesList() {
        val ride = Samples.ride("ride-1", "2026-11-16", "Threshold 2×20", 3900, 74.0, 196.0)
        val rides = RidesView(fresh(Samples.ridesPage(listOf(ride))), RidesList(listOf(ride)))
        shot("notes--list") { NotesView(notes(view(), rides), notesActions) }
    }

    @Test
    fun notesEmpty() =
        shot("notes--empty") {
            val response = WatchSamples.insights().copy(insights = emptyList())
            NotesView(notes(InsightsView(fresh(response))), notesActions)
        }

    @Test
    fun notesError() =
        shot("notes--error") {
            NotesView(notes(InsightsView(ResourceState(loaded = true, problem = dns, asked = true))), notesActions)
        }

    @Test
    fun notesLoading() =
        shot("notes--loading") {
            NotesView(notes(InsightsView(ResourceState(loaded = true, refreshing = true))), notesActions)
        }

    // region settings

    private val all = HealthAccess(HealthDataType.entries.toSet(), history = true, background = true)
    private val noSleep = all.copy(granted = all.granted - HealthDataType.SLEEP)

    private fun health(
        status: HealthStatus,
        battery: Boolean = true,
    ) = HealthWords.settings(status, battery, words)

    private val sent =
        SentCounts(
            214,
            200,
            14,
            0,
            mapOf(
                "restingHeartRate" to 1,
                "sleepSession" to 1,
                "heartRateSamples" to 40,
                "exerciseSession" to 2,
                "respiratoryRate" to 1,
            ),
        )

    private val settingsBase =
        SettingsUiState(
            address = "overdrive.example.net",
            addressMistake = null,
            notEncrypted = false,
            token = "tok-3f9a7c1e5b2d4a6f",
            tokenRefused = false,
            lastSyncValue = "Today 07:40",
            lastSyncNote = "Sent 214 watch records · received your latest figures.",
            syncing = false,
            version = "0.5.0",
            health =
                health(
                    HealthStatus(
                        checked = true,
                        access = all,
                        lastReadMillis = TestClock.fetched,
                        lastSentMillis = TestClock.fetched,
                        lastSent = sent,
                    ),
                ),
            heartRate =
                HeartRateWords.of(
                    Samples.profile().copy(
                        maxHeartRate = 186,
                        thresholdHeartRate = 168,
                        estimatedMaxHeartRate = 184,
                        estimatedThresholdHeartRate = 167,
                    ),
                ),
        )

    private val settingsActions = SettingsActions(none, {}, {}, none, none)

    @Test
    fun settingsWithHealthConnect() = shot("settings--health") { SettingsView(settingsBase, settingsActions) }

    @Test
    fun settingsPartialAndBlocked() =
        shot("settings--health-problems") {
            val status =
                HealthStatus(
                    checked = true,
                    access = noSleep,
                    lastReadMillis =
                        TestClock.fetched - 22 * 3_600_000L,
                    waitingRecords = 312,
                    waitingBatches = 2,
                    uploadProblem = offline,
                    readIssue = ReadIssue.READ_FAILED,
                )
            SettingsView(
                settingsBase.copy(
                    lastSyncValue = "Yesterday 21:10",
                    lastSyncNote =
                        "Nothing sent since then. 312 watch records are waiting. " +
                            "No network connection. Pull down to try again.",
                    health = health(status, battery = false),
                ),
                settingsActions,
            )
        }

    @Test
    fun settingsNothingAllowed() =
        shot("settings--health-none") {
            SettingsView(
                settingsBase.copy(
                    health = health(HealthStatus(checked = true, access = HealthAccess(emptySet(), false, false))),
                ),
                settingsActions,
            )
        }

    @Test
    fun settingsHealthConnectMissing() =
        shot("settings--health-missing") {
            SettingsView(
                settingsBase.copy(
                    health = health(HealthStatus(checked = true, availability = HealthAvailability.UPDATE_REQUIRED)),
                ),
                settingsActions,
            )
        }

    @Test
    fun settingsHeartRateNotSetWithEstimates() =
        shot("settings--heart-rate-unset") {
            SettingsView(
                settingsBase.copy(
                    heartRate =
                        HeartRateWords.of(
                            Samples.profile().copy(estimatedMaxHeartRate = 184, estimatedThresholdHeartRate = 167),
                        ),
                ),
                settingsActions,
            )
        }

    // region first run

    private val allAsked = HealthStatus(checked = true, access = all)

    @Test
    fun firstRunPermissionList() =
        shot("onboarding--health") {
            HealthStepView(healthStepState(HealthStatus(checked = true), host, asked = false), none, none)
        }

    @Test
    fun firstRunPartiallyAllowed() =
        shot("onboarding--partial") {
            HealthStepView(
                healthStepState(HealthStatus(checked = true, access = noSleep), host, asked = true),
                none,
                none,
            )
        }

    @Test
    fun firstRunHealthConnectMissing() =
        shot("onboarding--health-missing") {
            HealthStepView(
                healthStepState(
                    HealthStatus(checked = true, availability = HealthAvailability.NOT_INSTALLED),
                    host,
                    asked = false,
                ),
                none,
                none,
            )
        }

    @Test
    fun firstRunBackground() {
        val state = BackgroundStepState(batteryUnrestricted = true, healthConnectOff = false)
        shot("onboarding--background") { BackgroundStepView(state, none, none) }
    }

    @Test
    fun firstRunBackgroundBlocked() =
        shot("onboarding--background-blocked") {
            BackgroundStepView(BackgroundStepState(batteryUnrestricted = false, healthConnectOff = true), none, none)
        }

    @Test
    fun firstRunReading() =
        shot("onboarding--reading") {
            val progress = ReadProgress(5, 12, 1240, 3100)
            ReadingStepView(HealthWords.fraction(progress), HealthWords.progress(progress), null, none)
        }

    @Test
    fun firstRunReadingStopped() =
        shot("onboarding--reading-problem") {
            ReadingStepView(0.25f, "Starting…", HealthWords.readProblem(ReadIssue.UNAVAILABLE), none)
        }

    @Test
    fun rationale() = shot("onboarding--rationale") { RationaleView(none) }
}
