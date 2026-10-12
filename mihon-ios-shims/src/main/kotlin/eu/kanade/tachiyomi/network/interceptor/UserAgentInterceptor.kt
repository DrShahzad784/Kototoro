package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/** The Mobile API's user-agent ABI; explicit requests win unless the host supplies an override. */
class UserAgentInterceptor(private val defaultUserAgent: () -> String) : Interceptor {
    @Volatile private var forced: String? = null
    fun effectiveUserAgent(request: String?): String = forced ?: request?.takeIf { it.isNotBlank() } ?: defaultUserAgent()
    fun forceUserAgent(value: String) { require(value.isNotBlank()); forced = value }
    fun clearForcedUserAgent() { forced = null }
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(request.newBuilder().header("User-Agent", effectiveUserAgent(request.header("User-Agent"))).build())
    }
    companion object {
        const val BUILT_IN_DEFAULT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1"
    }
}
