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
    val maxHeartRate: Change<Int?> = Change.Keep,
    val thresholdHeartRate: Change<Int?> = Change.Keep,
    /** The bike's weight in kg (spec §12.5, docs/adr/0034); set to null clears it. */
    val bikeWeightKg: Change<Double?> = Change.Keep,
    /** Which power-skills table the levels use (docs/adr/0042); set to null goes back to the default, the men's. */
    val powerProfileTable: Change<PowerProfileTable?> = Change.Keep,
) {
    /** Whether the patch changes nothing. */
    public val isEmpty: Boolean
        get() =
            ftp == Change.Keep && zones == Change.Keep && weeklyAvailability.isEmpty() && goals == null &&
                physics == PhysicsPatch() && wPrimeKj == Change.Keep && maxHeartRate == Change.Keep &&
                thresholdHeartRate == Change.Keep && bikeWeightKg == Change.Keep && powerProfileTable == Change.Keep

    /**
     * The one patch that does what this one does and then [later] does: where both name a field, [later] wins (the last write
     * wins), where only one does, that one stands. Applying the result to a profile gives the same profile as applying the two
     * in turn.
     */
    public fun andThen(later: ProfilePatch): ProfilePatch =
        ProfilePatch(
            ftp = if (later.ftp == Change.Keep) ftp else later.ftp,
            zones = if (later.zones == Change.Keep) zones else later.zones,
            wPrimeKj = if (later.wPrimeKj == Change.Keep) wPrimeKj else later.wPrimeKj,
            weeklyAvailability = weeklyAvailability + later.weeklyAvailability,
            goals = later.goals ?: goals,
            physics =
                PhysicsPatch(
                    systemMassKg = later.physics.systemMassKg ?: physics.systemMassKg,
                    cdA = later.physics.cdA ?: physics.cdA,
                    crr = later.physics.crr ?: physics.crr,
                    airDensity = later.physics.airDensity ?: physics.airDensity,
                    drivetrainEfficiency = later.physics.drivetrainEfficiency ?: physics.drivetrainEfficiency,
                ),
            maxHeartRate = if (later.maxHeartRate == Change.Keep) maxHeartRate else later.maxHeartRate,
            thresholdHeartRate =
                if (later.thresholdHeartRate == Change.Keep) thresholdHeartRate else later.thresholdHeartRate,
            bikeWeightKg = if (later.bikeWeightKg == Change.Keep) bikeWeightKg else later.bikeWeightKg,
            powerProfileTable =
                if (later.powerProfileTable == Change.Keep) powerProfileTable else later.powerProfileTable,
        )

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
            maxHeartRate =
                when (maxHeartRate) {
                    Change.Keep -> profile.maxHeartRate
                    is Change.Set -> maxHeartRate.value
                },
            thresholdHeartRate =
                when (thresholdHeartRate) {
                    Change.Keep -> profile.thresholdHeartRate
                    is Change.Set -> thresholdHeartRate.value
                },
            bikeWeightKg =
                when (bikeWeightKg) {
                    Change.Keep -> profile.bikeWeightKg
                    is Change.Set -> bikeWeightKg.value
                },
            powerProfileTable =
                when (powerProfileTable) {
                    Change.Keep -> profile.powerProfileTable
                    is Change.Set -> powerProfileTable.value
                },
        )
    }
}

/**
 * Reads and writes the profile bodies of the sync API (spec §8.3). Strict like the schemas: unknown keys, wrong types,
 * values out of range and a missing key are all rejected with an [IllegalArgumentException].
 */
public object ProfileApiJson {
    private val json = Json { encodeDefaults = true }

    private val patchKeys =
        setOf(
            "ftp",
            "wPrimeKj",
            "zones",
            "weeklyAvailability",
            "goals",
            "physics",
            "maxHeartRate",
            "thresholdHeartRate",
            "bikeWeightKg",
            "powerProfileTable",
        )
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
        // The four heart-rate keys are optional on read (a profile cached before they existed) and always written.
        for (key in listOf(
            "maxHeartRate",
            "thresholdHeartRate",
            "estimatedMaxHeartRate",
            "estimatedThresholdHeartRate",
        )) {
            root[key]?.let { requireNumber(it, key) }
        }
        // The weight keys are optional too: written only when they have a value (spec §12.5).
        root["bikeWeightKg"]?.let { requireNumber(it, "bikeWeightKg") }
        (root["riderWeight"] as? JsonObject)?.let { weight ->
            weight["kg"]?.let { requireNumber(it, "riderWeight.kg") }
            weight["days"]?.let { requireNumber(it, "riderWeight.days") }
        }
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

    /** The JSON of [patch] as `PATCH /v1/profile` takes it: only the fields it changes, in the schema's shapes, on one line. */
    public fun encodePatch(patch: ProfilePatch): String {
        val body = LinkedHashMap<String, JsonElement>()
        (patch.ftp as? Change.Set)?.let { body["ftp"] = it.value?.let(::JsonPrimitive) ?: JsonNull }
        (patch.wPrimeKj as? Change.Set)?.let { body["wPrimeKj"] = it.value?.let(::JsonPrimitive) ?: JsonNull }
        (patch.zones as? Change.Set)?.let { change ->
            body["zones"] = change.value?.let { json.encodeToJsonElement(PowerZones.serializer(), it) } ?: JsonNull
        }
        if (patch.weeklyAvailability.isNotEmpty()) {
            val days = LinkedHashMap<String, JsonElement>()
            for (day in Weekday.entries) {
                if (day in patch.weeklyAvailability) {
                    days[day.name.lowercase()] = patch.weeklyAvailability[day]?.let(::JsonPrimitive) ?: JsonNull
                }
            }
            body["weeklyAvailability"] = JsonObject(days)
        }
        patch.goals?.let { goals -> body["goals"] = JsonArray(goals.map(::JsonPrimitive)) }
        (patch.maxHeartRate as? Change.Set)?.let { body["maxHeartRate"] = it.value?.let(::JsonPrimitive) ?: JsonNull }
        (patch.thresholdHeartRate as? Change.Set)?.let {
            body["thresholdHeartRate"] = it.value?.let(::JsonPrimitive) ?: JsonNull
        }
        (patch.bikeWeightKg as? Change.Set)?.let { body["bikeWeightKg"] = it.value?.let(::JsonPrimitive) ?: JsonNull }
        (patch.powerProfileTable as? Change.Set)?.let {
            body["powerProfileTable"] = it.value?.let { table -> JsonPrimitive(table.name.lowercase()) } ?: JsonNull
        }
        val physics =
            listOfNotNull(
                patch.physics.systemMassKg?.let { "systemMassKg" to JsonPrimitive(it) },
                patch.physics.cdA?.let { "cdA" to JsonPrimitive(it) },
                patch.physics.crr?.let { "crr" to JsonPrimitive(it) },
                patch.physics.airDensity?.let { "airDensity" to JsonPrimitive(it) },
                patch.physics.drivetrainEfficiency?.let { "drivetrainEfficiency" to JsonPrimitive(it) },
            )
        if (physics.isNotEmpty()) body["physics"] = JsonObject(physics.toMap())
        return json.encodeToString(JsonObject.serializer(), JsonObject(body))
    }

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
            maxHeartRate = element["maxHeartRate"]?.let { Change.Set(nullableInt(it, "maxHeartRate")) } ?: Change.Keep,
            thresholdHeartRate =
                element["thresholdHeartRate"]?.let { Change.Set(nullableInt(it, "thresholdHeartRate")) } ?: Change.Keep,
            bikeWeightKg =
                element["bikeWeightKg"]?.let { Change.Set(nullableNumber(it, "bikeWeightKg")) } ?: Change.Keep,
            powerProfileTable = element["powerProfileTable"]?.let { Change.Set(tableOf(it)) } ?: Change.Keep,
        )
    }

    private fun tableOf(element: JsonElement): PowerProfileTable? {
        if (element is JsonNull) return null
        require(element is JsonPrimitive && element.isString) { "powerProfileTable must be men, women or null" }
        return PowerProfileTable.entries.firstOrNull { it.name.lowercase() == element.content }
            ?: throw IllegalArgumentException("powerProfileTable must be men, women or null")
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
