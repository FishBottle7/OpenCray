package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayToolDispatcherAttachmentArtifactTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun workspaceWriteFilePublishesAttachmentArtifactMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("artifact-write-workspace").toPath()
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "workspace_write_file",
        arguments = buildJsonObject {
          put("path", "outputs/diagram.png")
          put("content", "png-bytes-placeholder")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID].orEmpty().startsWith("artifact-diagram-"))
    assertEquals("outputs/diagram.png", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH])
    assertEquals("diagram.png", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME])
    assertEquals("image", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT])
    assertEquals("image/png", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE])
  }

  @Test
  fun workspaceImportFilePublishesAttachmentArtifactMetadataForSingleFile() {
    val workspaceRoot = temporaryFolder.newFolder("artifact-import-workspace").toPath()
    val approvedReadRoot = temporaryFolder.newFolder("artifact-import-approved").toPath()
    Files.write(
      approvedReadRoot.resolve("voice-note.m4a"),
      byteArrayOf(1, 2, 3, 4),
    )
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      readRoots = setOf(workspaceRoot, approvedReadRoot),
    )

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "workspace_import_file",
        arguments = buildJsonObject {
          put("source_path", approvedReadRoot.resolve("voice-note.m4a").toString())
          put("destination_path", "media/voice-note.m4a")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID].orEmpty().startsWith("artifact-voice-note-"))
    assertEquals("media/voice-note.m4a", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH])
    assertEquals("voice-note.m4a", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME])
    assertEquals("voice", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT])
    assertEquals("audio/mp4", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE])
  }

  @Test
  fun workspaceMoveFilePublishesAttachmentArtifactMetadataForMovedFile() {
    val workspaceRoot = temporaryFolder.newFolder("artifact-move-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("drafts"))
    Files.write(
      workspaceRoot.resolve("drafts").resolve("report.pdf"),
      "pdf-placeholder".toByteArray(StandardCharsets.UTF_8),
    )
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "workspace_move_file",
        arguments = buildJsonObject {
          put("source_path", "drafts/report.pdf")
          put("destination_path", "deliverables/report.pdf")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID].orEmpty().startsWith("artifact-report-"))
    assertEquals("deliverables/report.pdf", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH])
    assertEquals("report.pdf", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME])
    assertEquals("file", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT])
  }

  private fun dispatcher(
    workspaceRoot: Path,
    readRoots: Set<Path> = setOf(workspaceRoot),
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(workspaceRoot),
      readRoots = readRoots,
    ),
  )

  private fun agentTask(
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
    metadata = metadata,
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest ->
      error("Retry not expected in OpenCrayToolDispatcherAttachmentArtifactTest.")
    },
  )
}
