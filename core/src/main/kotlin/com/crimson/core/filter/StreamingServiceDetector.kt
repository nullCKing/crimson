package com.crimson.core.filter

/**
 * Detects streaming service pseudochannels and categories (Netflix, Paramount+, HBO Max, Disney+, etc.).
 *
 * Distinguishes between streaming services and real linear cable networks:
 *  - Disney+ / Disney Plus is streaming; Disney Channel, Disney XD, Disney Junior are cable.
 *  - Paramount+ / Paramount Plus is streaming; Paramount Network is cable.
 *  - HBO Max / HBOMax is streaming; HBO, HBO 2, HBO Signature, HBO Comedy, HBO Family are cable.
 *  - Netflix is 100% streaming (no linear cable channel).
 *  - ESPN channels are 100% cable and never matched.
 */
object StreamingServiceDetector {

    private val NETFLIX_REGEX = Regex("""\bNETFLIX\b""", RegexOption.IGNORE_CASE)
    private val HBO_MAX_REGEX = Regex("""\b(HBO[\s\-_]*MAX|HBOMAX|(B/R|BR)[\s\-_]*MAX|MAX\s+(PPV|ORIGINAL|SERIES|MOVIES|CINEMA|SPORT|SPORTS|LIVE|VIP|EVENT|SPECIAL|NETWORK|ESPN))\b""", RegexOption.IGNORE_CASE)
    private val DISNEY_PLUS_REGEX = Regex("""\b(DISNEY\s*\+|DISNEY\s+PLUS\b|DISNEYPLUS\b)""", RegexOption.IGNORE_CASE)
    private val PARAMOUNT_PLUS_REGEX = Regex("""\b(PARAMOUNT\s*\+|PARAMOUNT\s+PLUS\b|PARAMOUNTPLUS\b)""", RegexOption.IGNORE_CASE)
    private val APPLE_TV_REGEX = Regex("""\b(APPLE\s*TV\s*\+|APPLE\s*TV\s+PLUS\b|APPLETV\+|APPLE\s*TV\b)""", RegexOption.IGNORE_CASE)
    private val PEACOCK_REGEX = Regex("""\bPEACOCK\b""", RegexOption.IGNORE_CASE)
    private val HULU_REGEX = Regex("""\bHULU\b""", RegexOption.IGNORE_CASE)
    private val PRIME_VIDEO_REGEX = Regex("""\b(AMAZON\s*PRIME|PRIME\s*VIDEO|PRIME\s*\+|PRIME\s+PLUS)\b|^[A-Z]{2}\|\s*PRIME(\s|$)""", RegexOption.IGNORE_CASE)
    private val DISCOVERY_PLUS_REGEX = Regex("""\b(DISCOVERY\s*\+|DISCOVERY\s+PLUS\b|DISCOVERYPLUS\b)""", RegexOption.IGNORE_CASE)

    fun isStreamingService(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return NETFLIX_REGEX.containsMatchIn(name) ||
            HBO_MAX_REGEX.containsMatchIn(name) ||
            DISNEY_PLUS_REGEX.containsMatchIn(name) ||
            PARAMOUNT_PLUS_REGEX.containsMatchIn(name) ||
            APPLE_TV_REGEX.containsMatchIn(name) ||
            PEACOCK_REGEX.containsMatchIn(name) ||
            HULU_REGEX.containsMatchIn(name) ||
            PRIME_VIDEO_REGEX.containsMatchIn(name) ||
            DISCOVERY_PLUS_REGEX.containsMatchIn(name)
    }
}
