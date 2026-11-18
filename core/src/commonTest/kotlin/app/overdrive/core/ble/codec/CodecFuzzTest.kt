package app.overdrive.core.ble.codec

import app.overdrive.core.ble.fake.HeartRateEncoder
import app.overdrive.core.ble.fake.IndoorBikeDataEncoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Everything decoded here arrives from the air: no input may throw, and a truncated one is a typed error, not a crash. */
class CodecFuzzTest {
    private val vectorsIbd =
        listOf(
            "41 00 C8 00",
            "44 00 C4 09 B4 00 FA 00",
            "06 00 B8 0B 22 0B A0 00",
            "10 00 E8 03 39 30 00",
            "00 02 00 00 96",
            "41 00 EC FF",
            "00 01 00 00 2C 01 E8 03 10",
            "00 18 00 00 F2 02 B0 04",
            "A0 04 00 00 19 00 B4 00 2D",
            "FE 1F 10 0E 7A 0D B4 00 AB 00 D2 04 00 1E 00 2C 01 18 01 F4 01 08 07 1E A0 50 08 07 58 02",
        )

    private fun parseEverything(bytes: ByteArray) {
        IndoorBikeDataCodec.parse(bytes)
        IndoorBikeDataCodec.parse(bytes, IndoorBikeFlagMap.SIG_XML_LABELS)
        HeartRateCodec.parse(bytes)
        HeartRateCodec.parse(bytes, HeartRateContactRule.ANY_NONZERO)
        ControlPointCodec.parseResponse(bytes)
        FitnessMachineStatusCodec.parse(bytes)
        FitnessMachineStatusCodec.parse(bytes, simulationSigned = false)
        FitnessMachineFeatureCodec.parse(bytes)
    }

    @Test
    fun `random bytes of every short length never throw`() {
        val random = Random(20261118)
        repeat(60_000) {
            val bytes = random.nextBytes(random.nextInt(0, 40))
            parseEverything(bytes)
        }
    }

    @Test
    fun `random flags with enough random bytes parse or fail cleanly, never partially`() {
        val random = Random(7)
        repeat(20_000) {
            val bytes = random.nextBytes(32)
            when (val result = IndoorBikeDataCodec.parse(bytes)) {
                is DecodeResult.Ok -> {
                    assertEquals(
                        bytes[0].toInt() and 0xFF or ((bytes[1].toInt() and 0xFF) shl 8),
                        result.value.flags,
                    )
                }

                is DecodeResult.Malformed -> {
                    assertEquals(DecodeErrorKind.TRUNCATED, result.error.kind)
                }
            }
        }
    }

    @Test
    fun `an empty payload is a typed error in every codec`() {
        val empty = ByteArray(0)
        assertEquals(DecodeErrorKind.EMPTY, IndoorBikeDataCodec.parse(empty).malformed().kind)
        assertEquals(DecodeErrorKind.EMPTY, HeartRateCodec.parse(empty).malformed().kind)
        assertEquals(DecodeErrorKind.EMPTY, ControlPointCodec.parseResponse(empty).malformed().kind)
        assertEquals(DecodeErrorKind.EMPTY, FitnessMachineStatusCodec.parse(empty).malformed().kind)
        assertEquals(DecodeErrorKind.EMPTY, FitnessMachineFeatureCodec.parse(empty).malformed().kind)
    }

    @Test
    fun `every proper prefix of an Indoor Bike Data vector is truncated, never partly used`() {
        for (vector in vectorsIbd) {
            val full = hex(vector)
            assertTrue(IndoorBikeDataCodec.parse(full) is DecodeResult.Ok, vector)
            for (length in 1 until full.size) {
                val error = IndoorBikeDataCodec.parse(full.copyOf(length)).malformed()
                assertEquals(DecodeErrorKind.TRUNCATED, error.kind, "$vector cut to $length")
                assertTrue(error.needed > error.available, "$vector cut to $length: $error")
            }
        }
    }

    @Test
    fun `heart rate prefixes are truncated until the announced fields are whole, then the RR list is just shorter`() {
        // HR7: 16-bit bpm and two RR intervals. Needs flags + 2 bytes of bpm.
        val hr7 = hex("17 96 00 84 03 00 04")
        assertEquals(DecodeErrorKind.TRUNCATED, HeartRateCodec.parse(hr7.copyOf(1)).malformed().kind)
        assertEquals(DecodeErrorKind.TRUNCATED, HeartRateCodec.parse(hr7.copyOf(2)).malformed().kind)
        assertEquals(emptyList(), HeartRateCodec.parse(hr7.copyOf(3)).ok().rrIntervalsRaw)
        assertEquals(listOf(900), HeartRateCodec.parse(hr7.copyOf(5)).ok().rrIntervalsRaw)
        assertEquals(listOf(900), HeartRateCodec.parse(hr7.copyOf(6)).ok().rrIntervalsRaw)
        // HR8: energy is announced, so it must be whole.
        val hr8 = hex("18 78 64 00 00 04")
        assertEquals(DecodeErrorKind.TRUNCATED, HeartRateCodec.parse(hr8.copyOf(3)).malformed().kind)
        assertEquals(DecodeErrorKind.TRUNCATED, HeartRateCodec.parse(hr8.copyOf(1)).malformed().kind)
    }

    @Test
    fun `short control point responses, status and feature payloads are typed errors`() {
        assertEquals(DecodeErrorKind.TRUNCATED, ControlPointCodec.parseResponse(hex("80 00")).malformed().kind)
        assertEquals(
            DecodeErrorKind.UNEXPECTED_MARKER,
            ControlPointCodec.parseResponse(hex("81 00 01")).malformed().kind,
        )
        assertEquals(DecodeErrorKind.TRUNCATED, FitnessMachineStatusCodec.parse(hex("08 C8")).malformed().kind)
        assertEquals(
            DecodeErrorKind.TRUNCATED,
            FitnessMachineStatusCodec.parse(hex("12 00 00 38 FF 28")).malformed().kind,
        )
        assertEquals(DecodeErrorKind.TRUNCATED, FitnessMachineStatusCodec.parse(hex("02")).malformed().kind)
        assertEquals(DecodeErrorKind.TRUNCATED, FitnessMachineFeatureCodec.parse(hex("06 54 00 00")).malformed().kind)
        assertEquals(
            DecodeErrorKind.WRONG_LENGTH,
            FitnessMachineFeatureCodec.parse(hex("06 54 00 00 08 20 00 00 00")).malformed().kind,
        )
    }

    @Test
    fun `random Indoor Bike Data survives an encode and decode round trip`() {
        val random = Random(99)
        repeat(2_000) {
            fun maybe(value: Int) = if (random.nextBoolean()) value else null
            val moreData = random.nextInt(5) == 0
            val map = if (random.nextBoolean()) IndoorBikeFlagMap.DECODERS else IndoorBikeFlagMap.SIG_XML_LABELS
            val energy = random.nextBoolean()
            val original =
                IndoorBikeData(
                    flags = 0,
                    moreData = moreData,
                    instantaneousSpeedKmh = if (moreData) null else random.nextInt(0, 9000) / 100.0,
                    averageSpeedKmh = maybe(random.nextInt(0, 9000))?.let { it / 100.0 },
                    instantaneousCadenceRpm = maybe(random.nextInt(0, 400))?.let { it / 2.0 },
                    averageCadenceRpm = maybe(random.nextInt(0, 400))?.let { it / 2.0 },
                    totalDistanceMetres = maybe(random.nextInt(0, 1 shl 24)),
                    resistanceLevel = maybe(random.nextInt(-100, 100)),
                    instantaneousPowerWatts = maybe(random.nextInt(-100, 2500)),
                    averagePowerWatts = maybe(random.nextInt(-100, 2500)),
                    totalEnergyKcal = if (energy) random.nextInt(0, 60000) else null,
                    energyPerHourKcal = if (energy) random.nextInt(0, 60000) else null,
                    energyPerMinuteKcal = if (energy) random.nextInt(0, 255) else null,
                    heartRateBpm = maybe(random.nextInt(0, 255)),
                    metabolicEquivalent = maybe(random.nextInt(0, 255))?.let { it / 10.0 },
                    elapsedTimeSeconds = maybe(random.nextInt(0, 65535)),
                    remainingTimeSeconds = maybe(random.nextInt(0, 65535)),
                )
            val decoded = IndoorBikeDataCodec.parse(IndoorBikeDataEncoder.encode(original, map), map).ok()
            assertEquals(original, decoded.copy(flags = 0))
        }
    }

    @Test
    fun `random heart rate measurements survive an encode and decode round trip`() {
        val random = Random(5)
        repeat(2_000) {
            val bpm = random.nextInt(0, 400)
            val contact = random.nextInt(4)
            val rr = if (random.nextBoolean()) List(random.nextInt(0, 6)) { random.nextInt(0, 65536) } else null
            val energy = if (random.nextBoolean()) random.nextInt(0, 65536) else null
            val decoded =
                HeartRateCodec
                    .parse(
                        HeartRateEncoder.encode(bpm, contact, rr, energy, force16Bit = random.nextBoolean()),
                    ).ok()
            assertEquals(bpm, decoded.bpm)
            assertEquals(rr, decoded.rrIntervalsRaw)
            assertEquals(energy, decoded.energyKilojoules)
        }
    }
}
