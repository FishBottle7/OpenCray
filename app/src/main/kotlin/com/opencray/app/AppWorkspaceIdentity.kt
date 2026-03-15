package com.opencray.app

import java.nio.file.Path

internal object AppWorkspaceIdentity {
  fun fromRoots(roots: Set<Path>): String? {
    val normalizedRoots = roots.asSequence()
      .map { root -> root.toAbsolutePath().normalize().toString().replace('\\', '/') }
      .filter(String::isNotBlank)
      .distinct()
      .sorted()
      .toList()
    if (normalizedRoots.isEmpty()) {
      return null
    }
    return normalizedRoots.joinToString(separator = "|")
  }
}
