package org.skepsun.kototoro.ios.compat

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.source.host.HostDomainRewrites

class DomainRewriteInterceptorTest {
    private val seen = mutableListOf<Request>()
    private val client = OkHttpClient.Builder()
        .addInterceptor(DomainRewriteInterceptor())
        .addInterceptor { chain ->
            seen += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("".toResponseBody()).build()
        }
        .build()

    @AfterEach
    fun reset() = setTarget("old.example", null)

    @Test
    fun `requests for the original host go to the configured domain`() {
        setTarget("old.example", "http://mirror.example:8080")
        client.newCall(Request.Builder().url("https://old.example/manga/1?page=2")
            .header("Referer", "https://old.example/").header("Origin", "https://old.example").build()).execute().close()
        client.newCall(Request.Builder().url("https://api.example/v1").build()).execute().close()
        assertEquals("http://mirror.example:8080/manga/1?page=2", seen[0].url.toString())
        assertEquals("http://mirror.example:8080/", seen[0].header("Referer"))
        assertEquals("http://mirror.example:8080", seen[0].header("Origin"))
        assertEquals("https://api.example/v1", seen[1].url.toString())
    }

    @Test
    fun `without a configured domain requests are untouched`() {
        client.newCall(Request.Builder().url("https://old.example/a").build()).execute().close()
        assertEquals("https://old.example/a", seen.single().url.toString())
    }

    private fun setTarget(host: String, target: String?) = HostDomainRewrites.set(host, target)
}
