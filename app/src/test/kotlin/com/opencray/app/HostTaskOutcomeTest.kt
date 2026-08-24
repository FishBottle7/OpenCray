package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAttachmentArtifact
import com.opencray.runtime.OpenCrayAttachmentArtifactMetadataKeys
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.memory.TaskCommitmentIntentAction
import com.opencray.runtime.memory.TaskCommitmentIntentDecision
import com.opencray.runtime.memory.TaskCommitmentIntentInterpretation
import com.opencray.runtime.memory.MemoryWriter
import com.opencray.runtime.memory.TaskCommitmentResolver
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTaskOutcomeTest : HostRuntimeTestBase() {
  @Test
  fun taskFailureUsesSetupHintWhenLlmConfigIsMissing() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-missing-llm"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need live output")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.FAILED,
        errorCode = AppAgentSessionTaskRuntimeFactory.ERROR_CODE_MISSING_LLM_CONFIG,
        errorMessage = "LLM configuration is incomplete.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals("Missing LLM", messages.last().text)
  }

  @Test
  fun taskFailureUsesProviderErrorWhenLlmConfigExists() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-provider-failure"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need live output")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.FAILED,
        errorCode = "HTTP_401",
        errorMessage = "Invalid API key.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals("Failed: Invalid API key.", messages.last().text)
  }

  @Test
  fun taskSuccessRedactsInternalToolPayloadFromChatAndDrawerPreview() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-payload-success"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need a clean answer")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val snapshot = hostRuntime.loadChatSnapshot()
    val drawer = snapshot["drawer"] as Map<*, *>
    val sessions = drawer["sessions"] as List<*>
    val firstSession = sessions.first() as Map<*, *>

    assertEquals(
      "The agent produced an internal tool payload instead of a user-facing reply.",
      messages.last().text,
    )
    assertEquals(
      "The agent produced an internal tool payload instead of a user-facing reply.",
      firstSession["preview"],
    )
  }

  @Test
  fun taskSuccessExtractsVisibleAnswerFromStructuredProtocolOutput() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-structured-protocol-success"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Search for OpenCray")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout =
          """{"type":"tool_call","tool_name":"WebSearch","arguments":{"query":"OpenCray"}}{"type":"final","answer":"OpenCray is an open-source mobile agent app."}""",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val snapshot = hostRuntime.loadChatSnapshot()
    val drawer = snapshot["drawer"] as Map<*, *>
    val sessions = drawer["sessions"] as List<*>
    val firstSession = sessions.first() as Map<*, *>

    assertEquals(
      "OpenCray is an open-source mobile agent app.",
      messages.last().text,
    )
    assertEquals(
      "OpenCray is an open-source mobile agent app.",
      firstSession["preview"],
    )
  }

  @Test
  fun taskFailureDoesNotSurfaceSuccessfulToolSummaryInFinalAssistantBubbleForPromptRuns() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-summary-fallback"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Read the README and answer.")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolCallEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(
          toolName = "Read",
          arguments = buildJsonObject {
            put("file_path", "README.md")
            put("offset", 1)
            put("limit", 2)
          },
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "Read"),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "README preview",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "1",
            "limit" to "2",
            "returnedLineCount" to "2",
            "totalLineCount" to "12",
          ),
        ),
        emittedAtEpochMs = 1_001L,
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.FAILED,
        errorCode = "PROVIDER_EMPTY_RESPONSE",
        errorMessage = "Provider returned an empty completion payload.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_002L,
        metadata = task.metadata + mapOf(
          "responseFormat" to "llm_failure",
          "llmStatus" to "FAILED",
          LiteLlmMetadataKeys.LAST_SUCCESSFUL_TOOL_NAME to "Read",
        ),
      ),
    )

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val finalText = messages.last().text.orEmpty()

    assertFalse(finalText.contains("Read README.md"))
    assertFalse(finalText.contains("README preview"))
    assertTrue(finalText.contains("Provider returned an empty completion payload."))
  }

  @Test
  fun taskSuccessArchivesAssistantAttachmentsIntoWorkspaceMediaStore() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-assistant-attachments"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-media-workspace").toPath()
    val approvedExternalRoot = temporaryFolder.newFolder("chat-media-approved").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(workspaceRoot.resolve("outputs").resolve("diagram.png"), byteArrayOf(1, 2, 3, 4))
    Files.write(approvedExternalRoot.resolve("voice-note.m4a"), byteArrayOf(5, 6, 7, 8))
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = setOf(approvedExternalRoot),
          summary = approvedExternalRoot.toString(),
        )
      },
    )

    hostRuntime.submitChatMessage("Send the generated media.")
    val task = handle.submittedTasks.single()
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          kind = "image",
          relativePath = "outputs/diagram.png",
          displayName = "diagram.png",
        ),
        OpenCrayFinalAttachment(
          kind = "audio",
          path = approvedExternalRoot.resolve("voice-note.m4a").toString(),
          displayName = "voice-note.m4a",
          durationMs = 4_200L,
          waveformBars = listOf(12, 40, 88),
          transcriptText = "Voice summary",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
    val snapshot = hostRuntime.loadChatSnapshot()
    val snapshotMessages = (snapshot["messages"] as List<*>).map { it as Map<*, *> }
    val snapshotAttachments =
      (snapshotMessages.last()["attachments"] as List<*>).map { it as Map<*, *> }

    assertEquals(null, assistantMessage.text)
    assertEquals(2, assistantMessage.attachments.size)
    assertEquals(ChatAttachmentKind.IMAGE, assistantMessage.attachments.first().kind)
    assertEquals(ChatAttachmentKind.VOICE, assistantMessage.attachments.last().kind)
    assertEquals(4_200L, assistantMessage.attachments.last().durationMs)
    assertEquals(listOf(12, 40, 88), assistantMessage.attachments.last().waveformBars)
    assertEquals("Voice summary", assistantMessage.attachments.last().transcriptText)
    assistantMessage.attachments.forEach { attachment ->
      assertTrue(attachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
      assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
    }
    assertEquals(2, snapshotAttachments.size)
    assertEquals("image", snapshotAttachments.first()["kind"])
    assertEquals("voice", snapshotAttachments.last()["kind"])
    assertEquals(4_200L, snapshotAttachments.last()["durationMs"])
    assertEquals(listOf(12, 40, 88), snapshotAttachments.last()["waveformBars"])
    assertEquals("Voice summary", snapshotAttachments.last()["transcriptText"])
  }

  @Test
  fun taskSuccessResolvesArtifactOnlyAttachmentsIntoWorkspaceMediaStore() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-assistant-artifact-attachments"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-artifact-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(workspaceRoot.resolve("outputs").resolve("diagram.png"), byteArrayOf(1, 2, 3, 4))
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )

    hostRuntime.submitChatMessage("Send the generated image by artifact id.")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "workspace_write_file"),
        result = AgentToolResult(
          toolName = "workspace_write_file",
          status = AgentToolResultStatus.SUCCESS,
          content = "Wrote outputs/diagram.png successfully.",
          metadata = mapOf(
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID to "artifact-diagram-1234abcd",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH to "outputs/diagram.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME to "diagram.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT to "image",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE to "image/png",
          ),
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          artifactId = "artifact-diagram-1234abcd",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
    val attachment = assistantMessage.attachments.single()

    assertEquals(null, assistantMessage.text)
    assertEquals(ChatAttachmentKind.IMAGE, attachment.kind)
    assertEquals("diagram.png", attachment.displayName)
    assertEquals("image/png", attachment.mimeType)
    assertTrue(attachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
  }

  @Test
  fun taskSuccessKeepsResolvedFinalAttachmentsWhenOneReferenceIsMissing() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-assistant-mixed-artifact-attachments"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-mixed-artifact-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(workspaceRoot.resolve("outputs").resolve("diagram.png"), byteArrayOf(1, 2, 3, 4))
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )

    hostRuntime.submitChatMessage("Send the generated image by artifact id.")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "workspace_write_file"),
        result = AgentToolResult(
          toolName = "workspace_write_file",
          status = AgentToolResultStatus.SUCCESS,
          content = "Wrote outputs/diagram.png successfully.",
          metadata = mapOf(
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID to "artifact-diagram-1234abcd",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH to "outputs/diagram.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME to "diagram.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT to "image",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE to "image/png",
          ),
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          artifactId = "artifact-diagram-1234abcd",
        ),
        OpenCrayFinalAttachment(
          artifactId = "artifact-stale-missing",
          kind = "image",
          displayName = "missing.png",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
    val attachment = assistantMessage.attachments.single()

    assertEquals(ChatAttachmentKind.IMAGE, attachment.kind)
    assertEquals("diagram.png", attachment.displayName)
    assertEquals("image/png", attachment.mimeType)
    assertTrue(attachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
    assertTrue(assistantMessage.text.orEmpty().contains("Attachment could not be saved"))
    assertTrue(assistantMessage.text.orEmpty().contains("1 attachment was missing"))
  }

  @Test
  fun taskSuccessShowsFailureTextWhenAssistantAttachmentCannotBeArchived() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-assistant-attachment-failure"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-attachment-failure-workspace").toPath()
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )

    hostRuntime.submitChatMessage("Send the generated image.")
    val task = handle.submittedTasks.single()
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          kind = "image",
          relativePath = "outputs/missing.png",
          displayName = "missing.png",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()

    assertEquals(emptyList<ChatAttachmentEntry>(), assistantMessage.attachments)
    assertTrue(assistantMessage.text.orEmpty().contains("Attachment could not be saved"))
    assertTrue(assistantMessage.text.orEmpty().contains("1 attachment was missing"))
  }

  @Test
  fun taskSuccessResolvesArtifactOnlyAttachmentsAfterArtifactEventFallsOutOfLiveHistory() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-assistant-artifact-attachments-overflow"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-artifact-overflow-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(workspaceRoot.resolve("outputs").resolve("diagram.png"), byteArrayOf(1, 2, 3, 4))
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )

    hostRuntime.submitChatMessage("Send the generated image by artifact id.")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "workspace_write_file"),
        result = AgentToolResult(
          toolName = "workspace_write_file",
          status = AgentToolResultStatus.SUCCESS,
          content = "Wrote outputs/diagram.png successfully.",
          metadata = mapOf(
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID to "artifact-diagram-overflow",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH to "outputs/diagram.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME to "diagram.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT to "image",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE to "image/png",
          ),
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
    repeat(30) { index ->
      manager.emitRunEvent(
        sessionId = activeSessionId,
        task = task,
        event = OpenCrayAssistantEvent(
          runId = run.runId,
          taskId = task.id,
          turn = index + 1,
          text = "Progress update ${index + 1}",
          stage = "Planning",
          emittedAtEpochMs = 1_100L + index,
        ),
      )
    }
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          artifactId = "artifact-diagram-overflow",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_500L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
    val attachment = assistantMessage.attachments.single()

    assertEquals(null, assistantMessage.text)
    assertEquals(ChatAttachmentKind.IMAGE, attachment.kind)
    assertEquals("diagram.png", attachment.displayName)
    assertEquals("image/png", attachment.mimeType)
    assertTrue(attachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
  }

  @Test
  fun taskSuccessResolvesArtifactOnlyAttachmentsWhenSameToolRunsTwiceInSameTurn() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-assistant-artifact-attachments-same-tool-turn"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-artifact-same-tool-turn-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(workspaceRoot.resolve("outputs").resolve("first.png"), byteArrayOf(1, 2, 3, 4))
    Files.write(workspaceRoot.resolve("outputs").resolve("second.png"), byteArrayOf(5, 6, 7, 8))
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )

    hostRuntime.submitChatMessage("Send the second generated image by artifact id.")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "workspace_write_file"),
        result = AgentToolResult(
          toolName = "workspace_write_file",
          status = AgentToolResultStatus.SUCCESS,
          content = "Wrote outputs/first.png successfully.",
          metadata = mapOf(
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID to "artifact-image-first",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH to "outputs/first.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME to "first.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT to "image",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE to "image/png",
          ),
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "workspace_write_file"),
        result = AgentToolResult(
          toolName = "workspace_write_file",
          status = AgentToolResultStatus.SUCCESS,
          content = "Wrote outputs/second.png successfully.",
          metadata = mapOf(
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID to "artifact-image-second",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH to "outputs/second.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME to "second.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT to "image",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE to "image/png",
          ),
        ),
        emittedAtEpochMs = 1_001L,
      ),
    )
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          artifactId = "artifact-image-second",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_100L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
    val attachment = assistantMessage.attachments.single()

    assertEquals(null, assistantMessage.text)
    assertEquals(ChatAttachmentKind.IMAGE, attachment.kind)
    assertEquals("second.png", attachment.displayName)
    assertEquals("image/png", attachment.mimeType)
    assertTrue(attachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
  }

  @Test
  fun taskSuccessResolvesChatAttachmentIdOnlyAttachmentsIntoWorkspaceMediaStore() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-assistant-chat-attachment-id"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-chat-attachment-id-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("seed"))
    Files.write(workspaceRoot.resolve("seed").resolve("camera_first.jpg"), byteArrayOf(1, 2, 3, 4))
    chatStore.appendSubmittedTurn(
      sessionId = activeSessionId,
      userText = "",
      assistantMessageId = "assistant-seed-chat-attachment-id",
      assistantPlaceholderText = "Seeded image.",
      attachments = listOf(
        ChatAttachmentEntry(
          attachmentId = "user-image-1",
          kind = ChatAttachmentKind.IMAGE,
          displayName = "camera_first.jpg",
          localPath = "seed/camera_first.jpg",
          mimeType = "image/jpeg",
          sizeBytes = 4,
        ),
      ),
    )
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )

    hostRuntime.submitChatMessage("Send the uploaded image back.")
    val task = handle.submittedTasks.single()
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          chatAttachmentId = "user-image-1",
          kind = "image",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
    val attachment = assistantMessage.attachments.single()

    assertEquals(null, assistantMessage.text)
    assertEquals(ChatAttachmentKind.IMAGE, attachment.kind)
    assertEquals("camera_first.jpg", attachment.displayName)
    assertEquals("image/jpeg", attachment.mimeType)
    assertTrue(attachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
  }

  @Test
  fun taskSuccessResolvesChatAttachmentIdFileAttachmentsIntoWorkspaceMediaStore() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-assistant-chat-file-attachment-id"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-chat-file-attachment-id-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("seed"))
    Files.write(workspaceRoot.resolve("seed").resolve("report.pdf"), byteArrayOf(5, 6, 7, 8))
    chatStore.appendSubmittedTurn(
      sessionId = activeSessionId,
      userText = "",
      assistantMessageId = "assistant-seed-chat-file-attachment-id",
      assistantPlaceholderText = "Seeded file.",
      attachments = listOf(
        ChatAttachmentEntry(
          attachmentId = "user-file-1",
          kind = ChatAttachmentKind.FILE,
          displayName = "report.pdf",
          localPath = "seed/report.pdf",
          mimeType = "application/pdf",
          sizeBytes = 4,
        ),
      ),
    )
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )

    hostRuntime.submitChatMessage("Send the uploaded file back.")
    val task = handle.submittedTasks.single()
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          chatAttachmentId = "user-file-1",
          kind = "file",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
    val attachment = assistantMessage.attachments.single()

    assertEquals(null, assistantMessage.text)
    assertEquals(ChatAttachmentKind.FILE, attachment.kind)
    assertEquals("report.pdf", attachment.displayName)
    assertEquals("application/pdf", attachment.mimeType)
    assertTrue(attachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
  }

  @Test
  fun taskSuccessResolvesAttachmentArtifactsJsonWithVoiceMetadata() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-assistant-artifact-json-attachments"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-artifact-json-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(workspaceRoot.resolve("outputs").resolve("diagram.png"), byteArrayOf(1, 2, 3, 4))
    Files.write(workspaceRoot.resolve("outputs").resolve("voice-note.m4a"), byteArrayOf(5, 6, 7, 8))
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )

    hostRuntime.submitChatMessage("Send the generated image and voice by artifact id.")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "GenerateImage"),
        result = AgentToolResult(
          toolName = "GenerateImage",
          status = AgentToolResultStatus.SUCCESS,
          content = "Generated image and voice artifacts.",
          metadata = mapOf(
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACTS_JSON to Json.encodeToString(
              ListSerializer(OpenCrayAttachmentArtifact.serializer()),
              listOf(
                OpenCrayAttachmentArtifact(
                  artifactId = "artifact-diagram-1234abcd",
                  relativePath = "outputs/diagram.png",
                  displayName = "diagram.png",
                  kindHint = "image",
                  mimeType = "image/png",
                ),
                OpenCrayAttachmentArtifact(
                  artifactId = "artifact-voice-note-5678efgh",
                  relativePath = "outputs/voice-note.m4a",
                  displayName = "voice-note.m4a",
                  kindHint = "voice",
                  mimeType = "audio/mp4",
                  durationMs = 3_200L,
                  transcriptText = "Generated spoken summary",
                ),
              ),
            ),
          ),
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          artifactId = "artifact-diagram-1234abcd",
        ),
        OpenCrayFinalAttachment(
          artifactId = "artifact-voice-note-5678efgh",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()

    assertEquals(2, assistantMessage.attachments.size)
    assertEquals(ChatAttachmentKind.IMAGE, assistantMessage.attachments.first().kind)
    assertEquals(ChatAttachmentKind.VOICE, assistantMessage.attachments.last().kind)
    assertEquals(3_200L, assistantMessage.attachments.last().durationMs)
    assertEquals("Generated spoken summary", assistantMessage.attachments.last().transcriptText)
    assistantMessage.attachments.forEach { attachment ->
      assertTrue(attachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
      assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
    }
  }

  @Test
  fun taskSuccessResolvesAttachmentMarkdownReferencesFromPriorChatAttachments() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-attachment-markdown-session"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("attachment-markdown-session-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("seed"))
    Files.write(workspaceRoot.resolve("seed").resolve("camera_first.jpg"), byteArrayOf(1, 2, 3, 4))
    Files.write(workspaceRoot.resolve("seed").resolve("report.pdf"), byteArrayOf(5, 6, 7, 8))
    chatStore.appendSubmittedTurn(
      sessionId = activeSessionId,
      userText = "",
      assistantMessageId = "assistant-seed",
      assistantPlaceholderText = "Seeded attachments.",
      attachments = listOf(
        ChatAttachmentEntry(
          attachmentId = "user-image-1",
          kind = ChatAttachmentKind.IMAGE,
          displayName = "camera_first.jpg",
          localPath = "seed/camera_first.jpg",
          mimeType = "image/jpeg",
          sizeBytes = 4,
        ),
        ChatAttachmentEntry(
          attachmentId = "user-file-1",
          kind = ChatAttachmentKind.FILE,
          displayName = "report.pdf",
          localPath = "seed/report.pdf",
          mimeType = "application/pdf",
          sizeBytes = 4,
        ),
      ),
    )
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )

    hostRuntime.submitChatMessage("Send the uploaded attachment back.")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = """
          Here they are:

          ![camera_first.jpg](attachment:artifact)

          [report.pdf](attachment:artifact)
        """.trimIndent(),
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()

    assertEquals(
      """
      Here they are:

      report.pdf
      """.trimIndent(),
      assistantMessage.text,
    )
    assertEquals(2, assistantMessage.attachments.size)
    assertEquals(ChatAttachmentKind.IMAGE, assistantMessage.attachments.first().kind)
    assertEquals("camera_first.jpg", assistantMessage.attachments.first().displayName)
    assertEquals(ChatAttachmentKind.FILE, assistantMessage.attachments.last().kind)
    assertEquals("report.pdf", assistantMessage.attachments.last().displayName)
    assistantMessage.attachments.forEach { attachment ->
      assertTrue(attachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
      assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
    }
  }

  @Test
  fun taskSuccessResolvesAttachmentMarkdownReferencesFromRunArtifacts() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-attachment-markdown-artifact"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("attachment-markdown-artifact-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(workspaceRoot.resolve("outputs").resolve("diagram.png"), byteArrayOf(1, 2, 3, 4))
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )

    hostRuntime.submitChatMessage("Send the artifact image back.")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "workspace_write_file"),
        result = AgentToolResult(
          toolName = "workspace_write_file",
          status = AgentToolResultStatus.SUCCESS,
          content = "Wrote outputs/diagram.png successfully.",
          metadata = mapOf(
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID to "artifact-diagram-1234abcd",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH to "outputs/diagram.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME to "diagram.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT to "image",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE to "image/png",
          ),
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "![diagram.png](attachment:artifact)",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
    val attachment = assistantMessage.attachments.single()

    assertNull(assistantMessage.text)
    assertEquals(ChatAttachmentKind.IMAGE, attachment.kind)
    assertEquals("diagram.png", attachment.displayName)
    assertTrue(attachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
  }

  @Test
  fun taskSuccessResolvesAttachmentMarkdownReferencesFromRunArtifactsAfterLiveHistoryOverflow() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-attachment-markdown-artifact-overflow"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("attachment-markdown-artifact-overflow-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(workspaceRoot.resolve("outputs").resolve("diagram.png"), byteArrayOf(1, 2, 3, 4))
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )

    hostRuntime.submitChatMessage("Send the artifact image back.")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "workspace_write_file"),
        result = AgentToolResult(
          toolName = "workspace_write_file",
          status = AgentToolResultStatus.SUCCESS,
          content = "Wrote outputs/diagram.png successfully.",
          metadata = mapOf(
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID to "artifact-diagram-overflow-markdown",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH to "outputs/diagram.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DISPLAY_NAME to "diagram.png",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_KIND_HINT to "image",
            OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE to "image/png",
          ),
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
    repeat(30) { index ->
      manager.emitRunEvent(
        sessionId = activeSessionId,
        task = task,
        event = OpenCrayAssistantEvent(
          runId = run.runId,
          taskId = task.id,
          turn = index + 1,
          text = "Planning update ${index + 1}",
          stage = "Planning",
          emittedAtEpochMs = 1_100L + index,
        ),
      )
    }
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "![diagram.png](attachment:artifact)",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_500L,
        metadata = task.metadata,
      ),
    )

    val assistantMessage = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
    val attachment = assistantMessage.attachments.single()

    assertNull(assistantMessage.text)
    assertEquals(ChatAttachmentKind.IMAGE, attachment.kind)
    assertEquals("diagram.png", attachment.displayName)
    assertTrue(attachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(attachment.localPath)))
  }

  @Test
  fun taskSuccessBackfillsMissingVoiceMetadataAsynchronously() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-voice-metadata-backfill"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("voice-metadata-backfill-workspace").toPath()
    val approvedExternalRoot = temporaryFolder.newFolder("voice-metadata-backfill-approved").toPath()
    val sourceVoice = approvedExternalRoot.resolve("voice-note.m4a")
    Files.write(sourceVoice, byteArrayOf(5, 6, 7, 8))
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val queuedExecutor = QueuedExecutor()
    var analyzerCallCount = 0
    val cacheStore = AppAgentWorkspaceVoiceMetadataCacheStore.fromWorkspaceRoot(workspaceRoot)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = setOf(approvedExternalRoot),
          summary = approvedExternalRoot.toString(),
        )
      },
      voiceMetadataAnalyzer = AppAgentWorkspaceVoiceMetadataAnalyzer { path, mimeType ->
        analyzerCallCount += 1
        assertTrue(path.startsWith(workspaceRoot.resolve(".opencray").normalize()))
        assertEquals("audio/mp4", mimeType)
        AppAgentWorkspaceVoiceMetadata(
          durationMs = 4_200L,
          waveformBars = listOf(12, 40, 88),
          transcriptText = "Backfilled transcript",
        )
      },
      voiceMetadataBackfillExecutor = queuedExecutor,
      voiceMetadataCacheStore = cacheStore,
    )

    hostRuntime.submitChatMessage("Send the generated voice.")
    val task = handle.submittedTasks.single()
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          kind = "voice",
          path = sourceVoice.toString(),
          displayName = "voice-note.m4a",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val initialAttachment = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
      .attachments
      .single()
    assertEquals(null, initialAttachment.durationMs)
    assertTrue(initialAttachment.waveformBars.isEmpty())
    assertEquals(null, initialAttachment.transcriptText)
    assertEquals(1, queuedExecutor.pendingCount())
    assertEquals(0, analyzerCallCount)

    queuedExecutor.runAll()

    val updatedAttachment = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
      .attachments
      .single()
    val snapshot = hostRuntime.loadChatSnapshot()
    val snapshotAttachments = ((snapshot["messages"] as List<*>).last() as Map<*, *>)["attachments"] as List<*>
    val snapshotVoice = snapshotAttachments.single() as Map<*, *>
    val cachedMetadata = cacheStore.get(updatedAttachment.contentSha256 ?: error("Expected voice hash."))

    assertEquals(1, analyzerCallCount)
    assertEquals(0, queuedExecutor.pendingCount())
    assertEquals(4_200L, updatedAttachment.durationMs)
    assertEquals(listOf(12, 40, 88), updatedAttachment.waveformBars)
    assertEquals("Backfilled transcript", updatedAttachment.transcriptText)
    assertEquals(4_200L, snapshotVoice["durationMs"])
    assertEquals(listOf(12, 40, 88), snapshotVoice["waveformBars"])
    assertEquals("Backfilled transcript", snapshotVoice["transcriptText"])
    assertEquals(
      AppAgentWorkspaceVoiceMetadata(
        durationMs = 4_200L,
        waveformBars = listOf(12, 40, 88),
        transcriptText = "Backfilled transcript",
      ),
      cachedMetadata,
    )
  }

  @Test
  fun voiceMetadataBackfillReusesCacheForSameContentAcrossSessions() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-voice-metadata-cache-reuse"),
    )
    val firstSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("voice-metadata-cache-workspace").toPath()
    val approvedExternalRoot = temporaryFolder.newFolder("voice-metadata-cache-approved").toPath()
    val sourceVoice = approvedExternalRoot.resolve("shared-voice.m4a")
    Files.write(sourceVoice, byteArrayOf(9, 10, 11, 12))
    val manager = RecordingRuntimeManager()
    val firstHandle = RecordingSessionHandle(
      sessionId = firstSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(firstHandle)
    val queuedExecutor = QueuedExecutor()
    var analyzerCallCount = 0
    val cacheStore = AppAgentWorkspaceVoiceMetadataCacheStore.fromWorkspaceRoot(workspaceRoot)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = setOf(approvedExternalRoot),
          summary = approvedExternalRoot.toString(),
        )
      },
      voiceMetadataAnalyzer = AppAgentWorkspaceVoiceMetadataAnalyzer { _, _ ->
        analyzerCallCount += 1
        AppAgentWorkspaceVoiceMetadata(
          durationMs = 8_100L,
          waveformBars = listOf(18, 36, 72),
          transcriptText = "Cached transcript",
        )
      },
      voiceMetadataBackfillExecutor = queuedExecutor,
      voiceMetadataCacheStore = cacheStore,
    )
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          kind = "voice",
          path = sourceVoice.toString(),
          displayName = "shared-voice.m4a",
        ),
      ),
    )

    hostRuntime.submitChatMessage("Send the first shared voice.")
    val firstTask = firstHandle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = firstSessionId,
      task = firstTask,
      result = ExecutionResult(
        taskId = firstTask.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = firstTask.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )
    assertEquals(1, queuedExecutor.pendingCount())

    queuedExecutor.runAll()

    assertEquals(1, analyzerCallCount)
    assertEquals(0, queuedExecutor.pendingCount())

    hostRuntime.createChatSession()
    val secondSessionId = chatStore.loadState().activeSession.sessionId
    val secondHandle = RecordingSessionHandle(
      sessionId = secondSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(secondHandle)

    hostRuntime.submitChatMessage("Send the same shared voice again.")
    val secondTask = secondHandle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = secondSessionId,
      task = secondTask,
      result = ExecutionResult(
        taskId = secondTask.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 2_000L,
        finishedAtEpochMs = 2_001L,
        metadata = secondTask.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val secondAttachment = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
      .last()
      .attachments
      .single()

    assertEquals(1, analyzerCallCount)
    assertEquals(0, queuedExecutor.pendingCount())
    assertEquals(8_100L, secondAttachment.durationMs)
    assertEquals(listOf(18, 36, 72), secondAttachment.waveformBars)
    assertEquals("Cached transcript", secondAttachment.transcriptText)
  }

  @Test
  fun recreatedHostsExposeFinalAttachmentsOnRetainedRunInspectorPayload() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-final-attachments-inspector"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("workspace-final-attachments-inspector").toPath()
    Files.createDirectories(workspaceRoot.resolve("outputs"))
    Files.write(workspaceRoot.resolve("outputs").resolve("diagram.png"), byteArrayOf(1, 2, 3, 4))
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val firstHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )

    val submission = firstHost.submitChatMessage("Send the generated diagram.")!!
    val task = handle.submittedTasks.single()
    val attachmentsJson = Json.encodeToString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      listOf(
        OpenCrayFinalAttachment(
          kind = "image",
          relativePath = "outputs/diagram.png",
          displayName = "diagram.png",
          mimeType = "image/png",
        ),
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON to attachmentsJson,
        ),
      ),
    )

    val recreatedHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      workspaceRootProvider = { workspaceRoot },
    )
    val runtimeActivity = recreatedHost.loadChatRuntimeSnapshot()
    val retainedRuns = (runtimeActivity["retainedRuns"] as List<*>).map { run ->
      run as Map<*, *>
    }
    val retainedRun = retainedRuns.single { run -> run["runId"] == submission["runId"] }
    val finalAttachments = (retainedRun["finalAttachments"] as List<*>).map { attachment ->
      attachment as Map<*, *>
    }

    assertEquals(1, finalAttachments.size)
    assertEquals("image", finalAttachments.single()["kind"])
    assertEquals("diagram.png", finalAttachments.single()["displayName"])
    assertEquals("image/png", finalAttachments.single()["mimeType"])
    assertTrue((finalAttachments.single()["localPath"] as String).contains(".opencray/chat-media/"))
  }

  @Test
  fun loadWorkspaceVoicePlaybackSourceResolvesSupportedVoiceFiles() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-voice-playback-source"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-voice-playback-source").toPath()
    val voiceFile = workspaceRoot.resolve(".opencray/chat-media/session-1/hash/voice-note.m4a")
    Files.createDirectories(voiceFile.parent)
    Files.write(voiceFile, byteArrayOf(1, 2, 3, 4))
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      workspaceRootProvider = { workspaceRoot },
    )

    val payload = hostRuntime.loadWorkspaceVoicePlaybackSource(
      ".opencray/chat-media/session-1/hash/voice-note.m4a",
    )

    assertEquals("voice-note.m4a", payload["name"])
    assertEquals(".opencray/chat-media/session-1/hash/voice-note.m4a", payload["relativePath"])
    assertEquals(voiceFile.toAbsolutePath().normalize().toString(), payload["localFilePath"])
    assertEquals("audio/mp4", payload["mimeType"])
    assertEquals(4L, payload["sizeBytes"])
  }

  @Test
  fun openWorkspaceEntryDelegatesToInjectedOpener() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-open-entry"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-open-entry").toPath()
    val openedEntries = mutableListOf<Pair<Path, String>>()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      workspaceRootProvider = { workspaceRoot },
      workspaceEntryOpener = { root, relativePath ->
        openedEntries += root to relativePath
      },
    )

    hostRuntime.openWorkspaceEntry(".opencray/chat-media/session-1/hash/report.pdf")

    assertEquals(
      listOf(
        workspaceRoot.toAbsolutePath().normalize() to
          ".opencray/chat-media/session-1/hash/report.pdf",
      ),
      openedEntries,
    )
  }

  @Test
  fun openExternalUriDelegatesToInjectedOpener() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-open-external"))
    val openedUris = mutableListOf<String>()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      externalUriOpener = { uri ->
        openedUris += uri
      },
    )

    hostRuntime.openExternalUri("https://opencray.dev/docs")

    assertEquals(listOf("https://opencray.dev/docs"), openedUris)
  }

  @Test
  fun chatObserverInitialSnapshotDoesNotEmbedRuntimeActivity() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-chat-observer-initial-runtime"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = activeSessionId)
    runtimeManager.putHandle(handle)
    val mainThreadPoster = QueuedMainThreadPoster()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      mainThreadPoster = mainThreadPoster,
    )

    hostRuntime.submitChatMessage("Start a run before observing")
    val fullSnapshot = hostRuntime.loadChatSnapshot()
    val observedSnapshots = mutableListOf<Map<String, Any?>>()
    val dispose = hostRuntime.observeChat { snapshot ->
      observedSnapshots += snapshot
    }
    mainThreadPoster.flush()
    dispose()

    assertTrue(fullSnapshot["runtimeActivity"] is Map<*, *>)
    assertTrue(observedSnapshots.isNotEmpty())
    assertEquals(null, observedSnapshots.first()["runtimeActivity"])
  }

  @Test
  fun chatObserverReceivesSettledSnapshotAfterTaskFinish() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-settled-observer"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = SettlingRuntimeManager(sessionId = activeSessionId)
    val mainThreadPoster = QueuedMainThreadPoster()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      mainThreadPoster = mainThreadPoster,
    )
    val observedSnapshots = mutableListOf<Map<String, Any?>>()
    val dispose = hostRuntime.observeChat { snapshot ->
      observedSnapshots += snapshot
    }
    mainThreadPoster.flush()
    observedSnapshots.clear()

    hostRuntime.submitChatMessage("Need a settled final reply")
    mainThreadPoster.flush()
    observedSnapshots.clear()

    runtimeManager.emitTaskFinished(
      ExecutionResult(
        taskId = runtimeManager.handle.requireSubmittedTask().id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Settled final reply",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = mapOf("responseFormat" to "json_final"),
      ),
    )
    mainThreadPoster.flush()
    dispose()

    val snapshot = observedSnapshots.last()
    val messages = (snapshot["messages"] as List<*>).map { it as Map<*, *> }
    val summary = snapshot["summary"] as Map<*, *>
    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val activeRuns = runtimeActivity["activeRuns"] as List<*>

    assertEquals("Settled final reply", messages.last()["text"])
    assertEquals("Local transcript is restored into the runtime window for each task.", summary["body"])
    assertEquals(null, snapshot["runtimeActivity"])
    assertTrue(activeRuns.isEmpty())
  }

  @Test
  fun chatObserverPublishesBackgroundReplyPreviewAndUnreadCountAfterTaskFinish() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-background-observer"))
    val sessionAId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handleA = RecordingSessionHandle(
      sessionId = sessionAId,
      onResume = runtimeManager.resumedSessionIds::add,
    )
    runtimeManager.putHandle(handleA)
    val mainThreadPoster = QueuedMainThreadPoster()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      mainThreadPoster = mainThreadPoster,
    )
    val observedSnapshots = mutableListOf<Map<String, Any?>>()
    val dispose = hostRuntime.observeChat { snapshot ->
      observedSnapshots += snapshot
    }
    mainThreadPoster.flush()
    observedSnapshots.clear()

    hostRuntime.submitChatMessage("Reply later")
    mainThreadPoster.flush()
    observedSnapshots.clear()

    hostRuntime.createChatSession()
    val sessionBId = chatStore.loadState().activeSession.sessionId
    mainThreadPoster.flush()
    observedSnapshots.clear()

    runtimeManager.emitTaskFinished(
      sessionId = sessionAId,
      task = handleA.submittedTasks.single(),
      result = ExecutionResult(
        taskId = handleA.submittedTasks.single().id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Background reply finished.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = handleA.submittedTasks.single().metadata + mapOf("responseFormat" to "json_final"),
      ),
    )
    mainThreadPoster.flush()
    dispose()

    val snapshot = observedSnapshots.last()
    val drawer = snapshot["drawer"] as Map<*, *>
    val sessions = (drawer["sessions"] as List<*>).map { it as Map<*, *> }
    val sessionA = sessions.first { session -> session["sessionId"] == sessionAId }
    val sessionB = sessions.first { session -> session["sessionId"] == sessionBId }

    assertEquals("Background reply finished.", sessionA["preview"])
    assertEquals(1, sessionA["unreadCount"])
    assertEquals(0, sessionB["unreadCount"])
    assertEquals(true, sessionB["isSelected"])
  }

  @Test
  fun taskSuccessWritesDeterministicMemoryAfterCompletion() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-write"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val memoryStore = InMemoryMemoryStore()
    val workspaceRoot = temporaryFolder.newFolder("workspace-root").toPath()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = memoryStore,
        workspaceIdProvider = { AppWorkspaceIdentity.fromRoots(setOf(workspaceRoot)) },
        candidateExtractor = semanticUserCandidateExtractor(),
      ),
    )

    hostRuntime.submitChatMessage(
      """
        Please default to Simplified Chinese for explanations.
        Do not use git reset --hard in this repo.
      """.trimIndent(),
    )
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = com.opencray.runtime.OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "Read"),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "Project uses the Gradle wrapper from the repo root.",
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Next I will run the targeted runtime tests.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val writtenKinds = memoryStore.list().mapNotNull { record -> record.extensions["kind"] }.sorted()
    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val events = runtimeActivity["events"] as List<*>
    val memoryEvent = events.last() as Map<*, *>

    assertEquals(
      listOf("durable_instruction", "project_fact", "task_commitment", "user_preference"),
      writtenKinds,
    )
    assertEquals("memory_write", memoryEvent["kind"])
    assertEquals(run.runId, memoryEvent["runId"])
    assertEquals(listOf("durable_instruction", "project_fact", "task_commitment", "user_preference"), memoryEvent["writtenKinds"])
  }

  @Test
  fun approvalRequiredTaskDoesNotWriteMemory() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-approval"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val memoryStore = InMemoryMemoryStore()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(memoryStore = memoryStore),
    )

    hostRuntime.submitChatMessage("Please default to PowerShell commands.")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    assertTrue(memoryStore.list().isEmpty())
  }

  @Test
  fun taskSuccessReportsResolvedAndExpiredCommitmentsInMemoryWriteEvent() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-maintenance"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        taskCommitmentRecord(
          id = "commitment-open",
          content = "run the targeted runtime tests",
          sourceSessionId = activeSessionId,
          updatedAtEpochMs = 1_000L,
        ),
      )
      upsert(
        taskCommitmentRecord(
          id = "commitment-expired",
          content = "clean up the temporary transcript snapshot",
          sourceSessionId = activeSessionId,
          updatedAtEpochMs = 1_000L,
          ttlMs = 100L,
          lastConfirmedAtEpochMs = 1_050L,
        ),
      )
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = memoryStore,
        writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
        taskCommitmentResolver = TaskCommitmentResolver(
          store = memoryStore,
          clock = { 2_000L },
          intentInterpreter = FixedTaskCommitmentIntentInterpreter(
            TaskCommitmentIntentInterpretation.Success(
              decisions = listOf(
                TaskCommitmentIntentDecision(
                  commitmentId = "commitment-open",
                  action = com.opencray.runtime.memory.TaskCommitmentIntentAction.RESOLVE,
                ),
              ),
            ),
          ),
        ),
      ),
    )

    hostRuntime.submitChatMessage("Please continue.")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "I ran the targeted runtime tests and updated the docs.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val events = runtimeActivity["events"] as List<*>
    val memoryEvent = events.last() as Map<*, *>

    assertEquals("memory_write", memoryEvent["kind"])
    assertEquals(listOf("commitment-open"), memoryEvent["resolvedRecordIds"])
    assertEquals(emptyList<String>(), memoryEvent["reaffirmedRecordIds"])
    assertEquals(listOf("commitment-expired"), memoryEvent["expiredRecordIds"])
    assertEquals("resolved", memoryStore.list().single { record -> record.id == "commitment-open" }.extensions["status"])
    assertTrue(memoryStore.list().none { record -> record.id == "commitment-expired" })
  }

  @Test
  fun taskSuccessReportsReaffirmedCommitmentsInMemoryWriteEvent() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-reaffirm"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        taskCommitmentRecord(
          id = "commitment-reaffirm",
          content = "stabilize the flaky runtime test",
          sourceSessionId = activeSessionId,
          updatedAtEpochMs = 1_000L,
        ),
      )
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = memoryStore,
        writer = MemoryWriter(store = memoryStore, clock = { 2_000L }),
        taskCommitmentResolver = TaskCommitmentResolver(
          store = memoryStore,
          clock = { 2_000L },
          intentInterpreter = FixedTaskCommitmentIntentInterpreter(
            TaskCommitmentIntentInterpretation.Success(
              decisions = listOf(
                TaskCommitmentIntentDecision(
                  commitmentId = "commitment-reaffirm",
                  action = TaskCommitmentIntentAction.REAFFIRM,
                ),
              ),
            ),
          ),
        ),
      ),
    )

    hostRuntime.submitChatMessage("Please continue.")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "The flaky runtime test still needs work; I am continuing on it next.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val events = runtimeActivity["events"] as List<*>
    val memoryEvent = events.last() as Map<*, *>

    assertEquals("memory_write", memoryEvent["kind"])
    assertEquals(emptyList<String>(), memoryEvent["resolvedRecordIds"])
    assertEquals(listOf("commitment-reaffirm"), memoryEvent["reaffirmedRecordIds"])
    assertEquals("open", memoryStore.list().single { record -> record.id == "commitment-reaffirm" }.extensions["status"])
  }

  @Test
  fun memoryWriteFailureDoesNotBreakTaskCompletionPath() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-failure"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(memoryStore = FailingMemoryStore()),
    )

    hostRuntime.submitChatMessage("Please default to Chinese replies.")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "All good.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals("All good.", messages.last().text)
  }
}
