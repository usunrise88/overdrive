package app.overdrive.core.ride

import kotlin.math.cbrt

/**
 * Turns rider power into virtual speed (spec §4.3).
 *
 * The real implementation is `app.overdrive.core.physics.FlatPhysicsSpeedModel` (rolling resistance plus
 * aerodynamic drag with system mass, CdA and Crr as settings), which `RideEngine` uses. [PlaceholderSpeedModel]
 * stays as the default of `SimulatedRideSession`, whose users already depend on its numbers.
 */
public fun interface SpeedModel {
    /** Steady-state speed in km/h on the flat for a rider holding [powerWatts]. */
    public fun speedKmh(powerWatts: Double): Double
}

/**
 * **Placeholder, not physics.** Speed grows with the cube root of power, which is the shape of the
 * drag-dominated case: about 28 km/h at 150 W and 33 km/h at 250 W. It exists only so the simulated
 * ride shows plausible numbers until the real model lands in stage M2.
 */
public object PlaceholderSpeedModel : SpeedModel {
    private const val KMH_PER_CUBE_ROOT_WATT = 5.27

    override fun speedKmh(powerWatts: Double): Double {
        if (powerWatts <= 0.0) return 0.0
        return KMH_PER_CUBE_ROOT_WATT * cbrt(powerWatts)
    }
}
