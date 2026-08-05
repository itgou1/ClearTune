package com.cleartune.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun `parser expands timestamps applies offset and sorts timeline`() {
        val bytes = """
            [ar:Aster]
            [offset:250]
            [00:02.50][00:04.000]Second
            [00:01.00]First
        """.trimIndent().toByteArray()

        val result = LrcParser().parse(bytes)

        assertEquals(
            listOf(
                LrcLine(1_250, "First"),
                LrcLine(2_750, "Second"),
                LrcLine(4_250, "Second"),
            ),
            result,
        )
    }

    @Test
    fun `parser rejects an oversized sidecar and bounds generated lines`() {
        val parser = LrcParser(maximumBytes = 32, maximumLines = 2)

        assertTrue(parser.parse(ByteArray(33)).isEmpty())
        assertEquals(
            listOf(LrcLine(0, "A"), LrcLine(1_000, "B")),
            parser.parse("[00:00]A\n[00:01]B\n[00:02]C".toByteArray()),
        )
    }
}
