package org.skepsun.kototoro.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosSubtitlesTest {
    @Test
    fun webVttSkipsMetadataBlocksAndMarkup() {
        val subtitles = IosSubtitles.parse(
            "\uFEFFWEBVTT - fixture\r\n\r\nNOTE a comment\r\n00:00:09.000 --> 00:00:10.000\r\nlate\r\n\r\n" +
                "STYLE\n::cue { color: red }\n\nintro\n00:01.500 --> 00:03.000 align:start position:10%\n" +
                "<v Mio><i>Hello</i> &amp; <c.yellow>welcome</c></v>\n{\\an8}second line\n\n" +
                "00:00:02.000 --> 00:00:04.250\nOverlap\n",
        )
        assertEquals(listOf(1_500L, 2_000L, 9_000L), subtitles.cues.map { it.startMs })
        assertNull(subtitles.textAt(1_499))
        assertEquals("Hello & welcome\nsecond line", subtitles.textAt(1_500))
        assertEquals("Hello & welcome\nsecond line\nOverlap", subtitles.textAt(2_500))
        assertEquals("Overlap", subtitles.textAt(3_000))
        assertNull(subtitles.textAt(4_250))
    }

    @Test
    fun subRipWithCommaFractionsAndUnsortedCues() {
        val subtitles = IosSubtitles.parse(
            "2\n00:00:05,000 --> 00:00:06,000\n第二句\n\n1\n00:00:01,000 --> 00:00:02,5\n<b>第一句</b>\n多行\n",
        )
        assertEquals(listOf(1_000L, 5_000L), subtitles.cues.map { it.startMs })
        assertEquals(2_500L, subtitles.cues.first().endMs)
        assertEquals("第一句\n多行", subtitles.textAt(1_200))
        assertEquals("第二句", subtitles.textAt(5_999))
        assertNull(subtitles.textAt(3_000))
    }

    @Test
    fun assUsesTheEventFormatAndKeepsCommasInText() {
        val subtitles = IosSubtitles.parse(
            """
            [Script Info]
            Title: fixture
            Dialogue: not an event
            [V4+ Styles]
            Format: Name, Fontname
            Style: Default,Arial
            [Events]
            Format: Layer, Start, End, Style, Text
            Comment: 0,0:00:00.00,0:00:09.00,Default,hidden
            Dialogue: 0,0:00:01.50,0:00:03.00,Default,{\pos(10,20)\b1}Hi, there\Nnext\hline
            Dialogue: 0,0:01:00.0,0:01:01.00,Default,
            """.trimIndent(),
            "https://anime.invalid/sub.ass?token=1",
        )
        assertEquals(1, subtitles.cues.size)
        assertEquals("Hi, there\nnext line", subtitles.textAt(2_000))
        assertNull(subtitles.textAt(500))
    }

    @Test
    fun unreadableContentHasNoCues() {
        assertTrue(IosSubtitles.parse("<html>404</html>").isEmpty)
        assertTrue(IosSubtitles.parse("1\nnot --> a time\ntext").isEmpty)
    }
}
