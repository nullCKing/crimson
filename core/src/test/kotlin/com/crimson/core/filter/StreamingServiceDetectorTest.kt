package com.crimson.core.filter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingServiceDetectorTest {

    @Test
    fun `identifies Netflix streaming channels and categories`() {
        assertTrue(StreamingServiceDetector.isStreamingService("NETFLIX"))
        assertTrue(StreamingServiceDetector.isStreamingService("US | NETFLIX"))
        assertTrue(StreamingServiceDetector.isStreamingService("NETFLIX 24/7"))
        assertTrue(StreamingServiceDetector.isStreamingService("Netflix: Stranger Things"))
        assertTrue(StreamingServiceDetector.isStreamingService("[US] NETFLIX HD"))
    }

    @Test
    fun `identifies Disney Plus but preserves Disney cable channels`() {
        // Streaming matches
        assertTrue(StreamingServiceDetector.isStreamingService("DISNEY+"))
        assertTrue(StreamingServiceDetector.isStreamingService("US | DISNEY+"))
        assertTrue(StreamingServiceDetector.isStreamingService("DISNEY+ HD"))
        assertTrue(StreamingServiceDetector.isStreamingService("DISNEY+HD"))
        assertTrue(StreamingServiceDetector.isStreamingService("DISNEY PLUS"))
        assertTrue(StreamingServiceDetector.isStreamingService("US | DISNEY PLUS"))
        assertTrue(StreamingServiceDetector.isStreamingService("Disney+: The Mandalorian"))
        assertTrue(StreamingServiceDetector.isStreamingService("DISNEYPLUS"))

        // Real cable channels MUST NOT MATCH
        assertFalse(StreamingServiceDetector.isStreamingService("Disney Channel"))
        assertFalse(StreamingServiceDetector.isStreamingService("US | DISNEY CHANNEL EAST HD"))
        assertFalse(StreamingServiceDetector.isStreamingService("Disney Junior"))
        assertFalse(StreamingServiceDetector.isStreamingService("Disney XD"))
        assertFalse(StreamingServiceDetector.isStreamingService("Disney East"))
        assertFalse(StreamingServiceDetector.isStreamingService("Disney West"))
        assertFalse(StreamingServiceDetector.isStreamingService("Disney HD"))
    }

    @Test
    fun `identifies Paramount Plus but preserves Paramount Network cable`() {
        // Streaming matches
        assertTrue(StreamingServiceDetector.isStreamingService("PARAMOUNT+"))
        assertTrue(StreamingServiceDetector.isStreamingService("US | PARAMOUNT+"))
        assertTrue(StreamingServiceDetector.isStreamingService("PARAMOUNT+ HD"))
        assertTrue(StreamingServiceDetector.isStreamingService("PARAMOUNT+HD"))
        assertTrue(StreamingServiceDetector.isStreamingService("PARAMOUNT PLUS"))
        assertTrue(StreamingServiceDetector.isStreamingService("US | PARAMOUNT PLUS"))
        assertTrue(StreamingServiceDetector.isStreamingService("Paramount+: 1923"))
        assertTrue(StreamingServiceDetector.isStreamingService("PARAMOUNTPLUS"))

        // Real cable channels MUST NOT MATCH
        assertFalse(StreamingServiceDetector.isStreamingService("Paramount Network"))
        assertFalse(StreamingServiceDetector.isStreamingService("US | PARAMOUNT NETWORK HD"))
        assertFalse(StreamingServiceDetector.isStreamingService("Paramount Channel"))
        assertFalse(StreamingServiceDetector.isStreamingService("Paramount East"))
        assertFalse(StreamingServiceDetector.isStreamingService("Paramount West"))
    }

    @Test
    fun `identifies HBO Max but preserves linear HBO channels`() {
        // Streaming matches
        assertTrue(StreamingServiceDetector.isStreamingService("HBO MAX"))
        assertTrue(StreamingServiceDetector.isStreamingService("US | HBO MAX"))
        assertTrue(StreamingServiceDetector.isStreamingService("HBOMAX"))
        assertTrue(StreamingServiceDetector.isStreamingService("HBO-MAX"))
        assertTrue(StreamingServiceDetector.isStreamingService("HBO MAX: Succession"))

        // Real cable channels MUST NOT MATCH
        assertFalse(StreamingServiceDetector.isStreamingService("HBO"))
        assertFalse(StreamingServiceDetector.isStreamingService("US | HBO EAST HD"))
        assertFalse(StreamingServiceDetector.isStreamingService("HBO 2"))
        assertFalse(StreamingServiceDetector.isStreamingService("HBO Signature"))
        assertFalse(StreamingServiceDetector.isStreamingService("HBO Comedy"))
        assertFalse(StreamingServiceDetector.isStreamingService("HBO Family"))
        assertFalse(StreamingServiceDetector.isStreamingService("HBO Zone"))
        assertFalse(StreamingServiceDetector.isStreamingService("HBO Latino"))
    }

    @Test
    fun `preserves ESPN and all cable sports channels`() {
        assertFalse(StreamingServiceDetector.isStreamingService("ESPN"))
        assertFalse(StreamingServiceDetector.isStreamingService("US | ESPN HD"))
        assertFalse(StreamingServiceDetector.isStreamingService("ESPN 2"))
        assertFalse(StreamingServiceDetector.isStreamingService("ESPNU"))
        assertFalse(StreamingServiceDetector.isStreamingService("ESPNews"))
        assertFalse(StreamingServiceDetector.isStreamingService("ESPN Deportes"))
    }

    @Test
    fun `preserves standard cable channels`() {
        assertFalse(StreamingServiceDetector.isStreamingService("CNN"))
        assertFalse(StreamingServiceDetector.isStreamingService("FOX NEWS"))
        assertFalse(StreamingServiceDetector.isStreamingService("MSNBC"))
        assertFalse(StreamingServiceDetector.isStreamingService("Discovery Channel"))
        assertFalse(StreamingServiceDetector.isStreamingService("USA Network"))
        assertFalse(StreamingServiceDetector.isStreamingService("TBS"))
        assertFalse(StreamingServiceDetector.isStreamingService("TNT"))
    }

    @Test
    fun `identifies Apple TV and Max PPV and Prime streaming services`() {
        assertTrue(StreamingServiceDetector.isStreamingService("UK| APPLE TV F1 PPV"))
        assertTrue(StreamingServiceDetector.isStreamingService("UK| APPLE TV+ SERIES"))
        assertTrue(StreamingServiceDetector.isStreamingService("APPLE TV"))
        assertTrue(StreamingServiceDetector.isStreamingService("UK| MAX PPV"))
        assertTrue(StreamingServiceDetector.isStreamingService("UK| MAX PPV VIP"))
        assertTrue(StreamingServiceDetector.isStreamingService("US| B/R MAX SPORTS PPV"))
        assertTrue(StreamingServiceDetector.isStreamingService("UK| AMAZON PRIME +"))
        assertTrue(StreamingServiceDetector.isStreamingService("US| PRIME"))
        assertTrue(StreamingServiceDetector.isStreamingService("UK| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
    }
}
