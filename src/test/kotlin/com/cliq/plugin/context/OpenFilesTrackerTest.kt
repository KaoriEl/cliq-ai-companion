package com.cliq.plugin.context

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.components.service

class OpenFilesTrackerTest : BasePlatformTestCase() {

    fun testFileOpenedPromotesFile() {
        val file = myFixture.addFileToProject("test.txt", "content").virtualFile
        val tracker = project.service<OpenFilesTracker>()
        
        myFixture.openFileInEditor(file)
        
        val snapshot = tracker.snapshot()
        assertTrue(snapshot.openFiles.any { it.path == file.path && it.isActive })
    }

    fun testMaxTrackedFiles() {
        val tracker = project.service<OpenFilesTracker>()
        for (i in 1..15) {
            val file = myFixture.addFileToProject("test$i.txt", "content$i").virtualFile
            myFixture.openFileInEditor(file)
        }
        
        val snapshot = tracker.snapshot()
        assertEquals(OpenFilesTracker.MAX_TRACKED_FILES, snapshot.openFiles.size)
    }

    fun testSelectionChangeUpdatesContext() {
        val file = myFixture.addFileToProject("test_selection.txt", "line1\nline2\nline3").virtualFile
        val tracker = project.service<OpenFilesTracker>()
        
        myFixture.openFileInEditor(file)
        myFixture.editor.selectionModel.setSelection(0, 5)
        
        val snapshot = tracker.snapshot()
        val tracked = snapshot.openFiles.first { it.path == file.path }
        assertEquals("line1", tracked.selectedText)
    }
}
