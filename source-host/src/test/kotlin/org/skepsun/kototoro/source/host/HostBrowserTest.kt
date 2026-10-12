package org.skepsun.kototoro.source.host

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skepsun.kototoro.core.source.SourceBrowserSession
import org.skepsun.kototoro.core.source.SourceOperationUnsupportedException
import java.io.IOException

class HostBrowserTest {
    private class Jar : CookieJar {
        val saved = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { saved += cookies }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = saved.filter { it.matches(url) }
    }

    /** The members a Mihon `HttpSource` exposes, resolved by name as on a real extension. */
    @Suppress("unused")
    class FakeSource(val client: OkHttpClient, val baseUrl: String, val headers: Headers)

    @Test
    fun `browser cookies land in the source client's jar and its User-Agent is reported`() {
        val jar = Jar()
        val source = FakeSource(OkHttpClient.Builder().cookieJar(jar).build(), "https://site.test",
            Headers.headersOf("User-Agent", "Extension/1.0"))
        assertEquals(SourceBrowserSession("https://site.test", "Extension/1.0"), HostBrowser.session(source))
        val count = HostBrowser.importCookies(source, "https://site.test/", listOf(
            "cf_clearance=abc; Path=/; Domain=site.test; Secure; HttpOnly",
            "not a cookie",
        ))
        assertEquals(1, count)
        assertEquals(listOf("cf_clearance=abc"), jar.loadForRequest("https://site.test/list".toHttpUrl())
            .map { "${it.name}=${it.value}" })
    }

    @Test
    fun `sources without an http base url have no browser session`() {
        val source = FakeSource(OkHttpClient(), "", Headers.headersOf())
        assertThrows<SourceOperationUnsupportedException> { HostBrowser.session(source) }
    }

    class CloudFlareProtectedException(val url: String) : IOException("protected")

    @Test
    fun `parser and server network Cloudflare failures are recognised, others are not`() {
        assertEquals("https://site.test", HostChallenges.of(CloudFlareProtectedException("https://site.test/a?token=1"))?.origin)
        val server = HostChallenges.of(IOException("Cloudflare bypass currently disabled"))
        assertNull(server?.origin)
        assertEquals(true, server != null)
        assertNull(HostChallenges.of(IOException("HTTP error 403")))
    }
}
