package com.opencray.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSearchTreeWalkTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun earlyBreakStopsVisitingRemainingEntries() {
    val root = newDirectory("walk-break-flat")
    repeat(50) { index ->
      Files.write(root.resolve("f%02d.txt".format(index)), ByteArray(0))
    }
    val visited = mutableListOf<Path>()

    useWorkspaceSearchCandidates(root = root, fileOnly = true) { candidates ->
      for (candidate in candidates) {
        visited.add(candidate)
        if (visited.size == 3) break
      }
    }

    assertEquals(3, visited.size)
  }

  @Test
  fun earlyBreakSkipsUnopenedSubdirectories() {
    val root = newDirectory("walk-break-nested")
    Files.write(root.resolve("a.txt"), ByteArray(0))
    Files.createDirectories(root.resolve("z").resolve("deep"))
    Files.write(root.resolve("z").resolve("deep").resolve("nested.txt"), ByteArray(0))
    val visited = mutableListOf<Path>()

    useWorkspaceSearchCandidates(root = root, fileOnly = false) { candidates ->
      for (candidate in candidates) {
        visited.add(candidate)
        break
      }
    }

    assertEquals(listOf("a.txt"), visited.map { it.fileName.toString() })
  }

  @Test
  fun fullTraversalCollectsEveryEntryExactlyOnceAndExcludesRoot() {
    val root = newDirectory("walk-full")
    Files.write(root.resolve("a.txt"), ByteArray(0))
    Files.write(root.resolve("m.txt"), ByteArray(0))
    Files.createDirectories(root.resolve("dir").resolve("sub"))
    Files.write(root.resolve("dir").resolve("b.txt"), ByteArray(0))
    Files.write(root.resolve("dir").resolve("sub").resolve("c.txt"), ByteArray(0))

    val collected = collectAll(root, fileOnly = false)

    val reference = Files.walk(root).use { stream ->
      stream.filter { candidate -> candidate != root }.collect(Collectors.toList())
    }
    assertEquals(reference.toSet(), collected.toSet())
    assertEquals(collected.size, collected.toSet().size)
    assertTrue(collected.none { it == root })
  }

  @Test
  fun regularFileModeMatchesEagerWalkReference() {
    val root = newDirectory("walk-files")
    Files.write(root.resolve("a.txt"), ByteArray(0))
    Files.createDirectories(root.resolve("dir"))
    Files.write(root.resolve("dir").resolve("b.bin"), ByteArray(0))

    val collected = collectAll(root, fileOnly = true)

    val reference = Files.walk(root).use { stream ->
      stream.sorted()
        .filter { candidate -> candidate != root && Files.isRegularFile(candidate) }
        .collect(Collectors.toList())
    }
    assertEquals(reference.toSet(), collected.toSet())
  }

  @Test
  fun traversalIsDeterministicWithParentsBeforeChildrenAndSortedSiblings() {
    val root = newDirectory("walk-order")
    Files.write(root.resolve("a.txt"), ByteArray(0))
    Files.createDirectories(root.resolve("a"))
    Files.write(root.resolve("a").resolve("z.txt"), ByteArray(0))

    val firstRun = collectAll(root, fileOnly = false)
    val secondRun = collectAll(root, fileOnly = false)

    assertEquals(
      listOf("a", "a/z.txt", "a.txt"),
      firstRun.map { path -> root.relativize(path).toString().replace('\\', '/') },
    )
    assertEquals(firstRun, secondRun)
  }

  @Test
  fun earlyBreakReleasesDirectoryStreamsSoTreeRemainsDeletable() {
    val root = newDirectory("walk-handle-release")
    Files.write(root.resolve("a.txt"), ByteArray(0))
    Files.createDirectories(root.resolve("l1").resolve("l2").resolve("l3"))
    Files.write(root.resolve("l1").resolve("l2").resolve("l3").resolve("deep.txt"), ByteArray(0))

    useWorkspaceSearchCandidates(root = root, fileOnly = true) { candidates ->
      for (candidate in candidates) {
        break
      }
    }

    Files.walk(root).use { stream ->
      stream.sorted(java.util.Comparator.reverseOrder()).forEach { path -> Files.delete(path) }
    }
    assertTrue(!Files.exists(root))
  }

  @Test
  fun nonDirectoryRootFallbacksMatchLegacyBehavior() {
    val folder = newDirectory("walk-fallback")
    val file = folder.resolve("single.txt")
    Files.write(file, ByteArray(0))
    val missing = folder.resolve("missing.txt")

    val fileAsRootFilesOnly = collectAll(file, fileOnly = true)
    val fileAsRootAllEntries = collectAll(file, fileOnly = false)
    val missingAsRootFilesOnly = collectAll(missing, fileOnly = true)
    val missingAsRootAllEntries = collectAll(missing, fileOnly = false)

    assertEquals(listOf(file), fileAsRootFilesOnly)
    assertEquals(listOf(file), fileAsRootAllEntries)
    assertEquals(emptyList<Path>(), missingAsRootFilesOnly)
    assertEquals(listOf(missing), missingAsRootAllEntries)
  }

  private fun collectAll(root: Path, fileOnly: Boolean): List<Path> {
    val collected = mutableListOf<Path>()
    useWorkspaceSearchCandidates(root = root, fileOnly = fileOnly) { candidates ->
      for (candidate in candidates) {
        collected.add(candidate)
      }
    }
    return collected
  }

  private fun newDirectory(name: String): Path = temporaryFolder.newFolder(name).toPath()
}
