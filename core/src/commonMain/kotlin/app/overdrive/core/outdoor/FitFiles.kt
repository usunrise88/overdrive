package app.overdrive.core.outdoor

import app.overdrive.core.record.Crc32

/*
 * The phone's side of the outdoor ride (spec §11.8 path A, ADR 0026, ADR 0028): the file OnelapFit shares is checked here for what is
 * certainly not a FIT file, kept in an outbox until the service has it, and sent to `POST /v1/activities/fit`. What the file says (the
 * ride, the load, whether it is cycling) is the service's to read; the phone never decodes it.
 */

/** The `Content-Type` of the file's bytes on the wire (`POST /v1/activities/fit`). */
public const val FIT_CONTENT_TYPE: String = "application/vnd.ant.fit"

/** Why a file was not imported: the rider can fix every one of them by sharing another file or by connecting to the service. */
public enum class FitRefusal {
    /** The file has no bytes. */
    EMPTY_FILE,

    /** The bytes are not a FIT file (the header says so here, or the service's decoder refused them: `invalid_fit`). */
    NOT_A_FIT_FILE,

    /** A zip archive: OnelapFit may share the export zipped, and the `.fit` inside is what the service takes. */
    ARCHIVE,

    /** More than [ACTIVITY_FIT_MAX_BYTES] (here, or the service's `413 payload_too_large`). */
    TOO_LARGE,

    /** A valid FIT file whose sport is not cycling (`unsupported_sport`). */
    NOT_CYCLING,

    /** A valid FIT file with no ride in it: no record with a time (`empty_activity`). */
    NO_RIDE,

    /** The service did not accept the kind of body (`415`): this app and the service disagree. */
    WRONG_TYPE,

    /** The outbox already holds as many files as it keeps: the rider has to connect to the service first. */
    QUEUE_FULL,

    /** The saved copy was damaged on the phone's disk (its checksum failed): it cannot be sent. */
    DAMAGED,

    /** Any other answer of the service in the 4xx range that says no for good. */
    SERVICE_REFUSED,
}

/**
 * What can be told about a file without the service, from its bytes and its name. **A refusal here is certain** (the file is not a FIT
 * file, whatever the service would say), so it is never kept; what is not refused is the service's to judge (`invalid_fit`).
 */
public object FitFileCheck {
    private const val FIT_SIGNATURE_AT = 8
    private const val MIN_HEADER = 12
    private const val LONG_HEADER = 14
    private const val CRC_BYTES = 2
    private const val HEX = 16
    private const val CRC_DIGITS = 8
    private const val ZIP_MAGIC_0 = 0x50
    private const val ZIP_MAGIC_1 = 0x4B
    private const val ZIP_MAGIC_2 = 0x03
    private const val ZIP_MAGIC_3 = 0x04
    private val SIGNATURE = ".FIT".encodeToByteArray()
    private val FIT_TYPES = setOf("application/vnd.ant.fit", "application/x-fit")

    /** What is certainly wrong with [bytes], or null when they may be a FIT file. */
    public fun refusalOf(bytes: ByteArray): FitRefusal? =
        when {
            bytes.isEmpty() -> FitRefusal.EMPTY_FILE
            bytes.size > ACTIVITY_FIT_MAX_BYTES -> FitRefusal.TOO_LARGE
            isZip(bytes) -> FitRefusal.ARCHIVE
            !hasFitHeader(bytes) -> FitRefusal.NOT_A_FIT_FILE
            else -> null
        }

    /** The 12 or 14 byte header (its size, then `.FIT` at byte 8), and room for the header and the closing checksum. */
    public fun hasFitHeader(bytes: ByteArray): Boolean {
        if (bytes.size < MIN_HEADER + CRC_BYTES) return false
        val headerSize = bytes[0].toInt() and 0xFF
        if (headerSize != MIN_HEADER && headerSize != LONG_HEADER) return false
        if (bytes.size < headerSize + CRC_BYTES) return false
        return SIGNATURE.indices.all { bytes[FIT_SIGNATURE_AT + it] == SIGNATURE[it] }
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0].toInt() == ZIP_MAGIC_0 &&
            bytes[1].toInt() == ZIP_MAGIC_1 &&
            bytes[2].toInt() == ZIP_MAGIC_2 &&
            bytes[3].toInt() == ZIP_MAGIC_3

    /**
     * Whether what another app shared is meant to be a FIT file (spec §11.8): its type says so (`application/vnd.ant.fit`,
     * `application/x-fit`), or, for the generic types a share sheet offers (`application/octet-stream`, anything), its display name
     * ends in `.fit`. A file with neither is refused with the plain cause; one that passes is still checked by its header.
     */
    public fun looksLikeFit(
        displayName: String?,
        mimeType: String?,
    ): Boolean {
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && type in FIT_TYPES) return true
        return displayName?.trim()?.lowercase()?.endsWith(".fit") == true
    }

    /** CRC-32 of [bytes] as eight lower-case hex digits: what the outbox files name their content by (not a security hash). */
    public fun checksumOf(bytes: ByteArray): String = Crc32.of(bytes).toString(HEX).padStart(CRC_DIGITS, '0')
}
