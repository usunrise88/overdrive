package app.overdrive.companion.shots

import androidx.compose.foundation.layout.Box
import app.overdrive.companion.RepeatSamples
import app.overdrive.companion.RidesMode
import app.overdrive.companion.Samples
import app.overdrive.companion.model.ActivityWords
import app.overdrive.companion.model.FitShown
import app.overdrive.companion.model.FitWords
import app.overdrive.companion.model.RepeatDetailUi
import app.overdrive.companion.model.RepeatsUi
import app.overdrive.companion.model.RidesUi
import app.overdrive.companion.model.TestClock
import app.overdrive.companion.model.TestClock.fresh
import app.overdrive.companion.ui.ActivityView
import app.overdrive.companion.ui.FitImportView
import app.overdrive.companion.ui.RepeatDetailActions
import app.overdrive.companion.ui.RepeatDetailView
import app.overdrive.companion.ui.RepeatsActions
import app.overdrive.companion.ui.RepeatsView
import app.overdrive.companion.ui.RidesActions
import app.overdrive.companion.ui.RidesView
import app.overdrive.companion.ui.SettingsActions
import app.overdrive.companion.ui.SettingsUiState
import app.overdrive.companion.ui.SettingsView
import app.overdrive.companion.ui.ValuesSheetFor
import app.overdrive.core.metrics.RidesResponse
import app.overdrive.core.outdoor.ActivityImportReason
import app.overdrive.core.outdoor.FitOutcome
import app.overdrive.core.outdoor.FitRefusal
import app.overdrive.core.outdoor.FitStatus
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.RidesList
import app.overdrive.core.remote.RidesView
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.uikit.chart.ChartSelection
import org.junit.Test

/**
 * The repeated sessions and the outdoor ride's screens (design/companion.md §4.11, §4.12) at 412 dp wide, from the synthetic answers,
 * into build/shots: the list in every state, the page of a session (falling, rising, two attempts, partial, no figure, selected, with
 * its Values sheet), the Rides tab's selector, the outdoor ride's row and page, Settings' row, and the import screen for each thing that
 * can come of a shared file. Looked at, never committed, never compared to goldens.
 */
class RepeatsShotsTest : ShotTest() {
    private val words = TestClock.words
    private val host = "overdrive.example.net"
    private val none = {}
    private val offline = ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")
    private val dns =
        ServiceProblem(ProblemKind.NAME_NOT_FOUND, "Cannot find the service address (DNS)", "UnknownHostException")
    private val listActions = RepeatsActions(none, none, {}, {})
    private val detailActions = RepeatDetailActions(none, none, none, {})

    // region the list

    private fun list(state: ResourceState<app.overdrive.core.repeats.RepeatsResponse>) =
        RepeatsUi.of(state, words, host)

    @Test
    fun list() = shot("repeats--list") { RepeatsView(list(fresh(RepeatSamples.twoSessions())), listActions) }

    @Test
    fun listOneSessionWithNoFigure() =
        shot("repeats--list-no-figure") {
            val single =
                RepeatSamples.list(
                    RepeatSamples.Listed(RepeatSamples.NAME, RepeatSamples.HASH, listOf(150.0, null), "2026-11-18"),
                )
            RepeatsView(list(fresh(single)), listActions)
        }

    @Test
    fun listStale() =
        shot("repeats--list-stale") {
            RepeatsView(list(fresh(RepeatSamples.twoSessions()).copy(problem = offline)), listActions)
        }

    @Test
    fun listEmpty() = shot("repeats--list-empty") { RepeatsView(list(fresh(RepeatSamples.list())), listActions) }

    @Test
    fun listLoading() =
        shot("repeats--list-loading") {
            RepeatsView(list(ResourceState(loaded = true, refreshing = true)), listActions)
        }

    @Test
    fun listError() =
        shot("repeats--list-error") {
            RepeatsView(list(ResourceState(loaded = true, problem = dns, asked = true)), listActions)
        }

    @Test
    fun ridesTabWithTheSelector() {
        val ui = RidesUi.of(RidesView(fresh(Samples.ridesPage()), RidesList(Samples.rideItems())), words, host)
        shot("rides--history-with-selector") {
            RidesView(ui, RidesActions(none, none, none, {}, none), mode = RidesMode.History)
        }
    }

    // region a session

    private fun detail(
        overall: List<Double?>,
        drift: List<Double?> = overall.map { 3.5 },
        compliance: List<Double?> = overall.map { 100.0 },
        powers: List<Double?> = overall.indices.map { 181.0 + it },
        steps: Int = 2,
    ) = RepeatDetailUi.of(
        fresh(RepeatSamples.detail(overall, drift, compliance, powers = powers, steps = steps)),
        words,
        host,
    )

    private val falling = listOf(152.0, 150.0, 151.0, 147.0, 146.0, 143.0)

    @Test
    fun detailFalling() =
        shot("repeat--detail") {
            RepeatDetailView(
                detail(
                    falling,
                    drift = listOf(6.1, 5.2, 4.9, 4.0, 3.6, 3.3),
                    compliance = listOf(100.0, 100.0, 92.0, 100.0, 100.0, 100.0),
                ),
                detailActions,
            )
        }

    @Test
    fun detailSelectedAttempt() {
        val selection = ChartSelection().also { it.select(2.0) }
        shot("repeat--selected") {
            RepeatDetailView(detail(falling), detailActions, selection = selection)
        }
    }

    @Test
    fun detailRising() =
        shot("repeat--rising") { RepeatDetailView(detail(listOf(141.0, 144.0, 148.0, 152.0)), detailActions) }

    @Test
    fun detailSteady() =
        shot("repeat--steady") { RepeatDetailView(detail(listOf(145.0, 146.0, 145.0, 146.0)), detailActions) }

    @Test
    fun detailTwoAttempts() =
        shot("repeat--two-attempts") { RepeatDetailView(detail(listOf(150.0, 146.0)), detailActions) }

    @Test
    fun detailPartial() =
        shot("repeat--partial") {
            RepeatDetailView(
                detail(
                    listOf(150.0, null, 146.0, 144.0),
                    drift = listOf(null, null, null, null),
                    compliance = listOf(100.0, 47.0, 100.0, 100.0),
                    powers = listOf(181.0, 150.0, null, 184.0),
                ),
                detailActions,
            )
        }

    @Test
    fun detailNoFigure() =
        shot("repeat--no-figure") {
            RepeatDetailView(
                detail(
                    listOf(null, null, null),
                    drift = listOf(null, null, null),
                    compliance = listOf(null, null, null),
                    powers = listOf(null, null, null),
                ),
                detailActions,
            )
        }

    @Test
    fun detailManySteps() = shot("repeat--many-steps") { RepeatDetailView(detail(falling, steps = 6), detailActions) }

    @Test
    fun detailManyAttempts() =
        shot("repeat--many-attempts") {
            RepeatDetailView(detail((0 until 14).map { 152.0 - it * 0.7 + (it % 3) }), detailActions)
        }

    @Test
    fun detailStale() =
        shot("repeat--stale") {
            RepeatDetailView(
                RepeatDetailUi.of(fresh(RepeatSamples.detail(falling)).copy(problem = offline), words, host),
                detailActions,
            )
        }

    @Test
    fun detailLoadingAndError() {
        shot("repeat--loading") {
            RepeatDetailView(
                RepeatDetailUi.of(ResourceState(loaded = true, refreshing = true), words, host),
                detailActions,
            )
        }
    }

    @Test
    fun detailError() {
        shot("repeat--error") {
            RepeatDetailView(
                RepeatDetailUi.of(ResourceState(loaded = true, problem = dns, asked = true), words, host),
                detailActions,
            )
        }
    }

    @Test
    fun detailValuesSheet() {
        val content = RepeatDetailUi.content(RepeatSamples.detail(falling), words)
        shot("repeat--values") {
            Box {
                RepeatDetailView(RepeatDetailUi.of(fresh(RepeatSamples.detail(falling)), words, host), detailActions)
                ValuesSheetFor(checkNotNull(content.heartRate.chart).table, {})
            }
        }
    }

    // region the outdoor ride

    @Test
    fun outdoorRow() {
        val items =
            listOf(Samples.rideItems()[0], Samples.rideItems()[2], RepeatSamples.outdoorItem(), Samples.rideItems()[3])
        val page = RidesResponse("2026-11-18T21:10:00Z", false, null, 50, false, null, items)
        val ui = RidesUi.of(RidesView(fresh(page), RidesList(items)), words, host)
        shot("rides--outdoor") { RidesView(ui, RidesActions(none, none, none, {}, none), mode = RidesMode.History) }
    }

    @Test
    fun outdoorActivityPage() =
        shot("activity--outdoor") { ActivityView(ActivityWords.of(RepeatSamples.outdoorItem(), words), none) }

    // region settings

    private val settingsActions = SettingsActions(none, {}, {}, none, none)

    private val settingsOk =
        SettingsUiState(
            address = "overdrive.example.net",
            addressMistake = null,
            notEncrypted = false,
            token = "tok-3f9a7c1e5b2d4a6f",
            tokenRefused = false,
            lastSyncValue = "Today 07:40",
            lastSyncNote = "Received your latest figures.",
            syncing = false,
            version = "0.4.0",
        )

    @Test
    fun settingsNothingWaiting() = shot("settings--outdoor-none") { SettingsView(settingsOk, settingsActions) }

    @Test
    fun settingsFilesWaiting() {
        val status = FitStatus(waiting = 2, problem = offline)
        shot("settings--outdoor-waiting") {
            SettingsView(
                settingsOk.copy(
                    outdoorValue = FitWords.settingsValue(status),
                    outdoorNote = FitWords.settingsNote(status, host),
                ),
                settingsActions,
            )
        }
    }

    @Test
    fun settingsFileRefused() {
        val status = FitStatus(waiting = 1, parked = listOf(FitRefusal.NOT_CYCLING))
        shot("settings--outdoor-refused") {
            SettingsView(
                settingsOk.copy(
                    outdoorValue = FitWords.settingsValue(status),
                    outdoorNote = FitWords.settingsNote(status, host),
                ),
                settingsActions,
            )
        }
    }

    // region the import screen
}

/** The import screen is a full screen: the phone itself, 412 × 915 dp. */
class ImportShotsTest : DeviceShotTest() {
    private val words = TestClock.words
    private val host = "overdrive.example.net"

    private fun screen(
        name: String,
        what: FitShown,
    ) = shot("import--$name") { FitImportView(FitWords.importScreen(what, words, host), {}, {}) }

    private fun done(outcome: FitOutcome) = FitShown.Done(outcome)

    @Test
    fun importing() = screen("importing", FitShown.Importing)

    @Test
    fun imported() = screen("imported", done(FitOutcome.Imported(RepeatSamples.FIT_ID, RepeatSamples.summary())))

    @Test
    fun alreadyImported() =
        screen(
            "duplicate",
            done(
                FitOutcome.Duplicate(
                    RepeatSamples.FIT_ID,
                    ActivityImportReason.SAME_START_AND_DURATION,
                    RepeatSamples.summary(load = null),
                ),
            ),
        )

    @Test
    fun noNetwork() =
        screen("offline", done(FitOutcome.Waiting(ServiceProblem(ProblemKind.NO_NETWORK, "No network connection"))))

    @Test
    fun tokenRefused() = screen("token", done(FitOutcome.Waiting(ServiceProblem.TokenRejected)))

    @Test
    fun serviceDown() =
        screen(
            "down",
            done(FitOutcome.Waiting(ServiceProblem(ProblemKind.SERVER_ERROR, "Service error (503)", status = 503))),
        )

    @Test
    fun notConnected() = screen("not-connected", done(FitOutcome.Waiting(null)))

    @Test
    fun notAFitFile() = screen("not-fit", done(FitOutcome.Refused(FitRefusal.NOT_A_FIT_FILE)))

    @Test
    fun zip() = screen("zip", done(FitOutcome.Refused(FitRefusal.ARCHIVE)))

    @Test
    fun notCycling() = screen("not-cycling", done(FitOutcome.Refused(FitRefusal.NOT_CYCLING, 422)))

    @Test
    fun emptyRide() = screen("no-ride", done(FitOutcome.Refused(FitRefusal.NO_RIDE, 422)))

    @Test
    fun tooLarge() = screen("too-large", done(FitOutcome.Refused(FitRefusal.TOO_LARGE)))

    @Test
    fun unreadable() = screen("unreadable", FitShown.Unreadable)

    @Test
    fun nothing() = screen("nothing", FitShown.Idle)
}
