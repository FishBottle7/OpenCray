package com.opencray.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentWorkspaceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun ensureRootForFilesDirCreatesDedicatedWorkspaceSubdirectory() {
    val filesDir = temporaryFolder.newFolder("app-files")

    val workspaceRoot = AppAgentWorkspace.ensureRootForFilesDir(filesDir)

    assertEquals(File(filesDir, AppAgentWorkspace.DIRECTORY_NAME).toPath(), workspaceRoot)
    assertNotEquals(filesDir.toPath(), workspaceRoot)
    assertTrue(workspaceRoot.toFile().isDirectory)
  }

  @Test
  fun ensureRootForFilesDirReusesExistingWorkspaceDirectory() {
    val filesDir = temporaryFolder.newFolder("app-files-existing")
    val expectedWorkspace = File(filesDir, AppAgentWorkspace.DIRECTORY_NAME)
    check(expectedWorkspace.mkdirs())

    val workspaceRoot = AppAgentWorkspace.ensureRootForFilesDir(filesDir)

    assertEquals(expectedWorkspace.toPath(), workspaceRoot)
    assertTrue(workspaceRoot.toFile().isDirectory)
  }
}
