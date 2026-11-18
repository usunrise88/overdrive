package app.overdrive.core.remote

import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.HttpGetRequest
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpPostRequest
import app.overdrive.core.sync.HttpPostTransport
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/** What one request to the service came to. */
public sealed interface Outcome<out T> {
    /** The service answered 200 and the body is the contract's: [value], read from [body]. */
    public class Ok<out T>(
        public val value: T,
        public val body: String,
    ) : Outcome<T>

    /** Nothing is set up (no address or token): nothing was asked and nothing is said. */
    public data object NotSetUp : Outcome<Nothing>

    /** It did not work, for [problem]. */
    public data class Failed(
        val problem: ServiceProblem,
    ) : Outcome<Nothing>
}

/**
 * The phone's one way to the service (spec §6.1, ADR 0024): `GET` of a path, `POST` of a small body, with the address and token
 * read from [config] **at every request**, so a change in Settings needs no restart. It turns what comes back into an [Outcome]
 * and never throws for a request that failed.
 *
 * - **A rejected token** (401) is remembered with the address and token it was rejected for: until either changes, or
 *   [forgetRejection] is called (the rider asked, pulled down, pressed Try again), every request answers
 *   [ServiceProblem.TokenRejected] without going out, so a wrong token costs one request and not one per screen.
 * - **A token that cannot be a header value** (spaces, accents) is rejected the same way without asking.
 * - **Nothing here logs**, and the token is read only to send it: it is in no outcome, no problem and no message.
 */
public class ServiceClient(
    private val config: () -> SyncConfig,
    private val reader: HttpGetTransport,
    private val poster: HttpPostTransport? = null,
) {
    private val rejected = MutableStateFlow<SyncConfig?>(null)

    /** The service is set up: an address and a token. */
    public val isConfigured: Boolean get() = config().isConfigured

    /** The rider asked again: a token that was refused is tried once more. */
    public fun forgetRejection() {
        rejected.value = null
    }

    /**
     * `GET` [pathAndQuery] (`/v1/metrics?from=...`) and read the body with [decode], which throws [IllegalArgumentException] for
     * anything that is not the contract's answer.
     */
    public suspend fun <T> get(
        pathAndQuery: String,
        maxBytes: Int,
        decode: (String) -> T,
    ): Outcome<T> {
        val settings = config()
        if (!settings.isConfigured) return Outcome.NotSetUp
        refusalFor(settings)?.let { return Outcome.Failed(it) }
        val answer =
            try {
                reader.get(HttpGetRequest(settings.baseUrl.trimEnd('/') + pathAndQuery, settings.token, maxBytes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                return Outcome.Failed(ServiceProblem.of(e))
            }
        if (answer.status == HTTP_OK) {
            return try {
                Outcome.Ok(decode(answer.body), answer.body)
            } catch (e: IllegalArgumentException) {
                Outcome.Failed(ServiceProblem.NotTheService)
            }
        }
        return Outcome.Failed(problemOf(answer, settings))
    }

    /**
     * `POST` [body] (JSON) to [path]. Returns the answer when the service gave one **and it was 2xx**; any other status or no
     * answer is a failure.
     */
    public suspend fun post(
        path: String,
        body: String,
        maxBytes: Int,
    ): Outcome<HttpAnswer> {
        val settings = config()
        if (!settings.isConfigured) return Outcome.NotSetUp
        val transport = poster ?: return Outcome.Failed(ServiceProblem.NotTheService)
        refusalFor(settings)?.let { return Outcome.Failed(it) }
        val answer =
            try {
                transport.post(HttpPostRequest(settings.baseUrl.trimEnd('/') + path, settings.token, body, maxBytes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                return Outcome.Failed(ServiceProblem.of(e))
            }
        if (answer.status in HTTP_SUCCESS) return Outcome.Ok(answer, answer.body)
        return Outcome.Failed(problemOf(answer, settings))
    }

    private fun refusalFor(settings: SyncConfig): ServiceProblem? =
        if (rejected.value == settings || !settings.token.all { it in '!'..'~' }) ServiceProblem.TokenRejected else null

    private fun problemOf(
        answer: HttpAnswer,
        settings: SyncConfig,
    ): ServiceProblem {
        val problem = ServiceProblem.of(answer)
        if (problem.kind == ProblemKind.TOKEN_REJECTED) rejected.value = settings
        return problem
    }

    private companion object {
        const val HTTP_OK = 200
        val HTTP_SUCCESS = 200..299
    }
}
