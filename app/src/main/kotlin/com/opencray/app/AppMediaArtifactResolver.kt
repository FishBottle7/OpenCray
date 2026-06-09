package com.opencray.app

import com.opencray.runtime.OpenCrayAttachmentArtifact
import com.opencray.runtime.OpenCrayMediaArtifactRegistry
import com.opencray.runtime.defaultOpenCrayMediaArtifactRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal fun resolveWorkspaceMediaArtifact(
  workspaceRoot: Path?,
  artifactId: String,
  mediaArtifactRegistry: OpenCrayMediaArtifactRegistry? = null,
): OpenCrayAttachmentArtifact? {
  val normalizedArtifactId = artifactId.trim().takeIf(String::isNotBlank) ?: return null
  val normalizedWorkspaceRoot = workspaceRoot?.toAbsolutePath()?.normalize() ?: return null
  val registry = mediaArtifactRegistry ?: defaultOpenCrayMediaArtifactRegistry(normalizedWorkspaceRoot)
  val registeredArtifact = registry.resolve(normalizedArtifactId) ?: return null
  val relativePath = registeredArtifact.artifact.relativePath
    .trim()
    .replace('\\', '/')
    .trim('/')
    .takeIf(String::isNotBlank)
    ?: return null
  val resolvedPath = normalizedWorkspaceRoot.resolve(relativePath).normalize()
  if (!resolvedPath.startsWith(normalizedWorkspaceRoot) || !Files.isRegularFile(resolvedPath)) {
    registry.sweep(normalizedWorkspaceRoot)
    return null
  }
  return registeredArtifact.artifact.copy(
    artifactId = normalizedArtifactId,
    relativePath = relativePath,
    displayName = registeredArtifact.artifact.displayName?.trim()?.takeIf(String::isNotBlank),
    kindHint = registeredArtifact.artifact.kindHint
      ?.trim()
      ?.lowercase(Locale.US)
      ?.takeIf(String::isNotBlank),
    mimeType = registeredArtifact.artifact.mimeType?.trim()?.takeIf(String::isNotBlank),
    waveformBars = registeredArtifact.artifact.waveformBars.map { value -> value.coerceIn(0, 100) },
    transcriptText = registeredArtifact.artifact.transcriptText?.trim()?.takeIf(String::isNotBlank),
  )
}
