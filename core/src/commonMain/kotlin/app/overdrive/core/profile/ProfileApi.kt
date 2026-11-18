package app.overdrive.core.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** What a patch does to one field: leave it, or set it (the value may be null where the field has a "none"). */
public sealed interface Change<out T> {
    public data object Keep : Change<Nothing>

    public data class Set<out T>(
        val value: T,
    ) : Change<T>
}

/** A patch of the physics constants: only the ones that are not null change. */
public data class PhysicsPatch(
    val systemMassKg: Double? = null,
    val cdA: Double? = null,
    val crr: Double? = null,
    val airDensity: Double? = null,
    val drivetrainEfficiency: Double? = null,
)

/**
 * The body of `PATCH /v1/profile` (`contracts/schema/profile-patch.schema.json`): a partial update in the manner of a
 * JSON merge patch. [Change.Keep] and absent entries change nothing. [ftp] set to null clears the FTP; [zones] set to
 * null resets them to Coggan's; [weeklyAvailability] names only the weekdays to change (null makes one unknown again);
 * [goals] replaces the whole list. `updatedAt` is not part of a patch: the service stamps it.
 */
public data class ProfilePatch(
    val ftp: Change<Int?> = Change.Keep,
    val zones: Change<PowerZones?> = Change.Keep,
    val weeklyAvailability: Map<Weekday, Int?> = emptyMap(),
    val goals: List<String>? = null,
    val physics: PhysicsPatch = PhysicsPatch(),
    /** Set to null puts W′ back to the service's default. */
    val wPrimeKj: Change<Double?> = Change.Keep,
) {
    /**
     * The profile this patch makes of [profile], with `updatedAt` as it was.
     *
     * @throws IllegalArgumentException if the result is not a valid profile.
     */
    public fun applyTo(profile: Profile): Profile {
        var availability = profile.weeklyAvailability
        for ((day, minutes) in weeklyAvailability) availability = availability.with(day, minutes)
        val before = profile.physics
        return profile.copy(
            ftp =
                when (ftp) {
                    Change.Keep -> profile.ftp
                    is Change.Set -> ftp.value
                },
            zones =
                when (zones) {
                    Change.Keep -> profile.zones
                    is Change.Set -> zones.value ?: PowerZones.COGGAN
                },
            wPrimeKj =
                when (wPrimeKj) {
                    Change.Keep -> profile.wPrimeKj
                    is Change.Set -> wPrimeKj.value
                },
            weeklyAvailability = availability,
            goals = goals ?: profile.goals,
            physics =
                before.copy(
                    systemMassKg = physics.systemMassKg ?: before.systemMassKg,
                    cdA = physics.cdA ?: before.cdA,
                    crr = physics.crr ?: before.crr,
                    airDensity = physics.airDensity ?: before.airDensity,
                    drivetrainEfficiency = physics.drivetrainEfficiency ?: before.drivetrainEfficiency,
                ),
        )
    }
}

/**
 * Reads and writes the profile bodies of the sync API (spec §8.3). Strict like the schemas: unknown keys, wrong types,
 * values out of range and a missing key are all rejected with an [IllegalArgumentException].
 */
public object ProfileApiJson {
    private val json = Json { encodeDefaults = true }

    private val patchKeys = setOf("ftp", "wPrimeKj", "zones", "weeklyAvailability", "goals", "physics")
    private val physicsKeys = setOf("systemMassKg", "cdA", "crr", "airDensity", "drivetrainEfficiency")

    /** @throws IllegalArgumentException if [text] is not a profile of the schema. */
    public fun decodeProfile(text: String): Profile {
        val root = json.parseToJsonElement(text)
        require(root is JsonObject) { "A profile is a JSON object" }
        // kotlinx reads "250" as the number 250; the schema does not.
        root["ftp"]?.let { requireNumber(it, "ftp") }
        // The key is always on the wire; the model's default is only for code that builds a profile.
        require("wPrimeKj" in root) { "A profile has a wPrimeKj, null when not set" }
        root["wPrimeKj"]?.let { requireNumber(it, "wPrimeKj") }
        (root["zones"] as? JsonObject)?.let { requireBoundsAreNumbers(it) }
        (root["weeklyAvailability"] as? JsonObject)?.forEach { (day, value) -> requireNumber(value, day) }
        (root["physics"] as? JsonObject)?.forEach { (key, value) -> requireNumber(value, key) }
        return json.decodeFromJsonElement(Profile.serializer(), root)
    }

    private fun requireNumber(
        element: JsonElement,
        what: String,
    ) = require(element !is JsonPrimitive || !element.isString) { "$what must be a number, not a string" }

    private fun requireBoundsAreNumbers(zones: JsonObject) {
        (zones["upperPercentOfFtp"] as? JsonArray)?.forEach { requireNumber(it, "a zone bound") }
    }

    public fun encodeProfile(profile: Profile): String = json.encodeToString(Profile.serializer(), profile)

    /** @throws IllegalArgumentException if [text] is not a patch of the schema. */
    public fun decodePatch(text: String): ProfilePatch = patchOf(json.parseToJsonElement(text))

    private fun patchOf(element: JsonElement): ProfilePatch {
        require(element is JsonObject) { "A patch is a JSON object" }
        val unknown = element.keys - patchKeys
        require(unknown.isEmpty()) { "Unknown patch key(s): ${unknown.sorted().joinToString()}" }
        return ProfilePatch(
            ftp = element["ftp"]?.let { Change.Set(nullableInt(it, "ftp")) } ?: Change.Keep,
            zones =
                element["zones"]?.let {
                    if (it is JsonObject) requireBoundsAreNumbers(it)
                    Change.Set(if (it is JsonNull) null else json.decodeFromJsonElement<PowerZones>(it))
                } ?: Change.Keep,
            weeklyAvailability = element["weeklyAvailability"]?.let(::availabilityOf).orEmpty(),
            goals = element["goals"]?.let(::goalsOf),
            physics = element["physics"]?.let(::physicsOf) ?: PhysicsPatch(),
            wPrimeKj = element["wPrimeKj"]?.let { Change.Set(nullableNumber(it, "wPrimeKj")) } ?: Change.Keep,
        )
    }

    private fun nullableNumber(
        element: JsonElement,
        what: String,
    ): Double? {
        if (element is JsonNull) return null
        require(element is JsonPrimitive && !element.isString) { "$what must be a number or null" }
        return requireNotNull(element.content.toDoubleOrNull()) { "$what must be a number or null" }
    }

    private fun nullableInt(
        element: JsonElement,
        what: String,
    ): Int? {
        if (element is JsonNull) return null
        require(element is JsonPrimitive && !element.isString) { "$what must be an integer or null" }
        return requireNotNull(element.content.toIntOrNull()) { "$what must be an integer or null" }
    }

    private fun availabilityOf(element: JsonElement): Map<Weekday, Int?> {
        require(element is JsonObject) { "weeklyAvailability must be an object" }
        val days = Weekday.entries.associateBy { it.name.lowercase() }
        return element.entries.associate { (key, value) ->
            val day = requireNotNull(days[key]) { "Unknown weekday '$key'" }
            day to nullableInt(value, key)
        }
    }

    private fun goalsOf(element: JsonElement): List<String> {
        require(element is JsonArray) { "goals must be an array of strings" }
        return element.map {
            require(it is JsonPrimitive && it.isString) { "goals must be an array of strings" }
            it.content
        }
    }

    private fun physicsOf(element: JsonElement): PhysicsPatch {
        require(element is JsonObject) { "physics must be an object" }
        val unknown = element.keys - physicsKeys
        require(unknown.isEmpty()) { "Unknown physics key(s): ${unknown.sorted().joinToString()}" }

        fun number(key: String): Double? =
            element[key]?.let {
                require(it is JsonPrimitive && !it.isString) { "$key must be a number" }
                requireNotNull(it.content.toDoubleOrNull()) { "$key must be a number" }
            }
        return PhysicsPatch(
            systemMassKg = number("systemMassKg"),
            cdA = number("cdA"),
            crr = number("crr"),
            airDensity = number("airDensity"),
            drivetrainEfficiency = number("drivetrainEfficiency"),
        )
    }
}
