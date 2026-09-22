package com.crimson.core.epg

import com.crimson.core.text.Tokenizer

/**
 * The colour buckets the guide paints program cells with, derived from XMLTV `<category>` tags.
 *
 * The reference guides use magenta for movies and blue for everything else; splitting the
 * "everything else" into sports, news and kids as well means the theme can colour them separately
 * without touching the parser.
 */
enum class ProgramCategory {
    MOVIE,
    SPORTS,
    NEWS,
    KIDS,
    SERIES_OTHER,
    ;

    companion object {

        private val MOVIE_WORDS = setOf(
            "MOVIE", "MOVIES", "FILM", "FILMS", "CINEMA", "FEATURE", "DRAMA FILM",
        )
        private val SPORTS_WORDS = setOf(
            "SPORT", "SPORTS", "FOOTBALL", "SOCCER", "BASKETBALL", "BASEBALL", "HOCKEY",
            "TENNIS", "GOLF", "BOXING", "WRESTLING", "RACING", "MOTORSPORT", "ATHLETICS",
            "CRICKET", "RUGBY", "MMA", "UFC", "OLYMPICS", "NFL", "NBA", "MLB", "NHL",
        )
        private val NEWS_WORDS = setOf(
            "NEWS", "WEATHER", "CURRENT AFFAIRS", "BUSINESS", "POLITICS", "JOURNAL",
            "NEWSMAGAZINE", "DOCUMENTARY NEWS",
        )
        private val KIDS_WORDS = setOf(
            "KIDS", "CHILDREN", "CHILDRENS", "CARTOON", "CARTOONS", "ANIMATED", "ANIMATION",
            "PRESCHOOL", "FAMILY", "YOUTH",
        )

        /**
         * Classifies from the `<category>` tags of one programme. Tags are checked in priority
         * order — a programme tagged both "Movie" and "Drama" is a movie — and anything
         * unrecognised falls through to [SERIES_OTHER], which is the guide's default blue.
         */
        fun fromXmltv(categories: List<String>): ProgramCategory {
            if (categories.isEmpty()) return SERIES_OTHER
            val tokens = categories.flatMap { Tokenizer.tokenize(it).list }.toSet()
            return when {
                tokens.any { it in MOVIE_WORDS } -> MOVIE
                tokens.any { it in SPORTS_WORDS } -> SPORTS
                tokens.any { it in KIDS_WORDS } -> KIDS
                tokens.any { it in NEWS_WORDS } -> NEWS
                else -> SERIES_OTHER
            }
        }
    }
}
