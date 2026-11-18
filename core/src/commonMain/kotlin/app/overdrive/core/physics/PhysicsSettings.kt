package app.overdrive.core.physics

/**
 * The constants of the virtual-speed model (spec §4.3): system mass, CdA and Crr are settings.
 *
 * The defaults describe an average rider on a road bike with hands on the hoods, the same ballpark as
 * Zwift's defaults, so virtual speeds are comparable with what other apps show.
 */
public data class PhysicsSettings(
    /** Rider, bike and kit together, in kilograms. */
    val systemMassKg: Double = DEFAULT_SYSTEM_MASS_KG,
    /** Drag area in square metres: drag coefficient times frontal area. */
    val cdA: Double = DEFAULT_CDA,
    /** Rolling resistance coefficient (dimensionless). */
    val crr: Double = DEFAULT_CRR,
    /** Air density in kg/m³. */
    val airDensity: Double = STANDARD_AIR_DENSITY,
    /**
     * Share of the measured power that reaches the wheel, in `(0, 1]`. The trainer measures power at the
     * pedals or the flywheel, so a real drivetrain would lose 2 to 3% on the way. The default 1.0 ignores
     * that, which is what other virtual-ride apps do; set about 0.975 for a physically honest figure.
     */
    val drivetrainEfficiency: Double = 1.0,
) {
    init {
        require(systemMassKg > 0.0 && systemMassKg.isFinite()) { "System mass must be positive, got $systemMassKg" }
        require(cdA > 0.0 && cdA.isFinite()) { "CdA must be positive, got $cdA" }
        require(crr >= 0.0 && crr.isFinite()) { "Crr must not be negative, got $crr" }
        require(airDensity > 0.0 && airDensity.isFinite()) { "Air density must be positive, got $airDensity" }
        require(drivetrainEfficiency > 0.0 && drivetrainEfficiency <= 1.0) {
            "Drivetrain efficiency must be in (0, 1], got $drivetrainEfficiency"
        }
    }

    /** Rolling resistance force on the flat in newtons: `Crr · m · g`. */
    public val rollingResistanceNewtons: Double get() = crr * systemMassKg * GRAVITY

    /**
     * Aerodynamic drag per squared speed in kg/m: `½ · ρ · CdA`. This is the value FTMS calls the wind
     * resistance coefficient, so it is what the trainer is told in `SimulationParameters`.
     */
    public val windResistanceCoefficient: Double get() = 0.5 * airDensity * cdA

    public companion object {
        public const val DEFAULT_SYSTEM_MASS_KG: Double = 80.0
        public const val DEFAULT_CDA: Double = 0.35
        public const val DEFAULT_CRR: Double = 0.004
        public const val STANDARD_AIR_DENSITY: Double = 1.225

        /** Standard gravity in m/s². */
        public const val GRAVITY: Double = 9.80665
    }
}
