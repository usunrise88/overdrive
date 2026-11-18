package app.overdrive.core.profile

import app.overdrive.core.plan.ScriptedGetTransport
import app.overdrive.core.profile.ProfileSamples.error
import app.overdrive.core.profile.ProfileSamples.json
import app.overdrive.core.profile.ProfileSamples.rider
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpPatchTransport
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rider's profile on the tablet (spec §8.3, ADR 0021) against a stand-in of the service and an in-memory cache: what is
 * asked and when, what every answer comes to, what stays when the service cannot be reached, and how an edit made on the tablet
 * is kept, sent, retried and, when the service refuses it, dropped.
 */
class ProfileRepositoryTest {
    private val token = "tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7"
    private val address = "https://overdrive.example.org"
    private var config = SyncConfig(address, token)
    private var elapsed = 10_000L
    private val service = FakeProfileService(rider())

    private fun repository(
        cache: ProfileCache = MemoryProfileCache(),
        reader: HttpGetTransport = service,
        writer: HttpPatchTransport = service,
    ) = ProfileRepository(
        config = { config },
        reader = reader,
        writer = writer,
        cache = cache,
        elapsedMillis = { elapsed },
    )

    private val ProfileRepository.profile get() = state.value.profile

    private fun ftp(value: Int) = ProfilePatch(ftp = Change.Set(value))

    // region what is asked

    @Test
    fun `asks for the profile with the token in the header, once`() =
        runTest {
            val reader = ScriptedGetTransport(HttpAnswer(200, json(rider())))
            repository(reader = reader).refresh(ProfileRefresh.START)
            val request = reader.requests.single()
            assertEquals("$address/v1/profile", request.url)
            assertEquals(token, request.bearerToken)
            assertEquals(ProfileRepository.MAX_ANSWER_BYTES, request.maxBytes)
            assertFalse(request.toString().contains(token), "the request prints without its token")
        }

    @Test
    fun `a trailing slash on the address does not double`() =
        runTest {
            config = SyncConfig("$address/", token)
            repository().refresh(ProfileRefresh.START)
            assertEquals("$address/v1/profile", service.gets.single().url)
        }

    @Test
    fun `nothing is asked and nothing is said until there is an address and a token`() =
        runTest {
            for (unset in listOf(
                SyncConfig("", ""),
                SyncConfig(address, ""),
                SyncConfig("", token),
                SyncConfig("  ", token),
            )) {
                config = unset
                val repository = repository()
                repository.load()
                repository.settingsChanged()
                for (why in ProfileRefresh.entries) repository.refresh(why)
                assertEquals(ProfileState(ready = true), repository.state.value, "$unset")
                assertFalse(repository.edit(ftp(180)), "no profile in force: the change is the tablet's own")
            }
            assertTrue(service.seen.isEmpty())
        }

    // endregion

    // region fresh, cached, offline

    @Test
    fun `a fresh answer is the profile in force and is kept on disk`() =
        runTest {
            val cache = MemoryProfileCache()
            val repository = repository(cache)
            assertFalse(repository.state.value.ready)
            repository.load()
            assertFalse(repository.state.value.ready, "the cache is read, the settings are not yet")
            repository.settingsChanged()
            assertTrue(repository.state.value.ready)
            assertNull(repository.profile, "nothing yet: the tablet's own values apply")
            repository.refresh(ProfileRefresh.START)
            val state = repository.state.value
            assertEquals(rider(), state.profile)
            assertNull(state.pending)
            assertNull(state.problem)
            assertFalse(state.refreshing)
            val kept = checkNotNull(cache.kept())
            assertEquals(rider(), kept.profile)
            assertNull(kept.pending)
            assertEquals(ProfileCacheFormat.sourceOf(address), kept.source)
        }

    @Test
    fun `a profile with no ftp is a profile in force with no ftp, which is the tablet's to fill`() =
        runTest {
            service.profile = Profile.default()
            val repository = repository()
            repository.refresh(ProfileRefresh.START)
            assertEquals(Profile.default(), repository.profile)
            assertNull(repository.profile?.ftp)
        }

    @Test
    fun `the cache is the profile after a restart with no service, and the failed request says so`() =
        runTest {
            val cache = MemoryProfileCache()
            repository(cache).refresh(ProfileRefresh.START)
            service.offline = true

            val restarted = repository(cache)
            assertFalse(restarted.state.value.ready)
            restarted.load()
            assertEquals(rider(), restarted.profile, "before any request: the last known profile")
            restarted.refresh(ProfileRefresh.START)
            val state = restarted.state.value
            assertEquals(rider(), state.profile, "the profile stays")
            assertEquals(ProfileProblem.Failed("Service unreachable"), state.problem)
            assertFalse(state.refreshing)
        }

    @Test
    fun `a cache of another address is not used until that address answers`() =
        runTest {
            val cache = MemoryProfileCache()
            repository(cache).refresh(ProfileRefresh.START)
            config = SyncConfig("https://other.example.org", token)
            val other = repository(cache)
            other.load()
            assertNull(other.profile, "another service's profile is not this rider's")
            service.offline = true
            other.refresh(ProfileRefresh.START)
            assertNull(other.profile)
            service.offline = false
            service.profile = rider(ftp = 240)
            other.refresh(ProfileRefresh.REQUESTED)
            assertEquals(240, other.profile?.ftp)
            assertEquals(ProfileCacheFormat.sourceOf("https://other.example.org"), cache.kept()?.source)
        }

    @Test
    fun `the cache is in force only while there is an address and a token`() =
        runTest {
            val cache = MemoryProfileCache()
            repository(cache).refresh(ProfileRefresh.START)
            val repository = repository(cache)
            repository.load()
            assertEquals(175, repository.profile?.ftp)
            config = SyncConfig("", "")
            repository.settingsChanged()
            assertNull(repository.profile, "no service set up: the tablet's own values")
            config = SyncConfig(address, token)
            repository.settingsChanged()
            assertEquals(175, repository.profile?.ftp, "and back, with nothing lost")
        }

    @Test
    fun `a damaged cache reads as no cache, every way it can be damaged, and the next answer makes a good one`() =
        runTest {
            val good = checkNotNull(MemoryProfileCache().also { repository(it).refresh(ProfileRefresh.START) }.text)
            val damaged =
                listOf(
                    "",
                    "garbage \u0000",
                    good.take(good.length / 2),
                    good.replace("175", "176"),
                    good.replaceFirst("overdrive-profile 1", "overdrive-profile 2"),
                    good.substringAfter('\n'),
                )
            for (text in damaged) {
                val cache = MemoryProfileCache(text)
                val repository = repository(cache)
                repository.load()
                repository.settingsChanged()
                assertTrue(repository.state.value.ready, text.take(20))
                assertNull(repository.profile, text.take(20))
                repository.refresh(ProfileRefresh.START)
                assertEquals(175, repository.profile?.ftp)
                assertEquals(rider(), cache.kept()?.profile, "rewritten whole")
            }
        }

    @Test
    fun `a cache that cannot be read is no cache, and a disk that refuses writes costs only the cache`() =
        runTest {
            val broken =
                object : ProfileCache {
                    override suspend fun read(): CachedProfile? = throw IllegalStateException("The disk is gone")

                    override suspend fun write(profile: CachedProfile): Unit =
                        throw IllegalStateException("The disk refuses")
                }
            val repository = repository(broken)
            repository.load()
            repository.settingsChanged()
            assertTrue(repository.state.value.ready)
            assertNull(repository.profile)
            repository.refresh(ProfileRefresh.START)
            assertEquals(175, repository.profile?.ftp, "the profile works for now")
            assertTrue(repository.edit(ftp(180)))
            repository.refresh(ProfileRefresh.EDITED)
            assertEquals(180, repository.profile?.ftp)
            assertNull(repository.state.value.pending)
        }

    // endregion

    // region every answer

    @Test
    fun `a token the service rejects is said, kept apart, and asked again only when the rider or the settings ask`() =
        runTest {
            val cache = MemoryProfileCache()
            repository(cache).refresh(ProfileRefresh.START)
            val repository = repository(cache)
            repository.load()
            service.status = 401
            service.body = error("unauthorized", "Missing or invalid bearer token")
            repository.refresh(ProfileRefresh.START)
            assertEquals(ProfileProblem.TokenRejected, repository.state.value.problem)
            assertEquals(175, repository.profile?.ftp, "the last good profile stays")

            val asked = service.gets.size
            elapsed += 10 * 60_000
            repository.refresh(ProfileRefresh.SCREEN_OPENED)
            repository.refresh(ProfileRefresh.START)
            assertEquals(asked, service.gets.size, "not asked again by itself")

            service.status = null
            repository.refresh(ProfileRefresh.REQUESTED)
            assertNull(repository.state.value.problem, "the rider's refresh asks, and it works now")

            service.status = 401
            repository.refresh(ProfileRefresh.REQUESTED)
            assertEquals(ProfileProblem.TokenRejected, repository.state.value.problem)
            config = SyncConfig(address, "tok-another-0123456789abcdef0123456789")
            repository.settingsChanged()
            assertNull(repository.state.value.problem, "the problem was about the old token")
            service.status = null
            repository.refresh(ProfileRefresh.SETTINGS_CHANGED)
            assertEquals("tok-another-0123456789abcdef0123456789", service.gets.last().token)
        }

    @Test
    fun `a token that cannot be a header value is rejected without asking`() =
        runTest {
            config = SyncConfig(address, "tok with spaces é")
            repository().refresh(ProfileRefresh.START)
            assertTrue(service.seen.isEmpty())
        }

    @Test
    fun `an answer that is not a profile leaves what there is and says it is not the service`() =
        runTest {
            val cache = MemoryProfileCache()
            repository(cache).refresh(ProfileRefresh.START)
            val before = cache.text
            val repository = repository(cache)
            repository.load()
            for (body in listOf(
                "<html>captive portal</html>",
                "{}",
                """{"ftp":"175"}""",
                json(rider()).replace("\"ftp\":175", "\"ftp\":5"),
            )) {
                service.status = 200
                service.body = body
                repository.refresh(ProfileRefresh.REQUESTED)
                assertEquals(
                    ProfileProblem.Failed("That is not the Overdrive service"),
                    repository.state.value.problem,
                    body,
                )
                assertEquals(175, repository.profile?.ftp, body)
            }
            assertEquals(before, cache.text)
        }

    @Test
    fun `other answers are plain words and the profile in force stays`() =
        runTest {
            val cache = MemoryProfileCache()
            repository(cache).refresh(ProfileRefresh.START)
            val repository = repository(cache)
            repository.load()
            val words =
                listOf(
                    Triple(404, "{}", "The service refused the request (404)"),
                    Triple(404, error("not_found", "No such route"), "The service has no profile"),
                    Triple(408, "", "The service timed out"),
                    Triple(429, "", "The service is busy"),
                    Triple(500, "", "Service error (500)"),
                    Triple(503, "", "Service error (503)"),
                    Triple(302, "", "The address redirects elsewhere"),
                    Triple(403, "", "The service refused the request (403)"),
                    Triple(204, "", "That is not the Overdrive service"),
                )
            for ((status, body, said) in words) {
                service.status = status
                service.body = body
                repository.refresh(ProfileRefresh.REQUESTED)
                assertEquals(ProfileProblem.Failed(said), repository.state.value.problem, "$status")
                assertEquals(175, repository.profile?.ftp)
            }
        }

    @Test
    fun `every kind of no answer says what broke, with the hint`() =
        runTest {
            val cases =
                listOf(
                    TransportException(TransportFailure.NameNotFound, hint = "UnknownHostException") to
                        "Cannot find the service address (DNS) (UnknownHostException)",
                    TransportException(
                        TransportFailure.NetworkBlocked,
                    ) to "The system blocks this app's network access",
                    TransportException(TransportFailure.Refused) to "The service refused the connection",
                    TransportException(TransportFailure.TimedOut) to "The service did not answer in time",
                )
            for ((failure, words) in cases) {
                val reader = ScriptedGetTransport(failure)
                val one = repository(reader = reader)
                one.refresh(ProfileRefresh.START)
                assertEquals(ProfileProblem.Failed(words), one.state.value.problem)
                assertNull(one.profile)
            }
        }

    // endregion

    // region when

    @Test
    fun `the start and the rider always ask, the screen opening asks once a minute, a failed request counts`() =
        runTest {
            val repository = repository()
            repository.refresh(ProfileRefresh.START)
            repository.refresh(ProfileRefresh.SCREEN_OPENED)
            assertEquals(1, service.gets.size, "within the minute")
            elapsed += 59_999
            repository.refresh(ProfileRefresh.SCREEN_OPENED)
            assertEquals(1, service.gets.size)
            elapsed += 1
            repository.refresh(ProfileRefresh.SCREEN_OPENED)
            assertEquals(2, service.gets.size, "a minute on")
            repository.refresh(ProfileRefresh.REQUESTED)
            repository.refresh(ProfileRefresh.START)
            assertEquals(4, service.gets.size, "the rider and the start never wait")

            service.offline = true
            elapsed += 10 * 60_000
            repository.refresh(ProfileRefresh.SCREEN_OPENED)
            repository.refresh(ProfileRefresh.SCREEN_OPENED)
            assertEquals(5, service.gets.size, "an offline tablet does not retry at every glance")
        }

    @Test
    fun `one request at a time, and a settings change during one asks again afterwards`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            service.beforeAnswer = {
                started.complete(Unit)
                release.await()
            }
            val repository = repository()
            val first = launch { repository.refresh(ProfileRefresh.START) }
            started.await()
            assertTrue(repository.state.value.refreshing)

            repository.refresh(ProfileRefresh.REQUESTED)
            repository.refresh(ProfileRefresh.SCREEN_OPENED)
            assertEquals(1, service.gets.size, "dropped: the one under way answers for them")

            config = SyncConfig(address, "tok-another-0123456789abcdef0123456789")
            repository.refresh(ProfileRefresh.SETTINGS_CHANGED)
            release.complete(Unit)
            first.join()
            assertEquals(2, service.gets.size, "the change was made with the old token, so once more")
            assertEquals("tok-another-0123456789abcdef0123456789", service.gets.last().token)
            assertFalse(repository.state.value.refreshing)
        }

    @Test
    fun `a settings change for the address and token the last request used asks nothing and keeps its problem`() =
        runTest {
            service.offline = true
            val repository = repository()
            repository.refresh(ProfileRefresh.START)
            repository.settingsChanged()
            repository.refresh(ProfileRefresh.SETTINGS_CHANGED)
            assertEquals(1, service.gets.size)
            assertEquals(ProfileProblem.Failed("Service unreachable"), repository.state.value.problem)

            service.offline = false
            config = SyncConfig(address, "tok-another-0123456789abcdef0123456789")
            repository.settingsChanged()
            assertNull(repository.state.value.problem)
            repository.refresh(ProfileRefresh.SETTINGS_CHANGED)
            assertEquals(2, service.gets.size)
            assertEquals(175, repository.profile?.ftp)
        }

    @Test
    fun `a request that was cancelled is not taken for one that was made`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            service.beforeAnswer = {
                started.complete(Unit)
                release.await()
            }
            val repository = repository()
            val first = launch { repository.refresh(ProfileRefresh.START) }
            started.await()
            first.cancel()
            first.join()
            assertFalse(repository.state.value.refreshing, "a cancelled request leaves nothing under way")
            release.complete(Unit)
            repository.refresh(ProfileRefresh.SETTINGS_CHANGED)
            assertEquals(2, service.gets.size, "the same settings, never answered: asked")
            assertEquals(175, repository.profile?.ftp)
        }

    // endregion

    // region edits

    @Test
    fun `an edit is in force at once and is sent as a patch, and the answer is the profile`() =
        runTest {
            val cache = MemoryProfileCache()
            val repository = repository(cache)
            repository.refresh(ProfileRefresh.START)

            assertTrue(repository.edit(ftp(180)))
            assertEquals(180, repository.profile?.ftp, "the number moves as the rider taps")
            assertEquals(ftp(180), repository.state.value.pending)
            assertTrue(service.patches.isEmpty(), "nothing sent yet")

            repository.refresh(ProfileRefresh.EDITED)
            val patch = service.patches.single()
            assertEquals("$address/v1/profile", patch.url)
            assertEquals(token, patch.token)
            assertEquals("""{"ftp":180}""", patch.body)
            assertNull(repository.state.value.pending)
            assertEquals(180, repository.profile?.ftp)
            assertEquals("2026-11-18T10:00:00Z", repository.profile?.updatedAt, "the service's answer, not a guess")
            assertEquals(1, service.gets.size, "the answer to the patch is the fresh profile: no GET follows")
            assertEquals(180, cache.kept()?.profile?.ftp)
            assertNull(cache.kept()?.pending)
        }

    @Test
    fun `the physics are edited the same way`() =
        runTest {
            val repository = repository()
            repository.refresh(ProfileRefresh.START)
            assertTrue(
                repository.edit(ProfilePatch(physics = PhysicsPatch(systemMassKg = 78.0, cdA = 0.32, crr = 0.0045))),
            )
            assertEquals(78.0, repository.profile?.physics?.systemMassKg)
            repository.refresh(ProfileRefresh.EDITED)
            assertEquals("""{"physics":{"systemMassKg":78.0,"cdA":0.32,"crr":0.0045}}""", service.patches.single().body)
            assertEquals(0.0045, service.profile.physics.crr)
            assertEquals(1.225, service.profile.physics.airDensity, "what was not named is untouched")
        }

    @Test
    fun `an edit that would make an invalid profile is a mistake of the caller and changes nothing`() =
        runTest {
            val repository = repository()
            repository.refresh(ProfileRefresh.START)
            assertFailsWith<IllegalArgumentException> { repository.edit(ftp(5)) }
            assertFailsWith<IllegalArgumentException> {
                repository.edit(
                    ProfilePatch(physics = PhysicsPatch(cdA = 3.0)),
                )
            }
            assertEquals(175, repository.profile?.ftp)
            assertNull(repository.state.value.pending)
        }

    @Test
    fun `an edit made offline stays in force, is on disk, survives a restart and is sent first at the next start`() =
        runTest {
            val cache = MemoryProfileCache()
            val repository = repository(cache)
            repository.refresh(ProfileRefresh.START)
            service.offline = true

            assertTrue(repository.edit(ftp(180)))
            repository.refresh(ProfileRefresh.EDITED)
            val state = repository.state.value
            assertEquals(180, state.profile?.ftp, "the rider's value stays in force")
            assertEquals(ftp(180), state.pending, "and is plainly not sent yet")
            assertEquals(ProfileProblem.Failed("Service unreachable"), state.problem)
            assertEquals(ftp(180), cache.kept()?.pending, "on disk")
            assertEquals(175, cache.kept()?.profile?.ftp, "beside the service's last answer")

            // The tablet is closed and opened with no network: the edit is still there.
            val restarted = repository(cache)
            restarted.load()
            assertEquals(180, restarted.profile?.ftp)
            assertEquals(ftp(180), restarted.state.value.pending)
            restarted.refresh(ProfileRefresh.START)
            assertEquals(180, restarted.profile?.ftp, "still offline: still the rider's")

            // The network is back: the next start sends it first, and nothing is read before it is out.
            service.offline = false
            service.seen.clear()
            val again = repository(cache)
            again.load()
            again.refresh(ProfileRefresh.START)
            assertEquals(listOf("PATCH"), service.seen.map { it.method })
            assertEquals("""{"ftp":180}""", service.patches.single().body)
            assertNull(again.state.value.pending)
            assertNull(again.state.value.problem)
            assertEquals(180, service.profile.ftp)
            assertNull(cache.kept()?.pending)
        }

    @Test
    fun `edits made offline are one patch, the last write winning, and the retry is the next refresh`() =
        runTest {
            val repository = repository()
            repository.refresh(ProfileRefresh.START)
            service.offline = true
            for (value in listOf(180, 185, 190)) {
                assertTrue(repository.edit(ftp(value)))
                repository.refresh(ProfileRefresh.EDITED)
            }
            assertTrue(repository.edit(ProfilePatch(physics = PhysicsPatch(systemMassKg = 80.0))))
            repository.refresh(ProfileRefresh.EDITED)
            assertTrue(repository.edit(ProfilePatch(physics = PhysicsPatch(systemMassKg = 79.0, crr = 0.005))))
            assertEquals(
                ProfilePatch(ftp = Change.Set(190), physics = PhysicsPatch(systemMassKg = 79.0, crr = 0.005)),
                repository.state.value.pending,
            )
            assertEquals(190, repository.profile?.ftp)

            service.offline = false
            service.seen.clear()
            repository.refresh(ProfileRefresh.SCREEN_OPENED)
            assertEquals("""{"ftp":190,"physics":{"systemMassKg":79.0,"crr":0.005}}""", service.patches.single().body)
            assertEquals(190, service.profile.ftp)
            assertEquals(79.0, service.profile.physics.systemMassKg)
            assertNull(repository.state.value.pending)
            assertEquals(0, service.gets.size)
        }

    @Test
    fun `a service that fails for a moment keeps the edit, and says why`() =
        runTest {
            val repository = repository()
            repository.refresh(ProfileRefresh.START)
            assertTrue(repository.edit(ftp(180)))
            for ((status, words) in listOf(
                500 to "Service error (500)",
                503 to "Service error (503)",
                408 to "The service timed out",
                429 to "The service is busy",
            )) {
                service.status = status
                service.body = ""
                repository.refresh(ProfileRefresh.REQUESTED)
                assertEquals(ProfileProblem.Failed(words), repository.state.value.problem)
                assertEquals(ftp(180), repository.state.value.pending, "$status: kept for the next time")
                assertEquals(180, repository.profile?.ftp)
            }
            val gets = service.gets.size
            service.status = null
            repository.refresh(ProfileRefresh.REQUESTED)
            assertEquals(gets, service.gets.size, "while something is unsent it is sent, and the answer is the profile")
            assertNull(repository.state.value.pending)
        }

    @Test
    fun `a token the service rejects keeps the edit and sends nothing more until asked`() =
        runTest {
            val repository = repository()
            repository.refresh(ProfileRefresh.START)
            assertTrue(repository.edit(ftp(180)))
            service.status = 401
            service.body = error("unauthorized", "Missing or invalid bearer token")
            repository.refresh(ProfileRefresh.EDITED)
            assertEquals(ProfileProblem.TokenRejected, repository.state.value.problem)
            assertEquals(ftp(180), repository.state.value.pending)
            val seen = service.seen.size
            repository.refresh(ProfileRefresh.START)
            repository.refresh(ProfileRefresh.EDITED)
            assertEquals(seen, service.seen.size)
            service.status = null
            repository.refresh(ProfileRefresh.REQUESTED)
            assertNull(repository.state.value.pending)
            assertEquals(180, service.profile.ftp)
        }

    @Test
    fun `a service that refuses the edit loses it, and the profile in force goes back to the service's`() =
        runTest {
            val cache = MemoryProfileCache()
            val repository = repository(cache)
            repository.refresh(ProfileRefresh.START)
            assertTrue(repository.edit(ftp(180)))
            service.status = 422
            service.body = error("invalid_profile", "Not a valid change: nope")
            repository.refresh(ProfileRefresh.EDITED)
            val state = repository.state.value
            assertNull(state.pending, "it will not be sent again and again")
            assertEquals(175, state.profile?.ftp)
            assertEquals(ProfileProblem.Failed("The service refused the change (422)"), state.problem)
            assertNull(cache.kept()?.pending)
            assertEquals(1, service.patches.size, "sent once")
        }

    @Test
    fun `an edit made while a send is under way is sent after it, the newest winning`() =
        runTest {
            val repository = repository()
            repository.refresh(ProfileRefresh.START)
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            service.beforeAnswer = {
                if (service.patches.size == 1) {
                    started.complete(Unit)
                    release.await()
                }
            }
            assertTrue(repository.edit(ftp(180)))
            val first = launch { repository.refresh(ProfileRefresh.EDITED) }
            started.await()

            assertTrue(repository.edit(ftp(185)))
            repository.refresh(ProfileRefresh.EDITED)
            assertEquals(1, service.patches.size, "one request at a time")
            assertEquals(185, repository.profile?.ftp, "the rider's newest value is in force meanwhile")
            release.complete(Unit)
            first.join()

            assertEquals(listOf("""{"ftp":180}""", """{"ftp":185}"""), service.patches.map { it.body })
            assertEquals(185, service.profile.ftp)
            assertEquals(185, repository.profile?.ftp)
            assertNull(repository.state.value.pending)
        }

    @Test
    fun `an edit made while the profile is being read stays, and is sent`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val repository = repository()
            repository.refresh(ProfileRefresh.START)
            service.beforeAnswer = {
                if (service.gets.size == 2) {
                    started.complete(Unit)
                    release.await()
                }
            }
            val first = launch { repository.refresh(ProfileRefresh.REQUESTED) }
            started.await()
            service.profile = rider(ftp = 200)
            assertTrue(repository.edit(ftp(190)))
            release.complete(Unit)
            first.join()

            assertEquals(listOf("""{"ftp":190}"""), service.patches.map { it.body })
            assertEquals(190, repository.profile?.ftp)
            assertNull(repository.state.value.pending)
            assertEquals(190, service.profile.ftp)
        }

    @Test
    fun `an unsent edit is for the address it was made on`() =
        runTest {
            val cache = MemoryProfileCache()
            val repository = repository(cache)
            repository.refresh(ProfileRefresh.START)
            service.offline = true
            assertTrue(repository.edit(ftp(180)))
            repository.refresh(ProfileRefresh.EDITED)

            config = SyncConfig("https://other.example.org", token)
            repository.settingsChanged()
            assertNull(repository.profile, "another service: its own profile, not this one with an edit on it")
            assertFalse(repository.edit(ftp(190)))
            service.offline = false
            service.profile = rider(ftp = 240)
            service.seen.clear()
            repository.refresh(ProfileRefresh.SETTINGS_CHANGED)
            assertEquals(240, repository.profile?.ftp)
            assertTrue(service.patches.isEmpty(), "the edit is not sent to another service")
            assertNull(repository.state.value.pending)
        }

    @Test
    fun `nothing the repository shows holds the token or the address`() =
        runTest {
            service.offline = true
            val repository = repository()
            repository.refresh(ProfileRefresh.START)
            service.offline = false
            repository.refresh(ProfileRefresh.REQUESTED)
            repository.edit(ftp(180))
            service.offline = true
            repository.refresh(ProfileRefresh.EDITED)
            val state = repository.state.value
            assertEquals(ProfileProblem.Failed("Service unreachable"), state.problem)
            assertFalse(state.toString().contains(token))
            assertFalse(state.toString().contains(address))
            assertFalse(service.patches.last().let { "PATCH ${it.url}" }.contains(token))
        }

    // endregion
}
