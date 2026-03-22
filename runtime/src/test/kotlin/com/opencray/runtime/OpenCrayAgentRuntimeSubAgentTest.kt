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
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.policy.SafetySettingsMetadataKeys
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentMetadataKeys
import com.opencray.runtime.subagent.SubAgentResultMetadataKeys
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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
    assertTrue(gateway.requests[1].prompt.contains("tool_name\":\"Read"))
    assertFalse(gateway.requests[1].prompt.contains("tool_name\":\"Task"))
    assertFalse(gateway.requests[1].prompt.contains("tool_name\":\"Write"))
    assertFalse(gateway.requests[1].prompt.contains("tool_name\":\"Bash"))
    assertFalse(gateway.requests[1].prompt.contains("tool_name\":\"python_exec"))
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
  fun taskToolAllowsExplicitMirroredContextOverride() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-mirrored-override").toPath()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Task","arguments":{"description":"reuse prior context","prompt":"Continue from the preserved conversation context.","subagent_type":"general-purpose","context_mode":"mirrored"}}""",
        """{"type":"final","answer":"Mirrored child answer."}""",
        """{"type":"final","answer":"Parent received mirrored answer."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
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
      task = promptTask("Delegate follow-up work and keep the prior context."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Parent received mirrored answer.", result.stdout)
    val taskResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .single()
      .result
      .metadata
    assertEquals("mirrored", taskResultMetadata["delegationContextMode"])
    assertTrue(gateway.requests[1].prompt.contains("Delegate follow-up work and keep the prior context."))
    assertTrue(gateway.requests[1].prompt.contains("Prior parent context that mirrored child should receive."))
    assertFalse(gateway.requests[1].prompt.contains("Delegated parent context for this child run."))
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
    assertEquals(4, gateway.requests.size)
    assertTrue(gateway.requests[2].prompt.contains("recent_observations:"))
    assertTrue(gateway.requests[2].prompt.contains("Read file_path=README.md"))
    assertFalse(gateway.requests[2].prompt.contains("tool_result Read"))
    assertFalse(gateway.requests[2].prompt.contains("hello"))
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
      "Unknown Task context_mode 'full'. Expected one of: minimal, delegated, mirrored.",
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
  fun taskToolCancellationPreservesSubagentMetadataOnParentResult() {
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
    assertEquals("SUBAGENT_CANCELLED", result.errorCode)
    assertEquals("Task", result.metadata["cancelledToolName"])
    assertEquals("Task", result.metadata["toolName"])
    assertEquals("minimal", result.metadata["subagentContextMode"])
    assertEquals("1", result.metadata["toolCallCount"])
    assertEquals("none", result.metadata[SubAgentResultMetadataKeys.CONTINUATION_KIND])
    assertEquals("false", result.metadata[SubAgentResultMetadataKeys.CONTINUATION_RESUMABLE])
    assertEquals(1, gateway.requests.size)
    val subAgentEvents = eventSink.events.filterIsInstance<OpenCraySubAgentEvent>()
    assertEquals(
      listOf(OpenCraySubAgentPhase.STARTED, OpenCraySubAgentPhase.CANCELLED),
      subAgentEvents.map(OpenCraySubAgentEvent::phase),
    )
    assertEquals(
      listOf(SubAgentExecutionState.RUNNING, SubAgentExecutionState.CANCELLED),
      subAgentEvents.map(OpenCraySubAgentEvent::executionState),
    )
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

  private fun runtime(
    workspaceRoot: java.nio.file.Path,
    readRoots: Set<java.nio.file.Path> = setOf(workspaceRoot),
    gateway: LiteLlmGateway,
    eventSink: OpenCrayAgentRuntimeEventSink = NoOpOpenCrayAgentRuntimeEventSink,
    sessionContext: AgentRuntimeSessionContext = AgentRuntimeSessionContext(),
    approvedSubAgentResume: SubAgentApprovalResume? = null,
    rejectedSubAgentResume: SubAgentApprovalResume? = null,
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
      approvedSubAgentResume = approvedSubAgentResume,
      rejectedSubAgentResume = rejectedSubAgentResume,
      json = TEST_JSON,
    ),
    eventSink = eventSink,
    clock = IncrementingClock(start = 10_000L)::next,
  )

  private fun promptTask(input: String): AgentTask = AgentTask(
    id = "prompt-task",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_ALL",
    ),
    createdAtEpochMs = 1_000L,
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
    private val queuedOutputs = ArrayDeque(outputs)
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    private var now = 20_000L

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      requests += request
      val output = queuedOutputs.removeFirstOrNull()
        ?: error("No fake LLM output left for request ${request.requestId}.")
      val selection = LiteLlmRouteSelectionMetadata(
        profileId = "test-profile",
        routeId = "test-route",
        providerId = "fake",
        model = "fake-model",
        attemptIndex = 0,
      )
      val startedAt = now++
      val finishedAt = now++
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
