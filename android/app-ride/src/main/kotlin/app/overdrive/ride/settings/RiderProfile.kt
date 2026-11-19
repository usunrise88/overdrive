package app.overdrive.ride.settings

import app.overdrive.core.profile.Change
import app.overdrive.core.profile.PhysicsPatch
import app.overdrive.core.profile.Profile
import app.overdrive.core.profile.ProfilePatch
import app.overdrive.core.profile.ProfileState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The settings a ride, the Ready screen and the Settings screen use: the tablet's own, with the rider's profile from the service on
 * top where it has an answer (ADR 0021).
 *
 * - **With no profile in force** (no address or token, or the service never reached and nothing cached) this is the tablet's own
 *   settings, untouched: the app works exactly as it did before there was a profile, offline included.
 * - **FTP** is the profile's when it has one; a profile whose FTP is null (the owner has not set it) leaves the tablet's own.
 * - **Mass, CdA, Crr, air density and drivetrain efficiency** are the profile's: it always has them, with the engine's defaults
 *   where nobody set them. **The mass is the exception that the rider asks for** (spec §12.5, docs/adr/0034): when the profile has both
 *   the rider's 7-day average weight and a bike weight, the mass is their sum, and `physics.systemMassKg` stands aside until the bike
 *   weight is cleared; with either missing it is `physics.systemMassKg` as before. The tablet's own mass never beats the profile's.
 *
 * Nothing is written to the tablet's stored settings: this value is derived every time, so taking the service away (clearing the
 * address) returns the rider to their own values. The result is **not** sanitized: a value the service holds off the tablet's
 * grid (an FTP of 177 W, a mass of 82.5 kg) is ridden as it is.
 */
fun AppSettings.withProfile(profile: Profile?): AppSettings =
    if (profile == null) {
        this
    } else {
        copy(
            ftp = profile.ftp ?: ftp,
            massKg = profile.systemMassKgFromWeights() ?: profile.physics.systemMassKg,
            bikeWeightKg = profile.bikeWeightKg,
            riderWeight = profile.riderWeight,
            cdA = profile.physics.cdA,
            crr = profile.physics.crr,
            airDensity = profile.physics.airDensity,
            drivetrainEfficiency = profile.physics.drivetrainEfficiency,
        )
    }

/**
 * The profile patch that makes [before] into [after], for the values the Settings screen can change and the profile holds: FTP,
 * mass, CdA, Crr and the bike's weight. Empty when none of them differ.
 */
fun riderPatch(
    before: AppSettings,
    after: AppSettings,
): ProfilePatch =
    ProfilePatch(
        ftp = if (after.ftp != before.ftp) Change.Set(after.ftp) else Change.Keep,
        physics =
            PhysicsPatch(
                systemMassKg = after.massKg.takeIf { it != before.massKg },
                cdA = after.cdA.takeIf { it != before.cdA },
                crr = after.crr.takeIf { it != before.crr },
            ),
        bikeWeightKg = if (after.bikeWeightKg != before.bikeWeightKg) Change.Set(after.bikeWeightKg) else Change.Keep,
    )

/**
 * How long the first ride waits for the profile to be known ([ProfileState.ready]: the cache read and the settings looked at). That
 * takes milliseconds, so this is only the bound that keeps **a ride from ever depending on the profile or its cache** (a disk that
 * does not answer, a flow that never says so): the tablet's own values apply until the profile is known, and a ride that has not
 * started is rebuilt when it is.
 */
const val PROFILE_WAIT_MILLIS: Long = 3_000

/**
 * What [profile] says is in force: the first value waits until the profile is known, but at most [waitMillis] (then it is null, the
 * tablet's own values), and every later change follows. Cold: each collector waits on its own.
 */
fun profileInForce(
    profile: StateFlow<ProfileState>,
    waitMillis: Long = PROFILE_WAIT_MILLIS,
): Flow<Profile?> =
    flow {
        val known = withTimeoutOrNull(waitMillis) { profile.first { it.ready } }
        emit(known?.profile)
        emitAll(profile.filter { it.ready }.map { it.profile })
    }.distinctUntilChanged()

/**
 * The tablet's settings with the profile in force on top ([withProfile]), as a flow that changes only when a value does. The first
 * value waits for the profile to be known, within [PROFILE_WAIT_MILLIS], so the ride built first uses the right FTP and not the
 * tablet's own for a moment (ADR 0021), and never waits for it longer than that (ADR 0029).
 */
fun effectiveSettingsOf(
    own: Flow<AppSettings>,
    profile: StateFlow<ProfileState>,
    waitMillis: Long = PROFILE_WAIT_MILLIS,
): Flow<AppSettings> =
    combine(own, profileInForce(profile, waitMillis)) { settings, inForce -> settings.withProfile(inForce) }
        .distinctUntilChanged()
