package org.skepsun.kototoro.ios.compat

import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.skepsun.kototoro.ios.compat.IosWebKit.string
import xyz.nulldev.androidcompat.webkit.CookieManagerImpl

/**
 * `CookieManager.getInstance()` with WKWebView pages in the picture. The Java cookie store stays the one OkHttp
 * uses (AndroidCompat's manager over the NetworkHelper cookie handler); WebKit's store is kept in step with it:
 * cookies set here go to both, cookies a page received are read from WebKit and copied over (Cloudflare's clearance,
 * a site's session), so extension requests and pages share them as on Android.
 */
internal class IosCookieManager : CookieManager() {
    private val java = CookieManagerImpl()

    override fun setAcceptCookie(accept: Boolean) = java.setAcceptCookie(accept)
    override fun acceptCookie(): Boolean = java.acceptCookie()
    override fun setAcceptThirdPartyCookies(webview: WebView?, accept: Boolean) = java.setAcceptThirdPartyCookies(webview, accept)
    override fun acceptThirdPartyCookies(webview: WebView?): Boolean = java.acceptThirdPartyCookies(webview)

    override fun setCookie(url: String, value: String?) {
        if (value == null || !java.acceptCookie()) return
        java.setCookie(url, value)
        runCatching { IosWebKit.command("cookies.set", payload = IosWebKit.payload("url" to url, "header" to value)) }
    }

    override fun setCookie(url: String, value: String?, callback: ValueCallback<Boolean>?) {
        setCookie(url, value)
        callback?.onReceiveValue(true)
    }

    /** Both stores' cookies for [url], WebKit's winning for the same name (the page set it last). */
    override fun getCookie(url: String): String? {
        importFromWebKit(url)
        return java.getCookie(url)
    }

    /** Copies the cookies WebKit holds for [url] into the Java store. */
    fun importFromWebKit(url: String) {
        val listed = runCatching {
            IosWebKit.json.parseToJsonElement(IosWebKit.command("cookies.list", payload = IosWebKit.payload("url" to url)))
        }.getOrNull() as? JsonArray ?: return
        for (item in listed) {
            val cookie = item as? JsonObject ?: continue
            val header = cookie.string("header") ?: continue
            java.setCookie(cookie.string("url") ?: url, header)
        }
    }

    /** Copies the Java store's cookies for [url] into WebKit before a page loads. */
    fun exportToWebKit(url: String) {
        val cookies = java.getCookie(url)?.split(';')?.map(String::trim)?.filter { '=' in it } ?: return
        for (cookie in cookies) {
            runCatching { IosWebKit.command("cookies.set", payload = IosWebKit.payload("url" to url, "header" to cookie)) }
        }
    }

    override fun removeSessionCookie() = removeSessionCookies(null)

    override fun removeSessionCookies(callback: ValueCallback<Boolean>?) {
        java.removeSessionCookies(null)
        val cleared = runCatching {
            IosWebKit.command("cookies.clear", payload = IosWebKit.payload("sessionOnly" to true))
        }.isSuccess
        callback?.onReceiveValue(cleared)
    }

    override fun removeAllCookie() = removeAllCookies(null)

    override fun removeAllCookies(callback: ValueCallback<Boolean>?) {
        java.removeAllCookies(null)
        val cleared = runCatching {
            IosWebKit.command("cookies.clear", payload = IosWebKit.payload("sessionOnly" to false))
        }.isSuccess
        callback?.onReceiveValue(cleared)
    }

    override fun hasCookies(): Boolean = java.hasCookies() ||
        runCatching { IosWebKit.json.parseToJsonElement(IosWebKit.command("cookies.list")) as? JsonArray }
            .getOrNull()?.isNotEmpty() == true

    override fun removeExpiredCookie() = java.removeExpiredCookie()
    override fun flush() = java.flush()
    override fun allowFileSchemeCookiesImpl(): Boolean = java.allowFileSchemeCookiesImpl()
    override fun setAcceptFileSchemeCookiesImpl(accept: Boolean) = java.setAcceptFileSchemeCookiesImpl(accept)
}
