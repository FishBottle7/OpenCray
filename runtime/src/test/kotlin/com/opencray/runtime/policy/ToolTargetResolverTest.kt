package com.opencray.runtime.policy

import com.opencray.runtime.WorkspaceBoundary
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolTargetResolverTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun displayModelPathReturnsWorkspaceRelativeForwardSlashPath() {
    val workspaceRoot = temporaryFolder.newFolder("resolver-model").toPath()
    Files.createDirectories(workspaceRoot.resolve("docs"))
    Files.write(
      workspaceRoot.resolve("docs").resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val resolver = resolver(workspaceRoot)

    val path = resolver.resolveReadablePath(
      candidate = "docs/README.md",
      label = "display model",
      defaultToRoot = false,
    )

    assertEquals("docs/README.md", resolver.displayModelPath(path))
  }

  @Test
  fun displayWritablePathReturnsWorkspaceRelativeForwardSlashPath() {
    val workspaceRoot = temporaryFolder.newFolder("resolver-write").toPath()
    Files.createDirectories(workspaceRoot.resolve("scripts"))
    val resolver = resolver(workspaceRoot)

    val path = resolver.resolveWritablePath(
      candidate = "scripts/run.py",
      label = "display writable",
      defaultToRoot = false,
    )

    assertEquals("scripts/run.py", resolver.displayWritablePath(path))
  }

  @Test
  fun resolveSearchRootAllowsReadableFilesAndDirectories() {
    val workspaceRoot = temporaryFolder.newFolder("resolver-search").toPath()
    Files.write(
      workspaceRoot.resolve("notes.txt"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val resolver = resolver(workspaceRoot)

    assertEquals(
      resolver.resolveReadablePath(
        candidate = "notes.txt",
        label = "expected notes",
        defaultToRoot = false,
      ),
      resolver.resolveSearchRoot("notes.txt", label = "Grep path"),
    )
    assertEquals(
      resolver.resolveReadablePath(
        candidate = null,
        label = "expected root",
        defaultToRoot = true,
      ),
      resolver.resolveSearchRoot(null, label = "Glob path"),
    )
  }

  @Test
  fun displayWorkingDirectoryNormalizesWorkspaceRelativePathForModel() {
    val workspaceRoot = temporaryFolder.newFolder("resolver-cwd").toPath()
    Files.createDirectories(workspaceRoot.resolve("server"))
    val resolver = resolver(workspaceRoot)

    val workingDirectory = resolver.resolveWritablePath(
      candidate = "server",
      label = "cwd",
      defaultToRoot = false,
    )

    assertEquals(
      "server",
      resolver.displayWorkingDirectory(workingDirectory.toString()),
    )
  }

  @Test
  fun workspaceRelationDistinguishesInsideOutsideAndMixedTargets() {
    val workspaceRoot = temporaryFolder.newFolder("resolver-relation-workspace").toPath()
    val externalRoot = temporaryFolder.newFolder("resolver-relation-external").toPath()
    Files.write(
      workspaceRoot.resolve("notes.txt"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      externalRoot.resolve("photo.txt"),
      "camera".toByteArray(StandardCharsets.UTF_8),
    )
    val resolver = ToolTargetResolver(
      readBoundary = WorkspaceBoundary(setOf(workspaceRoot, externalRoot)),
      writeBoundary = WorkspaceBoundary(setOf(workspaceRoot)),
    )
    val workspaceFile = resolver.resolveWritablePath(
      candidate = "notes.txt",
      label = "inside target",
      defaultToRoot = false,
    )
    val externalFile = resolver.resolveReadablePath(
      candidate = externalRoot.resolve("photo.txt").toString(),
      label = "outside target",
      defaultToRoot = false,
    )

    assertEquals(
      ToolWorkspaceRelation.INSIDE_WORKSPACE,
      resolver.workspaceRelation(primary = workspaceFile),
    )
    assertEquals(
      ToolWorkspaceRelation.OUTSIDE_WORKSPACE,
      resolver.workspaceRelation(primary = externalFile),
    )
    assertEquals(
      ToolWorkspaceRelation.MIXED,
      resolver.workspaceRelation(primary = externalFile, secondary = workspaceFile),
    )
    assertNull(resolver.workspaceRelation())
  }

  private fun resolver(workspaceRoot: java.nio.file.Path): ToolTargetResolver {
    val boundary = WorkspaceBoundary(setOf(workspaceRoot))
    return ToolTargetResolver(
      readBoundary = boundary,
      writeBoundary = boundary,
    )
  }
}
