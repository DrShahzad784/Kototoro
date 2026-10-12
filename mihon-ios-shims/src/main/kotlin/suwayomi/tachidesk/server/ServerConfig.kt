package suwayomi.tachidesk.server

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The part of Suwayomi's server configuration its network layer reads: the FlareSolverr settings of
 * `CloudflareInterceptor` / `CFClearance`. On iOS there is no FlareSolverr; Cloudflare is handled through WebViews
 * and the app's browser, so it stays disabled.
 */
class ServerConfig {
    val flareSolverrEnabled = MutableStateFlow(false)
    val flareSolverrUrl = MutableStateFlow("http://localhost:8191")
    val flareSolverrTimeout = MutableStateFlow(60)
    val flareSolverrSessionName = MutableStateFlow("kototoro")
    val flareSolverrSessionTtl = MutableStateFlow(15)
    val flareSolverrAsResponseFallback = MutableStateFlow(false)
}

/** `ServerConfigKt.getServerConfig()`, as Suwayomi's classes call it. */
val serverConfig: ServerConfig = ServerConfig()
