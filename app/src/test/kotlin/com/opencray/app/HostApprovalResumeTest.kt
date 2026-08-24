package com.opencray.app

import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.ERROR_RESTART_REQUIRES_EXPLICIT_RETRY
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.ERROR_LLM_RETRY_EXHAUSTED_AWAITING_RESUME
import com.opencray.runtime.OpenCrayExecutionMetadataKeys
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptCheckpointEmission
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCraySubAgentEvent
import com.opencray.runtime.OpenCraySubAgentPhase
import com.opencray.runtime.OpenCraySupplementEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.ProviderNativeWebSearchSupport
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostApprovalResumeTest : HostRuntimeTestBase() {
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
  fun finalAssistantEventPersistsFinalizationCheckpoint() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-finalization-checkpoint"))
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
      turnIndex = 3,
      toolCallCount = 1,
      transcript = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Finish the answer",
        ),
      ),
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    hostRuntime.submitChatMessage("Emit finalization checkpoint")
    val task = handle.submittedTasks.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: task.id,
        taskId = task.id,
        turn = 2,
        text = "Final answer is ready.",
        responseFormat = "text",
        isFinal = true,
        metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
          state = resumeState,
          json = json,
          checkpointBoundary = OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE,
        ),
        emittedAtEpochMs = 2_000L,
      ),
    )

    val checkpoint = promptCheckpointStore.get(task.id)
    assertEquals(PromptCheckpointKind.FINALIZATION_COMPLETE, checkpoint?.checkpointKind)
    assertEquals(null, checkpoint?.toolName)
    assertEquals(
      OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE,
      checkpoint?.promptCheckpointBoundary,
    )
    assertEquals(resumeState, checkpoint?.promptResumeState)
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
  fun successfulFinishClearsFinalizationCheckpoint() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-finalization-cleared"))
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
      turnIndex = 3,
      toolCallCount = 1,
    )
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
    )

    hostRuntime.submitChatMessage("Finish after finalization checkpoint")
    val task = handle.submittedTasks.single()
    manager.emitRunEvent(
      sessionId = activeSessionId,
      task = task,
      event = OpenCrayAssistantEvent(
        runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID] ?: task.id,
        taskId = task.id,
        turn = 2,
        text = "Final answer is ready.",
        responseFormat = "text",
        isFinal = true,
        metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
          state = resumeState,
          json = json,
          checkpointBoundary = OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE,
        ),
        emittedAtEpochMs = 2_000L,
      ),
    )

    assertEquals(PromptCheckpointKind.FINALIZATION_COMPLETE, promptCheckpointStore.get(task.id)?.checkpointKind)

    manager.emitTaskFinished(
      sessionId = activeSessionId,
      task = task,
      result = ExecutionResult(
        taskId = task.id,
        status = com.opencray.core.contracts.ExecutionStatus.SUCCESS,
        stdout = "Final answer is ready.",
        startedAtEpochMs = 2_001L,
        finishedAtEpochMs = 2_010L,
        metadata = OpenCrayPromptResumeMetadata.encodeToMetadata(
          state = resumeState,
          json = json,
          checkpointBoundary = OpenCrayPromptCheckpointBoundary.FINALIZATION_COMPLETE,
        ) + mapOf("responseFormat" to "text"),
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
  fun interruptChatRunDoesNotHoldHostLockWhileWaitingForCancellationToSettle() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-run-cancel-lock"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val cancellationSettled = CountDownLatch(1)
    lateinit var manager: RecordingRuntimeManager
    lateinit var handle: RecordingSessionHandle
    handle = RecordingSessionHandle(
      sessionId = activeSessionId,
      cancellationSettled = cancellationSettled,
      onRequestCancel = { taskId ->
        Thread {
          val task = handle.submittedTasks.single { submitted -> submitted.id == taskId }
          manager.emitTaskFinishedAfterListener(
            sessionId = activeSessionId,
            task = task,
            result = ExecutionResult(
              taskId = task.id,
              status = ExecutionStatus.CANCELLED,
              errorCode = "CANCELLED_BY_USER",
              errorMessage = "Run cancelled by user.",
              startedAtEpochMs = 1_000L,
              finishedAtEpochMs = 1_001L,
            ),
          )
          cancellationSettled.countDown()
        }.apply {
          name = "host-lock-cancellation-settlement"
          isDaemon = true
        }.start()
      },
    )
    manager = RecordingRuntimeManager().apply { putHandle(handle) }
    val hostRuntime = hostRuntime(chatStore = chatStore, runtimeManager = manager)
    val run = hostRuntime.submitChatMessage("Cancel without blocking lifecycle publication")!!

    hostRuntime.interruptChatRun(run["runId"] as String)

    assertTrue(cancellationSettled.await(2, TimeUnit.SECONDS))
    val events = hostRuntime.loadChatRuntimeSnapshot()["events"] as List<*>
    assertTrue(events.any { event -> (event as Map<*, *>)["kind"] == "interrupted" })
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
}
