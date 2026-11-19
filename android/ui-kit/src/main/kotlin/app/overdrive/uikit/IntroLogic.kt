package app.overdrive.uikit

/*
 * The decisions of the launch film (design/brand.md "The launch animation", ADR 0031), kept free of Android and Compose so that
 * they run in plain JVM tests: when the film plays at all, how its screen moves from one stage to the next, how it leaves, when
 * a film that does not start is given up, and where a film that was sent to the background carries on.
 */

/**
 * Whether the launch film plays: only on a cold start (the activity's `savedInstanceState` is null, so a rotation or a restored
 * task does not replay it) and only when the system's animator duration scale is not 0, the "remove animations" setting
 * (design/system.md §9), which means the rider asked for nothing that moves.
 */
fun shouldPlayIntro(
    coldStart: Boolean,
    animatorScale: Float,
): Boolean = coldStart && animatorScale > 0f

/** Where the film's screen is. */
enum class IntroStage {
    /** The film plays; nothing else is on the screen. */
    Playing,

    /** The ride app only: the **Continue** button is up and the rider has not pressed it. */
    Waiting,

    /** The film's layer fades out into the app. */
    Leaving,

    /** The layer is gone. */
    Finished,
}

/** What can happen to the film's screen. */
enum class IntroEvent {
    /** The player reached the end of the film. */
    FilmEnded,

    /** The player reported an error, or did not start in time. */
    FilmFailed,

    /** A tap anywhere on the film. */
    Tapped,

    /** The **Continue** button was pressed. */
    ContinuePressed,

    /** The fade out finished. */
    FadeEnded,
}

/**
 * The next stage. With [needsContinue] (the ride app) the end of the film, or a tap during it, brings up the button and waits
 * for it; without (the companion) it starts the fade out at once. A film that fails is removed at once from any stage that is
 * not already fading, and a stage that is over stays over.
 */
fun nextIntroStage(
    stage: IntroStage,
    event: IntroEvent,
    needsContinue: Boolean,
): IntroStage =
    when (stage) {
        IntroStage.Playing -> {
            when (event) {
                IntroEvent.FilmEnded, IntroEvent.Tapped -> if (needsContinue) IntroStage.Waiting else IntroStage.Leaving
                IntroEvent.FilmFailed -> IntroStage.Finished
                IntroEvent.ContinuePressed, IntroEvent.FadeEnded -> IntroStage.Playing
            }
        }

        IntroStage.Waiting -> {
            when (event) {
                IntroEvent.ContinuePressed -> IntroStage.Leaving
                IntroEvent.FilmFailed -> IntroStage.Finished
                IntroEvent.FilmEnded, IntroEvent.Tapped, IntroEvent.FadeEnded -> IntroStage.Waiting
            }
        }

        IntroStage.Leaving -> {
            if (event == IntroEvent.FadeEnded) IntroStage.Finished else IntroStage.Leaving
        }

        IntroStage.Finished -> {
            IntroStage.Finished
        }
    }

/**
 * How the film leaves. The player draws on its own surface, which Compose can fade only in ways that differ from device to
 * device, so the fade is drawn by Compose over it in two halves: the film's last frame dips into the film's own dark colour
 * (the cover, drawn above the player), then the player is gone and that colour fades away to show the app.
 * [progress] runs from 0 to 1 over the fade's duration.
 */
object IntroFade {
    private const val HALF = 0.5f

    /** The opacity of the dark cover over the player: 0 at the start, 1 at the half. */
    fun coverAlpha(progress: Float): Float = (progress / HALF).coerceIn(0f, 1f)

    /** The opacity of the dark layer behind the player and the cover: 1 until the half, then down to 0. */
    fun layerAlpha(progress: Float): Float = ((1f - progress) / HALF).coerceIn(0f, 1f)

    /** Whether the player is still part of the screen: it goes when the cover is opaque. */
    fun playerVisible(progress: Float): Boolean = progress < HALF
}

/** How long a film may take to render its first frame before it is given up, in milliseconds. */
const val INTRO_START_LIMIT_MS: Long = 1500

/**
 * Whether a film that has had [INTRO_START_LIMIT_MS] has failed to start: it has neither reported its first rendered frame nor
 * moved from position 0. Either sign of life is enough, because some players report one and not the other.
 */
fun introFailedToStart(
    renderStarted: Boolean,
    positionMs: Int,
): Boolean = !renderStarted && positionMs <= 0

/** Where the film carries on after the app came back from the background: seek to [seekToMs], then play if [play]. */
data class IntroResume(
    val seekToMs: Int,
    val play: Boolean,
)

/**
 * The plan for a film that was in the background (the activity was stopped, the player's surface went away and came back).
 * A film that had ended shows its closing frame again, a seek to the last frame, and does not play; one that had not
 * carries on from where it was.
 */
fun introResume(
    filmEnded: Boolean,
    positionMs: Int,
    durationMs: Int,
): IntroResume =
    if (filmEnded) {
        IntroResume(seekToMs = (durationMs - 1).coerceAtLeast(0), play = false)
    } else {
        IntroResume(seekToMs = positionMs.coerceAtLeast(0), play = true)
    }
