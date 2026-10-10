package org.skepsun.kototoro.source.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.skepsun.kototoro.core.source.*
import org.skepsun.kototoro.dex.ApkExtensionConverter
import org.skepsun.kototoro.dex.DexJarConverter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

@Serializable
internal data class HostManagementRequest(
    val managementVersion: Int, val operation: String, val path: String = "", val sha256: String = "",
    val packageName: String = "", val versionCode: Long = 0, val ecosystem: String = "",
)
@Serializable
internal data class HostManagementResult(
    val managementVersion: Int = 1,
    val installed: List<HostInstalled> = emptyList(),
    val sourceTitles: Map<String, String> = emptyMap(),
)
@Serializable
internal data class HostInstalled(val packageName: String, val versionCode: Long, val sha256: String, val ecosystem: String,
    val bundled: Boolean = false)

/** App-owned immutable artifacts and atomic launch configuration; APK conversion runs on the embedding JVM. */
internal class SourceHostManagement(
    private val directory: Path,
    private val initial: SourceHostConfig,
    private val registry: MihonJarRegistry,
    private val installPlugins: (List<SourceHostPlugin>, beforePublish: () -> Unit) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val file = directory.resolve("installed.json")
    private var state = readInstalled(directory)
    private val seededPackages = initial.jars.map { it.identity.packageName }.toSet()
    private val seededPlugins = initial.plugins.map { it.id }.toSet()

    @Synchronized
    fun exchange(request: String): String {
        val input = json.decodeFromString<HostManagementRequest>(request)
        require(input.managementVersion == 1) { "Unsupported management protocol" }
        when (input.operation) {
            "list" -> Unit
            "install" -> install(input)
            "uninstall" -> uninstall(input)
            else -> error("Unknown management operation")
        }
        return json.encodeToString(HostManagementResult(installed = registry.installed().map {
            HostInstalled(it.metadata.packageName, it.metadata.versionCode, it.metadata.sha256, it.metadata.ecosystem.name,
                it.metadata.packageName in seededPackages)
        } + mergedPlugins(state).map { HostInstalled(it.id, it.versionCode, it.sha256.orEmpty(), "JAR", it.id in seededPlugins) },
            sourceTitles = registry.installed().flatMap { it.sources }.associate { it.source.name to it.displayName }))
    }

    private fun install(input: HostManagementRequest) {
        require(input.sha256.matches(Regex("[a-f0-9]{64}")))
        val archive = Path.of(input.path).toRealPath()
        require(archive.startsWith(directory.resolve("incoming").toRealPath()) && Files.isRegularFile(archive))
        require(hash(archive) == input.sha256) { "Downloaded artifact hash changed" }
        val artifacts = Files.createDirectories(directory.resolve("extensions"))
        val staging = Files.createTempFile(artifacts, "conversion-", ".jar")
        try {
            val report = if (ApkExtensionConverter.isApk(archive)) ApkExtensionConverter.convert(archive, staging).report
            else if (ZipFile(archive.toFile()).use { zip -> zip.entries().asSequence().any { it.name.matches(Regex("classes[0-9]*\\.dex")) } }) {
                DexJarConverter.convert(archive, staging)
            } else { Files.copy(archive, staging, StandardCopyOption.REPLACE_EXISTING); null }
            require(report?.brokenMethods.orEmpty().isEmpty()) { "Conversion produced broken methods" }
            val convertedHash = hash(staging)
            val target = artifacts.resolve("$convertedHash.jar")
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
            val hasManifest = ZipFile(target.toFile()).use { it.getEntry("AndroidManifest.xml") != null }
            val ecosystem = input.ecosystem.ifEmpty { if (hasManifest) "AUTO" else "JAR" }
            require(ecosystem in setOf("AUTO", "MIHON", "ANIYOMI", "TSUNDOKU", "JAR"))
            if (ecosystem == "JAR") {
                require(input.packageName.matches(Regex("[A-Za-z0-9_.-]{1,200}")))
                val versionCode = if (input.versionCode > 0L) input.versionCode else 1L
                val plugin = SourceHostPlugin(relativizeToDirectory(directory, target), input.packageName, convertedHash, versionCode)
                val next = state.copy(plugins = state.plugins.filterNot { it.id == plugin.id } + plugin)
                installPlugins(mergedPlugins(next)) { persist(next) }
                state = next
            } else {
                val metadata = MihonJarInspector().inspect(target)
                if (ecosystem != "AUTO") {
                    require(metadata.ecosystem.name == ecosystem) { "Extension ecosystem mismatch" }
                    require(input.packageName.matches(Regex("[A-Za-z0-9_.-]{1,200}")))
                }
                val packageName = if (ecosystem == "AUTO") metadata.packageName else input.packageName
                val versionCode = if (ecosystem == "AUTO") metadata.versionCode else input.versionCode
                val identity = MihonJarIdentity(packageName, versionCode, convertedHash)
                val next = state.copy(jars = state.jars.filterNot { it.identity.packageName == packageName } +
                    SourceHostJar(relativizeToDirectory(directory, target), identity))
                registry.replace(target, identity) { persist(next) }
                state = next
            }
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    private fun uninstall(input: HostManagementRequest) {
        // Bundled preview artifacts cannot be removed through the managed-installation API.
        if (input.ecosystem == "JAR") {
            require(input.packageName !in seededPlugins) { "Bundled parser cannot be removed" }
            val next = state.copy(plugins = state.plugins.filterNot { it.id == input.packageName })
            installPlugins(mergedPlugins(next)) { persist(next) }; state = next
        } else {
            require(input.packageName !in seededPackages) { "Bundled extension cannot be removed" }
            val next = state.copy(jars = state.jars.filterNot { it.identity.packageName == input.packageName })
            persist(next); registry.unload(input.packageName); state = next
        }
    }

    private fun mergedPlugins(next: SourceHostConfig): List<SourceHostPlugin> {
        val resolvedNext = next.plugins.map { it.copy(path = resolveInstalledPath(directory, it.path).toString()) }
        return initial.plugins.filterNot { seed -> resolvedNext.any { it.id == seed.id } } + resolvedNext
    }

    private fun persist(next: SourceHostConfig) {
        val temp = Files.createTempFile(directory, "installed-", ".json")
        Files.writeString(temp, json.encodeToString(next))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun isManagement(request: String): Boolean =
            (runCatching { Json.parseToJsonElement(request) }.getOrNull() as? JsonObject)?.containsKey("managementVersion") == true

        fun resolveInstalledPath(directory: Path, rawPath: String): Path {
            val candidate = Path.of(rawPath)
            if (!candidate.isAbsolute) {
                return directory.resolve(candidate).normalize()
            }
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize()
            }
            val fileName = candidate.fileName ?: return candidate.normalize()
            val relocated = directory.resolve("extensions").resolve(fileName).normalize()
            return if (Files.isRegularFile(relocated)) relocated else candidate.normalize()
        }

        fun relativizeToDirectory(directory: Path, target: Path): String {
            val normDir = directory.toAbsolutePath().normalize()
            val normTarget = target.toAbsolutePath().normalize()
            return if (normTarget.startsWith(normDir)) normDir.relativize(normTarget).toString() else normTarget.toString()
        }

        fun readInstalled(directory: Path): SourceHostConfig {
            val file = directory.resolve("installed.json")
            if (!Files.exists(file)) return SourceHostConfig(platformClass = "")
            val decoded = runCatching { Json.decodeFromString<SourceHostConfig>(Files.readString(file)) }
                .getOrElse { return SourceHostConfig(platformClass = "") }
            return decoded.copy(
                jars = decoded.jars.mapNotNull { jar ->
                    val resolved = resolveInstalledPath(directory, jar.path)
                    if (Files.isRegularFile(resolved)) jar.copy(path = relativizeToDirectory(directory, resolved)) else null
                },
                plugins = decoded.plugins.mapNotNull { plugin ->
                    val resolved = resolveInstalledPath(directory, plugin.path)
                    if (Files.isRegularFile(resolved)) plugin.copy(path = relativizeToDirectory(directory, resolved)) else null
                },
            )
        }

        fun hash(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(8192)
                while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
