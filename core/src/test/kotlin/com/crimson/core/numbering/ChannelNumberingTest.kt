package com.crimson.core.numbering

import com.crimson.core.model.Country
import com.crimson.core.model.KeptChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNumberingTest {

    private fun ch(id: Long, name: String, country: Country) = KeptChannel(
        streamId = id,
        originalName = name,
        displayName = name,
        country = country,
        market = null,
        categoryId = null,
        categoryName = null,
        epgChannelId = null,
        streamIcon = null,
        tvArchive = false,
    )

    @Test
    fun `countries occupy their own blocks in order`() {
        val channels = listOf(
            ch(1, "ESPN", Country.US),
            ch(2, "BBC One", Country.UK),
            ch(3, "NHK G", Country.JP),
            ch(4, "KBS 1", Country.KR),
        )
        val a = ChannelNumbering.assign(ChannelNumbering.Assignments.EMPTY, channels)
        assertTrue(a.numberOf(1)!! in 1000..4999)
        assertTrue(a.numberOf(2)!! in 5000..6999)
        assertTrue(a.numberOf(3)!! in 7000..8499)
        assertTrue(a.numberOf(4)!! in 8500..9999)
    }

    @Test
    fun `numbers survive a refresh`() {
        val first = listOf(ch(10, "AMC", Country.US), ch(11, "ESPN", Country.US), ch(12, "TNT", Country.US))
        val a1 = ChannelNumbering.assign(ChannelNumbering.Assignments.EMPTY, first)

        // Provider reorders the list and adds a channel in the middle of the alphabet.
        val second = listOf(
            ch(12, "TNT", Country.US),
            ch(99, "Bravo", Country.US),
            ch(10, "AMC", Country.US),
            ch(11, "ESPN", Country.US),
        )
        val a2 = ChannelNumbering.assign(a1, second)

        assertEquals(a1.numberOf(10), a2.numberOf(10))
        assertEquals(a1.numberOf(11), a2.numberOf(11))
        assertEquals(a1.numberOf(12), a2.numberOf(12))
        assertNotEquals(null, a2.numberOf(99))
    }

    @Test
    fun `a channel that disappears and returns keeps its number`() {
        val all = listOf(ch(1, "A", Country.US), ch(2, "B", Country.US), ch(3, "C", Country.US))
        val a1 = ChannelNumbering.assign(ChannelNumbering.Assignments.EMPTY, all)
        val numberOfB = a1.numberOf(2)

        val withoutB = listOf(ch(1, "A", Country.US), ch(3, "C", Country.US))
        val a2 = ChannelNumbering.assign(a1, withoutB)

        val backAgain = ChannelNumbering.assign(a2, all)
        assertEquals(numberOfB, backAgain.numberOf(2))
    }

    @Test
    fun `a new channel never steals a retired number`() {
        val a1 = ChannelNumbering.assign(
            ChannelNumbering.Assignments.EMPTY,
            listOf(ch(1, "A", Country.US), ch(2, "B", Country.US)),
        )
        val retired = a1.numberOf(2)!!
        val a2 = ChannelNumbering.assign(a1, listOf(ch(1, "A", Country.US), ch(3, "New", Country.US)))
        assertNotEquals(retired, a2.numberOf(3))
    }

    @Test
    fun `assignment is alphabetical and deterministic on a fresh install`() {
        val channels = listOf(
            ch(5, "Zebra TV", Country.US),
            ch(6, "Alpha TV", Country.US),
            ch(7, "Middle TV", Country.US),
        )
        val a = ChannelNumbering.assign(ChannelNumbering.Assignments.EMPTY, channels)
        assertTrue(a.numberOf(6)!! < a.numberOf(7)!!)
        assertTrue(a.numberOf(7)!! < a.numberOf(5)!!)

        // Same input in a different order produces the same numbers.
        val b = ChannelNumbering.assign(ChannelNumbering.Assignments.EMPTY, channels.reversed())
        assertEquals(a.byStreamId, b.byStreamId)
    }

    @Test
    fun `a full block overflows instead of colliding`() {
        val block = ChannelNumbering.BLOCKS[Country.JP]!!
        val tooMany = (1..block.count() + 5).map { ch(it.toLong(), "JP $it", Country.JP) }
        val a = ChannelNumbering.assign(ChannelNumbering.Assignments.EMPTY, tooMany)
        assertEquals("every channel must get a number", tooMany.size, a.byStreamId.size)
        assertEquals("numbers must be unique", tooMany.size, a.byStreamId.values.toSet().size)
        assertTrue(a.byStreamId.values.any { it >= ChannelNumbering.OVERFLOW_START })
    }
}
