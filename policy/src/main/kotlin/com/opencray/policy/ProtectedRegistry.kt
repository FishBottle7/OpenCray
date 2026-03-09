package com.opencray.policy

import java.nio.file.Path

class ProtectedRegistry(
  protectedFileNames: Set<String> = emptySet(),
) {
  private val protectedBasenames: Set<String> =
    (MINIMUM_PROTECTED_FILE_NAMES + protectedFileNames)
      .map(::normalizeFileName)
      .toSet()

  fun isProtected(path: Path): Boolean {
    val fileName = path.fileName?.toString() ?: return false
    return normalizeFileName(fileName) in protectedBasenames
  }

  fun registeredProtectedFiles(): Set<String> = protectedBasenames

  companion object {
    val MINIMUM_PROTECTED_FILE_NAMES: Set<String> = setOf(
      "agent.md",
      "memory.md",
      "soul.md",
    )

    private fun normalizeFileName(fileName: String): String {
      val normalized = fileName.trim().lowercase()
      require(normalized.isNotBlank()) { "Protected file name must not be blank." }
      return normalized
    }
  }
}
