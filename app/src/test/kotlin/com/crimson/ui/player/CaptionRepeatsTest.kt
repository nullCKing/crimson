package com.crimson.ui.player

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionRepeatsTest {

    private fun texts(vararg cues: String) = withoutRepeats(cues.map { AnnotatedString(it) }).map { it.text }

    @Test
    fun `the same caption twice is shown once`() {
        assertEquals(listOf("Where were you?"), texts("Where were you?", "Where were you?"))
    }

    @Test
    fun `a line carried into the next cue is not shown on its own as well`() {
        assertEquals(listOf("Where were you?\nOut."), texts("Where were you?", "Where were you?\nOut."))
    }

    @Test
    fun `different captions all stay`() {
        assertEquals(listOf("[door slams]", "Who's there?"), texts("[door slams]", "Who's there?"))
    }
}
