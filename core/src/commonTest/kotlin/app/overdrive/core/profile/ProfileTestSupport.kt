package app.overdrive.core.profile

import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.HttpGetRequest
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpPatchRequest
import app.overdrive.core.sync.HttpPatchTransport
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure

/** Synthetic profiles for the tests: nothing here is anyone's body. */
internal object ProfileSamples {
    /** A rider whose FTP the owner set through an AI client: 175 W, a mass of 82 kg and the other constants as they come. */
    fun rider(
        ftp: Int? = 175,
        massKg: Double = 82.0,
        updatedAt: String? = "2026-11-18T09:30:00Z",
    ): Profile =
        Profile.default().copy(
            ftp = ftp,
            physics = ProfilePhysics.DEFAULT.copy(systemMassKg = massKg),
            updatedAt = updatedAt,
        )

    fun json(profile: Profile): String = ProfileApiJson.encodeProfile(profile)

    fun error(
        code: String,
        message: String,
    ) = """{"error":{"code":"$code","message":"$message"}}"""
}

/** An in-memory [ProfileCache] that keeps the text the real one keeps; [failWrites] makes the disk refuse. */
internal class MemoryProfileCache(
    var text: String? = null,
) : ProfileCache {
    var failWrites = false
    var reads = 0
    var writes = 0

    override suspend fun read(): CachedProfile? {
        reads++
        return text?.let(ProfileCacheFormat::decode)
    }

    override suspend fun write(profile: CachedProfile) {
        writes++
        if (failWrites) throw IllegalStateException("The disk refuses")
        text = ProfileCacheFormat.encode(profile)
    }

    /** What is on disk now, or null. */
    fun kept(): CachedProfile? = text?.let(ProfileCacheFormat::decode)
}

/**
 * A stand-in for `GET` and `PATCH /v1/profile` that behaves as the service does: it holds a profile, answers it, applies a patch it
 * reads **strictly** (so a body the repository gets wrong fails the test) and answers the profile that results. [offline] makes
 * every request fail with no answer, [status] overrides the answer's status (with [body] for what it says).
 */
internal class FakeProfileService(
    var profile: Profile = Profile.default(),
) : HttpGetTransport,
    HttpPatchTransport {
    class Seen(
        val method: String,
        val url: String,
        val token: String,
        val body: String?,
    )

    val seen = mutableListOf<Seen>()
    var offline = false
    var status: Int? = null
    var body: String? = null

    /** What to do before answering: a test uses it to hold a request open. */
    var beforeAnswer: suspend () -> Unit = {}

    val patches: List<Seen> get() = seen.filter { it.method == "PATCH" }
    val gets: List<Seen> get() = seen.filter { it.method == "GET" }

    override suspend fun get(request: HttpGetRequest): HttpAnswer {
        seen += Seen("GET", request.url, request.bearerToken, null)
        return answer { HttpAnswer(200, ProfileApiJson.encodeProfile(profile)) }
    }

    override suspend fun patch(request: HttpPatchRequest): HttpAnswer {
        seen += Seen("PATCH", request.url, request.bearerToken, request.body)
        return answer {
            val patch = ProfileApiJson.decodePatch(request.body)
            profile = patch.applyTo(profile).copy(updatedAt = "2026-11-18T10:00:00Z")
            HttpAnswer(200, ProfileApiJson.encodeProfile(profile))
        }
    }

    private suspend fun answer(ok: () -> HttpAnswer): HttpAnswer {
        beforeAnswer()
        if (offline) throw TransportException(TransportFailure.Unreachable)
        return status?.let { HttpAnswer(it, body.orEmpty()) } ?: ok()
    }
}

/** A [HttpPatchTransport] that answers from a script, in order (the last answer repeats), and writes down every request. */
internal class ScriptedPatchTransport(
    vararg script: Any,
) : HttpPatchTransport {
    private val script = script.toMutableList()
    private var last: Any? = null
    val requests = mutableListOf<HttpPatchRequest>()

    /** What to do before answering: a test uses it to hold a request open. */
    var beforeAnswer: suspend () -> Unit = {}

    fun then(vararg more: Any) {
        script.addAll(more)
    }

    override suspend fun patch(request: HttpPatchRequest): HttpAnswer {
        requests += request
        beforeAnswer()
        val next = if (script.isNotEmpty()) script.removeAt(0).also { last = it } else checkNotNull(last)
        return when (next) {
            is HttpAnswer -> next
            is Throwable -> throw next
            else -> error("bad script entry")
        }
    }
}
