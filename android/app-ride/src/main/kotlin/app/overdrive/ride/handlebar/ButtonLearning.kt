package app.overdrive.ride.handlebar

import androidx.compose.runtime.Immutable
import app.overdrive.core.click.ButtonChange
import app.overdrive.core.click.ClickBits
import app.overdrive.core.control.ButtonLearner
import app.overdrive.core.control.ButtonMapping
import app.overdrive.core.control.CoachButtonLearner
import app.overdrive.core.control.CoachLearnRejection
import app.overdrive.core.control.CoachLearnState
import app.overdrive.core.control.HandlebarButton
import app.overdrive.core.control.LearnRejection
import app.overdrive.core.control.LearnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the *Learn buttons* screen shows (spec §4.7: "Physical buttons are assigned once their identifiers are observed on
 * the device"): which of the five roles is being asked, what is settled so far, why the last press was refused, or that
 * every role has its button and the mapping is saved.
 */
@Immutable
data class LearnUi(
    /** The role being asked for, counting from 1 of [total]. After the last one it is the last role, with [done] set. */
    val role: HandlebarButton,
    val step: Int,
    val total: Int,
    /** The buttons settled so far, as the Click's own names (`Y`, `Plus`, …), by role. */
    val learned: Map<HandlebarButton, List<String>>,
    /** Why the last press was not taken, or null. The same role is asked again. */
    val rejection: LearnRejection? = null,
    /** For [LearnRejection.DUPLICATE], the role that already has that button. */
    val clashesWith: HandlebarButton? = null,
    /** Every role has its button and the mapping was saved. */
    val done: Boolean = false,
    /**
     * The flow is the **one extra button** for the live coach's mute (spec §12.1, docs/adr/0039) and not the five roles: [role] is then of no use (it is
     * the first role, a placeholder), [step] and [total] are 1, and [done] says the button was taken and saved.
     */
    val coach: Boolean = false,
)

/**
 * The guided flow that teaches the app which Click button is which role (ADR 0012): it asks for the five roles in turn and
 * decides on each press, with core's [ButtonLearner] fed by the Click's button changes, and **saves the mapping through
 * [save]** when the last one is settled. A combination, or a button that already has another role, is refused with a reason
 * and the same role is asked again. It can be cancelled at any moment (nothing is saved), and [resetToDefault] puts the
 * default back.
 *
 * - **Offered only when no ride is live** ([canLearn]), because the Click's gestures are suspended while [active]
 *   ([HandlebarService]): a press made to be learned must not shift a gear mid-ride. The container also ends the flow if a
 *   ride goes live meanwhile.
 * - **Collects before it asks**, so no press is missed; a press that was already down when learning began is not counted.
 * - It runs on [scope] and is safe from any thread. [active] is what the gesture layer reads to stay silent, and it stays
 *   true on the "all five learned" screen until the rider leaves it, so the last press cannot act either.
 *
 * @param buttons the Click's button changes (`PairedDevices.clickButtons`).
 * @param current the mapping in force, whose roles keep their buttons while others are learned.
 * @param ignoredBits bits that read as pressed all the time (`ClickOptions.ignoredBits`), never looked at.
 */
class ButtonLearning(
    private val scope: CoroutineScope,
    private val buttons: Flow<ButtonChange>,
    private val current: () -> ButtonMapping,
    private val save: suspend (ButtonMapping) -> Unit,
    private val canLearn: () -> Boolean = { true },
    private val ignoredBits: () -> Int = { 0 },
) {
    private val lock = Any()
    private val mutableUi = MutableStateFlow<LearnUi?>(null)
    private var job: Job? = null

    /** The flow's state, or null when the rider is not learning. */
    val ui: StateFlow<LearnUi?> = mutableUi.asStateFlow()

    /** Whether the rider is in the flow: nothing is carried out by the handlebar meanwhile. */
    val active: Boolean get() = mutableUi.value != null

    /** Starts asking. Does nothing when [canLearn] says a ride is live, or when already learning. */
    fun begin() {
        synchronized(lock) {
            if (mutableUi.value != null || !canLearn()) return
            val learner = ButtonLearner(ignoredBits = ignoredBits(), base = current())
            val first = learner.state as LearnState.Asking
            mutableUi.value = LearnUi(first.button, first.step, first.total, emptyMap())
            job =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    buttons.collect { change -> handle(learner.feed(change.held)) }
                }
        }
    }

    /**
     * Starts asking for **one** press: the button that mutes the live coach (spec §12.1, docs/adr/0039), which is on none of the five roles and unmapped by
     * default. A combination, or a button a role already has, is refused with a reason and the question is asked again; the five roles keep their buttons.
     * Saved when taken. Offered under the same conditions as [begin].
     */
    fun beginCoach() {
        synchronized(lock) {
            if (mutableUi.value != null || !canLearn()) return
            val learner = CoachButtonLearner(base = current(), ignoredBits = ignoredBits())
            mutableUi.value = coachUi()
            job =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    buttons.collect { change -> handleCoach(learner.feed(change.held)) }
                }
        }
    }

    /** Gives the live coach's mute no button again (the **Clear** button): the five roles are untouched. */
    fun clearCoachButton() {
        scope.launch { current().withCoachMute(0)?.let { save(it) } }
    }

    private fun coachUi(
        rejection: LearnRejection? = null,
        clashesWith: HandlebarButton? = null,
        done: Boolean = false,
        learned: Map<HandlebarButton, List<String>> = emptyMap(),
    ) = LearnUi(HandlebarButton.entries.first(), 1, 1, learned, rejection, clashesWith, done, coach = true)

    private suspend fun handleCoach(state: CoachLearnState) {
        if (mutableUi.value == null) return
        when (state) {
            CoachLearnState.Asking -> {
                mutableUi.value = coachUi()
            }

            is CoachLearnState.Rejected -> {
                val reason =
                    if (state.reason ==
                        CoachLearnRejection.COMBO
                    ) {
                        LearnRejection.COMBO
                    } else {
                        LearnRejection.DUPLICATE
                    }
                mutableUi.value = coachUi(rejection = reason, clashesWith = state.takenBy)
            }

            is CoachLearnState.Complete -> {
                save(state.mapping)
                synchronized(lock) {
                    if (mutableUi.value != null && job?.isActive == true) {
                        mutableUi.value =
                            coachUi(
                                done = true,
                                learned =
                                    mapOf(HandlebarButton.entries.first() to names(state.mapping.coachMuteMask)),
                            )
                        job?.cancel()
                    }
                }
            }
        }
    }

    /** Leaves the flow, saving nothing. After a finished flow it is the **Done** button. */
    fun cancel() {
        synchronized(lock) {
            job?.cancel()
            job = null
            mutableUi.value = null
        }
    }

    /** Puts [ButtonMapping.DEFAULT] back (the **Reset to default** button) and leaves the flow. */
    fun resetToDefault() {
        cancel()
        scope.launch { save(ButtonMapping.DEFAULT) }
    }

    private suspend fun handle(state: LearnState) {
        val previous = mutableUi.value ?: return
        when (state) {
            is LearnState.Asking -> {
                mutableUi.value =
                    LearnUi(state.button, state.step, state.total, state.learned.mapValues { names(it.value) })
            }

            is LearnState.Rejected -> {
                mutableUi.value = previous.copy(rejection = state.reason, clashesWith = state.clashesWith)
            }

            is LearnState.Complete -> {
                save(state.mapping)
                synchronized(lock) {
                    // Only if the rider did not leave while it was being saved.
                    if (mutableUi.value != null && job?.isActive == true) {
                        mutableUi.value =
                            previous.copy(
                                rejection = null,
                                clashesWith = null,
                                done = true,
                                step = previous.total,
                                learned = HandlebarButton.entries.associateWith { names(state.mapping.maskOf(it)) },
                            )
                        // Nothing more is listened to; the screen stays until Done.
                        job?.cancel()
                    }
                }
            }
        }
    }

    private fun names(mask: Int): List<String> = ClickBits.namesOf(mask)
}
