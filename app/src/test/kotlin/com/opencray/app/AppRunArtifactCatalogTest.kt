package com.opencray.app

import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAttachmentArtifact
import com.opencray.runtime.OpenCrayAttachmentArtifacts
import com.opencray.runtime.OpenCrayToolResultEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppRunArtifactCatalogTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun resolveReturnsLatestImageArtifactForSession() {
    val workspaceRoot = temporaryFolder.newFolder("artifact-catalog-workspace").toPath()
    val firstImagePath = writeFile(workspaceRoot, "media/generated/old.png")
    val latestImagePath = writeFile(workspaceRoot, "media/generated/latest.png")
    val artifactId = "artifact-shared-1"
    val sessionId = "session-1"
    val catalog = AppRunArtifactCatalog(
      workspaceRootProvider = { workspaceRoot },
      runtimeEventsProvider = { requestedSessionId ->
        if (requestedSessionId == sessionId) {
          listOf(
            toolResultEvent(
              runId = "run-1",
              artifact = OpenCrayAttachmentArtifact(
                artifactId = artifactId,
                relativePath = "media/generated/old.png",
                displayName = "old.png",
                kindHint = "image",
                mimeType = "image/png",
              ),
              emittedAtEpochMs = 10L,
            ),
            toolResultEvent(
              runId = "run-2",
              artifact = OpenCrayAttachmentArtifact(
                artifactId = artifactId,
                relativePath = "media/generated/latest.png",
                displayName = "latest.png",
                kindHint = "image",
                mimeType = "image/png",
              ),
              emittedAtEpochMs = 20L,
            ),
          )
        } else {
          emptyList()
        }
      },
    )

    val resolved = catalog.resolve(
      sessionId = sessionId,
      artifactId = artifactId,
    )

    assertNotNull(resolved)
    requireNotNull(resolved)
    assertEquals(latestImagePath.toAbsolutePath().normalize(), resolved.path)
    assertEquals("latest.png", resolved.displayName)
    assertEquals("image/png", resolved.mimeType)
    assertEquals(sessionId, resolved.sourceSessionId)
    assertTrue(firstImagePath != resolved.path)
  }

  @Test
  fun resolveRejectsNonImageArtifactMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("artifact-catalog-non-image").toPath()
    writeFile(workspaceRoot, "docs/spec.txt")
    val catalog = AppRunArtifactCatalog(
      workspaceRootProvider = { workspaceRoot },
      runtimeEventsProvider = {
        listOf(
          toolResultEvent(
            runId = "run-text",
            artifact = OpenCrayAttachmentArtifact(
              artifactId = "artifact-spec-1",
              relativePath = "docs/spec.txt",
              displayName = "spec.txt",
              kindHint = "file",
              mimeType = "text/plain",
            ),
            emittedAtEpochMs = 1L,
          ),
        )
      },
    )

    val resolved = catalog.resolve(
      sessionId = "session-1",
      artifactId = "artifact-spec-1",
    )

    assertNull(resolved)
  }

  private fun toolResultEvent(
    runId: String,
    artifact: OpenCrayAttachmentArtifact,
    emittedAtEpochMs: Long,
  ): OpenCrayToolResultEvent = OpenCrayToolResultEvent(
    runId = runId,
    taskId = "task-$runId",
    turn = 1,
    call = AgentToolCall(
      id = "call-$runId",
      toolName = "generate_image",
    ),
    result = AgentToolResult(
      toolName = "generate_image",
      status = AgentToolResultStatus.SUCCESS,
      content = "ok",
      metadata = OpenCrayAttachmentArtifacts.encodeMetadata(
        json = Json,
        artifacts = listOf(artifact),
      ),
    ),
    emittedAtEpochMs = emittedAtEpochMs,
  )

  private fun writeFile(
    root: Path,
    relativePath: String,
  ): Path {
    val target = root.resolve(relativePath).normalize()
    Files.createDirectories(requireNotNull(target.parent))
    Files.write(target, byteArrayOf(1, 2, 3, 4))
    return target
  }
}
