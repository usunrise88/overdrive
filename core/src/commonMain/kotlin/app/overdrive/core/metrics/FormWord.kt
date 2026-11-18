package app.overdrive.core.metrics

/**
 * The word for today's form, fitness minus fatigue (design/companion.md §7.3): one function, so the phone's Fitness screen and
 * the assistant's `get_fitness` say the same thing about the same number.
 */
public enum class FormWord(
    public val label: String,
) {
    /** Above +5. */
    FRESH("Fresh"),

    /** −10 to +5. */
    NEUTRAL("Neutral"),

    /** −30 to below −10. */
    TRAINING_HARD("Training hard"),

    /** Below −30. */
    VERY_FATIGUED("Very fatigued"),
    ;

    public companion object {
        private const val FRESH_ABOVE = 5.0
        private const val NEUTRAL_FROM = -10.0
        private const val TRAINING_HARD_FROM = -30.0

        /** The word for a form of [tsb]. */
        public fun of(tsb: Double): FormWord =
            when {
                tsb > FRESH_ABOVE -> FRESH
                tsb >= NEUTRAL_FROM -> NEUTRAL
                tsb >= TRAINING_HARD_FROM -> TRAINING_HARD
                else -> VERY_FATIGUED
            }
    }
}
