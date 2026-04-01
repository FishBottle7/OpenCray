package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.llm.LiteLlmAttemptOutcome
import com.opencray.llm.LiteLlmAttemptRecord
import com.opencray.llm.LiteLlmCompletionMode
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.policy.SafetySettingsMetadataKeys
import com.opencray.runtime.OpenCrayPromptCheckpointBoundary
import com.opencray.runtime.OpenCrayPromptResumeState
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.subagent.InMemorySubAgentExecutionCoordinator
import com.opencray.runtime.subagent.SubAgentActiveExecution
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionKey
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentMailbox
import com.opencray.runtime.subagent.SubAgentMailboxMessage
import com.opencray.runtime.subagent.SubAgentMetadataKeys
import com.opencray.runtime.subagent.SubAgentResultMetadataKeys
import com.opencray.runtime.skills.SkillCatalog
import com.opencray.runtime.skills.SkillCatalogEntry
import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl
import com.opencray.skills.SkillPermissionDecision
import com.opencray.skills.SkillPermissionRule
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayAgentRuntimeSubAgentTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun taskToolRunsResearcherChildWithMinimalContext() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-minimal").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}""",
        """{"type":"final","answer":"README says hello."}""",
        """{"type":"final","answer":"Child summary: README says hello."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = promptTask("Please delegate README inspection and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Child summary: README says hello.", result.stdout)
    assertEquals(3, gateway.requests.size)
    val taskResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .single()
      .result
      .metadata
    assertEquals("delegate_task", taskResultMetadata["capabilityKind"])
    assertEquals("delegation", taskResultMetadata["intentCategory"])
    assertEquals("subagent_task", taskResultMetadata["delegationIntentKind"])
    assertEquals("researcher", taskResultMetadata["delegationSubagentType"])
    assertEquals("minimal", taskResultMetadata["delegationContextMode"])
    assertEquals("Glob,Grep,LS,Read", taskResultMetadata["delegationAllowedTools"])
    assertEquals("completed", taskResultMetadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals("none", taskResultMetadata[SubAgentResultMetadataKeys.CONTINUATION_KIND])
    assertEquals("false", taskResultMetadata[SubAgentResultMetadataKeys.CONTINUATION_RESUMABLE])
    assertEquals(
      "false",
      taskResultMetadata[SubAgentResultMetadataKeys.CONTINUATION_REQUIRES_USER_ACTION],
    )
    assertEquals("false", taskResultMetadata[SubAgentResultMetadataKeys.CONTINUATION_IS_HIGH_RISK])
    assertEquals("README says hello.", taskResultMetadata[SubAgentResultMetadataKeys.SUMMARY_HEADLINE])
    assertFalse(gateway.requests[1].prompt.contains("Please delegate README inspection and then answer."))
    assertTrue(gateway.requests[1].prompt.contains("Read README.md and summarize it."))
    val childToolNames = gateway.requests[1].tools.map { definition -> definition.name }
    assertTrue(childToolNames.contains("Read"))
    assertFalse(childToolNames.contains("Task"))
    assertFalse(childToolNames.contains("Write"))
    assertFalse(childToolNames.contains("Bash"))
    assertFalse(childToolNames.contains("python_exec"))
    assertTrue(gateway.requests[2].prompt.contains("README says hello."))
    assertTrue(gateway.requests[2].prompt.contains("[Recent Working Observations]"))
    assertTrue(
      gateway.requests[2].prompt.contains(
        "Recent successful workspace and delegation observations from the current task are available below.",
      ),
    )
    assertTrue(
      gateway.requests[2].prompt.contains(
        "Task description=inspect readme subagent=researcher state=completed context=minimal",
      ),
    )
    assertTrue(gateway.requests[2].prompt.contains("Summary: README says hello."))
  }

  @Test
  fun taskToolAcceptsExplorerAliasAndPreservesAliasInMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-explorer-alias").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"explorer"}}""",
        """{"type":"final","answer":"README says hello."}""",
        """{"type":"final","answer":"Explorer child summary: README says hello."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = promptTask("Please delegate README inspection through the explorer alias."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Explorer child summary: README says hello.", result.stdout)
    val taskResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .single()
      .result
      .metadata
    assertEquals("explorer", taskResultMetadata["delegationSubagentType"])
    assertEquals("minimal", taskResultMetadata["delegationContextMode"])
    assertFalse(gateway.requests[1].prompt.contains("Please delegate README inspection through the explorer alias."))
    val childToolNames = gateway.requests[1].tools.map { definition -> definition.name }
    assertTrue(childToolNames.contains("Read"))
    assertFalse(childToolNames.contains("Task"))
    assertFalse(childToolNames.contains("Write"))
    assertTrue(
      gateway.requests[2].prompt.contains(
        "Task description=inspect readme subagent=explorer state=completed context=minimal",
      ),
    )
  }

  @Test
  fun taskToolRunsGeneralPurposeChildWithDelegatedParentSummary() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-delegated").toPath()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"continue investigation","prompt":"Check the repo layout and report back.","subagent_type":"general-purpose"}}""",
        """{"type":"final","answer":"Layout inspected."}""",
        """{"type":"final","answer":"Delegated result received."}""",
      ),
    )
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
    )

    val result = runtime.execute(
      task = promptTask("Investigate the codebase and continue carefully."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Delegated result received.", result.stdout)
    assertEquals(3, gateway.requests.size)
    assertTrue(gateway.requests[1].prompt.contains("Delegated parent context for this child run."))
    assertTrue(gateway.requests[1].prompt.contains("user_goal=Investigate the codebase and continue carefully."))
    assertTrue(gateway.requests[1].prompt.contains("Check the repo layout and report back."))
  }

  @Test
  fun taskToolAcceptsDefaultAliasAndUsesDelegatedContext() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-default-alias").toPath()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"continue investigation","prompt":"Check the repo layout and report back.","subagent_type":"default"}}""",
        """{"type":"final","answer":"Layout inspected through default."}""",
        """{"type":"final","answer":"Default child result received."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = promptTask("Investigate the codebase and continue carefully."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Default child result received.", result.stdout)
    val taskResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .single()
      .result
      .metadata
    assertEquals("default", taskResultMetadata["delegationSubagentType"])
    assertEquals("delegated", taskResultMetadata["delegationContextMode"])
    assertTrue(gateway.requests[1].prompt.contains("Delegated parent context for this child run."))
    assertTrue(gateway.requests[1].prompt.contains("user_goal=Investigate the codebase and continue carefully."))
    assertTrue(gateway.requests[1].prompt.contains("Check the repo layout and report back."))
  }

  @Test
  fun taskToolRejectsExplicitMirroredContextOverride() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-mirrored-override").toPath()
    val gateway = RecordingGateway(outputs = emptyList())
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      sessionContext = AgentRuntimeSessionContext(
        conversation = listOf(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.USER,
            content = "Prior parent context that mirrored child should receive.",
          ),
        ),
      ),
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"reuse prior context","prompt":"Continue from the preserved conversation context.","subagent_type":"general-purpose","context_mode":"mirrored"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("INVALID_SUBAGENT_TASK", result.errorCode)
    assertEquals(
      "Unsupported Task context_mode 'mirrored'. Expected one of: minimal, delegated. mirrored is reserved for internal-only child-runtime flows.",
      result.errorMessage,
    )
    assertEquals(0, gateway.requests.size)
  }

  @Test
  fun spawnAgentRejectsExplicitMirroredContextOverride() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-spawn-mirrored-override").toPath()
    val gateway = RecordingGateway(outputs = emptyList())
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"reuse prior context","prompt":"Continue from the preserved conversation context.","subagent_type":"general-purpose","context_mode":"mirrored"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("INVALID_SUBAGENT_TASK", result.errorCode)
    assertEquals(
      "Unsupported spawn_agent context_mode 'mirrored'. Expected one of: minimal, delegated. mirrored is reserved for internal-only child-runtime flows.",
      result.errorMessage,
    )
    assertEquals(0, gateway.requests.size)
  }

  @Test
  fun delegatedChildReceivesRecentWorkspaceObservationSummaries() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-delegated-observations").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"continue investigation","prompt":"Check the repo layout and report back.","subagent_type":"general-purpose"}}""",
        """{"type":"final","answer":"Layout inspected."}""",
        """{"type":"final","answer":"Delegated result received."}""",
      ),
    )
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
    )

    val result = runtime.execute(
      task = promptTask("Investigate the codebase and continue carefully."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Delegated result received.", result.stdout)
    assertTrue(gateway.requests.size in 4..5)
    assertTrue(gateway.requests[2].prompt.contains("recent_observations:"))
    assertTrue(gateway.requests[2].prompt.contains("Read file_path=README.md"))
    assertFalse(gateway.requests[2].prompt.contains("tool_result Read"))
    assertFalse(gateway.requests[2].prompt.contains("hello"))
  }

  @Test
  fun taskToolInheritsParentActiveSkillCapsuleAndRestrictsChildTools() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-active-skill-task").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}""",
        """{"type":"final","answer":"README says hello."}""",
        """{"type":"final","answer":"Parent received the child result."}""",
      ),
    )
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      sessionContext = sessionContextWithSkillCatalog(
        allowedToolPatterns = listOf("task", "read"),
      ),
      promptResumeState = activeSkillPromptResumeState(),
    )

    val result = runtime.execute(
      task = promptTask("Delegate the README inspection under the active skill."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Parent received the child result.", result.stdout)
    assertEquals(3, gateway.requests.size)
    val childPrompt = gateway.requests[1].prompt
    assertTrue(childPrompt.contains("A skill is now active for this run."))
    assertTrue(childPrompt.contains("name=focused-read"))
    assertTrue(childPrompt.contains("activation_source=skill_read"))
    assertTrue(childPrompt.contains("Follow the focused-read workflow."))
    val childToolNames = gateway.requests[1].tools.map { definition -> definition.name }
    assertTrue(childToolNames.contains("Read"))
    assertFalse(childToolNames.contains("LS"))
    assertFalse(childToolNames.contains("Grep"))
    assertFalse(childToolNames.contains("Glob"))
    assertFalse(childToolNames.contains("Task"))
  }

  @Test
  fun taskToolRejectsUnknownExplicitContextMode() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-invalid-context-mode").toPath()
    val gateway = RecordingGateway(outputs = emptyList())
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"nested","prompt":"delegate again","subagent_type":"researcher","context_mode":"full"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("INVALID_SUBAGENT_TASK", result.errorCode)
    assertEquals(
      "Unknown Task context_mode 'full'. Expected one of: minimal, delegated.",
      result.errorMessage,
    )
    assertEquals(0, gateway.requests.size)
  }

  @Test
  fun nestedTaskDelegationStopsAtConfiguredDepth() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-depth").toPath()
    val gateway = RecordingGateway(outputs = emptyList())
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"nested","prompt":"delegate again","subagent_type":"researcher"}}""",
        metadata = mapOf(SubAgentMetadataKeys.DEPTH to "1"),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("SUBAGENT_DEPTH_EXCEEDED", result.errorCode)
    assertEquals(0, gateway.requests.size)
  }

  @Test
  fun taskCancellationBeforeDelegationStartsReturnsTopLevelCancelledResult() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-cancelled").toPath()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}""",
      ),
    )
    val eventSink = RecordingEventSink()
    var cancellationCheckCount = 0
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = promptTask("Delegate the README inspection."),
      hooks = runtimeHooks(
        isCancellationRequested = {
          val shouldCancel = cancellationCheckCount >= 1
          cancellationCheckCount += 1
          shouldCancel
        },
      ),
    )

    assertEquals(ExecutionStatus.CANCELLED, result.status)
    assertEquals("AGENT_CANCELLED", result.errorCode)
    assertEquals("cancelled", result.metadata["responseFormat"])
    assertEquals(0, gateway.requests.size)
    assertTrue(eventSink.events.none { event -> event is OpenCraySubAgentEvent })
  }

  @Test
  fun taskToolFailsWhenChildAttemptsDisallowedWriteTool() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-disallowed-write").toPath()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Write","arguments":{"file_path":"notes.txt","content":"forbidden"}}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"attempt forbidden write","prompt":"Try to update notes.txt.","subagent_type":"researcher"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("SUBAGENT_TOOL_NOT_ALLOWED", result.errorCode)
    assertEquals("FAILED", result.metadata["childExecutionStatus"])
    assertEquals("failed", result.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals("none", result.metadata[SubAgentResultMetadataKeys.CONTINUATION_KIND])
    assertEquals("false", result.metadata[SubAgentResultMetadataKeys.CONTINUATION_RESUMABLE])
    assertEquals("SUBAGENT_TOOL_NOT_ALLOWED", result.metadata[SubAgentResultMetadataKeys.ERROR_CODE])
    assertEquals("Task", result.metadata["toolName"])
    assertEquals(
      listOf(OpenCraySubAgentPhase.STARTED, OpenCraySubAgentPhase.FAILED),
      eventSink.events
        .filterIsInstance<OpenCraySubAgentEvent>()
        .map(OpenCraySubAgentEvent::phase),
    )
    assertEquals(1, gateway.requests.size)
  }

  @Test
  fun taskToolRunsWorkerChildWithEditableToolSurface() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-worker-edit").toPath()
    val notesFile = workspaceRoot.resolve("notes.txt")
    Files.write(
      notesFile,
      "draft\nTODO\n".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"update notes","prompt":"Replace TODO with DONE in notes.txt and report back.","subagent_type":"worker"}}""",
        """{"type":"tool_call","tool_name":"Edit","arguments":{"file_path":"notes.txt","old_string":"TODO","new_string":"DONE"}}""",
        """{"type":"final","answer":"notes.txt updated."}""",
        """{"type":"final","answer":"Worker completed the edit."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = promptTask("Delegate a focused notes edit and continue."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Worker completed the edit.", result.stdout)
    assertEquals("draft\nDONE\n", String(Files.readAllBytes(notesFile), StandardCharsets.UTF_8))
    assertEquals(4, gateway.requests.size)
    val taskResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .single()
      .result
      .metadata
    assertEquals("worker", taskResultMetadata["delegationSubagentType"])
    assertEquals("delegated", taskResultMetadata["delegationContextMode"])
    assertEquals("Edit,Glob,Grep,LS,MultiEdit,Read,Write", taskResultMetadata["delegationAllowedTools"])
    assertEquals("completed", taskResultMetadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    val childToolNames = gateway.requests[1].tools.map { definition -> definition.name }
    assertTrue(childToolNames.contains("Edit"))
    assertTrue(childToolNames.contains("MultiEdit"))
    assertTrue(childToolNames.contains("Write"))
    assertFalse(childToolNames.contains("Task"))
    assertFalse(childToolNames.contains("Bash"))
    assertFalse(childToolNames.contains("python_exec"))
    assertTrue(gateway.requests[3].prompt.contains("notes.txt updated."))
  }

  @Test
  fun taskToolFailsWhenWorkerChildAttemptsDisallowedBash() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-worker-disallowed-bash").toPath()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Bash","arguments":{"command":"echo hi"}}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"attempt bash","prompt":"Try to run echo hi.","subagent_type":"worker"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("SUBAGENT_TOOL_NOT_ALLOWED", result.errorCode)
    assertEquals("FAILED", result.metadata["childExecutionStatus"])
    assertEquals("failed", result.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals("worker", result.metadata["subagentType"])
    assertTrue(result.errorMessage.orEmpty().contains("unavailable"))
    assertEquals(
      listOf(OpenCraySubAgentPhase.STARTED, OpenCraySubAgentPhase.FAILED),
      eventSink.events
        .filterIsInstance<OpenCraySubAgentEvent>()
        .map(OpenCraySubAgentEvent::phase),
    )
    assertEquals(1, gateway.requests.size)
  }

  @Test
  fun taskToolCapturesWorkerEditApprovalContinuationMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-worker-edit-approval").toPath()
    val notesFile = workspaceRoot.resolve("notes.txt")
    Files.write(
      notesFile,
      "draft\nTODO\n".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Edit","arguments":{"file_path":"notes.txt","old_string":"TODO","new_string":"DONE"}}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"edit notes","prompt":"Replace TODO with DONE in notes.txt.","subagent_type":"worker"}}""",
        metadata = mapOf(
          SafetySettingsMetadataKeys.EXECUTION_MODE to "safe",
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("DENIED", result.metadata["childExecutionStatus"])
    assertEquals("waiting_approval", result.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals("prompt_resume", result.metadata[SubAgentResultMetadataKeys.CONTINUATION_KIND])
    assertEquals("true", result.metadata[SubAgentResultMetadataKeys.CONTINUATION_RESUMABLE])
    assertEquals("true", result.metadata[SubAgentResultMetadataKeys.CONTINUATION_REQUIRES_USER_ACTION])
    assertEquals("false", result.metadata[SubAgentResultMetadataKeys.CONTINUATION_IS_HIGH_RISK])
    assertEquals("Edit", result.metadata["normalizedToolName"])
    assertEquals("STANDARD", result.metadata["approvalRisk"])
    assertEquals("ASK_SAFE_WRITE", result.metadata["policyReasonCode"])
    assertEquals("file", result.metadata["targetKind"])
    assertEquals("inside_workspace", result.metadata["workspaceRelation"])
    assertEquals("notes.txt", result.metadata["primaryTargetPath"])
    assertEquals("draft\nTODO\n", String(Files.readAllBytes(notesFile), StandardCharsets.UTF_8))
    assertTrue(
      result.metadata[SubAgentApprovalResumeMetadata.KEY_PROMPT_RESUME_JSON]
        ?.isNotBlank() == true,
    )
    assertEquals("Edit", result.metadata[SubAgentApprovalResumeMetadata.KEY_APPROVED_TOOL_NAME])
    val subAgentEvents = eventSink.events.filterIsInstance<OpenCraySubAgentEvent>()
    assertEquals(
      listOf(OpenCraySubAgentPhase.STARTED, OpenCraySubAgentPhase.FAILED),
      subAgentEvents.map(OpenCraySubAgentEvent::phase),
    )
    assertEquals(
      listOf(SubAgentExecutionState.RUNNING, SubAgentExecutionState.WAITING_APPROVAL),
      subAgentEvents.map(OpenCraySubAgentEvent::executionState),
    )
    assertEquals(
      listOf(SubAgentContinuationKind.NONE, SubAgentContinuationKind.PROMPT_RESUME),
      subAgentEvents.map(OpenCraySubAgentEvent::continuationKind),
    )
    assertEquals(listOf(false, true), subAgentEvents.map(OpenCraySubAgentEvent::resumable))
    assertEquals(listOf(false, true), subAgentEvents.map(OpenCraySubAgentEvent::requiresUserAction))
    assertEquals(listOf(false, false), subAgentEvents.map(OpenCraySubAgentEvent::isHighRisk))
    assertEquals(1, gateway.requests.size)
  }

  @Test
  fun approvedWorkerResumeContinuesChildEditWithoutReaskingApproval() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-worker-edit-resume").toPath()
    val notesFile = workspaceRoot.resolve("notes.txt")
    Files.write(
      notesFile,
      "draft\nTODO\n".toByteArray(StandardCharsets.UTF_8),
    )
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Edit","arguments":{"file_path":"notes.txt","old_string":"TODO","new_string":"DONE"}}""",
      ),
    )
    val task = directToolCallTask(
      """{"type":"tool_call","tool_name":"Task","arguments":{"description":"edit notes","prompt":"Replace TODO with DONE in notes.txt.","subagent_type":"worker"}}""",
      metadata = mapOf(
        SafetySettingsMetadataKeys.EXECUTION_MODE to "safe",
      ),
    )
    val initialRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = initialGateway,
    )

    val initialResult = initialRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )
    val approvedResume = requireNotNull(
      SubAgentApprovalResumeMetadata.decodeFromMetadata(
        metadata = initialResult.metadata,
        json = TEST_JSON,
      ),
    )

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Approved edit completed."}""",
      ),
    )
    val resumedEventSink = RecordingEventSink()
    val resumedRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = resumedGateway,
      approvedSubAgentResume = approvedResume,
      eventSink = resumedEventSink,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertTrue(resumedResult.stdout.contains("Approved edit completed."))
    assertEquals("draft\nDONE\n", String(Files.readAllBytes(notesFile), StandardCharsets.UTF_8))
    assertEquals(1, resumedGateway.requests.size)
    assertTrue(
      resumedGateway.requests.single().messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolName == "Edit"
      },
    )
    assertEquals(
      listOf(
        OpenCraySubAgentPhase.STARTED,
        OpenCraySubAgentPhase.RESUMED,
        OpenCraySubAgentPhase.COMPLETED,
      ),
      resumedEventSink.events
        .filterIsInstance<OpenCraySubAgentEvent>()
        .map(OpenCraySubAgentEvent::phase),
    )
  }

  @Test
  fun rejectedWorkerResumeContinuesChildWithoutReaskingApproval() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-worker-edit-reject-resume").toPath()
    val notesFile = workspaceRoot.resolve("notes.txt")
    Files.write(
      notesFile,
      "draft\nTODO\n".toByteArray(StandardCharsets.UTF_8),
    )
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Edit","arguments":{"file_path":"notes.txt","old_string":"TODO","new_string":"DONE"}}""",
      ),
    )
    val task = directToolCallTask(
      """{"type":"tool_call","tool_name":"Task","arguments":{"description":"edit notes","prompt":"Replace TODO with DONE in notes.txt.","subagent_type":"worker"}}""",
      metadata = mapOf(
        SafetySettingsMetadataKeys.EXECUTION_MODE to "safe",
      ),
    )
    val initialRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = initialGateway,
    )

    val initialResult = initialRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )
    val rejectedResume = requireNotNull(
      SubAgentApprovalResumeMetadata.decodeFromMetadata(
        metadata = initialResult.metadata,
        json = TEST_JSON,
      ),
    )

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"I skipped the blocked edit and left notes.txt unchanged."}""",
      ),
    )
    val resumedEventSink = RecordingEventSink()
    val resumedRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = resumedGateway,
      rejectedSubAgentResume = rejectedResume,
      eventSink = resumedEventSink,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertTrue(resumedResult.stdout.contains("I skipped the blocked edit"))
    assertEquals("draft\nTODO\n", String(Files.readAllBytes(notesFile), StandardCharsets.UTF_8))
    assertEquals(1, resumedGateway.requests.size)
    assertTrue(
      resumedGateway.requests.single().messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolName == "Edit" &&
          message.toolResult?.isError == true
      },
    )
    assertEquals(
      listOf(
        OpenCraySubAgentPhase.STARTED,
        OpenCraySubAgentPhase.RESUMED,
        OpenCraySubAgentPhase.COMPLETED,
      ),
      resumedEventSink.events
        .filterIsInstance<OpenCraySubAgentEvent>()
        .map(OpenCraySubAgentEvent::phase),
    )
  }

  @Test
  fun taskToolFailsWhenChildAttemptsNestedTaskDelegation() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-nested-task-denied").toPath()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"nested","prompt":"Keep delegating.","subagent_type":"researcher"}}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"attempt nested child","prompt":"Try to delegate again.","subagent_type":"researcher"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("SUBAGENT_TOOL_NOT_ALLOWED", result.errorCode)
    assertEquals("FAILED", result.metadata["childExecutionStatus"])
    assertEquals("failed", result.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals("none", result.metadata[SubAgentResultMetadataKeys.CONTINUATION_KIND])
    assertEquals("false", result.metadata[SubAgentResultMetadataKeys.CONTINUATION_RESUMABLE])
    assertEquals("SUBAGENT_TOOL_NOT_ALLOWED", result.metadata[SubAgentResultMetadataKeys.ERROR_CODE])
    assertTrue(result.errorMessage.orEmpty().contains("unavailable"))
    assertEquals(
      listOf(OpenCraySubAgentPhase.STARTED, OpenCraySubAgentPhase.FAILED),
      eventSink.events
        .filterIsInstance<OpenCraySubAgentEvent>()
        .map(OpenCraySubAgentEvent::phase),
    )
    assertEquals(1, gateway.requests.size)
  }

  @Test
  fun taskToolCapturesChildApprovalContinuationMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-approval-workspace").toPath()
    val externalReadRoot = temporaryFolder.newFolder("subagent-approval-external").toPath()
    val externalFile = externalReadRoot.resolve("notes.txt")
    Files.write(
      externalFile,
      "external context".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"${jsonEscape(externalFile.toString())}"}}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      readRoots = setOf(workspaceRoot, externalReadRoot),
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect external notes","prompt":"Read the external notes file and summarize it.","subagent_type":"researcher"}}""",
        metadata = mapOf(
          SafetySettingsMetadataKeys.EXECUTION_MODE to "safe",
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("DENIED", result.metadata["childExecutionStatus"])
    assertEquals("waiting_approval", result.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals("prompt_resume", result.metadata[SubAgentResultMetadataKeys.CONTINUATION_KIND])
    assertEquals("true", result.metadata[SubAgentResultMetadataKeys.CONTINUATION_RESUMABLE])
    assertEquals(
      "true",
      result.metadata[SubAgentResultMetadataKeys.CONTINUATION_REQUIRES_USER_ACTION],
    )
    assertEquals("false", result.metadata[SubAgentResultMetadataKeys.CONTINUATION_IS_HIGH_RISK])
    assertEquals("Read", result.metadata["normalizedToolName"])
    assertTrue(
      result.metadata[SubAgentApprovalResumeMetadata.KEY_PROMPT_RESUME_JSON]
        ?.isNotBlank() == true,
    )
    assertEquals("Read", result.metadata[SubAgentApprovalResumeMetadata.KEY_APPROVED_TOOL_NAME])
    val subAgentEvents = eventSink.events.filterIsInstance<OpenCraySubAgentEvent>()
    assertEquals(
      listOf(OpenCraySubAgentPhase.STARTED, OpenCraySubAgentPhase.FAILED),
      subAgentEvents.map(OpenCraySubAgentEvent::phase),
    )
    assertEquals(
      listOf(SubAgentExecutionState.RUNNING, SubAgentExecutionState.WAITING_APPROVAL),
      subAgentEvents.map(OpenCraySubAgentEvent::executionState),
    )
    assertEquals(
      listOf(SubAgentContinuationKind.NONE, SubAgentContinuationKind.PROMPT_RESUME),
      subAgentEvents.map(OpenCraySubAgentEvent::continuationKind),
    )
    assertEquals(listOf(false, true), subAgentEvents.map(OpenCraySubAgentEvent::resumable))
    assertEquals(listOf(false, true), subAgentEvents.map(OpenCraySubAgentEvent::requiresUserAction))
    assertEquals(listOf(false, false), subAgentEvents.map(OpenCraySubAgentEvent::isHighRisk))
    assertEquals(1, gateway.requests.size)
  }

  @Test
  fun approvedSubagentResumeContinuesChildWithoutReaskingApproval() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-resume-workspace").toPath()
    val externalReadRoot = temporaryFolder.newFolder("subagent-resume-external").toPath()
    val externalFile = externalReadRoot.resolve("notes.txt")
    Files.write(
      externalFile,
      "external context".toByteArray(StandardCharsets.UTF_8),
    )
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"${jsonEscape(externalFile.toString())}"}}""",
      ),
    )
    val initialRuntime = runtime(
      workspaceRoot = workspaceRoot,
      readRoots = setOf(workspaceRoot, externalReadRoot),
      gateway = initialGateway,
    )
    val initialResult = initialRuntime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect external notes","prompt":"Read the external notes file and summarize it.","subagent_type":"researcher"}}""",
        metadata = mapOf(
          SafetySettingsMetadataKeys.EXECUTION_MODE to "safe",
        ),
      ),
      hooks = runtimeHooks(),
    )
    val approvedResume = requireNotNull(
      SubAgentApprovalResumeMetadata.decodeFromMetadata(
        metadata = initialResult.metadata,
        json = TEST_JSON,
      ),
    )

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"External notes say resume works."}""",
      ),
    )
    val resumedRuntime = runtime(
      workspaceRoot = workspaceRoot,
      readRoots = setOf(workspaceRoot, externalReadRoot),
      gateway = resumedGateway,
      approvedSubAgentResume = approvedResume,
    )

    val resumedResult = resumedRuntime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect external notes","prompt":"Read the external notes file and summarize it.","subagent_type":"researcher"}}""",
        metadata = mapOf(
          SafetySettingsMetadataKeys.EXECUTION_MODE to "safe",
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertTrue(resumedResult.stdout.contains("External notes say resume works."))
    assertEquals(1, resumedGateway.requests.size)
  }

  @Test
  fun rejectedSubagentResumeContinuesChildWithoutReaskingApproval() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-reject-resume-workspace").toPath()
    val externalReadRoot = temporaryFolder.newFolder("subagent-reject-resume-external").toPath()
    val externalFile = externalReadRoot.resolve("notes.txt")
    Files.write(
      externalFile,
      "external context".toByteArray(StandardCharsets.UTF_8),
    )
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"${jsonEscape(externalFile.toString())}"}}""",
      ),
    )
    val initialRuntime = runtime(
      workspaceRoot = workspaceRoot,
      readRoots = setOf(workspaceRoot, externalReadRoot),
      gateway = initialGateway,
    )
    val initialResult = initialRuntime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect external notes","prompt":"Read the external notes file and summarize it.","subagent_type":"researcher"}}""",
        metadata = mapOf(
          SafetySettingsMetadataKeys.EXECUTION_MODE to "safe",
        ),
      ),
      hooks = runtimeHooks(),
    )
    val rejectedResume = requireNotNull(
      SubAgentApprovalResumeMetadata.decodeFromMetadata(
        metadata = initialResult.metadata,
        json = TEST_JSON,
      ),
    )

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"I skipped the blocked external read and continued."}""",
      ),
    )
    val resumedRuntime = runtime(
      workspaceRoot = workspaceRoot,
      readRoots = setOf(workspaceRoot, externalReadRoot),
      gateway = resumedGateway,
      rejectedSubAgentResume = rejectedResume,
    )

    val resumedResult = resumedRuntime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect external notes","prompt":"Read the external notes file and summarize it.","subagent_type":"researcher"}}""",
        metadata = mapOf(
          SafetySettingsMetadataKeys.EXECUTION_MODE to "safe",
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertTrue(resumedResult.stdout.contains("I skipped the blocked external read and continued."))
    assertEquals(1, resumedGateway.requests.size)
  }

  @Test
  fun taskToolEmitsParentAnchoredSubagentLifecycleEvents() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-events").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}""",
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"final","answer":"README says hello."}""",
        """{"type":"final","answer":"Child summary: README says hello."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = promptTask("Delegate the README inspection."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Child summary: README says hello.", result.stdout)
    val subAgentEvents = eventSink.events.filterIsInstance<OpenCraySubAgentEvent>()
    assertEquals(
      listOf(OpenCraySubAgentPhase.STARTED, OpenCraySubAgentPhase.COMPLETED),
      subAgentEvents.map(OpenCraySubAgentEvent::phase),
    )
    assertEquals(
      listOf(SubAgentExecutionState.RUNNING, SubAgentExecutionState.COMPLETED),
      subAgentEvents.map(OpenCraySubAgentEvent::executionState),
    )
    assertEquals(
      listOf(SubAgentContinuationKind.NONE, SubAgentContinuationKind.NONE),
      subAgentEvents.map(OpenCraySubAgentEvent::continuationKind),
    )
    assertEquals(
      listOf("Task"),
      eventSink.events
        .filterIsInstance<OpenCrayToolCallEvent>()
        .map { event -> event.call.toolName },
    )
    assertEquals(
      listOf("Task"),
      eventSink.events
        .filterIsInstance<OpenCrayToolResultEvent>()
        .map { event -> event.result.toolName },
    )
    assertTrue(
      eventSink.events.none { event ->
        event is OpenCrayToolCallEvent && event.call.toolName == "Read"
      },
    )
  }

  @Test
  fun spawnAgentStartsChildImmediatelyAndWaitAgentReadsCompletedHandle() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-spawn-wait").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val childMayFinish = CountDownLatch(1)
    var parentTurn = 0
    val gateway = ScriptedGateway { request ->
      when {
        requestHasTool(request, "Read") && !requestHasTool(request, "spawn_agent") -> {
          childMayFinish.await(5, TimeUnit.SECONDS)
          """{"type":"final","answer":"README says hello."}"""
        }

        requestHasTool(request, "spawn_agent") -> when (parentTurn++) {
          0 -> """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}"""
          1 -> {
            childMayFinish.countDown()
            """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-1"}}"""
          }

          2 -> """{"type":"final","answer":"Delegated wait completed."}"""
          else -> error("Unexpected parent turn for ${request.requestId}.")
        }

        else -> error("Unexpected prompt for ${request.requestId}.")
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = promptTask("Queue a child and then wait for it."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Delegated wait completed.", result.stdout)
    assertEquals(4, gateway.requests.size)
    val childRequest = gateway.requests.first { request ->
      requestHasTool(request, "Read") && !requestHasTool(request, "spawn_agent")
    }
    assertTrue(childRequest.prompt.contains("Read README.md and summarize it."))
    val spawnResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .first { event -> event.call.toolName == "spawn_agent" }
      .result
      .metadata
    assertEquals("child-1", spawnResultMetadata["agentId"])
    assertEquals("background_running", spawnResultMetadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    val waitResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .first { event -> event.call.toolName == "wait_agent" }
      .result
      .metadata
    assertEquals("completed", waitResultMetadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    val subAgentEvents = eventSink.events.filterIsInstance<OpenCraySubAgentEvent>()
    assertEquals(
      listOf(
        OpenCraySubAgentPhase.STARTED,
        OpenCraySubAgentPhase.RESUMED,
        OpenCraySubAgentPhase.COMPLETED,
      ),
      subAgentEvents.map(OpenCraySubAgentEvent::phase),
    )
    assertEquals(
      listOf(
        SubAgentExecutionState.BACKGROUND_QUEUED,
        SubAgentExecutionState.BACKGROUND_RUNNING,
        SubAgentExecutionState.COMPLETED,
      ),
      subAgentEvents.map(OpenCraySubAgentEvent::executionState),
    )
  }

  @Test
  fun waitAgentReattachesToSharedCoordinatorStateAfterRuntimeRecreation() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-shared-coordinator-reattach").toPath()
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val runningHandle = SubAgentHandleState.queued(
      agentId = "child-1",
      childRunId = "child-run-1",
      childTaskId = "child-task-1",
      description = "inspect readme",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "parent-run-1",
      parentTaskId = "prompt-task",
      parentTurn = 0,
      depth = 1,
      activeSkillName = null,
      activeSkillActivationSource = null,
      createdAtEpochMs = 1_000L,
    ).copy(
      snapshot = SubAgentExecutionSnapshot.backgroundRunning(
        headline = "Queued delegated child run started.",
      ),
      updatedAtEpochMs = 1_100L,
    )
    coordinator.upsertHandle(runningHandle)
    val executionKey = SubAgentExecutionKey.from(runningHandle)
    val executor = Executors.newSingleThreadExecutor()
    val future = FutureTask<Unit> {
      Thread.sleep(250L)
      coordinator.upsertHandle(
        runningHandle.copy(
          snapshot = SubAgentExecutionSnapshot(
            state = SubAgentExecutionState.COMPLETED,
            continuationKind = SubAgentContinuationKind.NONE,
            resumable = false,
            requiresUserAction = false,
            isHighRisk = false,
            headline = "README says hello.",
          ),
          childExecutionStatus = ExecutionStatus.SUCCESS.name,
          updatedAtEpochMs = 1_200L,
        ),
      )
      coordinator.takeActiveExecution(executionKey)
    }
    assertEquals(
      null,
      coordinator.registerActiveExecution(
        executionKey,
        SubAgentActiveExecution(
          executor = executor,
          future = future,
          cancelRequested = AtomicBoolean(false),
          closed = AtomicBoolean(false),
        ),
      ),
    )
    executor.execute(future)
    val eventSink = RecordingEventSink()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-1"}}""",
        """{"type":"final","answer":"Recovered shared child handle."}""",
      ),
    )
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
      promptResumeState = OpenCrayPromptResumeState(
        turnIndex = 0,
        toolCallCount = 0,
        subAgentHandles = listOf(runningHandle),
      ),
      subAgentExecutionCoordinator = coordinator,
    )

    val result = runtime.execute(
      task = promptTask("Harvest the delegated child after recreating the runtime."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Recovered shared child handle.", result.stdout)
    val waitResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .first { event -> event.call.toolName == "wait_agent" }
      .result
      .metadata
    assertEquals("completed", waitResultMetadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals("README says hello.", waitResultMetadata[SubAgentResultMetadataKeys.SUMMARY_HEADLINE])
    executor.shutdownNow()
  }

  @Test
  fun spawnAgentStartsChildImmediatelyUnderInheritedActiveSkill() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-active-skill-spawn-wait").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val childStarted = CountDownLatch(1)
    val childMayFinish = CountDownLatch(1)
    var parentTurn = 0
    val gateway = ScriptedGateway { request ->
      when {
        requestHasTool(request, "Read") && !requestHasTool(request, "spawn_agent") -> {
          childStarted.countDown()
          assertTrue(childMayFinish.await(5, TimeUnit.SECONDS))
          """{"type":"final","answer":"README says hello."}"""
        }

        requestHasTool(request, "spawn_agent") -> when (parentTurn++) {
          0 -> """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}"""
          1 -> {
            assertTrue(childStarted.await(5, TimeUnit.SECONDS))
            childMayFinish.countDown()
            """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-1"}}"""
          }

          2 -> """{"type":"final","answer":"Queued child finished under the inherited skill."}"""
          else -> error("Unexpected parent turn for ${request.requestId}.")
        }

        else -> error("Unexpected prompt for ${request.requestId}.")
      }
    }
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      sessionContext = sessionContextWithSkillCatalog(
        allowedToolPatterns = listOf("spawn_agent", "wait_agent", "read"),
      ),
      promptResumeState = activeSkillPromptResumeState(),
    )

    val result = runtime.execute(
      task = promptTask("Queue the child and wait for it under the active skill."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Queued child finished under the inherited skill.", result.stdout)
    assertEquals(4, gateway.requests.size)
    val childPrompt = gateway.requests.first { request ->
      requestHasTool(request, "Read") && !requestHasTool(request, "spawn_agent")
    }.prompt
    assertTrue(childPrompt.contains("A skill is now active for this run."))
    assertTrue(childPrompt.contains("name=focused-read"))
    val childToolNames = gateway.requests.first { request ->
      requestHasTool(request, "Read") && !requestHasTool(request, "spawn_agent")
    }.tools.map { definition -> definition.name }
    assertTrue(childToolNames.contains("Read"))
    assertFalse(childToolNames.contains("LS"))
    assertFalse(childToolNames.contains("Grep"))
    assertFalse(childToolNames.contains("Glob"))
    assertFalse(childToolNames.contains("spawn_agent"))
    assertFalse(childToolNames.contains("wait_agent"))
  }

  @Test
  fun sendInputFailsAfterSpawnedChildAlreadyCompleted() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-send-input").toPath()
    val childFinished = CountDownLatch(1)
    var parentTurn = 0
    val gateway = ScriptedGateway { request ->
      when {
        requestHasTool(request, "Read") && !requestHasTool(request, "spawn_agent") -> {
          childFinished.countDown()
          """{"type":"final","answer":"Inspected README.md only."}"""
        }

        requestHasTool(request, "spawn_agent") -> when (parentTurn++) {
          0 -> """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"inspect docs","prompt":"Inspect README.md only.","subagent_type":"researcher"}}"""
          1 -> {
            assertTrue(childFinished.await(5, TimeUnit.SECONDS))
            """{"type":"tool_call","tool_name":"send_input","arguments":{"agent_id":"child-1","message":"Also inspect docs/notes.md and mention it."}}"""
          }

          2 -> """{"type":"final","answer":"Completed child rejected more input."}"""
          else -> error("Unexpected parent turn for ${request.requestId}.")
        }

        else -> error("Unexpected prompt for ${request.requestId}.")
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = promptTask("Start a child and then try to add more input after it finishes."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Completed child rejected more input.", result.stdout)
    assertEquals(4, gateway.requests.size)
    assertTrue(
      gateway.requests.any { request ->
        request.prompt.contains("Inspect README.md only.")
      },
    )
    val sendInputResult = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .first { event -> event.call.toolName == "send_input" }
      .result
    assertEquals(AgentToolResultStatus.FAILED, sendInputResult.status)
    assertEquals("SUBAGENT_NOT_QUEUEABLE", sendInputResult.errorCode)
  }

  @Test
  fun closeAgentCancelsRunningHandleAndClosesIt() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-close").toPath()
    val childStarted = CountDownLatch(1)
    val childMayFinish = CountDownLatch(1)
    var parentTurn = 0
    val gateway = ScriptedGateway { request ->
      when {
        requestHasTool(request, "Read") && !requestHasTool(request, "spawn_agent") -> {
          childStarted.countDown()
          childMayFinish.await(5, TimeUnit.SECONDS)
          """{"type":"final","answer":"README says hello."}"""
        }

        requestHasTool(request, "spawn_agent") -> when (parentTurn++) {
          0 -> """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}"""
          1 -> {
            assertTrue(childStarted.await(5, TimeUnit.SECONDS))
            """{"type":"tool_call","tool_name":"close_agent","arguments":{"agent_id":"child-1"}}"""
          }

          2 -> {
            childMayFinish.countDown()
            """{"type":"final","answer":"Queued child closed."}"""
          }
          else -> error("Unexpected parent turn for ${request.requestId}.")
        }

        else -> error("Unexpected prompt for ${request.requestId}.")
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = promptTask("Queue a child and then close it."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Queued child closed.", result.stdout)
    assertEquals(4, gateway.requests.size)
    val closeResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .first { event -> event.call.toolName == "close_agent" }
      .result
      .metadata
    assertEquals("true", closeResultMetadata["closed"])
    assertEquals(
      listOf(OpenCraySubAgentPhase.STARTED, OpenCraySubAgentPhase.RESUMED, OpenCraySubAgentPhase.CANCELLED),
      eventSink.events
        .filterIsInstance<OpenCraySubAgentEvent>()
        .map(OpenCraySubAgentEvent::phase),
    )
  }

  @Test
  fun spawnedChildContinuesAfterParentFinalAndCanBeHarvestedInLaterRun() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-detached-between-runs").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val childMayFinish = CountDownLatch(1)
    var parentTurn = 0
    val gateway = ScriptedGateway { request ->
      when {
        requestHasTool(request, "Read") && !requestHasTool(request, "spawn_agent") -> {
          assertTrue(childMayFinish.await(5, TimeUnit.SECONDS))
          """{"type":"final","answer":"README says hello."}"""
        }

        requestHasTool(request, "spawn_agent") -> when (parentTurn++) {
          0 -> """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}"""
          1 -> """{"type":"final","answer":"Leaving the child running in the background."}"""
          2 -> {
            childMayFinish.countDown()
            """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-1"}}"""
          }

          3 -> """{"type":"final","answer":"Later wait completed."}"""
          else -> error("Unexpected parent turn for ${request.requestId}.")
        }

        else -> error("Unexpected prompt for ${request.requestId}.")
      }
    }
    val firstEventSink = RecordingEventSink()
    val firstRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = firstEventSink,
      subAgentExecutionCoordinator = coordinator,
    )

    val firstResult = firstRuntime.execute(
      task = promptTask("Launch a child and report back before waiting."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, firstResult.status)
    assertEquals("Leaving the child running in the background.", firstResult.stdout)
    val runningHandle = coordinator.allHandles().single()
    assertEquals("child-1", runningHandle.agentId)
    assertEquals(SubAgentExecutionState.BACKGROUND_RUNNING, runningHandle.snapshot.state)
    assertTrue(coordinator.activeExecution(SubAgentExecutionKey.from(runningHandle)) != null)
    assertTrue(
      firstEventSink.events
        .filterIsInstance<OpenCraySubAgentEvent>()
        .none { event -> event.phase == OpenCraySubAgentPhase.CANCELLED },
    )

    val secondEventSink = RecordingEventSink()
    val secondRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = secondEventSink,
      subAgentExecutionCoordinator = coordinator,
    )

    val secondResult = secondRuntime.execute(
      task = promptTask("Harvest the background child now."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, secondResult.status)
    assertEquals("Later wait completed.", secondResult.stdout)
    val completedHandle = coordinator.allHandles().single()
    assertEquals(SubAgentExecutionState.COMPLETED, completedHandle.snapshot.state)
    assertEquals(null, coordinator.activeExecution(SubAgentExecutionKey.from(completedHandle)))
    val waitResultMetadata = secondEventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .first { event -> event.call.toolName == "wait_agent" }
      .result
      .metadata
    assertEquals("completed", waitResultMetadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
  }

  @Test
  fun seededSubAgentHandlesCanBeHarvestedFromLaterPromptRunWithDifferentParentRunId() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-seeded-later-run").toPath()
    val completedHandle = SubAgentHandleState(
      agentId = "child-seeded",
      childRunId = "child-run-seeded",
      childTaskId = "child-task-seeded",
      description = "Inspect README",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "run-parent-old",
      parentTaskId = "task-parent-old",
      parentTurn = 1,
      depth = 1,
      snapshot = SubAgentExecutionSnapshot(
        state = SubAgentExecutionState.COMPLETED,
        continuationKind = SubAgentContinuationKind.NONE,
        resumable = false,
        requiresUserAction = false,
        isHighRisk = false,
        headline = "README says hello.",
      ),
      childExecutionStatus = ExecutionStatus.SUCCESS.name,
      childTurnCount = 1,
      childToolCallCount = 1,
      createdAtEpochMs = 900L,
      updatedAtEpochMs = 1_100L,
    )
    var parentTurn = 0
    val gateway = ScriptedGateway {
      when (parentTurn++) {
        0 -> """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-seeded"}}"""
        1 -> """{"type":"final","answer":"Harvested the seeded child."}"""
        else -> error("Unexpected parent turn.")
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
      seededSubAgentHandles = listOf(completedHandle),
    )

    val result = runtime.execute(
      task = promptTask("Harvest the seeded child from a later run."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Harvested the seeded child.", result.stdout)
    val waitResult = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .first { event -> event.call.toolName == "wait_agent" }
      .result
    assertEquals(AgentToolResultStatus.SUCCESS, waitResult.status)
    assertEquals("child-seeded", waitResult.metadata["agentId"])
    assertEquals("child-run-seeded", waitResult.metadata["childRunId"])
    assertEquals("completed", waitResult.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertTrue(waitResult.content.contains("README says hello."))
  }

  @Test
  fun directWaitAgentResumesColdRestartedQueuedChildFromDurableCheckpoint() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-cold-restart-direct-wait").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val seededHandle = SubAgentHandleState(
      agentId = "child-cold",
      childRunId = "child-run-cold",
      childTaskId = "child-task-cold",
      description = "Inspect README",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "run-parent-cold",
      parentTaskId = "task-parent-cold",
      parentTurn = 1,
      depth = 1,
      snapshot = SubAgentExecutionSnapshot.backgroundRunning(
        headline = "Delegated child run is still running in the background.",
      ),
      childPromptResumeState = OpenCrayPromptResumeState(
        turnIndex = 0,
        toolCallCount = 0,
      ),
      childPromptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.PRE_MODEL_REQUEST,
      childPromptCheckpointAtEpochMs = 950L,
      createdAtEpochMs = 900L,
      updatedAtEpochMs = 1_100L,
    )
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"final","answer":"README says hello."}""",
      ),
    )
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      seededSubAgentHandles = listOf(seededHandle),
      subAgentExecutionCoordinator = coordinator,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-cold"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertTrue(result.stdout.contains("README says hello."))
    val completedHandle = coordinator.allHandles().single()
    assertEquals(SubAgentExecutionState.COMPLETED, completedHandle.snapshot.state)
    assertEquals(null, completedHandle.childPromptResumeState)
    assertEquals(null, completedHandle.childPromptCheckpointBoundary)
    assertEquals(null, completedHandle.childPromptCheckpointAtEpochMs)
  }

  @Test
  fun spawnAgentCapturesApprovalContinuationAndApprovedResumeContinuesChild() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-wait-approval").toPath()
    val notesFile = workspaceRoot.resolve("notes.txt")
    Files.write(
      notesFile,
      "draft\nTODO\n".toByteArray(StandardCharsets.UTF_8),
    )
    val initialChildStarted = CountDownLatch(1)
    var initialParentTurn = 0
    val initialGateway = ScriptedGateway { request ->
      when {
        requestHasTool(request, "Edit") && !requestHasTool(request, "spawn_agent") -> {
          initialChildStarted.countDown()
          """{"type":"tool_call","tool_name":"Edit","arguments":{"file_path":"notes.txt","old_string":"TODO","new_string":"DONE"}}"""
        }

        requestHasTool(request, "spawn_agent") -> when (initialParentTurn++) {
          0 -> """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"edit notes","prompt":"Replace TODO with DONE in notes.txt.","subagent_type":"worker"}}"""
          1 -> {
            assertTrue(initialChildStarted.await(5, TimeUnit.SECONDS))
            """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-1"}}"""
          }

          else -> error("Unexpected parent turn for ${request.requestId}.")
        }

        else -> error("Unexpected prompt for ${request.requestId}.")
      }
    }
    val initialRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = initialGateway,
    )

    val initialResult = initialRuntime.execute(
      task = promptTask(
        "Start a worker child.",
        metadata = mapOf(
          SafetySettingsMetadataKeys.EXECUTION_MODE to "safe",
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.DENIED, initialResult.status)
    assertEquals("APPROVAL_REQUIRED", initialResult.errorCode)
    val approvedResume = requireNotNull(
      SubAgentApprovalResumeMetadata.decodeFromMetadata(
        metadata = initialResult.metadata,
        json = TEST_JSON,
      ),
    )
    assertEquals("child-1", approvedResume.agentId)
    val promptResumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = initialResult.metadata,
        json = TEST_JSON,
      ),
    )
    assertEquals(1, promptResumeState.subAgentHandles.size)
    assertEquals("child-1", promptResumeState.subAgentHandles.single().agentId)
    assertEquals(
      SubAgentExecutionState.WAITING_APPROVAL,
      promptResumeState.subAgentHandles.single().snapshot.state,
    )

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Approved edit completed."}""",
        """{"type":"final","answer":"Approved spawn completed."}""",
      ),
    )
    val resumedEventSink = RecordingEventSink()
    val resumedRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = resumedGateway,
      promptResumeState = promptResumeState,
      approvedSubAgentResume = approvedResume,
      eventSink = resumedEventSink,
    )

    val resumedResult = resumedRuntime.execute(
      task = promptTask(
        "Start a worker child.",
        metadata = mapOf(
          SafetySettingsMetadataKeys.EXECUTION_MODE to "safe",
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Approved spawn completed.", resumedResult.stdout)
    assertEquals("draft\nDONE\n", String(Files.readAllBytes(notesFile), StandardCharsets.UTF_8))
    assertEquals(2, resumedGateway.requests.size)
    assertTrue(
      resumedGateway.requests[0].messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolName == "Edit"
      },
    )
    assertEquals(
      listOf(OpenCraySubAgentPhase.RESUMED, OpenCraySubAgentPhase.COMPLETED),
      resumedEventSink.events
        .filterIsInstance<OpenCraySubAgentEvent>()
        .map(OpenCraySubAgentEvent::phase),
    )
  }

  @Test
  fun sendInputAppendsSupplementalParentInstructionWhileChildIsWaitingApproval() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-send-input-waiting-approval").toPath()
    val notesFile = workspaceRoot.resolve("notes.txt")
    Files.write(
      notesFile,
      "draft\nTODO\n".toByteArray(StandardCharsets.UTF_8),
    )
    val initialChildStarted = CountDownLatch(1)
    var initialParentTurn = 0
    val initialGateway = ScriptedGateway { request ->
      when {
        requestHasTool(request, "Edit") && !requestHasTool(request, "spawn_agent") -> {
          initialChildStarted.countDown()
          """{"type":"tool_call","tool_name":"Edit","arguments":{"file_path":"notes.txt","old_string":"TODO","new_string":"DONE"}}"""
        }

        requestHasTool(request, "spawn_agent") -> when (initialParentTurn++) {
          0 -> """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"edit notes","prompt":"Replace TODO with DONE in notes.txt.","subagent_type":"worker"}}"""
          1 -> {
            assertTrue(initialChildStarted.await(5, TimeUnit.SECONDS))
            """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-1"}}"""
          }

          else -> error("Unexpected parent turn for ${request.requestId}.")
        }

        else -> error("Unexpected prompt for ${request.requestId}.")
      }
    }
    val initialRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = initialGateway,
    )

    val initialResult = initialRuntime.execute(
      task = promptTask(
        "Start a worker child.",
        metadata = mapOf(
          SafetySettingsMetadataKeys.EXECUTION_MODE to "safe",
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.DENIED, initialResult.status)
    assertEquals("APPROVAL_REQUIRED", initialResult.errorCode)
    val approvedResume = requireNotNull(
      SubAgentApprovalResumeMetadata.decodeFromMetadata(
        metadata = initialResult.metadata,
        json = TEST_JSON,
      ),
    )
    val promptResumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = initialResult.metadata,
        json = TEST_JSON,
      ),
    )
    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"send_input","arguments":{"agent_id":"child-1","message":"After the edit, confirm whether notes.txt now contains DONE."}}""",
        """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-1"}}""",
        """{"type":"final","answer":"Approved edit completed with follow-up."}""",
        """{"type":"final","answer":"Approved wait with supplement completed."}""",
      ),
    )
    val resumedEventSink = RecordingEventSink()
    val resumedRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = resumedGateway,
      promptResumeState = promptResumeState.copy(
        pendingActions = emptyList(),
        pendingToolCall = null,
        nextActionIndex = 0,
      ),
      approvedSubAgentResume = approvedResume,
      eventSink = resumedEventSink,
    )

    val resumedResult = resumedRuntime.execute(
      task = promptTask(
        "Start a worker child.",
        metadata = mapOf(
          SafetySettingsMetadataKeys.EXECUTION_MODE to "safe",
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Approved wait with supplement completed.", resumedResult.stdout)
    assertEquals("draft\nDONE\n", String(Files.readAllBytes(notesFile), StandardCharsets.UTF_8))
    assertEquals(4, resumedGateway.requests.size)
    val sendInputResultMetadata = resumedEventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .first { event -> event.call.toolName == "send_input" }
      .result
      .metadata
    assertEquals("1", sendInputResultMetadata["supplementalInputCount"])
    assertEquals("1", sendInputResultMetadata["mailboxPendingInputCount"])
    assertTrue(
      resumedGateway.requests[2].prompt.contains(
        "After the edit, confirm whether notes.txt now contains DONE.",
      ),
    )
    assertTrue(
      resumedGateway.requests[2].messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.USER &&
          message.content == "After the edit, confirm whether notes.txt now contains DONE."
      },
    )
    assertEquals(
      listOf(OpenCraySubAgentPhase.RESUMED, OpenCraySubAgentPhase.COMPLETED),
      resumedEventSink.events
        .filterIsInstance<OpenCraySubAgentEvent>()
        .map(OpenCraySubAgentEvent::phase),
    )
  }

  @Test
  fun listSubagentsReturnsMailboxAndLifecycleStateForKnownHandles() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-list").toPath()
    val waitingHandle = SubAgentHandleState(
      agentId = "child-waiting",
      childRunId = "child-run-waiting",
      childTaskId = "child-task-waiting",
      description = "Edit notes after approval",
      prompt = "Replace TODO with DONE in notes.txt.",
      mailbox = SubAgentMailbox(
        messages = listOf(
          SubAgentMailboxMessage(
            messageId = "msg-1",
            text = "First follow-up.",
            createdAtEpochMs = 1_100L,
          ),
          SubAgentMailboxMessage(
            messageId = "msg-2",
            text = "Second follow-up.",
            createdAtEpochMs = 1_200L,
          ),
        ),
        lastDeliveredMessageId = "msg-1",
      ),
      subagentType = "worker",
      contextMode = "delegated",
      parentRunId = "parent-run-a",
      parentTaskId = "parent-task-a",
      parentTurn = 2,
      depth = 1,
      snapshot = SubAgentExecutionSnapshot(
        state = SubAgentExecutionState.WAITING_APPROVAL,
        continuationKind = SubAgentContinuationKind.PROMPT_RESUME,
        resumable = true,
        requiresUserAction = true,
        isHighRisk = false,
        headline = "Waiting for edit approval.",
      ),
      childPromptResumeState = OpenCrayPromptResumeState(
        turnIndex = 1,
        toolCallCount = 1,
      ),
      childPromptCheckpointBoundary = OpenCrayPromptCheckpointBoundary.TOOL_RESULT_COMMITTED,
      childPromptCheckpointAtEpochMs = 1_250L,
      childTurnCount = 1,
      childToolCallCount = 1,
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_300L,
    )
    val completedHandle = SubAgentHandleState(
      agentId = "child-done",
      childRunId = "child-run-done",
      childTaskId = "child-task-done",
      description = "Inspect README",
      prompt = "Read README.md and summarize it.",
      subagentType = "explorer",
      contextMode = "minimal",
      parentRunId = "parent-run-b",
      parentTaskId = "parent-task-b",
      parentTurn = 1,
      depth = 1,
      snapshot = SubAgentExecutionSnapshot(
        state = SubAgentExecutionState.COMPLETED,
        continuationKind = SubAgentContinuationKind.NONE,
        resumable = false,
        requiresUserAction = false,
        isHighRisk = false,
        headline = "README says hello.",
      ),
      childExecutionStatus = ExecutionStatus.SUCCESS.name,
      childTurnCount = 1,
      childToolCallCount = 1,
      createdAtEpochMs = 900L,
      updatedAtEpochMs = 1_100L,
    )
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = RecordingGateway(outputs = emptyList()),
      promptResumeState = OpenCrayPromptResumeState(
        turnIndex = 0,
        toolCallCount = 0,
        subAgentHandles = listOf(waitingHandle, completedHandle),
      ),
    )

    val result = runtime.execute(
      task = directToolCallTask("""{"type":"tool_call","tool_name":"list_subagents","arguments":{}}"""),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("2", result.metadata["subagentCount"])
    assertEquals("1", result.metadata["openSubagentCount"])
    val payload = TEST_JSON.parseToJsonElement(result.stdout).jsonObject
    assertEquals("2", payload.getValue("count").jsonPrimitive.content)
    assertEquals("1", payload.getValue("openCount").jsonPrimitive.content)
    val subagents = payload.getValue("subagents").jsonArray
    assertEquals(2, subagents.size)
    val firstHandle = subagents[0].jsonObject
    assertEquals("child-waiting", firstHandle.getValue("agentId").jsonPrimitive.content)
    assertEquals("parent-run-a", firstHandle.getValue("parentRunId").jsonPrimitive.content)
    assertEquals("worker", firstHandle.getValue("subagentType").jsonPrimitive.content)
    assertEquals("waiting_approval", firstHandle.getValue("state").jsonPrimitive.content)
    assertEquals("prompt_resume", firstHandle.getValue("continuationKind").jsonPrimitive.content)
    assertEquals("true", firstHandle.getValue("resumable").jsonPrimitive.content)
    assertEquals("true", firstHandle.getValue("requiresUserAction").jsonPrimitive.content)
    assertEquals("Waiting for edit approval.", firstHandle.getValue("summary").jsonPrimitive.content)
    assertEquals("2", firstHandle.getValue("mailboxMessageCount").jsonPrimitive.content)
    assertEquals("1", firstHandle.getValue("mailboxPendingMessageCount").jsonPrimitive.content)
    assertEquals("msg-1", firstHandle.getValue("mailboxLastDeliveredMessageId").jsonPrimitive.content)
    assertEquals("1", firstHandle.getValue("childTurnCount").jsonPrimitive.content)
    assertEquals("1", firstHandle.getValue("childToolCallCount").jsonPrimitive.content)
    assertEquals(
      "tool_result_committed",
      firstHandle.getValue("childPromptCheckpointBoundary").jsonPrimitive.content,
    )
    val secondHandle = subagents[1].jsonObject
    assertEquals("child-done", secondHandle.getValue("agentId").jsonPrimitive.content)
    assertEquals("completed", secondHandle.getValue("state").jsonPrimitive.content)
    assertEquals("SUCCESS", secondHandle.getValue("childExecutionStatus").jsonPrimitive.content)
  }

  private fun runtime(
    workspaceRoot: java.nio.file.Path,
    readRoots: Set<java.nio.file.Path> = setOf(workspaceRoot),
    gateway: LiteLlmGateway,
    eventSink: OpenCrayAgentRuntimeEventSink = NoOpOpenCrayAgentRuntimeEventSink,
    sessionContext: AgentRuntimeSessionContext = AgentRuntimeSessionContext(),
    promptResumeState: OpenCrayPromptResumeState? = null,
    approvedSubAgentResume: SubAgentApprovalResume? = null,
    rejectedSubAgentResume: SubAgentApprovalResume? = null,
    seededSubAgentHandles: List<SubAgentHandleState> = emptyList(),
    subAgentExecutionCoordinator: com.opencray.runtime.subagent.SubAgentExecutionCoordinator =
      InMemorySubAgentExecutionCoordinator(),
  ): OpenCrayAgentRuntime = OpenCrayAgentRuntime(
    gateway = gateway,
    toolDispatcher = OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(workspaceRoot),
        readRoots = readRoots,
      ),
    ),
    config = OpenCrayAgentRuntimeConfig(
      maxTurns = 6,
      maxToolCalls = 0,
      sessionContext = sessionContext,
      promptResumeState = promptResumeState,
      approvedSubAgentResume = approvedSubAgentResume,
      rejectedSubAgentResume = rejectedSubAgentResume,
      seededSubAgentHandles = seededSubAgentHandles,
      subAgentExecutionCoordinator = subAgentExecutionCoordinator,
      json = TEST_JSON,
    ),
    eventSink = eventSink,
    clock = IncrementingClock(start = 10_000L)::next,
  )

  private fun promptTask(
    input: String,
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "prompt-task",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_ALL",
    ),
    createdAtEpochMs = 1_000L,
    metadata = metadata,
  )

  private fun directToolCallTask(
    input: String,
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "tool-task",
    type = AgentTaskType.TOOL_CALL,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_ALL",
    ),
    createdAtEpochMs = 1_000L,
    metadata = metadata,
  )

  private fun runtimeHooks(
    isCancellationRequested: () -> Boolean = { false },
  ): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = isCancellationRequested,
    requestRetry = { _: RetryRequest -> error("Retry not expected in subagent runtime test.") },
  )

  private fun sessionContextWithSkillCatalog(
    allowedToolPatterns: List<String> = listOf("read"),
  ): AgentRuntimeSessionContext = AgentRuntimeSessionContext(
    skillCatalog = SkillCatalog(
      skillsByName = mapOf(
        "focused-read" to SkillCatalogEntry(
          name = "focused-read",
          description = "Keep the child focused on direct file reads only.",
          relativePath = ".codex/skills/focused-read/SKILL.md",
          invocationControl = SkillInvocationControl.EXPLICIT_ONLY,
          userInvocable = true,
          executionContext = SkillExecutionContext.INLINE,
          markdownBody = "# focused-read\n\nFollow the focused-read workflow.",
          toolPermissions = allowedToolPatterns.map { pattern ->
            SkillPermissionRule(
              pattern = pattern,
              decision = SkillPermissionDecision.ALLOW,
            )
          },
        ),
      ),
    ),
  )

  private fun activeSkillPromptResumeState(): OpenCrayPromptResumeState = OpenCrayPromptResumeState(
    turnIndex = 0,
    toolCallCount = 0,
    activeSkillName = "focused-read",
    activeSkillActivationSource = "skill_read",
  )

  private fun requestHasTool(
    request: LiteLlmGatewayRequest,
    toolName: String,
  ): Boolean = request.tools.any { definition ->
    definition.name == toolName
  }

  private fun jsonEscape(value: String): String = value.replace("\\", "\\\\")

  private companion object {
    val TEST_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
  }

  private class IncrementingClock(
    start: Long,
  ) {
    private var current: Long = start

    fun next(): Long = current++
  }

  private class RecordingGateway(
    outputs: List<String>,
  ) : LiteLlmGateway {
    private val lock = Any()
    private val queuedOutputs = ArrayDeque(outputs)
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    private var now = 20_000L

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      val (output, startedAt, finishedAt) = synchronized(lock) {
        requests += request
        val nextOutput = queuedOutputs.removeFirstOrNull()
          ?: error("No fake LLM output left for request ${request.requestId}.")
        Triple(nextOutput, now++, now++)
      }
      val selection = LiteLlmRouteSelectionMetadata(
        profileId = "test-profile",
        routeId = "test-route",
        providerId = "fake",
        model = "fake-model",
        attemptIndex = 0,
      )
      return LiteLlmGatewayResult(
        requestId = request.requestId,
        status = LiteLlmGatewayStatus.SUCCESS,
        completionMode = LiteLlmCompletionMode.PRIMARY,
        outputText = output,
        selectedRoute = selection,
        attempts = listOf(
          LiteLlmAttemptRecord(
            route = selection,
            outcome = LiteLlmAttemptOutcome.SUCCESS,
            outputChars = output.length,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
          ),
        ),
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
      )
    }
  }

  private class ScriptedGateway(
    private val handler: (LiteLlmGatewayRequest) -> String,
  ) : LiteLlmGateway {
    private val lock = Any()
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    private var now = 20_000L

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      synchronized(lock) {
        requests += request
      }
      val output = handler(request)
      val selection = LiteLlmRouteSelectionMetadata(
        profileId = "test-profile",
        routeId = "test-route",
        providerId = "fake",
        model = "fake-model",
        attemptIndex = 0,
      )
      val (startedAt, finishedAt) = synchronized(lock) {
        Pair(now++, now++)
      }
      return LiteLlmGatewayResult(
        requestId = request.requestId,
        status = LiteLlmGatewayStatus.SUCCESS,
        completionMode = LiteLlmCompletionMode.PRIMARY,
        outputText = output,
        selectedRoute = selection,
        attempts = listOf(
          LiteLlmAttemptRecord(
            route = selection,
            outcome = LiteLlmAttemptOutcome.SUCCESS,
            outputChars = output.length,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
          ),
        ),
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
      )
    }
  }

  private class RecordingEventSink : OpenCrayAgentRuntimeEventSink {
    val events = mutableListOf<OpenCrayAgentRunEvent>()

    override fun onRunEvent(task: AgentTask, event: OpenCrayAgentRunEvent) {
      events += event
    }
  }
}
