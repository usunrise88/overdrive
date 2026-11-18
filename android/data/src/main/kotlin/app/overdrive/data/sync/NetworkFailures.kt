package app.overdrive.data.sync

import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Tells why a request got no answer, in kinds the owner can act on (ADR 0014 section 6), from what the platform threw.
 *
 * "Service unreachable" for every socket error cannot tell a name that does not resolve from the system forbidding the app the
 * network (`EACCES`) from a server that refuses the connection, and the fix is different for each. So a [SocketException] is read
 * for the operating system's error name (`EACCES`, `ENETUNREACH`, `ECONNREFUSED`, ...), first from the `android.system.ErrnoException`
 * that Android puts in the cause chain (its `errno` number), then from the text of the exceptions in the chain (`socket failed:
 * EACCES (Permission denied)`), then from the plain phrase of the error (the JVM says only `Connection refused`).
 *
 * **What leaves this class is a fixed vocabulary.** The hint for the owner is the exception's class name and, when it is on the
 * whitelist below, the error's name (`SocketException · EACCES`). The exceptions' messages hold the address and port
 * (`failed to connect to /203.0.113.7 (port 443) ...`): they are read here, never copied anywhere.
 */
internal object NetworkFailures {
    /** The [TransportException] for [e]: the kind of failure, the exception as its cause, the hint for the owner. */
    fun exceptionOf(e: IOException): TransportException {
        val failure = classify(e)
        return TransportException(failure.kind, e, failure.hint)
    }

    /** The kind of failure [e] is and the short hint to show for it. */
    class Classified(
        val kind: TransportFailure,
        val hint: String?,
    )

    /**
     * @param errnoOf the errno number of a platform exception, or null when [Throwable] is not one. The default reads the
     * `errno` field of `android.system.ErrnoException` by reflection (the class is absent on a plain JVM); tests pass their own.
     */
    fun classify(
        e: IOException,
        errnoOf: (Throwable) -> Int? = ::platformErrno,
    ): Classified {
        val chain = chainOf(e)
        val token = tokenIn(chain, errnoOf)
        val kind =
            when (e) {
                is SocketTimeoutException -> TransportFailure.TimedOut
                is SSLException -> TransportFailure.SecureConnection
                is UnknownHostException -> TransportFailure.NameNotFound
                is SocketException -> socketFailure(token, chain)
                else -> TransportFailure.Other
            }
        return Classified(kind, hintOf(e, token))
    }

    private fun socketFailure(
        token: String?,
        chain: List<Throwable>,
    ): TransportFailure {
        val byName = ERRNOS.firstOrNull { it.name == token }
        if (byName != null) return byName.kind
        // No error name anywhere (the JVM's own messages are the bare phrase): the phrase tells.
        val messages = chain.mapNotNull(::messageOf)
        val byPhrase =
            ERRNOS.firstOrNull { errno ->
                errno.phrase != null &&
                    messages.any { it.contains(errno.phrase, ignoreCase = true) }
            }
        return byPhrase?.kind ?: TransportFailure.Unreachable
    }

    private fun hintOf(
        e: IOException,
        token: String?,
    ): String {
        val name = e.javaClass.simpleName
        val safe = if (CLASS_NAME.matches(name)) name else "IOException"
        return if (token == null) safe else "$safe · $token"
    }

    /** [e] and its causes, outermost first; short, and safe against a cause that leads back to an earlier one. */
    private fun chainOf(e: Throwable): List<Throwable> {
        val chain = ArrayList<Throwable>()
        var next: Throwable? = e
        while (next != null && chain.size < MAX_CAUSES && chain.none { it === next }) {
            chain += next
            next = next.cause
        }
        return chain
    }

    private fun tokenIn(
        chain: List<Throwable>,
        errnoOf: (Throwable) -> Int?,
    ): String? {
        for (t in chain) {
            val number = errnoOf(t) ?: continue
            ERRNOS.firstOrNull { it.number == number }?.let { return it.name }
        }
        for (t in chain) {
            val message = messageOf(t) ?: continue
            for (match in TOKEN.findAll(message)) {
                if (match.value in WHITELIST) return match.value
            }
        }
        return null
    }

    /** A message, or null: a platform exception can refuse to give it (the stubs of a unit test do). */
    private fun messageOf(t: Throwable): String? =
        try {
            t.message
        } catch (e: RuntimeException) {
            null
        }

    /** The errno of an `android.system.ErrnoException`, read by reflection so that this class loads on a plain JVM too. */
    private fun platformErrno(t: Throwable): Int? {
        if (t.javaClass.name != ERRNO_EXCEPTION) return null
        return try {
            t.javaClass.getField("errno").getInt(t)
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    /** One operating system error: its name, its number on Linux (Android's kernel), the phrase `strerror` gives, the kind. */
    private class Errno(
        val name: String,
        val number: Int,
        val phrase: String?,
        val kind: TransportFailure,
    )

    private val ERRNOS =
        listOf(
            Errno("EPERM", 1, "Operation not permitted", TransportFailure.NetworkBlocked),
            Errno("EACCES", 13, "Permission denied", TransportFailure.NetworkBlocked),
            Errno("EMFILE", 24, null, TransportFailure.Unreachable),
            Errno("EPIPE", 32, "Broken pipe", TransportFailure.Dropped),
            Errno("EAFNOSUPPORT", 97, null, TransportFailure.Unreachable),
            Errno("EADDRNOTAVAIL", 99, null, TransportFailure.Unreachable),
            Errno("ENETDOWN", 100, "Network is down", TransportFailure.NoNetwork),
            Errno("ENETUNREACH", 101, "Network is unreachable", TransportFailure.NoNetwork),
            Errno("ECONNABORTED", 103, "Software caused connection abort", TransportFailure.Dropped),
            Errno("ECONNRESET", 104, "Connection reset", TransportFailure.Dropped),
            Errno("ETIMEDOUT", 110, "Connection timed out", TransportFailure.TimedOut),
            Errno("ECONNREFUSED", 111, "Connection refused", TransportFailure.Refused),
            Errno("EHOSTUNREACH", 113, "No route to host", TransportFailure.Unreachable),
        )

    /** The names of the resolver's errors (`android.system.GaiException`), for the hint of an [UnknownHostException]. */
    private val RESOLVER_ERRORS = setOf("EAI_AGAIN", "EAI_FAIL", "EAI_FAMILY", "EAI_NODATA", "EAI_NONAME", "EAI_SYSTEM")

    /** The only error names that may reach the owner's screen: anything else shows the exception's class alone. */
    private val WHITELIST: Set<String> = ERRNOS.map { it.name }.toSet() + RESOLVER_ERRORS

    private val TOKEN = Regex("(?<![A-Za-z0-9_])E[A-Z0-9_]{2,}(?![A-Za-z0-9_])")
    private val CLASS_NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,59}")
    private const val ERRNO_EXCEPTION = "android.system.ErrnoException"
    private const val MAX_CAUSES = 8
}
