package org.skepsun.kototoro.ios.compat

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.source.SourcePreferenceStore
import org.skepsun.kototoro.parserhost.ParserPlatform
import org.skepsun.kototoro.parsers.network.UserAgents

/**
 * Parser plugins run on the Mobile API's network client, so cookies (and any clearance) are shared with Mihon /
 * Aniyomi sources. Bitmap descrambling and JavaScript are not available in this JVM yet; parsers needing them fail
 * with the base [ParserPlatform] errors rather than silently returning wrong images.
 */
internal class IosParserPlatform(
    private val client: OkHttpClient,
    override val preferences: SourcePreferenceStore,
) : ParserPlatform {
    override val httpClient: OkHttpClient get() = client
    override val cookieJar: CookieJar get() = client.cookieJar
    override val defaultUserAgent: String get() = UserAgents.CHROME_MOBILE
}
