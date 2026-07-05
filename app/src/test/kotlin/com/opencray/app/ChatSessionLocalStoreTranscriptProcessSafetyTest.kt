package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.store.file.JsonFileChatWorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatSessionLocalStoreTranscriptProcessSafetyTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun appendMessagePreservesConcurrentWorkspaceExtensions() {
    val directory = temporaryFolder.newFolder("chat-store-append-message-concurrent-extension")
    val rawStore = JsonFileChatWorkspaceStore(directory)
    var sessionId: String? = null
    var injectedConcurrentExtension = false
    var clock = 1_700_000_000_000L
    val store = ChatSessionLocalStore(
      directory = directory,
      nowEpochMs = {
        val now = clock++
        if (!injectedConcurrentExtension && sessionId != null) {
          injectedConcurrentExtension = true
          val current = requireNotNull(rawStore.load())
          rawStore.save(
            current.copy(
              extensions = current.extensions + ("concurrent.marker" to "foreground"),
              recordVersion = current.recordVersion + 1,
              updatedAtEpochMs = now,
            ),
          )
        }
        now
      },
    )
    sessionId = store.loadState().activeSession.sessionId
    val activeSessionId = requireNotNull(sessionId)

    store.appendMessage(
      sessionId = activeSessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Background result",
    )

    val restoredWorkspace = requireNotNull(rawStore.load())
    assertEquals("foreground", restoredWorkspace.extensions["concurrent.marker"])
    val restoredSession = requireNotNull(ChatSessionLocalStore(directory).loadSession(activeSessionId))
    assertEquals("Background result", restoredSession.messages.last().text)
  }

  @Test
  fun appendSubmittedTurnPreservesConcurrentWorkspaceExtensions() {
    val directory = temporaryFolder.newFolder("chat-store-submitted-turn-concurrent-extension")
    val rawStore = JsonFileChatWorkspaceStore(directory)
    var sessionId: String? = null
    var injectedConcurrentExtension = false
    var clock = 1_700_000_010_000L
    val store = ChatSessionLocalStore(
      directory = directory,
      nowEpochMs = {
        val now = clock++
        if (!injectedConcurrentExtension && sessionId != null) {
          injectedConcurrentExtension = true
          val current = requireNotNull(rawStore.load())
          rawStore.save(
            current.copy(
              extensions = current.extensions + ("concurrent.marker" to "foreground"),
              recordVersion = current.recordVersion + 1,
              updatedAtEpochMs = now,
            ),
          )
        }
        now
      },
    )
    sessionId = store.loadState().activeSession.sessionId
    val activeSessionId = requireNotNull(sessionId)
    val assistantMessageId = store.reserveMessageId(ChatTranscriptRole.ASSISTANT)

    store.appendSubmittedTurn(
      sessionId = activeSessionId,
      userText = "Run while detached",
      assistantMessageId = assistantMessageId,
      assistantPlaceholderText = "Working",
    )

    val restoredWorkspace = requireNotNull(rawStore.load())
    assertEquals("foreground", restoredWorkspace.extensions["concurrent.marker"])
    val restoredSession = requireNotNull(ChatSessionLocalStore(directory).loadSession(activeSessionId))
    assertEquals(assistantMessageId, restoredSession.messages.last().messageId)
  }

  @Test
  fun mergeVoiceAttachmentMetadataPreservesConcurrentWorkspaceExtensions() {
    val directory = temporaryFolder.newFolder("chat-store-voice-metadata-concurrent-extension")
    val setupStore = ChatSessionLocalStore(directory)
    val sessionId = setupStore.loadState().activeSession.sessionId
    setupStore.appendUserMessage(
      sessionId = sessionId,
      text = "Voice note",
      commandLabel = null,
      attachments = listOf(
        ChatAttachmentEntry(
          attachmentId = "voice-attachment",
          kind = ChatAttachmentKind.VOICE,
          displayName = "voice.m4a",
          localPath = ".opencray/chat-media/$sessionId/message/voice.m4a",
          mimeType = "audio/mp4",
          contentSha256 = "abc123",
        ),
      ),
    )
    val rawStore = JsonFileChatWorkspaceStore(directory)
    var injectedConcurrentExtension = false
    var clock = requireNotNull(rawStore.load()).updatedAtEpochMs + 1
    val store = ChatSessionLocalStore(
      directory = directory,
      nowEpochMs = {
        val now = clock++
        if (!injectedConcurrentExtension) {
          injectedConcurrentExtension = true
          val current = requireNotNull(rawStore.load())
          rawStore.save(
            current.copy(
              extensions = current.extensions + ("concurrent.marker" to "foreground"),
              recordVersion = current.recordVersion + 1,
              updatedAtEpochMs = now,
            ),
          )
        }
        now
      },
    )

    val changed = store.mergeVoiceAttachmentMetadata(
      contentSha256 = " ABC123 ",
      metadata = AppAgentWorkspaceVoiceMetadata(
        durationMs = 2_400L,
        waveformBars = listOf(10, 20, 30),
        transcriptText = "Voice transcript",
      ),
    )

    val restoredWorkspace = requireNotNull(rawStore.load())
    val restoredAttachment = requireNotNull(ChatSessionLocalStore(directory).loadSession(sessionId))
      .messages
      .last()
      .attachments
      .single()
    assertEquals(true, changed)
    assertEquals("foreground", restoredWorkspace.extensions["concurrent.marker"])
    assertEquals(2_400L, restoredAttachment.durationMs)
    assertEquals(listOf(10, 20, 30), restoredAttachment.waveformBars)
    assertEquals("Voice transcript", restoredAttachment.transcriptText)
  }
}
