package app.overdrive.core.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Crc32Test {
    @Test
    fun `the check value of CRC-32`() {
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
        assertEquals(0L, Crc32.of(ByteArray(0)))
        assertEquals(0xCBF43926L, Crc32.of("xx123456789yy".encodeToByteArray(), from = 2, to = 11))
    }
}

/** The wire format of the ride record in Kotlin; the cross-language agreement on the fixtures is in `RideRecordContractTest`. */
class RideRecordJsonTest {
    private val record = SyntheticRide.record()

    private fun tiny(vararg samples: RideSample): RideRecord =
        RideRecordBuilder.recover(RideHeader("t", 1_000, ftp = 200), samples.toList())

    @Test
    fun `a record survives a round trip exactly, doubles included`() {
        assertEquals(record, RideRecordJson.decode(RideRecordJson.encode(record)))
        val awkward =
            tiny(
                RideSample(1, speedKmh = 1.0 / 3.0, distanceMeters = 1.0e-7),
                RideSample(2, speedKmh = 123456789.123456789, distanceMeters = 1.0e21),
            )
        assertEquals(awkward, RideRecordJson.decode(RideRecordJson.encode(awkward)))
    }

    @Test
    fun `an empty ride is a record too`() {
        val empty = tiny()
        assertEquals(empty, RideRecordJson.decode(RideRecordJson.encode(empty)))
    }

    @Test
    fun `a gap is an absent key, and only that`() {
        val json =
            RideRecordJson.encode(
                tiny(RideSample(1, power = null, heartRate = 100, speedKmh = 1.0, distanceMeters = 0.5)),
            )
        assertFalse("null" in json, json)
        assertFalse("\"power\"" in json)
        assertTrue("\"heartRate\":100" in json)
        // The values that always have a meaning are always written.
        assertTrue("\"paused\":false" in json && "\"devices\":[]" in json && "\"speedKmh\":1.0" in json)
    }

    @Test
    fun `the document is the documented shape`() {
        val json = RideRecordJson.encode(tiny(RideSample(1, power = 150, speedKmh = 25.0, distanceMeters = 6.9)))
        assertEquals(
            """{"schema":1,"ride":{"id":"t","startEpochMillis":1000,"ftp":200,"devices":[]},""" +
                """"samples":[{"timeOffsetSeconds":1,"power":150,"speedKmh":25.0,"distanceMeters":6.9,""" +
                """"paused":false}],""" +
                """"rrIntervals":[],"laps":[{"startSeconds":0,"endSeconds":1,"averagePower":150.0}],""" +
                """"summary":{"durationSeconds":1,"movingSeconds":1,"distanceMeters":6.9,"averagePower":150.0,""" +
                """"maxPower":150,"workKilojoules":0.15}}""",
            json,
        )
    }

    @Test
    fun `explicit nulls are read as gaps`() {
        val json = RideRecordJson.encode(tiny(RideSample(1, power = 150, speedKmh = 25.0)))
        val withNulls = json.replace("\"power\":150", "\"power\":null,\"heartRate\":null,\"gear\":null")
        assertEquals(
            tiny(RideSample(1, power = null, speedKmh = 25.0)).samples,
            RideRecordJson.decode(withNulls).samples,
        )
    }

    private fun assertRejected(
        text: String,
        why: String,
    ) {
        assertFailsWith<IllegalArgumentException>(why) { RideRecordJson.decode(text) }
    }

    @Test
    fun `what the schema forbids is rejected`() {
        val good = RideRecordJson.encode(tiny(RideSample(1, power = 150, speedKmh = 25.0)))
        assertEquals(tiny(RideSample(1, power = 150, speedKmh = 25.0)), RideRecordJson.decode(good))
        assertRejected(good.replace("\"schema\":1", "\"schema\":2"), "wrong schema")
        assertRejected(good.replace("\"schema\":1,", ""), "no schema")
        assertRejected(good.replace("\"ftp\":200", "\"ftp\":0"), "ftp 0")
        assertRejected(good.replace("\"id\":\"t\"", "\"id\":\"\""), "empty id")
        assertRejected(good.replace("\"power\":150", "\"power\":-1"), "negative power")
        assertRejected(good.replace("\"power\":150", "\"power\":150.5"), "fractional power")
        assertRejected(good.replace("\"timeOffsetSeconds\":1", "\"timeOffsetSeconds\":0"), "offset 0")
        assertRejected(good.replace("\"speedKmh\":25.0", "\"speedKmh\":-1.0"), "negative speed")
        assertRejected(good.replace("\"paused\":false", "\"paused\":false,\"colour\":\"red\""), "unknown key")
        assertRejected(good.replace("\"laps\":[", "\"laps\":[{},"), "an empty lap")
        assertRejected("", "empty text")
        assertRejected("[]", "an array")
        assertRejected(good.dropLast(3), "truncated")
    }

    @Test
    fun `what the schema cannot say is rejected too`() {
        val three = tiny(RideSample(1), RideSample(2), RideSample(3))
        val json = RideRecordJson.encode(three)
        assertRejected(json.replace("\"timeOffsetSeconds\":2", "\"timeOffsetSeconds\":3"), "offsets 1, 3, 3")
        assertRejected(json.replace("\"endSeconds\":3", "\"endSeconds\":2"), "laps shorter than the ride")
        assertRejected(json.replace("\"startSeconds\":0", "\"startSeconds\":1"), "laps not starting at 0")
        assertRejected(json.replace("\"durationSeconds\":3", "\"durationSeconds\":4"), "summary of another length")
        assertRejected(json.replace("\"endSeconds\":3", "\"endSeconds\":0"), "a lap that ends before it starts")
    }

    @Test
    fun `laps must follow each other`() {
        val laps = listOf(Lap(0, 0, 2, null, null), Lap(1, 3, 4, null, null))
        val summary = RideSummary.of(listOf(RideSample(1), RideSample(2), RideSample(3), RideSample(4)), 200)
        val broken =
            RideRecord(RideHeader("t", 1, ftp = 200), (1..4).map { RideSample(it) }, emptyList(), laps, summary)
        assertFailsWith<IllegalArgumentException> { RideRecordJson.encode(broken) }
    }

    @Test
    fun `a ride record is not a log or a workout`() {
        assertRejected(RideRecordJson.encode(tiny(RideSample(1))).replace("\"samples\"", "\"sample\""), "renamed key")
        assertRejected("""{"schema":1,"id":"w","name":"n","steps":[]}""", "a workout")
    }
}
