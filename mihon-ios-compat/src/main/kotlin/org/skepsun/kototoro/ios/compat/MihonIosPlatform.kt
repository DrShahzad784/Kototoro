package org.skepsun.kototoro.ios.compat

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import eu.kanade.tachiyomi.createAppModule
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.OkHttpClient
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.skepsun.kototoro.core.source.SourcePreferenceStore
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import org.skepsun.kototoro.source.host.MihonPreferenceBridge
import org.skepsun.kototoro.source.host.SourceHostPreferenceUiPlatform
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.KoinRegistrar
import java.io.File
import java.net.CookieHandler
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** iOS platform for the pinned Mobile API. WKWebView/graphics/JavaScript are not enabled by this adapter. */
class MihonIosPlatform : SourceHostPreferenceUiPlatform {
    private var application: IosApplication? = null
    private var preferences: MihonPreferenceBridge? = null
    private var container: KoinApplication? = null
    private var client: OkHttpClient? = null
    private var thread: Thread? = null
    private val looper = AtomicReference<Looper>()
    private var previousInjekt: InjektScope? = null
    private var previousCookies: CookieHandler? = null
    private var ownedCookies: CookieHandler? = null
    private var closed = false

    override fun initialize(): ClassLoader = error("Persistent preferences are required by the iOS platform")

    @Synchronized
    override fun initialize(preferences: SourcePreferenceStore): ClassLoader {
        check(!closed && application == null)
        synchronized(processOwner) {
            check(!claimed && GlobalContext.getOrNull() == null) { "The JVM already owns a compatibility platform" }
            claimed = true
        }
        val root = (preferences as? FileSourcePreferenceStore)?.directory?.parent?.resolve("compat")
            ?: error("File-backed preferences are required by the iOS platform")
        val ready = CountDownLatch(1)
        val startupFailure = AtomicReference<Throwable>()
        val main = Thread({
            try {
                Looper.prepare()
                val current = requireNotNull(Looper.myLooper())
                looper.set(current)
                Looper.setMainLooperForTest(current)
                ready.countDown()
                Looper.loop()
            } catch (error: Throwable) { startupFailure.set(error); ready.countDown() }
        }, "Kototoro iOS compatibility main").apply { isDaemon = true }
        thread = main
        main.start()
        check(ready.await(10, TimeUnit.SECONDS)) { "iOS compatibility Looper startup timed out" }
        startupFailure.get()?.let { throw IllegalStateException("iOS compatibility Looper failed", it) }
        val handler = Handler(requireNotNull(looper.get()))
        val bridge = MihonPreferenceBridge(javaClass.classLoader, preferences, dispatchListener = { action ->
            check(handler.post(action)) { "iOS compatibility callback was rejected" }
        }).also { this.preferences = it }
        val app = IosApplication(root, bridge).also { application = it }
        val koin = KoinApplication.init().also { container = it }
        koin.modules(createAppModule(app), module { single<Context> { app } })
        GlobalContext.startKoin(koin)
        previousInjekt = Injekt
        Injekt = InjektScope(KoinRegistrar())
        WebView.setProviderFactory { throw UnsupportedOperationException("WKWebView is not connected") }
        app.onCreate()
        previousCookies = CookieHandler.getDefault()
        val helper = try { koin.koin.get<NetworkHelper>() } finally {
            ownedCookies = CookieHandler.getDefault().takeUnless { it === previousCookies }
        }
        client = helper.client
        return javaClass.classLoader
    }

    override fun preferenceContext(): Any = requireNotNull(application).also { check(!closed) }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        client?.let {
            release { it.dispatcher.cancelAll() }
            release { it.connectionPool.evictAll() }
            release { it.cache?.close() }
            release { it.dispatcher.executorService.shutdown() }
        }
        if (ownedCookies != null && CookieHandler.getDefault() === ownedCookies) {
            release { CookieHandler.setDefault(previousCookies) }
        }
        previousInjekt?.let { release { Injekt = it } }
        container?.let { owned ->
            release { if (GlobalContext.getOrNull() === owned.koin) GlobalContext.stopKoin() else owned.close() }
        }
        release { preferences?.close() }
        release { looper.get()?.quitSafely() }
        thread?.let { main ->
            if (Thread.currentThread() !== main) release { main.join(10000); check(!main.isAlive) }
        }
        if (looper.get() != null && Looper.getMainLooper() === looper.get()) {
            release { Looper.clearMainLooperForTest() }
        }
        application = null; preferences = null; client = null; container = null; thread = null
        failure?.let { throw it }
    }

    companion object {
        private val processOwner = Any()
        private var claimed = false
    }
}

private class IosApplication(private val root: Path, private val preferences: MihonPreferenceBridge) :
    eu.kanade.tachiyomi.App() {
    private val files = directory("files")
    private val cache = directory("cache")
    private val backup = directory("no-backup")
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferences.getSharedPreferences(name) as SharedPreferences
    override fun getApplicationContext(): Context = this
    override fun getClassLoader(): ClassLoader = javaClass.classLoader
    override fun getPackageName(): String = "org.skepsun.kototoro.ios"
    override fun getMainLooper(): Looper = requireNotNull(Looper.getMainLooper())
    override fun getDataDir(): File = root.toFile()
    override fun getFilesDir(): File = files
    override fun getCacheDir(): File = cache
    override fun getCodeCacheDir(): File = cache
    override fun getNoBackupFilesDir(): File = backup
    override fun getSystemService(name: String): Any = throw UnsupportedOperationException("iOS service: $name")
    override fun getResources(): android.content.res.Resources = throw UnsupportedOperationException("iOS resources")
    override fun getPackageManager(): android.content.pm.PackageManager =
        throw UnsupportedOperationException("iOS packages")
    private fun directory(name: String): File = Files.createDirectories(root.resolve(name)).toFile()
}
