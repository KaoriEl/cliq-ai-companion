package com.cliq.plugin.actions

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.TestActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class InsertFilesAsContextActionTest : BasePlatformTestCase() {

    fun testActionDisabledInEditor() {
        val action = InsertFilesAsContextAction()
        val file = myFixture.addFileToProject("test.txt", "").virtualFile
        myFixture.openFileInEditor(file)
        
        val event = TestActionEvent.createTestEvent(action) { dataId ->
            when (dataId) {
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.VIRTUAL_FILE.name -> file
                CommonDataKeys.EDITOR.name -> myFixture.editor
                else -> null
            }
        }
        
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible)
    }

    fun testActionEnabledInListContext() {
        val action = InsertFilesAsContextAction()
        val file = myFixture.addFileToProject("test.txt", "").virtualFile
        
        val event = TestActionEvent.createTestEvent(action) { dataId ->
            when (dataId) {
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.VIRTUAL_FILE.name -> file
                CommonDataKeys.EDITOR.name -> null
                else -> null
            }
        }
        
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
    }
}
