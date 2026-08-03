package com.alastorkaneki.lyricvideomaker.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesMultipleTimestampsAndOffset() {
        val lines = LrcParser.parse(
            """
            [offset:+100]
            [00:01.50][00:03.000]Hello
            [00:05:25]World
            """.trimIndent(),
        )

        assertEquals(listOf(1600L, 3100L, 5350L), lines.map { it.startTimeMs })
        assertEquals(listOf("Hello", "Hello", "World"), lines.map { it.text })
    }
}
