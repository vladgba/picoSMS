package x.vladgba.sms

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class SmsFormatsTest {
    private fun messages(reader: MsgReader): List<BakMsg> = reader.use {
        val out = ArrayList<BakMsg>()
        while (true) out.add(it.next() ?: break)
        out
    }

    @Test fun duplicateIdentityIncludesSenderAndCompleteText() {
        val a = MessageKey(123, 1, "BANK", "Aa")
        val b = MessageKey(123, 1, "BANK", "BB")
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertEquals(3, hashSetOf(a, b, a.copy(address = "SHOP"), a.copy()).size)
    }

    @Test fun xmlRoundTripPreservesLiteralNullAndEscapedText() {
        for (body in listOf("null", "a & b < c > d \"quoted\" 'single'\n\r\t😀")) {
            val out = StringWriter()
            val m = BakMsg().apply { address = "+359888123456"; this.body = body; date = 123; type = 2 }
            XmlOut(out).apply { begin(1); write(m, null); end() }
            val restored = messages(XmlIn(StringReader(out.toString()))).single()
            assertEquals(body, restored.body)
            assertEquals(m.address, restored.address)
            assertEquals(m.date, restored.date)
            assertEquals(m.type, restored.type)
        }
    }

    @Test fun xmlReadsSurrogateReferencesAndSkipsMms() {
        val reader = XmlIn(StringReader("<smses count='2'><mms><parts><part text='ignored'/></parts></mms><sms address='A' body='&#55357;&#56832; &gt;' /></smses>"))
        val restored = messages(reader)
        assertEquals("😀 >", restored.single().body)
        assertEquals(1, reader.mmsSkipped)
        assertEquals(2, reader.declared)
    }

    @Test fun xmlRejectsTruncatedBackups() {
        for (xml in listOf("<smses><sms address='A' body='broken", "<smses><sms address='A' body='ok'/>", "<smses count='1'")) {
            assertThrows(IllegalArgumentException::class.java) { messages(XmlIn(StringReader(xml))) }
        }
    }

    @Test fun xmlAcceptsEmptyRoot() {
        assertTrue(messages(XmlIn(StringReader("<smses/>"))).isEmpty())
        assertTrue(messages(XmlIn(StringReader("<smses></smses>"))).isEmpty())
    }

    @Test fun jsonRoundTripPreservesMessageFields() {
        val out = StringWriter()
        val m = BakMsg().apply {
            address = "BANK"; body = "null & 😀 \u0001 \"quote\"\n\\"; date = 123; dateSent = 122
            type = 2; read = 0; seen = 0; locked = 1; subId = 3; status = 64
            protocol = 1; serviceCenter = "+123"; subject = "subject"
        }
        JsonOut(out).apply { begin(1); write(m, "Name"); end() }
        val reader = JsonIn(StringReader(out.toString()))
        val restored = messages(reader).single()
        assertEquals(m.address, restored.address)
        assertEquals(m.body, restored.body)
        assertEquals(m.date, restored.date)
        assertEquals(m.dateSent, restored.dateSent)
        assertEquals(m.type, restored.type)
        assertEquals(m.read, restored.read)
        assertEquals(m.seen, restored.seen)
        assertEquals(m.locked, restored.locked)
        assertEquals(m.subId, restored.subId)
        assertEquals(m.status, restored.status)
        assertEquals(m.protocol, restored.protocol)
        assertEquals(m.serviceCenter, restored.serviceCenter)
        assertEquals(m.subject, restored.subject)
        assertEquals(1, reader.declared)
    }

    @Test fun jsonAcceptsBomBareArrayAndNestedMetadata() {
        val reader = JsonIn(StringReader("\uFEFF  [{\"address\":\"A\",\"body\":\"ok\",\"unknown\":{\"a\":[1,true,null,{\"x\":\"y\"}]}}]"))
        assertEquals("ok", messages(reader).single().body)
    }

    @Test fun jsonValidatesEnvelopeAfterLastMessage() {
        val reader = JsonIn(StringReader("{\"messages\":[],\"metadata\":{\"x\":[]},\"count\":0}"))
        assertTrue(messages(reader).isEmpty())
        assertEquals(0, reader.declared)
    }

    @Test fun jsonRejectsMalformedAndIncompleteFiles() {
        val invalid = listOf(
            "{\"messages\":[{}]", "{\"settings\":{}}", "[{} {}]", "[{},]",
            "[{\"body\":\"a\" \"address\":\"A\"}]", "[{\"body\":\"a\",}]",
            "[{\"read\":truth}]", "[{\"read\":falsehood}]", "[{\"date\":01}]",
            "[{\"body\":\"\\uZZZZ\"}]", "[{\"body\":\"\\q\"}]",
            "[{\"body\":\"unescaped\nnewline\"}]", "[{\"unknown\":{\"a\":[1,}}]",
            "[] garbage", "{\"messages\":[],}", "{\"messages\":[],\"unknown\":{"
        )
        for (json in invalid) {
            assertThrows("Accepted malformed JSON: $json", IllegalArgumentException::class.java) {
                messages(JsonIn(StringReader(json)))
            }
        }
    }

    @Test fun readersCloseTheirInputEvenWhenParsingFails() {
        class TrackedReader(text: String) : StringReader(text) {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        val json = TrackedReader("[")
        assertThrows(IllegalArgumentException::class.java) { messages(JsonIn(json)) }
        assertTrue(json.closed)
        val xml = TrackedReader("<smses>")
        assertThrows(IllegalArgumentException::class.java) { messages(XmlIn(xml)) }
        assertTrue(xml.closed)
    }

    @Test fun encryptionRoundTripRejectsWrongPasswordAndTampering() {
        val plain = "SMS backup 😀".toByteArray(Charsets.UTF_8)
        for (sha256 in listOf(true, false)) {
            val encrypted = Crypt.encrypt(plain, "password".toCharArray(), sha256)
            assertArrayEquals(plain, Crypt.decrypt(encrypted, "password".toCharArray()))
            assertThrows(Crypt.WrongPassword::class.java) { Crypt.decrypt(encrypted, "wrong".toCharArray()) }
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
            assertThrows(Crypt.WrongPassword::class.java) { Crypt.decrypt(encrypted, "password".toCharArray()) }
        }
    }

    @Test fun encryptedGzipStreamsRoundTripWithShortReads() {
        val plain = ByteArray(300_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(Crypt.encrypting(out, "password".toCharArray(), true)).use { gzip ->
            for (offset in plain.indices step 1000) gzip.write(plain, offset, minOf(1000, plain.size - offset))
        }
        val shortReads = object : ByteArrayInputStream(out.toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 7))
        }
        val decrypted = ByteArrayOutputStream()
        Crypt.decrypt(shortReads, decrypted, "password".toCharArray())
        val restored = GZIPInputStream(ByteArrayInputStream(decrypted.toByteArray())).use { it.readBytes() }
        assertArrayEquals(plain, restored)
    }

    @Test fun encryptedStreamRejectsTruncatedTagAndUnknownKdf() {
        val data = Crypt.encrypt("backup".toByteArray(), "password".toCharArray(), true)
        assertThrows(Crypt.WrongPassword::class.java) {
            Crypt.decrypt(data.copyOf(data.size - 1), "password".toCharArray())
        }
        data[4] = 3
        assertThrows(IllegalArgumentException::class.java) { Crypt.decrypt(data, "password".toCharArray()) }
    }
}
