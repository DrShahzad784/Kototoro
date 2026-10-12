package org.skepsun.kototoro.core.extensions

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.parsers.model.ContentSource
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GlobalExtensionManagerReloadTest {

    @TempDir
    lateinit var directory: File

    @Test
    fun `startup scan cannot overwrite newer sync reload`() {
        val context = mockk<Context> { every { filesDir } returns directory }
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getString(any(), any()) } returns ""
        }
        val source = mockk<ContentSource> { every { name } returns "RESTORED" }
        val plugin = LoadedJarPlugin("restored.jar", mockk(), ParserPluginArchitecture.KOTOTORO,
            mockk(), listOf(source), emptySet())
        val startupScanning = CountDownLatch(1)
        val releaseStartup = CountDownLatch(1)
        val refreshFinished = CountDownLatch(1)
        val loads = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        mockkObject(JarExtensionLoader)
        mockkStatic(PreferenceManager::class)
        try {
            every { PreferenceManager.getDefaultSharedPreferences(context) } returns prefs
            every { JarExtensionLoader.loadFromDirectory(context, any()) } answers {
                if (loads.getAndIncrement() == 0) {
                    startupScanning.countDown()
                    check(releaseStartup.await(5, TimeUnit.SECONDS))
                    emptyList()
                } else {
                    listOf(plugin)
                }
            }
            val startup = executor.submit { GlobalExtensionManager.initialize(context) }
            assertTrue(startupScanning.await(5, TimeUnit.SECONDS))
            val refresh = executor.submit {
                GlobalExtensionManager.initialize(context)
                refreshFinished.countDown()
            }
            // An overlapping refresh used to finish before the old startup scan, then get overwritten.
            refreshFinished.await(200, TimeUnit.MILLISECONDS)
            releaseStartup.countDown()
            startup.get(5, TimeUnit.SECONDS)
            refresh.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("RESTORED"), GlobalExtensionManager.contentSources.value.map { it.name })
        } finally {
            releaseStartup.countDown()
            executor.shutdownNow()
            every { JarExtensionLoader.loadFromDirectory(context, any()) } returns emptyList()
            GlobalExtensionManager.initialize(context)
            unmockkObject(JarExtensionLoader)
            unmockkStatic(PreferenceManager::class)
        }
    }
}
