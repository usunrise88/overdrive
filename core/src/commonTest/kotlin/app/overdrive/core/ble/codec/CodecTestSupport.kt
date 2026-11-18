package app.overdrive.core.ble.codec

import kotlin.test.fail

/** `"41 00 C8 00"` as bytes; spaces optional. */
internal fun hex(text: String): ByteArray = Hex.decode(text) ?: fail("not hex: $text")

internal fun <T> DecodeResult<T>.ok(): T =
    when (this) {
        is DecodeResult.Ok -> value
        is DecodeResult.Malformed -> fail("expected a well-formed payload, got $error")
    }

internal fun <T> DecodeResult<T>.malformed(): DecodeError =
    when (this) {
        is DecodeResult.Ok -> fail("expected a malformed payload, got $value")
        is DecodeResult.Malformed -> error
    }
