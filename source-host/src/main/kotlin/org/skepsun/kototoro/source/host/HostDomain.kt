package org.skepsun.kototoro.source.host

import org.skepsun.kototoro.core.source.SourcePreferenceKind
import org.skepsun.kototoro.core.source.SourcePreferenceNode
import org.skepsun.kototoro.core.source.SourcePreferenceUpdateStatus
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import java.lang.reflect.Field
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Request-level domain overrides: a platform that owns the extensions' shared OkHttp client (the iOS adapter) puts an
 * interceptor in front of it that sends requests for an original host to its replacement. This covers published
 * extensions whose `baseUrl` R8 folded into a constant, which no field write can change.
 */
object HostDomainRewrites {
    /** Set by the platform once its client carries the rewriting interceptor. */
    @Volatile
    var installed: Boolean = false

    private val targets = ConcurrentHashMap<String, String>()

    /** `scheme://host[:port]` requests for [host] go to instead, or null to keep the host. */
    fun target(host: String): String? = targets[host.lowercase()]

    fun set(host: String, target: String?) {
        if (target == null) targets.remove(host.lowercase()) else targets[host.lowercase()] = target
    }
}

/**
 * Kototoro's own domain setting for Mihon-family HTTP sources, next to [HostUserAgent]: parser plugins have
 * `ConfigKey.Domain`, extensions only their own mirror choices (if any). Most extensions declare
 * `override val baseUrl = "https://…"`, a field every request is built from (R8 renames it in published
 * extensions, so it is found by holding the value `getBaseUrl()` returns), and the host replaces that field's value
 * with the configured domain (keeping the original scheme and path). Where no field holds it (constant-folded or
 * computed), [HostDomainRewrites] redirects the requests instead, if the platform installed it. Stored in `source_<id>`
 * preferences under `domain_override`; blank restores the extension's address.
 */
internal object HostDomain {
    const val NODE_ID = "host:domain"
    const val KEY = "domain_override"
    private val stateOwner = Any()

    private class Applied(val baseField: Field?, val original: String?) {
        val host: String? = original?.let { runCatching { URI(it).host }.getOrNull() }
        val supported: Boolean
            get() = original != null && host != null && (baseField != null || HostDomainRewrites.installed)
    }

    fun stored(lease: MihonJarRegistry.SourceLease, context: Any): String? =
        (MihonReflection.call(HostUserAgent.preferences(lease, context), "getString", KEY, null) as? String)
            ?.trim()?.ifEmpty { null }

    private fun state(lease: MihonJarRegistry.SourceLease): Applied = lease.state(stateOwner) {
        val original = runCatching { lease.instance.javaClass.getMethod("getBaseUrl").invoke(lease.instance) as? String }
            .getOrNull()
        Applied(baseUrlField(lease.instance), original)
    }

    /** Installs the stored domain (or restores the original address); cheap when nothing changed. */
    fun apply(lease: MihonJarRegistry.SourceLease, context: Any) {
        val state = state(lease)
        val original = state.original ?: return
        val host = state.host ?: return
        val override = stored(lease, context)?.let { rewrite(original, it) }
        state.baseField?.let { field -> (override ?: original).let { if (field.get(lease.instance) != it) field.set(lease.instance, it) } }
        HostDomainRewrites.set(host, override?.let { URI(it).let { uri -> "${uri.scheme}://${uri.rawAuthority}" } })
    }

    fun node(lease: MihonJarRegistry.SourceLease, context: Any): SourcePreferenceNode {
        val state = state(lease)
        val supported = state.supported
        val host = state.host.orEmpty()
        return SourcePreferenceNode(
            NODE_ID, KEY, "域名",
            if (supported) "留空使用扩展默认地址" + (if (host.isNotBlank()) "：$host" else "")
            else "此平台无法改写该扩展的地址（如有镜像，请在上方扩展设置中切换）",
            SourcePreferenceKind.TEXT, enabled = supported, visible = true,
            value = SourcePreferenceValue.Text(stored(lease, context).orEmpty()),
            defaultValue = SourcePreferenceValue.Text(""),
        )
    }

    fun update(lease: MihonJarRegistry.SourceLease, context: Any, value: SourcePreferenceValue): SourcePreferenceUpdateStatus {
        val text = (value as? SourcePreferenceValue.Text)?.value?.trim() ?: return SourcePreferenceUpdateStatus.REJECTED
        val state = state(lease)
        if (!state.supported || state.original == null) return SourcePreferenceUpdateStatus.REJECTED
        if (text.isNotEmpty() && rewrite(state.original, text) == null) return SourcePreferenceUpdateStatus.REJECTED
        val editor = requireNotNull(MihonReflection.call(HostUserAgent.preferences(lease, context), "edit"))
        if (text.isEmpty()) MihonReflection.call(editor, "remove", KEY) else MihonReflection.call(editor, "putString", KEY, text)
        val saved = MihonReflection.call(editor, "commit") as Boolean
        runCatching { apply(lease, context) }
        return if (saved) SourcePreferenceUpdateStatus.ACCEPTED else SourcePreferenceUpdateStatus.PERSISTENCE_FAILED
    }

    /**
     * [original] with its host (and port) replaced by [domain]; a scheme in [domain] replaces the original one.
     * "mirror.example.org", "https://mirror.example.org/" and "mirror.example.org:8080" are accepted; null otherwise.
     */
    fun rewrite(original: String, domain: String): String? {
        val base = runCatching { URI(original) }.getOrNull() ?: return null
        val input = domain.trim().let { if ("://" in it) it else "${base.scheme ?: "https"}://$it" }
        val target = runCatching { URI(input) }.getOrNull() ?: return null
        val host = target.host?.takeIf { it.isNotBlank() && ' ' !in it } ?: return null
        if (target.scheme !in setOf("http", "https") || !target.rawPath.isNullOrEmpty() && target.rawPath != "/") return null
        val port = if (target.port >= 0) ":${target.port}" else ""
        return "${target.scheme}://$host$port${base.rawPath.orEmpty()}" +
            (base.rawQuery?.let { "?$it" } ?: "")
    }

    /**
     * The backing field of `override val baseUrl = …`: named `baseUrl`, or (minified) the String instance field that
     * holds what `getBaseUrl()` returns. Confirmed by writing a probe value and reading it back through the getter,
     * so a computed `baseUrl` (which ignores the field) is reported as unsupported.
     */
    fun baseUrlField(instance: Any): Field? {
        val current = runCatching { instance.javaClass.getMethod("getBaseUrl").invoke(instance) as? String }.getOrNull()
            ?: return null
        val candidates = generateSequence<Class<*>>(instance.javaClass) { it.superclass }
            .takeWhile { it != Any::class.java && !it.name.startsWith("eu.kanade.tachiyomi.") }
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.type == String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .sortedByDescending { it.name == "baseUrl" }
        for (field in candidates) {
            if (!field.trySetAccessible() || field.get(instance) != current) continue
            val probe = "$current#probe"
            val confirmed = runCatching {
                field.set(instance, probe)
                instance.javaClass.getMethod("getBaseUrl").invoke(instance) == probe
            }.getOrDefault(false)
            runCatching { field.set(instance, current) }
            if (confirmed) return field
        }
        return null
    }
}
