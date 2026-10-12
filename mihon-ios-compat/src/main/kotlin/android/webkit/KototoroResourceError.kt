package android.webkit

/**
 * A WebKit load failure for `WebViewClient.onReceivedError`. AndroidCompat keeps WebResourceError's constructor
 * package-private, so the subclass lives in its package (same class loader at runtime).
 */
class KototoroResourceError(private val code: Int, private val text: String) : WebResourceError() {
    override fun getErrorCode(): Int = code
    override fun getDescription(): CharSequence = text
}
