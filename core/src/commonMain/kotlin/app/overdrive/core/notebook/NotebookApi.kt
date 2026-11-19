package app.overdrive.core.notebook

import app.overdrive.core.wellness.WellnessTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The coach notebook of the sync API (spec §11.6, ADR 0035): `GET /v1/notebook`, the default coach scope only. The companion reads it and
 * never writes it. The model is as strict as `contracts/schema/notebook-response.schema.json` and checks the rules the schema cannot state
 * (its description lists them), so an unknown key, a missing one, a value out of range or an answer that breaks a rule is an
 * [IllegalArgumentException] when it is read. Messages name a field and the rule, never the text of a section: the notebook is private.
 */

/** The seven fixed sections, in the order they are served and shown, with the title each is shown under (the contract's own). */
@Serializable
public enum class NotebookSection(
    public val title: String,
) {
    @SerialName("profile")
    PROFILE("Rider profile and constraints"),

    @SerialName("what_works")
    WHAT_WORKS("What works"),

    @SerialName("what_does_not")
    WHAT_DOES_NOT("What does not work"),

    @SerialName("injuries")
    INJURIES("Injuries and niggles"),

    @SerialName("methodology")
    METHODOLOGY("Methodology"),

    @SerialName("open_questions")
    OPEN_QUESTIONS("Open questions"),

    @SerialName("log")
    LOG("Decisions log"),
}

/** What the checks use; the limits are those of the schema. */
internal object NotebookChecks {
    const val MAX_TEXT = 100_000
    const val MAX_REASON = 500
}

/** The server's caps, in characters (UTF-16 code units): for the whole document and for the log. */
@Serializable
public data class NotebookLimits(
    val maxChars: Int,
    val logMaxChars: Int,
) {
    init {
        require(maxChars >= 1) { "maxChars is below 1" }
        require(logMaxChars >= 1) { "logMaxChars is below 1" }
    }
}

/**
 * One section as served. A section never written is version 0 with empty text, no reason and no [updatedAt]; a written one (even one
 * that was blanked) has an [updatedAt]. [reason] is the reason given for the current text, or null.
 */
@Serializable
public data class NotebookSectionView(
    val key: NotebookSection,
    val title: String,
    val text: String,
    val version: Int,
    val updatedAt: String?,
    val reason: String?,
) {
    init {
        require(title == key.title) { "title is not the one the contract gives this section" }
        require(text.length <= NotebookChecks.MAX_TEXT) { "text is longer than 100000 characters" }
        require(version >= 0) { "version is negative" }
        require(reason == null || reason.length in 1..NotebookChecks.MAX_REASON) {
            "reason must have 1 to 500 characters"
        }
        if (version == 0) {
            require(text.isEmpty() && reason == null && updatedAt == null) {
                "version 0 (never written) has empty text, no reason and no updatedAt"
            }
        } else {
            require(updatedAt != null) { "updatedAt is needed for a version above 0" }
        }
        if (updatedAt != null) require(WellnessTime.isUtcInstant(updatedAt)) { "updatedAt is not a real UTC instant" }
    }
}

/** The answer to `GET /v1/notebook`: all seven sections in order, and the sum of their texts. */
@Serializable
public data class NotebookResponse(
    val scope: String,
    val generatedAt: String,
    val limits: NotebookLimits,
    val totalChars: Int,
    val sections: List<NotebookSectionView>,
) {
    init {
        require(scope == "coach") { "scope is not coach" }
        require(WellnessTime.isUtcInstant(generatedAt)) { "generatedAt is not a real UTC instant" }
        require(sections.size == NotebookSection.entries.size) { "sections must have the seven sections" }
        for ((index, section) in sections.withIndex()) {
            require(section.key == NotebookSection.entries[index]) {
                "sections/$index is not the section the contract puts there"
            }
        }
        require(totalChars >= 0) { "totalChars is negative" }
        require(totalChars == sections.sumOf { it.text.length }) { "totalChars is not the sum of the section texts" }
    }

    /** True when no section has ever been written: the notebook is empty. */
    public val isEmpty: Boolean get() = sections.all { it.version == 0 }
}
