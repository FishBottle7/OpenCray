package com.opencray.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal class WorkspaceBoundary(
  approvedRoots: Set<Path>,
) {
  private val canonicalApprovedRoots: List<Path>
  val defaultRoot: Path

  init {
    require(approvedRoots.isNotEmpty()) { "WorkspaceBoundary requires at least one approved root." }
    canonicalApprovedRoots = approvedRoots
      .map(::canonicalize)
      .distinct()
      .sortedBy { it.toString() }
    defaultRoot = canonicalApprovedRoots.first()
  }

  fun approvedRoots(): Set<Path> = canonicalApprovedRoots.toSet()

  fun resolve(candidate: String?, label: String, defaultToRoot: Boolean = true): Path {
    val normalizedCandidate = candidate?.trim().orEmpty()
    if (normalizedCandidate.isEmpty()) {
      if (defaultToRoot) {
        return defaultRoot
      }
      throw IllegalArgumentException("$label path must not be blank.")
    }

    val parsed = runCatching { Paths.get(normalizedCandidate) }
      .getOrElse { error -> throw IllegalArgumentException("$label path is invalid: ${error.message}") }

    if (containsTraversalSegment(parsed)) {
      throw IllegalArgumentException("$label path contains traversal segment '..'.")
    }

    val resolved = if (parsed.isAbsolute) parsed else defaultRoot.resolve(parsed)
    val canonicalCandidate = canonicalize(resolved)
    if (canonicalApprovedRoots.none { root -> canonicalCandidate.startsWith(root) }) {
      throw IllegalArgumentException("$label path escapes approved workspace roots.")
    }
    return canonicalCandidate
  }

  fun ensureDirectory(candidate: String?, label: String, defaultToRoot: Boolean = true): Path {
    val resolved = resolve(candidate = candidate, label = label, defaultToRoot = defaultToRoot)
    require(Files.isDirectory(resolved)) { "$label path is not a directory: $resolved" }
    return resolved
  }

  fun ensureFile(candidate: String?, label: String): Path {
    val resolved = resolve(candidate = candidate, label = label, defaultToRoot = false)
    require(Files.isRegularFile(resolved)) { "$label path is not a file: $resolved" }
    return resolved
  }

  private fun containsTraversalSegment(path: Path): Boolean {
    for (segment in path) {
      if (segment.toString() == "..") {
        return true
      }
    }
    return false
  }

  private fun canonicalize(path: Path): Path {
    val absoluteNormalized = path.toAbsolutePath().normalize()
    val existingAncestor = findNearestExistingAncestor(absoluteNormalized) ?: return absoluteNormalized
    val relativeSuffix = existingAncestor.relativize(absoluteNormalized)

    return runCatching {
      val canonicalAncestor = existingAncestor.toRealPath()
      canonicalAncestor.resolve(relativeSuffix).normalize()
    }.getOrDefault(absoluteNormalized)
  }

  private fun findNearestExistingAncestor(path: Path): Path? {
    var current: Path? = path
    while (current != null) {
      if (Files.exists(current)) {
        return current
      }
      current = current.parent
    }
    return null
  }
}
