package app.overdrive.core.remote

import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.NOT_THE_SERVICE
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import app.overdrive.core.sync.plainReason
import app.overdrive.core.sync.reasonForStatus

/** What kind of trouble a request ran into: enough for a screen to say what to do, without reading words. */
public enum class ProblemKind {
    /** The service answered 401 (or the token cannot be sent as a header). Settings is where it is fixed. */
    TOKEN_REJECTED,

    /** The phone has no network to send through. The status line says "Offline". */
    NO_NETWORK,

    /** The address did not resolve. */
    NAME_NOT_FOUND,

    /** The system forbids the app the network. */
    NETWORK_BLOCKED,

    /** The connection was refused, cut, too slow or otherwise got no answer: the service may be restarting. */
    NO_ANSWER,

    /** The secure connection could not be set up. */
    SECURE_CONNECTION,

    /** The service answered with a 5xx (or 408, 429): its own trouble. */
    SERVER_ERROR,

    /** The service answered, and refused this request (a 4xx other than 401). */
    REQUEST_REFUSED,

    /** The service does not have the route (404 `not_found`). */
    NO_SUCH_ROUTE,

    /** Whatever answered is not the Overdrive service (a body that is not the contract's, a redirect). */
    NOT_THE_SERVICE,
}

/**
 * Why a request failed, for one status line or one error block (design/companion.md §7.5).
 *
 * It holds **no secret**: no address, no token, no body. [plain] is the shared wording of the ride app (`plainReason`, ADR 0014),
 * and a screen that wants the design's longer sentence builds it from [kind] and the address it already has; [hint] is the short
 * technical vocabulary of the transport ("UnknownHostException", "SocketException · EACCES"), and [status] the HTTP status when
 * the service answered.
 */
public data class ServiceProblem(
    val kind: ProblemKind,
    val plain: String,
    val hint: String? = null,
    val status: Int? = null,
) {
    /** The phone has no network at all: the status line then says "Offline" and not "Out of date". */
    public val isOffline: Boolean get() = kind == ProblemKind.NO_NETWORK

    /** The rider has to change something in Settings (the address, the token) for this to go away. */
    public val needsSettings: Boolean
        get() = kind == ProblemKind.TOKEN_REJECTED || kind == ProblemKind.NAME_NOT_FOUND

    public companion object {
        public const val TOKEN_REJECTED_PLAIN: String = "The service did not accept the token"

        public val TokenRejected: ServiceProblem =
            ServiceProblem(ProblemKind.TOKEN_REJECTED, TOKEN_REJECTED_PLAIN, status = HTTP_UNAUTHORIZED)

        /** A 200 whose body is not the contract's answer. */
        public val NotTheService: ServiceProblem = ServiceProblem(ProblemKind.NOT_THE_SERVICE, NOT_THE_SERVICE)

        /** What a transport that got no answer failed with. */
        public fun of(e: TransportException): ServiceProblem =
            ServiceProblem(
                kind =
                    when (e.failure) {
                        TransportFailure.NoNetwork -> ProblemKind.NO_NETWORK

                        TransportFailure.NameNotFound -> ProblemKind.NAME_NOT_FOUND

                        TransportFailure.NetworkBlocked -> ProblemKind.NETWORK_BLOCKED

                        TransportFailure.SecureConnection -> ProblemKind.SECURE_CONNECTION

                        TransportFailure.Refused,
                        TransportFailure.Dropped,
                        TransportFailure.Unreachable,
                        TransportFailure.TimedOut,
                        TransportFailure.Other,
                        -> ProblemKind.NO_ANSWER
                    },
                plain = TransportException(e.failure).plainReason(),
                hint = e.hint,
            )

        /** What an answer that is not the one asked for comes to. */
        public fun of(answer: HttpAnswer): ServiceProblem {
            val status = answer.status
            val words = reasonForStatus(status, answer.body, NO_ROUTE)
            val kind =
                when {
                    status == HTTP_UNAUTHORIZED -> ProblemKind.TOKEN_REJECTED
                    status in TRANSIENT || status in HTTP_SERVER_ERRORS -> ProblemKind.SERVER_ERROR
                    status in HTTP_REDIRECTS -> ProblemKind.NOT_THE_SERVICE
                    words == NO_ROUTE -> ProblemKind.NO_SUCH_ROUTE
                    status in HTTP_CLIENT_ERRORS -> ProblemKind.REQUEST_REFUSED
                    else -> ProblemKind.NOT_THE_SERVICE
                }
            return if (kind == ProblemKind.TOKEN_REJECTED) {
                TokenRejected
            } else {
                ServiceProblem(kind, words, status = status)
            }
        }

        private const val NO_ROUTE = "The service does not have this answer yet"
        private const val HTTP_UNAUTHORIZED = 401
        private val TRANSIENT = setOf(408, 429)
        private val HTTP_REDIRECTS = 300..399
        private val HTTP_CLIENT_ERRORS = 400..499
        private val HTTP_SERVER_ERRORS = 500..599
    }
}
