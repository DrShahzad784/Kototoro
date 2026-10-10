package org.skepsun.kototoro.tvbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TvBoxUrlsTest {
    @Test
    fun resolvesLikeOkHttp() {
        val base = "https://cms.example/api.php/provide/vod/?ac=list"
        assertEquals("https://cms.example/up/a.jpg", TvBoxUrls.resolve(base, "/up/a.jpg"))
        assertEquals("https://cms.example/api.php/provide/vod/a.jpg", TvBoxUrls.resolve(base, "a.jpg"))
        assertEquals("https://cms.example/api.php/provide/a.jpg", TvBoxUrls.resolve(base, "../a.jpg"))
        assertEquals("https://img.example/a.jpg", TvBoxUrls.resolve(base, "//img.example/a.jpg"))
        assertEquals("https://v.example/1.m3u8", TvBoxUrls.resolve(base, "https://v.example/1.m3u8;md5;abc"))
        assertEquals("https://cms.example/api.php/provide/vod/?x=1", TvBoxUrls.resolve(base, "?x=1"))
    }

    @Test
    fun encodesLikeAndroidUri() {
        assertEquals("a%20b%2Fc%3F*'()~", TvBoxUrls.encode("a b/c?*'()~"))
        assertEquals("%E4%B8%AD", TvBoxUrls.encode("中"))
        assertEquals("a b/c?中", TvBoxUrls.decode("a+b%2Fc%3F%E4%B8%AD"))
        assertEquals("https://a.example/x?y=1&pg=2", TvBoxUrls.withQuery("https://a.example/x?y=1#frag", listOf("pg" to "2")))
    }

    @Test
    fun internationalHostsMatchJavaIdn() {
        for (host in listOf("饭太硬.net", "www.饭太硬.art", "肥猫.net", "我不是.摸鱼儿.top", "bücher.example", "mañana.com")) {
            assertEquals(
                "http://${java.net.IDN.toASCII(host)}:88/tv?x=中",
                TvBoxUrls.asciiHost("http://$host:88/tv?x=中"),
                host,
            )
        }
        // Supplementary code points (java.net.IDN's IDNA2003 tables reject emoji; RFC 3492 sample value).
        assertEquals("http://xn--ls8h.la/", TvBoxUrls.asciiHost("http://💩.la/"))
        assertEquals("https://plain.example/a", TvBoxUrls.asciiHost("https://plain.example/a"))
    }

    @Test
    fun playableStreams() {
        assertTrue(TvBoxUrls.isPlayableStream("https://v.example/index.m3u8?sign=1"))
        assertTrue(TvBoxUrls.isPlayableStream("rtmp://live.example/a"))
        assertFalse(TvBoxUrls.isPlayableStream("https://web.example/play/7"))
        assertEquals("v.example", TvBoxUrls.host("https://user@V.example:8443/a"))
    }

    @Test
    fun xmlParserKeepsCdataAndDecodesEntities() {
        val root = MiniXml.parse("<a x='1 &amp; 2'><b>t&#x4E2D;</b><b><![CDATA[<raw>]]></b><c/></a>")!!
        val a = root.children.single()
        assertEquals("1 & 2", a.attribute("x"))
        assertEquals(listOf("t中", "<raw>"), a.descendants("b").map { it.textContent })
        assertEquals("t中", a.childText("b"))
    }
}
