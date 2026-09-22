package com.crimson.core.numbering

import com.crimson.core.model.Country
import com.crimson.core.model.KeptChannel

/**
 * Assigns cable-style channel numbers in country blocks: US, then UK, then Japan, then South Korea.
 *
 * The property that matters is stability. A viewer learns that 1042 is their channel, and a
 * refresh that renumbers everything because the provider reordered its stream list would be worse
 * than useless. So numbers are keyed on `stream_id` and, once handed out, are never reassigned —
 * not even when a channel disappears. If it comes back a month later it gets its old number back.
 *
 * The cost of never reclaiming a number is that churn eats into a block. Each block is sized well
 * beyond any real provider's count for the countries involved, and [OVERFLOW_START] catches the
 * pathological case rather than colliding.
 */
object ChannelNumbering {

    /** Per-country number ranges, in the order the spec asks for. */
    val BLOCKS: Map<Country, IntRange> = linkedMapOf(
        Country.US to 1000..4999,
        Country.UK to 5000..6999,
        Country.JP to 7000..8499,
        Country.KR to 8500..9999,
    )

    /** Numbers handed out once every block is exhausted. */
    const val OVERFLOW_START = 10_000

    /**
     * The persisted assignment table: `stream_id` to channel number, covering every channel ever
     * seen, not only the ones present now.
     */
    data class Assignments(val byStreamId: Map<Long, Int>) {

        fun numberOf(streamId: Long): Int? = byStreamId[streamId]

        companion object {
            val EMPTY = Assignments(emptyMap())
        }
    }

    /**
     * Returns an assignment table covering [channels], preserving every number already present in
     * [previous].
     *
     * New channels are sorted by cleaned display name and then by stream id before numbers are
     * handed out, so a fresh install produces an alphabetical guide and two installs of the same
     * provider produce the same numbering.
     */
    fun assign(previous: Assignments, channels: List<KeptChannel>): Assignments {
        val result = HashMap(previous.byStreamId)
        val used = HashSet(previous.byStreamId.values)

        for ((country, block) in BLOCKS) {
            val newcomers = channels
                .filter { it.country == country && !result.containsKey(it.streamId) }
                .sortedWith(compareBy({ it.displayName.lowercase() }, { it.streamId }))
            if (newcomers.isEmpty()) continue

            var next = block.first
            for (channel in newcomers) {
                while (next in block && next in used) next++
                val number = if (next in block) next else nextOverflow(used)
                result[channel.streamId] = number
                used.add(number)
                if (number == next) next++
            }
        }
        return Assignments(result)
    }

    private fun nextOverflow(used: Set<Int>): Int {
        var n = OVERFLOW_START
        while (n in used) n++
        return n
    }

    /** The country block a number falls in, or null for overflow numbers. */
    fun countryOf(number: Int): Country? =
        BLOCKS.entries.firstOrNull { number in it.value }?.key
}
