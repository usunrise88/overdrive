package app.overdrive.data.settings

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * A secret kept in the settings (the service token, spec §4.9, §6.1): it prints as bullets, never as its value, so a settings
 * object that ends up in a log line, an exception message, a state dump or a test failure cannot leak it. Equality is by value, so
 * a changed token is a changed setting. Use [value] only where the secret itself is needed: the field that edits it and the
 * request that sends it.
 *
 * The ride app has its own copy of this class and of [ServiceRules] (`app.overdrive.ride.settings`, from before the companion);
 * the two apps keep separate settings files, and moving the ride app over is a later, separate change (ADR 0024).
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
sealed interface AddressCheck {
    /** Nothing typed: no service configured. */
    data object Empty : AddressCheck

    /** An address the app accepts, in the form that is stored. */
    data class Valid(
        val url: String,
    ) : AddressCheck {
        /** Whether the token and the data would travel encrypted. A plain `http://` address is accepted but flagged "Not encrypted". */
        val isEncrypted: Boolean get() = url.startsWith("https://")

        /** The host, for the words of a failure ("The phone could not find overdrive.example.net"). */
        val host: String get() = URI(url).host.orEmpty()
    }

    /** Not stored. [reason] is plain words for the field. */
    data class Invalid(
        val reason: String,
    ) : AddressCheck
}

/**
 * The rules of the service settings (design/companion.md §4.9), the same as the ride app's sync settings (ADR 0014): `http://` or
 * `https://`, a host, an optional port and path, at most 2048 characters, no spaces or control characters, **no user name or
 * password in it**. The scheme and the host are written in lower case. An address that fails is not stored: the field shows why
 * and keeps what was typed. The token loses control characters and surrounding spaces, at most 512 characters, and is never
 * validated for shape.
 *
 * A rider may type the address without its scheme ("overdrive.example.net", as the design's field shows it): [normalise] adds
 * `https://` to what has none, so the common case needs no typing of it.
 */
object ServiceRules {
    const val MAX_URL_LENGTH = 2048
    const val MAX_TOKEN_LENGTH = 512
    private const val MAX_PORT = 65_535

    /** [raw] with `https://` in front when it names no scheme at all. */
    fun normalise(raw: String): String {
        val text = raw.trim()
        return if (text.isEmpty() || "://" in text) text else "https://$text"
    }

    fun checkAddress(raw: String): AddressCheck {
        val text = normalise(raw)
        if (text.isEmpty()) return AddressCheck.Empty
        if (text.length > MAX_URL_LENGTH) return AddressCheck.Invalid("That address is too long.")
        if (text.any { it.isWhitespace() || it.isISOControl() }) {
            return AddressCheck.Invalid(
                "An address has no spaces.",
            )
        }
        val lower = text.lowercase(Locale.ROOT)
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return AddressCheck.Invalid("Start with https:// or http://.")
        }
        val authority = text.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return AddressCheck.Invalid("The address needs a host, like example.com.")
        val uri =
            try {
                URI(text)
            } catch (e: URISyntaxException) {
                return AddressCheck.Invalid("That is not a web address.")
            }
        if (uri.userInfo != null) {
            return AddressCheck.Invalid("Leave the user name and password out; the token goes below.")
        }
        val host = uri.host?.lowercase(Locale.ROOT)
        if (host.isNullOrEmpty()) return AddressCheck.Invalid("The address needs a host, like example.com.")
        if (uri.port != -1 && uri.port !in 1..MAX_PORT) return AddressCheck.Invalid("The port is not valid.")
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val rest =
            uri.rawPath.orEmpty() + (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
        return AddressCheck.Valid("$scheme://$host$port$rest")
    }

    /** The address as stored: the valid form of [raw], or empty (what a settings file that holds anything else reads as). */
    fun cleanAddress(raw: String): String = (checkAddress(raw) as? AddressCheck.Valid)?.url.orEmpty()

    /** The token as stored: no control characters, no surrounding spaces, at most [MAX_TOKEN_LENGTH] characters. */
    fun cleanToken(raw: String): String =
        raw
            .filter { !it.isISOControl() }
            .trim()
            .take(MAX_TOKEN_LENGTH)
}
