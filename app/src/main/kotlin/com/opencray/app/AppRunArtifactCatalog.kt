package com.opencray.app

import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAttachmentArtifacts
import com.opencray.runtime.OpenCrayToolResultEvent
import java.nio.file.Path
import kotlinx.serialization.json.Json

internal class AppRunArtifactCatalog(
  private val workspaceRootProvider: () -> Path?,
  private val runtimeEventsProvider: (String) -> List<OpenCrayAgentRunEvent>,
  private val json: Json = Json,
) {
  fun resolve(
    sessionId: String,
    artifactId: String,
    runId: String? = null,
  ): AppResolvedImageAssetHandle? {
    val normalizedArtifactId = artifactId.trim().takeIf(String::isNotBlank) ?: return null
    return list(
      sessionId = sessionId,
      artifactIds = setOf(normalizedArtifactId),
      runId = runId,
    )[normalizedArtifactId]
  }

  fun list(
    sessionId: String,
    artifactIds: Set<String> = emptySet(),
    runId: String? = null,
  ): Map<String, AppResolvedImageAssetHandle> {
    val normalizedSessionId = sessionId.trim().takeIf(String::isNotBlank) ?: return emptyMap()
    val normalizedArtifactIds = artifactIds
      .mapNotNull { artifactId -> artifactId.trim().takeIf(String::isNotBlank) }
      .toSet()
    val normalizedRunId = runId?.trim()?.takeIf(String::isNotBlank)
    val workspaceRoot = workspaceRootProvider()?.toAbsolutePath()?.normalize() ?: return emptyMap()
    val resolved = linkedMapOf<String, AppResolvedImageAssetHandle>()
    runtimeEventsProvider(normalizedSessionId)
      .asReversed()
      .forEach { event ->
        val toolResultEvent = event as? OpenCrayToolResultEvent ?: return@forEach
        if (normalizedRunId != null && toolResultEvent.runId != normalizedRunId) {
          return@forEach
        }
        OpenCrayAttachmentArtifacts.decodeMetadata(
          json = json,
          metadata = toolResultEvent.result.metadata,
        ).forEach artifactLoop@ { artifact ->
          if (normalizedArtifactIds.isNotEmpty() && artifact.artifactId !in normalizedArtifactIds) {
            return@artifactLoop
          }
          if (artifact.artifactId in resolved) {
            return@artifactLoop
          }
          val handle = artifact.toAppResolvedImageAssetHandle(workspaceRoot)
            ?.copy(sourceSessionId = normalizedSessionId)
            ?: return@artifactLoop
          resolved[artifact.artifactId] = handle
        }
      }
    return resolved
  }
}
