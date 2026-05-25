package com.cliq.plugin.util

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PathUtilTest {

    @Test
    fun testToRelativePosixSimple() {
        val basePath = File("/project").absolutePath
        val absolutePath = File("/project/src/Main.kt").absolutePath
        assertEquals("src/Main.kt", PathUtil.toRelativePosix(basePath, absolutePath))
    }

    @Test
    fun testToRelativePosixNested() {
        val basePath = File("/project").absolutePath
        val absolutePath = File("/project/a/b/c.txt").absolutePath
        assertEquals("a/b/c.txt", PathUtil.toRelativePosix(basePath, absolutePath))
    }

    @Test
    fun testToRelativePosixOutside() {
        val basePath = File("/project").absolutePath
        val absolutePath = File("/other/file.txt").absolutePath
        assertNull(PathUtil.toRelativePosix(basePath, absolutePath))
    }

    @Test
    fun testToRelativePosixNullBase() {
        assertNull(PathUtil.toRelativePosix(null, "/any/path"))
    }

    @Test
    fun testToRelativePosixSamePath() {
        val path = File("/project").absolutePath
        assertEquals("", PathUtil.toRelativePosix(path, path))
    }
}
