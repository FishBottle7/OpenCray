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
import org.junit.Assert.assertFalse
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

  @Test
  fun importChatAttachmentPublishesArtifactMetadataWithoutLeakingChatStoragePath() {
    val workspaceRoot = temporaryFolder.newFolder("artifact-chat-attachment-workspace").toPath()
    val sourcePath = workspaceRoot
      .resolve(".opencray")
      .resolve("chat-media")
      .resolve("session-1")
      .resolve("hash")
      .resolve("camera-first.jpg")
    Files.createDirectories(sourcePath.parent)
    Files.write(sourcePath, byteArrayOf(7, 8, 9))
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      chatAttachmentResolver = { attachmentId ->
        if (attachmentId == "user-image-1") {
          OpenCrayChatAttachmentSource(
            attachmentId = "user-image-1",
            displayName = "camera-first.jpg",
            sourcePath = sourcePath,
            mimeType = "image/jpeg",
          )
        } else {
          null
        }
      },
    )

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "import_chat_attachment",
        arguments = buildJsonObject {
          put("chat_attachment_id", "user-image-1")
          put("destination_path", "imports/camera-first.jpg")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(Files.exists(workspaceRoot.resolve("imports").resolve("camera-first.jpg")))
    assertTrue(result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID].orEmpty().startsWith("artifact-camera-first-"))
    assertEquals("imports/camera-first.jpg", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH])
    assertEquals("camera-first.jpg", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME])
    assertEquals("image", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT])
    assertEquals("image/jpeg", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE])
    assertFalse(result.content.contains(".opencray/chat-media"))
    assertFalse(result.metadata.values.any { value ->
      value.contains(".opencray/chat-media") || value.contains(sourcePath.toString())
    })
  }

  @Test
  fun viewWorkspaceImagePublishesPromptSupplementAttachmentMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("view-workspace-image-workspace").toPath()
    val imagePath = workspaceRoot.resolve("screens").resolve("camera-first.png")
    Files.createDirectories(imagePath.parent)
    Files.write(imagePath, byteArrayOf(1, 2, 3, 4))
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "view_workspace_image",
        arguments = buildJsonObject {
          put("path", "screens/camera-first.png")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("screens/camera-first.png", result.metadata["path"])
    assertEquals("camera-first.png", result.metadata["displayName"])
    assertEquals("image/png", result.metadata["mimeType"])
    val supplementText = OpenCrayPromptSupplementMetadata.decodeText(result.metadata)
    assertTrue(supplementText.orEmpty().contains("screens/camera-first.png"))
    val attachments = OpenCrayPromptSupplementMetadata.decodeAttachments(
      metadata = result.metadata,
      json = dispatcherJson,
    )
    assertEquals(1, attachments.size)
    assertEquals("camera-first.png", attachments.single().displayName)
    assertEquals("image/png", attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        imagePath,
        java.nio.file.Paths.get(requireNotNull(attachments.single().filePath)),
      ),
    )
  }

  @Test
  fun viewWorkspacePdfPublishesPromptSupplementAttachmentMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("view-workspace-pdf-workspace").toPath()
    val pdfPath = workspaceRoot.resolve("docs").resolve("report.pdf")
    Files.createDirectories(pdfPath.parent)
    Files.write(pdfPath, byteArrayOf(0x25, 0x50, 0x44, 0x46))
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "view_workspace_pdf",
        arguments = buildJsonObject {
          put("path", "docs/report.pdf")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("docs/report.pdf", result.metadata["path"])
    assertEquals("report.pdf", result.metadata["displayName"])
    assertEquals("application/pdf", result.metadata["mimeType"])
    val supplementText = OpenCrayPromptSupplementMetadata.decodeText(result.metadata)
    assertTrue(supplementText.orEmpty().contains("docs/report.pdf"))
    val attachments = OpenCrayPromptSupplementMetadata.decodeAttachments(
      metadata = result.metadata,
      json = dispatcherJson,
    )
    assertEquals(1, attachments.size)
    assertEquals("report.pdf", attachments.single().displayName)
    assertEquals("application/pdf", attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        pdfPath,
        java.nio.file.Paths.get(requireNotNull(attachments.single().filePath)),
      ),
    )
  }

  @Test
  fun viewWorkspaceDocumentPublishesPromptSupplementAttachmentMetadataForImage() {
    val workspaceRoot = temporaryFolder.newFolder("view-workspace-document-image").toPath()
    val imagePath = workspaceRoot.resolve("screens").resolve("camera-first.png")
    Files.createDirectories(imagePath.parent)
    Files.write(imagePath, byteArrayOf(1, 2, 3, 4))
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "view_workspace_document",
        arguments = buildJsonObject {
          put("path", "screens/camera-first.png")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("image", result.metadata["documentKind"])
    val attachments = OpenCrayPromptSupplementMetadata.decodeAttachments(
      metadata = result.metadata,
      json = dispatcherJson,
    )
    assertEquals(1, attachments.size)
    assertEquals("camera-first.png", attachments.single().displayName)
    assertEquals("image/png", attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        imagePath,
        java.nio.file.Paths.get(requireNotNull(attachments.single().filePath)),
      ),
    )
  }

  @Test
  fun viewWorkspaceDocumentPublishesPromptSupplementAttachmentMetadataForPdf() {
    val workspaceRoot = temporaryFolder.newFolder("view-workspace-document-pdf").toPath()
    val pdfPath = workspaceRoot.resolve("docs").resolve("report.pdf")
    Files.createDirectories(pdfPath.parent)
    Files.write(pdfPath, byteArrayOf(0x25, 0x50, 0x44, 0x46))
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "view_workspace_document",
        arguments = buildJsonObject {
          put("path", "docs/report.pdf")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("pdf", result.metadata["documentKind"])
    val attachments = OpenCrayPromptSupplementMetadata.decodeAttachments(
      metadata = result.metadata,
      json = dispatcherJson,
    )
    assertEquals(1, attachments.size)
    assertEquals("report.pdf", attachments.single().displayName)
    assertEquals("application/pdf", attachments.single().mimeType)
    assertTrue(
      Files.isSameFile(
        pdfPath,
        java.nio.file.Paths.get(requireNotNull(attachments.single().filePath)),
      ),
    )
  }

  @Test
  fun searchWorkspaceDocumentUsesConfiguredProviderAndPublishesSearchMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("search-workspace-document").toPath()
    val pdfPath = workspaceRoot.resolve("docs").resolve("report.pdf")
    Files.createDirectories(pdfPath.parent)
    Files.write(pdfPath, byteArrayOf(0x25, 0x50, 0x44, 0x46))
    var capturedPath: Path? = null
    var capturedRequest: WorkspaceDocumentSearchRequest? = null
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      documentSearchProvider = object : WorkspaceDocumentSearchProvider {
        override fun search(
          path: Path,
          request: WorkspaceDocumentSearchRequest,
        ): WorkspaceDocumentSearchResult {
          capturedPath = path
          capturedRequest = request
          return WorkspaceDocumentSearchResult(
            documentKind = WorkspaceDocumentKind.PDF,
            pageCount = 8,
            query = request.query,
            hits = listOf(
              WorkspaceDocumentSearchHit(
                pageNumber = 3,
                excerpt = "Quarterly revenue recognized in Q4.",
                matchCount = 2,
              ),
            ),
          )
        }
      },
    )

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "search_workspace_document",
        arguments = buildJsonObject {
          put("path", "docs/report.pdf")
          put("query", "revenue")
          put("page_from", 2)
          put("page_to", 4)
          put("max_results", 3)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(Files.isSameFile(pdfPath, requireNotNull(capturedPath)))
    assertEquals("revenue", capturedRequest?.query)
    assertEquals(2, capturedRequest?.pageFrom)
    assertEquals(4, capturedRequest?.pageTo)
    assertEquals(3, capturedRequest?.maxResults)
    assertTrue(result.content.contains("Workspace document search: docs/report.pdf"))
    assertTrue(result.content.contains("page 3 matches=2"))
    assertEquals("pdf", result.metadata["documentKind"])
    assertEquals("8", result.metadata["pageCount"])
    assertEquals("1", result.metadata["hitCount"])
    assertEquals("revenue", result.metadata["query"])
  }

  private fun dispatcher(
    workspaceRoot: Path,
    readRoots: Set<Path> = setOf(workspaceRoot),
    chatAttachmentResolver: ((String) -> OpenCrayChatAttachmentSource?)? = null,
    documentSearchProvider: WorkspaceDocumentSearchProvider = DefaultWorkspaceDocumentSearchProvider(),
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(workspaceRoot),
      readRoots = readRoots,
      chatAttachmentResolver = chatAttachmentResolver,
      documentSearchProvider = documentSearchProvider,
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

  companion object {
    private val dispatcherJson = kotlinx.serialization.json.Json {
      prettyPrint = true
      ignoreUnknownKeys = true
    }
  }
}
