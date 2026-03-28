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
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.subagent.SubAgentApprovalResume
import com.opencray.runtime.subagent.SubAgentApprovalResumeMetadata
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionState
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
  fun spawnAgentQueuesHandleAndWaitAgentRunsChild() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-spawn-wait").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}""",
        """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-1"}}""",
        """{"type":"final","answer":"README says hello."}""",
        """{"type":"final","answer":"Delegated wait completed."}""",
      ),
    )
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
    assertTrue(gateway.requests[2].prompt.contains("Read README.md and summarize it."))
    val spawnResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .first { event -> event.call.toolName == "spawn_agent" }
      .result
      .metadata
    assertEquals("child-1", spawnResultMetadata["agentId"])
    assertEquals("background_queued", spawnResultMetadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
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
  fun queuedWaitAgentKeepsInheritedActiveSkillRestrictions() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-active-skill-spawn-wait").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}""",
        """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-1"}}""",
        """{"type":"final","answer":"README says hello."}""",
        """{"type":"final","answer":"Queued child finished under the inherited skill."}""",
      ),
    )
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
    val childPrompt = gateway.requests[2].prompt
    assertTrue(childPrompt.contains("A skill is now active for this run."))
    assertTrue(childPrompt.contains("name=focused-read"))
    val childToolNames = gateway.requests[2].tools.map { definition -> definition.name }
    assertTrue(childToolNames.contains("Read"))
    assertFalse(childToolNames.contains("LS"))
    assertFalse(childToolNames.contains("Grep"))
    assertFalse(childToolNames.contains("Glob"))
    assertFalse(childToolNames.contains("spawn_agent"))
    assertFalse(childToolNames.contains("wait_agent"))
  }

  @Test
  fun sendInputAppendsSupplementalParentInstructionBeforeWait() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-send-input").toPath()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"inspect docs","prompt":"Inspect README.md only.","subagent_type":"researcher"}}""",
        """{"type":"tool_call","tool_name":"send_input","arguments":{"agent_id":"child-1","message":"Also inspect docs/notes.md and mention it."}}""",
        """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-1"}}""",
        """{"type":"final","answer":"Inspected README.md and docs/notes.md."}""",
        """{"type":"final","answer":"Supplemental wait completed."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
    )

    val result = runtime.execute(
      task = promptTask("Queue, supplement, and then wait for a child."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Supplemental wait completed.", result.stdout)
    assertEquals(5, gateway.requests.size)
    assertTrue(gateway.requests[3].prompt.contains("Inspect README.md only."))
    assertTrue(gateway.requests[3].prompt.contains("[Additional parent input 1]"))
    assertTrue(gateway.requests[3].prompt.contains("Also inspect docs/notes.md and mention it."))
    val sendInputResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .first { event -> event.call.toolName == "send_input" }
      .result
      .metadata
    assertEquals("1", sendInputResultMetadata["supplementalInputCount"])
  }

  @Test
  fun closeAgentCancelsQueuedHandleWithoutRunningChild() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-close").toPath()
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}""",
        """{"type":"tool_call","tool_name":"close_agent","arguments":{"agent_id":"child-1"}}""",
        """{"type":"final","answer":"Queued child closed."}""",
      ),
    )
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
    assertEquals(3, gateway.requests.size)
    val closeResultMetadata = eventSink.events
      .filterIsInstance<OpenCrayToolResultEvent>()
      .first { event -> event.call.toolName == "close_agent" }
      .result
      .metadata
    assertEquals("true", closeResultMetadata["closed"])
    assertEquals(
      listOf(OpenCraySubAgentPhase.STARTED, OpenCraySubAgentPhase.CANCELLED),
      eventSink.events
        .filterIsInstance<OpenCraySubAgentEvent>()
        .map(OpenCraySubAgentEvent::phase),
    )
  }

  @Test
  fun waitAgentCapturesApprovalContinuationAndApprovedResumeContinuesQueuedChild() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-wait-approval").toPath()
    val notesFile = workspaceRoot.resolve("notes.txt")
    Files.write(
      notesFile,
      "draft\nTODO\n".toByteArray(StandardCharsets.UTF_8),
    )
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"edit notes","prompt":"Replace TODO with DONE in notes.txt.","subagent_type":"worker"}}""",
        """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-1"}}""",
        """{"type":"tool_call","tool_name":"Edit","arguments":{"file_path":"notes.txt","old_string":"TODO","new_string":"DONE"}}""",
      ),
    )
    val initialRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = initialGateway,
    )

    val initialResult = initialRuntime.execute(
      task = promptTask(
        "Queue a worker child and wait for it.",
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
        """{"type":"final","answer":"Approved wait completed."}""",
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
        "Queue a worker child and wait for it.",
        metadata = mapOf(
          SafetySettingsMetadataKeys.EXECUTION_MODE to "safe",
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Approved wait completed.", resumedResult.stdout)
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
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-1","description":"edit notes","prompt":"Replace TODO with DONE in notes.txt.","subagent_type":"worker"}}""",
        """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-1"}}""",
        """{"type":"tool_call","tool_name":"Edit","arguments":{"file_path":"notes.txt","old_string":"TODO","new_string":"DONE"}}""",
      ),
    )
    val initialRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = initialGateway,
    )

    val initialResult = initialRuntime.execute(
      task = promptTask(
        "Queue a worker child and wait for it.",
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
        "Queue a worker child and wait for it.",
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

  private fun runtime(
    workspaceRoot: java.nio.file.Path,
    readRoots: Set<java.nio.file.Path> = setOf(workspaceRoot),
    gateway: LiteLlmGateway,
    eventSink: OpenCrayAgentRuntimeEventSink = NoOpOpenCrayAgentRuntimeEventSink,
    sessionContext: AgentRuntimeSessionContext = AgentRuntimeSessionContext(),
    promptResumeState: OpenCrayPromptResumeState? = null,
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
      promptResumeState = promptResumeState,
      approvedSubAgentResume = approvedSubAgentResume,
      rejectedSubAgentResume = rejectedSubAgentResume,
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
