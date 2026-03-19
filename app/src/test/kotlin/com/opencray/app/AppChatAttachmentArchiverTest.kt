package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.runtime.OpenCrayFinalAttachment
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppChatAttachmentArchiverTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun archivePopulatesVoiceMetadataFromAnalyzerAndPreservesTranscript() {
    val workspaceRoot = temporaryFolder.newFolder("chat-attachment-archiver-voice").toPath()
    val voicePath = workspaceRoot.resolve("outputs/voice-note.m4a")
    Files.createDirectories(voicePath.parent)
    Files.write(voicePath, byteArrayOf(1, 2, 3, 4))

    val archived = AppChatAttachmentArchiver.archive(
      workspaceRoot = workspaceRoot,
      approvedReadRoots = emptySet(),
      sessionId = "session-1",
      attachments = listOf(
        OpenCrayFinalAttachment(
          kind = "voice",
          relativePath = "outputs/voice-note.m4a",
          displayName = "voice-note.m4a",
          transcriptText = "Voice summary",
        ),
      ),
      voiceMetadataAnalyzer = AppAgentWorkspaceVoiceMetadataAnalyzer { _, _ ->
        AppAgentWorkspaceVoiceMetadata(
          durationMs = 4_200L,
          waveformBars = listOf(12, 40, 88),
        )
      },
    )

    val attachment = archived.single()

    assertEquals(ChatAttachmentKind.VOICE, attachment.kind)
    assertEquals(4_200L, attachment.durationMs)
    assertEquals(listOf(12, 40, 88), attachment.waveformBars)
    assertEquals("Voice summary", attachment.transcriptText)
    assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
  }

  @Test
  fun archivePrefersAttachmentProvidedVoiceMetadataOverAnalyzerFallback() {
    val workspaceRoot = temporaryFolder.newFolder("chat-attachment-archiver-provided-metadata").toPath()
    val voicePath = workspaceRoot.resolve("outputs/voice-note.m4a")
    Files.createDirectories(voicePath.parent)
    Files.write(voicePath, byteArrayOf(5, 6, 7, 8))
    var analyzerCalls = 0

    val archived = AppChatAttachmentArchiver.archive(
      workspaceRoot = workspaceRoot,
      approvedReadRoots = emptySet(),
      sessionId = "session-2",
      attachments = listOf(
        OpenCrayFinalAttachment(
          kind = "voice",
          relativePath = "outputs/voice-note.m4a",
          displayName = "voice-note.m4a",
          durationMs = 1_800L,
          waveformBars = listOf(9, 18, 27),
        ),
      ),
      voiceMetadataAnalyzer = AppAgentWorkspaceVoiceMetadataAnalyzer { _, _ ->
        analyzerCalls += 1
        AppAgentWorkspaceVoiceMetadata(
          durationMs = 9_999L,
          waveformBars = listOf(99, 99, 99),
        )
      },
    )

    val attachment = archived.single()

    assertEquals(0, analyzerCalls)
    assertEquals(1_800L, attachment.durationMs)
    assertEquals(listOf(9, 18, 27), attachment.waveformBars)
  }
}
