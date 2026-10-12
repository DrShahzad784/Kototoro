package org.skepsun.kototoro.source.host

import org.skepsun.kototoro.core.source.SourceBrowserSession
import org.skepsun.kototoro.core.source.SourceChallengeException
import org.skepsun.kototoro.core.source.SourceInvalidArgumentException
import org.skepsun.kototoro.core.source.SourceOperationUnsupportedException
import java.lang.reflect.InvocationTargetException

/**
 * Browser verification for Mihon-family HTTP sources: the site and User-Agent a browser should verify with (the
 * clearance is bound to the User-Agent), and the browser's cookies written back into the jar of the client the
 * source's requests use. The source's OkHttp types come from its own class loader, so everything is reflective.
 */
internal object HostBrowser {
    fun session(source: Any): SourceBrowserSession {
        val base = (MihonReflection.call(source, "getBaseUrl") as? String)?.takeIf { it.startsWith("http") }
            ?: throw SourceOperationUnsupportedException()
        val headers = runCatching { MihonReflection.call(source, "getHeaders") }.getOrNull()
        val userAgent = headers?.let { runCatching { MihonReflection.call(it, "get", "User-Agent") as? String }.getOrNull() }
        return SourceBrowserSession(base, userAgent?.takeIf(String::isNotBlank))
    }

    fun importCookies(source: Any, url: String, cookies: List<String>): Int {
        val client = MihonReflection.call(source, "getClient") ?: throw SourceOperationUnsupportedException()
        return saveCookies(client, url, cookies)
    }

    /** `client.cookieJar.saveFromResponse(url, cookies.map { Cookie.parse(url, it) })`, on [client]'s own OkHttp. */
    fun saveCookies(client: Any, url: String, cookies: List<String>): Int = try {
        val loader = client.javaClass.classLoader
        val httpUrlType = loader.loadClass("okhttp3.HttpUrl")
        val urlFactory = httpUrlType.getField("Companion").get(null)
        val httpUrl = urlFactory.javaClass.getMethod("parse", String::class.java).invoke(urlFactory, url)
            ?: throw SourceInvalidArgumentException()
        val cookieType = loader.loadClass("okhttp3.Cookie")
        val cookieFactory = cookieType.getField("Companion").get(null)
        val parse = cookieFactory.javaClass.getMethod("parse", httpUrlType, String::class.java)
        val parsed = cookies.mapNotNull { parse.invoke(cookieFactory, httpUrl, it) }
        val jar = client.javaClass.getMethod("cookieJar").invoke(client)
        loader.loadClass("okhttp3.CookieJar").getMethod("saveFromResponse", httpUrlType, List::class.java)
            .invoke(jar, httpUrl, parsed)
        parsed.size
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
}

/**
 * The host libraries' own Cloudflare failures, mapped to the protocol's challenge error: kotatsu/kototoro parsers'
 * `CloudFlareProtectedException` (which knows the URL), and the server network stack's interceptor, which gives up
 * with "Cloudflare bypass currently disabled" when no FlareSolverr is configured (always, on iOS).
 */
internal object HostChallenges {
    fun of(error: Throwable): SourceChallengeException? {
        val type = error.javaClass.name
        val message = error.message.orEmpty()
        return when {
            type.contains("CloudFlareProtected", ignoreCase = true) -> SourceChallengeException(url(error))
            type.endsWith("CloudflareBypassException") ||
                message.contains("Cloudflare bypass currently disabled", ignoreCase = true) -> SourceChallengeException(null)
            else -> null
        }
    }

    private fun url(error: Throwable): String? =
        runCatching { error.javaClass.getMethod("getUrl").invoke(error) as? String }.getOrNull()
}
