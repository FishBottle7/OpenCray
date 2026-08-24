package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayPromptResumeMetadata
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationMessageKind
import com.opencray.runtime.context.RuntimeConversationCommentary
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeConversationToolCall
import com.opencray.runtime.context.RuntimeConversationToolResult
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentMailbox
import com.opencray.runtime.subagent.SubAgentMailboxMessage
import com.opencray.runtime.subagent.SubAgentSessionLink
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTranscriptReplayTest : HostRuntimeTestBase() {
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
  fun chatRuntimeSnapshotProjectsClosedSubAgentEdgeFromReplayEvents() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-runtime-closed"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val transcriptMessages = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"subagent","run_id":"replay-run-close","task_id":"replay-task-close","agent_id":"child-handle-close","turn":0,"phase":"started","child_run_id":"child-run-close","child_task_id":"child-task-close","label":"Inspect README","subagent_type":"researcher","context_mode":"minimal","depth":1,"execution_state":"running","continuation_kind":"none","resumable":false,"requires_user_action":false,"is_high_risk":false}""",
      ),
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = """{"event_kind":"subagent","run_id":"replay-run-close","task_id":"replay-task-close","agent_id":"child-handle-close","turn":1,"phase":"cancelled","child_run_id":"child-run-close","child_task_id":"child-task-close","label":"Inspect README","subagent_type":"researcher","context_mode":"minimal","depth":1,"summary":"Delegated child handle closed.","execution_state":"cancelled","continuation_kind":"none","resumable":false,"requires_user_action":false,"is_high_risk":false,"closed":true}""",
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
    val events = (runtimeActivity["events"] as List<*>).map { entry -> entry as Map<*, *> }
    val subAgents = (runtimeActivity["subAgents"] as List<*>).map { entry -> entry as Map<*, *> }
    val closedEvent = events.last { event -> event["kind"] == "subagent" }
    val closedChild = subAgents.single()

    assertEquals(true, closedEvent["closed"])
    assertEquals("child-handle-close", closedEvent["agentId"])
    assertEquals("cancelled", closedEvent["phase"])
    assertEquals("cancelled", closedEvent["status"])
    assertEquals("Delegated child handle closed.", closedEvent["text"])
    assertEquals(true, closedChild["closed"])
    assertEquals("child-handle-close", closedChild["agentId"])
    assertEquals("cancelled", closedChild["phase"])
    assertEquals("cancelled", closedChild["status"])
    assertEquals("Delegated child handle closed.", closedChild["summary"])
    assertEquals(2, closedChild["eventCount"])
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
                contextModeSource = "profile_default",
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
    assertEquals("profile_default", child["contextModeSource"])
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
        contextModeSource = "policy_profile_override",
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
    assertEquals("policy_profile_override", child["contextModeSource"])
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
  fun chatRuntimeSnapshotBuildsSubAgentRegistryFromDurableClosedHandleSource() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-runtime-durable-closed"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = activeSessionId).apply {
      closedSubAgentHandles += SubAgentHandleState(
        agentId = "child-durable-closed",
        childRunId = "child-run-durable-closed",
        childTaskId = "child-task-durable-closed",
        description = "Closed runtime snapshot",
        prompt = "Inspect the durable closed runtime snapshot pipeline.",
        subagentType = "researcher",
        contextMode = "minimal",
        parentRunId = "run-parent-durable-closed",
        parentTaskId = "task-parent-durable-closed",
        parentTurn = 1,
        depth = 1,
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.CANCELLED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "Delegated child handle was explicitly closed.",
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

    assertEquals("child-durable-closed", child["agentId"])
    assertEquals("cancelled", child["phase"])
    assertEquals("cancelled", child["status"])
    assertEquals(true, child["closed"])
    assertEquals(false, child["hasActiveExecution"])
    assertEquals("Delegated child handle was explicitly closed.", child["summary"])
  }


  @Test
  fun chatRuntimeSnapshotBuildsSubAgentRegistryFromDurableSessionLinkSource() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-subagent-runtime-link"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = activeSessionId).apply {
      putRunSnapshot(
        AgentRunSnapshot(
          sessionId = activeSessionId,
          runId = "run-parent-link",
          taskId = "task-parent-link",
          acceptedAtEpochMs = 1_000L,
          updatedAtEpochMs = 1_300L,
          lifecycleState = QueueTaskLifecycleState.RUNNING,
          taskState = AgentTaskState.RUNNING,
          attempt = 1,
        ),
      )
    }
    val subAgentSessionLinkStoreFactory = inMemorySubAgentSessionLinkStoreFactory()
    subAgentSessionLinkStoreFactory.forChatSession(activeSessionId).upsert(
      SubAgentSessionLink(
        parentSessionId = activeSessionId,
        parentRunId = "run-parent-link",
        agentId = "child-link-only",
        childSessionId = "child-session-link-only",
        childRootRunId = "child-run-link-only",
        childRootTaskId = "child-task-link-only",
        subagentType = "researcher",
        contextMode = "minimal",
        depth = 1,
        label = "Inspect detached child link",
        status = "background_running",
        closed = false,
        createdAtEpochMs = 900L,
        updatedAtEpochMs = 1_300L,
      ),
    )
    manager.putHandle(handle)
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      subAgentSessionLinkStoreFactory = subAgentSessionLinkStoreFactory,
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val subAgents = (runtimeActivity["subAgents"] as List<*>).map { entry ->
      entry as Map<*, *>
    }
    val child = subAgents.single()

    assertEquals("run-parent-link", child["parentRunId"])
    assertEquals("child-link-only", child["agentId"])
    assertEquals("child-session-link-only", child["childSessionId"])
    assertEquals("child-run-link-only", child["childRunId"])
    assertEquals("child-task-link-only", child["childTaskId"])
    assertEquals("Inspect detached child link", child["label"])
    assertEquals("researcher", child["subagentType"])
    assertEquals("minimal", child["contextMode"])
    assertEquals(1, child["depth"])
    assertEquals("resumed", child["phase"])
    assertEquals("background_running", child["status"])
    assertEquals("background_running", child["executionState"])
    assertEquals("background_resume", child["continuationKind"])
    assertEquals(false, child["resumable"])
    assertEquals(false, child["requiresUserAction"])
    assertEquals(false, child["isHighRisk"])
    assertEquals(false, child["closed"])
    assertEquals(true, child["hasActiveExecution"])
    assertEquals(0, child["eventCount"])
    assertNull(child["summary"])
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
      assertTrue((observedEvents[0]["streamInstanceId"] as? String)?.isNotBlank() == true)
      assertEquals(observedEvents[0]["streamInstanceId"], observedEvents[1]["streamInstanceId"])
      assertEquals(1L, observedEvents[0]["sequence"])
      assertEquals(2L, observedEvents[1]["sequence"])
      assertEquals(observedEvents[0]["sequence"], observedEvents[0]["lastSequence"])
      assertEquals(observedEvents[1]["sequence"], observedEvents[1]["lastSequence"])
      assertTrue((observedEvents[0]["eventId"] as? String)?.isNotBlank() == true)
      assertTrue((observedEvents[1]["eventId"] as? String)?.isNotBlank() == true)
      val snapshot = hostRuntime.loadChatRuntimeSnapshot()
      assertEquals(observedEvents[1]["streamInstanceId"], snapshot["streamInstanceId"])
      assertEquals(observedEvents[1]["sequence"], snapshot["lastSequence"])
    } finally {
      disposer()
    }
  }

  @Test
  fun assistantDraftUpdatesDoNotPersistRuntimeEventsIntoInspectorHistory() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-live-draft-runtime-events"))
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
    )

    val submission = hostRuntime.submitChatMessage("Stream a long answer")!!
    val task = handle.submittedTasks.single()
    manager.emitAssistantDraftUpdated(
      sessionId = activeSessionId,
      task = task,
      text = "Growing streamed answer",
      emittedAtEpochMs = 1_500L,
    )

    val runtimeActivity = hostRuntime.loadChatRuntimeSnapshot()
    val liveDrafts = (runtimeActivity["liveAssistantDrafts"] as List<*>).map { draft ->
      draft as Map<*, *>
    }
    val events = (runtimeActivity["events"] as List<*>).map { event ->
      event as Map<*, *>
    }

    assertEquals(1, liveDrafts.size)
    assertEquals("Growing streamed answer", liveDrafts.single()["text"])
    assertEquals(
      task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID],
      liveDrafts.single()["pendingMessageId"],
    )
    assertFalse(events.any { event ->
      event["kind"] == "assistant_phase" &&
        event["stage"] == "Draft" &&
        event["runId"] == submission["runId"]
    })
  }

  @Test
  fun recreatedHostsDoNotRestoreTransientAssistantDrafts() {
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

    firstHost.submitChatMessage("Stream a long answer")!!
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
        event["stage"] == "Draft"
    }
    val messages = (recreatedHost.loadChatSnapshot()["messages"] as List<*>).map { message ->
      message as Map<*, *>
    }

    assertTrue(liveDrafts.isEmpty())
    assertTrue(draftEvents.isEmpty())
    assertEquals("Thinking", messages.last()["text"])
  }

  @Test
  fun runtimeEventDeltasEmitIncrementalEventsWithSequence() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-event-deltas"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = activeSessionId)
    manager.putHandle(handle)
    val mainThreadPoster = QueuedMainThreadPoster()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      mainThreadPoster = mainThreadPoster,
    )
    val observedDeltas = mutableListOf<Map<String, Any?>>()
    val disposer = hostRuntime.observeRuntimeEventDeltas { payload ->
      observedDeltas += payload
    }
    val observedChatSnapshots = mutableListOf<Map<String, Any?>>()
    val chatDisposer = hostRuntime.observeChat { payload ->
      observedChatSnapshots += payload
    }
    mainThreadPoster.flush()
    observedChatSnapshots.clear()

    try {
      val submission = hostRuntime.submitChatMessage("Read the README")!!
      mainThreadPoster.flush()
      observedChatSnapshots.clear()
      observedDeltas.clear()
      val task = handle.submittedTasks.single()
      manager.emitRunEvent(
        sessionId = activeSessionId,
        task = task,
        event = OpenCrayToolCallEvent(
          runId = submission["runId"] as String,
          taskId = task.id,
          turn = 0,
          call = AgentToolCall(toolName = "Read"),
          emittedAtEpochMs = 1_500L,
        ),
      )
      mainThreadPoster.flush()

      assertEquals(1, observedDeltas.size)
      val delta = observedDeltas.single()
      assertEquals(activeSessionId, delta["sessionId"])
      assertTrue((delta["sequence"] as Long) >= 1L)
      assertEquals(delta["sequence"], delta["lastSequence"])
      assertTrue((delta["streamInstanceId"] as? String)?.isNotBlank() == true)
      assertTrue((delta["eventId"] as? String)?.isNotBlank() == true)
      assertTrue(observedChatSnapshots.isEmpty())
      val activeRuns = delta["activeRuns"] as List<*>
      val activeRun = activeRuns.single() as Map<*, *>
      assertEquals(submission["runId"], activeRun["runId"])
      val events = delta["events"] as List<*>
      val event = events.single() as Map<*, *>
      assertEquals("tool_call", event["kind"])
      assertEquals("Read", event["toolName"])
      assertEquals(submission["runId"], event["runId"])
      assertTrue((event["eventId"] as? String)?.isNotBlank() == true)
      val fullSnapshot = hostRuntime.loadChatRuntimeSnapshot()
      assertEquals(delta["streamInstanceId"], fullSnapshot["streamInstanceId"])
      assertEquals(delta["sequence"], fullSnapshot["lastSequence"])
    } finally {
      disposer()
      chatDisposer()
    }
  }

  @Test
  fun runtimeEventDeltaSequenceIsNotConsumedWhenNoDeltaPayloadIsEmitted() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-runtime-event-delta-no-payload"))
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val manager = RecordingRuntimeManager()
    val handle = RecordingSessionHandle(sessionId = activeSessionId)
    manager.putHandle(handle)
    val mainThreadPoster = QueuedMainThreadPoster()
    val hostRuntime = hostRuntime(
      chatStore = chatStore,
      runtimeManager = manager,
      mainThreadPoster = mainThreadPoster,
    )
    val observedDeltas = mutableListOf<Map<String, Any?>>()
    val deltaDisposer = hostRuntime.observeRuntimeEventDeltas { payload ->
      observedDeltas += payload
    }
    val observedRuntimeSnapshots = mutableListOf<Map<String, Any?>>()
    val runtimeDisposer = hostRuntime.observeChatRuntime { payload ->
      observedRuntimeSnapshots += payload
    }
    mainThreadPoster.flush()
    observedDeltas.clear()
    observedRuntimeSnapshots.clear()

    try {
      val hiddenTask = AgentTask(
        id = "hidden-task",
        type = AgentTaskType.PROMPT,
        input = "hidden",
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = "TEST_ALLOW",
        ),
        metadata = mapOf(
          AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to "hidden-run",
        ),
        createdAtEpochMs = 1_000L,
      )
      manager.emitTaskStarted(activeSessionId, hiddenTask)
      mainThreadPoster.flush()

      assertTrue(observedDeltas.isEmpty())
      assertTrue(observedRuntimeSnapshots.isEmpty())

      val submission = hostRuntime.submitChatMessage("Read the README")!!
      mainThreadPoster.flush()
      observedDeltas.clear()
      observedRuntimeSnapshots.clear()
      val task = handle.submittedTasks.single()
      manager.emitRunEvent(
        sessionId = activeSessionId,
        task = task,
        event = OpenCrayToolCallEvent(
          runId = submission["runId"] as String,
          taskId = task.id,
          turn = 0,
          call = AgentToolCall(toolName = "Read"),
          emittedAtEpochMs = 1_500L,
        ),
      )
      mainThreadPoster.flush()

      assertEquals(1, observedDeltas.size)
      assertEquals(1L, observedDeltas.single()["sequence"])
    } finally {
      deltaDisposer()
      runtimeDisposer()
    }
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
}
