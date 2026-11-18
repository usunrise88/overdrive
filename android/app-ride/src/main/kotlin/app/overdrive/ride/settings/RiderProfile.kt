package app.overdrive.ride.settings

import app.overdrive.core.profile.Change
import app.overdrive.core.profile.PhysicsPatch
import app.overdrive.core.profile.Profile
import app.overdrive.core.profile.ProfilePatch

/**
 * The settings a ride, the Ready screen and the Settings screen use: the tablet's own, with the rider's profile from the service on
 * top where it has an answer (ADR 0021).
 *
 * - **With no profile in force** (no address or token, or the service never reached and nothing cached) this is the tablet's own
 *   settings, untouched: the app works exactly as it did before there was a profile, offline included.
 * - **FTP** is the profile's when it has one; a profile whose FTP is null (the owner has not set it) leaves the tablet's own.
 * - **Mass, CdA, Crr, air density and drivetrain efficiency** are the profile's: it always has them, with the engine's defaults
 *   where nobody set them.
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
            massKg = profile.physics.systemMassKg,
            cdA = profile.physics.cdA,
            crr = profile.physics.crr,
            airDensity = profile.physics.airDensity,
            drivetrainEfficiency = profile.physics.drivetrainEfficiency,
        )
    }

/**
 * The profile patch that makes [before] into [after], for the values the Settings screen can change and the profile holds: FTP,
 * mass, CdA and Crr. Empty when none of them differ.
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
    )
