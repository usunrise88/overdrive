package app.overdrive.core.route

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cross-language contract: the Kotlin model of a route and of the list of routes must agree with `contracts/schema/route.schema.json` and
 * `routes-response.schema.json` on every fixture under `contracts/fixtures/routes/route` and `routes-response` (ADR 0036). The TypeScript
 * tests of `contracts/` run the same fixtures against the schemas.
 */
class RoutesContractTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun fixtures(
        kind: String,
        dir: String,
    ): List<File> =
        File(contracts, "fixtures/routes/$kind/$dir")
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }

    private fun fixture(path: String): String = File(contracts, "fixtures/routes/$path").readText()

    /** Numbers compare by value (the fixtures write `20`, Kotlin writes a Double as `20.0`). */
    private fun normalize(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { normalize(it.value) })
            is JsonArray -> JsonArray(element.map(::normalize))
            is JsonNull -> element
            is JsonPrimitive -> numeric(element)
        }

    private fun numeric(element: JsonPrimitive): JsonElement =
        if (element.isString) element else element.content.toDoubleOrNull()?.let(::JsonPrimitive) ?: element

    private fun sameJson(
        original: String,
        written: String,
        name: String,
    ) = assertEquals(normalize(Json.parseToJsonElement(original)), normalize(Json.parseToJsonElement(written)), name)

    @Test
    fun `finds fixtures`() {
        assertTrue(fixtures("route", "valid").size >= 4)
        assertTrue(fixtures("route", "invalid").size >= 20)
        assertTrue(fixtures("routes-response", "valid").size >= 2)
        assertTrue(fixtures("routes-response", "invalid").size >= 5)
    }

    @Test
    fun `reads every valid route and writes it back the same`() {
        for (file in fixtures("route", "valid")) {
            val route = RoutesApiJson.decodeRoute(file.readText())
            val written = RoutesApiJson.encodeRoute(route)
            assertEquals(route, RoutesApiJson.decodeRoute(written), file.name)
            sameJson(file.readText(), written, file.name)
        }
    }

    @Test
    fun `rejects every invalid route`() {
        for (file in fixtures("route", "invalid")) {
            assertFailsWith<IllegalArgumentException>(file.name) { RoutesApiJson.decodeRoute(file.readText()) }
        }
    }

    @Test
    fun `reads every valid list of routes and writes it back the same`() {
        for (file in fixtures("routes-response", "valid")) {
            val response = RoutesApiJson.decodeRoutes(file.readText())
            val written = RoutesApiJson.encodeRoutes(response)
            assertEquals(response, RoutesApiJson.decodeRoutes(written), file.name)
            sameJson(file.readText(), written, file.name)
        }
    }

    @Test
    fun `rejects every invalid list of routes`() {
        for (file in fixtures("routes-response", "invalid")) {
            assertFailsWith<IllegalArgumentException>(file.name) { RoutesApiJson.decodeRoutes(file.readText()) }
        }
    }

    @Test
    fun `the three routes of the list say what they are`() {
        val list = RoutesApiJson.decodeRoutes(fixture("routes-response/valid/three-routes.json"))
        assertEquals(listOf("route-wp-0001", "route-gpx-0001", "route-profile01"), list.routes.map { it.id })
        assertEquals(listOf(RouteSource.WAYPOINTS, RouteSource.GPX, RouteSource.PROFILE), list.routes.map { it.source })
        assertEquals(listOf(true, true, false), list.routes.map { it.hasCoordinates })
        assertTrue(RoutesApiJson.decodeRoutes(fixture("routes-response/valid/empty.json")).routes.isEmpty())
    }

    @Test
    fun `a route with coordinates is a gpx route and one without is a profile`() {
        val gpx = RoutesApiJson.decodeRoute(fixture("route/valid/gpx-with-coordinates.json"))
        assertTrue(gpx.hasCoordinates && gpx.points.all { it.hasCoordinates })
        assertEquals(RouteSource.GPX, gpx.source)
        assertEquals(gpx.summary.id, gpx.id)
        val profile = RoutesApiJson.decodeRoute(fixture("route/valid/profile.json"))
        assertFalse(profile.hasCoordinates)
        assertTrue(profile.points.all { it.lat == null && it.lon == null })
        // Below sea level is a valid elevation.
        val dead = RoutesApiJson.decodeRoute(fixture("route/valid/below-sea-level.json"))
        assertTrue(dead.points.all { it.e < 0.0 })
    }

    @Test
    fun `the id syntax, the limits and the sources are the schema's`() {
        val schema = Json.parseToJsonElement(File(contracts, "schema/route.schema.json").readText()).jsonObject
        val defs = schema.getValue("\$defs").jsonObject

        fun def(name: String) = defs.getValue(name).jsonObject

        fun number(
            element: JsonObject,
            key: String,
        ) = element.getValue(key).jsonPrimitive.content.toInt()
        assertEquals("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\$", def("id").getValue("pattern").jsonPrimitive.content)
        assertTrue(RoutesApiJson.isValidRouteId("route-3f9a2c"))
        assertFalse(RoutesApiJson.isValidRouteId(""))
        assertFalse(RoutesApiJson.isValidRouteId("../r"))
        assertFalse(RoutesApiJson.isValidRouteId("x".repeat(65)))
        val sources = def("source").getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("gpx", "waypoints", "profile"), sources)
        assertEquals(sources, RouteSource.entries.map { Json.encodeToString(RouteSource.serializer(), it).trim('"') })
        val points = schema.getValue("properties").jsonObject.getValue("points").jsonObject
        assertEquals(RoutesApiJson.MIN_POINTS, number(points, "minItems"))
        assertEquals(RoutesApiJson.MAX_POINTS, number(points, "maxItems"))
        assertEquals(RoutesApiJson.MIN_DISTANCE_METERS, number(def("distanceMeters"), "minimum"))
        assertEquals(RoutesApiJson.MAX_NAME_CHARS, number(def("name"), "maxLength"))
    }

    @Test
    fun `a route of the most points is read and one more is not`() {
        fun route(count: Int): String {
            val points = (0 until count).joinToString(",") { """{"d":${it * 20},"e":100}""" }
            return """{"id":"route-big","name":"Big","source":"profile","distanceMeters":${(count - 1) * 20},""" +
                """"ascentMeters":0,"hasCoordinates":false,"createdAt":"2026-11-19T09:00:00Z","points":[$points]}"""
        }
        assertNotNull(RoutesApiJson.decodeRoute(route(RoutesApiJson.MAX_POINTS)))
        assertFailsWith<IllegalArgumentException> { RoutesApiJson.decodeRoute(route(RoutesApiJson.MAX_POINTS + 1)) }
    }

    @Test
    fun `the ascent is allowed a metre of rounding and no more`() {
        fun route(ascent: Int) =
            """{"id":"route-up","name":"Up","source":"profile","distanceMeters":100,"ascentMeters":$ascent,""" +
                """"hasCoordinates":false,"createdAt":"2026-11-19T09:00:00Z",""" +
                """"points":[{"d":0,"e":10},{"d":100,"e":15}]}"""
        assertNotNull(RoutesApiJson.decodeRoute(route(5)))
        assertNotNull(RoutesApiJson.decodeRoute(route(6)))
        assertNotNull(RoutesApiJson.decodeRoute(route(4)))
        assertFailsWith<IllegalArgumentException> { RoutesApiJson.decodeRoute(route(7)) }
        assertFailsWith<IllegalArgumentException> { RoutesApiJson.decodeRoute(route(3)) }
    }
}
