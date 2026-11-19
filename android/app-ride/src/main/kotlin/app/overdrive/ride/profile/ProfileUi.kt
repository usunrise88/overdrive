package app.overdrive.ride.profile

import androidx.compose.runtime.Immutable
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.profile.Change
import app.overdrive.core.profile.Profile
import app.overdrive.core.profile.ProfileProblem
import app.overdrive.core.profile.ProfileState
import app.overdrive.ride.plan.PlanUi
import app.overdrive.uikit.UiFormat

/** Where the value a Settings row shows comes from. */
enum class ValueSource(
    /** The small marker beside the value, or null when it is the tablet's own and needs none. */
    val marker: String?,
) {
    /** The tablet's own setting: no service, or the service has no value for it. */
    Tablet(null),

    /** The rider's profile on the service. */
    Service("From service"),

    /** Changed on the tablet and not yet taken by the service: it is in force here and will be sent. */
    NotSent("Not sent yet"),

    /** Worked out from the rider's weight and the bike's (spec §12.5, docs/adr/0034). */
    Weight("From your weight"),
}

/**
 * What the Settings screen says about the rider's profile (ADR 0021), made by [of] from the repository's [ProfileState]: for each of
 * the four values the profile holds and the tablet edits, where the number comes from; and, when the last request failed, one
 * plain line. Pure: the screen only draws it, and every word on it is tested here.
 *
 * @property note why the profile could not be read or sent, in plain words, or null; it replaces the FTP row's description.
 * @property massFromWeights the mass is the rider's weight plus the bike's (spec §12.5, docs/adr/0034): the mass row shows it as a value,
 * not a stepper, and [massLine] says where it comes from in one line.
 * @property massLine the one line under the mass row ("Rider 78.4 kg (7-day average) + bike 9 kg"), or null.
 * @property bikeWeightShown the bike weight row is drawn: only with a profile in force, because the bike's weight lives in the profile.
 * @property bikeNote what the bike weight row says about itself.
 */
@Immutable
data class ProfileUi(
    val ftp: ValueSource = ValueSource.Tablet,
    val mass: ValueSource = ValueSource.Tablet,
    val cdA: ValueSource = ValueSource.Tablet,
    val crr: ValueSource = ValueSource.Tablet,
    val note: String? = null,
    val massFromWeights: Boolean = false,
    val massLine: String? = null,
    val bikeWeightShown: Boolean = false,
    val bike: ValueSource = ValueSource.Tablet,
    val bikeNote: String = "",
) {
    companion object {
        /**
         * @param today the tablet's local date, `YYYY-MM-DD`, for how long ago the newest weighing was; null leaves the age out.
         */
        fun of(
            state: ProfileState,
            today: String? = null,
        ): ProfileUi {
            val profile = state.profile
            val pending = state.pending
            val fromWeights = profile?.systemMassKgFromWeights() != null

            fun physics(unsent: Boolean) =
                when {
                    profile == null -> ValueSource.Tablet
                    unsent -> ValueSource.NotSent
                    else -> ValueSource.Service
                }
            return ProfileUi(
                ftp =
                    when {
                        profile == null -> ValueSource.Tablet
                        pending?.ftp is Change.Set -> ValueSource.NotSent
                        profile.ftp != null -> ValueSource.Service
                        else -> ValueSource.Tablet
                    },
                mass =
                    when {
                        fromWeights -> ValueSource.Weight
                        else -> physics(pending?.physics?.systemMassKg != null)
                    },
                cdA = physics(pending?.physics?.cdA != null),
                crr = physics(pending?.physics?.crr != null),
                massFromWeights = fromWeights,
                massLine = profile?.let { massLine(it, today) },
                bikeWeightShown = profile != null,
                bike =
                    when {
                        profile == null -> ValueSource.Tablet
                        pending?.bikeWeightKg is Change.Set -> ValueSource.NotSent
                        profile.bikeWeightKg != null -> ValueSource.Service
                        else -> ValueSource.Tablet
                    },
                bikeNote = if (profile == null) "" else bikeNote(profile),
                note =
                    when (val problem = state.problem) {
                        null -> {
                            null
                        }

                        ProfileProblem.TokenRejected -> {
                            PlanUi.TOKEN_REJECTED
                        }

                        is ProfileProblem.Failed -> {
                            val effect =
                                when {
                                    profile == null -> "using this tablet's values"
                                    pending != null -> "your change is sent when the service answers"
                                    else -> "showing the last known profile"
                                }
                            "Profile: ${problem.reason} · $effect"
                        }
                    },
            )
        }
    }
}

// ---- Where the mass comes from (spec §12.5, docs/adr/0034) --------------------------------------------

/** How old the newest weighing may be before the line says so: a 7-day average still counts, but the rider should know it is not new. */
const val WEIGHT_STALE_AFTER_DAYS: Int = 3

/**
 * The one line under the mass row: the rider's weight and the bike's when they make the mass, a plain reason when the bike's weight is set
 * but the rider has not been weighed this week, and nothing otherwise. Weight is never on the overlay; this line is the only place the
 * app says a figure of it, and it is the rider's own settings screen.
 */
fun massLine(
    profile: Profile,
    today: String?,
): String? {
    val rider = profile.riderWeight
    val bike = profile.bikeWeightKg
    return when {
        rider != null && bike != null -> {
            val age = today?.let { daysAgo(rider.date, it) }
            val average =
                if (age != null &&
                    age >= WEIGHT_STALE_AFTER_DAYS
                ) {
                    "7-day average, last weighed $age days ago"
                } else {
                    "7-day average"
                }
            "Rider ${UiFormat.decimal(rider.kg, 1)} kg ($average) + bike ${trimmed(bike)} kg"
        }

        bike != null -> {
            "No weighing in the last 7 days, so the mass is the one set here"
        }

        else -> {
            null
        }
    }
}

/** What the bike weight row says: what setting it does, with the rider's weight when the service has one. */
fun bikeNote(profile: Profile): String {
    val rider = profile.riderWeight
    return when {
        profile.bikeWeightKg != null && rider != null -> "Added to your weight for the mass"
        profile.bikeWeightKg != null -> "Used once your weight is in Health Connect (7-day average)"
        rider != null -> "Set it to use your weight (${UiFormat.decimal(rider.kg, 1)} kg, 7-day average) for the mass"
        else -> "With your weight from Health Connect it makes the mass of the ride"
    }
}

/** Whole days from [date] to [today] (both `YYYY-MM-DD`), or null when either is not a date. Never negative. */
private fun daysAgo(
    date: String,
    today: String,
): Int? {
    val from = PlanDates.epochDay(date) ?: return null
    val to = PlanDates.epochDay(today) ?: return null
    return (to - from).coerceAtLeast(0).toInt()
}

/** "9" for 9.0 and "9.5" for 9.5: a weight written the way a person says it. */
private fun trimmed(kg: Double): String = if (kg % 1.0 == 0.0) kg.toInt().toString() else UiFormat.decimal(kg, 1)
