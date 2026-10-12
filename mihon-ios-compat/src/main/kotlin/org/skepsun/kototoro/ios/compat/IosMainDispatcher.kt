package org.skepsun.kototoro.ios.compat

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import kotlin.coroutines.CoroutineContext

/**
 * `Dispatchers.Main` on the compatibility main Looper, as kotlinx-coroutines-android provides it on a device.
 * Extensions switch to it to create and drive WebViews (Comix computes its API token in one); without a factory the
 * call fails with "Module with the Main dispatcher is missing".
 */
@OptIn(InternalCoroutinesApi::class)
class IosMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int get() = Int.MAX_VALUE / 2

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher =
        LooperDispatcher(isImmediate = false)

    override fun hintOnError(): String = "The iOS compatibility main Looper starts with the platform"
}

private class LooperDispatcher(private val isImmediate: Boolean) : MainCoroutineDispatcher() {
    private val handler by lazy { Handler(requireNotNull(Looper.getMainLooper()) { "Compatibility main Looper is not running" }) }

    override val immediate: MainCoroutineDispatcher
        get() = if (isImmediate) this else LooperDispatcher(isImmediate = true)

    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        !isImmediate || Looper.myLooper() !== Looper.getMainLooper()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        check(handler.post(block)) { "Compatibility main Looper has quit" }
    }

    override fun toString(): String = if (isImmediate) "Dispatchers.Main.immediate" else "Dispatchers.Main"
}
