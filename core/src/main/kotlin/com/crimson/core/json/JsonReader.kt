package com.crimson.core.json

import java.io.Closeable
import java.io.IOException
import java.io.Reader

/**
 * A pull parser for JSON, reading from a stream without ever holding the document in memory.
 *
 * A provider's `get_live_streams` response can be tens of megabytes, and a Fire TV Stick Lite has
 * a 1 GB heap to share with everything else on the device. Parsing that into a tree, or even into
 * a `String`, is the single easiest way to make this app die on the hardware it is for. So the
 * import reads one channel at a time, decides immediately whether to keep it, and lets the rest
 * fall out of scope before the next one is read.
 *
 * The API deliberately mirrors `android.util.JsonReader` so the two are interchangeable. This one
 * exists because `core` is a plain Kotlin module with no Android dependency — which is what makes
 * the filter, and this parser, testable on any JVM and reusable by the offline discovery tool.
 *
 * Not a validating parser: it accepts some things a strict one would reject. It is used against
 * one known producer, and being lenient about a provider's malformed output is a feature.
 */
class JsonReader(reader: Reader) : Closeable {

    enum class Token {
        BEGIN_ARRAY, END_ARRAY, BEGIN_OBJECT, END_OBJECT,
        NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT,
    }

    private val input: Reader = reader
    private val buffer = CharArray(BUFFER_SIZE)
    private var bufferLength = 0
    private var position = 0

    /** Nesting stack: true for an object, false for an array. */
    private val stack = ArrayList<Boolean>(16)

    /** Set when the next token in an object must be a name rather than a value. */
    private var expectingName = false

    private var peeked: Token? = null
    private val text = StringBuilder(64)

    // ------------------------------------------------------------------ public API

    fun peek(): Token {
        peeked?.let { return it }
        val token = scan()
        peeked = token
        return token
    }

    fun hasNext(): Boolean {
        val t = peek()
        return t != Token.END_ARRAY && t != Token.END_OBJECT && t != Token.END_DOCUMENT
    }

    fun beginArray() {
        expect(Token.BEGIN_ARRAY)
        stack.add(false)
        expectingName = false
        peeked = null
    }

    fun endArray() {
        expect(Token.END_ARRAY)
        popScope()
    }

    fun beginObject() {
        expect(Token.BEGIN_OBJECT)
        stack.add(true)
        expectingName = true
        peeked = null
    }

    fun endObject() {
        expect(Token.END_OBJECT)
        popScope()
    }

    fun nextName(): String {
        expect(Token.NAME)
        peeked = null
        expectingName = false
        return text.toString()
    }

    /** Reads a string, a number or a boolean as text; reads null as null. */
    fun nextString(): String? {
        return when (val t = peek()) {
            Token.STRING, Token.NUMBER, Token.BOOLEAN -> {
                peeked = null
                afterValue()
                text.toString()
            }
            Token.NULL -> {
                peeked = null
                afterValue()
                null
            }
            else -> throw IOException("expected a string but found $t")
        }
    }

    /**
     * Reads an integer. A value outside `Int` range returns 0 rather than the clamped
     * `Int.MAX_VALUE`: silently clamping would map every large id onto the same key, which is a
     * corruption that looks like working code. Fields that can be wide use [nextLong].
     */
    fun nextInt(): Int {
        val s = nextString() ?: return 0
        s.toIntOrNull()?.let { return it }
        val d = s.toDoubleOrNull() ?: return 0
        if (d < Int.MIN_VALUE.toDouble() || d > Int.MAX_VALUE.toDouble()) return 0
        return d.toInt()
    }

    fun nextLong(): Long = nextString()?.let {
        it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong()
    } ?: 0L

    fun nextBoolean(): Boolean {
        val s = nextString() ?: return false
        return s == "true" || s == "1"
    }

    fun nextNull() {
        expect(Token.NULL)
        peeked = null
        afterValue()
    }

    /** Skips the next value entirely, including a whole nested object or array. */
    fun skipValue() {
        var depth = 0
        do {
            when (peek()) {
                Token.BEGIN_ARRAY -> { beginArray(); depth++ }
                Token.END_ARRAY -> { endArray(); depth-- }
                Token.BEGIN_OBJECT -> { beginObject(); depth++ }
                Token.END_OBJECT -> { endObject(); depth-- }
                Token.NAME -> nextName()
                Token.END_DOCUMENT -> return
                else -> nextString()
            }
        } while (depth > 0)
    }

    override fun close() {
        input.close()
    }

    // ------------------------------------------------------------------ scanning

    private fun expect(token: Token) {
        val actual = peek()
        if (actual != token) throw IOException("expected $token but found $actual")
        if (token == Token.BEGIN_ARRAY || token == Token.BEGIN_OBJECT) peeked = null
    }

    private fun popScope() {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        peeked = null
        afterValue()
    }

    /** After a completed value, an object expects the next name and an array expects a value. */
    private fun afterValue() {
        expectingName = stack.isNotEmpty() && stack[stack.size - 1]
    }

    private fun scan(): Token {
        var c = nextNonWhitespace() ?: return Token.END_DOCUMENT

        // Structural commas and colons carry no information for a pull parser.
        while (c == ',' || c == ':') {
            c = nextNonWhitespace() ?: return Token.END_DOCUMENT
        }

        return when (c) {
            '{' -> Token.BEGIN_OBJECT
            '}' -> Token.END_OBJECT
            '[' -> Token.BEGIN_ARRAY
            ']' -> Token.END_ARRAY
            '"' -> {
                readQuoted()
                if (expectingName) Token.NAME else Token.STRING
            }
            't', 'f' -> { readBare(c); Token.BOOLEAN }
            'n' -> { readBare(c); Token.NULL }
            else -> { readBare(c); Token.NUMBER }
        }
    }

    private fun nextNonWhitespace(): Char? {
        while (true) {
            if (position >= bufferLength) {
                if (!fill()) return null
            }
            val c = buffer[position++]
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return c
        }
    }

    private fun fill(): Boolean {
        bufferLength = input.read(buffer, 0, buffer.size)
        position = 0
        return bufferLength > 0
    }

    private fun readChar(): Char? {
        if (position >= bufferLength && !fill()) return null
        return buffer[position++]
    }

    private fun readQuoted() {
        text.setLength(0)
        while (true) {
            val c = readChar() ?: return
            when (c) {
                '"' -> return
                '\\' -> {
                    when (val e = readChar() ?: return) {
                        'n' -> text.append('\n')
                        't' -> text.append('\t')
                        'r' -> text.append('\r')
                        'b' -> text.append('\b')
                        'f' -> text.append('\u000C')
                        'u' -> {
                            var code = 0
                            for (i in 0 until 4) {
                                val h = readChar() ?: return
                                code = code * 16 + hexValue(h)
                            }
                            text.append(code.toChar())
                        }
                        else -> text.append(e)  // covers \" \\ \/ and anything odd
                    }
                }
                else -> text.append(c)
            }
        }
    }

    /** Reads an unquoted literal: a number, `true`, `false` or `null`. */
    private fun readBare(first: Char) {
        text.setLength(0)
        text.append(first)
        while (true) {
            if (position >= bufferLength && !fill()) return
            val c = buffer[position]
            if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\n' ||
                c == '\r' || c == '\t' || c == ':'
            ) {
                return
            }
            text.append(c)
            position++
        }
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }

    private companion object {
        /** 8 KB of chars. Big enough that reads are cheap, small enough to be invisible on a Stick. */
        const val BUFFER_SIZE = 8192
    }
}
