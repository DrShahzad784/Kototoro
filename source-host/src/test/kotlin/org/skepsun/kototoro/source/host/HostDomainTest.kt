package org.skepsun.kototoro.source.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HostDomainTest {
    open class BaseSource { open val baseUrl: String get() = "https://computed.example" }
    class FieldSource : BaseSource() { override val baseUrl = "https://old.example/en" }
    class ComputedSource : BaseSource()
    /** What R8 leaves of `override val baseUrl = …` in published extensions. */
    class MinifiedSource : BaseSource() {
        @JvmField val a = "https://api.example"
        private val c = "https://old.example"
        override val baseUrl: String get() = c
    }

    @Test
    fun `domains replace host and keep the extension's path`() {
        assertEquals("https://new.example/en", HostDomain.rewrite("https://old.example/en", "new.example"))
        assertEquals("http://new.example:8080/en", HostDomain.rewrite("https://old.example/en", "http://new.example:8080/"))
        assertEquals("https://new.example", HostDomain.rewrite("https://old.example", " https://new.example "))
        assertNull(HostDomain.rewrite("https://old.example", "new.example/path"))
        assertNull(HostDomain.rewrite("https://old.example", "ftp://new.example"))
        assertNull(HostDomain.rewrite("https://old.example", "not a host"))
    }

    /** `override val baseUrl = …` is a final field; the source's own getter then returns the new address. */
    @Test
    fun `declared base url fields are replaced, computed ones are reported unsupported`() {
        val source = FieldSource()
        val field = HostDomain.baseUrlField(source)
        assertNotNull(field)
        field!!.set(source, "https://new.example/en")
        assertEquals("https://new.example/en", source.baseUrl)
        assertNull(HostDomain.baseUrlField(ComputedSource()))
        val minified = MinifiedSource()
        HostDomain.baseUrlField(minified)!!.set(minified, "https://new.example")
        assertEquals("https://new.example", minified.baseUrl)
        assertEquals("https://api.example", minified.a)
    }
}
