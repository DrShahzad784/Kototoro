package org.skepsun.kototoro.source.host

import kotlinx.serialization.Serializable
import org.skepsun.kototoro.core.source.MihonJarIdentity

/** Local desktop launch configuration. Relative paths resolve against the configuration's directory. */
@Serializable
data class SourceHostConfig(
    val platformClass: String,
    val jars: List<SourceHostJar> = emptyList(),
    val imageDirectory: String? = null,
    val preferenceDirectory: String? = null,
    /** Non-Tachiyomi plugins (kototoro / kotatsu / UMA parser JARs); served by a [SourceHostRuntimePlatform]. */
    val plugins: List<SourceHostPlugin> = emptyList(),
    /**
     * Where verified extensions' source lists are cached by SHA-256. Set, a start-up load registers catalogued
     * extensions without constructing them; each is constructed when one of its sources is first called.
     */
    val catalogDirectory: String? = null,
)

@Serializable
data class SourceHostJar(val path: String, val identity: MihonJarIdentity)

/** A plugin JAR another ecosystem host loads; [sha256] is verified before loading when present. */
@Serializable
data class SourceHostPlugin(val path: String, val id: String, val sha256: String? = null, val versionCode: Long = 0)
