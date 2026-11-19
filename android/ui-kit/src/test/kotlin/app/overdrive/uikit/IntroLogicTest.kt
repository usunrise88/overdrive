package app.overdrive.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launch film's decisions (ADR 0031): when it plays, how its screen moves, how it leaves. */
class IntroLogicTest {
    @Test
    fun theFilmPlaysOnAColdStartWithAnimationsOn() {
        assertTrue(shouldPlayIntro(coldStart = true, animatorScale = 1f))
        assertTrue(shouldPlayIntro(coldStart = true, animatorScale = 0.5f))
        assertTrue(shouldPlayIntro(coldStart = true, animatorScale = 10f))
    }

    @Test
    fun theFilmIsSkippedWhenTheRiderTurnedAnimationsOff() {
        assertFalse(shouldPlayIntro(coldStart = true, animatorScale = 0f))
    }

    @Test
    fun theFilmIsSkippedWhenTheActivityIsRestoredOrRotated() {
        assertFalse(shouldPlayIntro(coldStart = false, animatorScale = 1f))
        assertFalse(shouldPlayIntro(coldStart = false, animatorScale = 0f))
    }

    @Test
    fun rideAppTheEndOfTheFilmBringsUpTheButtonAndWaitsForIt() {
        var stage = IntroStage.Playing
        stage = nextIntroStage(stage, IntroEvent.FilmEnded, needsContinue = true)
        assertEquals(IntroStage.Waiting, stage)
        // Nothing but the button moves it on: not the film's end again, not a tap, not a fade that never started.
        for (event in listOf(IntroEvent.FilmEnded, IntroEvent.Tapped, IntroEvent.FadeEnded)) {
            assertEquals(IntroStage.Waiting, nextIntroStage(stage, event, needsContinue = true))
        }
        stage = nextIntroStage(stage, IntroEvent.ContinuePressed, needsContinue = true)
        assertEquals(IntroStage.Leaving, stage)
        stage = nextIntroStage(stage, IntroEvent.FadeEnded, needsContinue = true)
        assertEquals(IntroStage.Finished, stage)
    }

    @Test
    fun rideAppATapDuringTheFilmBringsUpTheButtonAtOnce() {
        val stage = nextIntroStage(IntroStage.Playing, IntroEvent.Tapped, needsContinue = true)
        assertEquals(IntroStage.Waiting, stage)
        // The film is still playing behind the button, and its end changes nothing.
        assertEquals(IntroStage.Waiting, nextIntroStage(stage, IntroEvent.FilmEnded, needsContinue = true))
    }

    @Test
    fun companionTheEndOfTheFilmOrATapStartsTheFadeAtOnce() {
        assertEquals(
            IntroStage.Leaving,
            nextIntroStage(IntroStage.Playing, IntroEvent.FilmEnded, needsContinue = false),
        )
        assertEquals(IntroStage.Leaving, nextIntroStage(IntroStage.Playing, IntroEvent.Tapped, needsContinue = false))
        assertEquals(
            IntroStage.Finished,
            nextIntroStage(IntroStage.Leaving, IntroEvent.FadeEnded, needsContinue = false),
        )
    }

    @Test
    fun withoutAButtonTheButtonCannotMoveTheScreen() {
        assertEquals(
            IntroStage.Playing,
            nextIntroStage(IntroStage.Playing, IntroEvent.ContinuePressed, needsContinue = false),
        )
    }

    @Test
    fun aFailedFilmIsRemovedAtOnceFromPlayingAndFromWaiting() {
        for (needsContinue in listOf(true, false)) {
            assertEquals(IntroStage.Finished, nextIntroStage(IntroStage.Playing, IntroEvent.FilmFailed, needsContinue))
        }
        assertEquals(
            IntroStage.Finished,
            nextIntroStage(IntroStage.Waiting, IntroEvent.FilmFailed, needsContinue = true),
        )
    }

    @Test
    fun aFadeInProgressIsNotCutShortByTheFilmOrATap() {
        for (event in listOf(
            IntroEvent.FilmEnded,
            IntroEvent.FilmFailed,
            IntroEvent.Tapped,
            IntroEvent.ContinuePressed,
        )) {
            assertEquals(IntroStage.Leaving, nextIntroStage(IntroStage.Leaving, event, needsContinue = true))
            assertEquals(IntroStage.Leaving, nextIntroStage(IntroStage.Leaving, event, needsContinue = false))
        }
    }

    @Test
    fun aFinishedScreenStaysFinished() {
        for (event in IntroEvent.entries) {
            assertEquals(IntroStage.Finished, nextIntroStage(IntroStage.Finished, event, needsContinue = true))
            assertEquals(IntroStage.Finished, nextIntroStage(IntroStage.Finished, event, needsContinue = false))
        }
    }

    @Test
    fun everyStageAndEventHasAnAnswerAndNothingGoesBackwards() {
        // Order of the stages is the order of the screen's life: a stage never answers with an earlier one.
        for (needsContinue in listOf(true, false)) {
            for (stage in IntroStage.entries) {
                for (event in IntroEvent.entries) {
                    assertTrue(nextIntroStage(stage, event, needsContinue).ordinal >= stage.ordinal)
                }
            }
        }
    }

    @Test
    fun theFadeDipsToTheFilmsDarkColourThenRevealsTheApp() {
        // Start: the film is untouched and the dark layer is opaque behind it.
        assertEquals(0f, IntroFade.coverAlpha(0f), 0f)
        assertEquals(1f, IntroFade.layerAlpha(0f), 0f)
        assertTrue(IntroFade.playerVisible(0f))
        // Half: the cover is opaque, so the player can go without a visible change; the layer is still opaque.
        assertEquals(1f, IntroFade.coverAlpha(0.5f), 0f)
        assertEquals(1f, IntroFade.layerAlpha(0.5f), 0f)
        assertFalse(IntroFade.playerVisible(0.5f))
        // End: the layer is gone, the app shows.
        assertEquals(1f, IntroFade.coverAlpha(1f), 0f)
        assertEquals(0f, IntroFade.layerAlpha(1f), 0f)
        assertFalse(IntroFade.playerVisible(1f))
    }

    @Test
    fun theFadeIsMonotoneAndStaysInsideZeroAndOne() {
        var cover = -1f
        var layer = 2f
        for (i in 0..100) {
            val progress = i / 100f
            assertTrue(IntroFade.coverAlpha(progress) >= cover)
            assertTrue(IntroFade.layerAlpha(progress) <= layer)
            cover = IntroFade.coverAlpha(progress)
            layer = IntroFade.layerAlpha(progress)
            assertTrue(cover in 0f..1f)
            assertTrue(layer in 0f..1f)
        }
        // Out-of-range input (an animation that overshoots) is clamped.
        assertEquals(0f, IntroFade.coverAlpha(-0.2f), 0f)
        assertEquals(0f, IntroFade.layerAlpha(1.2f), 0f)
    }

    @Test
    fun theCoverIsOpaqueWheneverThePlayerIsGone() {
        for (i in 0..100) {
            val progress = i / 100f
            if (!IntroFade.playerVisible(progress)) assertEquals(1f, IntroFade.coverAlpha(progress), 0f)
        }
    }

    @Test
    fun aFilmThatNeitherRenderedNorMovedIsGivenUp() {
        assertEquals(1500L, INTRO_START_LIMIT_MS)
        assertTrue(introFailedToStart(renderStarted = false, positionMs = 0))
        assertTrue(introFailedToStart(renderStarted = false, positionMs = -1))
    }

    @Test
    fun anySignOfLifeKeepsTheFilm() {
        assertFalse(introFailedToStart(renderStarted = true, positionMs = 0))
        assertFalse(introFailedToStart(renderStarted = false, positionMs = 40))
        assertFalse(introFailedToStart(renderStarted = true, positionMs = 1200))
    }

    @Test
    fun aFilmThatWasPlayingCarriesOnFromWhereItWas() {
        assertEquals(
            IntroResume(seekToMs = 2300, play = true),
            introResume(filmEnded = false, positionMs = 2300, durationMs = 4933),
        )
        // Not started yet, or the position is unknown: from the start, and it plays.
        assertEquals(
            IntroResume(seekToMs = 0, play = true),
            introResume(filmEnded = false, positionMs = 0, durationMs = -1),
        )
        assertEquals(
            IntroResume(seekToMs = 0, play = true),
            introResume(filmEnded = false, positionMs = -1, durationMs = -1),
        )
    }

    @Test
    fun aFilmThatHadEndedShowsItsClosingFrameAndStaysStill() {
        assertEquals(
            IntroResume(seekToMs = 4932, play = false),
            introResume(filmEnded = true, positionMs = 4933, durationMs = 4933),
        )
        assertEquals(
            IntroResume(seekToMs = 0, play = false),
            introResume(filmEnded = true, positionMs = 0, durationMs = -1),
        )
    }
}
