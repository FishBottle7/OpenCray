package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAttachmentArtifact
import com.opencray.runtime.OpenCrayAttachmentArtifacts
import com.opencray.runtime.OpenCrayImageReferenceSource
import com.opencray.runtime.OpenCrayImageReferenceSourceKind
import com.opencray.runtime.OpenCrayToolResultEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppImageReferenceSourceResolverFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun createResolvesChatAttachmentsUsingSourceSessionContext() {
    val workspaceRoot = temporaryFolder.newFolder("resolver-factory-workspace-chat").toPath()
    val privateRoot = temporaryFolder.newFolder("resolver-factory-private-chat").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("resolver-factory-chat-store"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val attachment = ChatAttachmentEntry(
      attachmentId = "chat-image-1",
      kind = ChatAttachmentKind.IMAGE,
      displayName = "camera.png",
      localPath = ".opencray/chat-media/$sessionId/camera.png",
      mimeType = "image/png",
    )
    val attachmentPath = writeFile(workspaceRoot, attachment.localPath)
    chatStore.appendUserMessage(
      sessionId = sessionId,
      text = "See this image.",
      commandLabel = null,
      attachments = listOf(attachment),
    )
    val resolver = AppImageReferenceSourceResolverFactory(
      workspaceRootProvider = { workspaceRoot },
      privateRootProvider = { privateRoot },
      chatSessionStore = chatStore,
      runArtifactCatalog = AppRunArtifactCatalog(
        workspaceRootProvider = { workspaceRoot },
        runtimeEventsProvider = { emptyList() },
      ),
      settingsImageAssetStore = AppSettingsImageAssetStore(
        directory = temporaryFolder.newFolder("resolver-factory-settings-assets-chat"),
      ),
    ).create()

    val resolved = resolver.resolve(
      OpenCrayImageReferenceSource(
        sourceKind = OpenCrayImageReferenceSourceKind.CHAT_ATTACHMENT,
        chatAttachmentId = "chat-image-1",
        sourceSessionId = sessionId,
      ),
    )

    assertNotNull(resolved)
    requireNotNull(resolved)
    assertEquals(attachmentPath.toAbsolutePath().normalize(), resolved.path)
    assertEquals("camera.png", resolved.displayName)
    assertEquals("image/png", resolved.mimeType)
    assertEquals(sessionId, resolved.sourceSessionId)
    assertNotNull(resolved.sourceMessageId)
  }

  @Test
  fun createResolvesRunArtifactsUsingSessionScopedCatalog() {
    val workspaceRoot = temporaryFolder.newFolder("resolver-factory-workspace-artifact").toPath()
    val privateRoot = temporaryFolder.newFolder("resolver-factory-private-artifact").toPath()
    val sessionId = "session-artifact"
    val artifact = OpenCrayAttachmentArtifact(
      artifactId = "artifact-render-1",
      relativePath = "media/generated/render.png",
      displayName = "render.png",
      kindHint = "image",
      mimeType = "image/png",
    )
    val artifactPath = writeFile(workspaceRoot, artifact.relativePath)
    val resolver = AppImageReferenceSourceResolverFactory(
      workspaceRootProvider = { workspaceRoot },
      privateRootProvider = { privateRoot },
      chatSessionStore = ChatSessionLocalStore(temporaryFolder.newFolder("resolver-factory-chat-store-artifact")),
      runArtifactCatalog = AppRunArtifactCatalog(
        workspaceRootProvider = { workspaceRoot },
        runtimeEventsProvider = { requestedSessionId ->
          if (requestedSessionId == sessionId) {
            listOf(
              toolResultEvent(
                runId = "run-1",
                artifact = artifact,
                emittedAtEpochMs = 1L,
              ),
            )
          } else {
            emptyList()
          }
        },
      ),
      settingsImageAssetStore = AppSettingsImageAssetStore(
        directory = temporaryFolder.newFolder("resolver-factory-settings-assets-artifact"),
      ),
    ).create()

    val resolved = resolver.resolve(
      OpenCrayImageReferenceSource(
        sourceKind = OpenCrayImageReferenceSourceKind.RUN_ARTIFACT,
        artifactId = artifact.artifactId,
        sourceSessionId = sessionId,
      ),
    )

    assertNotNull(resolved)
    requireNotNull(resolved)
    assertEquals(artifactPath.toAbsolutePath().normalize(), resolved.path)
    assertEquals("render.png", resolved.displayName)
  }

  @Test
  fun createRejectsChatAttachmentResolutionWithoutSourceSessionId() {
    val workspaceRoot = temporaryFolder.newFolder("resolver-factory-workspace-missing-session").toPath()
    val privateRoot = temporaryFolder.newFolder("resolver-factory-private-missing-session").toPath()
    val resolver = AppImageReferenceSourceResolverFactory(
      workspaceRootProvider = { workspaceRoot },
      privateRootProvider = { privateRoot },
      chatSessionStore = ChatSessionLocalStore(temporaryFolder.newFolder("resolver-factory-chat-store-missing-session")),
      runArtifactCatalog = AppRunArtifactCatalog(
        workspaceRootProvider = { workspaceRoot },
        runtimeEventsProvider = { emptyList() },
      ),
      settingsImageAssetStore = AppSettingsImageAssetStore(
        directory = temporaryFolder.newFolder("resolver-factory-settings-assets-missing-session"),
      ),
    ).create()

    val resolved = resolver.resolve(
      OpenCrayImageReferenceSource(
        sourceKind = OpenCrayImageReferenceSourceKind.CHAT_ATTACHMENT,
        chatAttachmentId = "chat-image-1",
      ),
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
