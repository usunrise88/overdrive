package app.overdrive.companion

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.overdrive.companion.model.TestClock
import app.overdrive.companion.settings.CompanionSettingsRepository
import app.overdrive.companion.ui.NavState
import app.overdrive.companion.ui.Route
import app.overdrive.companion.ui.SyncWords
import app.overdrive.companion.ui.Tab
import app.overdrive.core.remote.MetricsRange
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.data.settings.SecretText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The stored settings (the token is a secret, a hand-edited file reads as defaults), the back stacks and the Last sync words. */
class SettingsAndNavTest {
    private val directory = Files.createTempDirectory("companion-settings").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store =
        PreferenceDataStoreFactory.create(
            scope = scope,
        ) { directory.resolve("settings.preferences_pb") }
    private val repository = CompanionSettingsRepository(store)

    @After
    fun cleanUp() {
        scope.cancel()
        directory.deleteRecursively()
    }

    private fun read() = runBlocking { repository.settings.first() }

    @Test
    fun `nothing stored is the defaults, which is the first run`() {
        val settings = read()
        assertFalse(settings.isConfigured)
        assertEquals("", settings.serviceUrl)
        assertEquals(MetricsRange.SIX_MONTHS, settings.fitnessRange)
    }

    @Test
    fun `the address, the token and the ranges survive, and the token prints as dots`() {
        runBlocking {
            repository.update {
                it.copy(
                    serviceUrl = "https://overdrive.example.net",
                    token = SecretText("tok-3f9a7c1e"),
                    powerRange = MetricsRange.ONE_YEAR,
                )
            }
        }
        val settings = read()
        assertTrue(settings.isConfigured)
        assertEquals("tok-3f9a7c1e", settings.config.token)
        assertEquals(MetricsRange.ONE_YEAR, settings.powerRange)
        assertFalse(settings.toString().contains("tok-3f9a7c1e"))
        assertFalse(settings.config.toString().contains("tok-3f9a7c1e"))
    }

    @Test
    fun `an address that is not one and a token with control characters are cleaned on the way in and out`() {
        runBlocking {
            repository.update {
                it.copy(
                    serviceUrl = "https://me:secret@example.com",
                    token = SecretText(" a\u0000b "),
                )
            }
        }
        assertEquals("", read().serviceUrl)
        assertEquals("ab", read().config.token)
        // And a hand-edited file is read the same way.
        runBlocking {
            store.edit {
                it[stringPreferencesKey("service_url")] = "not a url"
                it[stringPreferencesKey("fitness_range")] = "FORTNIGHT"
            }
        }
        assertEquals("", read().serviceUrl)
        assertEquals(MetricsRange.DEFAULT, read().fitnessRange)
    }

    // region navigation

    @Test
    fun `each tab has its own back stack and system back goes pushed screen, then Today, then out`() {
        val nav = NavState()
        assertEquals(Tab.Today, nav.tab)
        nav.push(Route.Settings)
        nav.select(Tab.Rides)
        assertNull("another tab's stack is its own", nav.route)
        nav.push(Route.RideDetail("ride-1"))
        assertEquals(Route.RideDetail("ride-1"), nav.route)
        assertTrue(nav.back())
        assertNull(nav.route)
        assertTrue("from Rides, back goes to Today", nav.back())
        assertEquals(Tab.Today, nav.tab)
        assertEquals("Today's pushed screen was kept", Route.Settings, nav.route)
        assertTrue(nav.back())
        assertFalse("on Today's root back leaves the app", nav.back())
    }

    @Test
    fun `tapping the active tab empties its stack and asks its list to scroll up`() {
        val nav = NavState()
        nav.select(Tab.Fitness)
        nav.push(Route.Licences)
        val requests = mutableListOf<Tab>()
        val job = scope.launch(Dispatchers.Unconfined) { nav.scrollToTop.collect { requests += it } }
        nav.reselect(Tab.Fitness)
        assertNull(nav.route)
        assertEquals(listOf(Tab.Fitness), requests)
        job.cancel()
    }

    // region Last sync

    @Test
    fun `the Last sync row says when, and what went wrong and what waits`() {
        val words = TestClock.words
        assertEquals("Today 07:40", SyncWords.lastSync(TestClock.fetched, words))
        assertEquals("Never", SyncWords.lastSync(null, words))
        assertEquals("Nothing received yet.", SyncWords.note(null, null, 0, "x"))
        assertEquals("Received your latest figures.", SyncWords.note(TestClock.fetched, null, 0, "x"))
        assertEquals(
            "Received your latest figures. 2 answers are waiting to be sent.",
            SyncWords.note(TestClock.fetched, null, 2, "x"),
        )
        val offline = ServiceProblem(ProblemKind.NO_NETWORK, "No network connection")
        assertEquals(
            "The last attempt failed. No network connection. Pull down to try again. 1 answer is waiting to be sent.",
            SyncWords.note(TestClock.fetched, offline, 1, "x"),
        )
    }
}
