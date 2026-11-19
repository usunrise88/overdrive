package app.overdrive.core.metrics

import app.overdrive.core.sync.ApiErrorCode
import app.overdrive.core.sync.SyncApiJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-language contract: the Kotlin model of the metrics and rides answers must agree with the schemas in
 * `contracts/schema` on every fixture under `contracts/fixtures/metrics`. The TypeScript tests of `contracts/` (and the
 * service) run the same files.
 */
class MetricsContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    /** The metrics fixtures, and a ride detail whose plan has a `route_id` step (stage A2b-2) under `fixtures/routes/ride-detail-response`. */
    private fun fixtures(path: String): List<File> =
        listOf(File(contracts, "fixtures/metrics/$path"), File(contracts, "fixtures/routes/$path"))
            .flatMap { dir -> dir.listFiles { f -> f.extension == "json" }.orEmpty().toList() }
            .sortedBy { it.name }

    /** Numbers compare by value (the fixtures write `612`, Kotlin writes a Double as `612.0`). */
    private fun normalize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                JsonObject(element.mapValues { normalize(it.value) })
            }

            is JsonArray -> {
                JsonArray(element.map(::normalize))
            }

            is JsonNull -> {
                element
            }

            is JsonPrimitive -> {
                if (element.isString) element else element.content.toDoubleOrNull()?.let(::JsonPrimitive) ?: element
            }
        }

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    private class Case<T>(
        val directory: String,
        val decode: (String) -> T,
        val encode: (T) -> String,
        val valid: Int,
        val invalid: Int,
    )

    private val cases: List<Case<*>> =
        listOf(
            Case("metrics-response", MetricsApiJson::decodeMetrics, MetricsApiJson::encodeMetrics, 8, 35),
            Case(
                "ride-metrics-response",
                MetricsApiJson::decodeRideMetrics,
                MetricsApiJson::encodeRideMetrics,
                4,
                17,
            ),
            Case("rides-response", MetricsApiJson::decodeRides, MetricsApiJson::encodeRides, 3, 20),
            Case(
                "ride-detail-response",
                MetricsApiJson::decodeRideDetail,
                MetricsApiJson::encodeRideDetail,
                5,
                20,
            ),
            Case(
                "ftp-suggestion-dismiss-request",
                MetricsApiJson::decodeDismissRequest,
                MetricsApiJson::encodeDismissRequest,
                1,
                5,
            ),
            Case(
                "ftp-suggestion-dismiss-response",
                MetricsApiJson::decodeDismissResponse,
                MetricsApiJson::encodeDismissResponse,
                3,
                3,
            ),
        )

    @Test
    fun `finds the fixtures`() {
        for (case in cases) {
            assertTrue(fixtures("${case.directory}/valid").size >= case.valid, case.directory)
            assertTrue(fixtures("${case.directory}/invalid").size >= case.invalid, case.directory)
        }
    }

    @Test
    fun `reads every valid answer and writes it back the same`() {
        for (case in cases) {
            readAndWrite(case)
        }
    }

    private fun <T> readAndWrite(case: Case<T>) {
        for (file in fixtures("${case.directory}/valid")) {
            val value = case.decode(file.readText())
            val written = case.encode(value)
            assertEquals(value, case.decode(written), "${case.directory}/${file.name}")
            assertEquals(
                normalize(parse(file.readText())),
                normalize(parse(written)),
                "${case.directory}/${file.name}",
            )
        }
    }

    @Test
    fun `rejects every invalid answer`() {
        for (case in cases) {
            for (file in fixtures("${case.directory}/invalid")) {
                assertFailsWith<IllegalArgumentException>("${case.directory}/${file.name}") {
                    case.decode(file.readText())
                }
            }
        }
    }

    @Test
    fun `the power curve has the 25 durations of the design`() {
        assertEquals(25, MetricsApiJson.POWER_CURVE_DURATIONS.size)
        val minutes = listOf(1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 7.0, 10.0, 15.0, 20.0, 30.0, 40.0, 60.0, 90.0, 120.0)
        assertEquals(
            listOf(1, 2, 3, 5, 8, 10, 15, 20, 30, 45) + minutes.map { (it * 60).toInt() },
            MetricsApiJson.POWER_CURVE_DURATIONS,
        )
    }

    @Test
    fun `a ride without power or a drift is a valid answer`() {
        val file = File(contracts, "fixtures/metrics/ride-metrics-response/valid/no-power-no-drift.json")
        val ride = MetricsApiJson.decodeRideMetrics(file.readText())
        assertNull(ride.load)
        assertTrue(ride.powerCurve.isEmpty())
        assertTrue(!ride.drift.available)
        assertNull(ride.wPrimeBalance)
    }

    @Test
    fun `serves 366 days and refuses 367`() {
        val empty = File(contracts, "fixtures/metrics/metrics-response/valid/no-rides-yet.json").readText()
        val day = MetricsApiJson.decodeMetrics(empty).daily.first()

        fun over(days: Int): String {
            // Days since 1970-01-01; the Monday of a week is worked out from them (1970-01-01 was a Thursday).
            val end = 20_775L // 2026-11-18
            val first = end - days + 1
            val dates = (0 until days).map { date(first + it) }
            val monday = { d: Long -> d - (d + 3).mod(7L) }
            val weeks = (monday(first)..monday(end) step 7).map { date(it) }
            val daily = dates.joinToString(",") { dayJson(day, it) }
            val weekly =
                weeks.joinToString(",") {
                    """{"weekStart":"$it","load":0,"rideLoad":0,"otherLoad":0,"rides":0,"activities":0}"""
                }
            return """{"from":"${dates.first()}","to":"2026-11-18","generatedAt":"2026-11-18T08:00:00Z",""" +
                """"stale":false,"firstLoadDate":null,"daily":[$daily],"weekly":[$weekly],""" +
                """"powerCurve":{"range":[],"allTime":[]},""" +
                """"ftp":{"current":null,"setAt":null,"suggestion":null,"latestEstimate":null,""" +
                """"history":[],"estimates":[]},"drift":[]}"""
        }
        assertEquals(366, MetricsApiJson.decodeMetrics(over(366)).daily.size)
        assertEquals(1, MetricsApiJson.decodeMetrics(over(1)).daily.size)
        assertFailsWith<IllegalArgumentException> { MetricsApiJson.decodeMetrics(over(367)) }
    }

    private fun dayJson(
        template: MetricsDay,
        date: String,
    ): String =
        """{"date":"$date","load":${template.load},"loadCycling":${template.loadCycling},""" +
            """"loadOther":${template.loadOther},"ctl":${template.ctl},"atl":${template.atl},"tsb":${template.tsb}}"""

    /** `YYYY-MM-DD` of days since 1970-01-01, by Howard Hinnant's algorithm. */
    private fun date(epochDay: Long): String {
        val z = epochDay + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "%04d-%02d-%02d".format(y, m, d)
    }

    @Test
    fun `power curve durations are the schemas`() {
        for (name in listOf("metrics-response")) {
            val schema = parse(File(contracts, "schema/$name.schema.json").readText()) as JsonObject
            val definitions = schema.getValue("\$defs") as JsonObject
            val durations = (definitions.getValue("duration") as JsonObject).getValue("enum") as JsonArray
            assertEquals(MetricsApiJson.POWER_CURVE_DURATIONS, durations.map { (it as JsonPrimitive).content.toInt() })
        }
    }

    @Test
    fun `invalid_query is a code of the API errors`() {
        val file = File(contracts, "fixtures/sync/api-error/valid/invalid-query.json")
        assertEquals(ApiErrorCode.INVALID_QUERY, SyncApiJson.decodeApiError(file.readText()).code)
        assertEquals(400, ApiErrorCode.INVALID_QUERY.httpStatus)
    }
}
