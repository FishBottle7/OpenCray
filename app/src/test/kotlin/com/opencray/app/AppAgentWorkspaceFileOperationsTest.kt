package com.opencray.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentWorkspaceFileOperationsTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun createsAndRenamesDirectoriesWithinWorkspace() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-create-rename").toPath()

    AppAgentWorkspaceFileOperations.createDirectory(
      workspaceRoot = workspaceRoot,
      parentRelativePath = "",
      name = "docs",
    )
    AppAgentWorkspaceFileOperations.renameEntry(
      workspaceRoot = workspaceRoot,
      targetRelativePath = "docs",
      newName = "notes",
    )

    assertTrue(Files.isDirectory(workspaceRoot.resolve("notes")))
    assertFalse(Files.exists(workspaceRoot.resolve("docs")))
  }

  @Test
  fun copiesDirectoryTreesIntoDestinationDirectory() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-copy").toPath()
    val docsDirectory = Files.createDirectories(workspaceRoot.resolve("docs"))
    Files.write(
      docsDirectory.resolve("report.md"),
      "report body".toByteArray(StandardCharsets.UTF_8),
    )
    Files.createDirectories(workspaceRoot.resolve("archive"))

    AppAgentWorkspaceFileOperations.pasteEntries(
      workspaceRoot = workspaceRoot,
      sourceRelativePaths = listOf("docs"),
      destinationRelativePath = "archive",
      move = false,
    )

    assertTrue(Files.isDirectory(workspaceRoot.resolve("docs")))
    assertTrue(Files.isDirectory(workspaceRoot.resolve("archive/docs")))
    assertEquals(
      "report body",
      String(
        Files.readAllBytes(workspaceRoot.resolve("archive/docs/report.md")),
        StandardCharsets.UTF_8,
      ),
    )
  }

  @Test
  fun movesEntriesAndDeletesSelections() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-move-delete").toPath()
    Files.write(
      workspaceRoot.resolve("todo.txt"),
      "todo body".toByteArray(StandardCharsets.UTF_8),
    )
    Files.createDirectories(workspaceRoot.resolve("done"))

    AppAgentWorkspaceFileOperations.pasteEntries(
      workspaceRoot = workspaceRoot,
      sourceRelativePaths = listOf("todo.txt"),
      destinationRelativePath = "done",
      move = true,
    )

    assertFalse(Files.exists(workspaceRoot.resolve("todo.txt")))
    assertTrue(Files.exists(workspaceRoot.resolve("done/todo.txt")))

    AppAgentWorkspaceFileOperations.deleteEntries(
      workspaceRoot = workspaceRoot,
      relativePaths = listOf("done/todo.txt"),
    )

    assertFalse(Files.exists(workspaceRoot.resolve("done/todo.txt")))
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsPathsThatEscapeWorkspace() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-escape").toPath()

    AppAgentWorkspaceFileOperations.createDirectory(
      workspaceRoot = workspaceRoot,
      parentRelativePath = "../outside",
      name = "nope",
    )
  }
}
