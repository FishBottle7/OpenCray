package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.OpenCrayFinalAttachment
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.context.RuntimeConversationAttachment
import java.nio.file.Files
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostChatSessionTest : HostRuntimeTestBase() {
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
    val facade = com.opencray.app.facade.media.LocalMediaSpeechSettingsFacade.create(store)
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
    assertEquals("••••-key", savedVideo["apiKey"])
    assertEquals(true, savedVideo["hasCredential"])
    assertEquals("-key", savedVideo["credentialHint"])
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

    val addedSnapshot = observedSnapshots.last()
    assertEquals(null, addedSnapshot["runtimeActivity"])
    val addedTodos = addedSnapshot["todos"] as List<*>
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

    val clearedSnapshot = observedSnapshots.last()
    assertEquals(null, clearedSnapshot["runtimeActivity"])
    val clearedTodos = clearedSnapshot["todos"] as List<*>
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
}
