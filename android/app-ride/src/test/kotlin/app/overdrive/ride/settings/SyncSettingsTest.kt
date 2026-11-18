package app.overdrive.ride.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.overdrive.ride.devices.InMemoryPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync settings (spec §4.9, ADR 0012): an address and a token that are **stored and not used** in Phase 1. The rules
 * keep a damaged or hand-edited settings file from putting anything odd where Phase 2 will read it, and the token never
 * prints as itself.
 */
class SyncSettingsTest {
    private val url = stringPreferencesKey("sync_url")
    private val token = stringPreferencesKey("sync_token")

    private fun valid(text: String): String = (SyncRules.checkUrl(text) as UrlCheck.Valid).url

    private fun invalid(text: String): String = (SyncRules.checkUrl(text) as UrlCheck.Invalid).reason

    @Test
    fun anAddressIsHttpsOrHttpWithAHostAndKeepsItsPortPathAndQuery() {
        assertEquals("https://overdrive.example.org", valid("https://overdrive.example.org"))
        assertEquals("https://overdrive.example.org/api/v1", valid("https://overdrive.example.org/api/v1"))
        assertEquals("http://192.168.1.20:8080", valid("http://192.168.1.20:8080"))
        assertEquals("https://host:8443/a/b?x=1#top", valid("https://host:8443/a/b?x=1#top"))
        assertEquals("http://[::1]:3000/", valid("http://[::1]:3000/"))
    }

    @Test
    fun theSchemeAndTheHostAreWrittenInLowerCaseAndSpacesAroundAreDropped() {
        assertEquals("https://example.org/Path", valid("  HTTPS://Example.ORG/Path \n"))
        assertEquals("http://example.org:81", valid("HTTP://EXAMPLE.org:81"))
    }

    @Test
    fun nothingTypedIsNoServiceAndNotAnError() {
        assertEquals(UrlCheck.Empty, SyncRules.checkUrl(""))
        assertEquals(UrlCheck.Empty, SyncRules.checkUrl("   \t"))
        assertEquals("", SyncRules.cleanUrl(""))
    }

    @Test
    fun anAddressThatIsNotOneIsRefusedWithAReasonInPlainWords() {
        assertEquals("Start with https:// or http://.", invalid("example.org"))
        assertEquals("Start with https:// or http://.", invalid("ftp://example.org"))
        assertEquals("Start with https:// or http://.", invalid("javascript:alert(1)"))
        assertEquals("Start with https:// or http://.", invalid("file:///etc/passwd"))
        assertEquals("An address has no spaces.", invalid("https://exam ple.org"))
        assertEquals("An address has no spaces.", invalid("https://example.org/a\u0000b"))
        assertEquals("The address needs a host, like example.com.", invalid("https://"))
        assertEquals("The address needs a host, like example.com.", invalid("https:///path"))
        assertEquals("The port is not valid.", invalid("https://example.org:70000"))
        assertEquals("The port is not valid.", invalid("https://example.org:0"))
        assertEquals("That is not a web address.", invalid("https://exa[mple.org"))
        assertEquals(
            "Leave the user name and password out; the token goes below.",
            invalid("https://user:secret@example.org"),
        )
        assertEquals(
            "That address is too long.",
            invalid("https://example.org/" + "a".repeat(SyncRules.MAX_URL_LENGTH)),
        )
    }

    @Test
    fun anAddressWithACredentialInItIsNeverStored() {
        // Not even its user name: it would end up in a file and in a backup that Phase 2 has to read.
        assertEquals("", SyncRules.cleanUrl("https://rider:hunter2@example.org/api"))
        assertEquals("", SyncRules.cleanUrl("https://rider@example.org/api"))
    }

    @Test
    fun aTokenLosesControlCharactersAndSurroundingSpacesAndIsCutAtTheLimit() {
        assertEquals("abc123", SyncRules.cleanToken("  abc123 \n"))
        assertEquals("abc123", SyncRules.cleanToken("abc\u0000\u0007123"))
        assertEquals("a b", SyncRules.cleanToken("a b"))
        assertEquals(SyncRules.MAX_TOKEN_LENGTH, SyncRules.cleanToken("x".repeat(5_000)).length)
        assertEquals("", SyncRules.cleanToken("\n\t "))
    }

    @Test
    fun theTokenPrintsAsBulletsNeverAsItself() {
        val secret = SecretText("hunter2-very-secret")
        assertFalse(secret.toString().contains("hunter2"))
        assertEquals("••••", secret.toString())
        assertEquals("(none)", SecretText().toString())
        assertTrue(SecretText().isEmpty)
        assertEquals("equal by value, so a changed token is a changed setting", SecretText("a"), SecretText("a"))
        assertFalse(SecretText("a") == SecretText("b"))

        // A settings object in a log line, an exception or a failed assertion leaks nothing.
        val settings = AppSettings(syncUrl = "https://example.org", syncToken = secret)
        assertFalse(settings.toString().contains("hunter2"))
        assertFalse(settings.copy(ftp = 300).toString().contains("hunter2"))
        assertTrue(settings.toString().contains("••••"))
    }

    @Test
    fun theAddressAndTheTokenAreStoredAndReadBack() =
        runTest {
            val repository = SettingsRepository(InMemoryPreferences())
            val fresh = repository.settings.first()
            assertEquals("", fresh.syncUrl)
            assertTrue(fresh.syncToken.isEmpty)

            repository.update {
                it.copy(
                    syncUrl = "https://Overdrive.example.org/api",
                    syncToken = SecretText("t0ken"),
                )
            }
            val read = repository.settings.first()
            assertEquals("https://overdrive.example.org/api", read.syncUrl)
            assertEquals("t0ken", read.syncToken.value)
        }

    @Test
    fun anInvalidAddressIsNotStoredAndAnEmptyOneRemovesTheKeys() =
        runTest {
            val store = InMemoryPreferences()
            val repository = SettingsRepository(store)
            repository.update { it.copy(syncUrl = "not an address", syncToken = SecretText("t0ken")) }
            assertEquals("", repository.settings.first().syncUrl)
            assertNull(store.data.first()[url])
            assertEquals("t0ken", store.data.first()[token])

            repository.update { it.copy(syncUrl = "https://example.org", syncToken = SecretText()) }
            assertEquals("https://example.org", store.data.first()[url])
            assertNull("an emptied token leaves nothing behind", store.data.first()[token])
            repository.update { it.copy(syncUrl = "") }
            assertNull(store.data.first()[url])
        }

    @Test
    fun aDamagedOrEditedSettingsFileReadsAsCleanValues() =
        runTest {
            val store = InMemoryPreferences()
            store.edit {
                it[url] = "https://user:pw@example.org"
                it[token] = "  spaced\u0000token  "
            }
            val read = SettingsRepository(store).settings.first()
            assertEquals("", read.syncUrl)
            assertEquals("spacedtoken", read.syncToken.value)

            store.edit { it[url] = "ftp://example.org" }
            assertEquals("", SettingsRepository(store).settings.first().syncUrl)
            store.edit { it[url] = "HTTPS://EXAMPLE.org/x" }
            assertEquals("https://example.org/x", SettingsRepository(store).settings.first().syncUrl)
        }

    @Test
    fun changingTheSyncSettingsDoesNotRebuildARide() {
        val before = AppSettings()
        val after = before.copy(syncUrl = "https://example.org", syncToken = SecretText("t"))
        assertEquals(before.rideInputs(), after.rideInputs())
    }
}
