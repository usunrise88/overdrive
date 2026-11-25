package app.overdrive.core.control

import app.overdrive.core.click.ClickBits
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The pure core of the *Learn buttons* screen (spec §4.7: "assigned once their identifiers are observed on the device"). */
class ButtonLearnerTest {
    private fun ButtonLearner.press(mask: Int): LearnState {
        feed(mask)
        return feed(0)
    }

    private fun asking(state: LearnState) = assertIs<LearnState.Asking>(state)

    @Test
    fun `one press and release per role in order builds the mapping`() {
        val learner = ButtonLearner()
        val first = asking(learner.state)
        assertEquals(HandlebarButton.PLUS, first.button)
        assertEquals(1, first.step)
        assertEquals(5, first.total)

        // The rider presses A, B, Y, Z and Plus in turn: a mapping nothing like the default.
        assertEquals(HandlebarButton.BUTTON_2, asking(learner.press(ClickBits.A)).button)
        assertEquals(HandlebarButton.BUTTON_3, asking(learner.press(ClickBits.B)).button)
        assertEquals(HandlebarButton.BUTTON_4, asking(learner.press(ClickBits.Y)).button)
        val last = asking(learner.press(ClickBits.Z))
        assertEquals(HandlebarButton.BUTTON_5, last.button)
        assertEquals(5, last.step)
        assertEquals(
            mapOf(HandlebarButton.PLUS to ClickBits.A, HandlebarButton.BUTTON_2 to ClickBits.B),
            last.learned.filterKeys {
                it.ordinal <
                    2
            },
        )

        val done = assertIs<LearnState.Complete>(learner.press(ClickBits.PLUS))
        assertEquals(ClickBits.A, done.mapping.maskOf(HandlebarButton.PLUS))
        assertEquals(ClickBits.B, done.mapping.maskOf(HandlebarButton.BUTTON_2))
        assertEquals(ClickBits.Y, done.mapping.maskOf(HandlebarButton.BUTTON_3))
        assertEquals(ClickBits.Z, done.mapping.maskOf(HandlebarButton.BUTTON_4))
        assertEquals(ClickBits.PLUS, done.mapping.maskOf(HandlebarButton.BUTTON_5))
    }

    @Test
    fun `a press counts when it is released not when it goes down`() {
        val learner = ButtonLearner()
        assertEquals(
            HandlebarButton.PLUS,
            asking(learner.feed(ClickBits.A)).button,
            "still down: still asking for Plus",
        )
        assertEquals(HandlebarButton.PLUS, asking(learner.feed(ClickBits.A)).button, "a repeated frame changes nothing")
        assertEquals(HandlebarButton.BUTTON_2, asking(learner.feed(0)).button)
    }

    @Test
    fun `a button that is another role's is a duplicate naming it and the same role is asked again`() {
        val learner = ButtonLearner()
        learner.press(ClickBits.A)
        val rejected = assertIs<LearnState.Rejected>(learner.press(ClickBits.A))
        assertEquals(HandlebarButton.BUTTON_2, rejected.button)
        assertEquals(LearnRejection.DUPLICATE, rejected.reason)
        assertEquals(HandlebarButton.PLUS, rejected.clashesWith)
        // The next press starts a new try and clears the refusal, then B is accepted.
        assertEquals(HandlebarButton.BUTTON_2, asking(learner.feed(ClickBits.B)).button)
        assertEquals(HandlebarButton.BUTTON_3, asking(learner.feed(0)).button)
    }

    @Test
    fun `two buttons together are a combo and are refused`() {
        val learner = ButtonLearner()
        val rejected = assertIs<LearnState.Rejected>(learner.press(ClickBits.A or ClickBits.B))
        assertEquals(LearnRejection.COMBO, rejected.reason)
        assertEquals(HandlebarButton.PLUS, rejected.button)
        assertEquals(null, rejected.clashesWith)
    }

    @Test
    fun `a second button joining a press is a combo whichever is released first`() {
        for (releaseOrder in listOf(listOf(ClickBits.A, ClickBits.B), listOf(ClickBits.B, ClickBits.A))) {
            val learner = ButtonLearner()
            learner.feed(ClickBits.A)
            learner.feed(ClickBits.A or ClickBits.B)
            learner.feed(ClickBits.A or ClickBits.B xor releaseOrder[0])
            val state = learner.feed(0)
            assertEquals(LearnRejection.COMBO, assertIs<LearnState.Rejected>(state).reason, "$releaseOrder")
        }
    }

    @Test
    fun `a button released a moment before another one pressed is two presses not a combo`() {
        val learner = ButtonLearner()
        assertEquals(HandlebarButton.BUTTON_2, asking(learner.press(ClickBits.A)).button)
        assertEquals(HandlebarButton.BUTTON_3, asking(learner.press(ClickBits.B)).button)
    }

    @Test
    fun `a button that sets several bits at once can be allowed a bit that joins later is still a combo`() {
        val strict = ButtonLearner()
        assertEquals(
            LearnRejection.COMBO,
            assertIs<LearnState.Rejected>(
                strict.press(ClickBits.PLUS or ClickBits.PLUS_SHIFT_DOWN),
            ).reason,
        )

        val lenient = ButtonLearner(allowMultiBitButtons = true)
        val next = asking(lenient.press(ClickBits.PLUS or ClickBits.PLUS_SHIFT_DOWN))
        assertEquals(HandlebarButton.BUTTON_2, next.button)
        assertEquals(ClickBits.PLUS or ClickBits.PLUS_SHIFT_DOWN, next.learned.getValue(HandlebarButton.PLUS))

        val joining = ButtonLearner(allowMultiBitButtons = true)
        joining.feed(ClickBits.A)
        joining.feed(ClickBits.A or ClickBits.B)
        assertEquals(LearnRejection.COMBO, assertIs<LearnState.Rejected>(joining.feed(0)).reason)
    }

    @Test
    fun `ignored bits are never looked at so a stuck bit cannot block the learning`() {
        val learner = ButtonLearner(ignoredBits = ClickBits.PLUS_SHIFT_DOWN)
        learner.feed(ClickBits.PLUS_SHIFT_DOWN) // reads as pressed all the time
        assertEquals(HandlebarButton.PLUS, asking(learner.feed(ClickBits.PLUS_SHIFT_DOWN)).button, "no press yet")
        learner.feed(ClickBits.PLUS_SHIFT_DOWN or ClickBits.A) // A goes down with the stuck bit still down
        val state = learner.feed(ClickBits.PLUS_SHIFT_DOWN) // and comes up again
        assertEquals(HandlebarButton.BUTTON_2, asking(state).button)
        assertEquals(ClickBits.A, asking(state).learned.getValue(HandlebarButton.PLUS), "A, not A plus the stuck bit")
    }

    @Test
    fun `a dropped link a feed of nothing abandons the press`() {
        val learner = ButtonLearner()
        learner.feed(ClickBits.A)
        assertEquals(
            HandlebarButton.BUTTON_2,
            asking(learner.feed(0)).button,
            "a release that never came is taken as the release",
        )
    }

    @Test
    fun `learning one role again keeps the others and refuses their buttons`() {
        val learner = ButtonLearner(roles = listOf(HandlebarButton.BUTTON_3), base = ButtonMapping.DEFAULT)
        assertEquals(HandlebarButton.BUTTON_3, asking(learner.state).button)
        assertEquals(1, asking(learner.state).total)
        val clash = assertIs<LearnState.Rejected>(learner.press(ClickBits.PLUS))
        assertEquals(HandlebarButton.PLUS, clash.clashesWith)
        val done = assertIs<LearnState.Complete>(learner.press(ClickBits.MINUS))
        assertEquals(ClickBits.MINUS, done.mapping.maskOf(HandlebarButton.BUTTON_3))
        assertEquals(ClickBits.PLUS, done.mapping.maskOf(HandlebarButton.PLUS))
        assertEquals(ButtonMapping.DEFAULT.with(HandlebarButton.BUTTON_3, ClickBits.MINUS), done.mapping)
        // The old button of the re-learned role is free to be learned: it is not counted against the new one.
        val again = ButtonLearner(roles = listOf(HandlebarButton.BUTTON_3))
        assertIs<LearnState.Complete>(again.press(ClickBits.Y))
    }

    @Test
    fun `complete is final until reset`() {
        val learner = ButtonLearner(roles = listOf(HandlebarButton.PLUS))
        val done = learner.press(ClickBits.MINUS)
        assertIs<LearnState.Complete>(done)
        assertEquals(done, learner.press(ClickBits.A), "more presses change nothing")
        learner.reset()
        assertEquals(HandlebarButton.PLUS, asking(learner.state).button)
    }

    @Test
    fun `whatever is fed the learner never throws and a finished mapping is always valid`() {
        val random = Random(31)
        repeat(500) {
            val learner = ButtonLearner(allowMultiBitButtons = random.nextBoolean())
            repeat(300) {
                learner.feed(
                    if (random.nextInt(3) ==
                        0
                    ) {
                        0
                    } else {
                        1 shl random.nextInt(8) or (if (random.nextInt(4) == 0) 1 shl random.nextInt(8) else 0)
                    },
                )
            }
            val state = learner.state
            if (state is LearnState.Complete) {
                assertEquals(5, HandlebarButton.entries.map { state.mapping.maskOf(it) }.toSet().size)
            }
        }
    }
}
