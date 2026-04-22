package com.opencray.app

import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.OnDeviceLlmModelOptionSnapshot
import com.opencray.app.facade.llm.EmptyLlmConfigFacade
import com.opencray.app.facade.llm.SaveCustomLlmProviderRequest
import com.opencray.app.facade.llm.SaveLlmConfigRequest
import com.opencray.app.facade.llm.ValidateLlmConfigRequest
import com.opencray.app.facade.mcp.McpServerSettingsSnapshot
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsSnapshot
import com.opencray.app.facade.personalization.PersonalizationConfigSnapshot
import com.opencray.app.facade.personalization.PersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot
import com.opencray.app.facade.personalization.PersonalizationPresetSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetScope
import com.opencray.app.facade.personalization.SavePersonalizationConfigRequest
import com.opencray.app.facade.search.EmptyNetworkSearchConfigFacade
import com.opencray.app.facade.search.LocalNetworkSearchConfigFacade
import com.opencray.app.facade.search.NetworkSearchConfigFacade
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.app.facade.safety.SafetySettingsLocationSnapshot
import com.opencray.app.facade.safety.SafetySettingsSnapshot
import com.opencray.app.facade.safety.SaveSafetySettingsRequest
import com.opencray.app.facade.skills.InstallSourceSnapshot
import com.opencray.app.facade.skills.InstalledSkillSnapshot
import com.opencray.app.facade.skills.SkillInstallRequestResult
import com.opencray.app.facade.skills.SkillInstructionsSnapshot
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.app.facade.skills.SkillsSnapshot
import com.opencray.app.facade.skills.SuggestedSkillSnapshot
import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsRouteId
import com.opencray.app.shell.AppShellStateStore
import com.opencray.app.shell.InMemoryAppShellKeyValueStore
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.OpenCrayAttachmentArtifact
import com.opencray.runtime.OpenCrayAttachmentArtifactMetadataKeys
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.ProviderNativeWebSearchSupport
import com.opencray.runtime.TodoWriteMetadataKeys
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationAttachment
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationCommentary
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolCall
import com.opencray.runtime.context.RuntimeConversationToolResult
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.memory.TaskCommitmentIntentAction
import com.opencray.runtime.memory.TaskCommitmentIntentDecision
import com.opencray.runtime.memory.TaskCommitmentIntentInterpretation
import com.opencray.runtime.memory.TaskCommitmentIntentInterpreter
import com.opencray.runtime.memory.TaskCommitmentIntentRequest
import com.opencray.runtime.memory.UserMemoryIntent
import com.opencray.runtime.memory.UserMemoryIntentInterpretation
import com.opencray.runtime.memory.UserMemoryIntentInterpreter
import com.opencray.runtime.memory.UserMemoryIntentRequest
import com.opencray.runtime.memory.MemoryWriter
import com.opencray.runtime.memory.TaskCommitmentResolver
import com.opencray.runtime.skills.SkillPackageBatchInstallAttempt
import com.opencray.runtime.skills.SkillPackageBatchInstallEntry
import com.opencray.runtime.skills.SkillPackageBatchInstallResult
import com.opencray.runtime.skills.SkillPackageCheckReport
import com.opencray.runtime.skills.SkillPackageCheckResult
import com.opencray.runtime.skills.SkillPackageCheckStatus
import com.opencray.runtime.skills.SkillPackageUpdateReport
import com.opencray.runtime.skills.SkillPackageUpdateResult
import com.opencray.runtime.skills.SkillPackageUpdateStatus
import com.opencray.runtime.skills.SkillSourceInspectionAttempt
import com.opencray.runtime.skills.SkillSourceInspectionCandidate
import com.opencray.runtime.skills.SkillSourceInspectionResult
import com.opencray.policy.ExternalAccessMode
import com.opencray.policy.SafetyAutomationMode
import com.opencray.policy.ToolPolicyOverride
import com.opencray.policy.WorkspaceAccessProfile
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentMailbox
import com.opencray.runtime.subagent.SubAgentMailboxMessage
import com.opencray.runtime.soul.InteractionPreferenceState
import com.opencray.runtime.soul.PreferenceAxisState
import com.opencray.runtime.soul.PreferredAddressState
import com.opencray.runtime.soul.PreferredAddressStyle
import com.opencray.runtime.soul.RelationshipState
import com.opencray.runtime.soul.SoulMemoryExtensionKeys
import com.opencray.runtime.soul.SoulMemoryObjectTypes
import com.opencray.runtime.soul.SoulProfileExtensionKeys
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayHostRuntimeTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun submitChatMessageLeavesTranscriptUntouchedWhenQueueSubmitFails() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-submit-fail"))
    val initialSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager().apply {
      putHandle(
        RecordingSessionHandle(
          sessionId = initialSessionId,
          onResume = resumedSessionIds::add,
          submitFailure = IllegalStateException("queue persistence failed"),
        ),
      )
    }
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    runCatching {
      hostRuntime.submitChatMessage("Ship the patch")
    }

    val messages = chatStore.loadState().activeSession.messages

    assertEquals(1, messages.size)
    assertEquals(listOf(initialSessionId), manager.resumedSessionIds)
    assertTrue(messages.none { message -> message.role == ChatTranscriptRole.USER })
    assertTrue(messages.none { message -> message.role == ChatTranscriptRole.ASSISTANT })
  }

  @Test
  fun selectChatSessionResumesResolvedActiveSessionInsteadOfInvalidInputId() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-select"))
    val createdState = chatStore.createSession()
    val expectedSessionId = createdState.activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.selectChatSession("missing-session-id")

    assertEquals(listOf(expectedSessionId, expectedSessionId), manager.resumedSessionIds)
    assertTrue("missing-session-id" !in manager.requestedSessionIds)
  }

  @Test
  fun selectChatSessionDiscardsCurrentEmptySessionBeforeSwitching() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-select-discard-empty"))
    val populatedSessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(populatedSessionId, "Keep this session")
    val emptySessionId = chatStore.createSession().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    manager.putHandle(
      RecordingSessionHandle(
        sessionId = populatedSessionId,
        onResume = manager.resumedSessionIds::add,
      ),
    )
    manager.putHandle(
      RecordingSessionHandle(
        sessionId = emptySessionId,
        onResume = manager.resumedSessionIds::add,
      ),
    )
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.selectChatSession(populatedSessionId)

    assertNull(chatStore.loadSession(emptySessionId))
    assertEquals(populatedSessionId, chatStore.loadState().activeSession.sessionId)
    assertEquals(listOf(emptySessionId), manager.releasedSessionIds)
  }

  @Test
  fun selectChatSessionPreservesCurrentSessionWhenItHasUserContent() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-select-keep-non-empty"))
    val populatedSessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(populatedSessionId, "Do not delete this session")
    val emptySessionId = chatStore.createSession().activeSession.sessionId
    chatStore.selectSession(populatedSessionId)
    val manager = RecordingRuntimeManager()
    manager.putHandle(
      RecordingSessionHandle(
        sessionId = populatedSessionId,
        onResume = manager.resumedSessionIds::add,
      ),
    )
    manager.putHandle(
      RecordingSessionHandle(
        sessionId = emptySessionId,
        onResume = manager.resumedSessionIds::add,
      ),
    )
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.selectChatSession(emptySessionId)

    assertNotNull(chatStore.loadSession(populatedSessionId))
    assertEquals(emptySessionId, chatStore.loadState().activeSession.sessionId)
    assertTrue(manager.releasedSessionIds.isEmpty())
  }

  @Test
  fun loadAndSaveMediaSpeechConfigExposeVideoAndProviderAuthFields() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-media-settings"))
    val store = MediaSpeechSettingsStore(InMemoryMediaSpeechSettingsKeyValueStore())
    val facade = com.opencray.app.facade.media.LocalMediaSpeechSettingsFacade.createForTest(store)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      mediaSpeechSettingsFacade = facade,
    )

    val loaded = hostRuntime.loadMediaSpeechConfig()
    val loadedVideo = loaded["videoGeneration"] as Map<*, *>
    val loadedVoice = loaded["voiceGeneration"] as Map<*, *>
    assertEquals("Runway", loadedVideo["provider"])
    assertEquals("gen4_turbo", loadedVideo["model"])
    assertEquals(ProviderAuthProtocols.BEARER, loadedVideo["authProtocol"])
    assertEquals("tts-1", loadedVoice["model"])

    val saved = hostRuntime.saveMediaSpeechConfig(
      mapOf(
        "imageGeneration" to mapOf(
          "provider" to "Images",
          "baseUrl" to "https://images.example.com",
          "endpoint" to "/v1/images",
          "model" to "flux-pro",
          "authProtocol" to ProviderAuthProtocols.BEARER,
          "apiKey" to "image-key",
        ),
        "videoGeneration" to mapOf(
          "provider" to "Videos",
          "baseUrl" to "https://videos.example.com",
          "endpoint" to "/v1/videos",
          "model" to "gen4",
          "authProtocol" to ProviderAuthProtocols.ANTHROPIC,
          "apiKey" to "video-key",
        ),
        "voiceGeneration" to mapOf(
          "provider" to "Speech",
          "baseUrl" to "https://speech.example.com",
          "endpoint" to "/v1/audio/speech",
          "model" to "tts-omni",
          "voicePreset" to "nova · warm",
          "authProtocol" to ProviderAuthProtocols.NONE,
          "apiKey" to "",
        ),
        "sttRouteId" to SpeechToTextRouteId.EXTERNAL_API.wireValue,
        "externalStt" to mapOf(
          "provider" to "STT",
          "baseUrl" to "https://stt.example.com",
          "endpoint" to "/v1/transcribe",
          "model" to "whisper-large-v3",
          "authProtocol" to ProviderAuthProtocols.BEARER,
          "apiKey" to "stt-key",
        ),
        "onDeviceModel" to mapOf(
          "modelPackage" to "Whisper Small",
          "downloadStatus" to "ready",
        ),
      ),
    )

    val savedVideo = saved["videoGeneration"] as Map<*, *>
    val savedVoice = saved["voiceGeneration"] as Map<*, *>
    assertEquals("Videos", savedVideo["provider"])
    assertEquals(ProviderAuthProtocols.ANTHROPIC, savedVideo["authProtocol"])
    assertEquals("video-key", savedVideo["apiKey"])
    assertEquals("tts-omni", savedVoice["model"])
    assertEquals(ProviderAuthProtocols.NONE, savedVoice["authProtocol"])
  }

  @Test
  fun hostRuntimeStartupDoesNotAutoStartQueuedChatRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-startup-queued"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    chatStore.enqueuePendingUserInput(activeSessionId, "Queued follow-up")
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)

    hostRuntime(chatStore = chatStore, runtimeManager = manager)

    assertTrue(handle.submittedInputs.isEmpty())
    assertEquals(
      listOf("Queued follow-up"),
      chatStore.loadPendingUserInputs(activeSessionId).map(PendingUserInputEntry::text),
    )
  }

  @Test
  fun projectionOnlyHostRuntimeStartupDoesNotResumeActiveSession() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-projection-startup"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    manager.putHandle(
      RecordingSessionHandle(
        sessionId = activeSessionId,
        onResume = manager.resumedSessionIds::add,
      ),
    )

    hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      resumeActiveSessionOnInit = false,
    )

    assertTrue(manager.resumedSessionIds.isEmpty())
  }

  @Test
  fun selectChatSessionDoesNotAutoStartQueuedChatRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-select-queued"))
    val sessionAId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handleA = RecordingSessionHandle(
      sessionId = sessionAId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handleA)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.createChatSession()
    chatStore.enqueuePendingUserInput(sessionAId, "Queued follow-up")

    hostRuntime.selectChatSession(sessionAId)

    assertTrue(handleA.submittedInputs.isEmpty())
    assertEquals(
      listOf("Queued follow-up"),
      chatStore.loadPendingUserInputs(sessionAId).map(PendingUserInputEntry::text),
    )
  }

  @Test
  fun backgroundSessionReplyDoesNotSwitchActiveSessionAndMarksUnread() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-background-unread"))
    val sessionAId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handleA = RecordingSessionHandle(
      sessionId = sessionAId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handleA)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Reply later")
    val task = handleA.submittedTasks.single()

    hostRuntime.createChatSession()
    val sessionBId = chatStore.loadState().activeSession.sessionId

    manager.emitTaskFinished(
      sessionId = sessionAId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Background reply finished.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val drawer = snapshot["drawer"] as Map<*, *>
    val sessions = (drawer["sessions"] as List<*>).map { it as Map<*, *> }
    val sessionA = sessions.first { session -> session["sessionId"] == sessionAId }
    val sessionB = sessions.first { session -> session["sessionId"] == sessionBId }

    assertEquals(sessionBId, chatStore.loadState().activeSession.sessionId)
    assertEquals(true, sessionB["isSelected"])
    assertEquals(1, sessionA["unreadCount"])
    assertEquals(0, sessionB["unreadCount"])
  }

  @Test
  fun selectingSessionClearsUnreadCountForThatSession() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-clear-unread"))
    val sessionAId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handleA = RecordingSessionHandle(
      sessionId = sessionAId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handleA)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Reply later")
    val task = handleA.submittedTasks.single()
    hostRuntime.createChatSession()

    manager.emitTaskFinished(
      sessionId = sessionAId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Background reply finished.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    hostRuntime.selectChatSession(sessionAId)

    val snapshot = hostRuntime.loadChatSnapshot()
    val drawer = snapshot["drawer"] as Map<*, *>
    val sessions = (drawer["sessions"] as List<*>).map { it as Map<*, *> }
    val selectedSession = sessions.first { session -> session["sessionId"] == sessionAId }

    assertEquals(sessionAId, chatStore.loadState().activeSession.sessionId)
    assertEquals(true, selectedSession["isSelected"])
    assertEquals(0, selectedSession["unreadCount"])
  }

  @Test
  fun loadChatSnapshotIncludesTodosForActiveSession() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-todos"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      todoSnapshotProvider = { requestedSessionId ->
        if (requestedSessionId != sessionId) {
          ChatSessionTodoPresentation.empty()
        } else {
          ChatSessionTodoPresentation(
            todos = listOf(
              AgentTodoEntry(
                content = "Review chat composer layout",
                status = AgentTodoStatus.PENDING,
              ),
              AgentTodoEntry(
                content = "Highlight active todo text",
                status = AgentTodoStatus.IN_PROGRESS,
                activeForm = "Highlighting active todo text",
              ),
              AgentTodoEntry(
                content = "Approve Pencil prototype",
                status = AgentTodoStatus.COMPLETED,
              ),
            ),
            state = ChatSessionTodoPresentationState.ACTIVE,
          )
        }
      },
    )

    val snapshot = hostRuntime.loadChatSnapshot()

    val todos = snapshot["todos"] as List<*>
    val pendingTodo = todos[0] as Map<*, *>
    val activeTodo = todos[1] as Map<*, *>
    val completedTodo = todos[2] as Map<*, *>

    assertEquals(3, todos.size)
    assertEquals("active", snapshot["todoState"])
    assertEquals(null, snapshot["todoHideDelayMs"])
    assertEquals("Review chat composer layout", pendingTodo["content"])
    assertEquals("pending", pendingTodo["status"])
    assertEquals("Highlight active todo text", activeTodo["content"])
    assertEquals("in_progress", activeTodo["status"])
    assertEquals("Highlighting active todo text", activeTodo["activeForm"])
    assertEquals("Approve Pencil prototype", completedTodo["content"])
    assertEquals("completed", completedTodo["status"])
  }

  @Test
  fun loadChatSnapshotIncludesArchivedCompletedTodoVisibilityWindow() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-archived-todos"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      todoSnapshotProvider = { requestedSessionId ->
        if (requestedSessionId != sessionId) {
          ChatSessionTodoPresentation.empty()
        } else {
          ChatSessionTodoPresentation(
            todos = listOf(
              AgentTodoEntry(
                content = "Review chat composer layout",
                status = AgentTodoStatus.COMPLETED,
              ),
            ),
            state = ChatSessionTodoPresentationState.ARCHIVED_COMPLETED,
            hideDelayMs = 4_000L,
            completedAtEpochMs = 1_700_000_000_000L,
          )
        }
      },
    )

    val snapshot = hostRuntime.loadChatSnapshot()

    val todos = snapshot["todos"] as List<*>
    val completedTodo = todos.single() as Map<*, *>
    assertEquals("archived_completed", snapshot["todoState"])
    assertEquals(4_000L, snapshot["todoHideDelayMs"])
    assertEquals(1_700_000_000_000L, snapshot["todoCompletedAtEpochMs"])
    assertEquals("Review chat composer layout", completedTodo["content"])
    assertEquals("completed", completedTodo["status"])
  }

  @Test
  fun loadChatSnapshotBlocksInputWhileOnDeviceWarmupIsRunning() {
    val warmupController = RecordingOnDeviceLlmWarmupController(
      state = OnDeviceLlmWarmupState(phase = OnDeviceLlmWarmupPhase.WARMING),
    )
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-on-device-warmup")),
      runtimeManager = RecordingRuntimeManager(),
      llmConfigFacade = RecordingLlmConfigFacade(
        snapshot = readyOnDeviceLlmConfigSnapshot(),
      ),
      onDeviceLlmWarmupController = warmupController,
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val summary = snapshot["summary"] as Map<*, *>

    assertEquals(false, snapshot["isInputEnabled"])
    assertEquals("Preparing on-device model", snapshot["composerPlaceholder"])
    assertEquals("Preparing the on-device model.", summary["body"])
    assertEquals(OnDeviceLlmCatalog.GEMMA_4_E2B_IT, warmupController.lastSpec?.modelId)
  }

  @Test
  fun loadChatSnapshotKeepsInputEnabledAfterOnDeviceWarmupIsReady() {
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-on-device-ready")),
      runtimeManager = RecordingRuntimeManager(),
      llmConfigFacade = RecordingLlmConfigFacade(
        snapshot = readyOnDeviceLlmConfigSnapshot(),
      ),
      onDeviceLlmWarmupController = RecordingOnDeviceLlmWarmupController(
        state = OnDeviceLlmWarmupState(phase = OnDeviceLlmWarmupPhase.READY),
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()

    assertEquals(true, snapshot["isInputEnabled"])
    assertEquals("Message OpenCray", snapshot["composerPlaceholder"])
  }

  @Test
  fun loadChatSnapshotSurfacesWarmupFailureWithoutDisablingInput() {
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-on-device-warmup-failed")),
      runtimeManager = RecordingRuntimeManager(),
      llmConfigFacade = RecordingLlmConfigFacade(
        snapshot = readyOnDeviceLlmConfigSnapshot(),
      ),
      onDeviceLlmWarmupController = RecordingOnDeviceLlmWarmupController(
        state = OnDeviceLlmWarmupState(
          phase = OnDeviceLlmWarmupPhase.FAILED,
          failureMessage = "Model warmup failed.",
        ),
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()

    assertEquals(true, snapshot["isInputEnabled"])
    assertEquals("Model warmup failed.", snapshot["composerPlaceholder"])
  }

  @Test
  fun chatObserverPublishesTodoSnapshotWhenTodoWriteResultArrives() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-todo-observer"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = sessionId,
      onResume = runtimeManager.resumedSessionIds::add,
    )
    runtimeManager.putHandle(handle)
    val mainThreadPoster = QueuedMainThreadPoster()
    var currentTodoPresentation = ChatSessionTodoPresentation.empty()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      todoSnapshotProvider = { requestedSessionId ->
        if (requestedSessionId == sessionId) {
          currentTodoPresentation
        } else {
          ChatSessionTodoPresentation.empty()
        }
      },
      mainThreadPoster = mainThreadPoster,
    )
    val observedSnapshots = mutableListOf<Map<String, Any?>>()
    val dispose = hostRuntime.observeChat { snapshot ->
      observedSnapshots += snapshot
    }
    mainThreadPoster.flush()
    observedSnapshots.clear()

    hostRuntime.submitChatMessage("Track active todo changes")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    mainThreadPoster.flush()
    observedSnapshots.clear()

    currentTodoPresentation = ChatSessionTodoPresentation(
      todos = listOf(
        AgentTodoEntry(
          content = "Review chat composer layout",
          status = AgentTodoStatus.IN_PROGRESS,
          activeForm = "Reviewing chat composer layout",
        ),
      ),
      state = ChatSessionTodoPresentationState.ACTIVE,
    )
    runtimeManager.emitRunEvent(
      sessionId = sessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "TodoWrite"),
        result = AgentToolResult(
          toolName = "TodoWrite",
          status = AgentToolResultStatus.SUCCESS,
          content = "[in_progress] Review chat composer layout | active: Reviewing chat composer layout",
          metadata = mapOf(
            "todoCount" to "1",
            "mutated" to "true",
          ),
        ),
        emittedAtEpochMs = 1_001L,
      ),
    )
    mainThreadPoster.flush()

    val addedTodos = observedSnapshots.last()["todos"] as List<*>
    val addedTodo = addedTodos.single() as Map<*, *>
    assertEquals("Review chat composer layout", addedTodo["content"])
    assertEquals("in_progress", addedTodo["status"])
    assertEquals("Reviewing chat composer layout", addedTodo["activeForm"])

    observedSnapshots.clear()
    currentTodoPresentation = ChatSessionTodoPresentation.empty()
    runtimeManager.emitRunEvent(
      sessionId = sessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 1,
        call = AgentToolCall(toolName = "TodoWrite"),
        result = AgentToolResult(
          toolName = "TodoWrite",
          status = AgentToolResultStatus.SUCCESS,
          content = "Todo list is empty.",
          metadata = mapOf(
            "todoCount" to "0",
            "mutated" to "true",
          ),
        ),
        emittedAtEpochMs = 1_002L,
      ),
    )
    mainThreadPoster.flush()
    dispose()

    val clearedTodos = observedSnapshots.last()["todos"] as List<*>
    assertTrue(clearedTodos.isEmpty())
  }

  @Test
  fun copyChatSessionDuplicatesTranscriptAndSelectsCopy() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-copy"))
    val originalSessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(originalSessionId, "Clone this thread")
    chatStore.appendMessage(
      sessionId = originalSessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Copied reply.",
    )
    val manager = RecordingRuntimeManager()
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.copyChatSession(originalSessionId)

    val copiedSessionId = chatStore.loadState().activeSession.sessionId
    val originalMessages = checkNotNull(chatStore.loadSession(originalSessionId)).messages
    val copiedMessages = checkNotNull(chatStore.loadSession(copiedSessionId)).messages

    assertTrue(copiedSessionId != originalSessionId)
    assertEquals(listOf(originalSessionId, copiedSessionId), manager.resumedSessionIds)
    assertEquals(originalMessages, copiedMessages)
  }

  @Test
  fun branchChatSessionFromMessageClonesTranscriptPrefixIntoNewSession() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-branch"))
    val originalSessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(originalSessionId, "Keep the first turn")
    chatStore.appendMessage(
      sessionId = originalSessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "First assistant reply.",
    )
    chatStore.appendUserMessage(originalSessionId, "Drop the later turn")
    val originalMessages = checkNotNull(chatStore.loadSession(originalSessionId)).messages
    val branchMessageId = originalMessages
      .first { message ->
        message.role == ChatTranscriptRole.ASSISTANT &&
          message.text == "First assistant reply."
      }
      .messageId
    val branchIndex = originalMessages.indexOfFirst { message -> message.messageId == branchMessageId }
    val manager = RecordingRuntimeManager()
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.branchChatSessionFromMessage(originalSessionId, branchMessageId)

    val branchedSessionId = chatStore.loadState().activeSession.sessionId
    val branchedMessages = checkNotNull(chatStore.loadSession(branchedSessionId)).messages

    assertTrue(branchedSessionId != originalSessionId)
    assertEquals(listOf(originalSessionId, branchedSessionId), manager.resumedSessionIds)
    assertEquals(originalMessages.take(branchIndex + 1), branchedMessages)
    assertEquals(originalMessages, checkNotNull(chatStore.loadSession(originalSessionId)).messages)
  }

  @Test
  fun deleteChatSessionCreatesReplacementWhenRemovingLastSession() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-delete-last"))
    val originalSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.deleteChatSession(originalSessionId)

    val state = chatStore.loadState()

    assertEquals(1, state.sessions.size)
    assertTrue(state.activeSession.sessionId != originalSessionId)
    assertEquals(listOf(originalSessionId), manager.releasedSessionIds)
  }

  @Test
  fun deleteChatSessionTerminatesLiveManagedProcessesBeforeRelease() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-delete-processes"))
    val originalSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = originalSessionId)
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-running",
        taskId = "task-running",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 120_000L,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
      ),
    )
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-success",
        taskId = "task-success",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.SUCCESS,
        processStarted = true,
        timeoutMs = 120_000L,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
        finishedAtEpochMs = 1_001L,
      ),
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.deleteChatSession(originalSessionId)

    assertEquals(listOf("proc-running"), handle.terminatedProcessIds)
    assertEquals(listOf(originalSessionId), manager.releasedSessionIds)
  }

  @Test
  fun deleteChatSessionIgnoresLateRuntimeEventsFromRemovedSession() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-delete-late-events"))
    val sessionAId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handleA = RecordingSessionHandle(
      sessionId = sessionAId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handleA)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Reply later")
    val task = handleA.submittedTasks.single()
    hostRuntime.createChatSession()
    val sessionBId = chatStore.loadState().activeSession.sessionId

    hostRuntime.deleteChatSession(sessionAId)

    manager.emitTaskFinished(
      sessionId = sessionAId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Late reply ignored.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val drawer = snapshot["drawer"] as Map<*, *>
    val sessions = (drawer["sessions"] as List<*>).map { it as Map<*, *> }

    assertEquals(listOf(sessionAId), manager.releasedSessionIds)
    assertEquals(sessionBId, chatStore.loadState().activeSession.sessionId)
    assertEquals(1, sessions.size)
    assertTrue(sessions.none { session -> session["sessionId"] == sessionAId })
  }

  @Test
  fun deleteChatMessageRemovesPendingAssistantMessageAndCancelsMatchingRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-delete-message"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = sessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Delete the pending bubble")
    val sessionBeforeDelete = checkNotNull(chatStore.loadSession(sessionId))
    val assistantMessageId = sessionBeforeDelete.messages
      .last { message -> message.role == ChatTranscriptRole.ASSISTANT }
      .messageId

    hostRuntime.deleteChatMessage(sessionId, assistantMessageId)

    val remainingMessages = checkNotNull(chatStore.loadSession(sessionId)).messages

    assertTrue(remainingMessages.none { message -> message.messageId == assistantMessageId })
    assertEquals(listOf(handle.submissions.single().taskId), handle.cancelledTaskIds)
    assertEquals(listOf(setOf(assistantMessageId)), handle.cancelledPendingMessageIdSets)
  }

  @Test
  fun recallChatMessageDropsPromptTailAndCancelsPendingRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-recall-message"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = sessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Recall this turn")
    val sessionBeforeRecall = checkNotNull(chatStore.loadSession(sessionId))
    val userMessageId = sessionBeforeRecall.messages
      .last { message -> message.role == ChatTranscriptRole.USER }
      .messageId
    val assistantMessageId = sessionBeforeRecall.messages
      .last { message -> message.role == ChatTranscriptRole.ASSISTANT }
      .messageId

    hostRuntime.recallChatMessage(sessionId, userMessageId)

    val remainingMessages = checkNotNull(chatStore.loadSession(sessionId)).messages

    assertTrue(remainingMessages.none { message -> message.messageId == userMessageId })
    assertTrue(remainingMessages.none { message -> message.messageId == assistantMessageId })
    assertEquals(listOf(handle.submissions.single().taskId), handle.cancelledTaskIds)
    assertEquals(
      listOf(linkedSetOf(userMessageId, assistantMessageId)),
      handle.cancelledPendingMessageIdSets,
    )
  }

  @Test
  fun submitChatMessageCancelsQueuedTaskWhenTranscriptPersistenceFails() {
    val chatStore = FailingChatSessionLocalStore(temporaryFolder.newFolder("chat-store-persist-fail"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    runCatching {
      hostRuntime.submitChatMessage("This write will fail")
    }

    val messages = chatStore.loadState().activeSession.messages

    assertEquals(1, messages.size)
    assertEquals(listOf(handle.submissions.single().taskId), handle.cancelledTaskIds)
    assertTrue(handle.ensureProcessingTaskIds.isEmpty())
  }

  @Test
  fun submitChatMessageQueuesBeforePersistingTranscript() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-submit-order"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need a durable owner path")

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals(listOf("Need a durable owner path", "Thinking"), messages.map { it.text })
    assertEquals(listOf("Need a durable owner path"), handle.submittedInputs)
    assertEquals(listOf(handle.submissions.single().taskId), handle.ensureProcessingTaskIds)
  }

  @Test
  fun submitChatMessageArchivesUserAttachmentsAndAllowsEmptyText() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-user-attachments"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-user-attachments-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("imports"))
    Files.write(
      workspaceRoot.resolve("imports").resolve("workspace-shot.png"),
      byteArrayOf(1, 2, 3, 4),
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

    hostRuntime.submitChatMessage(
      text = "",
      attachments = listOf(
        OpenCrayFinalAttachment(
          kind = "image",
          relativePath = "imports/workspace-shot.png",
          displayName = "workspace-shot.png",
        ),
      ),
    )

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val userMessage = messages.first()
    val userAttachment = userMessage.attachments.single()

    assertEquals(listOf(null, "Thinking"), messages.map { it.text })
    assertTrue(handle.submittedInputs.single().contains("Attachments:"))
    assertTrue(handle.submittedInputs.single().contains("workspace-shot.png"))
    assertTrue(handle.submittedInputs.single().contains("kind=image"))
    assertTrue(handle.submittedInputs.single().contains("chat_attachment_id="))
    assertEquals(ChatAttachmentKind.IMAGE, userAttachment.kind)
    assertEquals("workspace-shot.png", userAttachment.displayName)
    assertTrue(userAttachment.localPath.startsWith(".opencray/chat-media/$activeSessionId/"))
    assertTrue(Files.exists(workspaceRoot.resolve(userAttachment.localPath)))
  }

  @Test
  fun submitChatMessageStoresStructuredPromptAttachmentMetadataForRuntimeReplay() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-attachment-runtime-metadata"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-attachment-runtime-metadata-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("imports"))
    Files.write(
      workspaceRoot.resolve("imports").resolve("workspace-shot.png"),
      byteArrayOf(1, 2, 3, 4),
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

    hostRuntime.submitChatMessage(
      text = "",
      attachments = listOf(
        OpenCrayFinalAttachment(
          kind = "image",
          relativePath = "imports/workspace-shot.png",
          displayName = "workspace-shot.png",
        ),
      ),
    )

    val userAttachment = chatStore.loadState().activeSession.messages
      .first { message -> message.role == ChatTranscriptRole.USER }
      .attachments
      .single()
    val submittedTask = handle.submittedTasks.single()
    val encodedAttachments = submittedTask.metadata[
      AppAgentSessionTaskRuntimeFactory.METADATA_PROMPT_RUNTIME_ATTACHMENTS_JSON
    ].orEmpty()
    val runtimeAttachments = Json.decodeFromString(
      ListSerializer(RuntimeConversationAttachment.serializer()),
      encodedAttachments,
    )

    assertEquals("", submittedTask.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PROMPT_USER_TEXT])
    assertEquals(1, runtimeAttachments.size)
    assertEquals(userAttachment.attachmentId, runtimeAttachments.single().attachmentId)
    assertEquals("workspace-shot.png", runtimeAttachments.single().displayName)
    assertEquals("image/png", runtimeAttachments.single().mimeType)
    assertEquals(
      workspaceRoot.resolve(userAttachment.localPath).toAbsolutePath().normalize().toString().replace('\\', '/'),
      runtimeAttachments.single().filePath,
    )
  }

  @Test
  fun submitChatMessageWhileRunActiveStoresSupplementInsteadOfQueueingFollowUp() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-mid-loop-supplement"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Inspect the repo")
    val secondRun = hostRuntime.submitChatMessage("Also check the tests")

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val queuedInputs = chatStore.loadPendingUserInputs(activeSessionId)
    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(null, secondRun)
    assertEquals(listOf("Inspect the repo", "Thinking"), messages.map { it.text })
    assertTrue(queuedInputs.isEmpty())
    assertEquals(
      listOf("Inspect the repo", "Thinking", "Also check the tests"),
      renderedMessages,
    )
    assertEquals(listOf("Inspect the repo"), handle.submittedInputs)
    assertTrue(handle.cancelledTaskIds.isEmpty())
  }

  @Test
  fun submitChatMessageAfterLiveProcessRunCompletesStillTargetsSameRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-live-process-follow-up"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val supplementStore = InMemorySessionSupplementStore()
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      override fun forChatSession(sessionId: String): SessionSupplementStore = supplementStore
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      supplementStoreFactory = supplementStoreFactory,
    )

    hostRuntime.submitChatMessage("Start the dev server")
    val task = handle.submittedTasks.single()
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-live-follow-up",
        taskId = task.id,
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 120_000L,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Server is running in the background.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_002L,
        metadata = task.metadata,
      ),
    )

    val secondRun = hostRuntime.submitChatMessage("Also check the logs")
    val queuedInputs = chatStore.loadPendingUserInputs(activeSessionId)
    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(null, secondRun)
    assertEquals(listOf("Start the dev server"), handle.submittedInputs)
    assertTrue(queuedInputs.isEmpty())
    assertEquals(listOf("Also check the logs"), supplementStore.snapshot().map { it.text })
    assertEquals(
      listOf(
        "Start the dev server",
        "Server is running in the background.",
        "Also check the logs",
      ),
      renderedMessages,
    )
  }

  @Test
  fun activeRunDrawerPreviewUsesRuntimeProgressInsteadOfThinkingPlaceholder() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-drawer-progress-preview"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val run = hostRuntime.submitChatMessage("Inspect the repo")!!
    val task = handle.submittedTasks.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run["runId"] as String,
        taskId = task.id,
        turn = 1,
        isFinal = false,
        stage = "Planning",
        text = "Inspecting README and Gradle files before the next tool call.",
        emittedAtEpochMs = 1_100L,
      ),
    )

    val drawer = hostRuntime.loadChatSnapshot()["drawer"] as Map<*, *>
    val session = ((drawer["sessions"] as List<*>).single()) as Map<*, *>
    val preview = session["preview"] as String

    assertTrue(preview.contains("Inspecting README and Gradle files"))
    assertFalse(preview.startsWith("Thinking"))
  }

  @Test
  fun submitChatMessageWhileRunActiveQueuesAttachmentsAsFollowUp() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-queued-attachment-follow-up"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("chat-queued-attachments-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve("imports"))
    Files.write(
      workspaceRoot.resolve("imports").resolve("workspace-shot.png"),
      byteArrayOf(1, 2, 3, 4),
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

    hostRuntime.submitChatMessage("Inspect the repo")
    val secondRun = hostRuntime.submitChatMessage(
      text = "",
      attachments = listOf(
        OpenCrayFinalAttachment(
          kind = "image",
          relativePath = "imports/workspace-shot.png",
          displayName = "workspace-shot.png",
        ),
      ),
    )

    val queuedInputs = chatStore.loadPendingUserInputs(activeSessionId)
    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { it as Map<*, *> }
    val queuedMessage = renderedMessages.last()
    val queuedAttachments = queuedMessage["attachments"] as List<*>

    assertEquals(null, secondRun)
    assertEquals(listOf("Inspect the repo"), handle.submittedInputs)
    assertEquals(listOf(""), queuedInputs.map { it.text })
    assertEquals(1, queuedInputs.single().attachments.size)
    assertEquals(1, queuedAttachments.size)
    assertEquals("workspace-shot.png", (queuedAttachments.single() as Map<*, *>)["displayName"])
  }

  @Test
  fun replayedSupplementEventProjectsOutboundBubbleForCurrentRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-replayed-supplement"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    var replayMessages: List<RuntimeConversationMessage> = emptyList()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      transcriptMessagesProvider = { replayMessages },
    )

    hostRuntime.submitChatMessage("Inspect the repo")
    val submittedTask = handle.submittedTasks.single()
    val submittedRun = handle.submissions.single()
    replayMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """
        {"event_kind":"supplement","run_id":"${submittedRun.runId}","task_id":"${submittedTask.id}","turn":1,"entry_id":"supplement-1","text":"Also check the tests","checkpoint":"turn_start"}
        """.trimIndent(),
      ),
    )

    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { it as Map<*, *> }

    assertEquals(
      listOf("Inspect the repo", "Also check the tests", "Thinking"),
      renderedMessages.map { message -> message["text"] },
    )
    assertEquals("outbound", renderedMessages[1]["kind"])
  }

  @Test
  fun toolGeneratedSupplementEventStaysInRunTraceButNotChatBubble() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-tool-generated-supplement"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Search the latest OpenCray updates")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCraySupplementEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 1,
        entryId = "tool-supplement-web-search-1",
        text = "Provider-native web search ran for \"OpenCray\" within opencray.com.",
        checkpoint = "post_tool_pre_model",
        emittedAtEpochMs = 1_150L,
      ),
    )

    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val renderedMessages = (chatSnapshot["messages"] as List<*>)
      .map { it as Map<*, *> }
    val runtimeActivity = chatSnapshot["runtimeActivity"] as Map<*, *>
    val supplementEvents = (runtimeActivity["events"] as List<*>)
      .map { it as Map<*, *> }
      .filter { event -> event["kind"] == "supplement" }

    assertEquals(
      listOf("Search the latest OpenCray updates", "Thinking"),
      renderedMessages.map { message -> message["text"] },
    )
    assertEquals(1, supplementEvents.size)
    assertEquals(
      "Provider-native web search ran for \"OpenCray\" within opencray.com.",
      supplementEvents.single()["text"],
    )
  }

  @Test
  fun liveProcessRunCompletionDoesNotPromoteExistingSupplementsIntoNewRun() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-live-process-supplement-retention"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val supplementStore = InMemorySessionSupplementStore()
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      override fun forChatSession(sessionId: String): SessionSupplementStore = supplementStore
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      supplementStoreFactory = supplementStoreFactory,
    )

    hostRuntime.submitChatMessage("Start the dev server")
    val task = handle.submittedTasks.single()
    assertEquals(null, hostRuntime.submitChatMessage("Also inspect the logs"))
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-live-retained",
        taskId = task.id,
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 120_000L,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
      ),
    )

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Server is running in the background.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_002L,
        metadata = task.metadata,
      ),
    )

    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(listOf("Start the dev server"), handle.submittedInputs)
    assertTrue(chatStore.loadPendingUserInputs(activeSessionId).isEmpty())
    assertEquals(listOf("Also inspect the logs"), supplementStore.snapshot().map { it.text })
    assertEquals(
      listOf(
        "Start the dev server",
        "Server is running in the background.",
        "Also inspect the logs",
      ),
      renderedMessages,
    )
  }

  @Test
  fun unconsumedSupplementsPromoteIntoNextQueuedRunAfterCurrentRunFinishes() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-supplement-promote"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Inspect the workspace")
    val firstTask = handle.submittedTasks.first()
    assertEquals(null, hostRuntime.submitChatMessage("Also inspect the tests"))

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = firstTask,
      result = ExecutionResult(
        taskId = firstTask.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "First done.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = firstTask.metadata,
      ),
    )

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals(
      listOf("Inspect the workspace", "First done.", "Also inspect the tests", "Thinking"),
      messages.map { it.text },
    )
    assertEquals(listOf("Inspect the workspace", "Also inspect the tests"), handle.submittedInputs)
    assertTrue(chatStore.loadPendingUserInputs(activeSessionId).isEmpty())
  }

  @Test
  fun submitChatMessageWhileRunActiveKeepsDeferredQueueAsFollowUps() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-queued-follow-up"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Inspect the repo")
    chatStore.enqueuePendingUserInput(activeSessionId, "Already queued")
    val secondRun = hostRuntime.submitChatMessage("Also check the tests")

    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val queuedInputs = chatStore.loadPendingUserInputs(activeSessionId)
    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }
    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val activeRuns = (runtimeActivity["activeRuns"] as List<*>).map { it as Map<*, *> }

    assertEquals(null, secondRun)
    assertEquals(listOf("Inspect the repo", "Thinking"), messages.map { it.text })
    assertEquals(
      listOf("Already queued", "Also check the tests"),
      queuedInputs.map { it.text },
    )
    assertEquals(
      listOf("Inspect the repo", "Thinking", "Already queued", "Also check the tests"),
      renderedMessages,
    )
    assertEquals(listOf("Inspect the repo"), handle.submittedInputs)
    assertTrue(handle.cancelledTaskIds.isEmpty())
    assertTrue(handle.cancelledPendingMessageIdSets.isEmpty())
    assertEquals(1, activeRuns.size)
    assertEquals(handle.submissions.first().runId, activeRuns.single()["runId"])
  }

  @Test
  fun approvalRequiredResultDemotesExistingSupplementsIntoDeferredFollowUps() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-approval-demote-supplement"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need approval")
    assertEquals(null, hostRuntime.submitChatMessage("Use a safer approach"))
    val task = handle.submittedTasks.single()

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Write",
        ),
      ),
    )

    val queuedInputs = chatStore.loadPendingUserInputs(activeSessionId)
    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(listOf("Use a safer approach"), queuedInputs.map { it.text })
    assertEquals(
      listOf(
        "Need approval",
        "Approval is required before Write can run.",
        "Use a safer approach",
      ),
      renderedMessages,
    )
  }

  @Test
  fun chatSnapshotSummaryShowsSupplementRecordedWhileRunIsStillActive() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-summary-supplement"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Inspect the repo")
    assertEquals(null, hostRuntime.submitChatMessage("Also inspect the tests"))

    val summary = hostRuntime.loadChatSnapshot()["summary"] as Map<*, *>

    assertEquals(
      "Recorded. This will be applied to the current run when it reaches the next safe checkpoint.",
      summary["body"],
    )
  }

  @Test
  fun queuedFollowUpsDrainInOrderAfterEachRunCompletes() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-drain-follow-ups"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Inspect the workspace")
    val firstTask = handle.submittedTasks.first()
    chatStore.enqueuePendingUserInput(activeSessionId, "Also inspect the tests")
    assertEquals(null, hostRuntime.submitChatMessage("Then summarize"))

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = firstTask,
      result = ExecutionResult(
        taskId = firstTask.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "First done.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = firstTask.metadata,
      ),
    )

    val secondTask = handle.submittedTasks.last()
    val afterFirstCompletionMessages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val afterFirstRuntime = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val afterFirstActiveRuns = (afterFirstRuntime["activeRuns"] as List<*>)
      .map { it as Map<*, *> }

    assertEquals(
      listOf("Inspect the workspace", "First done.", "Also inspect the tests", "Thinking"),
      afterFirstCompletionMessages.map { it.text },
    )
    assertEquals(1, afterFirstActiveRuns.size)
    assertEquals(handle.submissions.last().runId, afterFirstActiveRuns.single()["runId"])
    assertEquals(listOf("Inspect the workspace", "Also inspect the tests"), handle.submittedInputs)
    assertEquals(listOf("Then summarize"), chatStore.loadPendingUserInputs(activeSessionId).map { it.text })

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = secondTask,
      result = ExecutionResult(
        taskId = secondTask.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Second done.",
        startedAtEpochMs = 1_002L,
        finishedAtEpochMs = 1_003L,
        metadata = secondTask.metadata,
      ),
    )

    val thirdTask = handle.submittedTasks.last()
    val afterSecondCompletionMessages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val afterSecondRuntime = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val afterSecondActiveRuns = afterSecondRuntime["activeRuns"] as List<*>

    assertEquals(
      listOf(
        "Inspect the workspace",
        "First done.",
        "Also inspect the tests",
        "Second done.",
        "Then summarize",
        "Thinking",
      ),
      afterSecondCompletionMessages.map { it.text },
    )
    assertEquals(listOf("Inspect the workspace", "Also inspect the tests", "Then summarize"), handle.submittedInputs)
    assertEquals(1, afterSecondActiveRuns.size)
    assertTrue(chatStore.loadPendingUserInputs(activeSessionId).isEmpty())

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = thirdTask,
      result = ExecutionResult(
        taskId = thirdTask.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "All set.",
        startedAtEpochMs = 1_004L,
        finishedAtEpochMs = 1_005L,
        metadata = thirdTask.metadata,
      ),
    )

    val finalMessages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }
    val finalRuntime = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val finalActiveRuns = finalRuntime["activeRuns"] as List<*>

    assertEquals(
      listOf(
        "Inspect the workspace",
        "First done.",
        "Also inspect the tests",
        "Second done.",
        "Then summarize",
        "All set.",
      ),
      finalMessages.map { it.text },
    )
    assertTrue(finalActiveRuns.isEmpty())
  }

  @Test
  fun submitChatMessageDoesNotAttachHostOnlyPolicyDetailToPromptTask() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-policy-detail"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Check approval behavior")

    val submittedTask = handle.submittedTasks.single()

    assertEquals(PolicyDecisionOutcome.ALLOW, submittedTask.policyDecision.outcome)
    assertEquals("FLUTTER_CHAT_ALLOW", submittedTask.policyDecision.reasonCode)
    assertEquals(null, submittedTask.policyDecision.detail)
  }

  @Test
  fun submitChatMessageIncludesCurrentSafetyMetadataOverrides() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-safety-metadata"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val safetyFacade = RecordingSafetySettingsFacade(
      snapshot = defaultSafetySettingsSnapshot().copy(
        automationMode = SafetyAutomationMode.DEV,
        fileChangesPolicy = ToolPolicyOverride.ALLOW,
        fileDeletesPolicy = ToolPolicyOverride.BLOCK,
        shellCommandsPolicy = ToolPolicyOverride.ASK,
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      safetySettingsFacade = safetyFacade,
    )

    hostRuntime.submitChatMessage("Check the current guardrails")

    val submittedTask = handle.submittedTasks.single()

    assertEquals("DEV", submittedTask.metadata["chatMode"])
    assertEquals("DEVELOPER", submittedTask.metadata["executionMode"])
    assertEquals("allow", submittedTask.metadata["fileChangesPolicyId"])
    assertEquals("block", submittedTask.metadata["fileDeletesPolicyId"])
    assertEquals("ask", submittedTask.metadata["shellCommandsPolicyId"])
  }

  @Test
  fun submitChatMessageIncludesApprovedReadRootsMetadata() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approved-read-roots"))
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
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = emptySet(),
          summary = "workspace=/workspace | photo_library=/storage/emulated/0/DCIM,/storage/emulated/0/Pictures",
        )
      },
    )

    hostRuntime.submitChatMessage("Check external read roots")

    val submittedTask = handle.submittedTasks.single()

    assertEquals("select_paths", submittedTask.metadata["externalAccessModeId"])
    assertEquals("true", submittedTask.metadata["readOnlyOutsideWorkspace"])
    assertEquals(
      "workspace=/workspace | photo_library=/storage/emulated/0/DCIM,/storage/emulated/0/Pictures",
      submittedTask.metadata["approvedReadRoots"],
    )
  }

  @Test
  fun chatSnapshotReflectsCurrentSafetyModeLabel() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-safety-mode"))
    val manager = RecordingRuntimeManager()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      safetySettingsFacade = RecordingSafetySettingsFacade(
        snapshot = defaultSafetySettingsSnapshot().copy(
          automationMode = SafetyAutomationMode.SAFE,
        ),
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()

    assertEquals("SAFE", snapshot["modeLabel"])
  }

  @Test
  fun saveSafetySettingsPersistsLiveContextMode() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-live-context-mode"))
    val safetyFacade = RecordingSafetySettingsFacade()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      safetySettingsFacade = safetyFacade,
    )

    val payload = hostRuntime.saveSafetySettings(
      automationModeId = SafetyAutomationMode.AUTO.wireValue,
      rollbackJournalEnabled = true,
      maxFilesPerBatch = 20,
      maxAgentTurns = 0,
      maxToolCalls = 0,
      undoWindowHours = 24,
      fileChangesPolicyId = ToolPolicyOverride.INHERIT.wireValue,
      fileDeletesPolicyId = ToolPolicyOverride.INHERIT.wireValue,
      shellCommandsPolicyId = ToolPolicyOverride.INHERIT.wireValue,
      externalAccessModeId = ExternalAccessMode.SELECT_PATHS.wireValue,
      photoLibraryEnabled = true,
      downloadsEnabled = true,
      documentsEnabled = false,
      recordingsEnabled = false,
      workspaceAccessProfileId = WorkspaceAccessProfile.WORK.wireValue,
      readOnlyOutsideWorkspace = true,
      liveContextModeId = LiveContextMode.NO_SOUL.wireValue,
      memoryToolsEnabled = false,
    )

    assertEquals(LiveContextMode.NO_SOUL.wireValue, payload["liveContextModeId"])
    assertEquals(false, payload["memoryToolsEnabled"])
    assertEquals(LiveContextMode.NO_SOUL.wireValue, safetyFacade.lastSavedRequest?.liveContextModeId)
    assertEquals(false, safetyFacade.lastSavedRequest?.memoryToolsEnabled)
    assertEquals(LiveContextMode.NO_SOUL, safetyFacade.snapshot.liveContextMode)
    assertEquals(false, safetyFacade.snapshot.memoryToolsEnabled)
  }

  @Test
  fun saveSandboxSettingsPersistsCloudRoutingAndApiKey() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-sandbox-settings"))
    val repository = testSandboxSettingsRepository()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      sandboxSettingsRepository = repository,
    )

    val initialPayload = hostRuntime.loadSandboxSettings()
    val savedPayload = hostRuntime.saveSandboxSettings(
      mapOf(
        "enabled" to true,
        "providerId" to "e2b",
        "defaultBackend" to "sandbox",
        "sessionMode" to "sticky",
        "autoResume" to true,
        "idleTimeoutMinutes" to 25,
        "startupTimeoutMs" to 45_000L,
        "requestTimeoutMs" to 600_000L,
        "timeoutAction" to "pause",
        "templateId" to "python-sandbox",
        "e2bApiKey" to "e2b_secret",
      ),
    )
    val resolved = repository.load()

    assertEquals("local", initialPayload["defaultBackend"])
    assertEquals(false, initialPayload["apiKeyConfigured"])
    assertEquals(true, savedPayload["enabled"])
    assertEquals("sandbox", savedPayload["defaultBackend"])
    assertEquals("sticky", savedPayload["sessionMode"])
    assertEquals("pause", savedPayload["timeoutAction"])
    assertEquals("e2b_secret", savedPayload["e2bApiKey"])
    assertEquals(true, savedPayload["apiKeyConfigured"])
    assertEquals("sandbox", resolved.state.defaultBackend)
    assertEquals("sticky", resolved.state.sessionMode)
    assertEquals("pause", resolved.state.timeoutAction)
    assertEquals("python-sandbox", resolved.state.templateId)
    assertEquals("e2b_secret", resolved.e2bApiKey)
  }

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

      ![camera_first.jpg](attachment:artifact)

      [report.pdf](attachment:artifact)
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

    assertEquals("![diagram.png](attachment:artifact)", assistantMessage.text)
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

    assertEquals("![diagram.png](attachment:artifact)", assistantMessage.text)
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
    val runtimeActivity = snapshot["runtimeActivity"] as Map<*, *>
    val activeRuns = runtimeActivity["activeRuns"] as List<*>

    assertEquals("Settled final reply", messages.last()["text"])
    assertEquals("Local transcript is restored into the runtime window for each task.", summary["body"])
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

  @Test
  fun approvalRequiredFailureAppearsInPendingApprovals() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-pending"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need approval")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
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

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val pendingApproval = pendingApprovals.single() as Map<*, *>
    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals("Approval is required before Write can run.", messages.last().text)
    assertEquals(run.runId, pendingApproval["runId"])
    assertEquals(task.id, pendingApproval["taskId"])
    assertEquals("standard", pendingApproval["risk"])
    assertEquals("Approval is required before Write can run.", pendingApproval["body"])
  }

  @Test
  fun approvalRequiredFailureRedactsInternalToolPayloadFromBubbleAndApprovalBody() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-redaction"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need approval")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = """{"type":"tool_call","tool_name":"Write","arguments":{"file_path":"note.txt","content":"secret"}}""",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val pendingApproval = pendingApprovals.single() as Map<*, *>
    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals("Approval required before the agent can continue.", messages.last().text)
    assertEquals("Approval required before the agent can continue.", pendingApproval["body"])
  }

  @Test
  fun approvalRequiredFailureIncludesToolReasonInPendingApprovalBody() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-reason"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need approval")
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
        metadata = task.metadata + mapOf(
          "toolReason" to "Need to update notes.txt before answering.",
        ),
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val pendingApproval = pendingApprovals.single() as Map<*, *>

    assertEquals(
      "Agent reason: Need to update notes.txt before answering.\n\nApproval is required before Write can run.",
      pendingApproval["body"],
    )
    assertEquals("Need to update notes.txt before answering.", pendingApproval["reason"])
    assertEquals("Approval is required before Write can run.", pendingApproval["message"])
  }

  @Test
  fun approvalRequiredFailureIncludesToolNameAndConcreteRequestDetailsInPendingApprovalBody() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-details"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need shell approval")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Bash can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Bash",
          "shellCommand" to "git status --short",
          "workingDirectory" to ".",
          "toolReason" to "Check repository state before editing.",
        ),
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val pendingApproval = pendingApprovals.single() as Map<*, *>
    val body = pendingApproval["body"] as String

    assertEquals("Bash", pendingApproval["toolName"])
    assertEquals("git status --short", pendingApproval["requestSummary"])
    assertEquals("git status --short", pendingApproval["primaryDetail"])
    assertEquals(".", pendingApproval["workingDirectory"])
    assertEquals("Check repository state before editing.", pendingApproval["reason"])
    assertEquals("Approval is required before Bash can run.", pendingApproval["message"])
    assertTrue(body.contains("Command: git status --short"))
    assertTrue(body.contains("Working directory: ."))
    assertTrue(body.contains("Agent reason: Check repository state before editing."))
    assertTrue(body.contains("Approval is required before Bash can run."))
  }

  @Test
  fun approvalRequiredFailureIncludesDelegationDetailsInPendingApprovalBody() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-delegation"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need delegation approval")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Task can delegate this work.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Task",
          "targetSummary" to "Inspect README",
          "delegationDescription" to "Inspect README",
          "delegationPromptPreview" to "Read README.md and summarize it.",
          "delegationAllowedTools" to "Glob,Grep,LS,Read",
          "toolReason" to "Use a child researcher to inspect the repo first.",
        ),
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val pendingApproval = pendingApprovals.single() as Map<*, *>
    val body = pendingApproval["body"] as String

    assertEquals("Task", pendingApproval["toolName"])
    assertEquals("Inspect README", pendingApproval["requestSummary"])
    assertEquals("Inspect README", pendingApproval["primaryDetail"])
    assertEquals("Use a child researcher to inspect the repo first.", pendingApproval["reason"])
    assertEquals("Approval is required before Task can delegate this work.", pendingApproval["message"])
    assertTrue(body.contains("Request: Inspect README"))
    assertTrue(body.contains("Prompt: Read README.md and summarize it."))
    assertTrue(body.contains("Allowed tools: Glob,Grep,LS,Read"))
    assertTrue(body.contains("Agent reason: Use a child researcher to inspect the repo first."))
  }

  @Test
  fun approvalRequiredFailureAddsApprovalWaitEventToRuntimeActivity() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-runtime-event"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need approval")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Write",
          "toolReason" to "Update the notes before answering.",
        ),
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val approvalEvent = (runtimeActivity["events"] as List<*>).single() as Map<*, *>
    val eventText = approvalEvent["text"] as String

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals(run.runId, approvalEvent["runId"])
    assertEquals(task.id, approvalEvent["taskId"])
    assertEquals("approval_wait", approvalEvent["kind"])
    assertEquals("required", approvalEvent["status"])
    assertEquals("Write", approvalEvent["toolName"])
    assertEquals(false, approvalEvent["isHighRisk"])
    assertTrue(eventText.contains("Approval required"))
    assertTrue(eventText.contains("Update the notes before answering."))
    assertTrue(eventText.contains("Approval is required before Write can run."))
  }

  @Test
  fun submitChatMessageWhileApprovalPendingQueuesDeferredFollowUp() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-supplement"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need approval")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Write",
        ),
      ),
    )

    val secondRun = hostRuntime.submitChatMessage("Use a safer approach")
    val queuedInputs = chatStore.loadPendingUserInputs(activeSessionId)
    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(null, secondRun)
    assertEquals(listOf("Use a safer approach"), queuedInputs.map { it.text })
    assertEquals(listOf("Need approval"), handle.submittedInputs)
    assertEquals(
      listOf(
        "Need approval",
        "Approval is required before Write can run.",
        "Use a safer approach",
      ),
      renderedMessages,
    )
  }

  @Test
  fun chatSnapshotSummaryShowsApprovalFollowUpRecordedWhileApprovalIsPending() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-summary-approval-follow-up"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need approval")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Write can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Write",
        ),
      ),
    )
    assertEquals(null, hostRuntime.submitChatMessage("Use a safer approach"))

    val summary = hostRuntime.loadChatSnapshot()["summary"] as Map<*, *>

    assertEquals(
      "Recorded. The current run is waiting for approval, so this message will be handled after that decision.",
      summary["body"],
    )
  }

  @Test
  fun loadChatSnapshotRepairsStaleSupplementsWithoutTouchingLiveRunEntries() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-stale-supplement-repair"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val supplementStore = InMemorySessionSupplementStore()
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      override fun forChatSession(sessionId: String): SessionSupplementStore = supplementStore
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      supplementStoreFactory = supplementStoreFactory,
    )

    hostRuntime.submitChatMessage("Inspect the repo")
    val liveTask = handle.submittedTasks.single()
    val liveRun = handle.submissions.single()
    supplementStore.append(
      runId = "stale-run",
      taskId = "stale-task",
      text = "Stale queued follow-up",
    )
    supplementStore.append(
      runId = liveRun.runId,
      taskId = liveTask.id,
      text = "Live supplement",
    )

    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(
      listOf("Stale queued follow-up"),
      chatStore.loadPendingUserInputs(activeSessionId).map { it.text },
    )
    assertEquals(listOf("Live supplement"), supplementStore.snapshot().map { it.text })
    assertEquals(
      listOf(
        "Inspect the repo",
        "Thinking",
        "Stale queued follow-up",
        "Live supplement",
      ),
      renderedMessages,
    )
  }

  @Test
  fun loadChatSnapshotKeepsSupplementsForTerminalLiveProcessRun() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-live-process-stale-supplement-repair"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val supplementStore = InMemorySessionSupplementStore()
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      override fun forChatSession(sessionId: String): SessionSupplementStore = supplementStore
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      supplementStoreFactory = supplementStoreFactory,
    )

    handle.putRunSnapshot(
      AgentRunSnapshot(
        sessionId = activeSessionId,
        runId = "run-live-terminal",
        taskId = "task-live-terminal",
        acceptedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
        lifecycleState = QueueTaskLifecycleState.COMPLETED,
        taskState = AgentTaskState.COMPLETED,
        executionStatus = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
      ),
    )
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-live-terminal",
        taskId = "task-live-terminal",
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 120_000L,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
      ),
    )
    supplementStore.append(
      runId = "stale-run",
      taskId = "stale-task",
      text = "Stale queued follow-up",
    )
    supplementStore.append(
      runId = "run-live-terminal",
      taskId = "task-live-terminal",
      text = "Keep following the logs",
    )

    val renderedMessages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> (message as Map<*, *>)["text"] }

    assertEquals(
      listOf("Stale queued follow-up"),
      chatStore.loadPendingUserInputs(activeSessionId).map { it.text },
    )
    assertEquals(listOf("Keep following the logs"), supplementStore.snapshot().map { it.text })
    assertEquals(
      listOf(
        "Stale queued follow-up",
        "Keep following the logs",
      ),
      renderedMessages,
    )
  }

  @Test
  fun chatSnapshotIncludesRuntimeActivityEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-activity"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Need runtime events")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayLifecycleEvent(
        runId = run.runId,
        taskId = task.id,
        phase = OpenCrayRunLifecyclePhase.START,
        emittedAtEpochMs = 1_100L,
      ),
    )

    val snapshot = hostRuntime.loadChatSnapshot()
    val runtimeActivity = snapshot["runtimeActivity"] as Map<*, *>
    val events = runtimeActivity["events"] as List<*>
    val firstEvent = events.single() as Map<*, *>

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals("lifecycle", firstEvent["kind"])
    assertEquals("start", firstEvent["phase"])
    assertEquals(run.runId, firstEvent["runId"])
  }

  @Test
  fun chatSnapshotIncludesStructuredToolResultMetadata() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-result-metadata"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Read README with detail")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(
          toolName = "Read",
        ),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "alpha\nbeta",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "5",
            "limit" to "2",
            "returnedLineCount" to "2",
            "totalLineCount" to "12",
            "truncated" to "false",
            "resultLimitApplied" to "true",
            "resultTruncated" to "false",
            "resultLimitKind" to "read_byte_budget",
            "checkpointId" to "hidden-checkpoint",
            OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON to """{"turnIndex":1,"toolCallCount":1}""",
          ),
        ),
        emittedAtEpochMs = 1_200L,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val firstEvent = (runtimeActivity["events"] as List<*>).single() as Map<*, *>
    val resultMetadata = firstEvent["resultMetadata"] as Map<*, *>

    assertEquals("tool_result", firstEvent["kind"])
    assertEquals("README.md", resultMetadata["filePath"])
    assertEquals("5", resultMetadata["offset"])
    assertEquals("2", resultMetadata["limit"])
    assertEquals("2", resultMetadata["returnedLineCount"])
    assertEquals("12", resultMetadata["totalLineCount"])
    assertEquals("false", resultMetadata["truncated"])
    assertEquals("true", resultMetadata["resultLimitApplied"])
    assertEquals("false", resultMetadata["resultTruncated"])
    assertEquals("read_byte_budget", resultMetadata["resultLimitKind"])
    assertFalse(resultMetadata.containsKey("checkpointId"))
    assertFalse(resultMetadata.containsKey(OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON))
  }

  @Test
  fun chatSnapshotIncludesTodoPlanResultMetadata() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-todo-result-metadata"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Track the current plan")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "TodoWrite"),
        result = AgentToolResult(
          toolName = "TodoWrite",
          status = AgentToolResultStatus.SUCCESS,
          content = """
            [completed] Inspect runtime continuation
            [in_progress] Prepare final answer | active: Preparing final answer
          """.trimIndent(),
          metadata = mapOf(
            TodoWriteMetadataKeys.TODO_COUNT to "2",
            TodoWriteMetadataKeys.MUTATED to "true",
            TodoWriteMetadataKeys.PLAN_CHANGED to "true",
            TodoWriteMetadataKeys.PENDING_TODO_COUNT to "0",
            TodoWriteMetadataKeys.IN_PROGRESS_TODO_COUNT to "1",
            TodoWriteMetadataKeys.COMPLETED_TODO_COUNT to "1",
            TodoWriteMetadataKeys.ADDED_TODO_COUNT to "1",
            TodoWriteMetadataKeys.REMOVED_TODO_COUNT to "1",
            TodoWriteMetadataKeys.STATUS_CHANGED_TODO_COUNT to "1",
            TodoWriteMetadataKeys.COMPLETED_TODO_DELTA_COUNT to "1",
            TodoWriteMetadataKeys.ACTIVE_TODO_CHANGED to "true",
            TodoWriteMetadataKeys.ACTIVE_TODO_CONTENT to "Prepare final answer",
            "checkpointId" to "hidden-checkpoint",
          ),
        ),
        emittedAtEpochMs = 1_210L,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val firstEvent = (runtimeActivity["events"] as List<*>).single() as Map<*, *>
    val resultMetadata = firstEvent["resultMetadata"] as Map<*, *>

    assertEquals("tool_result", firstEvent["kind"])
    assertEquals("2", resultMetadata[TodoWriteMetadataKeys.TODO_COUNT])
    assertEquals("true", resultMetadata[TodoWriteMetadataKeys.MUTATED])
    assertEquals("true", resultMetadata[TodoWriteMetadataKeys.PLAN_CHANGED])
    assertEquals("0", resultMetadata[TodoWriteMetadataKeys.PENDING_TODO_COUNT])
    assertEquals("1", resultMetadata[TodoWriteMetadataKeys.IN_PROGRESS_TODO_COUNT])
    assertEquals("1", resultMetadata[TodoWriteMetadataKeys.COMPLETED_TODO_COUNT])
    assertEquals("1", resultMetadata[TodoWriteMetadataKeys.ADDED_TODO_COUNT])
    assertEquals("1", resultMetadata[TodoWriteMetadataKeys.REMOVED_TODO_COUNT])
    assertEquals("1", resultMetadata[TodoWriteMetadataKeys.STATUS_CHANGED_TODO_COUNT])
    assertEquals("1", resultMetadata[TodoWriteMetadataKeys.COMPLETED_TODO_DELTA_COUNT])
    assertEquals("true", resultMetadata[TodoWriteMetadataKeys.ACTIVE_TODO_CHANGED])
    assertEquals("Prepare final answer", resultMetadata[TodoWriteMetadataKeys.ACTIVE_TODO_CONTENT])
    assertFalse(resultMetadata.containsKey("checkpointId"))
  }

  @Test
  fun chatSnapshotIncludesPublicProgressEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-event"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Keep me updated while you inspect the workspace")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Scanning README and Gradle files before choosing the next tool.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val firstEvent = (runtimeActivity["events"] as List<*>).single() as Map<*, *>

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals("assistant_phase", firstEvent["kind"])
    assertEquals("commentary", firstEvent["phase"])
    assertEquals("Planning", firstEvent["stage"])
    assertEquals(
      "Scanning README and Gradle files before choosing the next tool.",
      firstEvent["text"],
    )
    assertEquals(run.runId, firstEvent["runId"])
  }

  @Test
  fun chatSnapshotKeepsRetryEventsInRuntimeActivityButHidesTheirChatBubbles() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-hidden-retry-progress"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Try again if the provider disconnects")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Provider timed out. Retrying after 15000ms.",
        isFinal = false,
        stage = "llm_retry",
        emittedAtEpochMs = 1_160L,
      ),
    )

    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val runtimeActivity = chatSnapshot["runtimeActivity"] as Map<*, *>
    val runtimeEvent = (runtimeActivity["events"] as List<*>).single() as Map<*, *>
    val messages = (chatSnapshot["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals("assistant_phase", runtimeEvent["kind"])
    assertEquals("llm_retry", runtimeEvent["stage"])
    assertEquals("Provider timed out. Retrying after 15000ms.", runtimeEvent["text"])
    assertEquals(
      listOf("Try again if the provider disconnects", "Thinking"),
      messages.map { message -> message["text"] },
    )
  }

  @Test
  fun chatSnapshotProjectsProgressMessagesBeforeCompletedAssistantReply() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-messages-live"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Keep me updated while you inspect the workspace")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Scanning README and Gradle files before choosing the next tool.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "README and Gradle files look consistent.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_200L,
        metadata = task.metadata,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Keep me updated while you inspect the workspace",
        "Planning\n\nScanning README and Gradle files before choosing the next tool.",
        "README and Gradle files look consistent.",
      ),
      messages.map { message -> message["text"] },
    )
    assertEquals(false, messages[1]["isEphemeral"])
    assertEquals(1_150L, messages[1]["createdAtEpochMs"])
  }

  @Test
  fun chatSnapshotDeduplicatesRepeatedLiveProgressEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-dedupe-live"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Keep me updated while you inspect the workspace")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Scanning README and Gradle files before choosing the next tool.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "  Scanning README and  Gradle files before choosing the next tool.\n",
        isFinal = false,
        stage = " Planning ",
        emittedAtEpochMs = 1_151L,
      ),
    )

    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val runtimeActivity = chatSnapshot["runtimeActivity"] as Map<*, *>
    val events = (runtimeActivity["events"] as List<*>)
      .map { event -> event as Map<*, *> }
    val messages = (chatSnapshot["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(1, events.count { event -> event["kind"] == "assistant_phase" })
    assertEquals(
      "Scanning README and Gradle files before choosing the next tool.",
      events.single()["text"],
    )
    assertEquals(
      1,
      messages.count { message ->
        message["text"] == "Planning\n\nScanning README and Gradle files before choosing the next tool."
      },
    )
  }

  @Test
  fun chatSnapshotIgnoresPreviousExecutionProgressMessagesForChatBubbles() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-execution-scope"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Resume after approval and keep me posted")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    handle.updateRunSnapshot(run.runId) { snapshot ->
      snapshot.copy(
        executionId = "exec-2",
        executionOrdinal = 2,
        executionKind = "approval_resume",
      )
    }
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        executionId = "exec-1",
        executionOrdinal = 1,
        executionKind = "initial",
        turn = 0,
        text = "Checking the workspace before the next step.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        executionId = "exec-2",
        executionOrdinal = 2,
        executionKind = "approval_resume",
        turn = 0,
        text = "Checking the workspace before the next step.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_250L,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Resume after approval and keep me posted",
        "Planning\n\nChecking the workspace before the next step.",
        "Thinking",
      ),
      messages.map { message -> message["text"] },
    )
    assertEquals(
      1,
      messages.count { message ->
        message["text"] == "Planning\n\nChecking the workspace before the next step."
      },
    )
  }

  @Test
  fun chatSnapshotProjectsPendingExecutionProgressMessagesBeforeExecutionIdIsAssigned() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-pending-progress-execution"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Start the dev server and keep me posted")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    handle.updateRunSnapshot(run.runId) { snapshot ->
      snapshot.copy(
        pendingExecutionKind = "initial",
      )
    }
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Checking the workspace before the next step.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Start the dev server and keep me posted",
        "Planning\n\nChecking the workspace before the next step.",
        "Thinking",
      ),
      messages.map { message -> message["text"] },
    )
    assertEquals(
      1,
      messages.count { message ->
        message["text"] == "Planning\n\nChecking the workspace before the next step."
      },
    )
  }

  @Test
  fun chatSnapshotPersistsMultipleProgressMessagesBeforePendingAssistantReply() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-messages-persisted"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Keep me updated while you inspect the workspace")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Scanning README and Gradle files before choosing the next tool.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 1,
        text = "Checking the tests after the first pass.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_175L,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Keep me updated while you inspect the workspace",
        "Planning\n\nScanning README and Gradle files before choosing the next tool.",
        "Planning\n\nChecking the tests after the first pass.",
        "Thinking",
      ),
      messages.map { message -> message["text"] },
    )
    assertTrue(
      messages
        .drop(1)
        .dropLast(1)
        .all { message -> message["isEphemeral"] == false },
    )
  }

  @Test
  fun chatSnapshotProjectsManagedProcessMessagesBeforePendingAssistantReply() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-managed-process-bubble"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Start the dev server and keep me posted")
    val task = handle.submittedTasks.single()
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-live",
        taskId = task.id,
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 120_000L,
        stdout = "ready on http://localhost:3000",
        startedAtEpochMs = 1_100L,
        updatedAtEpochMs = 1_200L,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Start the dev server and keep me posted",
        "Process proc-live\n\nrunning: npm run dev\n\nready on http://localhost:3000",
        "Thinking",
      ),
      messages.map { message -> message["text"] },
    )
    assertEquals("inbound", messages[1]["kind"])
    assertEquals(true, messages[1]["isEphemeral"])
  }

  @Test
  fun chatSnapshotInterleavesManagedProcessMessagesWithRuntimeProgressByTimestamp() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-managed-process-global-order"),
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Start the dev server and keep me posted")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Checking the workspace before the next step.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-live",
        taskId = task.id,
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 120_000L,
        stdout = "ready on http://localhost:3000",
        startedAtEpochMs = 1_160L,
        updatedAtEpochMs = 1_200L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 1,
        text = "Waiting for the server to finish booting.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_175L,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Start the dev server and keep me posted",
        "Planning\n\nChecking the workspace before the next step.",
        "Process proc-live\n\nrunning: npm run dev\n\nready on http://localhost:3000",
        "Planning\n\nWaiting for the server to finish booting.",
        "Thinking",
      ),
      messages.map { message -> message["text"] },
    )
  }

  @Test
  fun chatSnapshotIncludesPersistedMessageTimestamps() {
    var nowEpochMs = 1_000L
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-message-timestamps"),
      nowEpochMs = { nowEpochMs },
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = NoOpRuntimeManager())

    chatStore.appendUserMessage(
      sessionId = activeSessionId,
      text = "First persisted message",
      commandLabel = null,
      attachments = emptyList(),
    )
    nowEpochMs = 2_000L
    chatStore.appendMessage(
      sessionId = activeSessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Second persisted message",
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf("First persisted message", "Second persisted message"),
      messages.map { message -> message["text"] },
    )
    assertEquals(
      listOf(1_000L, 2_000L),
      messages.map { message -> message["createdAtEpochMs"] },
    )
  }

  @Test
  fun chatSnapshotDrawerIncludesLastMessageTimestamp() {
    var nowEpochMs = 1_000L
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-drawer-message-timestamps"),
      nowEpochMs = { nowEpochMs },
    )
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = NoOpRuntimeManager())

    chatStore.appendUserMessage(
      sessionId = activeSessionId,
      text = "First persisted message",
      commandLabel = null,
      attachments = emptyList(),
    )
    nowEpochMs = 2_000L
    chatStore.appendMessage(
      sessionId = activeSessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Second persisted message",
    )

    val drawer = hostRuntime.loadChatSnapshot()["drawer"] as Map<*, *>
    val session = ((drawer["sessions"] as List<*>).single() as Map<*, *>)

    assertEquals("2 messages", session["meta"])
    assertEquals(2_000L, session["lastMessageAtEpochMs"])
  }

  @Test
  fun recallChatMessageRemovesProjectedProgressMessagesForRecalledTurn() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-recall"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Keep me updated while you inspect the workspace")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        text = "Scanning README and Gradle files before choosing the next tool.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_150L,
      ),
    )
    val userMessageId = checkNotNull(chatStore.loadSession(activeSessionId)).messages
      .last { message -> message.role == ChatTranscriptRole.USER }
      .messageId

    hostRuntime.recallChatMessage(activeSessionId, userMessageId)

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertTrue(messages.isEmpty())
  }

  @Test
  fun chatSnapshotProjectsReplayedProgressMessagesBeforeCompletedAssistantReply() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-messages-replay"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    var transcriptMessages: List<RuntimeConversationMessage> = emptyList()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    hostRuntime.submitChatMessage("Keep me updated while you inspect the workspace")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "README and Gradle files look consistent.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_200L,
        metadata = task.metadata,
      ),
    )
    transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"assistant_phase","phase":"commentary","run_id":"${run.runId}","task_id":"${task.id}","turn":0,"text":"Scanning README and Gradle files before choosing the next tool.","stage":"Planning"}""",
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Keep me updated while you inspect the workspace",
        "Planning\n\nScanning README and Gradle files before choosing the next tool.",
        "README and Gradle files look consistent.",
      ),
      messages.map { message -> message["text"] },
    )
    assertEquals(true, messages[1]["isEphemeral"])
  }

  @Test
  fun chatSnapshotKeepsToolMessagesOutOfChatHistory() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-messages-live"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Inspect README before editing")
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
          arguments = jsonObject("""{"file_path":"README.md","offset":5,"limit":2}"""),
          reason = "Inspect README before editing.",
        ),
        emittedAtEpochMs = 1_050L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(
          toolName = "Read",
          arguments = jsonObject("""{"file_path":"README.md","offset":5,"limit":2}"""),
        ),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "README preview",
          metadata = mapOf(
            "filePath" to "README.md",
            "offset" to "5",
            "limit" to "2",
            "returnedLineCount" to "2",
            "totalLineCount" to "12",
            "truncated" to "false",
          ),
        ),
        emittedAtEpochMs = 1_100L,
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "README is ready for the next step.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_200L,
        metadata = task.metadata,
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Inspect README before editing",
        "README is ready for the next step.",
      ),
      messages.map { message -> message["text"] },
    )
  }

  @Test
  fun chatSnapshotKeepsReplayedToolMessagesOutOfChatHistory() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-messages-replay"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    var transcriptMessages: List<RuntimeConversationMessage> = emptyList()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    hostRuntime.submitChatMessage("Inspect README before editing")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "README is ready for the next step.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_200L,
        metadata = task.metadata,
      ),
    )
    transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"run_id":"${run.runId}","task_id":"${task.id}","turn":0,"tool_name":"Read","reason":"Inspect README before editing.","arguments":{"file_path":"README.md","offset":5,"limit":2}}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"run_id":"${run.runId}","task_id":"${task.id}","turn":0,"tool_name":"Read","status":"success","content":"README preview","metadata":{"filePath":"README.md","offset":"5","limit":"2","returnedLineCount":"2","totalLineCount":"12","truncated":"false"}}""",
      ),
    )

    val messages = (hostRuntime.loadChatSnapshot()["messages"] as List<*>)
      .map { message -> message as Map<*, *> }

    assertEquals(
      listOf(
        "Inspect README before editing",
        "README is ready for the next step.",
      ),
      messages.map { message -> message["text"] },
    )
  }

  @Test
  fun chatRuntimeSnapshotReplaysDurableTranscriptEventsWhenLiveHistoryIsEmpty() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-replay"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"run_id":"replay-run","task_id":"replay-task","turn":0,"tool_name":"Read","reason":"Inspect README before editing.","arguments":{"file_path":"README.md","offset":5,"limit":2}}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"run_id":"replay-run","task_id":"replay-task","turn":0,"tool_name":"Read","status":"success","content":"README preview","metadata":{"filePath":"README.md","offset":"5","limit":"2","returnedLineCount":"2","totalLineCount":"12","truncated":"false","checkpointId":"hidden"}}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"assistant_phase","phase":"commentary","run_id":"replay-run","task_id":"replay-task","turn":1,"text":"Planning the next edit after reading README.","stage":"Planning"}""",
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val events = runtimeActivity["events"] as List<*>
    val toolCall = events[0] as Map<*, *>
    val toolResult = events[1] as Map<*, *>
    val progress = events[2] as Map<*, *>
    val resultMetadata = toolResult["resultMetadata"] as Map<*, *>

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals(3, events.size)
    assertEquals("tool_call", toolCall["kind"])
    assertEquals("Read", toolCall["toolName"])
    assertEquals("Inspect README before editing.", toolCall["toolReason"])
    assertTrue((toolCall["argumentsJson"] as String).contains("README.md"))
    assertEquals("tool_result", toolResult["kind"])
    assertEquals("README preview", toolResult["contentPreview"])
    assertEquals("README.md", resultMetadata["filePath"])
    assertEquals("5", resultMetadata["offset"])
    assertEquals("2", resultMetadata["limit"])
    assertFalse(resultMetadata.containsKey("checkpointId"))
    assertEquals("assistant_phase", progress["kind"])
    assertEquals("commentary", progress["phase"])
    assertEquals("Planning", progress["stage"])
    assertEquals(
      "Planning the next edit after reading README.",
      progress["text"],
    )
  }

  @Test
  fun chatRuntimeSnapshotReplaysStructuredDurableTranscriptEventsWithoutLegacyPrefixes() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-structured-replay"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.ASSISTANT,
        content = """{"run_id":"replay-run","task_id":"replay-task","turn":0,"tool_call_id":"call-1","tool_name":"Read","reason":"Inspect README before editing.","arguments":{"file_path":"README.md","offset":5,"limit":2}}""",
        kind = RuntimeConversationMessageKind.TOOL_CALL,
        toolCall = RuntimeConversationToolCall(
          id = "call-1",
          toolName = "Read",
          arguments = buildJsonObject {
            put("file_path", "README.md")
            put("offset", 5)
            put("limit", 2)
          },
          reason = "Inspect README before editing.",
        ),
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"run_id":"replay-run","task_id":"replay-task","turn":0,"tool_call_id":"call-1","tool_name":"Read","status":"success","content":"README full content from transcript","stdout":"README stdout","metadata":{"filePath":"README.md","offset":"5","limit":"2","checkpointId":"hidden"}}""",
        kind = RuntimeConversationMessageKind.TOOL_RESULT,
        toolResult = RuntimeConversationToolResult(
          toolCallId = "call-1",
          toolName = "Read",
          status = "success",
          isError = false,
        ),
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"assistant_phase","phase":"commentary","run_id":"replay-run","task_id":"replay-task","turn":1,"text":"Planning the next edit after reading README.","stage":"Planning"}""",
        kind = RuntimeConversationMessageKind.COMMENTARY,
        commentary = RuntimeConversationCommentary(
          runId = "replay-run",
          taskId = "replay-task",
          turn = 1,
          text = "Planning the next edit after reading README.",
          stage = "Planning",
        ),
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val events = runtimeActivity["events"] as List<*>
    val toolCall = events[0] as Map<*, *>
    val toolResult = events[1] as Map<*, *>
    val progress = events[2] as Map<*, *>
    val resultMetadata = toolResult["resultMetadata"] as Map<*, *>

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals(3, events.size)
    assertEquals("tool_call", toolCall["kind"])
    assertEquals("Read", toolCall["toolName"])
    assertEquals("Inspect README before editing.", toolCall["toolReason"])
    assertTrue((toolCall["argumentsJson"] as String).contains("README.md"))
    assertEquals("tool_result", toolResult["kind"])
    assertEquals("README full content from transcript", toolResult["contentPreview"])
    assertEquals("README.md", resultMetadata["filePath"])
    assertEquals("5", resultMetadata["offset"])
    assertEquals("2", resultMetadata["limit"])
    assertFalse(resultMetadata.containsKey("checkpointId"))
    assertEquals("assistant_phase", progress["kind"])
    assertEquals("commentary", progress["phase"])
    assertEquals("Planning", progress["stage"])
    assertEquals(
      "Planning the next edit after reading README.",
      progress["text"],
    )
  }

  @Test
  fun chatRuntimeSnapshotReplaysPlainJsonDurableEventsWithoutKindsOrLegacyPrefixes() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-plain-json-replay"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"run_id":"replay-run","task_id":"replay-task","turn":0,"tool_call_id":"call-1","tool_name":"Read","reason":"Inspect README before editing.","arguments":{"file_path":"README.md","offset":5,"limit":2}}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"run_id":"replay-run","task_id":"replay-task","turn":0,"tool_call_id":"call-1","tool_name":"Read","status":"success","content":"README full content from transcript","metadata":{"filePath":"README.md","offset":"5","limit":"2"}}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"assistant_phase","phase":"commentary","run_id":"replay-run","task_id":"replay-task","turn":1,"text":"Planning the next edit after reading README.","stage":"Planning"}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"run_id":"replay-run","task_id":"replay-task","turn":1,"entry_id":"supplement-1","text":"Also inspect the logs","checkpoint":"turn_start","metadata":{"source":"manual","${OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON}":"{\"turnIndex\":1,\"toolCallCount\":1}"}}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"run_id":"replay-run","task_id":"replay-task","turn":1,"phase":"failed","child_run_id":"child-run","child_task_id":"child-task","label":"Inspect README","subagent_type":"researcher","context_mode":"minimal","depth":1,"summary":"Waiting for approval to read /external/notes.txt.","execution_state":"waiting_approval","continuation_kind":"prompt_resume","resumable":true,"requires_user_action":true,"is_high_risk":false}""",
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val events = runtimeActivity["events"] as List<*>
    val toolCall = events[0] as Map<*, *>
    val toolResult = events[1] as Map<*, *>
    val progress = events[2] as Map<*, *>
    val supplement = events[3] as Map<*, *>
    val subagent = events[4] as Map<*, *>

    assertEquals(5, events.size)
    assertEquals("tool_call", toolCall["kind"])
    assertEquals("Read", toolCall["toolName"])
    assertEquals("tool_result", toolResult["kind"])
    assertEquals("README full content from transcript", toolResult["contentPreview"])
    assertEquals("assistant_phase", progress["kind"])
    assertEquals("commentary", progress["phase"])
    assertEquals("Planning", progress["stage"])
    assertEquals("supplement", supplement["kind"])
    assertEquals("Also inspect the logs", supplement["text"])
    assertEquals(mapOf("source" to "manual"), supplement["metadata"])
    assertEquals(true, supplement["hasResumeCheckpointMetadata"])
    assertFalse((supplement["metadata"] as Map<*, *>).containsKey(OpenCrayPromptResumeMetadata.KEY_PROMPT_RESUME_JSON))
    assertEquals("subagent", subagent["kind"])
    assertEquals("failed", subagent["phase"])
    assertEquals("waiting_approval", subagent["status"])
  }

  @Test
  fun successfulRuntimeToolEventsPersistOnceAndRetainCompletedRunHistory() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-success-runtime-history"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val runEventJournalStoreFactory = hostRuntimeTestRunEventJournalStoreFactory()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
    )

    val run = hostRuntime.submitChatMessage("Read the README")!!
    val task = handle.submittedTasks.single()
    val fullToolContent = "README full content from the live runtime event."
    val toolResultEvent = OpenCrayToolResultEvent(
      runId = run["runId"] as String,
      taskId = task.id,
      turn = 0,
      call = AgentToolCall(
        toolName = "Read",
        reason = "Inspect README",
      ),
      result = AgentToolResult(
        toolName = "Read",
        status = AgentToolResultStatus.SUCCESS,
        content = fullToolContent,
        metadata = mapOf("filePath" to "README.md"),
      ),
      emittedAtEpochMs = 1_000L,
    )
    val journalStore = runEventJournalStoreFactory.forChatSession(activeSessionId)
    journalStore.append(toolResultEvent)
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = toolResultEvent,
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "Finished reading README.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    val journalEvents = journalStore
      .listRuntimeEvents()
      .filterIsInstance<OpenCrayToolResultEvent>()
    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val events = (runtimeActivity["events"] as List<*>).map { event -> event as Map<*, *> }
    val activeRuns = runtimeActivity["activeRuns"] as List<*>
    val retainedRuns = (runtimeActivity["retainedRuns"] as List<*>).map { runMap ->
      runMap as Map<*, *>
    }
    val toolResult = events.single { event -> event["kind"] == "tool_result" }

    assertEquals(1, journalEvents.size)
    assertEquals(fullToolContent, journalEvents.single().result.content)
    assertTrue(activeRuns.isEmpty())
    assertEquals(1, retainedRuns.size)
    assertEquals(run["runId"], retainedRuns.single()["runId"])
    assertEquals("tool_result", toolResult["kind"])
    assertEquals(fullToolContent, toolResult["content"])
    assertEquals(fullToolContent, toolResult["contentPreview"])
    assertEquals("README.md", (toolResult["resultMetadata"] as Map<*, *>)["filePath"])
  }

  @Test
  fun chatRuntimeSnapshotReplaysDurableSubagentEventsWhenLiveHistoryIsEmpty() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-runtime-replay"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"subagent","run_id":"replay-run","task_id":"replay-task","turn":0,"phase":"started","child_run_id":"child-run","child_task_id":"child-task","label":"Inspect README","subagent_type":"researcher","context_mode":"minimal","depth":1,"execution_state":"running","continuation_kind":"none","resumable":false,"requires_user_action":false,"is_high_risk":false}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"subagent","run_id":"replay-run","task_id":"replay-task","turn":0,"phase":"failed","child_run_id":"child-run","child_task_id":"child-task","label":"Inspect README","subagent_type":"researcher","context_mode":"minimal","depth":1,"summary":"Waiting for approval to read /external/notes.txt.","execution_state":"waiting_approval","continuation_kind":"prompt_resume","resumable":true,"requires_user_action":true,"is_high_risk":false}""",
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val events = runtimeActivity["events"] as List<*>
    val started = events[0] as Map<*, *>
    val waiting = events[1] as Map<*, *>

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals(2, events.size)
    assertEquals("subagent", started["kind"])
    assertEquals("started", started["phase"])
    assertEquals("running", started["status"])
    assertEquals("child-run", started["childRunId"])
    assertEquals("researcher", started["subagentType"])
    assertEquals("minimal", started["contextMode"])
    assertEquals(1, started["depth"])
    assertEquals(false, started["resumable"])
    assertEquals("subagent", waiting["kind"])
    assertEquals("failed", waiting["phase"])
    assertEquals("waiting_approval", waiting["status"])
    assertEquals("prompt_resume", waiting["continuationKind"])
    assertEquals(true, waiting["resumable"])
    assertEquals(true, waiting["requiresUserAction"])
    assertEquals(false, waiting["isHighRisk"])
    assertEquals(
      "Waiting for approval to read /external/notes.txt.",
      waiting["text"],
    )
  }

  @Test
  fun chatRuntimeSnapshotBuildsLatestSubAgentRegistryFromReplayEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-runtime-registry"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"subagent","run_id":"replay-run","task_id":"replay-task","turn":0,"phase":"started","child_run_id":"child-run-1","child_task_id":"child-task-1","label":"Inspect README","subagent_type":"researcher","context_mode":"minimal","depth":1,"execution_state":"running","continuation_kind":"none","resumable":false,"requires_user_action":false,"is_high_risk":false}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"subagent","run_id":"replay-run","task_id":"replay-task","turn":0,"phase":"failed","child_run_id":"child-run-1","child_task_id":"child-task-1","label":"Inspect README","subagent_type":"researcher","context_mode":"minimal","depth":1,"summary":"Waiting for approval to read /external/notes.txt.","execution_state":"waiting_approval","continuation_kind":"prompt_resume","resumable":true,"requires_user_action":true,"is_high_risk":false}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"subagent","run_id":"replay-run","task_id":"replay-task","turn":1,"phase":"started","child_run_id":"child-run-2","child_task_id":"child-task-2","label":"Patch tests","subagent_type":"worker","context_mode":"delegated","depth":1,"execution_state":"running","continuation_kind":"none","resumable":false,"requires_user_action":false,"is_high_risk":false}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"subagent","run_id":"replay-run","task_id":"replay-task","turn":1,"phase":"completed","child_run_id":"child-run-2","child_task_id":"child-task-2","label":"Patch tests","subagent_type":"worker","context_mode":"delegated","depth":1,"summary":"Updated runtime tests.","execution_state":"completed","continuation_kind":"none","resumable":false,"requires_user_action":false,"is_high_risk":false}""",
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val subAgents = (runtimeActivity["subAgents"] as List<*>).map { entry ->
      entry as Map<*, *>
    }
    val waitingChild = subAgents.single { entry -> entry["childRunId"] == "child-run-1" }
    val completedChild = subAgents.single { entry -> entry["childRunId"] == "child-run-2" }

    assertEquals(2, subAgents.size)
    assertEquals("replay-run", waitingChild["parentRunId"])
    assertEquals("replay-task", waitingChild["parentTaskId"])
    assertEquals("failed", waitingChild["phase"])
    assertEquals("waiting_approval", waitingChild["status"])
    assertEquals("waiting_approval", waitingChild["executionState"])
    assertEquals("prompt_resume", waitingChild["continuationKind"])
    assertEquals(true, waitingChild["resumable"])
    assertEquals(true, waitingChild["requiresUserAction"])
    assertEquals(false, waitingChild["isHighRisk"])
    assertEquals("Waiting for approval to read /external/notes.txt.", waitingChild["summary"])
    assertEquals(2, waitingChild["eventCount"])
    assertEquals("worker", completedChild["subagentType"])
    assertEquals("delegated", completedChild["contextMode"])
    assertEquals("completed", completedChild["phase"])
    assertEquals("completed", completedChild["status"])
    assertEquals("Updated runtime tests.", completedChild["summary"])
    assertEquals(2, completedChild["eventCount"])
  }

  @Test
  fun chatRuntimeSnapshotBuildsSubAgentRegistryFromPromptCheckpointHandles() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-runtime-checkpoint"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    promptCheckpointStoreFactory
      .forChatSession(activeSessionId)
      .upsert(
        PersistedPromptCheckpoint(
          sessionId = activeSessionId,
          runId = "run-parent",
          taskId = "task-parent",
          checkpointId = "checkpoint-subagent-runtime",
          checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_200L,
          promptResumeState = OpenCrayPromptResumeState(
            turnIndex = 2,
            toolCallCount = 3,
            subAgentHandles = listOf(
              SubAgentHandleState(
                agentId = "child-1",
                childRunId = "child-run-checkpoint",
                childTaskId = "child-task-checkpoint",
                description = "Inspect external notes",
                prompt = "Read the external notes file and summarize it.",
                subagentType = "researcher",
                contextMode = "minimal",
                parentRunId = "run-parent",
                parentTaskId = "task-parent",
                parentTurn = 1,
                depth = 1,
                snapshot = SubAgentExecutionSnapshot(
                  state = SubAgentExecutionState.WAITING_APPROVAL,
                  continuationKind = SubAgentContinuationKind.PROMPT_RESUME,
                  resumable = true,
                  requiresUserAction = true,
                  isHighRisk = false,
                  headline = "Waiting for approval to read /external/notes.txt.",
                ),
                createdAtEpochMs = 900L,
                updatedAtEpochMs = 1_150L,
              ),
            ),
          ),
        ),
      )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val subAgents = (runtimeActivity["subAgents"] as List<*>).map { entry ->
      entry as Map<*, *>
    }
    val child = subAgents.single()

    assertEquals("run-parent", child["parentRunId"])
    assertEquals("task-parent", child["parentTaskId"])
    assertEquals("child-run-checkpoint", child["childRunId"])
    assertEquals("child-task-checkpoint", child["childTaskId"])
    assertEquals("Inspect external notes", child["label"])
    assertEquals("researcher", child["subagentType"])
    assertEquals("minimal", child["contextMode"])
    assertEquals("failed", child["phase"])
    assertEquals("waiting_approval", child["status"])
    assertEquals("waiting_approval", child["executionState"])
    assertEquals("prompt_resume", child["continuationKind"])
    assertEquals(true, child["resumable"])
    assertEquals(true, child["requiresUserAction"])
    assertEquals(false, child["isHighRisk"])
    assertEquals("Waiting for approval to read /external/notes.txt.", child["summary"])
    assertEquals(0, child["eventCount"])
    assertEquals(900L, child["startedAtEpochMs"])
    assertEquals(1_150L, child["updatedAtEpochMs"])
  }

  @Test
  fun chatRuntimeSnapshotBuildsSubAgentRegistryFromDurableHandleSource() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-runtime-durable"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = activeSessionId).apply {
      subAgentHandles += SubAgentHandleState(
        agentId = "child-durable",
        childRunId = "child-run-durable",
        childTaskId = "child-task-durable",
        description = "Inspect runtime snapshot",
        prompt = "Inspect the runtime snapshot pipeline and summarize it.",
        subagentType = "researcher",
        contextMode = "minimal",
        parentRunId = "run-parent-durable",
        parentTaskId = "task-parent-durable",
        parentTurn = 1,
        depth = 1,
        mailbox = SubAgentMailbox(
          messages = listOf(
            SubAgentMailboxMessage(
              messageId = "mailbox-durable-1",
              text = "Initial parent follow-up",
              createdAtEpochMs = 1_000L,
            ),
            SubAgentMailboxMessage(
              messageId = "mailbox-durable-2",
              text = "Second parent follow-up",
              createdAtEpochMs = 1_200L,
            ),
          ),
          lastDeliveredMessageId = "mailbox-durable-1",
        ),
        snapshot = SubAgentExecutionSnapshot.backgroundRunning(
          headline = "Delegated child runtime is still running in the background.",
        ),
        createdAtEpochMs = 900L,
        updatedAtEpochMs = 1_300L,
      )
    }
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val subAgents = (runtimeActivity["subAgents"] as List<*>).map { entry ->
      entry as Map<*, *>
    }
    val child = subAgents.single()

    assertEquals("run-parent-durable", child["parentRunId"])
    assertEquals("task-parent-durable", child["parentTaskId"])
    assertEquals("child-run-durable", child["childRunId"])
    assertEquals("child-task-durable", child["childTaskId"])
    assertEquals("Inspect runtime snapshot", child["label"])
    assertEquals("researcher", child["subagentType"])
    assertEquals("minimal", child["contextMode"])
    assertEquals("resumed", child["phase"])
    assertEquals("background_running", child["status"])
    assertEquals("background_running", child["executionState"])
    assertEquals("background_resume", child["continuationKind"])
    assertEquals(true, child["resumable"])
    assertEquals(false, child["requiresUserAction"])
    assertEquals(false, child["isHighRisk"])
    assertEquals("Delegated child runtime is still running in the background.", child["summary"])
    assertEquals(0, child["eventCount"])
    assertEquals(900L, child["startedAtEpochMs"])
    assertEquals(1_300L, child["updatedAtEpochMs"])
    assertEquals(2, child["mailboxMessageCount"])
    assertEquals(1, child["mailboxPendingMessageCount"])
    assertEquals("mailbox-durable-1", child["mailboxLastDeliveredMessageId"])
  }

  @Test
  fun chatSnapshotsHideInternalDetachedSubAgentRecoveryRunsWhileKeepingDurableSubAgentState() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-runtime-hidden-recovery"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val pendingMessageId = chatStore.appendMessage(
      sessionId = activeSessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Thinking",
    ).messageId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = activeSessionId).apply {
      putRunSnapshot(
        AgentRunSnapshot(
          sessionId = activeSessionId,
          runId = "run-parent-visible",
          taskId = "task-parent-visible",
          acceptedAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          lifecycleState = QueueTaskLifecycleState.COMPLETED,
          taskState = AgentTaskState.COMPLETED,
          attempt = 1,
        ),
      )
      putRunSnapshot(
        AgentRunSnapshot(
          sessionId = activeSessionId,
          runId = "run-hidden-recovery",
          taskId = "task-hidden-recovery",
          acceptedAtEpochMs = 1_200L,
          updatedAtEpochMs = 1_250L,
          lifecycleState = QueueTaskLifecycleState.RUNNING,
          taskState = AgentTaskState.RUNNING,
          attempt = 1,
          pendingMessageId = pendingMessageId,
          lifecycleDiagnostics = RunLifecycleDiagnostics(
            submissionSource = RunSubmissionSources.RUNTIME_SERVICE_SUBAGENT_RECOVERY,
          ),
        ),
      )
      subAgentHandles += SubAgentHandleState(
        agentId = "child-visible",
        childRunId = "child-run-visible",
        childTaskId = "child-task-visible",
        description = "Resume detached child",
        prompt = "Wait for the detached child to finish and return the result.",
        subagentType = "worker",
        contextMode = "delegated",
        parentRunId = "run-parent-visible",
        parentTaskId = "task-parent-visible",
        parentTurn = 1,
        depth = 1,
        snapshot = SubAgentExecutionSnapshot.backgroundRunning(
          headline = "Detached child resumed after cold restart.",
        ),
        createdAtEpochMs = 1_050L,
        updatedAtEpochMs = 1_260L,
      )
    }
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
    )
    val hiddenTask = AgentTask(
      id = "task-hidden-recovery",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"wait_agent","arguments":{"targets":["child-run-visible"]}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      metadata = mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to "run-hidden-recovery",
      ),
      createdAtEpochMs = 1_200L,
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = hiddenTask,
      event = OpenCrayAssistantEvent(
        runId = "run-hidden-recovery",
        taskId = "task-hidden-recovery",
        turn = 0,
        text = "Recovering detached child in background",
        stage = "commentary",
        emittedAtEpochMs = 1_300L,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val activeRuns = runtimeActivity["activeRuns"] as List<*>
    val events = (runtimeActivity["events"] as List<*>).map { event -> event as Map<*, *> }
    val subAgents = (runtimeActivity["subAgents"] as List<*>).map { event -> event as Map<*, *> }
    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val messages = (chatSnapshot["messages"] as List<*>).map { entry -> entry as Map<*, *> }
    val summary = chatSnapshot["summary"] as Map<*, *>
    val child = subAgents.single()

    assertTrue(activeRuns.isEmpty())
    assertTrue(events.isEmpty())
    assertEquals(1, subAgents.size)
    assertEquals("run-parent-visible", child["parentRunId"])
    assertEquals("child-run-visible", child["childRunId"])
    assertEquals("resumed", child["phase"])
    assertEquals("background_running", child["status"])
    assertEquals("Detached child resumed after cold restart.", child["summary"])
    assertTrue(messages.none { entry ->
      (entry["text"] as? String)?.contains("Recovering detached child in background") == true
    })
    assertEquals(
      "Local transcript is restored into the runtime window for each task.",
      summary["body"],
    )
    assertNull(hostRuntime.loadChatRunSnapshot("run-hidden-recovery"))
    assertEquals(
      "run-parent-visible",
      hostRuntime.loadChatRunSnapshot("run-parent-visible")?.get("runId"),
    )
  }

  @Test
  fun chatRuntimeSnapshotExposesAndClearsLiveAssistantDrafts() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-live-assistant-draft"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = activeSessionId)
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
    )

    hostRuntime.submitChatMessage("Stream a long answer")
    val task = handle.submittedTasks.single()

    manager.emitAssistantDraftUpdated(
      sessionId = activeSessionId,
      task = task,
      text = "Growing answer",
      emittedAtEpochMs = 1_500L,
    )

    val runtimeWithDraft = hostRuntime.loadChatRuntimeSnapshot()
    val liveDrafts = (runtimeWithDraft["liveAssistantDrafts"] as List<*>)
      .map { entry -> entry as Map<*, *> }
    assertEquals(1, liveDrafts.size)
    assertEquals(
      task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
      liveDrafts.single()["pendingMessageId"],
    )
    assertEquals("Growing answer", liveDrafts.single()["text"])

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Final streamed answer.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_600L,
        metadata = task.metadata,
      ),
    )

    val runtimeAfterFinish = hostRuntime.loadChatRuntimeSnapshot()
    assertTrue((runtimeAfterFinish["liveAssistantDrafts"] as List<*>).isEmpty())
  }

  @Test
  fun liveAssistantDraftEventsEmitIncrementalUpdateAndClearPayloads() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-live-assistant-draft-events"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = activeSessionId)
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
    )
    val observedEvents = mutableListOf<Map<String, Any?>>()
    val disposer = hostRuntime.observeLiveAssistantDraftEvents { payload ->
      observedEvents += payload
    }

    try {
      hostRuntime.submitChatMessage("Stream a long answer")
      val task = handle.submittedTasks.single()

      manager.emitAssistantDraftUpdated(
        sessionId = activeSessionId,
        task = task,
        text = "Growing answer",
        emittedAtEpochMs = 1_500L,
      )
      manager.emitAssistantDraftCleared(
        sessionId = activeSessionId,
        task = task,
        emittedAtEpochMs = 1_550L,
      )

      assertEquals(2, observedEvents.size)
      assertEquals(activeSessionId, observedEvents[0]["sessionId"])
      assertEquals("Growing answer", observedEvents[0]["text"])
      assertEquals(false, observedEvents[0]["cleared"])
      assertEquals(
        task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
        observedEvents[0]["pendingMessageId"],
      )
      assertEquals(true, observedEvents[1]["cleared"])
      assertEquals("", observedEvents[1]["text"])
    } finally {
      disposer()
    }
  }

  @Test
  fun recreatedHostsRestorePersistedLiveAssistantDraftsIntoRuntimeSnapshotAndInspectorHistory() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-live-draft-recreation")
    val firstFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val secondFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-live-draft-recreation"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val firstHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runEventJournalStoreFactory = firstFactory,
    )

    val submission = firstHost.submitChatMessage("Stream a long answer")!!
    val task = handle.submittedTasks.single()
    manager.emitAssistantDraftUpdated(
      sessionId = activeSessionId,
      task = task,
      text = "Growing streamed answer",
      emittedAtEpochMs = 1_500L,
    )

    val recreatedHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runEventJournalStoreFactory = secondFactory,
    )
    val runtimeActivity = recreatedHost.loadChatRuntimeSnapshot()
    val liveDrafts = (runtimeActivity["liveAssistantDrafts"] as List<*>).map { draft ->
      draft as Map<*, *>
    }
    val events = (runtimeActivity["events"] as List<*>).map { event ->
      event as Map<*, *>
    }
    val draftEvent = events.single { event ->
      event["kind"] == "assistant_phase" &&
        event["stage"] == "Draft" &&
        event["runId"] == submission["runId"]
    }

    assertEquals(1, liveDrafts.size)
    assertEquals("Growing streamed answer", liveDrafts.single()["text"])
    assertEquals(
      task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
      liveDrafts.single()["pendingMessageId"],
    )
    assertEquals("Growing streamed answer", draftEvent["text"])
  }

  @Test
  fun recreatedHostsDoNotRestoreAssistantDraftAfterPersistedClearMarker() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-live-draft-clear-recreation")
    val firstFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val secondFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-live-draft-clear-recreation"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val firstHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runEventJournalStoreFactory = firstFactory,
    )

    val submission = firstHost.submitChatMessage("Stream a long answer")!!
    val task = handle.submittedTasks.single()
    manager.emitAssistantDraftUpdated(
      sessionId = activeSessionId,
      task = task,
      text = "Transient streamed answer",
      emittedAtEpochMs = 1_500L,
    )
    manager.emitAssistantDraftCleared(
      sessionId = activeSessionId,
      task = task,
      emittedAtEpochMs = 1_550L,
    )

    val recreatedHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runEventJournalStoreFactory = secondFactory,
    )
    val runtimeActivity = recreatedHost.loadChatRuntimeSnapshot()
    val liveDrafts = (runtimeActivity["liveAssistantDrafts"] as List<*>).map { draft ->
      draft as Map<*, *>
    }
    val events = (runtimeActivity["events"] as List<*>).map { event ->
      event as Map<*, *>
    }
    val draftEvents = events.filter { event ->
      event["kind"] == "assistant_phase" &&
        event["stage"] == "Draft" &&
        event["runId"] == submission["runId"]
    }
    val messages = (recreatedHost.loadChatSnapshot()["messages"] as List<*>).map { message ->
      message as Map<*, *>
    }

    assertTrue(liveDrafts.isEmpty())
    assertEquals(2, draftEvents.size)
    assertEquals("Transient streamed answer", draftEvents.first()["text"])
    assertEquals("", draftEvents.last()["text"])
    assertEquals("Thinking", messages.last()["text"])
  }

  @Test
  fun chatRuntimeSnapshotPrefersPromptCheckpointHandleStateOverOlderReplayEventState() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-runtime-merge"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"subagent","run_id":"run-parent","task_id":"task-parent","turn":0,"phase":"started","child_run_id":"child-run-merge","child_task_id":"child-task-merge","label":"Inspect external notes","subagent_type":"researcher","context_mode":"minimal","depth":1,"execution_state":"running","continuation_kind":"none","resumable":false,"requires_user_action":false,"is_high_risk":false}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"subagent","run_id":"run-parent","task_id":"task-parent","turn":0,"phase":"failed","child_run_id":"child-run-merge","child_task_id":"child-task-merge","label":"Inspect external notes","subagent_type":"researcher","context_mode":"minimal","depth":1,"summary":"Waiting for approval to read /external/notes.txt.","execution_state":"waiting_approval","continuation_kind":"prompt_resume","resumable":true,"requires_user_action":true,"is_high_risk":false}""",
      ),
    )
    promptCheckpointStoreFactory
      .forChatSession(activeSessionId)
      .upsert(
        PersistedPromptCheckpoint(
          sessionId = activeSessionId,
          runId = "run-parent",
          taskId = "task-parent",
          checkpointId = "checkpoint-subagent-runtime-merge",
          checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
          createdAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_300L,
          promptResumeState = OpenCrayPromptResumeState(
            turnIndex = 2,
            toolCallCount = 3,
            subAgentHandles = listOf(
              SubAgentHandleState(
                agentId = "child-merge",
                childRunId = "child-run-merge",
                childTaskId = "child-task-merge",
                description = "Inspect external notes",
                prompt = "Read the external notes file and summarize it.",
                subagentType = "researcher",
                contextMode = "minimal",
                parentRunId = "run-parent",
                parentTaskId = "task-parent",
                parentTurn = 1,
                depth = 1,
                snapshot = SubAgentExecutionSnapshot.backgroundRunning(
                  headline = "Delegated child approval granted. The child will continue.",
                ),
                createdAtEpochMs = 950L,
                updatedAtEpochMs = 1_250L,
              ),
            ),
          ),
        ),
      )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val subAgents = (runtimeActivity["subAgents"] as List<*>).map { entry ->
      entry as Map<*, *>
    }
    val child = subAgents.single()
    assertEquals("resumed", child["phase"])
    assertEquals("background_running", child["status"])
    assertEquals("background_running", child["executionState"])
    assertEquals("background_resume", child["continuationKind"])
    assertEquals(true, child["resumable"])
    assertEquals(false, child["requiresUserAction"])
    assertEquals("Delegated child approval granted. The child will continue.", child["summary"])
    assertEquals(2, child["eventCount"])
    assertEquals(1L, child["startedAtEpochMs"])
    assertEquals(1_250L, child["updatedAtEpochMs"])
  }

  @Test
  fun activeRunUsesReplayedTranscriptEventAsLastEventWhenLiveHistoryIsEmpty() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-replay-last-event"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    var transcriptMessages: List<RuntimeConversationMessage> = emptyList()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    hostRuntime.submitChatMessage("Recover my timeline after restart")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"assistant_phase","phase":"commentary","run_id":"${run.runId}","task_id":"${task.id}","turn":0,"text":"Restored progress from transcript.","stage":"Planning"}""",
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val activeRun = ((runtimeActivity["activeRuns"] as List<*>).single()) as Map<*, *>
    val lastEvent = activeRun["lastEvent"] as Map<*, *>

    assertEquals(run.runId, activeRun["runId"])
    assertEquals("assistant_phase", lastEvent["kind"])
    assertEquals("commentary", lastEvent["phase"])
    assertEquals("Planning", lastEvent["stage"])
    assertEquals("Restored progress from transcript.", lastEvent["text"])
  }

  @Test
  fun chatRuntimeSnapshotDedupesReplayEventsAgainstLiveEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-replay-dedupe"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    var transcriptMessages: List<RuntimeConversationMessage> = emptyList()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    hostRuntime.submitChatMessage("Read README and keep the timeline clean")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"run_id":"${run.runId}","task_id":"${task.id}","turn":0,"tool_name":"Read","arguments":{"file_path":"README.md"}}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"run_id":"${run.runId}","task_id":"${task.id}","turn":0,"tool_name":"Read","status":"success","content":"README preview","metadata":{"filePath":"README.md"}}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"assistant_phase","phase":"commentary","run_id":"${run.runId}","task_id":"${task.id}","turn":1,"text":"Evaluating the next step.","stage":"Planning"}""",
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
          metadata = mapOf("filePath" to "README.md"),
        ),
        emittedAtEpochMs = 1_200L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 1,
        text = "Evaluating the next step.",
        isFinal = false,
        stage = "Planning",
        emittedAtEpochMs = 1_250L,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val events = runtimeActivity["events"] as List<*>
    val kinds = events.map { event -> (event as Map<*, *>)["kind"] }

    assertEquals(listOf("tool_call", "tool_result", "assistant_phase"), kinds)
    assertEquals(1, kinds.count { kind -> kind == "tool_result" })
    assertEquals(1, kinds.count { kind -> kind == "assistant_phase" })
  }

  @Test
  fun chatRuntimeSnapshotDeduplicatesReplayAndLiveProgressEventsWithWhitespaceDifferences() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-progress-whitespace-dedupe"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    var transcriptMessages: List<RuntimeConversationMessage> = emptyList()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    hostRuntime.submitChatMessage("Read README and keep the timeline clean")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"assistant_phase","phase":"commentary","run_id":"${run.runId}","task_id":"${task.id}","turn":1,"text":"Evaluating the next step.","stage":"Planning"}""",
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 1,
        text = "  Evaluating   the next step.\n",
        isFinal = false,
        stage = " Planning ",
        emittedAtEpochMs = 1_250L,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val assistantEvents = (runtimeActivity["events"] as List<*>)
      .map { event -> event as Map<*, *> }
      .filter { event -> event["kind"] == "assistant_phase" }

    assertEquals(1, assistantEvents.size)
    assertEquals("Planning", assistantEvents.single()["stage"])
    assertEquals("Evaluating the next step.", assistantEvents.single()["text"])
  }

  @Test
  fun chatSnapshotIncludesStructuredMemoryRetrievalEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-retrieval-event"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.submitChatMessage("Recall the previous build decision")
    val task = handle.submittedTasks.single()
    val run = handle.submissions.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayMemoryRetrievalEvent(
        runId = run.runId,
        taskId = task.id,
        turn = 0,
        toolName = "memory_search",
        operation = "search",
        query = "gradle wrapper repo root",
        queryTerms = listOf("gradle", "wrapper", "repo", "root"),
        resultCount = 1,
        corpusFileCount = 1,
        recordIds = listOf("memory-user"),
        paths = listOf("memory/2024-03-11.md"),
        lineRanges = listOf("5-8"),
        emittedAtEpochMs = 1_100L,
      ),
    )

    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val firstEvent = (runtimeActivity["events"] as List<*>).single() as Map<*, *>

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals("memory_retrieval", firstEvent["kind"])
    assertEquals("memory_search", firstEvent["toolName"])
    assertEquals("search", firstEvent["operation"])
    assertEquals("gradle wrapper repo root", firstEvent["query"])
    assertEquals(listOf("gradle", "wrapper", "repo", "root"), firstEvent["queryTerms"])
    assertEquals(1, firstEvent["resultCount"])
    assertEquals(1, firstEvent["corpusFileCount"])
    assertEquals(listOf("memory-user"), firstEvent["recordIds"])
    assertEquals(listOf("memory/2024-03-11.md"), firstEvent["paths"])
    assertEquals(listOf("5-8"), firstEvent["lineRanges"])
    assertEquals(run.runId, firstEvent["runId"])
  }

  @Test
  fun submitChatMessageReturnsRunSubmissionAndRunSnapshot() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-submission"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need a run id")!!
    val runId = submission["runId"] as String
    val runSnapshot = hostRuntime.loadChatRunSnapshot(runId)
    val runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot()
    val hostLifecycle = runtimeSnapshot["hostLifecycle"] as Map<*, *>

    assertEquals(activeSessionId, submission["sessionId"])
    assertEquals(handle.submissions.single().taskId, submission["taskId"])
    assertEquals(runId, runSnapshot?.get("runId"))
    assertEquals(handle.submissions.single().taskId, runSnapshot?.get("taskId"))
    assertTrue((hostLifecycle["processStartId"] as String).isNotBlank())
    assertTrue((hostLifecycle["hostInstanceId"] as String).isNotBlank())
    assertTrue((hostLifecycle["runtimeOwnerId"] as String).isNotBlank())
  }

  @Test
  fun refreshSandboxSessionInfoSubmitsPreapprovedHostToolCallWithoutTranscriptMessage() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-sandbox-session-refresh"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    hostRuntime.refreshSandboxSessionInfo()

    val submittedTask = handle.submittedTasks.single()
    val taskPayload = jsonObject(submittedTask.input)
    val chatMessages = hostRuntime.loadChatSnapshot()["messages"] as List<*>
    val runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot()
    val activeRun = ((runtimeSnapshot["activeRuns"] as List<*>).single()) as Map<*, *>

    assertEquals(AgentTaskType.TOOL_CALL, submittedTask.type)
    assertEquals("tool_call", taskPayload["type"].toString().trim('"'))
    assertEquals("sandbox_session_info", taskPayload["tool_name"].toString().trim('"'))
    assertEquals(
      RunSubmissionSources.HOST_UI_TOOL_ACTION,
      submittedTask.metadata[RunLifecycleMetadataKeys.SUBMISSION_SOURCE],
    )
    assertEquals(
      "sandbox_session_info",
      submittedTask.metadata[RunLifecycleMetadataKeys.PREAPPROVED_TOOL_NAME],
    )
    assertEquals(
      activeSessionId,
      submittedTask.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_HOST_SESSION_ID],
    )
    assertEquals(listOf(submittedTask.id), handle.ensureProcessingTaskIds)
    assertTrue(chatMessages.isEmpty())
    assertEquals(handle.submissions.single().runId, activeRun["runId"])
  }

  @Test
  fun chatRuntimeSnapshotRestoresJournaledEventsAfterHostRecreation() {
    val runtimeRoot = temporaryFolder.newFolder("runtime-journal-host-recreation")
    val firstFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val secondFactory = FileBackedRunEventJournalStoreFactory(runtimeRoot)
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-journal-recreation"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)

    val firstHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runEventJournalStoreFactory = firstFactory,
    )

    val submission = firstHost.submitChatMessage("persist runtime events")!!
    val task = handle.submittedTasks.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = submission["runId"] as String,
        taskId = task.id,
        turn = 0,
        text = "Scanning workspace",
        isFinal = false,
        stage = "workspace_scan",
        emittedAtEpochMs = 1_234L,
      ),
    )

    val recreatedHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runEventJournalStoreFactory = secondFactory,
    )
    val runtimeActivity = recreatedHost.loadChatRuntimeSnapshot()
    val events = runtimeActivity["events"] as List<*>
    val event = events.single() as Map<*, *>

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals("assistant_phase", event["kind"])
    assertEquals("commentary", event["phase"])
    assertEquals("Scanning workspace", event["text"])
    assertEquals("workspace_scan", event["stage"])
    assertEquals(submission["runId"], event["runId"])
  }

  @Test
  fun recreatedHostsExposeStableRuntimeOwnerLifecycle() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-owner-lifecycle"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val runtimeOwnerDescriptor = HostRuntimeLifecycleDescriptor()
    val firstHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
        processStartId = runtimeOwnerDescriptor.processStartId,
        processStartedAtEpochMs = runtimeOwnerDescriptor.processStartedAtEpochMs,
        runtimeOwnerId = runtimeOwnerDescriptor.runtimeOwnerId,
      ),
      runtimeOwnerDescriptor = runtimeOwnerDescriptor,
    )
    val secondHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
        processStartId = runtimeOwnerDescriptor.processStartId,
        processStartedAtEpochMs = runtimeOwnerDescriptor.processStartedAtEpochMs,
        runtimeOwnerId = runtimeOwnerDescriptor.runtimeOwnerId,
      ),
      runtimeOwnerDescriptor = runtimeOwnerDescriptor,
    )

    val firstRuntimeSnapshot = firstHost.loadChatRuntimeSnapshot()
    val secondRuntimeSnapshot = secondHost.loadChatRuntimeSnapshot()
    val firstHostLifecycle = firstRuntimeSnapshot["hostLifecycle"] as Map<*, *>
    val secondHostLifecycle = secondRuntimeSnapshot["hostLifecycle"] as Map<*, *>
    val firstOwnerLifecycle = firstRuntimeSnapshot["runtimeOwnerLifecycle"] as Map<*, *>
    val secondOwnerLifecycle = secondRuntimeSnapshot["runtimeOwnerLifecycle"] as Map<*, *>

    assertTrue(firstHostLifecycle["hostInstanceId"] != secondHostLifecycle["hostInstanceId"])
    assertEquals(
      firstHostLifecycle["runtimeOwnerId"],
      firstOwnerLifecycle["runtimeOwnerId"],
    )
    assertEquals(
      secondHostLifecycle["runtimeOwnerId"],
      secondOwnerLifecycle["runtimeOwnerId"],
    )
    assertEquals(firstOwnerLifecycle, secondOwnerLifecycle)
  }

  @Test
  fun recreatedHostsExposeStableRuntimeServiceLifecycle() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-service-lifecycle"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val runtimeOwnerDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(
      processStartId = runtimeOwnerDescriptor.processStartId,
      processStartedAtEpochMs = runtimeOwnerDescriptor.processStartedAtEpochMs,
    )
    val firstHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
        processStartId = runtimeOwnerDescriptor.processStartId,
        processStartedAtEpochMs = runtimeOwnerDescriptor.processStartedAtEpochMs,
        runtimeOwnerId = runtimeOwnerDescriptor.runtimeOwnerId,
      ),
      runtimeOwnerDescriptor = runtimeOwnerDescriptor,
      runtimeServiceDescriptor = runtimeServiceDescriptor,
    )
    val secondHost = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      lifecycleDescriptor = HostRuntimeLifecycleDescriptor(
        processStartId = runtimeOwnerDescriptor.processStartId,
        processStartedAtEpochMs = runtimeOwnerDescriptor.processStartedAtEpochMs,
        runtimeOwnerId = runtimeOwnerDescriptor.runtimeOwnerId,
      ),
      runtimeOwnerDescriptor = runtimeOwnerDescriptor,
      runtimeServiceDescriptor = runtimeServiceDescriptor,
    )

    val firstRuntimeSnapshot = firstHost.loadChatRuntimeSnapshot()
    val secondRuntimeSnapshot = secondHost.loadChatRuntimeSnapshot()
    val firstServiceLifecycle = firstRuntimeSnapshot["runtimeServiceLifecycle"] as Map<*, *>
    val secondServiceLifecycle = secondRuntimeSnapshot["runtimeServiceLifecycle"] as Map<*, *>

    assertEquals(
      runtimeServiceDescriptor.serviceInstanceId,
      firstServiceLifecycle["serviceInstanceId"],
    )
    assertEquals(firstServiceLifecycle, secondServiceLifecycle)
  }

  @Test
  fun runtimeSnapshotsProjectRuntimeServiceConnectionState() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-service-connection"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val connectionState = RuntimeServiceConnectionState.inProcessFallback(
      serviceStartRequested = true,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
      runtimeServiceConnectionState = connectionState,
    )

    val runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot()
    val shellSnapshot = hostRuntime.loadShellSnapshot()
    val runtimeConnection = runtimeSnapshot["runtimeServiceConnectionState"] as Map<*, *>
    val shellConnection = shellSnapshot["runtimeServiceConnectionState"] as Map<*, *>

    assertEquals("fallback", runtimeConnection["phase"])
    assertEquals("in_process", runtimeConnection["transport"])
    assertEquals(true, runtimeConnection["serviceStartRequested"])
    assertEquals(false, runtimeConnection["bindingRequested"])
    assertEquals(false, runtimeConnection["binderAvailable"])
    assertEquals("binder_unavailable", runtimeConnection["fallbackReason"])
    assertEquals(runtimeConnection, shellConnection)
  }

  @Test
  fun runtimeSnapshotsProjectRuntimeOwnerWorkSummaryAcrossTrackedSessions() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-owner-work-summary"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendUserMessage(activeSessionId, "Keep the original session")
    val backgroundSessionId = chatStore.createSession().activeSession.sessionId
    chatStore.selectSession(activeSessionId)
    val manager = RecordingRuntimeManager()
    val activeHandle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    ).apply {
      putRunSnapshot(
        AgentRunSnapshot(
          sessionId = activeSessionId,
          runId = "run-active",
          taskId = "task-active",
          acceptedAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_100L,
          lifecycleState = QueueTaskLifecycleState.RUNNING,
          taskState = AgentTaskState.RUNNING,
        ),
      )
    }
    val backgroundHandle = RecordingSessionHandle(
      sessionId = backgroundSessionId,
      onResume = manager.resumedSessionIds::add,
    ).apply {
      putRunSnapshot(
        AgentRunSnapshot(
          sessionId = backgroundSessionId,
          runId = "run-background",
          taskId = "task-background",
          acceptedAtEpochMs = 2_000L,
          updatedAtEpochMs = 2_100L,
          lifecycleState = QueueTaskLifecycleState.RUNNING,
          taskState = AgentTaskState.RUNNING,
          managedProcessIds = listOf("proc-1"),
          runningManagedProcessCount = 1,
          hasLiveManagedProcesses = true,
        ),
      )
      putManagedProcess(
        com.opencray.runtime.process.ManagedProcessSnapshot(
          processId = "proc-1",
          taskId = "task-background",
          command = "npm",
          args = listOf("run", "dev"),
          workingDirectory = ".",
          status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
          processStarted = true,
          timeoutMs = 120_000L,
          startedAtEpochMs = 2_000L,
          updatedAtEpochMs = 2_100L,
        ),
      )
    }
    manager.putHandle(activeHandle)
    manager.putHandle(backgroundHandle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
      runtimeServiceConnectionState = RuntimeServiceConnectionState.binderConnected(),
    )

    val runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot()
    val shellSnapshot = hostRuntime.loadShellSnapshot()
    val runtimeSummary = runtimeSnapshot["runtimeOwnerWorkSummary"] as Map<*, *>
    val shellSummary = shellSnapshot["runtimeOwnerWorkSummary"] as Map<*, *>

    assertEquals(true, runtimeSummary["hasActiveWork"])
    assertEquals(2, runtimeSummary["trackedSessionCount"])
    assertEquals(2, runtimeSummary["activeRunCount"])
    assertEquals(2, runtimeSummary["activeSessionCount"])
    assertEquals(
      setOf(activeSessionId, backgroundSessionId),
      (runtimeSummary["activeSessionIds"] as List<*>).toSet(),
    )
    assertEquals(
      setOf(activeSessionId, backgroundSessionId),
      (runtimeSummary["pendingWorkSessionIds"] as List<*>).toSet(),
    )
    assertEquals(
      listOf(backgroundSessionId),
      runtimeSummary["liveManagedProcessSessionIds"],
    )
    assertEquals(runtimeSummary, shellSummary)
  }

  @Test
  fun runtimeSnapshotsProjectRuntimeServiceWorkState() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-service-work-state"))
    val manager = RecordingRuntimeManager()
    val sessionId = chatStore.loadState().activeSession.sessionId
    manager.putHandle(
      RecordingSessionHandle(
        sessionId = sessionId,
        onResume = manager.resumedSessionIds::add,
      ),
    )
    val workState = RuntimeServiceWorkState(
      phase = RuntimeServiceWorkState.PHASE_ACTIVE_WORK,
      hasActiveWork = true,
      keepAliveRequired = true,
      keepAliveReason = RuntimeServiceWorkState.KEEP_ALIVE_REASON_ACTIVE_RUN,
      changedAtEpochMs = 9_000L,
      activeSinceEpochMs = 8_500L,
      idleSinceEpochMs = null,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
      runtimeServiceWorkState = workState,
      runtimeServiceConnectionState = RuntimeServiceConnectionState.binderConnected(),
    )

    val runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot()
    val shellSnapshot = hostRuntime.loadShellSnapshot()
    val runtimeWorkState = runtimeSnapshot["runtimeServiceWorkState"] as Map<*, *>
    val shellWorkState = shellSnapshot["runtimeServiceWorkState"] as Map<*, *>

    assertEquals("active_work", runtimeWorkState["phase"])
    assertEquals(true, runtimeWorkState["hasActiveWork"])
    assertEquals(true, runtimeWorkState["keepAliveRequired"])
    assertEquals("active_run", runtimeWorkState["keepAliveReason"])
    assertEquals(9_000L, runtimeWorkState["changedAtEpochMs"])
    assertEquals(8_500L, runtimeWorkState["activeSinceEpochMs"])
    assertEquals(null, runtimeWorkState["idleSinceEpochMs"])
    assertEquals(runtimeWorkState, shellWorkState)
  }

  @Test
  fun runtimeSnapshotsProjectRuntimeServiceKeepAliveState() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-service-keepalive-state"))
    val manager = RecordingRuntimeManager()
    val sessionId = chatStore.loadState().activeSession.sessionId
    manager.putHandle(
      RecordingSessionHandle(
        sessionId = sessionId,
        onResume = manager.resumedSessionIds::add,
      ),
    )
    val keepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
      idleGraceMs = 30_000L,
      stopScheduled = true,
      stopDeadlineEpochMs = 31_000L,
      lastStartId = 7,
      lastStartCommandAtEpochMs = 1_000L,
      lastStopRequestAtEpochMs = null,
      lastStopSucceeded = null,
      changedAtEpochMs = 1_500L,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
      runtimeServiceKeepAliveState = keepAliveState,
      runtimeServiceConnectionState = RuntimeServiceConnectionState.binderConnected(),
    )

    val runtimeSnapshot = hostRuntime.loadChatRuntimeSnapshot()
    val shellSnapshot = hostRuntime.loadShellSnapshot()
    val runtimeKeepAliveState = runtimeSnapshot["runtimeServiceKeepAliveState"] as Map<*, *>
    val shellKeepAliveState = shellSnapshot["runtimeServiceKeepAliveState"] as Map<*, *>

    assertEquals("idle_grace", runtimeKeepAliveState["phase"])
    assertEquals(30_000L, runtimeKeepAliveState["idleGraceMs"])
    assertEquals(true, runtimeKeepAliveState["stopScheduled"])
    assertEquals(31_000L, runtimeKeepAliveState["stopDeadlineEpochMs"])
    assertEquals(true, runtimeKeepAliveState["hasSeenStartCommand"])
    assertEquals(7, runtimeKeepAliveState["lastStartId"])
    assertEquals(1_000L, runtimeKeepAliveState["lastStartCommandAtEpochMs"])
    assertEquals(1_500L, runtimeKeepAliveState["changedAtEpochMs"])
    assertEquals(runtimeKeepAliveState, shellKeepAliveState)
  }

  @Test
  fun runtimeServiceConnectionChangesEmitShellAndRuntimeSnapshots() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-service-connection-observer"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val mainThreadPoster = QueuedMainThreadPoster()
    var connectionState = RuntimeServiceConnectionState.bindingPending()
    var connectionChangeListener: (() -> Unit)? = null
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      mainThreadPoster = mainThreadPoster,
      runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
      runtimeServiceConnectionStateProvider = { connectionState },
      runtimeServiceConnectionChangeRegistrar = RuntimeServiceConnectionChangeRegistrar { listener ->
        connectionChangeListener = listener
        { if (connectionChangeListener === listener) connectionChangeListener = null }
      },
    )
    val observedShellSnapshots = mutableListOf<Map<String, Any?>>()
    val observedRuntimeSnapshots = mutableListOf<Map<String, Any?>>()
    val disposeShell = hostRuntime.observeShell { snapshot ->
      observedShellSnapshots += snapshot
    }
    val disposeRuntime = hostRuntime.observeChatRuntime { snapshot ->
      observedRuntimeSnapshots += snapshot
    }
    mainThreadPoster.flush()
    observedShellSnapshots.clear()
    observedRuntimeSnapshots.clear()

    connectionState = RuntimeServiceConnectionState.binderConnected()
    checkNotNull(connectionChangeListener).invoke()
    mainThreadPoster.flush()

    val shellConnection =
      observedShellSnapshots.last()["runtimeServiceConnectionState"] as Map<*, *>
    val runtimeConnection =
      observedRuntimeSnapshots.last()["runtimeServiceConnectionState"] as Map<*, *>

    assertEquals("bound", shellConnection["phase"])
    assertEquals("binder", shellConnection["transport"])
    assertEquals(true, shellConnection["bindingRequested"])
    assertEquals(true, shellConnection["binderAvailable"])
    assertEquals(shellConnection, runtimeConnection)

    disposeShell()
    disposeRuntime()
  }

  @Test
  fun runtimeServiceKeepAliveChangesEmitShellAndRuntimeSnapshots() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-service-keepalive-observer"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val mainThreadPoster = QueuedMainThreadPoster()
    var keepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_CREATED,
      changedAtEpochMs = 1_000L,
    )
    var keepAliveChangeListener: (() -> Unit)? = null
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      mainThreadPoster = mainThreadPoster,
      runtimeServiceDescriptor = RuntimeServiceLifecycleDescriptor(),
      runtimeServiceKeepAliveStateProvider = { keepAliveState },
      runtimeServiceKeepAliveChangeRegistrar = RuntimeServiceKeepAliveChangeRegistrar { listener ->
        keepAliveChangeListener = listener
        { if (keepAliveChangeListener === listener) keepAliveChangeListener = null }
      },
    )
    val observedShellSnapshots = mutableListOf<Map<String, Any?>>()
    val observedRuntimeSnapshots = mutableListOf<Map<String, Any?>>()
    val disposeShell = hostRuntime.observeShell { snapshot ->
      observedShellSnapshots += snapshot
    }
    val disposeRuntime = hostRuntime.observeChatRuntime { snapshot ->
      observedRuntimeSnapshots += snapshot
    }
    mainThreadPoster.flush()
    observedShellSnapshots.clear()
    observedRuntimeSnapshots.clear()

    keepAliveState = RuntimeServiceKeepAliveState(
      phase = RuntimeServiceKeepAliveState.PHASE_IDLE_GRACE,
      idleGraceMs = 30_000L,
      stopScheduled = true,
      stopDeadlineEpochMs = 31_000L,
      lastStartId = 5,
      lastStartCommandAtEpochMs = 1_000L,
      changedAtEpochMs = 1_500L,
    )
    checkNotNull(keepAliveChangeListener).invoke()
    mainThreadPoster.flush()

    val shellKeepAlive =
      observedShellSnapshots.last()["runtimeServiceKeepAliveState"] as Map<*, *>
    val runtimeKeepAlive =
      observedRuntimeSnapshots.last()["runtimeServiceKeepAliveState"] as Map<*, *>

    assertEquals("idle_grace", shellKeepAlive["phase"])
    assertEquals(true, shellKeepAlive["stopScheduled"])
    assertEquals(31_000L, shellKeepAlive["stopDeadlineEpochMs"])
    assertEquals(5, shellKeepAlive["lastStartId"])
    assertEquals(shellKeepAlive, runtimeKeepAlive)

    disposeShell()
    disposeRuntime()
  }

  @Test
  fun completedRunSnapshotIncludesStructuredMemoryTrace() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-memory-trace"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need run memory trace")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Applied the recalled memory.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "responseFormat" to "json_final",
          "contextMatchedMemoryCount" to "2",
          "contextInjectedMemoryCount" to "1",
          "contextOmittedMemoryCount" to "1",
          "contextMemoryQueryTerms" to "chinese,gradle",
          "contextMemorySelectedSummary" to "memory-user@420[chinese]",
          "contextMemoryOmittedSummary" to "memory-project:max_records",
          "contextMemoryFilteredCounts" to "scope_mismatch:1,expired:2",
        ),
      ),
    )

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val memoryTrace = runSnapshot["memoryTrace"] as Map<*, *>
    val selected = memoryTrace["selected"] as List<*>
    val omitted = memoryTrace["omitted"] as List<*>
    val filteredCounts = memoryTrace["filteredCounts"] as Map<*, *>

    assertEquals(2, memoryTrace["matchedRecordCount"])
    assertEquals(1, memoryTrace["injectedRecordCount"])
    assertEquals(1, memoryTrace["omittedRecordCount"])
    assertEquals(listOf("chinese", "gradle"), memoryTrace["queryTerms"])
    assertEquals("memory-user", (selected.single() as Map<*, *>)["id"])
    assertEquals("max_records", (omitted.single() as Map<*, *>)["reason"])
    assertEquals(1, filteredCounts["scope_mismatch"])
    assertEquals(2, filteredCounts["expired"])
  }

  @Test
  fun completedRunSnapshotIncludesStructuredMemoryFlushTrace() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-memory-flush"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need run memory flush trace")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Applied the flushed memory.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "responseFormat" to "json_final",
          "contextMemoryFlushOutcome" to "written",
          "contextMemoryFlushOmittedMessageCount" to "4",
          "contextMemoryFlushOmittedCharCount" to "512",
          "contextMemoryFlushSignature" to "flush-signature-123",
          "contextMemoryFlushCandidateCount" to "3",
          "contextMemoryFlushWrittenRecordCount" to "2",
          "contextMemoryFlushWrittenKinds" to "project_fact,user_preference",
          "contextMemoryFlushWrittenRecordIds" to "mem-a,mem-b",
        ),
      ),
    )

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val memoryFlush = runSnapshot["memoryFlush"] as Map<*, *>

    assertEquals("written", memoryFlush["outcome"])
    assertEquals(4, memoryFlush["omittedMessageCount"])
    assertEquals(512, memoryFlush["omittedCharCount"])
    assertEquals("flush-signature-123", memoryFlush["signature"])
    assertEquals(3, memoryFlush["candidateCount"])
    assertEquals(2, memoryFlush["writtenRecordCount"])
    assertEquals(listOf("project_fact", "user_preference"), memoryFlush["writtenKinds"])
    assertEquals(listOf("mem-a", "mem-b"), memoryFlush["writtenRecordIds"])
  }

  @Test
  fun completedRunSnapshotIncludesStructuredSkillInventory() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-skill-inventory"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need run skill inventory trace")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Applied the visible skills.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "responseFormat" to "json_final",
          "contextVisibleSkillCount" to "2",
          "contextInjectedSkillCount" to "2",
          "contextOmittedSkillCount" to "0",
          "contextImplicitSkillCount" to "1",
          "contextInvalidSkillCount" to "1",
          "contextActiveSkillName" to "ui-ux-pro-max",
          "contextActiveSkillRelativePath" to ".codex/skills/ui-ux-pro-max/SKILL.md",
          "contextActiveSkillInvocationControl" to "explicit-only",
          "contextActiveSkillExecutionContext" to "inline",
          "contextActiveSkillActivationSource" to "skill_read",
          "contextActiveSkillToolRestrictionEnabled" to "true",
          "contextActiveSkillAllowedTools" to "read,write",
          "contextActiveSkillTruncated" to "false",
          "contextVisibleSkillSummary" to
            "ui-ux-pro-max@.codex/skills/ui-ux-pro-max/SKILL.md[explicit-only|true|inline];" +
            "fun-brainstorming@.codex/skills/fun-brainstorming/SKILL.md[explicit-and-implicit|true|fork]",
        ),
      ),
    )

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val skillInventory = runSnapshot["skillInventory"] as Map<*, *>
    val activeSkill = runSnapshot["activeSkill"] as Map<*, *>
    val skills = skillInventory["skills"] as List<*>
    val firstSkill = skills[0] as Map<*, *>
    val secondSkill = skills[1] as Map<*, *>

    assertEquals(2, skillInventory["visibleSkillCount"])
    assertEquals(2, skillInventory["injectedSkillCount"])
    assertEquals(0, skillInventory["omittedSkillCount"])
    assertEquals(1, skillInventory["implicitSkillCount"])
    assertEquals(1, skillInventory["invalidSkillCount"])
    assertEquals("ui-ux-pro-max", firstSkill["name"])
    assertEquals(".codex/skills/ui-ux-pro-max/SKILL.md", firstSkill["relativePath"])
    assertEquals("explicit-only", firstSkill["invocationControl"])
    assertEquals(true, firstSkill["userInvocable"])
    assertEquals("fork", secondSkill["executionContext"])
    assertEquals("ui-ux-pro-max", activeSkill["name"])
    assertEquals("skill_read", activeSkill["activationSource"])
    assertEquals(true, activeSkill["toolRestrictionEnabled"])
    assertEquals(listOf("read", "write"), activeSkill["allowedToolKeys"])
  }

  @Test
  fun completedRunSnapshotIncludesStructuredDurableCompaction() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-durable-compaction"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need run durable compaction trace")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Applied durable compaction.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "responseFormat" to "json_final",
          "contextDurableCompactionCompactedThisRun" to "true",
          "contextDurableCompactionSourceTranscriptMessageCount" to "18",
          "contextDurableCompactionRetainedTranscriptMessageCount" to "12",
          "contextDurableCompactionLatestMessageCount" to "6",
          "contextDurableCompactionIncludedSummaryCount" to "1",
          "contextDurableCompactionOmittedSummaryCount" to "0",
          "contextDurableCompactionTotalCompactedMessageCount" to "6",
          "contextDurableCompactionLatestAtEpochMs" to "4200",
        ),
      ),
    )

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val durableCompaction = runSnapshot["durableCompaction"] as Map<*, *>

    assertEquals(true, durableCompaction["compactedThisRun"])
    assertEquals(18, durableCompaction["sourceTranscriptMessageCount"])
    assertEquals(12, durableCompaction["retainedTranscriptMessageCount"])
    assertEquals(6, durableCompaction["latestCompactedMessageCount"])
    assertEquals(1, durableCompaction["includedSummaryCount"])
    assertEquals(0, durableCompaction["omittedSummaryCount"])
    assertEquals(1, durableCompaction["totalSummaryCount"])
    assertEquals(6, durableCompaction["totalCompactedMessageCount"])
    assertEquals(4200L, durableCompaction["latestCompactedAtEpochMs"])
  }

  @Test
  fun completedRunSnapshotIncludesStructuredBootstrap() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-bootstrap"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need run bootstrap trace")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Applied bootstrap files.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "responseFormat" to "json_final",
          "contextLiveMode" to "no_soul",
          "contextLiveSoulEnabled" to "false",
          "contextLiveMemoryRecallEnabled" to "true",
          "contextBootstrapMode" to "full",
          "contextBootstrapVisibleFileCount" to "2",
          "contextBootstrapInjectedFileCount" to "2",
          "contextBootstrapOmittedFileCount" to "0",
          "contextBootstrapTruncatedFileCount" to "1",
          "contextBootstrapFileSummary" to
            "AGENTS.md@AGENTS.md[42|42|false];PROJECT.md@PROJECT.md[80|31|true]",
        ),
      ),
    )

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val liveContext = runSnapshot["liveContext"] as Map<*, *>
    val bootstrap = runSnapshot["bootstrap"] as Map<*, *>
    val files = bootstrap["files"] as List<*>
    val firstFile = files[0] as Map<*, *>
    val secondFile = files[1] as Map<*, *>

    assertEquals("no_soul", liveContext["mode"])
    assertEquals(false, liveContext["soulEnabled"])
    assertEquals(true, liveContext["memoryRecallEnabled"])
    assertEquals("full", bootstrap["mode"])
    assertEquals(2, bootstrap["visibleFileCount"])
    assertEquals(2, bootstrap["injectedFileCount"])
    assertEquals(0, bootstrap["omittedFileCount"])
    assertEquals(1, bootstrap["truncatedFileCount"])
    assertEquals("AGENTS.md", firstFile["name"])
    assertEquals("AGENTS.md", firstFile["relativePath"])
    assertEquals(42, firstFile["sourceCharCount"])
    assertEquals(42, firstFile["injectedCharCount"])
    assertEquals(false, firstFile["truncated"])
    assertEquals(true, secondFile["truncated"])
  }

  @Test
  fun completedRunSnapshotIncludesStructuredContextBudget() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-context-budget"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need run context budget trace")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Applied context budget.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "responseFormat" to "json_final",
          "contextBudgetApplied" to "true",
          "contextBudgetPressureMode" to "EMERGENCY",
          "contextBudgetContextWindowTokens" to "900",
          "contextBudgetReservedOutputTokens" to "256",
          "contextBudgetSafetyMarginTokens" to "96",
          "contextBudgetSelectedPreset" to "balanced",
          "contextBudgetEffectivePreset" to "dev",
          "contextBudgetPresetSource" to "raw",
          "contextBudgetPresetDiverged" to "true",
          "contextBudgetSourcePreset" to "expanded",
          "contextBudgetSourceTranscriptMaxMessages" to "16",
          "contextBudgetSourceInjectedMemoryMaxRecords" to "6",
          "contextBudgetSourceMemoryRecallMaxRecords" to "8",
          "contextBudgetSourceBootstrapMaxChars" to "4800",
          "contextBudgetSourceSkillInventoryMaxSkills" to "12",
          "contextBudgetSourceActiveSkillMaxChars" to "4800",
          "contextBudgetSourceRecentObservationMaxEntries" to "6",
          "contextBudgetSourceMemoryFlushMaxToolObservations" to "12",
          "contextBudgetHardInputTokens" to "548",
          "contextBudgetTargetInputTokens" to "512",
          "contextBudgetEmergencyInputTokens" to "548",
          "contextBudgetUnresolvedOverflow" to "true",
          "contextBudgetFullLayerCount" to "4",
          "contextBudgetCompactLayerCount" to "2",
          "contextBudgetMinimalLayerCount" to "1",
          "contextBudgetOmittedLayerCount" to "1",
          "contextBudgetReducedLayerNames" to "Working State,Conversation",
          "contextBudgetOmittedLayerNames" to "Retrieved Memory",
          "contextBudgetLayerDetails" to """
            [
              {
                "id": "WORKING_STATE",
                "name": "Working State",
                "priorityClass": "OPTIONAL_SUPPORT_CONTEXT",
                "retentionPriority": 70,
                "estimatedTokensBefore": 220,
                "estimatedTokensAfter": 120,
                "finalState": "compact",
                "omitted": false,
                "reduced": true,
                "appliedOperators": ["reduce_working_state_compact"]
              },
              {
                "id": "CONVERSATION",
                "name": "Conversation",
                "priorityClass": "RECENT_REPLAY",
                "retentionPriority": 110,
                "estimatedTokensBefore": 420,
                "estimatedTokensAfter": 180,
                "finalState": "minimal",
                "omitted": false,
                "reduced": true,
                "appliedOperators": ["reduce_conversation_window_minimal"]
              },
              {
                "id": "RETRIEVED_MEMORY",
                "name": "Retrieved Memory",
                "priorityClass": "BOUNDED_DURABLE_RECALL",
                "retentionPriority": 90,
                "estimatedTokensBefore": 48,
                "estimatedTokensAfter": 0,
                "finalState": "omitted",
                "omitted": true,
                "reduced": false,
                "appliedOperators": ["omit_layer"]
              }
            ]
          """.trimIndent(),
          "contextBudgetLayerSummary" to
            "WORKING_STATE:compact:20;CONVERSATION:minimal:120;RETRIEVED_MEMORY:omitted:48",
        ),
      ),
    )

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val contextBudget = runSnapshot["contextBudget"] as Map<*, *>

    assertEquals(true, contextBudget["applied"])
    assertEquals("EMERGENCY", contextBudget["pressureMode"])
    assertEquals("balanced", contextBudget["selectedPreset"])
    assertEquals("dev", contextBudget["effectivePreset"])
    assertEquals("raw", contextBudget["presetSource"])
    assertEquals(true, contextBudget["presetDiverged"])
    assertEquals("expanded", contextBudget["sourcePreset"])
    assertEquals(16, contextBudget["sourceTranscriptMaxMessages"])
    assertEquals(6, contextBudget["sourceInjectedMemoryMaxRecords"])
    assertEquals(8, contextBudget["sourceMemoryRecallMaxRecords"])
    assertEquals(4800, contextBudget["sourceBootstrapMaxChars"])
    assertEquals(12, contextBudget["sourceSkillInventoryMaxSkills"])
    assertEquals(4800, contextBudget["sourceActiveSkillMaxChars"])
    assertEquals(6, contextBudget["sourceRecentObservationMaxEntries"])
    assertEquals(12, contextBudget["sourceMemoryFlushMaxToolObservations"])
    assertEquals(900, contextBudget["contextWindowTokens"])
    assertEquals(256, contextBudget["reservedOutputTokens"])
    assertEquals(96, contextBudget["safetyMarginTokens"])
    assertEquals(548, contextBudget["hardInputTokens"])
    assertEquals(512, contextBudget["targetInputTokens"])
    assertEquals(548, contextBudget["emergencyInputTokens"])
    assertEquals(true, contextBudget["unresolvedOverflow"])
    assertEquals(4, contextBudget["fullLayerCount"])
    assertEquals(2, contextBudget["compactLayerCount"])
    assertEquals(1, contextBudget["minimalLayerCount"])
    assertEquals(1, contextBudget["omittedLayerCount"])
    assertEquals(
      listOf("Working State", "Conversation"),
      contextBudget["reducedLayerNames"],
    )
    assertEquals(listOf("Retrieved Memory"), contextBudget["omittedLayerNames"])
    assertEquals(
      "WORKING_STATE:compact:20;CONVERSATION:minimal:120;RETRIEVED_MEMORY:omitted:48",
      contextBudget["layerSummary"],
    )
    val layers = contextBudget["layers"] as List<*>
    assertEquals(3, layers.size)
    val workingStateLayer = layers[0] as Map<*, *>
    assertEquals("WORKING_STATE", workingStateLayer["id"])
    assertEquals("Working State", workingStateLayer["name"])
    assertEquals("OPTIONAL_SUPPORT_CONTEXT", workingStateLayer["priorityClass"])
    assertEquals(70, workingStateLayer["retentionPriority"])
    assertEquals(220, workingStateLayer["estimatedTokensBefore"])
    assertEquals(120, workingStateLayer["estimatedTokensAfter"])
    assertEquals("compact", workingStateLayer["finalState"])
    assertEquals(false, workingStateLayer["omitted"])
    assertEquals(true, workingStateLayer["reduced"])
    assertEquals(
      listOf("reduce_working_state_compact"),
      workingStateLayer["appliedOperators"],
    )
  }

  @Test
  fun completedRunSnapshotIncludesLlmDiagnosticsCacheBreakReason() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-llm-diagnostics"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Need cache-break diagnostics")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Captured LLM diagnostics.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE to "openai_tool_calls",
          LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED to "true",
          LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED to "true",
          LiteLlmMetadataKeys.PARSED_TOOL_CALL_OBSERVED to "true",
          LiteLlmMetadataKeys.FALLBACK_PARSER_ATTEMPTED to "false",
          LiteLlmMetadataKeys.FALLBACK_PARSER_SUCCEEDED to "false",
          "responsesContinuationRecoveryCount" to "1",
          "responsesContinuationRecoveryLastReason" to "responses_restored_replay_required",
          "localContinuationUsedCount" to "0",
          "localContinuationFallbackCount" to "1",
          "localContinuationLastMode" to "full_rebuild",
          "localContinuationLastReason" to "user_setting_changed",
          LiteLlmMetadataKeys.TOOL_CALL_EVENT_EMITTED to "true",
          LiteLlmMetadataKeys.TOOL_RESULT_EVENT_EMITTED to "true",
          LiteLlmMetadataKeys.CONTEXT_CACHE_BREAK_REASON to "user_setting_changed",
          LiteLlmMetadataKeys.LAST_SUCCESSFUL_TOOL_NAME to "EchoProbe",
        ),
      ),
    )

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val llmDiagnostics = runSnapshot["llmDiagnostics"] as Map<*, *>

    assertEquals("openai_tool_calls", llmDiagnostics["providerResponseShape"])
    assertEquals(true, llmDiagnostics["nativeToolCallRequested"])
    assertEquals(true, llmDiagnostics["nativeToolCallObserved"])
    assertEquals(true, llmDiagnostics["parsedToolCallObserved"])
    assertEquals(false, llmDiagnostics["fallbackParserAttempted"])
    assertEquals(false, llmDiagnostics["fallbackParserSucceeded"])
    assertEquals(1, llmDiagnostics["responsesContinuationRecoveryCount"])
    assertEquals(
      "responses_restored_replay_required",
      llmDiagnostics["responsesContinuationRecoveryLastReason"],
    )
    assertEquals(0, llmDiagnostics["localContinuationUsedCount"])
    assertEquals(1, llmDiagnostics["localContinuationFallbackCount"])
    assertEquals("full_rebuild", llmDiagnostics["localContinuationLastMode"])
    assertEquals("user_setting_changed", llmDiagnostics["localContinuationLastReason"])
    assertEquals(true, llmDiagnostics["toolCallEventEmitted"])
    assertEquals(true, llmDiagnostics["toolResultEventEmitted"])
    assertEquals("user_setting_changed", llmDiagnostics["contextCacheBreakReason"])
    assertEquals("EchoProbe", llmDiagnostics["lastSuccessfulToolName"])
  }

  @Test
  fun runSnapshotIncludesManagedProcessLinkageAndKeepsLiveProcessRunVisible() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-process-linkage"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)

    val submission = hostRuntime.submitChatMessage("Start the dev server")!!
    val task = handle.submittedTasks.single()
    val fullStdout = "booting\nready on http://localhost:3000"
    val fullStderr = "warn: deprecated dependency"
    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-live",
        taskId = task.id,
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 120_000L,
        stdout = fullStdout,
        stderr = fullStderr,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
      ),
    )
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = com.opencray.runtime.OpenCrayToolResultEvent(
        runId = submission["runId"] as String,
        taskId = task.id,
        turn = 1,
        call = AgentToolCall(
          toolName = "ProcessStart",
        ),
        result = AgentToolResult(
          toolName = "ProcessStart",
          status = AgentToolResultStatus.SUCCESS,
          content = "Started dev server",
          metadata = mapOf("processId" to "proc-live"),
        ),
        emittedAtEpochMs = 1_001L,
      ),
    )
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Server is running in the background.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_002L,
        metadata = task.metadata,
      ),
    )

    val runSnapshot = hostRuntime.loadChatRunSnapshot(submission["runId"] as String)!!
    val runtimeActivity = hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>
    val activeRuns = runtimeActivity["activeRuns"] as List<*>
    val activeRun = activeRuns.single() as Map<*, *>
    val runManagedProcesses = runSnapshot["managedProcesses"] as List<*>
    val activeManagedProcesses = activeRun["managedProcesses"] as List<*>
    val runManagedProcess = runManagedProcesses.single() as Map<*, *>
    val activeManagedProcess = activeManagedProcesses.single() as Map<*, *>

    assertEquals(listOf("proc-live"), runSnapshot["managedProcessIds"])
    assertEquals(1, runManagedProcesses.size)
    assertEquals("proc-live", runManagedProcess["processId"])
    assertEquals("npm", runManagedProcess["command"])
    assertEquals(fullStdout, runManagedProcess["stdout"])
    assertEquals(fullStderr, runManagedProcess["stderr"])
    assertEquals(fullStdout, runManagedProcess["stdoutPreview"])
    assertEquals(fullStderr, runManagedProcess["stderrPreview"])
    assertEquals(1, runSnapshot["runningManagedProcessCount"])
    assertEquals(true, runSnapshot["hasLiveManagedProcesses"])
    assertEquals(true, runSnapshot["isTerminal"])
    assertEquals(true, runSnapshot["isActive"])
    assertEquals(submission["runId"], activeRun["runId"])
    assertEquals(1, activeManagedProcesses.size)
    assertEquals("proc-live", activeManagedProcess["processId"])
    assertEquals(fullStdout, activeManagedProcess["stdout"])
    assertEquals(fullStderr, activeManagedProcess["stderr"])
    assertEquals(true, activeRun["hasLiveManagedProcesses"])
  }

  @Test
  fun observeChatRuntimeRefreshesManagedProcessSnapshotsWhileRunIsActive() {
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-runtime-live-process-refresh"),
    )
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
      liveChatRuntimeRefreshIntervalMs = 20L,
    )
    val observedRuntimeSnapshots = mutableListOf<Map<String, Any?>>()
    val disposeRuntime = hostRuntime.observeChatRuntime { snapshot ->
      observedRuntimeSnapshots += snapshot
    }

    val submission = hostRuntime.submitChatMessage("Start the dev server")!!
    val task = handle.submittedTasks.single()
    observedRuntimeSnapshots.clear()

    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-live",
        taskId = task.id,
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 120_000L,
        stdout = "ready on http://localhost:3000",
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_100L,
      ),
    )

    val firstManagedProcess = waitForObservedManagedProcessSnapshot(
      observedRuntimeSnapshots = observedRuntimeSnapshots,
      runId = submission["runId"] as String,
    )
    assertEquals("proc-live", firstManagedProcess["processId"])
    assertEquals(
      "ready on http://localhost:3000",
      firstManagedProcess["stdout"],
    )
    assertEquals(
      "ready on http://localhost:3000",
      firstManagedProcess["stdoutPreview"],
    )

    handle.putManagedProcess(
      com.opencray.runtime.process.ManagedProcessSnapshot(
        processId = "proc-live",
        taskId = task.id,
        command = "npm",
        args = listOf("run", "dev"),
        workingDirectory = ".",
        status = com.opencray.runtime.process.ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = 120_000L,
        stdout = "ready on http://localhost:3000\ncompiled successfully",
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_200L,
      ),
    )

    val refreshedManagedProcess = waitForObservedManagedProcessSnapshot(
      observedRuntimeSnapshots = observedRuntimeSnapshots,
      runId = submission["runId"] as String,
      stdoutPreview = "compiled successfully",
    )
    assertEquals("proc-live", refreshedManagedProcess["processId"])
    assertTrue(
      (refreshedManagedProcess["stdout"] as String).contains(
        "compiled successfully",
      ),
    )
    assertTrue(
      (refreshedManagedProcess["stdoutPreview"] as String).contains(
        "compiled successfully",
      ),
    )

    disposeRuntime()
  }

  @Test
  fun approveChatApprovalResumesTaskAndRestoresThinkingPlaceholder() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-resume"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val replayCalls = mutableListOf<Map<String, Any?>>()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      approvalApprovedReplayRecorder = { sessionId, taskId, runId, toolName, isHighRisk, _ ->
        replayCalls += mapOf(
          "sessionId" to sessionId,
          "taskId" to taskId,
          "runId" to runId,
          "toolName" to toolName,
          "isHighRisk" to isHighRisk,
        )
      },
    )

    val run = hostRuntime.submitChatMessage("Need approval")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "HIGH_RISK_APPROVAL_REQUIRED",
        errorMessage = "High-risk approval required. Review this request carefully before approving. Approval is required before command_exec can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Bash",
        ),
      ),
    )

    hostRuntime.approveChatApproval(run["runId"] as String)

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val runtimeActivity = snapshot["runtimeActivity"] as Map<*, *>
    val events = (runtimeActivity["events"] as List<*>).map { event -> event as Map<*, *> }
    val approvalResultEvent = events.last()
    val composerPlaceholder = snapshot["composerPlaceholder"]
    val drawer = snapshot["drawer"] as Map<*, *>
    val drawerSession = ((drawer["sessions"] as List<*>).single()) as Map<*, *>
    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals(listOf(task.id), handle.resumedTaskIds)
    assertTrue(pendingApprovals.isEmpty())
    assertEquals(
      listOf(
        mapOf(
          "sessionId" to activeSessionId,
          "taskId" to task.id,
          "runId" to (run["runId"] as String),
          "toolName" to "Bash",
          "isHighRisk" to true,
        ),
      ),
      replayCalls,
    )
    assertEquals(listOf("approval_wait", "approval_result"), events.map { it["kind"] })
    assertEquals("approved", approvalResultEvent["status"])
    assertEquals("Bash", approvalResultEvent["toolName"])
    assertEquals(true, approvalResultEvent["isHighRisk"])
    assertEquals("Approval granted. The agent is resuming.", approvalResultEvent["text"])
    assertEquals("Message OpenCray", composerPlaceholder)
    assertEquals(
      listOf(
        "Need approval",
        "Thinking",
        "Approval granted. The agent is resuming.",
      ),
      messages.map { it.text },
    )
  }

  @Test
  fun approveChatApprovalPersistsDurableCheckpointUntilNextRuntimeEvent() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-checkpoint"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val promptCheckpointStore = promptCheckpointStoreFactory.forChatSession(activeSessionId)
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 1,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    val run = hostRuntime.submitChatMessage("Need approval")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Read can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata +
          OpenCrayPromptResumeMetadata.encodeToMetadata(resumeState, json) +
          mapOf("normalizedToolName" to "Read"),
      ),
    )

    hostRuntime.approveChatApproval(run["runId"] as String)

    val runSnapshotBeforeResumeEvent = hostRuntime.loadChatRunSnapshot(run["runId"] as String)!!
    val recoveryPlan = runSnapshotBeforeResumeEvent["recoveryPlan"] as Map<*, *>

    assertEquals(
      PromptCheckpointKind.APPROVED_PENDING_RESUME,
      promptCheckpointStore.get(task.id)?.checkpointKind,
    )
    assertEquals("resume_waiting_for_user", recoveryPlan["action"])
    assertEquals("approval_granted_waiting_for_manual_resume", recoveryPlan["reasonCode"])

    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = run["runId"] as String,
        taskId = task.id,
        turn = 1,
        text = "Continuing after approval",
        isFinal = false,
        stage = "resume",
        emittedAtEpochMs = 1_002L,
      ),
    )

    assertNull(promptCheckpointStore.get(task.id))
  }

  @Test
  fun approveChatApprovalUsesResumeToolNameForGrantButKeepsDisplayToolName() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-resume-tool-name"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val approvalRegistry = AgentTaskApprovalRegistry()
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val promptCheckpointStore = promptCheckpointStoreFactory.forChatSession(activeSessionId)
    val replayCalls = mutableListOf<Map<String, Any?>>()
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 1,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      approvalRegistry = approvalRegistry,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      approvalApprovedReplayRecorder = { sessionId, taskId, runId, toolName, isHighRisk, _ ->
        replayCalls += mapOf(
          "sessionId" to sessionId,
          "taskId" to taskId,
          "runId" to runId,
          "toolName" to toolName,
          "isHighRisk" to isHighRisk,
        )
      },
    )

    val run = hostRuntime.submitChatMessage("Need skills approval")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "HIGH_RISK_APPROVAL_REQUIRED",
        errorMessage = "Approval is required before SkillsFind can access the remote skills service.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata +
          OpenCrayPromptResumeMetadata.encodeToMetadata(resumeState, json) +
          mapOf(
            "normalizedToolName" to "SkillsFind",
            OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME to "WebFetch",
          ),
      ),
    )

    val pendingApproval = ((hostRuntime.loadChatSnapshot()["pendingApprovals"] as List<*>).single()) as Map<*, *>
    assertEquals("SkillsFind", pendingApproval["toolName"])

    hostRuntime.approveChatApproval(run["runId"] as String)

    val approvalGrant = approvalRegistry.consumeApproved(activeSessionId, task.id)
    val checkpoint = requireNotNull(promptCheckpointStore.get(task.id))

    assertEquals(listOf(task.id), handle.resumedTaskIds)
    assertEquals("WebFetch", approvalGrant?.toolName)
    assertEquals(resumeState, approvalGrant?.promptResumeState)
    assertEquals("WebFetch", checkpoint.toolName)
    assertEquals(
      listOf(
        mapOf(
          "sessionId" to activeSessionId,
          "taskId" to task.id,
          "runId" to (run["runId"] as String),
          "toolName" to "SkillsFind",
          "isHighRisk" to true,
        ),
      ),
      replayCalls,
    )
  }

  @Test
  fun approveChatApprovalForSessionPersistsSessionGrantAndUsesResumeToolName() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-session-scope"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val approvalRegistry = AgentTaskApprovalRegistry()
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val promptCheckpointStore = promptCheckpointStoreFactory.forChatSession(activeSessionId)
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 1,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      approvalRegistry = approvalRegistry,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    val run = hostRuntime.submitChatMessage("Need provider-native search approval")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before WebSearch can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata +
          OpenCrayPromptResumeMetadata.encodeToMetadata(resumeState, json) +
          mapOf(
            "normalizedToolName" to "WebSearch",
            OpenCrayExecutionMetadataKeys.APPROVAL_RESUME_TOOL_NAME to
              ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
            ProviderNativeWebSearchSupport.METADATA_APPROVAL_KIND to
              ProviderNativeWebSearchSupport.APPROVAL_KIND,
            ProviderNativeWebSearchSupport.METADATA_SUPPORTS_SESSION_APPROVAL to "true",
          ),
      ),
    )

    val pendingApproval = ((hostRuntime.loadChatSnapshot()["pendingApprovals"] as List<*>).single()) as Map<*, *>
    assertEquals("WebSearch", pendingApproval["toolName"])
    assertEquals(true, pendingApproval["supportsSessionApproval"])
    assertEquals("Allow session", pendingApproval["approveForSessionLabel"])

    hostRuntime.approveChatApprovalForSession(run["runId"] as String)

    val snapshot = hostRuntime.loadChatSnapshot()
    val runtimeActivity = snapshot["runtimeActivity"] as Map<*, *>
    val events = (runtimeActivity["events"] as List<*>).map { event -> event as Map<*, *> }
    val approvalResultEvent = events.last()
    val approvalGrant = approvalRegistry.consumeApproved(activeSessionId, task.id)
    val checkpoint = requireNotNull(promptCheckpointStore.get(task.id))
    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertTrue(chatStore.isNativeWebSearchSessionApproved(activeSessionId))
    assertEquals(listOf(task.id), handle.resumedTaskIds)
    assertEquals(
      ProviderNativeWebSearchSupport.RESUME_TOOL_NAME,
      approvalGrant?.toolName,
    )
    assertEquals(resumeState, approvalGrant?.promptResumeState)
    assertEquals(PromptCheckpointKind.APPROVED_PENDING_RESUME, checkpoint.checkpointKind)
    assertEquals(ProviderNativeWebSearchSupport.RESUME_TOOL_NAME, checkpoint.toolName)
    assertEquals(listOf("approval_wait", "approval_result"), events.map { it["kind"] })
    assertEquals("approved", approvalResultEvent["status"])
    assertEquals("WebSearch", approvalResultEvent["toolName"])
    assertEquals(
      "Approval granted for this session. The agent is resuming.",
      approvalResultEvent["text"],
    )
    assertTrue((snapshot["pendingApprovals"] as List<*>).isEmpty())
    assertEquals(
      listOf(
        "Need provider-native search approval",
        "Thinking",
        "Approval granted for this session. The agent is resuming.",
      ),
      messages.map { it.text },
    )
  }

  @Test
  fun successfulToolResultEventPersistsToolResultCheckpoint() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-general-resume-checkpoint"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val promptCheckpointStore = promptCheckpointStoreFactory.forChatSession(activeSessionId)
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 2,
      toolCallCount = 1,
      responsesPreviousResponseId = "resp_general",
      responsesProviderLineageId = "lineage_general",
      responsesLineageTrusted = true,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    hostRuntime.submitChatMessage("Checkpoint the run")
    val task = handle.submittedTasks.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: task.id,
        taskId = task.id,
        turn = 1,
        call = AgentToolCall(toolName = "LS"),
        result = AgentToolResult(
          toolName = "LS",
          status = AgentToolResultStatus.SUCCESS,
          content = "Listed 1 entry.",
          metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
            state = resumeState,
            json = json,
            checkpointBoundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
          ),
        ),
        emittedAtEpochMs = 2_000L,
      ),
    )

    val checkpoint = promptCheckpointStore.get(task.id)
    assertEquals(PromptCheckpointKind.TOOL_RESULT_COMMITTED, checkpoint?.checkpointKind)
    assertEquals("LS", checkpoint?.toolName)
    assertEquals(OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED, checkpoint?.promptCheckpointBoundary)
    assertEquals(resumeState, checkpoint?.promptResumeState)
  }

  @Test
  fun failedToolResultEventAlsoPersistsToolResultCheckpoint() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-general-resume-failed"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val promptCheckpointStore = promptCheckpointStoreFactory.forChatSession(activeSessionId)
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 2,
      toolCallCount = 1,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    hostRuntime.submitChatMessage("Checkpoint after a failed tool result")
    val task = handle.submittedTasks.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: task.id,
        taskId = task.id,
        turn = 1,
        call = AgentToolCall(toolName = "Read"),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.FAILED,
          content = "Missing file.",
          errorCode = "FILE_NOT_FOUND",
          errorMessage = "missing.txt was not found.",
          metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
            state = resumeState,
            json = json,
            checkpointBoundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
          ),
        ),
        emittedAtEpochMs = 2_000L,
      ),
    )

    val checkpoint = promptCheckpointStore.get(task.id)
    assertEquals(PromptCheckpointKind.TOOL_RESULT_COMMITTED, checkpoint?.checkpointKind)
    assertEquals("Read", checkpoint?.toolName)
    assertEquals(OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED, checkpoint?.promptCheckpointBoundary)
    assertEquals(resumeState, checkpoint?.promptResumeState)
  }

  @Test
  fun supplementEventAlsoPersistsSupplementCheckpoint() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-general-resume-supplement"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val promptCheckpointStore = promptCheckpointStoreFactory.forChatSession(activeSessionId)
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 2,
      toolCallCount = 1,
      transcript = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Supplement checkpoint",
        ),
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    hostRuntime.submitChatMessage("Checkpoint after supplement")
    val task = handle.submittedTasks.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCraySupplementEvent(
        runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: task.id,
        taskId = task.id,
        turn = 1,
        entryId = "supplement-1",
        text = "Supplement checkpoint",
        checkpoint = "post_tool_pre_model",
        metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
          state = resumeState,
          json = json,
          checkpointBoundary = OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED,
        ),
        emittedAtEpochMs = 2_000L,
      ),
    )

    val checkpoint = promptCheckpointStore.get(task.id)
    assertEquals(PromptCheckpointKind.SUPPLEMENT_INGESTED, checkpoint?.checkpointKind)
    assertEquals(null, checkpoint?.toolName)
    assertEquals(OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED, checkpoint?.promptCheckpointBoundary)
    assertEquals(resumeState, checkpoint?.promptResumeState)

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val supplement = (runtimeActivity["events"] as List<*>)
      .filterIsInstance<Map<*, *>>()
      .first { event -> event["kind"] == "supplement" }
    assertEquals(true, supplement["hasResumeCheckpointMetadata"])
    assertFalse(supplement.containsKey("metadata"))
  }

  @Test
  fun chatSnapshotIgnoresCheckpointJournalTailWhenRecoveringActiveRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-checkpoint-tail"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val runEventJournalStoreFactory = hostRuntimeTestRunEventJournalStoreFactory()
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 0,
      transcript = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Checkpoint tail",
        ),
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
    )

    hostRuntime.submitChatMessage("Ignore the checkpoint tail")
    val task = handle.submittedTasks.single()
    val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: task.id
    val journalStore = runEventJournalStoreFactory.forChatSession(activeSessionId)
    journalStore.append(
      OpenCraySupplementEvent(
        runId = runId,
        taskId = task.id,
        turn = 1,
        entryId = "supplement-tail-safe",
        text = "Checkpoint tail should not poison the host snapshot.",
        checkpoint = "post_tool_pre_model",
        metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
          state = resumeState,
          json = json,
          checkpointBoundary = OpenCrayPromptCheckpointBoundary.SUPPLEMENT_INGESTED,
        ),
        emittedAtEpochMs = 2_000L,
      ),
    )
    journalStore.appendCheckpoint(
      runId = runId,
      taskId = task.id,
      emission = OpenCrayPromptCheckpointEmission(
        boundary = OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST,
        state = resumeState,
        emittedAtEpochMs = 2_001L,
      ),
    )

    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val runtimeActivity = chatSnapshot["runtimeActivity"] as Map<*, *>
    val activeRun = (runtimeActivity["activeRuns"] as List<*>).single() as Map<*, *>
    val lastEvent = activeRun["lastEvent"] as Map<*, *>

    assertEquals("supplement", lastEvent["kind"])
    assertEquals(
      "Checkpoint tail should not poison the host snapshot.",
      lastEvent["text"],
    )
  }

  @Test
  fun assistantProgressEventPersistsProgressCheckpoint() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-progress-checkpoint"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val promptCheckpointStore = promptCheckpointStoreFactory.forChatSession(activeSessionId)
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 2,
      toolCallCount = 1,
      transcript = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Keep going",
        ),
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    hostRuntime.submitChatMessage("Emit progress checkpoint")
    val task = handle.submittedTasks.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: task.id,
        taskId = task.id,
        turn = 1,
        text = "Working through the next step",
        stage = "analysis",
        metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
          state = resumeState,
          json = json,
          checkpointBoundary = OpenCrayPromptCheckpointBoundary.COMMENTARY_EMITTED,
        ),
        emittedAtEpochMs = 2_000L,
      ),
    )

    val checkpoint = promptCheckpointStore.get(task.id)
    assertEquals(PromptCheckpointKind.COMMENTARY_EMITTED, checkpoint?.checkpointKind)
    assertEquals(null, checkpoint?.toolName)
    assertEquals(OpenCrayPromptCheckpointBoundary.COMMENTARY_EMITTED, checkpoint?.promptCheckpointBoundary)
    assertEquals(resumeState, checkpoint?.promptResumeState)

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val assistantProgress = (runtimeActivity["events"] as List<*>)
      .filterIsInstance<Map<*, *>>()
      .first { event ->
        event["kind"] == "assistant_phase" && event["text"] == "Working through the next step"
      }
    assertEquals(true, assistantProgress["hasResumeCheckpointMetadata"])
  }

  @Test
  fun terminalTaskFinishClearsGeneralResumeCheckpoint() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-general-resume-cleared"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val promptCheckpointStore = promptCheckpointStoreFactory.forChatSession(activeSessionId)
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val resumeState = OpenCrayPromptResumeState(
      turnIndex = 2,
      toolCallCount = 1,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    hostRuntime.submitChatMessage("Checkpoint and then finish")
    val task = handle.submittedTasks.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: task.id,
        taskId = task.id,
        turn = 1,
        call = AgentToolCall(toolName = "LS"),
        result = AgentToolResult(
          toolName = "LS",
          status = AgentToolResultStatus.SUCCESS,
          content = "Listed 1 entry.",
          metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(resumeState, json),
        ),
        emittedAtEpochMs = 2_000L,
      ),
    )

    assertEquals(PromptCheckpointKind.GENERAL_RESUME, promptCheckpointStore.get(task.id)?.checkpointKind)

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Final answer.",
        startedAtEpochMs = 2_001L,
        finishedAtEpochMs = 2_010L,
        metadata = task.metadata,
      ),
    )

    assertNull(promptCheckpointStore.get(task.id))
  }

  @Test
  fun approveDelegatedChildApprovalRetainsParentAndChildResumeState() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-approval-resume"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val approvalRegistry = AgentTaskApprovalRegistry()
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val promptCheckpointStore = promptCheckpointStoreFactory.forChatSession(activeSessionId)
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val parentPromptResumeState = OpenCrayPromptResumeState(
      turnIndex = 1,
      toolCallCount = 1,
    )
    val childPromptResumeState = OpenCrayPromptResumeState(
      turnIndex = 0,
      toolCallCount = 1,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      approvalRegistry = approvalRegistry,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    val run = hostRuntime.submitChatMessage("Need delegated approval")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Read can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata +
          OpenCrayPromptResumeMetadata.encodeToMetadata(parentPromptResumeState, json) +
          com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata.encodeToMetadata(
            com.opencray.runtime.subagent.SubAgentApprovalResume(
              approvedToolName = "Read",
              promptResumeState = childPromptResumeState,
              agentId = "child-agent-1",
              childRunId = "child-run-1",
              childTaskId = "child-task-1",
            ),
            json,
          ) +
          mapOf(
            "normalizedToolName" to "Read",
            "primaryTargetPath" to "/external/notes.txt",
            "delegationPromptPreview" to "Read the external notes file and summarize it.",
            "delegationAllowedTools" to "Glob,Grep,LS,Read",
          ),
      ),
    )

    val pendingApprovals = hostRuntime.loadChatSnapshot()["pendingApprovals"] as List<*>
    val pendingApproval = pendingApprovals.single() as Map<*, *>
    assertEquals("Read", pendingApproval["toolName"])

    hostRuntime.approveChatApproval(run["runId"] as String)

    val approvalGrant = approvalRegistry.consumeApproved(activeSessionId, task.id)
    val checkpoint = requireNotNull(promptCheckpointStore.get(task.id))
    assertEquals(listOf(task.id), handle.resumedTaskIds)
    assertEquals("Read", approvalGrant?.toolName)
    assertEquals(parentPromptResumeState, approvalGrant?.promptResumeState)
    assertEquals("Read", approvalGrant?.subAgentApprovalResume?.approvedToolName)
    assertEquals(childPromptResumeState, approvalGrant?.subAgentApprovalResume?.promptResumeState)
    assertEquals("child-agent-1", approvalGrant?.subAgentApprovalResume?.agentId)
    assertEquals("child-run-1", approvalGrant?.subAgentApprovalResume?.childRunId)
    assertEquals("child-task-1", approvalGrant?.subAgentApprovalResume?.childTaskId)
    assertEquals("child-agent-1", checkpoint.subAgentAgentId)
    assertEquals("child-run-1", checkpoint.subAgentChildRunId)
    assertEquals("child-task-1", checkpoint.subAgentChildTaskId)
    assertTrue(hostRuntime.loadChatSnapshot()["pendingApprovals"] is List<*>)
    assertTrue((hostRuntime.loadChatSnapshot()["pendingApprovals"] as List<*>).isEmpty())
  }

  @Test
  fun approveDelegatedChildApprovalEmitsResumedSubagentEvent() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-approval-approve"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val replayedSubAgentEvents = mutableListOf<OpenCraySubAgentEvent>()
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      subAgentReplayRecorder = { _, event ->
        replayedSubAgentEvents += event
      },
    )

    val run = hostRuntime.submitChatMessage("Need delegated approval")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Read can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata +
          OpenCrayPromptResumeMetadata.encodeToMetadata(
            OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1),
            json,
          ) +
          com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata.encodeToMetadata(
            com.opencray.runtime.subagent.SubAgentApprovalResume(
              approvedToolName = "Read",
              promptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
            ),
            json,
          ) +
          mapOf(
            "normalizedToolName" to "Read",
            "childRunId" to "child-run-approve",
            "childTaskId" to "child-task-approve",
            "subagentType" to "researcher",
            "subagentContextMode" to "minimal",
            "subagentDepth" to "1",
            "delegationDescription" to "Inspect external notes",
          ),
      ),
    )

    hostRuntime.approveChatApproval(run["runId"] as String)

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val activeRuns = runtimeActivity["activeRuns"] as List<*>
    val activeRun = activeRuns.single() as Map<*, *>
    val lastEvent = activeRun["lastEvent"] as Map<*, *>
    val events = (runtimeActivity["events"] as List<*>).map { event -> event as Map<*, *> }
    val subAgents = (runtimeActivity["subAgents"] as List<*>).map { event -> event as Map<*, *> }
    val subagentEvent = events.last { event ->
      event["kind"] == "subagent" && event["phase"] == "resumed"
    }
    val subagentRegistryEntry = subAgents.single()

    assertEquals(listOf(task.id), handle.resumedTaskIds)
    assertEquals(run["runId"], activeRun["runId"])
    assertEquals("subagent", lastEvent["kind"])
    assertEquals("resumed", lastEvent["phase"])
    assertEquals("running", lastEvent["status"])
    assertEquals("child-run-approve", lastEvent["childRunId"])
    assertEquals(1, replayedSubAgentEvents.size)
    assertEquals(OpenCraySubAgentPhase.RESUMED, replayedSubAgentEvents.single().phase)
    assertEquals("child-run-approve", replayedSubAgentEvents.single().childRunId)
    assertEquals("subagent", subagentEvent["kind"])
    assertEquals("resumed", subagentEvent["phase"])
    assertEquals("running", subagentEvent["status"])
    assertEquals(false, subagentEvent["resumable"])
    assertEquals(false, subagentEvent["requiresUserAction"])
    assertEquals("child-run-approve", subagentRegistryEntry["childRunId"])
    assertEquals("resumed", subagentRegistryEntry["phase"])
    assertEquals("running", subagentRegistryEntry["status"])
    assertEquals(false, subagentRegistryEntry["resumable"])
    assertEquals(
      "Delegated child approval granted. The child will continue.",
      subagentRegistryEntry["summary"],
    )
    assertEquals(
      "Delegated child approval granted. The child will continue.",
      subagentEvent["text"],
    )
  }

  @Test
  fun rejectDelegatedChildApprovalEmitsTerminalSubagentEvent() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-approval-reject"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val replayedSubAgentEvents = mutableListOf<OpenCraySubAgentEvent>()
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      subAgentReplayRecorder = { _, event ->
        replayedSubAgentEvents += event
      },
    )

    val run = hostRuntime.submitChatMessage("Need delegated approval")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Read can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata +
          OpenCrayPromptResumeMetadata.encodeToMetadata(
            OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1),
            json,
          ) +
          com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata.encodeToMetadata(
            com.opencray.runtime.subagent.SubAgentApprovalResume(
              approvedToolName = "Read",
              promptResumeState = OpenCrayPromptResumeState(turnIndex = 0, toolCallCount = 1),
            ),
            json,
          ) +
          mapOf(
            "normalizedToolName" to "Read",
            "childRunId" to "child-run-1",
            "childTaskId" to "child-task-1",
            "subagentType" to "researcher",
            "subagentContextMode" to "minimal",
            "subagentDepth" to "1",
            "delegationDescription" to "Inspect external notes",
          ),
      ),
    )

    hostRuntime.rejectChatApproval(run["runId"] as String)

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val activeRuns = runtimeActivity["activeRuns"] as List<*>
    val retainedRuns = runtimeActivity["retainedRuns"] as List<*>
    val retainedRun = retainedRuns.single() as Map<*, *>
    val lastEvent = retainedRun["lastEvent"] as Map<*, *>
    val events = (runtimeActivity["events"] as List<*>).map { event -> event as Map<*, *> }
    val subAgents = (runtimeActivity["subAgents"] as List<*>).map { event -> event as Map<*, *> }
    val subagentEvent = events.last { event -> event["kind"] == "subagent" }
    val subagentSnapshot = subAgents.single()

    assertEquals(listOf(task.id), handle.cancelledTaskIds)
    assertTrue(handle.resumedTaskIds.isEmpty())
    assertTrue(activeRuns.isEmpty())
    assertEquals(1, retainedRuns.size)
    assertEquals(run["runId"], retainedRun["runId"])
    assertEquals("approval_result", lastEvent["kind"])
    assertEquals("rejected", lastEvent["status"])
    assertEquals("Read", lastEvent["toolName"])
    assertEquals(1, replayedSubAgentEvents.size)
    assertEquals("child-run-1", replayedSubAgentEvents.single().childRunId)
    assertEquals(OpenCraySubAgentPhase.CANCELLED, replayedSubAgentEvents.single().phase)
    assertEquals("subagent", subagentEvent["kind"])
    assertEquals("cancelled", subagentEvent["phase"])
    assertEquals("cancelled", subagentEvent["status"])
    assertEquals("child-run-1", subagentEvent["childRunId"])
    assertEquals("child-task-1", subagentEvent["childTaskId"])
    assertEquals("Inspect external notes", subagentEvent["label"])
    assertEquals("researcher", subagentEvent["subagentType"])
    assertEquals("minimal", subagentEvent["contextMode"])
    assertEquals(false, subagentEvent["resumable"])
    assertEquals(false, subagentEvent["requiresUserAction"])
    assertEquals("child-run-1", subagentSnapshot["childRunId"])
    assertEquals("cancelled", subagentSnapshot["phase"])
    assertEquals("cancelled", subagentSnapshot["status"])
    assertEquals(false, subagentSnapshot["resumable"])
    assertEquals(
      "Delegated child approval rejected. The child run was stopped.",
      subagentEvent["text"],
    )
    assertEquals(
      "Delegated child approval rejected. The child run was stopped.",
      subagentSnapshot["summary"],
    )
  }

  @Test
  fun rejectChatApprovalHidesPendingApprovalWithoutRetry() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-reject"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val replayCalls = mutableListOf<Map<String, Any?>>()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      approvalReplayRecorder = { sessionId, taskId, runId, toolName, isHighRisk, _ ->
        replayCalls += mapOf(
          "sessionId" to sessionId,
          "taskId" to taskId,
          "runId" to runId,
          "toolName" to toolName,
          "isHighRisk" to isHighRisk,
        )
      },
    )

    val run = hostRuntime.submitChatMessage("Need approval")!!
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
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Write",
        ),
      ),
    )

    hostRuntime.rejectChatApproval(run["runId"] as String)

    val snapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = snapshot["pendingApprovals"] as List<*>
    val runtimeActivity = snapshot["runtimeActivity"] as Map<*, *>
    val events = (runtimeActivity["events"] as List<*>).map { event -> event as Map<*, *> }
    val approvalResultEvent = events.last()
    val composerPlaceholder = snapshot["composerPlaceholder"]
    val drawer = snapshot["drawer"] as Map<*, *>
    val drawerSession = ((drawer["sessions"] as List<*>).single()) as Map<*, *>
    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertEquals(listOf(task.id), handle.cancelledTaskIds)
    assertTrue(handle.resumedTaskIds.isEmpty())
    assertTrue(pendingApprovals.isEmpty())
    assertEquals(
      listOf(
        mapOf(
          "sessionId" to activeSessionId,
          "taskId" to task.id,
          "runId" to (run["runId"] as String),
          "toolName" to "Write",
          "isHighRisk" to false,
        ),
      ),
      replayCalls,
    )
    assertEquals(listOf("approval_wait", "approval_result"), events.map { it["kind"] })
    assertEquals("rejected", approvalResultEvent["status"])
    assertEquals("Write", approvalResultEvent["toolName"])
    assertEquals(false, approvalResultEvent["isHighRisk"])
    assertEquals("Approval rejected. The requested action was not run.", approvalResultEvent["text"])
    assertEquals("Tell OpenCray differently", composerPlaceholder)
    assertEquals(
      "Waiting for your next instruction.",
      (snapshot["summary"] as Map<*, *>)["body"],
    )
    assertEquals("Waiting for your next instruction.", drawerSession["preview"])
    assertEquals(
      listOf(
        "Need approval",
        "Thinking",
        "Approval rejected. The requested action was not run.",
      ),
      messages.map { it.text },
    )
  }

  @Test
  fun interruptChatRunWhileDelegatedChildApprovalIsPendingKeepsApprovalAvailable() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-approval-cancel"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val replayedSubAgentEvents = mutableListOf<OpenCraySubAgentEvent>()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      subAgentReplayRecorder = { _, event ->
        replayedSubAgentEvents += event
      },
    )

    val run = hostRuntime.submitChatMessage("Need delegated approval")!!
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.DENIED,
        errorCode = "APPROVAL_REQUIRED",
        errorMessage = "Approval is required before Read can run.",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Read",
          "childRunId" to "child-run-2",
          "childTaskId" to "child-task-2",
          "subagentType" to "researcher",
          "subagentContextMode" to "minimal",
          "subagentDepth" to "1",
          "delegationDescription" to "Inspect external notes",
        ),
      ),
    )

    hostRuntime.interruptChatRun(run["runId"] as String)

    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val pendingApprovals = chatSnapshot["pendingApprovals"] as List<*>
    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val events = (runtimeActivity["events"] as List<*>).map { event -> event as Map<*, *> }
    val cancellationEvent = events.last { event -> event["kind"] == "interrupted" }

    assertTrue(handle.cancelledTaskIds.isEmpty())
    assertEquals(1, pendingApprovals.size)
    assertTrue(replayedSubAgentEvents.isEmpty())
    assertEquals(
      "Approval required before the agent can continue.",
      (chatSnapshot["summary"] as Map<*, *>)["body"],
    )
    assertEquals("interrupted", cancellationEvent["kind"])
    assertEquals("Read", cancellationEvent["toolName"])
  }

  @Test
  fun approveChatApprovalAfterInterruptDefersResumeUntilUserRestartsRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-deferred-approve"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val promptCheckpointStore = promptCheckpointStoreFactory.forChatSession(activeSessionId)
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    val run = hostRuntime.submitChatMessage("Need approval")!!
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
        metadata = task.metadata +
          OpenCrayPromptResumeMetadata.encodeToMetadata(
            OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1),
            json,
          ) +
          mapOf("normalizedToolName" to "Write"),
      ),
    )

    hostRuntime.interruptChatRun(run["runId"] as String)
    hostRuntime.approveChatApproval(run["runId"] as String)

    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val runtimeActivity = chatSnapshot["runtimeActivity"] as Map<*, *>
    val activeRuns = runtimeActivity["activeRuns"] as List<*>
    val activeRun = activeRuns.single() as Map<*, *>
    val lastEvent = activeRun["lastEvent"] as Map<*, *>
    val recoveryPlan = activeRun["recoveryPlan"] as Map<*, *>
    val messages = chatStore.loadState().activeSession.messages
      .filter { message -> message.role != ChatTranscriptRole.SYSTEM }

    assertTrue(handle.resumedTaskIds.isEmpty())
    assertTrue(handle.cancelledTaskIds.isEmpty())
    assertTrue((chatSnapshot["pendingApprovals"] as List<*>).isEmpty())
    assertEquals(PromptCheckpointKind.APPROVED_PENDING_RESUME, promptCheckpointStore.get(task.id)?.checkpointKind)
    assertEquals("approval_result", lastEvent["kind"])
    assertEquals("approved", lastEvent["status"])
    assertEquals(
      "Approval granted. The decision is recorded and will apply when you manually resume the run.",
      lastEvent["text"],
    )
    assertEquals("resume_waiting_for_user", recoveryPlan["action"])
    assertEquals("approved_pending_resume", recoveryPlan["checkpointKind"])
    assertEquals("Message OpenCray", chatSnapshot["composerPlaceholder"])
    assertEquals(
      "Waiting for your next instruction.",
      (chatSnapshot["summary"] as Map<*, *>)["body"],
    )
    assertEquals(
      listOf(
        "Need approval",
        "Approval is required before Write can run.",
        "Approval granted. The decision is recorded and will apply when you manually resume the run.",
      ),
      messages.map { it.text },
    )
  }

  @Test
  fun rejectChatApprovalAfterInterruptDefersApplicationUntilUserRestartsRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-deferred-reject"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val promptCheckpointStore = promptCheckpointStoreFactory.forChatSession(activeSessionId)
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    val run = hostRuntime.submitChatMessage("Need approval")!!
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
        metadata = task.metadata +
          OpenCrayPromptResumeMetadata.encodeToMetadata(
            OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1),
            json,
          ) +
          mapOf("normalizedToolName" to "Write"),
      ),
    )

    hostRuntime.interruptChatRun(run["runId"] as String)
    hostRuntime.rejectChatApproval(run["runId"] as String)

    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val runtimeActivity = chatSnapshot["runtimeActivity"] as Map<*, *>
    val activeRuns = runtimeActivity["activeRuns"] as List<*>
    val activeRun = activeRuns.single() as Map<*, *>
    val lastEvent = activeRun["lastEvent"] as Map<*, *>
    val recoveryPlan = activeRun["recoveryPlan"] as Map<*, *>

    assertTrue(handle.resumedTaskIds.isEmpty())
    assertTrue(handle.cancelledTaskIds.isEmpty())
    assertTrue((chatSnapshot["pendingApprovals"] as List<*>).isEmpty())
    assertEquals(PromptCheckpointKind.REJECTED_PENDING_RESUME, promptCheckpointStore.get(task.id)?.checkpointKind)
    assertEquals("approval_result", lastEvent["kind"])
    assertEquals("rejected", lastEvent["status"])
    assertEquals(
      "Approval rejected. The decision is recorded and will apply when you manually resume the run.",
      lastEvent["text"],
    )
    assertEquals("stop_rejected_awaiting_direction", recoveryPlan["action"])
    assertEquals("rejected_pending_resume", recoveryPlan["checkpointKind"])
    assertEquals("Message OpenCray", chatSnapshot["composerPlaceholder"])
  }

  @Test
  fun retryChatRunResumesDeferredApprovalDecisionWithoutCreatingNewRun() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-deferred-retry"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    val run = hostRuntime.submitChatMessage("Need approval")!!
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
        metadata = task.metadata +
          OpenCrayPromptResumeMetadata.encodeToMetadata(
            OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1),
            json,
          ) +
          mapOf("normalizedToolName" to "Write"),
      ),
    )

    hostRuntime.interruptChatRun(run["runId"] as String)
    hostRuntime.approveChatApproval(run["runId"] as String)
    hostRuntime.retryChatRun(run["runId"] as String)

    assertEquals(listOf(task.id), handle.resumedTaskIds)
    assertEquals(
      listOf(com.opencray.core.orchestrator.EXECUTION_KIND_APPROVAL_RESUME),
      handle.resumedExecutionKinds,
    )
    assertTrue(handle.retriedTaskIds.isEmpty())
  }

  @Test
  fun submitChatMessageQueuesFollowUpAndResumesDeferredApprovalDecision() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-deferred-message"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    val run = hostRuntime.submitChatMessage("Need approval")!!
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
        metadata = task.metadata +
          OpenCrayPromptResumeMetadata.encodeToMetadata(
            OpenCrayPromptResumeState(turnIndex = 1, toolCallCount = 1),
            json,
          ) +
          mapOf("normalizedToolName" to "Write"),
      ),
    )

    hostRuntime.interruptChatRun(run["runId"] as String)
    hostRuntime.approveChatApproval(run["runId"] as String)

    assertEquals(null, hostRuntime.submitChatMessage("Resume with this follow-up"))
    assertEquals(listOf(task.id), handle.resumedTaskIds)
    assertEquals(
      listOf(com.opencray.core.orchestrator.EXECUTION_KIND_APPROVAL_RESUME),
      handle.resumedExecutionKinds,
    )
    assertEquals(
      listOf("Resume with this follow-up"),
      chatStore.loadPendingUserInputs(activeSessionId).map { entry -> entry.text },
    )
  }

  @Test
  fun rejectChatApprovalStopsRunAndRetainsAwaitingDirectionState() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-approval-retained"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
    )

    val run = hostRuntime.submitChatMessage("Need approval")!!
    val task = handle.submittedTasks.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayToolResultEvent(
        runId = run["runId"] as String,
        taskId = task.id,
        turn = 0,
        call = AgentToolCall(toolName = "Read"),
        result = AgentToolResult(
          toolName = "Read",
          status = AgentToolResultStatus.SUCCESS,
          content = "Read README.md:1-10",
        ),
        emittedAtEpochMs = 1_000L,
      ),
    )
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
        metadata = task.metadata + mapOf(
          "normalizedToolName" to "Write",
        ),
      ),
    )

    hostRuntime.rejectChatApproval(run["runId"] as String)

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val activeRuns = runtimeActivity["activeRuns"] as List<*>
    val retainedRuns = runtimeActivity["retainedRuns"] as List<*>
    val retainedRun = retainedRuns.single() as Map<*, *>
    val lastEvent = retainedRun["lastEvent"] as Map<*, *>
    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val composerPlaceholder = chatSnapshot["composerPlaceholder"]
    val drawer = chatSnapshot["drawer"] as Map<*, *>
    val drawerSession = ((drawer["sessions"] as List<*>).single()) as Map<*, *>

    assertEquals(listOf(task.id), handle.cancelledTaskIds)
    assertTrue(handle.resumedTaskIds.isEmpty())
    assertTrue(activeRuns.isEmpty())
    assertEquals(1, retainedRuns.size)
    assertEquals(run["runId"], retainedRun["runId"])
    assertEquals(task.id, retainedRun["taskId"])
    assertEquals(false, retainedRun["isActive"])
    assertEquals(true, retainedRun["isTerminal"])
    assertEquals("approval_result", lastEvent["kind"])
    assertEquals("rejected", lastEvent["status"])
    assertEquals("Write", lastEvent["toolName"])
    assertEquals(false, lastEvent["isHighRisk"])
    assertEquals("Tell OpenCray differently", composerPlaceholder)
    assertEquals(
      "Waiting for your next instruction.",
      (chatSnapshot["summary"] as Map<*, *>)["body"],
    )
    assertEquals("Waiting for your next instruction.", drawerSession["preview"])
  }

  @Test
  fun interruptChatRunInterruptsTaskAndRecordsReplayObservation() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-cancel"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val replayCalls = mutableListOf<Map<String, String?>>()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      runCancellationReplayRecorder = { sessionId, taskId, runId, toolName, _ ->
        replayCalls += mapOf(
          "sessionId" to sessionId,
          "taskId" to taskId,
          "runId" to runId,
          "toolName" to toolName,
        )
      },
    )

    val run = hostRuntime.submitChatMessage("Cancel this run")!!

    hostRuntime.interruptChatRun(run["runId"] as String)

    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val runtimeActivity = chatSnapshot["runtimeActivity"] as Map<*, *>
    val cancelledEvent = ((runtimeActivity["events"] as List<*>).single()) as Map<*, *>
    val drawer = chatSnapshot["drawer"] as Map<*, *>
    val drawerSession = ((drawer["sessions"] as List<*>).single()) as Map<*, *>

    assertEquals(listOf(handle.submissions.single().taskId), handle.cancelledTaskIds)
    assertEquals(
      listOf(
        mapOf(
          "sessionId" to activeSessionId,
          "taskId" to handle.submissions.single().taskId,
          "runId" to (run["runId"] as String),
          "toolName" to null,
        ),
      ),
      replayCalls,
    )
    assertEquals("interrupted", cancelledEvent["kind"])
    assertEquals(run["runId"], cancelledEvent["runId"])
    assertEquals(handle.submissions.single().taskId, cancelledEvent["taskId"])
    assertEquals("user_interrupted", cancelledEvent["status"])
    assertEquals(
      "Run interrupted. The agent is waiting for your next instruction.",
      cancelledEvent["text"],
    )
    assertEquals("Tell OpenCray differently", chatSnapshot["composerPlaceholder"])
    assertEquals(
      "Waiting for your next instruction.",
      ((chatSnapshot["summary"] as Map<*, *>)["body"]),
    )
    assertEquals("Waiting for your next instruction.", drawerSession["preview"])
  }

  @Test
  fun interruptedRestoreRunIsRetainedAndRequiresExplicitRetry() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-retry"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      retryResult = true,
    )
    handle.putRunSnapshot(
      AgentRunSnapshot(
        sessionId = activeSessionId,
        runId = "run-interrupted",
        taskId = "task-interrupted",
        acceptedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_050L,
        lifecycleState = QueueTaskLifecycleState.FAILED,
        taskState = AgentTaskState.FAILED,
        attempt = 1,
        errorCode = ERROR_RESTART_REQUIRES_EXPLICIT_RETRY,
        errorMessage = "Retry explicitly when you want to continue.",
        pendingMessageId = "message-assistant-1",
      ),
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val activeRuns = runtimeActivity["activeRuns"] as List<*>
    val retainedRuns = runtimeActivity["retainedRuns"] as List<*>
    val retainedRun = retainedRuns.single() as Map<*, *>

    assertTrue(activeRuns.isEmpty())
    assertEquals(1, retainedRuns.size)
    assertEquals("run-interrupted", retainedRun["runId"])
    assertEquals(ERROR_RESTART_REQUIRES_EXPLICIT_RETRY, retainedRun["errorCode"])
    assertEquals(false, retainedRun["isActive"])
    assertEquals(true, retainedRun["isTerminal"])

    hostRuntime.retryChatRun("run-interrupted")

    assertEquals(listOf("task-interrupted"), handle.retriedTaskIds)
  }

  @Test
  fun pausedLlmRetryRunRemainsActiveAndUsesCheckpointResume() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-llm-pause-resume"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    handle.putRunSnapshot(
      AgentRunSnapshot(
        sessionId = activeSessionId,
        runId = "run-llm-paused",
        taskId = "task-llm-paused",
        acceptedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_050L,
        lifecycleState = QueueTaskLifecycleState.SUSPENDED,
        taskState = AgentTaskState.SUSPENDED,
        attempt = 1,
        errorCode = ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME,
        errorMessage = "Recoverable retries were exhausted.",
        pendingMessageId = "message-assistant-1",
      ),
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
    )

    val chatSnapshot = hostRuntime.loadChatSnapshot()
    val runtimeActivity = chatSnapshot["runtimeActivity"] as Map<*, *>
    val activeRuns = runtimeActivity["activeRuns"] as List<*>
    val activeRun = activeRuns.single() as Map<*, *>

    assertEquals(1, activeRuns.size)
    assertEquals("run-llm-paused", activeRun["runId"])
    assertEquals(true, activeRun["isActive"])
    assertEquals(false, activeRun["isTerminal"])
    assertEquals("Tell OpenCray differently", chatSnapshot["composerPlaceholder"])
    assertEquals(
      "Waiting for your next instruction.",
      ((chatSnapshot["summary"] as Map<*, *>)["body"]),
    )

    hostRuntime.retryChatRun("run-llm-paused")

    assertEquals(listOf("task-llm-paused"), handle.resumedTaskIds)
    assertEquals(
      listOf(com.opencray.core.orchestrator.EXECUTION_KIND_CHECKPOINT_RESUME),
      handle.resumedExecutionKinds,
    )
    assertTrue(handle.retriedTaskIds.isEmpty())
  }

  @Test
  fun submitChatMessageResumesPausedLlmRetryRunWithSameRunAndUpdatedCheckpoint() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-llm-pause-submit"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendSubmittedTurn(
      sessionId = activeSessionId,
      userText = "Initial prompt",
      assistantMessageId = "message-assistant-1",
      assistantPlaceholderText = "Paused",
    )
    val promptCheckpointStoreFactory = hostRuntimeTestPromptCheckpointStoreFactory()
    promptCheckpointStoreFactory.forChatSession(activeSessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = activeSessionId,
        runId = "run-llm-paused",
        taskId = "task-llm-paused",
        checkpointId = "checkpoint-llm-paused",
        checkpointKind = PromptCheckpointKind.GENERAL_RESUME,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        pendingMessageId = "message-assistant-1",
        promptResumeState = OpenCrayPromptResumeState(
          transcript = listOf(
            RuntimeConversationMessage(
              role = RuntimeConversationRole.USER,
              content = "Initial prompt",
            ),
            RuntimeConversationMessage(
              role = RuntimeConversationRole.ASSISTANT,
              content = "Paused",
            ),
          ),
          turnIndex = 0,
          toolCallCount = 0,
        ),
      ),
    )
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
      resumeResult = true,
    )
    handle.putRunSnapshot(
      AgentRunSnapshot(
        sessionId = activeSessionId,
        runId = "run-llm-paused",
        taskId = "task-llm-paused",
        acceptedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_050L,
        lifecycleState = QueueTaskLifecycleState.SUSPENDED,
        taskState = AgentTaskState.SUSPENDED,
        attempt = 1,
        errorCode = ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME,
        errorMessage = "Recoverable retries were exhausted.",
        pendingMessageId = "message-assistant-1",
      ),
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    val submission = hostRuntime.submitChatMessage("Follow up")!!

    val metadataUpdates = handle.resumedTaskMetadataUpdates.single()
    val resumedPendingMessageId =
      metadataUpdates[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
    val checkpoint = promptCheckpointStoreFactory.forChatSession(activeSessionId).get(
      "task-llm-paused",
    )
    val messages = chatStore.loadState().activeSession.messages.takeLast(2)

    assertEquals("run-llm-paused", submission["runId"])
    assertEquals("task-llm-paused", submission["taskId"])
    assertTrue(handle.submittedInputs.isEmpty())
    assertEquals(listOf("task-llm-paused"), handle.resumedTaskIds)
    assertEquals(
      listOf(com.opencray.core.orchestrator.EXECUTION_KIND_CHECKPOINT_RESUME),
      handle.resumedExecutionKinds,
    )
    assertEquals(
      resumedPendingMessageId,
      metadataUpdates[AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID],
    )
    assertTrue(resumedPendingMessageId?.isNotBlank() == true)
    assertEquals(resumedPendingMessageId, checkpoint?.pendingMessageId)
    assertEquals("Follow up", checkpoint?.promptResumeState?.transcript?.lastOrNull()?.content)
    assertEquals(ChatTranscriptRole.USER, messages.first().role)
    assertEquals("Follow up", messages.first().text)
    assertEquals(ChatTranscriptRole.ASSISTANT, messages.last().role)
    assertEquals(resumedPendingMessageId, messages.last().messageId)
    assertEquals("Thinking", messages.last().text)
  }

  @Test
  fun chatRuntimeSnapshotParsesReplayedApprovalAndCancellationEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-replayed-approval-cancel"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = "approval_approved task_id=task-approved run_id=run-approved tool_name=Write outcome=user_approved executed=false next_step=agent_resumed",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = "approval_rejected task_id=task-rejected run_id=run-rejected tool_name=Bash risk=high_risk outcome=user_rejected executed=false next_step=await_user_instruction",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = "run_interrupted task_id=task-cancelled run_id=run-cancelled tool_name=Read outcome=user_interrupted executed=false next_step=await_user_instruction",
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      transcriptMessagesProvider = { sessionId ->
        if (sessionId == activeSessionId) {
          transcriptMessages
        } else {
          emptyList()
        }
      },
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val events = (runtimeActivity["events"] as List<*>).map { event -> event as Map<*, *> }
    val approved = events[0]
    val rejected = events[1]
    val cancelled = events[2]

    assertEquals(activeSessionId, runtimeActivity["sessionId"])
    assertEquals(listOf("approval_result", "approval_result", "interrupted"), events.map { it["kind"] })
    assertEquals("approved", approved["status"])
    assertEquals("Write", approved["toolName"])
    assertEquals(false, approved["isHighRisk"])
    assertEquals("Approval granted. The agent is resuming.", approved["text"])
    assertEquals("rejected", rejected["status"])
    assertEquals("Bash", rejected["toolName"])
    assertEquals(true, rejected["isHighRisk"])
    assertEquals("Approval rejected. The requested action was not run.", rejected["text"])
    assertEquals("user_interrupted", cancelled["status"])
    assertEquals("Read", cancelled["toolName"])
    assertEquals(
      "Interrupted the pending Read request. The agent is waiting for your next instruction.",
      cancelled["text"],
    )
  }

  @Test
  fun taskFailureRepairsTerminalReplayObservation() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-terminal-repair"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      onResume = manager.resumedSessionIds::add,
    )
    manager.putHandle(handle)
    val repairCalls = mutableListOf<Pair<String, List<AgentRunSnapshot>>>()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      terminalReplayRepairer = { sessionId, runs ->
        repairCalls += sessionId to runs
      },
    )

    hostRuntime.submitChatMessage("Will fail")
    val task = handle.submittedTasks.single()
    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.FAILED,
        errorCode = "RUNTIME_EXCEPTION",
        errorMessage = "boom",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_001L,
        metadata = task.metadata,
      ),
    )

    assertEquals(activeSessionId, repairCalls.last().first)
    assertEquals(task.id, repairCalls.last().second.single().taskId)
  }

  @Test
  fun validateLlmConfigReturnsFacadePayloadForFlutterBridge() {
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-validation")),
      runtimeManager = RecordingRuntimeManager(),
      llmConfigFacade = RecordingLlmConfigFacade(
        validationResult = LlmValidationResult(
          isSuccess = true,
          message = "Connection verified for gpt-4o-mini.",
          agentCapability = LlmAgentCapabilitySnapshot(
            routeFingerprint = llmRouteFingerprint(
              protocol = LlmProviderProtocols.OPENAI,
              baseUrl = "https://api.openai.com/v1",
              model = "gpt-4o-mini",
            ),
            verifiedAtEpochMs = 1234L,
            visionInputSupported = true,
            pdfInputSupported = true,
            nativeToolCallingAvailable = true,
            toolChoiceSupported = true,
            parallelToolCallsSupported = true,
            strictToolSchemaSupported = true,
          ),
        ),
      ),
    )

    val payload = hostRuntime.validateLlmConfig(
      providerId = "openai",
      protocol = LlmProviderProtocols.OPENAI,
      baseUrl = "https://api.openai.com/v1",
      apiKey = "secret",
      model = "gpt-4o-mini",
      reasoningEffort = "medium",
    )

    assertEquals(true, payload["isSuccess"])
    assertEquals("Connection verified for gpt-4o-mini.", payload["message"])
    val capability = payload["agentCapability"] as Map<*, *>
    assertEquals(true, capability["visionInputSupported"])
    assertEquals(true, capability["nativeToolCallingAvailable"])
    assertEquals(true, capability["strictToolSchemaSupported"])
  }

  @Test
  fun validateLlmConfigDoesNotBlockSettingsOverviewLoads() {
    val validationStarted = CountDownLatch(1)
    val allowValidationToFinish = CountDownLatch(1)
    val facade = RecordingLlmConfigFacade(
      onValidate = {
        validationStarted.countDown()
        assertTrue(allowValidationToFinish.await(3, TimeUnit.SECONDS))
      },
    )
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-validation-lock")),
      runtimeManager = RecordingRuntimeManager(),
      llmConfigFacade = facade,
    )

    val validationThread = Thread {
      hostRuntime.validateLlmConfig(
        providerId = "custom",
        protocol = LlmProviderProtocols.ANTHROPIC,
        baseUrl = "https://api.anthropic.com",
        apiKey = "secret",
        model = "claude-3-7-sonnet",
        reasoningEffort = "medium",
      )
    }
    validationThread.start()
    assertTrue(validationStarted.await(1, TimeUnit.SECONDS))

    val overviewLoadedAtStartNs = System.nanoTime()
    val overview = hostRuntime.loadSettingsOverview()
    val overviewLoadDurationMs = TimeUnit.NANOSECONDS.toMillis(
      System.nanoTime() - overviewLoadedAtStartNs,
    )

    allowValidationToFinish.countDown()
    validationThread.join(3_000L)

    assertNotNull(overview)
    assertTrue(
      "Expected settings overview load to stay responsive during validation, but it took ${overviewLoadDurationMs}ms.",
      overviewLoadDurationMs < 500L,
    )
  }

  @Test
  fun saveCustomLlmProviderReturnsFacadePayloadForFlutterBridge() {
    val facade = RecordingLlmConfigFacade()
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-save-custom-provider")),
      runtimeManager = RecordingRuntimeManager(),
      llmConfigFacade = facade,
    )

    val payload = hostRuntime.saveCustomLlmProvider(
      selectedProviderOptionId = "custom",
      protocol = LlmProviderProtocols.ANTHROPIC,
      providerName = "Acme",
      providerNotes = "Regional fallback",
      baseUrl = "https://api.acme.example/v1",
      apiKey = "secret",
      model = "claude-3-7-sonnet",
      reasoningEffort = "high",
      systemPrompt = "Be concise.",
    )

    assertEquals("custom-saved", payload["selectedProviderOptionId"])
    assertEquals("custom", payload["providerId"])
    assertEquals("Regional fallback", payload["providerNotes"])
    assertEquals("Acme", facade.lastSavedCustomRequest?.providerName)
  }

  @Test
  fun savePersonalizationConfigReturnsFacadePayloadForFlutterBridge() {
    val personalizationFacade = RecordingPersonalizationFacade()
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-personalization")),
      runtimeManager = RecordingRuntimeManager(),
      personalizationFacade = personalizationFacade,
    )

    val payload = hostRuntime.savePersonalizationConfig(
      presetId = "warm",
      customLabel = "Night Shift",
      customGuidance = "Stay calm.",
    )

    assertEquals("Night Shift", payload["livePreviewName"])
    assertEquals("warm", payload["selectedPresetId"])
    assertEquals("Stay calm.", payload["customGuidance"])
    assertEquals(
      SavePersonalizationConfigRequest(
        presetId = "warm",
        customLabel = "Night Shift",
        customGuidance = "Stay calm.",
      ),
      personalizationFacade.lastSaveRequest,
    )
  }

  @Test
  fun networkSearchConfigRoundTripsForFlutterBridge() {
    val facade = LocalNetworkSearchConfigFacade.createForTest(
      WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore()),
    )
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-network-search")),
      runtimeManager = RecordingRuntimeManager(),
      networkSearchConfigFacade = facade,
    )

    val initialPayload = hostRuntime.loadNetworkSearchConfig()
    assertEquals("Network & Search", initialPayload["title"])
    assertEquals(0, (initialPayload["slots"] as List<*>).size)

    val savedPayload = hostRuntime.saveNetworkSearchConfig(
      slots = listOf(
        mapOf(
          "id" to "slot-primary",
          "providerId" to "openai_web_search",
          "label" to "Primary OpenAI Search",
          "baseUrl" to "https://proxy.example.com/v1",
          "model" to "gpt-5-mini",
          "apiKey" to "openai-secret",
          "enabled" to true,
        ),
        mapOf(
          "id" to "slot-backup",
          "providerId" to "tavily",
          "label" to "Backup Tavily",
          "baseUrl" to "https://ignored.example.com",
          "model" to "ignored-model",
          "apiKey" to "",
          "enabled" to false,
        ),
      ),
    )

    val savedSlots = (savedPayload["slots"] as List<*>).map { it as Map<*, *> }
    assertEquals(2, savedSlots.size)
    assertEquals("openai_web_search", savedSlots[0]["providerId"])
    assertEquals("Primary OpenAI Search", savedSlots[0]["label"])
    assertEquals("https://proxy.example.com/v1", savedSlots[0]["baseUrl"])
    assertEquals("gpt-5-mini", savedSlots[0]["model"])
    assertEquals(true, savedSlots[0]["enabled"])
    assertEquals("tavily", savedSlots[1]["providerId"])
    assertEquals("", savedSlots[1]["baseUrl"])
    assertEquals("", savedSlots[1]["model"])
    assertEquals(false, savedSlots[1]["enabled"])
  }

  @Test
  fun setAppLanguageReturnsUpdatedPersonalizationPayload() {
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-language")),
      runtimeManager = RecordingRuntimeManager(),
      personalizationFacade = RecordingPersonalizationFacade(),
    )

    val payload = hostRuntime.setAppLanguage("zh-CN")

    assertEquals("zh-CN", payload["selectedAppLanguageId"])
    val options = payload["appLanguageOptions"] as List<*>
    val selectedOption = options.map { it as Map<*, *> }
      .first { option -> option["isSelected"] == true }
    assertEquals("zh-CN", selectedOption["id"])
  }

  @Test
  fun setMcpServerEnabledReturnsFacadePayloadForFlutterBridge() {
    val mcpSettingsFacade = RecordingMcpSettingsFacade()
    val hostRuntime = hostRuntime(
      chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-mcp")),
      runtimeManager = RecordingRuntimeManager(),
      mcpSettingsFacade = mcpSettingsFacade,
    )

    val payload = hostRuntime.setMcpServerEnabled(
      serverId = "community-bridge",
      enabled = true,
    )

    assertEquals("Enabled 3 • Blocked 0 • Attention 1", payload["summaryLine"])
    assertEquals(
      "This page lists per-server status and actions. Today the runtime only exposes server visibility through mcp_list_servers; remote MCP tools are not proxied into the agent yet.",
      payload["serversHelper"],
    )
    val servers = payload["servers"] as List<*>
    val firstServer = servers.first() as Map<*, *>
    assertEquals("community-bridge", firstServer["id"])
    assertEquals("Exposure: Blocked", firstServer["exposureLine"])
    assertEquals(
      "Blocked until you enable this server manually. Exposure stays hidden until you consent here, and remote MCP tools are not proxied yet.",
      firstServer["guidance"],
    )
    assertEquals("Enable server", firstServer["actionLabel"])
    assertEquals("community-bridge" to true, mcpSettingsFacade.lastServerToggle)
  }

  @Test
  fun loadMemoryDebugSnapshotReturnsStructuredStoreRecords() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-debug"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-debug"),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-old",
        content = "Old workspace preference.",
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        tags = listOf("kind:user_preference", "scope:workspace"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "workspace",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.WORKSPACE_ID to "workspace=unavailable",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to "session-old",
          MemoryRecordExtensionKeys.TTL_MS to "1000",
          MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "1000",
        ),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_100L,
        tags = listOf("kind:user_preference", "scope:user"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE to "user_input",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to "durable",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )

    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )

    val payload = hostRuntime.loadMemoryDebugSnapshot()

    assertEquals(sessionId, payload["sessionId"])
    val records = payload["records"] as List<*>
    val firstRecord = records[0] as Map<*, *>
    val secondRecord = records[1] as Map<*, *>
    assertEquals("memory-user", firstRecord["id"])
    assertEquals("agent_display_name", firstRecord["preferenceKey"])
    assertEquals("Xiao Bai", firstRecord["preferenceValue"])
    assertEquals(false, firstRecord["isExpired"])
    assertEquals("memory-old", secondRecord["id"])
    assertEquals(true, secondRecord["isExpired"])
  }

  @Test
  fun searchMemoryDebugReturnsProjectedMatchesForVisibleRecords() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-search"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-search"),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_100L,
        tags = listOf("kind:user_preference", "scope:user"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )

    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )

    val payload = hostRuntime.searchMemoryDebug(query = "xiao bai")

    assertEquals(sessionId, payload["sessionId"])
    assertEquals("xiao bai", payload["query"])
    val queryTerms = payload["queryTerms"] as List<*>
    assertTrue(queryTerms.contains("xiao"))
    val results = payload["results"] as List<*>
    val firstResult = results.single() as Map<*, *>
    assertEquals("memory-user", firstResult["recordId"])
    assertEquals("user", firstResult["scope"])
    assertEquals("active", firstResult["status"])
    assertEquals(true, (firstResult["path"] as String).isNotBlank())
    assertEquals(true, (firstResult["snippet"] as String).contains("Xiao Bai"))
  }

  @Test
  fun getMemoryDebugSliceReturnsProjectedSnippetForRequestedPath() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-slice"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-slice"),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_100L,
        tags = listOf("kind:user_preference", "scope:user"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )

    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )

    val searchPayload = hostRuntime.searchMemoryDebug(query = "xiao")
    val path = ((searchPayload["results"] as List<*>).single() as Map<*, *>)["path"] as String
    val startLine = ((searchPayload["results"] as List<*>).single() as Map<*, *>)["startLine"] as Int
    val endLine = ((searchPayload["results"] as List<*>).single() as Map<*, *>)["endLine"] as Int

    val payload = hostRuntime.getMemoryDebugSlice(
      path = path,
      fromLine = startLine,
      lines = endLine - startLine + 1,
    )

    assertEquals(sessionId, payload["sessionId"])
    assertEquals(path, payload["path"])
    assertEquals(startLine, payload["startLine"])
    assertEquals(endLine, payload["endLine"])
    val recordIds = payload["recordIds"] as List<*>
    assertEquals(listOf("memory-user"), recordIds)
    assertEquals(true, (payload["text"] as String).contains("content: Call the agent Xiao Bai"))
  }

  @Test
  fun loadMemoryDebugLinksSnapshotReturnsSourceRecallRetrievalAndMaintenanceLinks() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-links"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-links"),
    )
    val runtimeManager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = sessionId)
    runtimeManager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      personalizationLocalStore = personalizationStore,
    )

    val sourceSubmission = handle.submitPrompt(
      userText = "Remember my preferred agent name.",
      pendingMessageId = "pending-memory-source",
      visibleThroughMessageId = "pending-memory-source",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      metadata = emptyMap(),
    )
    val sourceTask = handle.submittedTasks.last()
    handle.recordResult(
      task = sourceTask,
      result = ExecutionResult(
        taskId = sourceTask.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 2_100L,
        finishedAtEpochMs = 2_200L,
        metadata = mapOf("responseFormat" to "json_final"),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_200L,
        tags = listOf("kind:user_preference", "scope:user"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE to "user_input",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.SOURCE_TASK_ID to sourceTask.id,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY to "durable",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )
    runtimeManager.emitRunEvent(
      sessionId = sessionId,
      task = sourceTask,
      event = OpenCrayMemoryWriteEvent(
        runId = sourceSubmission.runId,
        taskId = sourceTask.id,
        writtenRecordIds = listOf("memory-user"),
        writtenKinds = listOf("user_preference"),
        emittedAtEpochMs = 2_200L,
      ),
    )

    val recallSubmission = handle.submitPrompt(
      userText = "What name should I use for the agent?",
      pendingMessageId = "pending-memory-recall",
      visibleThroughMessageId = "pending-memory-recall",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      metadata = emptyMap(),
    )
    val recallTask = handle.submittedTasks.last()
    handle.recordResult(
      task = recallTask,
      result = ExecutionResult(
        taskId = recallTask.id,
        status = ExecutionStatus.SUCCESS,
        stdout = "",
        startedAtEpochMs = 2_400L,
        finishedAtEpochMs = 2_500L,
        metadata = mapOf(
          "responseFormat" to "json_final",
          "contextMemorySelectedSummary" to "memory-user@420[chinese|name]",
          "contextMemoryFlushWrittenRecordIds" to "memory-user",
        ),
      ),
    )
    runtimeManager.emitRunEvent(
      sessionId = sessionId,
      task = recallTask,
      event = OpenCrayMemoryRetrievalEvent(
        runId = recallSubmission.runId,
        taskId = recallTask.id,
        turn = 0,
        toolName = "memory_search",
        operation = "search",
        query = "what name should I call the agent",
        queryTerms = listOf("name", "agent"),
        resultCount = 1,
        corpusFileCount = 1,
        recordIds = listOf("memory-user"),
        paths = listOf("memory/2024-03-11.md"),
        lineRanges = listOf("5-8"),
        emittedAtEpochMs = 2_400L,
      ),
    )

    val payload = hostRuntime.loadMemoryDebugLinksSnapshot()

    assertEquals(sessionId, payload["sessionId"])
    val records = payload["records"] as List<*>
    val userLinks = records
      .map { entry -> entry as Map<*, *> }
      .first { entry -> entry["recordId"] == "memory-user" }
    assertEquals(sessionId, userLinks["sourceSessionId"])
    assertEquals(sourceTask.id, userLinks["sourceTaskId"])
    val sourceRun = userLinks["sourceRun"] as Map<*, *>
    assertEquals(sourceSubmission.runId, sourceRun["runId"])
    val promptRecall = (userLinks["promptRecalls"] as List<*>)
      .single() as Map<*, *>
    assertEquals(420, promptRecall["score"])
    val retrieval = (userLinks["toolRetrievals"] as List<*>)
      .single() as Map<*, *>
    assertEquals("memory_search", retrieval["toolName"])
    val maintenanceActions = (userLinks["maintenanceActions"] as List<*>)
      .map { entry -> entry as Map<*, *> }
    assertTrue(maintenanceActions.any { entry -> entry["action"] == "written" })
    assertTrue(maintenanceActions.any { entry -> entry["action"] == "flush_written" })
  }

  @Test
  fun applyMemoryDebugActionSuppressesRecordAndAddsMaintenanceLink() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-debug-action"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-debug-action"),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_100L,
        tags = listOf("kind:user_preference", "scope:user", "status:active"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE to "user_input",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )

    val payload = hostRuntime.applyMemoryDebugAction(
      recordId = "memory-user",
      actionId = "suppress",
    )

    assertEquals("memory-user", payload["recordId"])
    assertEquals("suppress", payload["action"])
    assertEquals(true, payload["applied"])
    val updatedRecord = personalizationStore.listMemoryRecords()
      .single { record -> record.id == "memory-user" }
    assertEquals("resolved", updatedRecord.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals(
      "operator_suppressed",
      updatedRecord.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON],
    )

    val runtimePayload = hostRuntime.loadChatRuntimeSnapshot()
    val runtimeEvents = runtimePayload["events"] as List<*>
    assertTrue(runtimeEvents.none { event ->
      (event as? Map<*, *>)?.get("kind") == "memory_write"
    })
    val runtimeActivity = (hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>)["events"] as List<*>
    assertTrue(runtimeActivity.none { event ->
      (event as? Map<*, *>)?.get("kind") == "memory_write"
    })

    val reloadedHostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )
    val linksPayload = reloadedHostRuntime.loadMemoryDebugLinksSnapshot()
    val links = (linksPayload["records"] as List<*>)
      .map { entry -> entry as Map<*, *> }
      .first { entry -> entry["recordId"] == "memory-user" }
    val maintenanceActions = (links["maintenanceActions"] as List<*>)
      .map { entry -> entry as Map<*, *> }
    assertTrue(maintenanceActions.any { entry -> entry["action"] == "suppressed" })
  }

  @Test
  fun applyMemoryDebugActionReaffirmsSuppressedRecordAndAddsMaintenanceLink() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-memory-debug-reaffirm"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-memory-debug-reaffirm"),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_200L,
        tags = listOf("kind:user_preference", "scope:user", "status:resolved"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "resolved",
          MemoryRecordExtensionKeys.SOURCE to "user_input",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemoryRecordExtensionKeys.RESOLUTION_REASON to "operator_suppressed",
          MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS to "2200",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )

    val payload = hostRuntime.applyMemoryDebugAction(
      recordId = "memory-user",
      actionId = "reaffirm",
    )

    assertEquals("memory-user", payload["recordId"])
    assertEquals("reaffirm", payload["action"])
    assertEquals(true, payload["applied"])
    val updatedRecord = personalizationStore.listMemoryRecords()
      .single { record -> record.id == "memory-user" }
    assertEquals("active", updatedRecord.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals(null, updatedRecord.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON])

    val runtimePayload = hostRuntime.loadChatRuntimeSnapshot()
    val runtimeEvents = runtimePayload["events"] as List<*>
    assertTrue(runtimeEvents.none { event ->
      (event as? Map<*, *>)?.get("kind") == "memory_write"
    })
    val runtimeActivity = (hostRuntime.loadChatSnapshot()["runtimeActivity"] as Map<*, *>)["events"] as List<*>
    assertTrue(runtimeActivity.none { event ->
      (event as? Map<*, *>)?.get("kind") == "memory_write"
    })

    val reloadedHostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = NoOpRuntimeManager(),
      personalizationLocalStore = personalizationStore,
    )
    val linksPayload = reloadedHostRuntime.loadMemoryDebugLinksSnapshot()
    val links = (linksPayload["records"] as List<*>)
      .map { entry -> entry as Map<*, *> }
      .first { entry -> entry["recordId"] == "memory-user" }
    val maintenanceActions = (links["maintenanceActions"] as List<*>)
      .map { entry -> entry as Map<*, *> }
    assertTrue(maintenanceActions.any { entry -> entry["action"] == "reaffirmed" })
  }

  @Test
  fun loadSoulDebugSnapshotReturnsStoredEffectiveSoulAndFieldSources() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-soul-debug"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-soul-debug"),
    )
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-debug").toPath()
    WorkspaceSoulProfileStore().saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "STEADY",
        customLabel = "Night Shift",
        customGuidance = "Keep replies calm and concrete.",
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-user",
        content = "Call the agent Xiao Bai.",
        createdAtEpochMs = 2_000L,
        updatedAtEpochMs = 2_100L,
        tags = listOf("kind:user_preference", "scope:user"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "Xiao Bai",
          MemorySoulExtensionKeys.DISPLAY_NAME to "Xiao Bai",
        ),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-style",
        content = "Keep the tone warmer.",
        createdAtEpochMs = 2_200L,
        updatedAtEpochMs = 2_250L,
        tags = listOf("kind:user_preference", "scope:session"),
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "user_preference",
          MemoryRecordExtensionKeys.SCOPE to "session",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.PREFERENCE_KEY to MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
          MemoryRecordExtensionKeys.PREFERENCE_VALUE to "warm",
          MemorySoulExtensionKeys.TONE to "warm",
          MemorySoulExtensionKeys.VOICE to "warm and gentle",
          MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE to "supportive",
        ),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "interaction-state",
        content = "internal interaction preference snapshot",
        createdAtEpochMs = 2_300L,
        updatedAtEpochMs = 2_300L,
        extensions = mapOf(
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "2300",
          SoulMemoryExtensionKeys.OBJECT_TYPE to SoulMemoryObjectTypes.INTERACTION_PREFERENCE_STATE,
          SoulMemoryExtensionKeys.OBJECT_SCHEMA_VERSION to "1",
          SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON to Json.encodeToString(
            InteractionPreferenceState.serializer(),
            InteractionPreferenceState(
              warmth = PreferenceAxisState(offset = 1, higherSupport = 2),
              formality = PreferenceAxisState(offset = -1, lowerSupport = 2),
              initiative = PreferenceAxisState(offset = 1, higherSupport = 2),
              playfulness = PreferenceAxisState(offset = 1, higherSupport = 2),
              reassurance = PreferenceAxisState(offset = 1, higherSupport = 2),
              addressStyle = PreferredAddressState(
                selectedStyle = PreferredAddressStyle.FRIENDLY,
                friendlySupport = 2,
              ),
              preferredNaming = "A-Cheng",
              preferredNamingSupport = 2,
            ),
          ),
        ),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "relationship-state",
        content = "internal relationship snapshot",
        createdAtEpochMs = 2_400L,
        updatedAtEpochMs = 2_400L,
        extensions = mapOf(
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "2400",
          SoulMemoryExtensionKeys.OBJECT_TYPE to SoulMemoryObjectTypes.RELATIONSHIP_STATE,
          SoulMemoryExtensionKeys.OBJECT_SCHEMA_VERSION to "1",
          SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON to Json.encodeToString(
            RelationshipState.serializer(),
            RelationshipState(
              familiarity = 66,
              trust = 74,
              safety = 76,
              intimacyPermission = 61,
              playfulnessPermission = 44,
              affectionTendency = 34,
              reciprocity = 49,
            ),
          ),
        ),
      ),
    )

    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      personalizationLocalStore = personalizationStore,
      workspaceRootProvider = { workspaceRoot },
    )

    val payload = hostRuntime.loadSoulDebugSnapshot()

    val storedSoul = payload["storedSoul"] as Map<*, *>
    val effectiveSoul = payload["effectiveSoul"] as Map<*, *>
    val interactionPreferenceDebug = payload["interactionPreferenceDebug"] as Map<*, *>
    val relationshipStateDebug = payload["relationshipStateDebug"] as Map<*, *>
    val fieldSources = payload["fieldSources"] as List<*>

    assertEquals("STEADY", storedSoul["presetName"])
    assertEquals("Xiao Bai", effectiveSoul["displayName"])
    assertEquals("warm", effectiveSoul["tone"])
    assertEquals("warm and gentle", effectiveSoul["voice"])
    assertEquals("1", effectiveSoul["warmthPreferenceOffset"])
    assertEquals("-1", effectiveSoul["formalityPreferenceOffset"])
    assertEquals("1", effectiveSoul["initiativePreferenceOffset"])
    assertEquals("1", effectiveSoul["playfulnessPreferenceOffset"])
    assertEquals("1", effectiveSoul["reassurancePreferenceOffset"])
    assertEquals("true", effectiveSoul["supportiveReassuranceAllowed"])
    assertEquals("true", effectiveSoul["proactiveRelationalCheckInAllowed"])
    assertEquals("true", effectiveSoul["lightPlayfulnessAllowed"])
    assertEquals("true", effectiveSoul["playfulTeasingAllowed"])
    assertEquals("user", interactionPreferenceDebug["scope"])
    assertEquals("A-Cheng", interactionPreferenceDebug["preferredNaming"])
    assertEquals("friendly", interactionPreferenceDebug["preferredAddressStyle"])
    assertEquals("user", relationshipStateDebug["scope"])
    assertEquals("intimate", relationshipStateDebug["derivedAddressStyle"])
    assertEquals(false, relationshipStateDebug["recentNegativeGuardActive"])
    assertEquals(true, relationshipStateDebug["supportiveReassuranceAllowed"])
    assertEquals(true, relationshipStateDebug["proactiveRelationalCheckInAllowed"])
    assertEquals(true, relationshipStateDebug["lightPlayfulnessAllowed"])
    assertEquals(true, relationshipStateDebug["playfulTeasingAllowed"])
    assertEquals(true, relationshipStateDebug["highIntimacyBehaviorAllowed"])
    val mappedFieldSources = fieldSources.map { item -> item as Map<*, *> }
    fun fieldSource(field: String): Map<*, *> =
      mappedFieldSources.first { source -> source["field"] == field }

    val displayNameSource = fieldSource("displayName")
    val preferredNamingSource = fieldSource("preferredNaming")
    val warmthOffsetSource = fieldSource("warmthPreferenceOffset")
    val playfulnessOffsetSource = fieldSource("playfulnessPreferenceOffset")
    val reassuranceOffsetSource = fieldSource("reassurancePreferenceOffset")
    val supportiveReassuranceSource = fieldSource("supportiveReassuranceAllowed")
    val proactiveCheckInSource = fieldSource("proactiveRelationalCheckInAllowed")
    val lightPlayfulnessSource = fieldSource("lightPlayfulnessAllowed")
    val playfulTeasingSource = fieldSource("playfulTeasingAllowed")
    val highIntimacySource = fieldSource("highIntimacyBehaviorAllowed")
    assertEquals("memory_overlay", displayNameSource["sourceType"])
    assertEquals("memory-user", displayNameSource["recordId"])
    assertEquals("interaction_preference", preferredNamingSource["sourceType"])
    assertEquals("interaction-state", preferredNamingSource["recordId"])
    assertEquals("interaction_preference", warmthOffsetSource["sourceType"])
    assertEquals("interaction-state", warmthOffsetSource["recordId"])
    assertEquals("interaction_preference", playfulnessOffsetSource["sourceType"])
    assertEquals("interaction-state", playfulnessOffsetSource["recordId"])
    assertEquals("interaction_preference", reassuranceOffsetSource["sourceType"])
    assertEquals("interaction-state", reassuranceOffsetSource["recordId"])
    assertEquals("relationship_state", supportiveReassuranceSource["sourceType"])
    assertEquals("relationship-state", supportiveReassuranceSource["recordId"])
    assertEquals("relationship_state", proactiveCheckInSource["sourceType"])
    assertEquals("relationship-state", proactiveCheckInSource["recordId"])
    assertEquals("relationship_state", lightPlayfulnessSource["sourceType"])
    assertEquals("relationship-state", lightPlayfulnessSource["recordId"])
    assertEquals("relationship_state", playfulTeasingSource["sourceType"])
    assertEquals("relationship-state", playfulTeasingSource["recordId"])
    assertEquals("relationship_state", highIntimacySource["sourceType"])
    assertEquals("relationship-state", highIntimacySource["recordId"])
  }

  @Test
  fun loadSoulDebugSnapshotAttributesRelationshipDerivedAddressStyleOverBaseSoul() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-soul-debug-address"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-soul-debug-address"),
    )
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-debug-address").toPath()
    WorkspaceSoulProfileStore().saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "STEADY",
        customLabel = "Night Shift",
        customGuidance = "Keep replies calm and concrete.",
        extensions = mapOf(
          SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE to "neutral",
          SoulProfileExtensionKeys.PLASTICITY to "medium",
        ),
      ),
    )
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "relationship-state",
        content = "internal relationship snapshot",
        createdAtEpochMs = 2_400L,
        updatedAtEpochMs = 2_400L,
        extensions = mapOf(
          MemoryRecordExtensionKeys.SCOPE to "user",
          MemoryRecordExtensionKeys.STATUS to "active",
          MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sessionId,
          MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to "2400",
          SoulMemoryExtensionKeys.OBJECT_TYPE to SoulMemoryObjectTypes.RELATIONSHIP_STATE,
          SoulMemoryExtensionKeys.OBJECT_SCHEMA_VERSION to "1",
          SoulMemoryExtensionKeys.OBJECT_PAYLOAD_JSON to Json.encodeToString(
            RelationshipState.serializer(),
            RelationshipState(
              familiarity = 66,
              trust = 74,
              safety = 76,
              intimacyPermission = 61,
              playfulnessPermission = 44,
              affectionTendency = 34,
              reciprocity = 49,
            ),
          ),
        ),
      ),
    )

    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager(),
      personalizationLocalStore = personalizationStore,
      workspaceRootProvider = { workspaceRoot },
    )

    val payload = hostRuntime.loadSoulDebugSnapshot()

    val effectiveSoul = payload["effectiveSoul"] as Map<*, *>
    val relationshipStateDebug = payload["relationshipStateDebug"] as Map<*, *>
    val fieldSources = (payload["fieldSources"] as List<*>).map { item -> item as Map<*, *> }
    val preferredAddressSource = fieldSources.first { source ->
      source["field"] == "preferredAddressStyle"
    }

    assertEquals("intimate", effectiveSoul["preferredAddressStyle"])
    assertEquals("intimate", relationshipStateDebug["derivedAddressStyle"])
    assertEquals("relationship_state", preferredAddressSource["sourceType"])
    assertEquals("relationship-state", preferredAddressSource["recordId"])
  }

  @Test
  fun loadSkillsSnapshotQueryUsesFacadeSearchResults() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-query"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      snapshot = SkillsSnapshot(
        installedSkills = emptyList(),
        installSources = listOf(
          InstallSourceSnapshot(
            id = "github-url",
            title = "GitHub URL",
            subtitle = "Enter a source ref.",
            actionLabel = "Inspect",
            isAvailable = true,
          ),
        ),
        suggestedSkills = listOf(
          SuggestedSkillSnapshot(
            id = "roin-orca/skills/find-skills",
            name = "find-skills",
            description = "roin-orca/skills via skills.sh",
            sourceRef = "roin-orca/skills@find-skills",
            sourceLabel = "skills.sh",
            installs = 42,
            detailUrl = "https://skills.sh/roin-orca/skills",
          ),
        ),
        suggestedSkillsMayHaveMore = true,
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val payload = hostRuntime.loadSkillsSnapshot(query = "find", suggestedLimit = 8)

    assertEquals("find", skillsFacade.lastLoadedQuery)
    assertEquals(8, skillsFacade.lastSuggestedLimit)
    assertTrue(handle.submittedTasks.isEmpty())
    val suggestedSkills = payload["suggestedSkills"] as List<*>
    val firstResult = suggestedSkills.first() as Map<*, *>
    assertEquals("roin-orca/skills@find-skills", firstResult["sourceRef"])
    assertEquals("skills.sh", firstResult["sourceLabel"])
    assertEquals(42, firstResult["installs"])
    assertEquals("https://skills.sh/roin-orca/skills", firstResult["detailUrl"])
    assertEquals(true, payload["suggestedSkillsMayHaveMore"])
  }

  @Test
  fun installSkillSourceUsesSkillsFacadeAndReturnsInstalledSkillMessage() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-install-source"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      installResult = SkillInstallRequestResult(installedSkillId = "find-skills")
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val message = hostRuntime.installSkillSource(
      sourceRef = "roin-orca/skills@find-skills",
      selectedSkillName = "",
    )

    assertEquals("roin-orca/skills@find-skills", skillsFacade.lastInstalledSourceRef)
    assertEquals("", skillsFacade.lastInstalledSelectedSkillName)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("Installed find-skills.", message)
  }

  @Test
  fun installSkillSourcePassesSelectedSkillNameThroughSkillsFacade() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-install-source-selected"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      installResult = SkillInstallRequestResult(installedSkillId = "review-skills")
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val message = hostRuntime.installSkillSource(
      sourceRef = "roin-orca/skills",
      selectedSkillName = "review-skills",
    )

    assertEquals("roin-orca/skills", skillsFacade.lastInstalledSourceRef)
    assertEquals("review-skills", skillsFacade.lastInstalledSelectedSkillName)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("Installed review-skills.", message)
  }

  @Test
  fun installSkillSourceBatchPassesSelectedSkillNamesThroughSkillsFacade() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-install-source-batch"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      batchInstallResult = SkillPackageBatchInstallAttempt(
        result = SkillPackageBatchInstallResult(
          sourceType = "remote_github",
          sourceRef = "roin-orca/skills",
          entries = listOf(
            SkillPackageBatchInstallEntry(
              requestedSkillName = "find-skills",
              installedSkillId = "find-skills",
            ),
            SkillPackageBatchInstallEntry(
              requestedSkillName = "review-skills",
              installedSkillId = "review-skills",
            ),
          ),
        ),
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val message = hostRuntime.installSkillSourceBatch(
      sourceRef = "roin-orca/skills",
      selectedSkillNames = listOf("find-skills", "review-skills"),
    )

    assertEquals("roin-orca/skills", skillsFacade.lastBatchInstalledSourceRef)
    assertEquals(listOf("find-skills", "review-skills"), skillsFacade.lastBatchInstalledSkillNames)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("Installed 2 skills.", message)
  }

  @Test
  fun inspectSkillSourceUsesSkillsFacadeAndReturnsInspectionPayload() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-inspect-source"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      inspectResult = SkillSourceInspectionAttempt(
        result = SkillSourceInspectionResult(
          sourceType = "remote_github",
          sourceRef = "roin-orca/skills",
          sourcePath = "https://github.com/roin-orca/skills",
          resolvedRevision = "main",
          resolvedCommitSha = "deadbeef",
          candidates = listOf(
            SkillSourceInspectionCandidate(
              name = "find-skills",
              description = "Discover skills",
              relativePath = "skills/find-skills/SKILL.md",
            ),
            SkillSourceInspectionCandidate(
              name = "review-skills",
              description = "Review changes",
              relativePath = "skills/review-skills/SKILL.md",
            ),
          ),
        ),
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val payload = hostRuntime.inspectSkillSource("roin-orca/skills")

    assertEquals("roin-orca/skills", skillsFacade.lastInspectedSourceRef)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("remote_github", payload["sourceType"])
    assertEquals("roin-orca/skills", payload["sourceRef"])
    val candidates = payload["candidates"] as List<*>
    assertEquals(2, candidates.size)
    assertEquals("find-skills", (candidates[0] as Map<*, *>)["name"])
    assertEquals("review-skills", (candidates[1] as Map<*, *>)["name"])
  }

  @Test
  fun deleteInstalledSkillUsesSkillsFacadeAndReturnsRemovedSkillMessage() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-delete-skill"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      deleteResult = true
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val message = hostRuntime.deleteInstalledSkill("find-skills")

    assertEquals("find-skills", skillsFacade.lastDeletedSkillId)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("Removed find-skills.", message)
  }

  @Test
  fun loadSkillsSnapshotWithoutQueryUsesFacadeSnapshot() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-default"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      snapshot = SkillsSnapshot(
        installedSkills = listOf(
          InstalledSkillSnapshot(
            id = "find-skills",
            name = "find-skills",
            description = "Fallback description",
            isEnabled = false,
            sourceDirectoryPath = "/managed/find-skills",
            canDelete = true,
          ),
        ),
        installSources = listOf(
          InstallSourceSnapshot(
            id = "github-url",
            title = "GitHub URL",
            subtitle = "Enter a source ref.",
            actionLabel = "Inspect",
            isAvailable = true,
          ),
        ),
        suggestedSkills = listOf(
          SuggestedSkillSnapshot(
            id = "acme/skills/remote-skill",
            name = "remote-skill",
            description = "acme/skills via skills.sh",
            sourceRef = "acme/skills@remote-skill",
            sourceLabel = "skills.sh",
          ),
        ),
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val payload = hostRuntime.loadSkillsSnapshot(query = "", suggestedLimit = 0)

    assertEquals("", skillsFacade.lastLoadedQuery)
    assertEquals(0, skillsFacade.lastSuggestedLimit)
    assertTrue(handle.submittedTasks.isEmpty())
    val installedSkills = payload["installedSkills"] as List<*>
    val installed = installedSkills.single() as Map<*, *>
    assertEquals("find-skills", installed["id"])
    assertEquals("Fallback description", installed["description"])
    assertEquals(false, installed["isEnabled"])
    assertEquals("/managed/find-skills", installed["sourceDirectoryPath"])
    val suggestedSkills = payload["suggestedSkills"] as List<*>
    val suggested = suggestedSkills.single() as Map<*, *>
    assertEquals("acme/skills@remote-skill", suggested["sourceRef"])
    assertEquals("skills.sh", suggested["sourceLabel"])
    assertEquals(false, payload["suggestedSkillsMayHaveMore"])
  }

  @Test
  fun loadSuggestedSkillInstructionsUsesFacadePreview() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-suggested-instructions"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      suggestedInstructions = SkillInstructionsSnapshot(
        id = "find-skills",
        name = "find-skills",
        description = "Find and install useful skills.",
        body = "## Usage\nUse this skill to discover skills.",
        sourceDirectoryPath = "https://skills.sh/roin-orca/skills",
        isEnabled = false,
        canDelete = false,
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val runtimeManager = RecordingRuntimeManager().apply {
      putHandle(handle)
    }
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = runtimeManager,
      skillsFacade = skillsFacade,
    )

    val payload = hostRuntime.loadSuggestedSkillInstructions(
      sourceRef = "roin-orca/skills@find-skills",
      selectedSkillName = "find-skills",
    )

    assertEquals("roin-orca/skills@find-skills", skillsFacade.lastSuggestedInstructionsSourceRef)
    assertEquals("find-skills", skillsFacade.lastSuggestedInstructionsSkillName)
    assertEquals("find-skills", payload["name"])
    assertEquals("https://skills.sh/roin-orca/skills", payload["sourceDirectoryPath"])
    assertTrue(handle.submittedTasks.isEmpty())
  }

  @Test
  fun checkInstalledSkillUpdatesUsesSkillsFacade() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-check"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      checkReport = SkillPackageCheckReport(
        results = listOf(
          SkillPackageCheckResult(
            skillId = "find-skills",
            sourceType = "remote_github",
            sourceRef = "roin-orca/skills",
            status = SkillPackageCheckStatus.UP_TO_DATE,
            checkedAtEpochMs = 1_000L,
          ),
        ),
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager().apply { putHandle(handle) },
      skillsFacade = skillsFacade,
    )

    val message = hostRuntime.checkInstalledSkillUpdates("find-skills")

    assertEquals("find-skills", skillsFacade.lastCheckedSkillId)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("Skill 'find-skills' is up to date.", message)
  }

  @Test
  fun updateInstalledSkillUsesSkillsFacade() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-update"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val skillsFacade = TestSkillsFacade().apply {
      updateReport = SkillPackageUpdateReport(
        results = listOf(
          SkillPackageUpdateResult(
            skillId = "find-skills",
            sourceType = "remote_github",
            sourceRef = "roin-orca/skills",
            status = SkillPackageUpdateStatus.UPDATED,
          ),
        ),
      )
    }
    val handle = RecordingSessionHandle(sessionId = sessionId)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = RecordingRuntimeManager().apply { putHandle(handle) },
      skillsFacade = skillsFacade,
    )

    val message = hostRuntime.updateInstalledSkill("find-skills")

    assertEquals("find-skills", skillsFacade.lastUpdatedSkillId)
    assertTrue(handle.submittedTasks.isEmpty())
    assertEquals("Updated 'find-skills'.", message)
  }

  private fun semanticUserCandidateExtractor(): MemoryCandidateExtractor =
    MemoryCandidateExtractor(
      userIntentInterpreter = object : UserMemoryIntentInterpreter {
        override fun interpret(
          request: UserMemoryIntentRequest,
        ): UserMemoryIntentInterpretation = UserMemoryIntentInterpretation.Success(
          intents = buildList {
            if (request.userInput.contains("Simplified Chinese", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  content = "Default to Simplified Chinese for explanations",
                ),
              )
            }
            if (request.userInput.contains("git reset --hard", ignoreCase = true)) {
              add(
                UserMemoryIntent(
                  kind = MemoryKind.DURABLE_INSTRUCTION,
                  scope = MemoryScope.WORKSPACE,
                  content = "Do not use git reset --hard in this repo",
                ),
              )
            }
          },
        )
      },
    )

  private fun hostRuntime(
    chatStore: ChatSessionLocalStore,
    runtimeManager: AgentSessionRuntimeManager,
    networkSearchConfigFacade: NetworkSearchConfigFacade = EmptyNetworkSearchConfigFacade,
    mediaSpeechSettingsFacade: com.opencray.app.facade.media.MediaSpeechSettingsFacade =
      com.opencray.app.facade.media.EmptyMediaSpeechSettingsFacade,
    sandboxSettingsRepository: SandboxSettingsRepository? = null,
    llmConfigFacade: LlmConfigFacade = RecordingLlmConfigFacade(),
    onDeviceLlmWarmupController: OnDeviceLlmWarmupController? = null,
    personalizationFacade: PersonalizationFacade = RecordingPersonalizationFacade(),
    personalizationLocalStore: PersonalizationLocalStore? = null,
    mcpSettingsFacade: McpSettingsFacade = RecordingMcpSettingsFacade(),
    safetySettingsFacade: SafetySettingsFacade = RecordingSafetySettingsFacade(),
    skillsFacade: SkillsFacade = TestSkillsFacade(),
    directTaskRuntimeFactory: AgentSessionTaskRuntimeFactory? = null,
    memoryIngestionCoordinator: ChatMemoryIngestionCoordinator? = null,
    workspaceRootProvider: (() -> Path)? = null,
    approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot = {
      ApprovedReadRootsSnapshot(roots = emptySet(), summary = "workspace=unavailable")
    },
    todoSnapshotProvider: (String) -> ChatSessionTodoPresentation = {
      ChatSessionTodoPresentation.empty()
    },
    transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage> = { emptyList() },
    approvalRegistry: AgentTaskApprovalRegistry = AgentTaskApprovalRegistry(),
    approvalReplayRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _, _ -> },
    approvalApprovedReplayRecorder: (String, String, String, String?, Boolean, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _, _ -> },
    subAgentReplayRecorder: (String, OpenCraySubAgentEvent) -> Unit = { _, _ -> },
    runCancellationReplayRecorder: (String, String, String, String?, RuntimeReplayExecutionContext) -> Unit = { _, _, _, _, _ -> },
    terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit = { _, _ -> },
    supplementStoreFactory: AgentSessionSupplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      private val stores = mutableMapOf<String, SessionSupplementStore>()

      override fun forChatSession(sessionId: String): SessionSupplementStore =
        stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
    },
    mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
    workspaceEntryOpener: ((Path, String) -> Unit)? = null,
    externalUriOpener: ((String) -> Unit)? = null,
    voiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer =
      AppAgentWorkspaceVoiceMetadataAnalyzer { _, _ -> null },
    voiceMetadataBackfillExecutor: Executor = Executor { command -> command.run() },
    voiceMetadataCacheStore: AppAgentWorkspaceVoiceMetadataCacheStore? = null,
    runEventJournalStoreFactory: RunEventJournalStoreFactory =
      hostRuntimeTestRunEventJournalStoreFactory(),
    promptCheckpointStoreFactory: PromptCheckpointStoreFactory =
      hostRuntimeTestPromptCheckpointStoreFactory(),
    lifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
    runtimeOwnerDescriptor: HostRuntimeLifecycleDescriptor = lifecycleDescriptor,
    runtimeServiceDescriptor: RuntimeServiceLifecycleDescriptor? = null,
    runtimeServiceWorkState: RuntimeServiceWorkState? = null,
    runtimeServiceWorkStateProvider: () -> RuntimeServiceWorkState? = {
      runtimeServiceWorkState
    },
    runtimeServiceKeepAliveState: RuntimeServiceKeepAliveState? = null,
    runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState? = {
      runtimeServiceKeepAliveState
    },
    runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar? = null,
    runtimeServiceConnectionState: RuntimeServiceConnectionState? = null,
    runtimeServiceConnectionStateProvider: () -> RuntimeServiceConnectionState? = {
      runtimeServiceConnectionState
    },
    runtimeServiceConnectionChangeRegistrar: RuntimeServiceConnectionChangeRegistrar? = null,
    resumeActiveSessionOnInit: Boolean = true,
    liveChatRuntimeRefreshIntervalMs: Long = 400L,
  ): OpenCrayHostRuntime = OpenCrayHostRuntime.createForTest(
    stateStore = AppShellStateStore(InMemoryAppShellKeyValueStore()),
    chatSessionStore = chatStore,
    settingsFacade = NoOpSettingsFacade,
    networkSearchConfigFacade = networkSearchConfigFacade,
    mediaSpeechSettingsFacade = mediaSpeechSettingsFacade,
    sandboxSettingsRepository = sandboxSettingsRepository,
    llmConfigFacade = llmConfigFacade,
    personalizationFacade = personalizationFacade,
    personalizationLocalStore = personalizationLocalStore,
    mcpSettingsFacade = mcpSettingsFacade,
    safetySettingsFacade = safetySettingsFacade,
    skillsFacade = skillsFacade,
    sessionRuntimeManager = runtimeManager,
    directTaskRuntimeFactory = directTaskRuntimeFactory,
    supplementStoreFactory = supplementStoreFactory,
    workspaceRootProvider = workspaceRootProvider,
    workspaceEntryOpener = workspaceEntryOpener,
    externalUriOpener = externalUriOpener,
    approvedReadRootsProvider = approvedReadRootsProvider,
    voiceMetadataAnalyzer = voiceMetadataAnalyzer,
    voiceMetadataBackfillExecutor = voiceMetadataBackfillExecutor,
    voiceMetadataCacheStore = voiceMetadataCacheStore,
    todoSnapshotProvider = todoSnapshotProvider,
    transcriptMessagesProvider = transcriptMessagesProvider,
    runEventJournalStoreFactory = runEventJournalStoreFactory,
    promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    approvalRegistry = approvalRegistry,
    memoryIngestionCoordinator = memoryIngestionCoordinator,
    approvalReplayRecorder = approvalReplayRecorder,
    approvalApprovedReplayRecorder = approvalApprovedReplayRecorder,
    subAgentReplayRecorder = subAgentReplayRecorder,
    runCancellationReplayRecorder = runCancellationReplayRecorder,
    terminalReplayRepairer = terminalReplayRepairer,
    mainThreadPoster = mainThreadPoster,
    lifecycleDescriptor = lifecycleDescriptor,
    runtimeOwnerDescriptor = runtimeOwnerDescriptor,
    runtimeServiceDescriptor = runtimeServiceDescriptor,
    runtimeServiceWorkState = runtimeServiceWorkState,
    runtimeServiceWorkStateProvider = runtimeServiceWorkStateProvider,
    runtimeServiceKeepAliveState = runtimeServiceKeepAliveState,
    runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
    runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
    runtimeServiceConnectionState = runtimeServiceConnectionState,
    runtimeServiceConnectionStateProvider = runtimeServiceConnectionStateProvider,
    runtimeServiceConnectionChangeRegistrar = runtimeServiceConnectionChangeRegistrar,
    resumeActiveSessionOnInit = resumeActiveSessionOnInit,
    liveChatRuntimeRefreshIntervalMs = liveChatRuntimeRefreshIntervalMs,
    onDeviceLlmWarmupController = onDeviceLlmWarmupController,
    strings = HostRuntimeStrings(
      localeTag = "en",
      shellHostLabel = "HOST CONNECTED",
      shellHostSummary = "Android host bridge is attached to the live app runtime.",
      chatScreenTitle = "Chat",
      chatModeLabel = "AUTO",
      chatSessionButtonLabel = "Sessions",
      chatRecentSessionsEyebrow = "Recent sessions",
      chatRecentSessionsTitle = "Recent sessions",
      chatNewSessionLabel = "New session",
      chatDefaultSessionTitle = "New chat",
      chatMessagesBadge = { count -> "$count messages" },
      chatSummaryReplyInProgress = "Reply in progress",
      chatSummaryOnDevicePreparing = "Preparing the on-device model.",
      chatSummaryAwaitingDirection = "Waiting for your next instruction.",
      chatSummaryStartNewSession = "Start a new session",
      chatSummaryRestored = "Local transcript is restored into the runtime window for each task.",
      skillInstalled = { skillId -> "Installed $skillId." },
      skillRemoved = { skillId -> "Removed $skillId." },
      skillsReloaded = "Reloaded skills from local storage.",
      composerPlaceholder = "Message OpenCray",
      chatMessageOnDevicePreparing = "Preparing on-device model",
      composerRejectedPlaceholder = "Tell OpenCray differently",
      agentThinking = "Thinking",
      agentCancelled = "Cancelled",
      agentMissingLlm = "Missing LLM",
      agentEmptyAnswer = "The model returned an empty answer.",
      agentFailed = { detail -> "Failed: $detail" },
    ),
  )

  private fun waitForObservedManagedProcessSnapshot(
    observedRuntimeSnapshots: List<Map<String, Any?>>,
    runId: String,
    stdoutPreview: String? = null,
  ): Map<*, *> {
    repeat(20) {
      val managedProcess = observedRuntimeSnapshots
        .asReversed()
        .firstNotNullOfOrNull { snapshot ->
          val activeRuns = snapshot["activeRuns"] as? List<*> ?: return@firstNotNullOfOrNull null
          val activeRun = activeRuns
            .mapNotNull { item -> item as? Map<*, *> }
            .firstOrNull { run -> run["runId"] == runId }
            ?: return@firstNotNullOfOrNull null
          val managedProcess = (activeRun["managedProcesses"] as? List<*>)
            .orEmpty()
            .mapNotNull { item -> item as? Map<*, *> }
            .firstOrNull()
            ?: return@firstNotNullOfOrNull null
          if (
            stdoutPreview != null &&
            !(managedProcess["stdoutPreview"] as? String).orEmpty().contains(
              stdoutPreview,
            )
          ) {
            return@firstNotNullOfOrNull null
          }
          managedProcess
        }
      if (managedProcess != null) {
        return managedProcess
      }
      Thread.sleep(25L)
    }
    throw AssertionError(
      "Timed out waiting for observed managed process snapshot for run $runId.",
    )
  }

  private object NoOpSettingsFacade : SettingsFacade {
    override fun loadOverview(): SettingsOverviewSnapshot = SettingsOverviewSnapshot(
      eyebrow = "",
      title = "",
      subtitle = "",
      deviceTitle = "",
      deviceSummary = "",
      entries = emptyList(),
    )

    override fun loadDetail(routeId: SettingsRouteId): SettingsDetailSnapshot = SettingsDetailSnapshot(
      routeId = routeId,
      title = "",
      subtitle = "",
      sections = emptyList(),
    )
  }

  private class TestSkillsFacade : SkillsFacade {
    var lastLoadedQuery: String? = null
    var lastSuggestedLimit: Int? = null
    var lastInstalledSourceRef: String? = null
    var lastInstalledSelectedSkillName: String? = null
    var lastBatchInstalledSourceRef: String? = null
    var lastBatchInstalledSkillNames: List<String>? = null
    var lastInspectedSourceRef: String? = null
    var lastDeletedSkillId: String? = null
    var lastCheckedSkillId: String? = null
    var lastUpdatedSkillId: String? = null
    var lastSuggestedInstructionsSourceRef: String? = null
    var lastSuggestedInstructionsSkillName: String? = null
    var snapshot: SkillsSnapshot = SkillsSnapshot(
      installedSkills = emptyList(),
      installSources = emptyList(),
      suggestedSkills = emptyList(),
    )
    var installResult: SkillInstallRequestResult = SkillInstallRequestResult(
      errorMessage = "Not configured.",
    )
    var batchInstallResult: SkillPackageBatchInstallAttempt = SkillPackageBatchInstallAttempt(
      errorCode = "NOT_CONFIGURED",
      errorMessage = "Not configured.",
    )
    var inspectResult: SkillSourceInspectionAttempt = SkillSourceInspectionAttempt(
      errorCode = "NOT_CONFIGURED",
      errorMessage = "Not configured.",
    )
    var deleteResult: Boolean = true
    var checkReport: SkillPackageCheckReport = SkillPackageCheckReport(results = emptyList())
    var updateReport: SkillPackageUpdateReport = SkillPackageUpdateReport(results = emptyList())
    var suggestedInstructions: SkillInstructionsSnapshot? = null

    override fun loadSnapshot(query: String, suggestedLimit: Int): SkillsSnapshot {
      lastLoadedQuery = query
      lastSuggestedLimit = suggestedLimit
      return snapshot
    }

    override fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean = true

    override fun installSkillSource(
      sourceRef: String,
      selectedSkillName: String,
    ): SkillInstallRequestResult {
      lastInstalledSourceRef = sourceRef
      lastInstalledSelectedSkillName = selectedSkillName
      return installResult
    }

    override fun installSuggestedSkill(skillId: String): Boolean =
      installSkillSource(skillId, "").succeeded

    override fun installSkillSourceBatch(
      sourceRef: String,
      selectedSkillNames: List<String>,
    ): SkillPackageBatchInstallAttempt {
      lastBatchInstalledSourceRef = sourceRef
      lastBatchInstalledSkillNames = selectedSkillNames
      return batchInstallResult
    }

    override fun inspectSkillSource(sourceRef: String): SkillSourceInspectionAttempt {
      lastInspectedSourceRef = sourceRef
      return inspectResult
    }

    override fun deleteInstalledSkill(skillId: String): Boolean {
      lastDeletedSkillId = skillId
      return deleteResult
    }

    override fun refresh() = Unit

    override fun checkInstalledSkillUpdates(skillId: String): SkillPackageCheckReport {
      lastCheckedSkillId = skillId
      return checkReport
    }

    override fun updateInstalledSkill(skillId: String): SkillPackageUpdateReport {
      lastUpdatedSkillId = skillId
      return updateReport
    }

    override fun loadInstructions(skillId: String): SkillInstructionsSnapshot? = null

    override fun loadSuggestedInstructions(
      sourceRef: String,
      selectedSkillName: String,
    ): SkillInstructionsSnapshot? {
      lastSuggestedInstructionsSourceRef = sourceRef
      lastSuggestedInstructionsSkillName = selectedSkillName
      return suggestedInstructions
    }

    override fun enabledSkillRoots(): List<java.io.File> = emptyList()

    override fun activateInstallSource(sourceId: String): String = sourceId
  }

  private class NoOpRuntimeManager : AgentSessionRuntimeManager {
    override fun forSession(sessionId: String): AgentSessionHandle = RecordingSessionHandle(
      sessionId = sessionId,
    )

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = {}

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  private class RecordingDirectTaskRuntimeFactory(
    private val status: ExecutionStatus,
    private val stdout: String = "",
    private val errorMessage: String? = null,
  ) : AgentSessionTaskRuntimeFactory {
    var lastSessionId: String? = null
    val submittedTasks = mutableListOf<AgentTask>()
    var lastTask: AgentTask? = null

    override fun create(
      sessionId: String,
      eventSink: com.opencray.runtime.OpenCrayAgentRuntimeEventSink,
    ): SessionTaskRuntime = SessionTaskRuntime { task, _ ->
      lastSessionId = sessionId
      submittedTasks += task
      lastTask = task
      ExecutionResult(
        taskId = task.id,
        status = status,
        stdout = if (status == ExecutionStatus.SUCCESS) stdout else "",
        stderr = if (status == ExecutionStatus.SUCCESS) "" else (errorMessage ?: stdout),
        errorMessage = if (status == ExecutionStatus.SUCCESS) null else errorMessage,
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_000L,
      )
    }
  }

  private class RecordingLlmConfigFacade(
    private val snapshot: LlmConfigSnapshot = EmptyLlmConfigFacade.load(),
    private val validationResult: LlmValidationResult = LlmValidationResult(
      isSuccess = false,
      message = "Not configured.",
    ),
    private val onValidate: (() -> Unit)? = null,
  ) : LlmConfigFacade {
    var lastSavedCustomRequest: SaveCustomLlmProviderRequest? = null

    override fun load(): LlmConfigSnapshot = snapshot

    override fun save(request: SaveLlmConfigRequest): LlmConfigSnapshot =
      throw UnsupportedOperationException("save is not used in this test")

    override fun saveCustomProvider(request: SaveCustomLlmProviderRequest): LlmConfigSnapshot {
      lastSavedCustomRequest = request
      return LlmConfigSnapshot(
        localeTag = "en",
        enabled = true,
        providerId = "custom",
        selectedProviderOptionId = "custom-saved",
        protocol = request.protocol,
        providerOptions = emptyList(),
        providerName = request.providerName,
        providerNotes = request.providerNotes,
        baseUrl = request.baseUrl,
        apiKey = request.apiKey,
        model = request.model,
        reasoningEffort = request.reasoningEffort,
        systemPrompt = request.systemPrompt,
        helperText = "Helper",
      )
    }

    override fun validate(request: ValidateLlmConfigRequest): LlmValidationResult {
      onValidate?.invoke()
      return validationResult
    }

    override fun downloadOnDeviceModel(modelId: String): LlmConfigSnapshot = load()

    override fun cancelOnDeviceModelDownload(modelId: String): LlmConfigSnapshot = load()

    override fun deleteOnDeviceModel(modelId: String): LlmConfigSnapshot = load()
  }

  private class RecordingOnDeviceLlmWarmupController(
    private val state: OnDeviceLlmWarmupState,
  ) : OnDeviceLlmWarmupController {
    var lastSpec: OnDeviceLlmWarmupSpec? = null
      private set

    override fun ensureWarm(spec: OnDeviceLlmWarmupSpec): OnDeviceLlmWarmupState {
      lastSpec = spec
      return state
    }

    override fun clear(): OnDeviceLlmWarmupState = OnDeviceLlmWarmupState()
  }

  private fun readyOnDeviceLlmConfigSnapshot(): LlmConfigSnapshot = EmptyLlmConfigFacade.load().copy(
    enabled = true,
    providerMode = LlmProviderModes.ON_DEVICE_MODEL,
    selectedOnDeviceModelId = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
    onDeviceModels = listOf(
      OnDeviceLlmModelOptionSnapshot(
        id = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
        title = "Gemma 4 E2B",
        subtitle = "Ready",
        sizeLabel = "2.6 GB",
        fileSizeBytes = 2_580_000_000L,
        installState = OnDeviceLlmDownloadStates.READY,
        sha256Verified = true,
        isSelected = true,
      ),
    ),
    onDeviceAccelerator = OnDeviceLlmAccelerators.GPU,
    onDeviceMaxContextWindow = 32_768,
    onDeviceMaxTokens = 4_096,
    onDeviceTopK = 40,
    onDeviceTopP = 0.95,
    onDeviceTemperature = 0.7,
    onDeviceThinkingEnabled = true,
  )

  private class RecordingPersonalizationFacade : PersonalizationFacade {
    var lastSaveRequest: SavePersonalizationConfigRequest? = null

    override fun load(): PersonalizationConfigSnapshot = snapshot()

    override fun save(request: SavePersonalizationConfigRequest): PersonalizationConfigSnapshot {
      lastSaveRequest = request
      return snapshot(
        selectedPresetId = request.presetId,
        customLabel = request.customLabel,
        customGuidance = request.customGuidance,
      )
    }

    override fun setAppLanguage(languageId: String): PersonalizationConfigSnapshot =
      snapshot(selectedAppLanguageId = languageId)

    override fun reset(scope: PersonalizationResetScope): PersonalizationConfigSnapshot =
      snapshot(lastResetMessage = scope.wireValue)

    private fun snapshot(
      selectedPresetId: String = "steady",
      customLabel: String = "",
      customGuidance: String = "",
      selectedAppLanguageId: String = "en",
      lastResetMessage: String? = null,
    ): PersonalizationConfigSnapshot = PersonalizationConfigSnapshot(
      title = "Personalization",
      subtitle = "Tune voice",
      introTitle = "Shape how OpenCray sounds",
      introBody = "Body",
      introHelper = "Helper",
      presetsTitle = "Presets",
      presetsHelper = "Presets helper",
      presets = listOf(
        PersonalizationPresetSnapshot(
          id = selectedPresetId,
          title = "Preset",
          summary = "Summary",
          voice = "Voice",
          status = "Selected",
          isSelected = true,
        ),
      ),
      selectedPresetId = selectedPresetId,
      customOverlayTitle = "Overlay",
      customOverlayHelper = "Overlay helper",
      customLabelHint = "Label hint",
      customLabelHelper = "Label helper",
      customGuidanceHint = "Guidance hint",
      customGuidanceHelper = "Guidance helper",
      customLabel = customLabel,
      customGuidance = customGuidance,
      behaviorDefaultsTitle = "Behavior defaults",
      appLanguageTitle = "App language",
      appLanguageOptions = listOf(
        PersonalizationLanguageOptionSnapshot(
          id = "en",
          title = "English",
          isSelected = selectedAppLanguageId == "en",
        ),
        PersonalizationLanguageOptionSnapshot(
          id = "zh-CN",
          title = "中文",
          isSelected = selectedAppLanguageId == "zh-CN",
        ),
      ),
      selectedAppLanguageId = selectedAppLanguageId,
      livePreviewTitle = "Preview",
      livePreviewName = customLabel.ifBlank { "Preset" },
      livePreviewSummary = customGuidance.ifBlank { "Preview summary" },
      queueTitle = "Idle",
      queueBody = "Queue body",
      queueIsIdle = true,
      lastResetTitle = "Latest reset result",
      lastResetMessage = lastResetMessage,
      resetActions = listOf(
        PersonalizationResetActionSnapshot(
          scope = PersonalizationResetScope.MEMORY,
          title = "Reset memory",
          scopeBody = "Memory scope",
          retainBody = "Retain",
          confirmationToken = "RESET MEMORY",
          inputHint = "Type RESET MEMORY",
          disabledGuidance = "Disabled",
          typeExactGuidance = "Type exact",
          armedGuidance = "Armed",
          isInputEnabled = true,
        ),
      ),
    )
  }

  private class RecordingMcpSettingsFacade : McpSettingsFacade {
    var lastServerToggle: Pair<String, Boolean>? = null

    override fun load(): McpSettingsSnapshot = snapshot()

    override fun setMasterEnabled(enabled: Boolean): McpSettingsSnapshot = snapshot(
      masterEnabled = enabled,
    )

    override fun setServerEnabled(serverId: String, enabled: Boolean): McpSettingsSnapshot {
      lastServerToggle = serverId to enabled
      return snapshot(
        summaryLine = "Enabled 3 • Blocked 0 • Attention 1",
      )
    }

    override fun currentExposureReport() =
      com.opencray.mcp.McpClientExposureReport(
        activeClients = emptyList(),
        blockedClients = emptyList(),
      )

    private fun snapshot(
      masterEnabled: Boolean = true,
      summaryLine: String = "Enabled 2 • Blocked 1 • Attention 2",
    ): McpSettingsSnapshot = McpSettingsSnapshot(
      title = "MCP",
      subtitle = "Control server discovery",
      masterTitle = "Enable MCP integrations",
      masterSummary = "Trusted servers follow this master switch.",
      masterEnabled = masterEnabled,
      summaryLine = summaryLine,
      serversTitle = "Per-server controls live here",
      serversHelper = "This page lists per-server status and actions. Today the runtime only exposes server visibility through mcp_list_servers; remote MCP tools are not proxied into the agent yet.",
      masterDisabledTitle = null,
      masterDisabledBody = null,
      servers = listOf(
        McpServerSettingsSnapshot(
          id = "community-bridge",
          title = "Community Bridge",
          statusLabel = "Blocked",
          statusTone = "blocked",
          trustLine = "Trust: Requires manual enable",
          authLine = "Auth: Credential configured",
          readinessLine = "Readiness: Ready",
          transportLine = "Transport: Remote SSE",
          exposureLine = "Exposure: Blocked",
          guidance = "Blocked until you enable this server manually. Exposure stays hidden until you consent here, and remote MCP tools are not proxied yet.",
          actionLabel = "Enable server",
          actionTurnsOn = true,
          isActionEnabled = true,
        ),
      ),
    )
  }

  private class RecordingSafetySettingsFacade(
    var snapshot: SafetySettingsSnapshot = defaultSafetySettingsSnapshot(),
  ) : SafetySettingsFacade {
    var lastSavedRequest: SaveSafetySettingsRequest? = null

    override fun load(): SafetySettingsSnapshot = snapshot

    override fun save(request: SaveSafetySettingsRequest): SafetySettingsSnapshot {
      lastSavedRequest = request
      snapshot = defaultSafetySettingsSnapshot().copy(
        automationMode = SafetyAutomationMode.fromWireValue(request.automationModeId),
        rollbackJournalEnabled = request.rollbackJournalEnabled,
        maxFilesPerBatch = request.maxFilesPerBatch,
        maxAgentTurns = request.maxAgentTurns,
        maxToolCalls = request.maxToolCalls,
        undoWindowHours = request.undoWindowHours,
        fileChangesPolicy = ToolPolicyOverride.fromWireValue(request.fileChangesPolicyId),
        fileDeletesPolicy = ToolPolicyOverride.fromWireValue(request.fileDeletesPolicyId),
        shellCommandsPolicy = ToolPolicyOverride.fromWireValue(request.shellCommandsPolicyId),
        externalAccessMode = ExternalAccessMode.fromWireValue(request.externalAccessModeId),
        locations = listOf(
          SafetySettingsLocationSnapshot("photo_library", request.photoLibraryEnabled),
          SafetySettingsLocationSnapshot("downloads", request.downloadsEnabled),
          SafetySettingsLocationSnapshot("documents", request.documentsEnabled),
          SafetySettingsLocationSnapshot("recordings", request.recordingsEnabled),
        ),
        workspaceAccessProfile = WorkspaceAccessProfile.fromWireValue(request.workspaceAccessProfileId),
        readOnlyOutsideWorkspace = request.readOnlyOutsideWorkspace,
        liveContextMode = LiveContextMode.fromWireValue(request.liveContextModeId),
        memoryToolsEnabled = request.memoryToolsEnabled,
      )
      return snapshot
    }
  }

  private companion object {
    private val TOOL_NAME_REGEX: Regex = Regex("""\"tool_name\"\s*:\s*\"([^\"]+)\"""")

    fun defaultSafetySettingsSnapshot(): SafetySettingsSnapshot = SafetySettingsSnapshot(
      automationMode = SafetyAutomationMode.AUTO,
      rollbackJournalEnabled = true,
      maxFilesPerBatch = 20,
      maxAgentTurns = SafetySettingsState.DEFAULT_MAX_AGENT_TURNS,
      maxToolCalls = SafetySettingsState.DEFAULT_MAX_TOOL_CALLS,
      undoWindowHours = 24,
      fileChangesPolicy = ToolPolicyOverride.INHERIT,
      fileDeletesPolicy = ToolPolicyOverride.INHERIT,
      shellCommandsPolicy = ToolPolicyOverride.INHERIT,
      externalAccessMode = ExternalAccessMode.SELECT_PATHS,
      locations = listOf(
        SafetySettingsLocationSnapshot(id = "photo_library", enabled = true),
        SafetySettingsLocationSnapshot(id = "downloads", enabled = true),
        SafetySettingsLocationSnapshot(id = "documents", enabled = false),
        SafetySettingsLocationSnapshot(id = "recordings", enabled = false),
      ),
      workspaceAccessProfile = WorkspaceAccessProfile.WORK,
      readOnlyOutsideWorkspace = true,
      memoryToolsEnabled = true,
    )
  }

  private class RecordingRuntimeManager : AgentSessionRuntimeManager {
    private val handlesBySession = linkedMapOf<String, RecordingSessionHandle>()
    private val listeners = mutableListOf<AgentSessionRuntimeListener>()
    val resumedSessionIds = mutableListOf<String>()
    val requestedSessionIds = mutableListOf<String>()
    val releasedSessionIds = mutableListOf<String>()

    fun putHandle(handle: RecordingSessionHandle) {
      handlesBySession[handle.sessionId] = handle
    }

    override fun forSession(sessionId: String): AgentSessionHandle {
      requestedSessionIds += sessionId
      return handlesBySession.getOrPut(sessionId) {
        RecordingSessionHandle(
          sessionId = sessionId,
          onResume = resumedSessionIds::add,
        )
      }
    }

    fun emitTaskFinished(
      sessionId: String,
      task: AgentTask,
      result: ExecutionResult,
    ) {
      handlesBySession[sessionId]?.recordResult(task = task, result = result)
      listeners.forEach { listener ->
        listener.onTaskFinished(sessionId, task, result)
      }
    }

    fun emitRunEvent(
      sessionId: String,
      task: AgentTask,
      event: com.opencray.runtime.OpenCrayAgentRunEvent,
    ) {
      handlesBySession[sessionId]?.recordEvent(event)
      listeners.forEach { listener ->
        listener.onRunEvent(sessionId, task, event)
        when (event) {
          is OpenCrayToolCallEvent -> listener.onToolCall(
            sessionId = sessionId,
            task = task,
            turn = event.turn,
            call = event.call,
          )
          is OpenCrayToolResultEvent -> listener.onToolResult(
            sessionId = sessionId,
            task = task,
            turn = event.turn,
            call = event.call,
            result = event.result,
          )
          else -> Unit
        }
      }
    }

    fun emitAssistantDraftUpdated(
      sessionId: String,
      task: AgentTask,
      text: String,
      emittedAtEpochMs: Long,
    ) {
      listeners.forEach { listener ->
        listener.onAssistantDraftUpdated(
          sessionId = sessionId,
          task = task,
          text = text,
          emittedAtEpochMs = emittedAtEpochMs,
        )
      }
    }

    fun emitAssistantDraftCleared(
      sessionId: String,
      task: AgentTask,
      emittedAtEpochMs: Long,
    ) {
      listeners.forEach { listener ->
        listener.onAssistantDraftCleared(
          sessionId = sessionId,
          task = task,
          emittedAtEpochMs = emittedAtEpochMs,
        )
      }
    }

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit {
      listeners += listener
      return {
        listeners -= listener
      }
    }

    override fun activeWorkSummary(): RuntimeOwnerWorkSummary {
      val activeSessionIds = linkedSetOf<String>()
      val pendingWorkSessionIds = mutableListOf<String>()
      val liveManagedProcessSessionIds = mutableListOf<String>()
      var activeRunCount = 0

      handlesBySession.values.forEach { handle ->
        val runs = handle.listRuns()
        val hasPendingWork = runs.any { snapshot -> !snapshot.isTerminal }
        val hasLiveManagedProcesses = runs.any(AgentRunSnapshot::hasLiveManagedProcesses)
        if (hasPendingWork) {
          pendingWorkSessionIds += handle.sessionId
          activeSessionIds += handle.sessionId
        }
        if (hasLiveManagedProcesses) {
          liveManagedProcessSessionIds += handle.sessionId
          activeSessionIds += handle.sessionId
        }
        activeRunCount += runs.count(AgentRunSnapshot::isActive)
      }

      return RuntimeOwnerWorkSummary(
        trackedSessionCount = handlesBySession.size,
        activeRunCount = activeRunCount,
        activeSessionIds = activeSessionIds.toList(),
        pendingWorkSessionIds = pendingWorkSessionIds.distinct(),
        liveManagedProcessSessionIds = liveManagedProcessSessionIds.distinct(),
      )
    }

    override fun release(sessionId: String) {
      releasedSessionIds += sessionId
      handlesBySession.remove(sessionId)
    }

    override fun releaseIdleSessions() = Unit
  }

  private class SettlingRuntimeManager(
    private val sessionId: String,
  ) : AgentSessionRuntimeManager {
    private val listeners = mutableListOf<AgentSessionRuntimeListener>()
    val handle = SettlingSessionHandle(sessionId = sessionId)

    override fun forSession(sessionId: String): AgentSessionHandle {
      require(sessionId == this.sessionId) {
        "Unexpected sessionId: $sessionId"
      }
      return handle
    }

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit {
      listeners += listener
      return {
        listeners -= listener
      }
    }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit

    fun emitTaskFinished(result: ExecutionResult) {
      val task = handle.requireSubmittedTask()
      handle.recordResult(result)
      listeners.forEach { listener ->
        listener.onTaskFinished(sessionId = sessionId, task = task, result = result)
      }
      handle.settleTerminalState()
    }
  }

  private class SettlingSessionHandle(
    override val sessionId: String,
  ) : AgentSessionHandle {
    private var submittedTask: AgentTask? = null
    private var submission: AgentRunSubmission? = null
    private var lifecycleState: QueueTaskLifecycleState = QueueTaskLifecycleState.QUEUED
    private var result: ExecutionResult? = null

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission {
      val runId = "settling-run"
      val task = AgentTask(
        id = "settling-task",
        type = AgentTaskType.PROMPT,
        input = userText,
        policyDecision = policyDecision,
        metadata = metadata + mapOf(
          AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
          AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
          AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to visibleThroughMessageId,
        ),
        createdAtEpochMs = 1_000L,
      )
      val createdSubmission = AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = 1_000L,
      )
      submittedTask = task
      submission = createdSubmission
      lifecycleState = QueueTaskLifecycleState.QUEUED
      result = null
      return createdSubmission
    }

    override fun ensureProcessing() {
      lifecycleState = QueueTaskLifecycleState.RUNNING
    }

    override fun requestCancel(taskId: String): Boolean = false

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean = false

    override fun listRuns(): List<AgentRunSnapshot> {
      val task = submittedTask ?: return emptyList()
      val createdSubmission = submission ?: return emptyList()
      return listOf(
        AgentRunSnapshot(
          sessionId = sessionId,
          runId = createdSubmission.runId,
          taskId = task.id,
          acceptedAtEpochMs = createdSubmission.acceptedAtEpochMs,
          updatedAtEpochMs = result?.finishedAtEpochMs ?: task.updatedAtEpochMs,
          lifecycleState = lifecycleState,
          taskState = taskStateFor(lifecycleState),
          attempt = 1,
          executionStatus = result?.status,
          errorCode = result?.errorCode,
          errorMessage = result?.errorMessage,
          responseFormat = result?.metadata?.get("responseFormat"),
          resultMetadata = result?.metadata.orEmpty(),
          pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
        ),
      )
    }

    override fun findRun(runId: String): AgentRunSnapshot? =
      listRuns().firstOrNull { snapshot -> snapshot.runId == runId }

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? =
      findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState = SessionLifecycleState.IDLE

    override fun snapshot(): SessionQueueSnapshot {
      val task = submittedTask
      val updatedAtEpochMs = result?.finishedAtEpochMs ?: 1_000L
      if (task == null) {
        return SessionQueueSnapshot(
          sessionId = sessionId,
          agentId = "test-agent",
          updatedAtEpochMs = updatedAtEpochMs,
        )
      }
      return SessionQueueSnapshot(
        sessionId = sessionId,
        agentId = "test-agent",
        tasks = listOf(
          SessionQueueTaskSnapshot(
            enqueueOrder = 0,
            task = task.copy(
              state = taskStateFor(lifecycleState),
              updatedAtEpochMs = updatedAtEpochMs,
            ),
            lifecycleState = lifecycleState,
            attempt = 1,
            lastErrorCode = result?.errorCode,
            lastErrorMessage = result?.errorMessage,
          ),
        ),
        updatedAtEpochMs = updatedAtEpochMs,
      )
    }

    override fun hasPendingWork(): Boolean =
      lifecycleState == QueueTaskLifecycleState.QUEUED ||
        lifecycleState == QueueTaskLifecycleState.RUNNING

    fun requireSubmittedTask(): AgentTask =
      checkNotNull(submittedTask) { "Expected task to be submitted." }

    fun recordResult(result: ExecutionResult) {
      this.result = result
    }

    fun settleTerminalState() {
      lifecycleState = when (result?.status) {
        com.opencray.core.contracts.ExecutionStatus.SUCCESS -> QueueTaskLifecycleState.COMPLETED
        com.opencray.core.contracts.ExecutionStatus.CANCELLED -> QueueTaskLifecycleState.CANCELLED
        else -> QueueTaskLifecycleState.FAILED
      }
    }

    private fun taskStateFor(
      lifecycleState: QueueTaskLifecycleState,
    ): AgentTaskState = when (lifecycleState) {
      QueueTaskLifecycleState.QUEUED,
      QueueTaskLifecycleState.RETRY_PENDING,
      -> AgentTaskState.QUEUED

      QueueTaskLifecycleState.RUNNING,
      QueueTaskLifecycleState.CANCEL_REQUESTED,
      -> AgentTaskState.RUNNING

      QueueTaskLifecycleState.SUSPENDED -> AgentTaskState.SUSPENDED
      QueueTaskLifecycleState.COMPLETED -> AgentTaskState.COMPLETED
      QueueTaskLifecycleState.FAILED -> AgentTaskState.FAILED
      QueueTaskLifecycleState.CANCELLED -> AgentTaskState.CANCELLED
    }
  }

  private class QueuedMainThreadPoster : MainThreadPoster {
    private val actions = ArrayDeque<() -> Unit>()

    override fun post(action: () -> Unit) {
      actions += action
    }

    fun flush() {
      while (actions.isNotEmpty()) {
        actions.removeFirst().invoke()
      }
    }
  }

  private class QueuedExecutor : Executor {
    private val commands = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
      commands += command
    }

    fun pendingCount(): Int = commands.size

    fun runAll() {
      while (commands.isNotEmpty()) {
        commands.removeFirst().run()
      }
    }
  }

  private class FixedTaskCommitmentIntentInterpreter(
    private val interpretation: TaskCommitmentIntentInterpretation,
  ) : TaskCommitmentIntentInterpreter {
    override fun interpret(
      request: TaskCommitmentIntentRequest,
    ): TaskCommitmentIntentInterpretation = interpretation
  }

  private class RecordingSessionHandle(
    override val sessionId: String,
    private val onResume: ((String) -> Unit)? = null,
    private val submitFailure: Throwable? = null,
    private val resumeResult: Boolean = false,
    private val retryResult: Boolean = false,
  ) : AgentSessionHandle {
    var queuedToolCompletion: QueuedToolCompletion? = null
    val queuedToolCompletions = mutableListOf<QueuedToolCompletion>()
    val submittedInputs = mutableListOf<String>()
    val submittedTasks = mutableListOf<AgentTask>()
    val submissions = mutableListOf<AgentRunSubmission>()
    val ensureProcessingTaskIds = mutableListOf<String>()
    val cancelledTaskIds = mutableListOf<String>()
    val cancelledPendingMessageIdSets = mutableListOf<Set<String>>()
    val resumedTaskIds = mutableListOf<String>()
    val resumedExecutionKinds = mutableListOf<String>()
    val resumedTaskMetadataUpdates = mutableListOf<Map<String, String>>()
    val retriedTaskIds = mutableListOf<String>()
    val terminatedProcessIds = mutableListOf<String>()
    val subAgentHandles = mutableListOf<SubAgentHandleState>()
    val retainedSubAgentParentRunIds = mutableListOf<Set<String>>()
    private var lastSubmittedTaskId: String? = null
    private val runSnapshotsById = linkedMapOf<String, AgentRunSnapshot>()
    private val managedProcessesById =
      linkedMapOf<String, com.opencray.runtime.process.ManagedProcessSnapshot>()

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission {
      submitFailure?.let { throw it }
      submittedInputs += userText
      val taskId = "task-${submittedTasks.size + 1}"
      val runId = "run-${submittedTasks.size + 1}"
      lastSubmittedTaskId = taskId
      val task = AgentTask(
        id = taskId,
        type = AgentTaskType.PROMPT,
        input = userText,
        policyDecision = policyDecision,
        metadata = metadata + mapOf(
          AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
          AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
          AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to visibleThroughMessageId,
        ),
        createdAtEpochMs = 1_000L,
      )
      val submission = AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
      )
      submittedTasks += task
      submissions += submission
      runSnapshotsById[runId] = AgentRunSnapshot(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        acceptedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        lifecycleState = null,
        taskState = null,
        pendingMessageId = pendingMessageId,
      )
      return submission
    }

    override fun submitTask(task: AgentTask): AgentRunSubmission {
      submitFailure?.let { throw it }
      val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?.takeIf(String::isNotBlank)
        ?: "run-${submittedTasks.size + 1}"
      val submission = AgentRunSubmission(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
      )
      lastSubmittedTaskId = task.id
      submittedTasks += task
      submissions += submission
      runSnapshotsById[runId] = AgentRunSnapshot(
        sessionId = sessionId,
        runId = runId,
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
        updatedAtEpochMs = task.createdAtEpochMs,
        lifecycleState = null,
        taskState = null,
        pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
      )
      val completion = queuedToolCompletion?.also {
        queuedToolCompletion = null
      } ?: if (queuedToolCompletions.isNotEmpty()) {
        queuedToolCompletions.removeAt(0)
      } else {
        null
      }
      completion?.also {
        completeQueuedToolCall(
          task = task,
          submission = submission,
          completion = it,
        )
      }
      return submission
    }

    override fun ensureProcessing() {
      lastSubmittedTaskId?.let(ensureProcessingTaskIds::add)
    }

    override fun requestCancel(taskId: String): Boolean {
      cancelledTaskIds += taskId
      runSnapshotsById.entries.firstOrNull { (_, snapshot) -> snapshot.taskId == taskId }?.let { (runId, snapshot) ->
        runSnapshotsById[runId] = snapshot.copy(
          updatedAtEpochMs = snapshot.updatedAtEpochMs + 1L,
          lifecycleState = QueueTaskLifecycleState.CANCELLED,
          taskState = AgentTaskState.CANCELLED,
        )
      }
      return true
    }

    override fun requestRetry(taskId: String): Boolean {
      retriedTaskIds += taskId
      return retryResult
    }

    override fun requestResumeTask(taskId: String): Boolean {
      return requestResumeTask(
        taskId = taskId,
        executionKind = com.opencray.core.orchestrator.EXECUTION_KIND_APPROVAL_RESUME,
        taskMetadataUpdates = emptyMap(),
      )
    }

    override fun requestResumeTask(
      taskId: String,
      executionKind: String,
      taskMetadataUpdates: Map<String, String>,
    ): Boolean {
      resumedTaskIds += taskId
      resumedExecutionKinds += executionKind
      resumedTaskMetadataUpdates += taskMetadataUpdates
      if (taskMetadataUpdates.isNotEmpty()) {
        runSnapshotsById.entries.firstOrNull { (_, snapshot) -> snapshot.taskId == taskId }?.let { (runId, snapshot) ->
          runSnapshotsById[runId] = snapshot.copy(
            pendingMessageId =
              taskMetadataUpdates[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
                ?: snapshot.pendingMessageId,
          )
        }
      }
      return resumeResult
    }

    fun recordResult(
      task: AgentTask,
      result: ExecutionResult,
    ) {
      val runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID].orEmpty()
      val existing = runSnapshotsById[runId] ?: return
      val lifecycleState = lifecycleStateForResult(result)
      runSnapshotsById[runId] = existing.copy(
        updatedAtEpochMs = result.finishedAtEpochMs,
        lifecycleState = lifecycleState,
        taskState = taskStateFor(lifecycleState),
        executionStatus = result.status,
        errorCode = result.errorCode,
        errorMessage = result.errorMessage,
        responseFormat = result.metadata["responseFormat"],
        resultMetadata = result.metadata,
      )
    }

    fun recordEvent(event: com.opencray.runtime.OpenCrayAgentRunEvent) {
      val existing = runSnapshotsById[event.runId] ?: return
      runSnapshotsById[event.runId] = existing.copy(
        updatedAtEpochMs = event.emittedAtEpochMs,
        managedProcessIds = mergeManagedProcessIds(
          existing = existing.managedProcessIds,
          candidate = (event as? com.opencray.runtime.OpenCrayToolResultEvent)
            ?.result
            ?.metadata
            ?.get("processId"),
        ),
        lastEvent = event,
      )
    }

    fun updateRunSnapshot(
      runId: String,
      transform: (AgentRunSnapshot) -> AgentRunSnapshot,
    ) {
      val existing = runSnapshotsById[runId] ?: return
      runSnapshotsById[runId] = transform(existing)
    }

    private fun completeQueuedToolCall(
      task: AgentTask,
      submission: AgentRunSubmission,
      completion: QueuedToolCompletion,
    ) {
      val resolvedToolName = completion.toolName
        ?: TOOL_NAME_REGEX.find(task.input)?.groupValues?.getOrNull(1)
        ?: "UnknownTool"
      val toolResult = AgentToolResult(
        toolName = resolvedToolName,
        status = when (completion.status) {
          ExecutionStatus.SUCCESS -> AgentToolResultStatus.SUCCESS
          ExecutionStatus.DENIED -> AgentToolResultStatus.DENIED
          ExecutionStatus.CANCELLED -> AgentToolResultStatus.CANCELLED
          ExecutionStatus.TIMEOUT -> AgentToolResultStatus.TIMEOUT
          ExecutionStatus.FAILED -> AgentToolResultStatus.FAILED
        },
        content = completion.content,
        errorCode = completion.errorCode,
        errorMessage = completion.errorMessage,
        metadata = completion.metadata,
      )
      recordEvent(
        OpenCrayToolResultEvent(
          runId = submission.runId,
          taskId = task.id,
          turn = 0,
          call = AgentToolCall(toolName = resolvedToolName),
          result = toolResult,
          emittedAtEpochMs = task.createdAtEpochMs + 1L,
        ),
      )
      recordResult(
        task = task,
        result = ExecutionResult(
          taskId = task.id,
          status = completion.status,
          stdout = if (completion.status == ExecutionStatus.SUCCESS) completion.content else "",
          errorCode = completion.errorCode,
          errorMessage = completion.errorMessage,
          startedAtEpochMs = task.createdAtEpochMs,
          finishedAtEpochMs = task.createdAtEpochMs + 1L,
        ),
      )
    }

    override fun listRuns(): List<AgentRunSnapshot> = runSnapshotsById.values.map(::withManagedProcessState)

    override fun findRun(runId: String): AgentRunSnapshot? = runSnapshotsById[runId]?.let(::withManagedProcessState)

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int {
      val normalizedIds = pendingMessageIds
        .filterTo(linkedSetOf()) { pendingMessageId -> pendingMessageId.isNotBlank() }
      if (normalizedIds.isEmpty()) {
        return 0
      }
      cancelledPendingMessageIdSets += normalizedIds
      val matchingTaskIds = runSnapshotsById.values
        .filter { run -> !run.isTerminal && run.pendingMessageId in normalizedIds }
        .map { run -> run.taskId }
      matchingTaskIds.forEach(::requestCancel)
      return matchingTaskIds.size
    }

    override fun resume(): SessionLifecycleState {
      onResume?.invoke(sessionId)
      return SessionLifecycleState.IDLE
    }

    override fun snapshot(): SessionQueueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 1_000L,
      tasks = emptyList<SessionQueueTaskSnapshot>(),
    )

    override fun hasPendingWork(): Boolean = false

    override fun listManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot> =
      managedProcessesById.values.toList()

    override fun listSubAgentHandles(): List<SubAgentHandleState> = subAgentHandles.toList()

    override fun retainKnownSubAgentParentRuns(parentRunIds: Set<String>) {
      retainedSubAgentParentRunIds += parentRunIds
      subAgentHandles.removeAll { handle -> handle.parentRunId !in parentRunIds }
    }

    override fun terminateRunningManagedProcesses(): List<com.opencray.runtime.process.ManagedProcessSnapshot> =
      managedProcessesById.values
        .filter { snapshot ->
          snapshot.status == com.opencray.runtime.process.ManagedProcessStatus.RUNNING
        }
        .map { snapshot ->
          terminatedProcessIds += snapshot.processId
          snapshot.copy(
            status = com.opencray.runtime.process.ManagedProcessStatus.CANCELLED,
            cancelled = true,
            updatedAtEpochMs = snapshot.updatedAtEpochMs + 1L,
            finishedAtEpochMs = snapshot.updatedAtEpochMs + 1L,
          ).also { updated ->
            managedProcessesById[updated.processId] = updated
          }
        }

    fun putManagedProcess(snapshot: com.opencray.runtime.process.ManagedProcessSnapshot) {
      managedProcessesById[snapshot.processId] = snapshot
    }

    fun putRunSnapshot(snapshot: AgentRunSnapshot) {
      runSnapshotsById[snapshot.runId] = snapshot
    }

    private fun withManagedProcessState(snapshot: AgentRunSnapshot): AgentRunSnapshot {
      val managedProcessIds = (
        snapshot.managedProcessIds +
          managedProcessesById.values
            .asSequence()
            .filter { process -> process.taskId == snapshot.taskId }
            .map { process -> process.processId }
            .toList()
        ).distinct()
      val managedProcesses = managedProcessIds.mapNotNull(managedProcessesById::get)
      val runningManagedProcessCount = managedProcessIds.count { processId ->
        managedProcessesById[processId]?.status == com.opencray.runtime.process.ManagedProcessStatus.RUNNING
      }
      return snapshot.copy(
        managedProcessIds = managedProcessIds,
        managedProcesses = managedProcesses,
        runningManagedProcessCount = runningManagedProcessCount,
        hasLiveManagedProcesses = runningManagedProcessCount > 0,
      )
    }

    private fun lifecycleStateForResult(
      result: ExecutionResult,
    ): QueueTaskLifecycleState = when {
      result.status == ExecutionStatus.SUCCESS -> QueueTaskLifecycleState.COMPLETED
      result.status == ExecutionStatus.CANCELLED -> QueueTaskLifecycleState.CANCELLED
      result.status == ExecutionStatus.DENIED &&
        (
          result.errorCode == "APPROVAL_REQUIRED" ||
            result.errorCode == "HIGH_RISK_APPROVAL_REQUIRED" ||
            result.errorCode == ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
          ) -> QueueTaskLifecycleState.SUSPENDED
      else -> QueueTaskLifecycleState.FAILED
    }

    private fun taskStateFor(
      lifecycleState: QueueTaskLifecycleState,
    ): AgentTaskState = when (lifecycleState) {
      QueueTaskLifecycleState.QUEUED,
      QueueTaskLifecycleState.RETRY_PENDING,
      -> AgentTaskState.QUEUED

      QueueTaskLifecycleState.RUNNING,
      QueueTaskLifecycleState.CANCEL_REQUESTED,
      -> AgentTaskState.RUNNING

      QueueTaskLifecycleState.SUSPENDED -> AgentTaskState.SUSPENDED
      QueueTaskLifecycleState.COMPLETED -> AgentTaskState.COMPLETED
      QueueTaskLifecycleState.FAILED -> AgentTaskState.FAILED
      QueueTaskLifecycleState.CANCELLED -> AgentTaskState.CANCELLED
    }

    private fun mergeManagedProcessIds(
      existing: List<String>,
      candidate: String?,
    ): List<String> = candidate
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { processId -> if (processId in existing) existing else existing + processId }
      ?: existing
  }

  private data class QueuedToolCompletion(
    val toolName: String? = null,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val status: ExecutionStatus = ExecutionStatus.SUCCESS,
    val errorCode: String? = null,
    val errorMessage: String? = null,
  )

  private class FailingChatSessionLocalStore(
    directory: java.io.File,
  ) : ChatSessionLocalStore(directory) {
    override fun appendSubmittedTurn(
      sessionId: String,
      userText: String,
      assistantMessageId: String,
      assistantPlaceholderText: String,
      attachments: List<ChatAttachmentEntry>,
    ): ChatSessionsState = error("transcript persistence failed")
  }

  private class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadRecords = records.isNotEmpty()
      records.clear()
      return hadRecords
    }
  }

  private fun taskCommitmentRecord(
    id: String,
    content: String,
    sourceSessionId: String,
    updatedAtEpochMs: Long,
    ttlMs: Long = 14L * 24L * 60L * 60L * 1000L,
    lastConfirmedAtEpochMs: Long = updatedAtEpochMs,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    tags = listOf(
      "kind:task_commitment",
      "scope:session",
      "status:open",
    ),
    extensions = mapOf(
      MemoryRecordExtensionKeys.KIND to "task_commitment",
      MemoryRecordExtensionKeys.SCOPE to "session",
      MemoryRecordExtensionKeys.STATUS to "open",
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sourceSessionId,
      MemoryRecordExtensionKeys.TTL_MS to ttlMs.toString(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to lastConfirmedAtEpochMs.toString(),
    ),
  )

  private class FailingMemoryStore : MemoryStore {
    override fun list(): List<MemoryRecord> = emptyList()

    override fun upsert(record: MemoryRecord) {
      error("memory store unavailable")
    }

    override fun delete(id: String): Boolean = false

    override fun clear(): Boolean = false
  }

  private fun hostRuntimeTestRunEventJournalStoreFactory(): RunEventJournalStoreFactory =
    hostRuntimeTestInvokeKtStatic(
      className = "com.opencray.app.RunEventJournalStoreFactoryKt",
      methodName = "inMemoryRunEventJournalStoreFactory",
    )

  private fun hostRuntimeTestPromptCheckpointStoreFactory(): PromptCheckpointStoreFactory =
    hostRuntimeTestInvokeKtStatic(
      className = "com.opencray.app.PromptCheckpointStoreFactoryKt",
      methodName = "inMemoryPromptCheckpointStoreFactory",
    )

  @Suppress("UNCHECKED_CAST")
  private fun <T> hostRuntimeTestInvokeKtStatic(
    className: String,
    methodName: String,
    args: Array<out Any?> = emptyArray(),
  ): T {
    val method = Class.forName(className)
      .methods
      .firstOrNull { candidate ->
        candidate.name == methodName && candidate.parameterCount == args.size
      }
      ?: error("Missing method $className::$methodName with ${args.size} args")
    return method.invoke(null, *args) as T
  }

  private fun jsonObject(raw: String): JsonObject =
    Json.parseToJsonElement(raw).jsonObject
}
