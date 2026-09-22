package com.crimson.core.json

import com.crimson.core.model.RawChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class JsonReaderTest {

    private fun reader(s: String) = JsonReader(StringReader(s))

    @Test
    fun `reads a flat object`() {
        val json = reader("""{"a":"one","b":2,"c":true,"d":null}""")
        json.beginObject()
        assertEquals("a", json.nextName()); assertEquals("one", json.nextString())
        assertEquals("b", json.nextName()); assertEquals(2, json.nextInt())
        assertEquals("c", json.nextName()); assertTrue(json.nextBoolean())
        assertEquals("d", json.nextName()); assertNull(json.nextString())
        json.endObject()
        assertEquals(JsonReader.Token.END_DOCUMENT, json.peek())
    }

    @Test
    fun `reads an array of objects`() {
        val json = reader("""[{"id":1},{"id":2},{"id":3}]""")
        val ids = ArrayList<Int>()
        json.beginArray()
        while (json.hasNext()) {
            json.beginObject()
            while (json.hasNext()) {
                json.nextName()
                ids.add(json.nextInt())
            }
            json.endObject()
        }
        json.endArray()
        assertEquals(listOf(1, 2, 3), ids)
    }

    @Test
    fun `skipValue skips whole nested structures`() {
        val json = reader("""{"keep":"yes","junk":{"a":[1,2,{"b":3}],"c":"x"},"after":"z"}""")
        json.beginObject()
        assertEquals("keep", json.nextName()); assertEquals("yes", json.nextString())
        assertEquals("junk", json.nextName()); json.skipValue()
        assertEquals("after", json.nextName()); assertEquals("z", json.nextString())
        json.endObject()
    }

    @Test
    fun `handles escapes and unicode`() {
        val json = reader("""{"t":"a\"b\\c\ndé日"}""")
        json.beginObject()
        json.nextName()
        assertEquals("a\"b\\c\ndé日", json.nextString())
        json.endObject()
    }

    @Test
    fun `numbers survive their many shapes`() {
        val json = reader("""[0,-5,3.75,1e3,12345678901]""")
        json.beginArray()
        assertEquals(0, json.nextInt())
        assertEquals(-5, json.nextInt())
        assertEquals(3, json.nextInt())          // truncates, like the API's integer fields
        assertEquals(1000, json.nextInt())
        assertEquals(12345678901L, json.nextLong())
        json.endArray()
    }

    @Test
    fun `empty containers are handled`() {
        val json = reader("""{"a":[],"b":{}}""")
        json.beginObject()
        json.nextName(); json.beginArray(); assertFalse(json.hasNext()); json.endArray()
        json.nextName(); json.beginObject(); assertFalse(json.hasNext()); json.endObject()
        json.endObject()
    }

    @Test
    fun `a large array streams without retaining anything`() {
        // 20,000 objects built lazily, so the test itself does not hold the document either.
        val body = buildString {
            append('[')
            for (i in 0 until 20_000) {
                if (i > 0) append(',')
                append("""{"stream_id":$i,"name":"Channel $i","category_id":"1"}""")
            }
            append(']')
        }
        var count = 0
        var last = 0
        val json = JsonReader(StringReader(body))
        json.beginArray()
        while (json.hasNext()) {
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "stream_id" -> last = json.nextInt()
                    else -> json.skipValue()
                }
            }
            json.endObject()
            count++
        }
        json.endArray()
        assertEquals(20_000, count)
        assertEquals(19_999, last)
    }
}

class XtreamParserTest {

    @Test
    fun `login is parsed with server and user info`() {
        val body = """
            {"user_info":{"username":"u","password":"p","message":"","auth":1,
             "status":"Active","exp_date":"1821554216","is_trial":"0","active_cons":"0",
             "max_connections":"2","allowed_output_formats":["m3u8","ts"]},
             "server_info":{"url":"example.test","port":"8080","https_port":"8443",
             "server_protocol":"http","timezone":"UTC"}}
        """.trimIndent()
        val info = XtreamParser.parseLogin(body.reader())
        assertTrue(info.authenticated)
        assertEquals("Active", info.status)
        assertEquals(2, info.maxConnections)
        assertEquals(listOf("m3u8", "ts"), info.allowedOutputFormats)
        assertEquals("m3u8", info.preferredFormat)
        assertEquals("example.test", info.serverUrl)
        assertEquals("8080", info.serverPort)
        assertEquals(1821554216L, info.expiryEpochSeconds)
    }

    @Test
    fun `a rejected login is reported as unauthenticated with its message`() {
        val body = """{"user_info":{"auth":0,"status":"Disabled","message":"Invalid credentials"}}"""
        val info = XtreamParser.parseLogin(body.reader())
        assertFalse(info.authenticated)
        assertEquals("Invalid credentials", info.message)
    }

    @Test
    fun `categories are parsed`() {
        val body = """[{"category_id":"1","category_name":"US | SPORTS","parent_id":0},
                       {"category_id":"2","category_name":"UK | NEWS","parent_id":0}]"""
        val cats = XtreamParser.parseCategories(body.reader())
        assertEquals(2, cats.size)
        assertEquals("US | SPORTS", cats[0].categoryName)
        assertEquals("2", cats[1].categoryId)
    }

    @Test
    fun `channels stream through the callback`() {
        val body = """[
            {"num":1,"name":"US| ESPN","stream_type":"live","stream_id":101,
             "stream_icon":"http://x/1.png","epg_channel_id":"espn.us","added":"1",
             "category_id":"1","custom_sid":"","tv_archive":1,"direct_source":""},
            {"num":2,"name":"UK| BBC One","stream_type":"live","stream_id":102,
             "stream_icon":"","epg_channel_id":"","added":"1","category_id":"2","tv_archive":0}
        ]"""
        val got = ArrayList<RawChannel>()
        val seen = XtreamParser.streamChannels(body.reader()) { got.add(it) }
        assertEquals(2, seen)
        assertEquals(101L, got[0].streamId)
        assertEquals("US| ESPN", got[0].name)
        assertEquals("espn.us", got[0].epgChannelId)
        assertEquals(1, got[0].tvArchive)
        assertEquals("UK| BBC One", got[1].name)
    }

    @Test
    fun `channels with no name or id are skipped but still counted`() {
        val body = """[{"stream_id":0,"name":"broken"},{"stream_id":5,"name":""},
                       {"stream_id":7,"name":"US| Good"}]"""
        val got = ArrayList<RawChannel>()
        val seen = XtreamParser.streamChannels(body.reader()) { got.add(it) }
        assertEquals(3, seen)
        assertEquals(1, got.size)
        assertEquals(7L, got[0].streamId)
    }

    @Test
    fun `short epg entries are parsed with their timestamps`() {
        val body = """{"epg_listings":[
            {"id":"1","title":"TmV3cw==","description":"ZGVzYw==",
             "start_timestamp":"1790017200","stop_timestamp":"1790019000"},
            {"id":"2","title":"U3BvcnQ=","description":"",
             "start_timestamp":"1790019000","stop_timestamp":"1790022600"}]}"""
        val entries = XtreamParser.parseShortEpg(body.reader())
        assertEquals(2, entries.size)
        assertEquals(1790017200L, entries[0].startEpochSeconds)
        assertEquals("TmV3cw==", entries[0].titleEncoded)
    }

    @Test
    fun `an unexpected response shape does not throw`() {
        assertEquals(emptyList<Any>(), XtreamParser.parseCategories("""{"error":"nope"}""".reader()))
        assertFalse(XtreamParser.parseLogin("""[]""".reader()).authenticated)
        assertEquals(emptyList<Any>(), XtreamParser.parseShortEpg("""[]""".reader()))
    }
}

class JsonNumberRangeTest {

    @Test
    fun `values beyond Int range are exact as Long and refuse to clamp as Int`() {
        // Silently clamping a large stream_id to Int.MAX_VALUE maps every such channel onto one
        // key, which corrupts the database while looking like it works.
        val big = 4_294_967_295L
        JsonReader(StringReader("""{"a":$big}""")).let { json ->
            json.beginObject(); json.nextName()
            assertEquals(big, json.nextLong())
            json.endObject()
        }
        JsonReader(StringReader("""{"a":$big}""")).let { json ->
            json.beginObject(); json.nextName()
            assertEquals(0, json.nextInt())
            json.endObject()
        }
    }

    @Test
    fun `stream ids beyond Int range survive the channel parser`() {
        val body = """[{"stream_id":4294967295,"name":"US| Big","category_id":"1"},
                       {"stream_id":4294967294,"name":"US| Other","category_id":"1"}]"""
        val got = ArrayList<RawChannel>()
        XtreamParser.streamChannels(body.reader()) { got.add(it) }
        assertEquals(2, got.size)
        assertEquals(4294967295L, got[0].streamId)
        assertEquals(4294967294L, got[1].streamId)
        assertTrue("distinct ids must stay distinct", got[0].streamId != got[1].streamId)
    }
}
