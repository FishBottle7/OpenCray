package com.opencray.app

import com.opencray.app.facade.safety.EmptySafetySettingsFacade
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAttachmentArtifact
import com.opencray.runtime.OpenCrayAttachmentArtifactMetadataKeys
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayMediaArtifactSource
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.defaultOpenCrayMediaArtifactRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ServiceOwnedChatSubmissionAccessAttachmentTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun submitChatMessageResolvesArtifactReferencesBeforeArchiving() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-artifact-ref"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("artifact-ref-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(workspaceRoot.resolve("outputs/diagram.png"), byteArrayOf(1, 2, 3, 4))
    val runtimeHostAccess = RecordingRuntimeHostAccess(sessionId)
    runtimeHostAccess.runEventJournalStore(sessionId).append(
      OpenCrayToolResultEvent(
        runId = "run-artifact",
        taskId = "task-artifact",
        turn = 0,
        call = AgentToolCall(toolName = "GenerateImage"),
        result = AgentToolResult(
          toolName = "GenerateImage",
          status = AgentToolResultStatus.SUCCESS,
          content = "Generated image artifact.",
          metadata = mapOf(
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACTS_JSON to Json.encodeToString(
              ListSerializer(OpenCrayAttachmentArtifact.serializer()),
              listOf(
                OpenCrayAttachmentArtifact(
                  artifactId = "artifact-diagram-1",
                  relativePath = "outputs/diagram.png",
                  displayName = "diagram.png",
                  kindHint = "image",
                  mimeType = "image/png",
                ),
              ),
            ),
          ),
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
    val access = submissionAccess(
      chatStore = chatStore,
      runtimeHostAccess = runtimeHostAccess,
      workspaceRoot = workspaceRoot,
    )

    access.submitChatMessage(
      text = "Reuse the generated image",
      attachments = listOf(OpenCrayFinalAttachment(artifactId = "artifact-diagram-1")),
    )

    val userMessage = requireNotNull(
      chatStore.loadState().activeSession.messages.lastOrNull { message ->
        message.role == ChatTranscriptRole.USER
      },
    )
    val archivedAttachment = userMessage.attachments.single()

    assertEquals(ChatAttachmentKind.IMAGE, archivedAttachment.kind)
    assertEquals("diagram.png", archivedAttachment.displayName)
    assertTrue(archivedAttachment.localPath.startsWith(".opencray/chat-media/$sessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(archivedAttachment.localPath)))
  }

  @Test
  fun submitChatMessageResolvesArtifactReferencesFromWorkspaceRegistry() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-registry-artifact-ref"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("registry-artifact-ref-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve(".opencray/generated-media/images"))
    Files.write(
      workspaceRoot.resolve(".opencray/generated-media/images/diagram.png"),
      byteArrayOf(1, 2, 3, 4),
    )
    defaultOpenCrayMediaArtifactRegistry(workspaceRoot).register(
      artifacts = listOf(
        OpenCrayAttachmentArtifact(
          artifactId = "artifact-registry-diagram-1",
          relativePath = ".opencray/generated-media/images/diagram.png",
          displayName = "diagram.png",
          kindHint = "image",
          mimeType = "image/png",
        ),
      ),
      source = OpenCrayMediaArtifactSource(
        runId = "run-other-session",
        toolName = "GenerateImage",
        source = "generated",
      ),
    )
    val runtimeHostAccess = RecordingRuntimeHostAccess(sessionId)
    val access = submissionAccess(
      chatStore = chatStore,
      runtimeHostAccess = runtimeHostAccess,
      workspaceRoot = workspaceRoot,
    )

    access.submitChatMessage(
      text = "Reuse a registry image",
      attachments = listOf(OpenCrayFinalAttachment(artifactId = "artifact-registry-diagram-1")),
    )

    val userMessage = requireNotNull(
      chatStore.loadState().activeSession.messages.lastOrNull { message ->
        message.role == ChatTranscriptRole.USER
      },
    )
    val archivedAttachment = userMessage.attachments.single()

    assertEquals(ChatAttachmentKind.IMAGE, archivedAttachment.kind)
    assertEquals("diagram.png", archivedAttachment.displayName)
    assertTrue(archivedAttachment.localPath.startsWith(".opencray/chat-media/$sessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(archivedAttachment.localPath)))
  }

  @Test
  fun submitChatMessageResolvesChatAttachmentReferencesBeforeArchiving() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-chat-ref"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-ref-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("seed"))
    Files.write(workspaceRoot.resolve("seed/report.pdf"), byteArrayOf(9, 8, 7, 6))
    chatStore.appendSubmittedTurn(
      sessionId = sessionId,
      userText = "Seed attachment",
      assistantMessageId = "assistant-seed",
      assistantPlaceholderText = "Thinking",
      attachments = listOf(
        ChatAttachmentEntry(
          attachmentId = "chat-attachment-1",
          kind = ChatAttachmentKind.FILE,
          displayName = "report.pdf",
          localPath = "seed/report.pdf",
          mimeType = "application/pdf",
          sizeBytes = 4,
        ),
      ),
    )
    val runtimeHostAccess = RecordingRuntimeHostAccess(sessionId)
    val access = submissionAccess(
      chatStore = chatStore,
      runtimeHostAccess = runtimeHostAccess,
      workspaceRoot = workspaceRoot,
    )

    access.submitChatMessage(
      text = "Reuse the uploaded file",
      attachments = listOf(OpenCrayFinalAttachment(chatAttachmentId = "chat-attachment-1")),
    )

    val userMessages = chatStore.loadState().activeSession.messages.filter { message ->
      message.role == ChatTranscriptRole.USER
    }
    val archivedAttachment = userMessages.last().attachments.single()

    assertEquals(ChatAttachmentKind.FILE, archivedAttachment.kind)
    assertEquals("report.pdf", archivedAttachment.displayName)
    assertTrue(archivedAttachment.localPath.startsWith(".opencray/chat-media/$sessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(archivedAttachment.localPath)))
  }

  @Test
  fun submitChatMessageReferenceKeepsResolvedVoiceKindEvenWhenCallerSendsFileKind() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-chat-voice-ref"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-voice-ref-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("seed"))
    Files.write(workspaceRoot.resolve("seed/voice-note.m4a"), byteArrayOf(5, 4, 3, 2))
    chatStore.appendSubmittedTurn(
      sessionId = sessionId,
      userText = "Seed voice attachment",
      assistantMessageId = "assistant-seed-voice",
      assistantPlaceholderText = "Thinking",
      attachments = listOf(
        ChatAttachmentEntry(
          attachmentId = "chat-voice-1",
          kind = ChatAttachmentKind.VOICE,
          displayName = "voice-note.m4a",
          localPath = "seed/voice-note.m4a",
          mimeType = "audio/mp4",
          sizeBytes = 4,
          durationMs = 1_200L,
          transcriptText = "voice note",
        ),
      ),
    )
    val runtimeHostAccess = RecordingRuntimeHostAccess(sessionId)
    val access = submissionAccess(
      chatStore = chatStore,
      runtimeHostAccess = runtimeHostAccess,
      workspaceRoot = workspaceRoot,
    )

    access.submitChatMessage(
      text = "Reuse the uploaded voice note",
      attachments = listOf(
        OpenCrayFinalAttachment(
          kind = "file",
          relativePath = "seed/voice-note.m4a",
          chatAttachmentId = "chat-voice-1",
        ),
      ),
    )

    val userMessages = chatStore.loadState().activeSession.messages.filter { message ->
      message.role == ChatTranscriptRole.USER
    }
    val archivedAttachment = userMessages.last().attachments.single()

    assertEquals(ChatAttachmentKind.VOICE, archivedAttachment.kind)
    assertEquals("voice-note.m4a", archivedAttachment.displayName)
    assertEquals(1_200L, archivedAttachment.durationMs)
    assertEquals("voice note", archivedAttachment.transcriptText)
    assertTrue(archivedAttachment.localPath.startsWith(".opencray/chat-media/$sessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(archivedAttachment.localPath)))
  }

  @Test
  fun submitChatMessageVoiceReferenceKeepsVoiceMetadataAfterRecallRemovesSessionAttachment() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-chat-voice-recall"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-voice-recall-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("seed"))
    Files.write(workspaceRoot.resolve("seed/voice-note.m4a"), byteArrayOf(7, 6, 5, 4))
    chatStore.appendSubmittedTurn(
      sessionId = sessionId,
      userText = "Seed recalled voice attachment",
      assistantMessageId = "assistant-seed-voice-recall",
      assistantPlaceholderText = "Thinking",
      attachments = listOf(
        ChatAttachmentEntry(
          attachmentId = "chat-voice-recalled-1",
          kind = ChatAttachmentKind.VOICE,
          displayName = "voice-note.m4a",
          localPath = "seed/voice-note.m4a",
          mimeType = "audio/mp4",
          sizeBytes = 4,
          durationMs = 2_300L,
          waveformBars = listOf(5, 10, 15),
          transcriptText = "recalled voice note",
        ),
      ),
    )
    val recalledMessageId = requireNotNull(
      chatStore.loadState().activeSession.messages.firstOrNull { message ->
        message.role == ChatTranscriptRole.USER
      }?.messageId,
    )
    chatStore.recallMessageCascade(sessionId, recalledMessageId)
    val runtimeHostAccess = RecordingRuntimeHostAccess(sessionId)
    val access = submissionAccess(
      chatStore = chatStore,
      runtimeHostAccess = runtimeHostAccess,
      workspaceRoot = workspaceRoot,
    )

    access.submitChatMessage(
      text = "Reuse the recalled voice note",
      attachments = listOf(
        OpenCrayFinalAttachment(
          kind = "voice",
          relativePath = "seed/voice-note.m4a",
          chatAttachmentId = "chat-voice-recalled-1",
          displayName = "voice-note.m4a",
          mimeType = "audio/mp4",
          durationMs = 2_300L,
          waveformBars = listOf(5, 10, 15),
          transcriptText = "recalled voice note",
        ),
      ),
    )

    val userMessage = requireNotNull(
      chatStore.loadState().activeSession.messages.lastOrNull { message ->
        message.role == ChatTranscriptRole.USER
      },
    )
    val archivedAttachment = userMessage.attachments.single()

    assertEquals(ChatAttachmentKind.VOICE, archivedAttachment.kind)
    assertEquals("voice-note.m4a", archivedAttachment.displayName)
    assertEquals(2_300L, archivedAttachment.durationMs)
    assertEquals(listOf(5, 10, 15), archivedAttachment.waveformBars)
    assertEquals("recalled voice note", archivedAttachment.transcriptText)
    assertTrue(archivedAttachment.localPath.startsWith(".opencray/chat-media/$sessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(archivedAttachment.localPath)))
  }

  @Test
  fun submitChatMessageFailsWhenAttachmentReferenceCannotBeResolved() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-missing-ref"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("missing-ref-workspace").toPath()
    val runtimeHostAccess = RecordingRuntimeHostAccess(sessionId)
    val access = submissionAccess(
      chatStore = chatStore,
      runtimeHostAccess = runtimeHostAccess,
      workspaceRoot = workspaceRoot,
    )

    try {
      access.submitChatMessage(
        text = "Reuse the missing file",
        attachments = listOf(OpenCrayFinalAttachment(chatAttachmentId = "missing-attachment")),
      )
      fail("Expected unresolved attachment references to fail.")
    } catch (expected: IllegalArgumentException) {
      assertTrue(
        expected.message.orEmpty().contains("missing-attachment"),
      )
    }
  }

  private fun submissionAccess(
    chatStore: ChatSessionLocalStore,
    runtimeHostAccess: OpenCrayRuntimeHostAccess,
    workspaceRoot: java.nio.file.Path,
  ): ServiceOwnedChatSubmissionAccess = ServiceOwnedChatSubmissionAccess(
    chatSessionStore = chatStore,
    runtimeHostAccess = runtimeHostAccess,
    safetySettingsFacade = EmptySafetySettingsFacade,
    workspaceRootProvider = { workspaceRoot },
  )

  private class RecordingRuntimeHostAccess(
    sessionId: String,
  ) : OpenCrayRuntimeHostAccess {
    private val runtimeSession = RecordingRuntimeSessionAccess(sessionId)
    private val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    private val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    private val supplementStore = InMemorySessionSupplementStore()

    override val lifecycleDescriptor: HostRuntimeLifecycleDescriptor =
      HostRuntimeLifecycleDescriptor()

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun activeWorkSummary(): RuntimeOwnerWorkSummary = RuntimeOwnerWorkSummary(
      trackedSessionCount = 1,
      activeRunCount = 0,
    )

    override fun session(sessionId: String): OpenCrayRuntimeSessionAccess = runtimeSession

    override fun releaseSession(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit

    override fun runEventJournalStore(sessionId: String): RunEventJournalStore =
      runEventJournalStoreFactory.forChatSession(sessionId)

    override fun promptCheckpointStore(sessionId: String): PromptCheckpointStore =
      promptCheckpointStoreFactory.forChatSession(sessionId)

    override fun supplementStore(sessionId: String): SessionSupplementStore = supplementStore

    override fun markApprovalApproved(
      sessionId: String,
      taskId: String,
      toolName: String?,
      promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
      subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
    ) = Unit

    override fun markApprovalRejected(
      sessionId: String,
      taskId: String,
      toolName: String?,
      promptResumeState: com.opencray.runtime.OpenCrayPromptResumeState?,
      subAgentApprovalResume: com.opencray.runtime.subagent.SubAgentApprovalResume?,
    ) = Unit

    override fun clearApproval(sessionId: String, taskId: String) = Unit

    override fun retainKnownApprovalTasks(sessionId: String, taskIds: Set<String>) = Unit

    override fun isApprovalApproved(sessionId: String, taskId: String): Boolean = false

    override fun isApprovalRejected(sessionId: String, taskId: String): Boolean = false
  }

  private class RecordingRuntimeSessionAccess(
    override val sessionId: String,
  ) : OpenCrayRuntimeSessionAccess {
    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission = AgentRunSubmission(
      sessionId = sessionId,
      runId = "run-$sessionId",
      taskId = "task-$sessionId",
      acceptedAtEpochMs = 1_000L,
    )

    override fun submitTask(task: AgentTask): AgentRunSubmission =
      throw UnsupportedOperationException("submitTask is not used in this test.")

    override fun ensureProcessing() = Unit

    override fun requestCancel(taskId: String): Boolean = false

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean = false

    override fun listRuns(): List<AgentRunSnapshot> = emptyList()

    override fun findRun(runId: String): AgentRunSnapshot? = null

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = null

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState = SessionLifecycleState.IDLE

    override fun snapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 0L,
      tasks = emptyList(),
    )

    override fun hasPendingWork(): Boolean = false

    override fun listManagedProcesses(): List<ManagedProcessSnapshot> = emptyList()

    override fun hasLiveManagedProcesses(): Boolean = false

    override fun terminateRunningManagedProcesses(): List<ManagedProcessSnapshot> = emptyList()
  }
}
