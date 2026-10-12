package org.skepsun.kototoro.ios

import kotlinx.serialization.json.*
import org.skepsun.kototoro.core.db.entity.ExternalExtensionRepoEntity
import org.skepsun.kototoro.extensions.repo.*

data class IosRepository(val kind: String, val url: String, val title: String, val fingerprint: String)
class IosExtension internal constructor(internal val value: ExtensionStoreIndex.Extension) {
    val id: String get() = value.packageName
    val title: String get() = value.name
    val version: String get() = value.versionName
    val versionCode: Long get() = value.versionCode
    val url: String get() = value.resources.jarUrl.ifBlank { value.resources.apkUrl }
    val languages: List<String> get() = value.sources.map { it.language }.distinct()
    val isAdult: Boolean get() = value.contentWarning == ExtensionStoreIndex.ContentWarning.NSFW
    val sourceNames: List<String> get() = value.sources.map { it.name }
    val iconUrl: String get() = value.resources.iconUrl
}
class IosRepositoryCatalog internal constructor(
    val url: String, val kind: String, val title: String, val signingKey: String,
    val externalList: String?, val website: String, internal val rows: List<ExtensionStoreIndex.Extension>,
    val resourceBaseUrl: String = url,
) {
    val extensions: List<IosExtension> get() = rows.map(::IosExtension)
}

/** Shared decoders and existing repository table; Swift supplies URL loading and SHA-256. */
class IosRepositories(private val library: IosLibrary) {
    /** Repositories saved before names were derived like Android's show "owner/repo" instead of the folder name. */
    @Throws(Exception::class)
    suspend fun saved(): List<IosRepository> = library.database.getExternalExtensionRepoDao().getAll()
        .filter { it.type.name in supported }.map {
            val title = if (it.name.isBlank() || it.name == "repo") repositoryName(it.baseUrl) else it.name
            IosRepository(it.type.name, it.baseUrl, title, it.signingKeyFingerprint)
        }

    @Throws(Exception::class)
    fun decode(bytes: ByteArray, protobuf: Boolean, url: String, kind: String): IosRepositoryCatalog {
        require(kind in supported)
        val payload = gunzipExtensionStoreBytes(bytes)
        val legacy = !protobuf && payload.decodeToString().trimStart().startsWith("[")
        val index = if (legacy) decodeLegacyExtensionIndex(payload, repositoryName(url))
            else decodeExtensionStoreIndex(payload, protobuf)
        require(index.name.isNotBlank())
        return IosRepositoryCatalog(url, kind, index.name, index.signingKey, index.extensionListUrl,
            index.contact.website, index.extensionList?.extensions.orEmpty())
    }

    @Throws(Exception::class)
    fun external(catalog: IosRepositoryCatalog, bytes: ByteArray, protobuf: Boolean, url: String): IosRepositoryCatalog {
        val payload = gunzipExtensionStoreBytes(bytes)
        val legacy = !protobuf && payload.decodeToString().trimStart().startsWith("[")
        var signingKey = catalog.signingKey
        val rows = if (legacy) {
            decodeLegacyExtensionIndex(payload, catalog.title).extensionList?.extensions.orEmpty()
        } else {
            val fromIndex = if (protobuf) {
                runCatching { decodeExtensionStoreIndex(payload, true) }.getOrNull()
                    ?.takeIf { !it.extensionList?.extensions.isNullOrEmpty() }
            } else null
            if (signingKey.isBlank() && fromIndex != null) {
                signingKey = fromIndex.signingKey
            }
            fromIndex?.extensionList?.extensions ?: decodeExtensionStoreList(payload, protobuf).extensions
        }
        return IosRepositoryCatalog(catalog.url, catalog.kind, catalog.title, signingKey, null, catalog.website,
            rows, url)
    }

    @Throws(Exception::class)
    fun release(json: String, url: String): IosRepositoryCatalog {
        val root = Json.parseToJsonElement(json).jsonObject
        val code = root.getValue("id").jsonPrimitive.long
        val version = root.getValue("tag_name").jsonPrimitive.content
        val rows = root.getValue("assets").jsonArray.mapNotNull {
            val asset = it.jsonObject
            val name = asset.getValue("name").jsonPrimitive.content
            if (!name.endsWith(".jar", true)) return@mapNotNull null
            val id = "github." + url.removePrefix("https://github.com/").replace('/', '.') + "." + name.removeSuffix(".jar")
            ExtensionStoreIndex.Extension(name, id, ExtensionStoreIndex.Resources(
                asset.getValue("browser_download_url").jsonPrimitive.content, ""), "parser", code, version,
                ExtensionStoreIndex.ContentWarning.UNSPECIFIED, emptyList())
        }
        require(rows.isNotEmpty()) { "此 GitHub 发布没有 JAR 插件" }
        return IosRepositoryCatalog(url, "JAR", root["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: version,
            "", null, url, rows)
    }

    @Throws(Exception::class)
    suspend fun save(catalog: IosRepositoryCatalog, fingerprint: String) {
        require(fingerprint.matches(Regex("[a-f0-9]{64}")))
        require(catalog.extensions.size <= 20000 && catalog.extensions.map { it.id }.distinct().size == catalog.extensions.size)
        val type = ExternalExtensionType.valueOf(catalog.kind)
        val dao = library.database.getExternalExtensionRepoDao()
        val previous = dao.get(type, catalog.url)
        require(previous == null || previous.signingKeyFingerprint == fingerprint) { "仓库签名信息已变化，请核对地址" }
        val duplicate = dao.getByFingerprint(type, fingerprint)
        require(duplicate == null || duplicate.baseUrl == catalog.url) { "此仓库已从其他地址添加" }
        val time = kotlin.time.Clock.System.now().toEpochMilliseconds()
        dao.upsert(ExternalExtensionRepoEntity(type, catalog.url, catalog.title, null, catalog.website, fingerprint,
            previous?.createdAt ?: time, time, time, null, null))
    }

    @Throws(Exception::class)
    suspend fun remove(repository: IosRepository) {
        library.database.getExternalExtensionRepoDao().delete(ExternalExtensionType.valueOf(repository.kind), repository.url)
    }

    internal companion object {
        private val supported = setOf("MIHON", "ANIYOMI", "TSUNDOKU", "JAR")

        /**
         * Android's `deriveRepoName`: GitHub-hosted indexes (raw.githubusercontent.com / github.com) are named
         * "owner/repo", since their index usually sits in a branch or folder called `repo`; otherwise the index's
         * folder, then the host.
         */
        internal fun repositoryName(url: String): String {
            val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase()
            val segments = url.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')
                .split('/').filter(String::isNotEmpty)
            return when {
                segments.size >= 2 && (host.endsWith("githubusercontent.com") || host == "github.com") ->
                    "${segments[0]}/${segments[1]}"
                segments.size >= 2 -> segments[segments.size - 2]
                segments.isNotEmpty() && !segments.last().contains('.') -> segments.last()
                else -> host
            }
        }
    }
}
