package app.overdrive.ride.settings

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * A secret kept in the settings (the sync token, spec §4.9): it prints as bullets, never as its value, so a settings object
 * that ends up in a log line, an exception message or a test failure cannot leak it. Equality is by value, so a changed
 * token is a changed setting. Use [value] only where the secret itself is needed: the field that edits it and the request
 * that sends it.
 */
class SecretText(
    val value: String = "",
) {
    val isEmpty: Boolean get() = value.isEmpty()

    override fun equals(other: Any?): Boolean = other is SecretText && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = if (value.isEmpty()) "(none)" else "••••"
}

/** What typing a service address came to. */
sealed interface UrlCheck {
    /** Nothing typed: no service configured. */
    data object Empty : UrlCheck

    /** An address the app accepts, in the form that is stored. */
    data class Valid(
        val url: String,
    ) : UrlCheck {
        /**
         * Whether the token and the rides would travel encrypted. A plain `http://` address is accepted (a bench on the local
         * network) but the Settings screen says so: anyone on the network could read the token.
         */
        val isEncrypted: Boolean get() = url.startsWith("https://")
    }

    /** Not stored. [reason] is plain words for the field. */
    data class Invalid(
        val reason: String,
    ) : UrlCheck
}

/**
 * The rules of the sync settings (spec §4.9): the service address and the token are what the upload to the service reads
 * (ADR 0014), and the rules are what keeps a damaged or hand-edited settings file from putting anything odd where it does.
 *
 * - **Address:** `http://` or `https://`, a host, an optional port and path, at most 2048 characters, no spaces or control
 *   characters, **no user name or password in it** (that is what the token field is for). The scheme and the host are
 *   written in lower case, a path is kept as typed. An address that fails is not stored: the field shows why and keeps what
 *   was typed.
 * - **Token:** control characters and surrounding spaces are dropped, at most 512 characters. It is never validated for
 *   shape, because nothing here knows what the service issues.
 */
object SyncRules {
    const val MAX_URL_LENGTH = 2048
    const val MAX_TOKEN_LENGTH = 512
    private const val MAX_PORT = 65_535

    fun checkUrl(raw: String): UrlCheck {
        val text = raw.trim()
        if (text.isEmpty()) return UrlCheck.Empty
        if (text.length > MAX_URL_LENGTH) return UrlCheck.Invalid("That address is too long.")
        if (text.any { it.isWhitespace() || it.isISOControl() }) return UrlCheck.Invalid("An address has no spaces.")
        val lower = text.lowercase(Locale.ROOT)
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return UrlCheck.Invalid("Start with https:// or http://.")
        }
        // "https://" and "https:///path": a scheme and no host yet, which is a thing a rider types on the way to an address.
        val authority = text.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return UrlCheck.Invalid("The address needs a host, like example.com.")
        val uri =
            try {
                URI(text)
            } catch (e: URISyntaxException) {
                return UrlCheck.Invalid("That is not a web address.")
            }
        if (uri.userInfo != null) return UrlCheck.Invalid("Leave the user name and password out; the token goes below.")
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host.isNullOrEmpty()) return UrlCheck.Invalid("The address needs a host, like example.com.")
        if (uri.port != -1 && uri.port !in 1..MAX_PORT) return UrlCheck.Invalid("The port is not valid.")
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val port = if (uri.port == -1) "" else ":${uri.port}"
        // `URI.getHost` keeps the brackets of an IPv6 literal, so the host is written as it came.
        val rest =
            uri.rawPath.orEmpty() + (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
        return UrlCheck.Valid("$scheme://$host$port$rest")
    }

    /** The address as stored: the valid form of [raw], or empty (what a settings file that holds anything else reads as). */
    fun cleanUrl(raw: String): String = (checkUrl(raw) as? UrlCheck.Valid)?.url.orEmpty()

    /** The token as stored: no control characters, no surrounding spaces, at most [MAX_TOKEN_LENGTH] characters. */
    fun cleanToken(raw: String): String =
        raw
            .filter { !it.isISOControl() }
            .trim()
            .take(MAX_TOKEN_LENGTH)
}
