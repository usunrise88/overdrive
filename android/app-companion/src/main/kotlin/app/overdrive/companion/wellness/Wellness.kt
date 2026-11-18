package app.overdrive.companion.wellness

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * What the recovery card shows. **The producer of everything but [NotAvailableYet] arrives with the watch data stage (P3-4b)**: this
 * is the seam, and the card, its words and its tests are already here, so that stage only fills it.
 */
sealed interface RecoveryState {
    /** The watch data is not connected: nothing is rated and nothing is being learned (stage P3-4a). */
    data object NotAvailableYet : RecoveryState

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

/**
 * The seam to the watch data (design/companion.md §11, spec §6.4): the recovery rating, and later the Wellness charts, the
 * Health Connect access and the background reading. In this stage there is only [UnavailableWellness].
 */
interface WellnessRepository {
    /** The rating for Today's recovery card. */
    val recovery: StateFlow<RecoveryState>
}

/** The watch data is not here yet: everything that reads it draws the design's learning and empty states. */
class UnavailableWellness : WellnessRepository {
    override val recovery: StateFlow<RecoveryState> =
        MutableStateFlow<RecoveryState>(
            RecoveryState.NotAvailableYet,
        ).asStateFlow()
}
