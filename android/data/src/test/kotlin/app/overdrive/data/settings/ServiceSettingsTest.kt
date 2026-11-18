package app.overdrive.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceSettingsTest {
    private fun valid(raw: String) = ServiceRules.checkAddress(raw) as AddressCheck.Valid

    private fun reason(raw: String) = (ServiceRules.checkAddress(raw) as AddressCheck.Invalid).reason

    @Test
    fun `nothing typed is no service`() {
        assertEquals(AddressCheck.Empty, ServiceRules.checkAddress(""))
        assertEquals(AddressCheck.Empty, ServiceRules.checkAddress("   "))
        assertEquals("", ServiceRules.cleanAddress(""))
    }

    @Test
    fun `an address without a scheme is https, as the design's field shows it`() {
        assertEquals("https://overdrive.example.net", valid("overdrive.example.net").url)
        assertTrue(valid("overdrive.example.net").isEncrypted)
        assertEquals("overdrive.example.net", valid("overdrive.example.net").host)
    }

    @Test
    fun `scheme and host are lower case, a path and a port are kept`() {
        assertEquals("https://example.com:8443/base", valid("HTTPS://Example.COM:8443/base").url)
        assertEquals("http://192.168.1.20:3000", valid("http://192.168.1.20:3000").url)
    }

    @Test
    fun `a plain http address is accepted and says it is not encrypted`() {
        assertFalse(valid("http://192.168.1.20:3000").isEncrypted)
    }

    @Test
    fun `mistakes are said in words and not stored`() {
        assertEquals("The address needs a host, like example.com.", reason("https://"))
        assertEquals("The address needs a host, like example.com.", reason("https:///path"))
        assertEquals("An address has no spaces.", reason("https://exa mple.com"))
        assertEquals("Start with https:// or http://.", reason("ftp://example.com"))
        assertEquals(
            "Leave the user name and password out; the token goes below.",
            reason("https://me:secret@example.com"),
        )
        assertEquals("The port is not valid.", reason("https://example.com:99999"))
        assertEquals("That address is too long.", reason("https://example.com/" + "a".repeat(2100)))
        assertEquals("", ServiceRules.cleanAddress("https://me:secret@example.com"))
    }

    @Test
    fun `a token loses control characters and spaces and is cut at 512`() {
        assertEquals("abc", ServiceRules.cleanToken("  abc\n"))
        assertEquals("abc", ServiceRules.cleanToken("a\u0000b\tc"))
        assertEquals(512, ServiceRules.cleanToken("x".repeat(900)).length)
    }

    @Test
    fun `a secret prints as dots and never as itself, and compares by value`() {
        val secret = SecretText("tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7")
        assertEquals("••••", secret.toString())
        assertFalse("$secret".contains("tok-"))
        assertEquals("(none)", SecretText().toString())
        assertEquals(secret, SecretText("tok-3f9a7c1e5b2d4a6f8091a2b3c4d5e6f7"))
        assertFalse(secret == SecretText("other"))
        assertTrue(SecretText().isEmpty)
    }
}
