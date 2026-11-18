package app.overdrive.ride.profile

import androidx.compose.runtime.Immutable
import app.overdrive.core.profile.Change
import app.overdrive.core.profile.ProfileProblem
import app.overdrive.core.profile.ProfileState
import app.overdrive.ride.plan.PlanUi

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
}

/**
 * What the Settings screen says about the rider's profile (ADR 0021), made by [of] from the repository's [ProfileState]: for each of
 * the four values the profile holds and the tablet edits, where the number comes from; and, when the last request failed, one
 * plain line. Pure: the screen only draws it, and every word on it is tested here.
 *
 * @property note why the profile could not be read or sent, in plain words, or null; it replaces the FTP row's description.
 */
@Immutable
data class ProfileUi(
    val ftp: ValueSource = ValueSource.Tablet,
    val mass: ValueSource = ValueSource.Tablet,
    val cdA: ValueSource = ValueSource.Tablet,
    val crr: ValueSource = ValueSource.Tablet,
    val note: String? = null,
) {
    companion object {
        fun of(state: ProfileState): ProfileUi {
            val profile = state.profile
            val pending = state.pending

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
                mass = physics(pending?.physics?.systemMassKg != null),
                cdA = physics(pending?.physics?.cdA != null),
                crr = physics(pending?.physics?.crr != null),
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
