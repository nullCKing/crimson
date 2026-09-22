package com.crimson.core.sports

import com.crimson.core.live.WorldRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsTest {

    private val sample = """
        {"leagues":[{"id":"28"}],
         "events":[
          {"id":"401","date":"2026-09-21T17:00Z","name":"Cleveland Browns at Kansas City Chiefs","shortName":"CLE @ KC",
           "competitions":[{"id":"401",
             "venue":{"fullName":"GEHA Field at Arrowhead Stadium"},
             "competitors":[
               {"homeAway":"home","winner":false,"score":"17","records":[{"summary":"2-0"}],
                "team":{"displayName":"Kansas City Chiefs","shortDisplayName":"Chiefs","abbreviation":"KC","color":"e31837","logo":"https://a.espncdn.com/kc.png"}},
               {"homeAway":"away","winner":false,"score":"14",
                "team":{"displayName":"Cleveland Browns","shortDisplayName":"Browns","abbreviation":"CLE","logo":"https://a.espncdn.com/cle.png"}}],
             "status":{"clock":321.0,"period":3,"type":{"state":"in","detail":"5:21 - 3rd Quarter","shortDetail":"5:21 - 3rd"}},
             "broadcasts":[{"market":"national","names":["NBC","Peacock"]}],
             "geoBroadcasts":[{"type":{"shortName":"TV"},"market":{"type":"National"},"media":{"shortName":"NBC"}}]}]},
          {"id":"402","date":"2026-09-22T00:20Z","name":"A at B","shortName":"A @ B",
           "competitions":[{"competitors":[],"status":{"type":{"state":"pre","shortDetail":"9/21 - 8:20 PM EDT"}},
             "broadcasts":[{"market":"national","names":["Prime Video"]}]}]},
          {"id":"403","date":"not a date","name":"broken"}
         ]}
    """.trimIndent()

    @Test
    fun `scoreboard parses live game with network and scores`() {
        val events = EspnScoreboard.parse(sample.reader(), Leagues.NFL)
        assertEquals(2, events.size)
        val game = events.first()
        assertEquals(EventState.LIVE, game.state)
        assertEquals("5:21 - 3rd", game.statusText)
        assertEquals("KC", game.home?.abbreviation)
        assertEquals("17", game.home?.score)
        assertEquals("2-0", game.home?.record)
        assertEquals("Browns", game.away?.shortName)
        assertEquals(listOf("NBC", "Peacock"), game.networks)
        assertEquals("GEHA Field at Arrowhead Stadium", game.venue)
        assertEquals(EventState.UPCOMING, events[1].state)
    }

    @Test
    fun `selecting a game searches for its network`() {
        val events = EspnScoreboard.parse(sample.reader(), Leagues.NFL)
        assertEquals("NBC", BroadcastSearch.termFor(events[0]))
        assertEquals("Prime", BroadcastSearch.termFor(events[1]))
    }

    @Test
    fun `a streaming service loses to a channel`() {
        val event = EspnScoreboard.parse(sample.reader(), Leagues.NFL)[0].copy(networks = listOf("ESPN+", "ABC"))
        assertEquals("ABC", BroadcastSearch.termFor(event))
    }

    @Test
    fun `espn times without seconds parse`() {
        assertNotNull(EspnScoreboard.parseTime("2026-09-18T00:15Z"))
    }

    @Test
    fun `regions resolve from any spelling`() {
        assertEquals("DE", WorldRegions.forToken("DEUTSCH")?.code)
        assertEquals("UK", WorldRegions.forToken("UK")?.code)
        assertEquals("LATAM", WorldRegions.forToken("LATINO")?.code)
        assertEquals("FR", WorldRegions.forCategory(null, null, "FR| SPORTS").code)
        assertEquals("US", WorldRegions.forCategory("US", null, "anything").code)
        assertEquals(WorldRegions.OTHER, WorldRegions.forCategory(null, null, "24/7 LOOPS"))
        assertEquals("🇯🇵", WorldRegions.byCode("JP")?.flag)
        assertTrue(WorldRegions.matches(WorldRegions.byCode("JP")!!, "jap"))
    }
}
