package org.skepsun.kototoro.ios

import kotlin.test.Test
import kotlin.test.assertEquals

class IosRepositoryNameTest {
    @Test
    fun legacyIndexesAreNamedLikeAndroid() {
        val name = IosRepositories::repositoryName
        assertEquals("LittleSurvival/copymanga-copy20",
            name("https://raw.githubusercontent.com/LittleSurvival/copymanga-copy20/repo/index.min.json"))
        assertEquals("skepsun/kototoro-parsers", name("https://raw.githubusercontent.com/skepsun/kototoro-parsers/repo/index.min.json"))
        assertEquals("keiyoushi/extensions", name("https://github.com/keiyoushi/extensions/raw/repo/index.pb"))
        assertEquals("extensions", name("https://example.org/extensions/index.min.json"))
        assertEquals("example.org", name("https://example.org/index.min.json"))
    }
}
