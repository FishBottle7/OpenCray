package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.runtime.OpenCrayAttachmentArtifact
import com.opencray.runtime.OpenCrayMediaArtifactSource
import com.opencray.runtime.defaultOpenCrayMediaArtifactRegistry
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentWorkspaceMediaGcTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun sweepDeletesOnlyUnreferencedChatMediaAndPrunesStaleRegistryRecords() {
    val workspaceRoot = temporaryFolder.newFolder("workspace").toPath()
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store"),
      nowEpochMs = IncrementingClock(1_000L)::next,
    )
    val sessionId = chatStore.loadState().activeSession.sessionId
    val messageAttachmentPath = ".opencray/chat-media/$sessionId/message/diagram.png"
    val pendingAttachmentPath = ".opencray/chat-media/$sessionId/pending/voice.m4a"
    val orphanAttachmentPath = ".opencray/chat-media/$sessionId/orphan/old.png"
    val publishedPath = "exports/published.png"

    writeBytes(workspaceRoot.resolve(messageAttachmentPath), byteArrayOf(1, 2, 3))
    writeBytes(workspaceRoot.resolve(pendingAttachmentPath), byteArrayOf(4, 5, 6))
    writeBytes(workspaceRoot.resolve(orphanAttachmentPath), byteArrayOf(7, 8, 9))
    writeBytes(workspaceRoot.resolve(publishedPath), byteArrayOf(10, 11, 12))
    chatStore.appendUserMessage(
      sessionId = sessionId,
      text = "Keep image",
      commandLabel = null,
      attachments = listOf(
        ChatAttachmentEntry(
          attachmentId = "attachment-message",
          kind = ChatAttachmentKind.IMAGE,
          displayName = "diagram.png",
          localPath = messageAttachmentPath,
          mimeType = "image/png",
        ),
      ),
    )
    chatStore.enqueuePendingUserInput(
      sessionId = sessionId,
      text = "",
      attachments = listOf(
        ChatAttachmentEntry(
          attachmentId = "attachment-pending",
          kind = ChatAttachmentKind.VOICE,
          displayName = "voice.m4a",
          localPath = pendingAttachmentPath,
          mimeType = "audio/mp4",
        ),
      ),
    )
    val registry = defaultOpenCrayMediaArtifactRegistry(workspaceRoot)
    registry.register(
      artifacts = listOf(
        OpenCrayAttachmentArtifact(
          artifactId = "artifact-live",
          relativePath = publishedPath,
          displayName = "published.png",
          kindHint = "image",
          mimeType = "image/png",
        ),
        OpenCrayAttachmentArtifact(
          artifactId = "artifact-stale",
          relativePath = orphanAttachmentPath,
          displayName = "old.png",
          kindHint = "image",
          mimeType = "image/png",
        ),
      ),
      source = OpenCrayMediaArtifactSource(
        runId = "run-media",
        toolName = "GenerateImage",
        source = "generated",
      ),
    )

    val result = AppAgentWorkspaceMediaGc.sweep(
      workspaceRoot = workspaceRoot,
      chatSessionStore = chatStore,
    )

    assertEquals(1, result.deletedFiles)
    assertEquals(1, result.removedEmptyDirectories)
    assertEquals(1, result.removedRegistryRecords)
    assertTrue(Files.isRegularFile(workspaceRoot.resolve(messageAttachmentPath)))
    assertTrue(Files.isRegularFile(workspaceRoot.resolve(pendingAttachmentPath)))
    assertTrue(Files.isRegularFile(workspaceRoot.resolve(publishedPath)))
    assertFalse(Files.exists(workspaceRoot.resolve(orphanAttachmentPath)))
    assertTrue(registry.resolve("artifact-live") != null)
    assertNull(registry.resolve("artifact-stale"))
  }

  private fun writeBytes(
    path: Path,
    bytes: ByteArray,
  ) {
    Files.createDirectories(path.parent)
    Files.write(path, bytes)
  }

  private class IncrementingClock(
    private var value: Long,
  ) {
    fun next(): Long {
      value += 1L
      return value
    }
  }
}
