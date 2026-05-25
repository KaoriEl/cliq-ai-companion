package com.cliq.plugin.util

import org.junit.Test
import kotlin.test.assertEquals

class CliPathEscaperTest {

    @Test
    fun testEscapePosixSimple() {
        assertEquals("file.txt", CliPathEscaper.escape("file.txt"))
    }

    @Test
    fun testEscapePosixWithSpaces() {
        assertEquals("'file with space.txt'", CliPathEscaper.escape("file with space.txt"))
    }

    @Test
    fun testEscapePosixWithQuotes() {
        assertEquals("'it'\\''s a file.txt'", CliPathEscaper.escape("it's a file.txt"))
    }

    @Test
    fun testEscapePosixEmpty() {
        assertEquals("''", CliPathEscaper.escape(""))
    }

    @Test
    fun testEscapePosixSpecialChars() {
        assertEquals("'file$!*.txt'", CliPathEscaper.escape("file$!*.txt"))
    }
}
