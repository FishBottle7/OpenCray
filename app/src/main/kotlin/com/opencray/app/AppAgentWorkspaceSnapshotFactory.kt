package com.opencray.app

import java.nio.file.Files
import java.nio.file.Path

internal object AppAgentWorkspaceSnapshotFactory {
  private const val DEFAULT_MAX_TREE_NODES: Int = 400
  private const val DEFAULT_MAX_TREE_DEPTH: Int = 10

  fun createSnapshot(
    workspaceRoot: Path,
    maxTreeNodes: Int = DEFAULT_MAX_TREE_NODES,
    maxTreeDepth: Int = DEFAULT_MAX_TREE_DEPTH,
  ): WorkspaceTreeSnapshot {
    require(maxTreeNodes > 0) { "maxTreeNodes must be greater than 0." }
    require(maxTreeDepth > 0) { "maxTreeDepth must be greater than 0." }

    val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
    Files.createDirectories(normalizedRoot)

    val budget = SnapshotBudget(remainingNodes = maxTreeNodes)
    val children = buildChildren(
      directory = normalizedRoot,
      workspaceRoot = normalizedRoot,
      depth = 0,
      budget = budget,
      maxTreeDepth = maxTreeDepth,
    )
    val counts = countChildren(children)

    return WorkspaceTreeSnapshot(
      rootName = normalizedRoot.fileName?.toString().orEmpty().ifBlank { normalizedRoot.toString() },
      rootPath = normalizedRoot.toString(),
      availableBytes = normalizedRoot.toFile().usableSpace.coerceAtLeast(0L),
      directoryCount = counts.directoryCount,
      fileCount = counts.fileCount,
      entryCount = counts.directoryCount + counts.fileCount,
      isTruncated = budget.truncated,
      children = children,
    )
  }

  private fun buildChildren(
    directory: Path,
    workspaceRoot: Path,
    depth: Int,
    budget: SnapshotBudget,
    maxTreeDepth: Int,
  ): List<WorkspaceTreeNodeSnapshot> {
    val listedChildren = listChildren(directory)
    val snapshots = mutableListOf<WorkspaceTreeNodeSnapshot>()

    for (child in listedChildren) {
      if (budget.remainingNodes <= 0) {
        budget.truncated = true
        break
      }
      budget.remainingNodes -= 1
      snapshots += buildNode(
        path = child,
        workspaceRoot = workspaceRoot,
        depth = depth + 1,
        budget = budget,
        maxTreeDepth = maxTreeDepth,
      )
    }

    return snapshots
  }

  private fun buildNode(
    path: Path,
    workspaceRoot: Path,
    depth: Int,
    budget: SnapshotBudget,
    maxTreeDepth: Int,
  ): WorkspaceTreeNodeSnapshot {
    val isDirectory = Files.isDirectory(path)
    val directChildren = if (isDirectory) listChildren(path) else emptyList()
    val nestedChildren = mutableListOf<WorkspaceTreeNodeSnapshot>()
    var isTruncated = false

    if (isDirectory) {
      if (depth >= maxTreeDepth) {
        if (directChildren.isNotEmpty()) {
          budget.truncated = true
          isTruncated = true
        }
      } else {
        for (child in directChildren) {
          if (budget.remainingNodes <= 0) {
            budget.truncated = true
            isTruncated = true
            break
          }
          budget.remainingNodes -= 1
          nestedChildren += buildNode(
            path = child,
            workspaceRoot = workspaceRoot,
            depth = depth + 1,
            budget = budget,
            maxTreeDepth = maxTreeDepth,
          )
        }
      }
    }

    return WorkspaceTreeNodeSnapshot(
      name = path.fileName?.toString().orEmpty(),
      relativePath = workspaceRoot.relativize(path).toString().replace('\\', '/'),
      isDirectory = isDirectory,
      childCount = directChildren.size,
      sizeBytes = if (isDirectory) null else runCatching { Files.size(path) }.getOrDefault(0L),
      isTruncated = isTruncated,
      children = nestedChildren,
    )
  }

  private fun listChildren(directory: Path): List<Path> {
    if (!Files.isDirectory(directory)) {
      return emptyList()
    }

    val collected = mutableListOf<Path>()
    Files.list(directory).use { stream ->
      val iterator = stream.iterator()
      while (iterator.hasNext()) {
        collected.add(iterator.next())
      }
    }
    return collected.sortedWith(
      compareBy<Path>(
        { if (Files.isDirectory(it)) 0 else 1 },
        { it.fileName?.toString()?.lowercase().orEmpty() },
      ),
    )
  }

  private fun countChildren(
    children: List<WorkspaceTreeNodeSnapshot>,
  ): WorkspaceTreeCount {
    var directoryCount = 0
    var fileCount = 0

    for (child in children) {
      if (child.isDirectory) {
        directoryCount += 1
      } else {
        fileCount += 1
      }
      val nestedCounts = countChildren(child.children)
      directoryCount += nestedCounts.directoryCount
      fileCount += nestedCounts.fileCount
    }

    return WorkspaceTreeCount(
      directoryCount = directoryCount,
      fileCount = fileCount,
    )
  }
}

internal data class WorkspaceTreeSnapshot(
  val rootName: String,
  val rootPath: String,
  val availableBytes: Long,
  val directoryCount: Int,
  val fileCount: Int,
  val entryCount: Int,
  val isTruncated: Boolean,
  val children: List<WorkspaceTreeNodeSnapshot>,
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "rootName" to rootName,
    "rootPath" to rootPath,
    "availableBytes" to availableBytes,
    "directoryCount" to directoryCount,
    "fileCount" to fileCount,
    "entryCount" to entryCount,
    "isTruncated" to isTruncated,
    "children" to children.map(WorkspaceTreeNodeSnapshot::toMap),
  )
}

internal data class WorkspaceTreeNodeSnapshot(
  val name: String,
  val relativePath: String,
  val isDirectory: Boolean,
  val childCount: Int,
  val sizeBytes: Long?,
  val isTruncated: Boolean,
  val children: List<WorkspaceTreeNodeSnapshot>,
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "name" to name,
    "relativePath" to relativePath,
    "isDirectory" to isDirectory,
    "childCount" to childCount,
    "sizeBytes" to sizeBytes,
    "isTruncated" to isTruncated,
    "children" to children.map(WorkspaceTreeNodeSnapshot::toMap),
  )
}

private data class SnapshotBudget(
  var remainingNodes: Int,
  var truncated: Boolean = false,
)

private data class WorkspaceTreeCount(
  val directoryCount: Int,
  val fileCount: Int,
)
