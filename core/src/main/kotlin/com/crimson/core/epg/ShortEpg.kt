package com.crimson.core.epg

/**
 * Decoding for `action=get_short_epg`, whose `title` and `description` fields arrive base64
 * encoded (and, from some providers, double encoded or not encoded at all).
 *
 * Base64 is implemented here rather than borrowed because the two obvious choices both have a
 * problem: `java.util.Base64` needs API 26 and this app supports API 25, and `android.util.Base64`
 * would drag an Android dependency into a module that is deliberately pure Kotlin so it can be
 * unit-tested on any JVM.
 */
object ShortEpg {

    private val TABLE = IntArray(128) { -1 }.also { t ->
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        alphabet.forEachIndexed { i, c -> t[c.code] = i }
        // Accept the URL-safe alphabet too; some providers use it.
        t['-'.code] = 62
        t['_'.code] = 63
    }

    /**
     * Decodes [value] as base64 UTF-8 text.
     *
     * Providers are inconsistent about whether a field is encoded at all, so a string that is not
     * valid base64, or that decodes to something that is not plausible text, is returned unchanged.
     * Showing a slightly odd title beats showing mojibake.
     */
    fun decodeField(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val trimmed = value.trim()
        val bytes = decodeOrNull(trimmed) ?: return trimmed
        val text = String(bytes, Charsets.UTF_8)
        return if (looksLikeText(text)) text else trimmed
    }

    fun decodeOrNull(s: String): ByteArray? {
        val clean = StringBuilder(s.length)
        for (c in s) {
            when {
                c == '=' || c == '\n' || c == '\r' || c == ' ' -> {}
                c.code < 128 && TABLE[c.code] >= 0 -> clean.append(c)
                else -> return null
            }
        }
        if (clean.length % 4 == 1) return null
        val out = ByteArray(clean.length * 3 / 4)
        var acc = 0
        var bits = 0
        var o = 0
        for (c in clean) {
            acc = (acc shl 6) or TABLE[c.code]
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = ((acc shr bits) and 0xFF).toByte()
            }
        }
        return if (o == out.size) out else out.copyOf(o)
    }

    /**
     * A decoded field is accepted as text when it contains no control characters other than the
     * usual whitespace. Random bytes from a non-base64 string almost always trip this.
     */
    private fun looksLikeText(s: String): Boolean {
        if (s.isEmpty()) return false
        for (c in s) {
            if (c == '\n' || c == '\r' || c == '\t') continue
            if (c.code < 0x20 || c.code == 0x7F) return false
            if (c == '�') return false
        }
        return true
    }
}
