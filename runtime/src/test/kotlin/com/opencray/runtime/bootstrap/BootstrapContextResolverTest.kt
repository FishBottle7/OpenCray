package com.opencray.runtime.bootstrap

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BootstrapContextResolverTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun resolveFullModeLoadsSupportedBootstrapFilesInPriorityOrder() {
    val workspaceRoot = temporaryFolder.newFolder("bootstrap-full").toPath()
    writeFile(workspaceRoot.resolve("AGENTS.md"), "# Agents\nUse repo conventions.")
    writeFile(workspaceRoot.resolve("SOUL.md"), "# Soul\nStay concise.")
    writeFile(workspaceRoot.resolve("TOOLS.md"), "# Tools\nPrefer workspace tools.")
    writeFile(workspaceRoot.resolve("PROJECT.md"), "# Project\nThis repo uses Gradle.")

    val context = BootstrapContextResolver().resolve(
      workspaceRoots = setOf(workspaceRoot),
      mode = BootstrapMode.FULL,
    )

    assertEquals(BootstrapMode.FULL, context.mode)
    assertEquals(listOf("AGENTS.md", "SOUL.md", "TOOLS.md", "PROJECT.md"), context.files.map { it.name })
    assertEquals(4, context.trace.visibleFileCount)
    assertEquals(4, context.trace.injectedFileCount)
    assertEquals(0, context.trace.omittedFileCount)
    assertEquals("full", context.trace.mode)
    assertEquals("AGENTS.md", context.files.first().relativePath)
  }

  @Test
  fun resolveLightweightModeKeepsAgentsAndProjectOnly() {
    val workspaceRoot = temporaryFolder.newFolder("bootstrap-lightweight").toPath()
    writeFile(workspaceRoot.resolve("AGENTS.md"), "# Agents\nUse repo conventions.")
    writeFile(workspaceRoot.resolve("SOUL.md"), "# Soul\nStay concise.")
    writeFile(workspaceRoot.resolve("TOOLS.md"), "# Tools\nPrefer workspace tools.")
    writeFile(workspaceRoot.resolve("PROJECT.md"), "# Project\nThis repo uses Gradle.")

    val context = BootstrapContextResolver().resolve(
      workspaceRoots = setOf(workspaceRoot),
      mode = BootstrapMode.LIGHTWEIGHT,
    )

    assertEquals(listOf("AGENTS.md", "PROJECT.md"), context.files.map { it.name })
    assertEquals(2, context.trace.visibleFileCount)
    assertEquals(2, context.trace.injectedFileCount)
    assertEquals(0, context.trace.omittedFileCount)
    assertFalse(context.files.any { file -> file.name == "SOUL.md" || file.name == "TOOLS.md" })
  }

  @Test
  fun resolveHonorsPerFileAndTotalBudgets() {
    val workspaceRoot = temporaryFolder.newFolder("bootstrap-budget").toPath()
    writeFile(workspaceRoot.resolve("AGENTS.md"), "A".repeat(220))
    writeFile(workspaceRoot.resolve("PROJECT.md"), "B".repeat(220))

    val context = BootstrapContextResolver(
      BootstrapContextResolverConfig(
        maxCharsPerFile = 160,
        maxTotalChars = 250,
        minRemainingCharsToInject = 80,
      ),
    ).resolve(
      workspaceRoots = setOf(workspaceRoot),
      mode = BootstrapMode.FULL,
    )

    assertEquals(2, context.trace.visibleFileCount)
    assertEquals(2, context.trace.injectedFileCount)
    assertEquals(0, context.trace.omittedFileCount)
    assertEquals(2, context.trace.truncatedFileCount)
    assertEquals(2, context.files.size)
    assertTrue(context.files.all { file -> file.truncated })
    assertEquals(listOf(160, 90), context.files.map { file -> file.content.length })
    assertEquals(listOf(220, 220), context.files.map { file -> file.sourceCharCount })
  }

  private fun writeFile(path: java.nio.file.Path, content: String) {
    Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
  }
}
