package com.crimson.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenizerTest {

    private fun t(s: String) = Tokenizer.tokenize(s).list

    @Test
    fun `separators of every shape split tokens`() {
        assertEquals(listOf("US", "ESPN"), t("US|ESPN"))
        assertEquals(listOf("US", "ESPN"), t("US| ESPN"))
        assertEquals(listOf("US", "ESPN"), t("|US|ESPN"))
        assertEquals(listOf("US", "ESPN"), t("US: ESPN"))
        assertEquals(listOf("US", "ESPN"), t("US - ESPN"))
        assertEquals(listOf("US", "ESPN"), t("[US] ESPN"))
        assertEquals(listOf("US", "ESPN"), t("US ▎ESPN"))
        assertEquals(listOf("US", "ESPN"), t("(US)  ESPN"))
        assertEquals(listOf("US", "ESPN"), t("US•ESPN"))
        assertEquals(listOf("US", "ESPN"), t("US.ESPN"))
    }

    @Test
    fun `initialisms collapse but dotted separators split`() {
        assertEquals(listOf("LA"), t("L.A."))
        assertEquals(listOf("USA"), t("U.S.A."))
        assertEquals(listOf("ST", "PETERSBURG"), t("ST. PETERSBURG"))
        assertEquals(listOf("US", "ESPN"), t("US.ESPN"))
    }

    @Test
    fun `case and unicode letters fold to ascii`() {
        assertEquals(listOf("ESPN", "HD"), t("espn ᴴᴰ"))
        assertEquals(listOf("ESPN"), t("ＥＳＰＮ"))
    }

    @Test
    fun `whole tokens only`() {
        val latino = Tokenizer.tokenize("LATINO MUSIC")
        assertFalse(latino.has("LA"))
        assertTrue(latino.has("LATINO"))

        val sony = Tokenizer.tokenize("SONY MOVIES")
        assertFalse(sony.has("NY"))
        assertTrue(sony.has("SONY"))
    }

    @Test
    fun `phrases match consecutive tokens only`() {
        val ny = Tokenizer.tokenize("US| WABC NEW YORK 7")
        assertTrue(ny.hasPhrase(phrase("NEW YORK")))
        assertFalse(ny.hasPhrase(phrase("YORK NEW")))

        val scattered = Tokenizer.tokenize("NEW CHANNEL YORK TIMES")
        assertFalse("tokens must be adjacent", scattered.hasPhrase(phrase("NEW YORK")))
    }

    @Test
    fun `hard boundaries mark punctuation-closed tokens`() {
        val piped = Tokenizer.tokenize("DE | SPORT")
        assertEquals(listOf("DE", "SPORT"), piped.list)
        assertTrue("DE is closed by a pipe", piped.isHardBoundary(0))
        assertFalse("SPORT ends the string", piped.isHardBoundary(1))

        val sentence = Tokenizer.tokenize("IN THE MIX")
        assertFalse("IN is closed by a space", sentence.isHardBoundary(0))

        assertTrue(Tokenizer.tokenize("US|ESPN").isHardBoundary(0))
        assertTrue(Tokenizer.tokenize("[IT] RAI").isHardBoundary(0))
        assertTrue(Tokenizer.tokenize("FR:  TF1").isHardBoundary(0))
    }

    @Test
    fun `prefix window limits where a match counts`() {
        val early = Tokenizer.tokenize("US VIP ESPN")
        assertTrue(early.hasPhraseInPrefix(phrase("US")))

        val late = Tokenizer.tokenize("THE BEST OF THE US SHOWS")
        assertFalse(late.hasPhraseInPrefix(phrase("US")))
    }

    @Test
    fun `empty and decoration-only input is handled`() {
        assertEquals(emptyList<String>(), t(""))
        assertEquals(emptyList<String>(), t("||| --- "))
        assertEquals(emptyList<String>(), Tokenizer.tokenize(null).list)
    }
}
