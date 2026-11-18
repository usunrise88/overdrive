package app.overdrive.companion.wellness

/**
 * One signal of the recovery card (design/companion.md §4.1), already in words: the service writes the figures and the usual
 * ranges, the phone only draws them. [key] names the metric, for the tap that opens Wellness at it.
 */
data class RecoveryFactData(
    val key: String,
    val name: String,
    val value: String?,
    val unit: String?,
    /** "usual 50–56", "above usual 50–56", "last night", "no reading". */
    val caption: String,
    val outside: Boolean,
)

/** How ready the rider is, in the service's own rating (design/companion.md §7.2). */
enum class RecoveryLevel { Ready, Caution, Rest }

/**
 * What the recovery card shows, made from `GET /v1/recovery` by `RecoveryWords` (ADR 0027): the phone words the service's rating
 * and rates nothing itself.
 */
sealed interface RecoveryState {
    /** Health Connect is not allowed: nothing is rated, and the card says where to allow it (design/companion.md §4.10). */
    data object AccessNeeded : RecoveryState

    /** Fewer nights than the baseline needs: no state, the figures of last night, and how far along it is. */
    data class Learning(
        val nights: Int,
        val needed: Int,
        val facts: List<RecoveryFactData>,
    ) : RecoveryState

    /** The service's rating of this morning (or [thisMorning] false: of yesterday's), its one or two sentences and its signals. */
    data class Rated(
        val level: RecoveryLevel,
        val reason: String,
        val facts: List<RecoveryFactData>,
        val thisMorning: Boolean = true,
    ) : RecoveryState

    /** No reading from last night: the watch was not worn, or its data has not arrived. */
    data object NoReading : RecoveryState
}
