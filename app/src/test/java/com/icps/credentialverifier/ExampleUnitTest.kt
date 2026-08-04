package com.icps.credentialverifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun parseToken_acceptsCredentialVerifyUri() {
        assertEquals("abc123opaque", QrPayloadParser.parseToken("cv://verify/abc123opaque"))
    }

    @Test
    fun parseToken_rejectsNonCredentialPayloads() {
        assertNull(QrPayloadParser.parseToken("https://example.com/credential/abc123opaque"))
        assertNull(QrPayloadParser.parseToken("cv://other/abc123opaque"))
        assertNull(QrPayloadParser.parseToken("550e8400-e29b-41d4-a716-446655440000"))
        assertNull(QrPayloadParser.parseToken("cv://verify/"))
    }
}
