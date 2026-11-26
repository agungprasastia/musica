package com.music.musica.data.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.schabi.newpipe.extractor.utils.Utils

class NewPipeApiCompatTest {

    @Test
    fun vendoredNewPipeUtilsRoundTripsUtf8Encoding() {
        val raw = "Malam Minggu & Kopi — #1/2 (Live)"
        val encoded = Utils.encodeUrlUtf8(raw)
        assertEquals(raw, Utils.decodeUrlUtf8(encoded))
    }

    @Test
    fun vendoredUtilsBytecodeDoesNotReferenceApi33CharsetOverload() {
        val classBytes = Utils::class.java.classLoader!!
            .getResourceAsStream("org/schabi/newpipe/extractor/utils/Utils.class")!!
            .use { it.readBytes() }
        val ascii = String(classBytes, Charsets.ISO_8859_1)

        // API 33 overload descriptor: (Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;
        assertFalse(
            "Utils.class must use the API 1 (String, String) overload instead of the API 33 (String, Charset) overload",
            ascii.contains("(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"),
        )
    }
}
