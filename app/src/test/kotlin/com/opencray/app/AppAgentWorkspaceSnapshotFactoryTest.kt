package com.opencray.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentWorkspaceSnapshotFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun createSnapshotBuildsNestedTreeFromWorkspaceRoot() {
    val workspaceRoot = temporaryFolder.newFolder("agent-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("docs"))
    Files.createDirectories(workspaceRoot.resolve("src"))
    Files.write(
      workspaceRoot.resolve("docs").resolve("report.md"),
      "# report".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )

    val snapshot = AppAgentWorkspaceSnapshotFactory.createSnapshot(workspaceRoot)

    assertEquals("agent-workspace", snapshot.rootName)
    assertEquals(2, snapshot.directoryCount)
    assertEquals(2, snapshot.fileCount)
    assertEquals(4, snapshot.entryCount)
    assertFalse(snapshot.isTruncated)
    assertEquals(listOf("docs", "src", "README.md"), snapshot.children.map { it.name })

    val docsNode = snapshot.children.first { it.name == "docs" }
    assertTrue(docsNode.isDirectory)
    assertEquals(1, docsNode.childCount)
    assertEquals("docs/report.md", docsNode.children.single().relativePath)
  }

  @Test
  fun createSnapshotMarksTreeAsTruncatedWhenNodeBudgetIsExceeded() {
    val workspaceRoot = temporaryFolder.newFolder("agent-workspace-budget").toPath()
    Files.write(
      workspaceRoot.resolve("one.txt"),
      "one".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.resolve("two.txt"),
      "two".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.resolve("three.txt"),
      "three".toByteArray(StandardCharsets.UTF_8),
    )

    val snapshot = AppAgentWorkspaceSnapshotFactory.createSnapshot(
      workspaceRoot = workspaceRoot,
      maxTreeNodes = 2,
      maxTreeDepth = 10,
    )

    assertTrue(snapshot.isTruncated)
    assertEquals(2, snapshot.children.size)
  }
}
