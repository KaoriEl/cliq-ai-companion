package com.cliq.plugin.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import java.io.File
import java.nio.charset.StandardCharsets

class CliqDiffManagerTest : BasePlatformTestCase() {

    fun testApplyDirectlyWritesFile() {
        val tempFile = File.createTempFile("cliq_diff_test", ".txt")
        val path = tempFile.absolutePath
        val manager = project.service<CliqDiffManager>()
        val newContent = "updated content"
        
        manager.applyDirectly(path, newContent)
        
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        
        val refreshed = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
        assertNotNull(refreshed)
        val actual = String(refreshed!!.contentsToByteArray(), StandardCharsets.UTF_8)
        assertEquals(newContent, actual)
        tempFile.delete()
    }

    fun testApplyDirectlyCreatesNewFile() {
        val manager = project.service<CliqDiffManager>()
        val tempDir = System.getProperty("java.io.tmpdir")
        val newPath = "${tempDir}/cliq_new_file_${System.currentTimeMillis()}.txt"
        val content = "brand new content"
        
        manager.applyDirectly(newPath, content)
        
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(newPath)
        try {
            assertNotNull(file)
            assertEquals(content, String(file!!.contentsToByteArray(), StandardCharsets.UTF_8))
        } finally {
            File(newPath).delete()
        }
    }
}
