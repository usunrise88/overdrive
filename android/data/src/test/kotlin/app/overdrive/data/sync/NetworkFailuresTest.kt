package app.overdrive.data.sync

import app.overdrive.core.sync.TransportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * What the platform threw, told into kinds the owner can act on, and the hint that is shown for it. The messages are the ones
 * Android formats (`ErrnoException.getMessage`: `<function> failed: <ERRNO> (<strerror>)`, and `IoBridge`'s
 * `failed to connect to <address> (port N) ...`), which hold addresses: none of that may reach the hint.
 */
class NetworkFailuresTest {
    private fun classify(
        e: IOException,
        errnoOf: (Throwable) -> Int? = { null },
    ) = NetworkFailures.classify(e, errnoOf)

    private fun assertKind(
        kind: TransportFailure,
        hint: String,
        e: IOException,
    ) {
        val result = classify(e)
        assertEquals("kind of ${e.message}", kind, result.kind)
        assertEquals("hint of ${e.message}", hint, result.hint)
    }

    // region the errno name, from the messages Android formats

    @Test
    fun `the system forbidding the app the network is its own kind`() {
        assertKind(
            TransportFailure.NetworkBlocked,
            "SocketException · EACCES",
            SocketException("socket failed: EACCES (Permission denied)"),
        )
        assertKind(
            TransportFailure.NetworkBlocked,
            "SocketException · EPERM",
            SocketException("socket failed: EPERM (Operation not permitted)"),
        )
        assertKind(TransportFailure.NetworkBlocked, "SocketException", SocketException("Permission denied"))
    }

    @Test
    fun `no network at all is its own kind`() {
        assertKind(
            TransportFailure.NoNetwork,
            "SocketException · ENETUNREACH",
            SocketException("connect failed: ENETUNREACH (Network is unreachable)"),
        )
        assertKind(
            TransportFailure.NoNetwork,
            "SocketException · ENETDOWN",
            SocketException("sendto failed: ENETDOWN (Network is down)"),
        )
        assertKind(
            TransportFailure.NoNetwork,
            "ConnectException",
            ConnectException("Network is unreachable (connect failed)"),
        )
    }

    @Test
    fun `a refused connection is its own kind, and its address stays out of the hint`() {
        assertKind(
            TransportFailure.Refused,
            "ConnectException · ECONNREFUSED",
            ConnectException(
                "failed to connect to overdrive.example.org/203.0.113.7 (port 443) from /:: (port 41234) " +
                    "after 10000ms: isConnected failed: ECONNREFUSED (Connection refused)",
            ),
        )
        assertKind(TransportFailure.Refused, "ConnectException", ConnectException("Connection refused"))
    }

    @Test
    fun `a connection that was made and cut is its own kind`() {
        assertKind(
            TransportFailure.Dropped,
            "SocketException · ECONNRESET",
            SocketException("recvfrom failed: ECONNRESET (Connection reset by peer)"),
        )
        assertKind(
            TransportFailure.Dropped,
            "SocketException · EPIPE",
            SocketException("sendto failed: EPIPE (Broken pipe)"),
        )
        assertKind(
            TransportFailure.Dropped,
            "SocketException · ECONNABORTED",
            SocketException("read failed: ECONNABORTED (Software caused connection abort)"),
        )
        assertKind(TransportFailure.Dropped, "SocketException", SocketException("Connection reset"))
    }

    @Test
    fun `a connect that timed out in the system is a timeout, and no route is the fallback with its name`() {
        assertKind(
            TransportFailure.TimedOut,
            "ConnectException · ETIMEDOUT",
            ConnectException(
                "failed to connect to /203.0.113.7 (port 443): connect failed: ETIMEDOUT (Connection timed out)",
            ),
        )
        assertKind(
            TransportFailure.Unreachable,
            "NoRouteToHostException · EHOSTUNREACH",
            NoRouteToHostException("connect failed: EHOSTUNREACH (No route to host)"),
        )
    }

    @Test
    fun `a socket error with nothing to tell it by is the fallback and shows only its class`() {
        assertKind(TransportFailure.Unreachable, "SocketException", SocketException("Socket closed"))
        assertKind(TransportFailure.Unreachable, "SocketException", SocketException())
        assertKind(
            TransportFailure.Unreachable,
            "SocketException · EMFILE",
            SocketException("socket failed: EMFILE (Too many open files)"),
        )
    }

    // endregion

    // region the hint is a whitelist

    @Test
    fun `an error name that is not on the whitelist shows the class only`() {
        assertKind(
            TransportFailure.Unreachable,
            "SocketException",
            SocketException("sendto failed: EFAULT (Bad address)"),
        )
        assertKind(
            TransportFailure.Unreachable,
            "SocketException",
            SocketException("failed: ETOTALLYMADEUP (overdrive.example.org 203.0.113.7)"),
        )
        // Part of a longer word is not a name either.
        assertKind(TransportFailure.Unreachable, "SocketException", SocketException("XEACCES and EACCESS and EACCES_"))
    }

    @Test
    fun `the hint never holds a host name, an address or any message text`() {
        val messages =
            listOf(
                """Unable to resolve host "overdrive.example.org": No address associated with hostname""",
                "failed to connect to overdrive.example.org/203.0.113.7 (port 443) after 10000ms",
                "Bearer tok-secret-0123456789 was rejected by 203.0.113.7:8443",
            )
        for (message in messages) {
            for (e in listOf(
                SocketException(message),
                ConnectException(message),
                UnknownHostException(message),
                IOException(message),
            )) {
                val hint = classify(e).hint.orEmpty()
                assertTrue(hint, hint.isNotEmpty())
                for (leak in listOf("overdrive", "203.0.113.7", "tok-secret", "443", "Bearer", "10000")) {
                    assertFalse("'$leak' in the hint: $hint", hint.contains(leak))
                }
            }
        }
    }

    @Test
    fun `a class without a plain name shows as IOException`() {
        val anonymous = object : IOException("x") {}
        assertEquals("IOException", classify(anonymous).hint)
    }

    // endregion

    // region the other kinds

    @Test
    fun `a name that does not resolve is its own kind, with the resolver's error name when there is one`() {
        assertKind(
            TransportFailure.NameNotFound,
            "UnknownHostException",
            UnknownHostException(
                """Unable to resolve host "overdrive.example.org": No address associated with hostname""",
            ),
        )
        val withCause =
            UnknownHostException("Unable to resolve host").apply {
                initCause(IOException("android_getaddrinfo failed: EAI_NODATA (No address associated with hostname)"))
            }
        assertKind(TransportFailure.NameNotFound, "UnknownHostException · EAI_NODATA", withCause)
    }

    @Test
    fun `timeouts, secure connection failures and anything else keep their kinds, with the class as hint`() {
        assertKind(
            TransportFailure.TimedOut,
            "SocketTimeoutException",
            SocketTimeoutException("failed to connect after 10000ms"),
        )
        assertKind(
            TransportFailure.SecureConnection,
            "SSLHandshakeException",
            SSLHandshakeException("not for 203.0.113.7"),
        )
        assertKind(TransportFailure.Other, "EOFException", EOFException("Unexpected end of stream on 203.0.113.7"))
    }

    // endregion

    // region where the error name comes from

    @Test
    fun `the errno of the platform exception in the cause chain comes first, and is read by its number`() {
        val errno = IllegalStateException("a stand-in for android.system.ErrnoException")
        val e = SocketException("connect failed").apply { initCause(errno) }
        val result = classify(e) { if (it === errno) 13 else null }
        assertEquals(TransportFailure.NetworkBlocked, result.kind)
        assertEquals("SocketException · EACCES", result.hint)
        // The number wins over what the text says.
        val text = SocketException("socket failed: EACCES (Permission denied)").apply { initCause(errno) }
        assertEquals(TransportFailure.Refused, classify(text) { if (it === errno) 111 else null }.kind)
        // A number that is not on the list shows nothing, and the text is read after all.
        assertEquals(
            "SocketException · ECONNRESET",
            classify(
                SocketException("recvfrom failed: ECONNRESET (Connection reset by peer)").apply { initCause(errno) },
            ) { if (it === errno) 9999 else null }.hint,
        )
    }

    @Test
    fun `the numbers are Linux's`() {
        val expected =
            mapOf(
                1 to TransportFailure.NetworkBlocked,
                13 to TransportFailure.NetworkBlocked,
                32 to TransportFailure.Dropped,
                100 to TransportFailure.NoNetwork,
                101 to TransportFailure.NoNetwork,
                103 to TransportFailure.Dropped,
                104 to TransportFailure.Dropped,
                110 to TransportFailure.TimedOut,
                111 to TransportFailure.Refused,
                113 to TransportFailure.Unreachable,
            )
        for ((number, kind) in expected) {
            val e = SocketException("x")
            assertEquals("errno $number", kind, classify(e) { number }.kind)
        }
    }

    @Test
    fun `an error name in a cause's message is found, and a cause that leads back to the exception is no loop`() {
        val outer = SocketException("Connection problem")
        val inner = IOException("connect failed: ENETUNREACH (Network is unreachable)")
        outer.initCause(inner)
        assertEquals(TransportFailure.NoNetwork, classify(outer).kind)
        assertEquals("SocketException · ENETUNREACH", classify(outer).hint)
        inner.initCause(outer)
        assertEquals(TransportFailure.NoNetwork, classify(outer).kind)
    }

    @Test
    fun `without a platform exception in the chain only the text counts`() {
        val result = NetworkFailures.classify(SocketException("socket failed: EACCES (Permission denied)"))
        assertEquals("SocketException · EACCES", result.hint)
        assertEquals("SocketException", NetworkFailures.classify(SocketException("Socket closed")).hint)
    }

    @Test
    fun `the default reader reads the errno of the real android system ErrnoException`() {
        // The stub of the unit tests does not store the number its constructor is given (nor give a message): set the public
        // field the real class has, so what is checked is that the reader finds the class and the field by their names.
        val errno = android.system.ErrnoException("connect", 0)
        android.system.ErrnoException::class.java
            .getField("errno")
            .apply { isAccessible = true }
            .setInt(errno, 13)
        val result = NetworkFailures.classify(SocketException("connect failed").apply { initCause(errno) })
        assertEquals(TransportFailure.NetworkBlocked, result.kind)
        assertEquals("SocketException · EACCES", result.hint)
    }

    // endregion

    @Test
    fun `the exception carries the cause and a message that names only the kind`() {
        val e = ConnectException("failed to connect to /203.0.113.7 (port 443): ECONNREFUSED (Connection refused)")
        val thrown = NetworkFailures.exceptionOf(e)
        assertEquals(TransportFailure.Refused, thrown.failure)
        assertEquals("ConnectException · ECONNREFUSED", thrown.hint)
        assertEquals("No answer from the server: Refused", thrown.message)
        assertTrue(thrown.cause === e)
    }
}
