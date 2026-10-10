package org.skepsun.kototoro.ios

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.source.SourcePage

/** Offline chapter manifests preserve every source-owned request field. */
class IosPageArchive {
    fun encode(pages: List<IosSourcePage>): String =
        Json.encodeToString(ListSerializer(SourcePage.serializer()), pages.map { it.value })

    @Throws(Exception::class)
    fun decode(json: String): List<IosSourcePage> =
        Json.decodeFromString(ListSerializer(SourcePage.serializer()), json).map(::IosSourcePage)
}
