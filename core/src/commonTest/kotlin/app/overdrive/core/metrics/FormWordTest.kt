package app.overdrive.core.metrics

import kotlin.test.Test
import kotlin.test.assertEquals

class FormWordTest {
    @Test
    fun `the words follow the thresholds of the design to the tenth`() {
        assertEquals(FormWord.FRESH, FormWord.of(11.0))
        assertEquals(FormWord.FRESH, FormWord.of(5.1))
        assertEquals(FormWord.NEUTRAL, FormWord.of(5.0))
        assertEquals(FormWord.NEUTRAL, FormWord.of(0.0))
        assertEquals(FormWord.NEUTRAL, FormWord.of(-10.0))
        assertEquals(FormWord.TRAINING_HARD, FormWord.of(-10.1))
        assertEquals(FormWord.TRAINING_HARD, FormWord.of(-30.0))
        assertEquals(FormWord.VERY_FATIGUED, FormWord.of(-30.1))
        assertEquals("Training hard", FormWord.of(-20.0).label)
    }
}
