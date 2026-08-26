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
import com.opencray.runtime.subagent.MAX_RETAINED_CLOSED_SUB_AGENT_HANDLES
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
import com.opencray.runtime.subagent.SubAgentContextMode
import com.opencray.runtime.subagent.SubAgentContextModeResolutionSource
import com.opencray.runtime.subagent.SubAgentContextPolicy
import com.opencray.runtime.subagent.SubAgentMetadataKeys
import com.opencray.runtime.subagent.SubAgentResultMetadataKeys
import com.opencray.runtime.subagent.synchronizedSubAgentHandles
import com.opencray.runtime.skills.SkillCatalog
import com.opencray.runtime.skills.SkillCatalogEntry
import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl
import com.opencray.skills.SkillPermissionDecision
import com.opencray.skills.SkillPermissionRule
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    assertEquals(
      SubAgentContextModeResolutionSource.PROFILE_DEFAULT.wireValue,
      taskResultMetadata["delegationContextModeSource"],
    )
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
    assertEquals(
      SubAgentContextModeResolutionSource.PROFILE_DEFAULT.wireValue,
      taskResultMetadata["subagentContextModeSource"],
    )
    assertFalse(gateway.requests[1].prompt.contains("Please delegate README inspection and then answer."))
    assertTrue(gateway.requests[1].prompt.contains("Read README.md and summarize it."))
    val childToolNames = gateway.requests[1].tools.map { definition -> definition.name }
    assertTrue(childToolNames.contains("Read"))
    assertFalse(childToolNames.contains("Task"))
    assertFalse(childToolNames.contains("Write"))
    assertFalse(childToolNames.contains("Bash"))
    assertFalse(childToolNames.contains("python_exec"))
    assertTrue(gateway.requests[2].prompt.contains("README says hello."))
    assertFalse(gateway.requests[2].prompt.contains("[Recent Working Observations]"))
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
    assertEquals(
      SubAgentContextModeResolutionSource.PROFILE_DEFAULT.wireValue,
      taskResultMetadata["delegationContextModeSource"],
    )
    assertEquals(
      SubAgentContextModeResolutionSource.PROFILE_DEFAULT.wireValue,
      taskResultMetadata["subagentContextModeSource"],
    )
    assertFalse(gateway.requests[1].prompt.contains("Please delegate README inspection through the explorer alias."))
    val childToolNames = gateway.requests[1].tools.map { definition -> definition.name }
    assertTrue(childToolNames.contains("Read"))
    assertFalse(childToolNames.contains("Task"))
    assertFalse(childToolNames.contains("Write"))
    assertFalse(gateway.requests[2].prompt.contains("[Recent Working Observations]"))
    assertTrue(gateway.requests[2].prompt.contains("README says hello."))
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
    fun taskToolHonorsExplicitContextModeWithEmptyPolicy() {
      val workspaceRoot = temporaryFolder.newFolder("subagent-policy-explicit-empty").toPath()
      Files.write(
        workspaceRoot.resolve("README.md"),
        "hello".toByteArray(StandardCharsets.UTF_8),
      )
      val gateway = RecordingGateway(
        outputs = listOf(
          """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"general-purpose","context_mode":"minimal"}}""",
          """{"type":"final","answer":"README says hello."}""",
          """{"type":"final","answer":"Parent accepted the minimal child result."}""",
        ),
      )
      val eventSink = RecordingEventSink()
      val runtime = runtime(
        workspaceRoot = workspaceRoot,
        gateway = gateway,
        eventSink = eventSink,
      )

      val result = runtime.execute(
        task = promptTask("Please delegate README inspection with a minimal child."),
        hooks = runtimeHooks(),
      )

      assertEquals(ExecutionStatus.SUCCESS, result.status)
      assertEquals("Parent accepted the minimal child result.", result.stdout)
      val taskResultMetadata = eventSink.events
        .filterIsInstance<OpenCrayToolResultEvent>()
        .single()
        .result
        .metadata
      assertEquals("general-purpose", taskResultMetadata["delegationSubagentType"])
      assertEquals("minimal", taskResultMetadata["delegationContextMode"])
      assertFalse(gateway.requests[1].prompt.contains("Delegated parent context for this child run."))
      assertTrue(gateway.requests[1].prompt.contains("Read README.md and summarize it."))
    }

    @Test
    fun taskToolAppliesRuntimeContextPolicyDefaultWhenNoExplicitContextMode() {
      val workspaceRoot =
        temporaryFolder.newFolder("subagent-policy-default").toPath()
      val gateway = RecordingGateway(
        outputs = listOf(
          """{"type":"tool_call","tool_name":"Task","arguments":{"description":"continue investigation","prompt":"Check the repo layout and report back.","subagent_type":"general-purpose"}}""",
          """{"type":"final","answer":"Layout inspected under policy default."}""",
          """{"type":"final","answer":"Parent accepted the policy-default child result."}""",
        ),
      )
      val eventSink = RecordingEventSink()
      val runtime = runtime(
        workspaceRoot = workspaceRoot,
        gateway = gateway,
        eventSink = eventSink,
        subAgentContextPolicy = SubAgentContextPolicy(
          defaultContextMode = SubAgentContextMode.MINIMAL,
        ),
      )

      val result = runtime.execute(
        task = promptTask("Investigate the codebase and continue carefully."),
        hooks = runtimeHooks(),
      )

      assertEquals(ExecutionStatus.SUCCESS, result.status)
      assertEquals("Parent accepted the policy-default child result.", result.stdout)
      val taskResultMetadata = eventSink.events
        .filterIsInstance<OpenCrayToolResultEvent>()
        .single()
        .result
        .metadata
      assertEquals("general-purpose", taskResultMetadata["delegationSubagentType"])
      assertEquals("minimal", taskResultMetadata["delegationContextMode"])
      assertEquals(
        SubAgentContextModeResolutionSource.POLICY_DEFAULT.wireValue,
        taskResultMetadata["delegationContextModeSource"],
      )
      assertEquals(
        SubAgentContextModeResolutionSource.POLICY_DEFAULT.wireValue,
        taskResultMetadata["subagentContextModeSource"],
      )
      assertFalse(gateway.requests[1].prompt.contains("Delegated parent context for this child run."))
      assertTrue(gateway.requests[1].prompt.contains("Check the repo layout and report back."))
    }

    @Test
    fun taskToolAppliesRuntimeContextPolicyProfileOverrideWhenNoExplicitContextMode() {
      val workspaceRoot =
        temporaryFolder.newFolder("subagent-policy-profile-override").toPath()
      val gateway = RecordingGateway(
        outputs = listOf(
          """{"type":"tool_call","tool_name":"Task","arguments":{"description":"inspect repo","prompt":"Inspect the repo layout and summarize it.","subagent_type":"researcher"}}""",
          """{"type":"final","answer":"Delegated researcher inspected the repo."}""",
          """{"type":"final","answer":"Parent accepted the delegated researcher result."}""",
        ),
      )
      val eventSink = RecordingEventSink()
      val runtime = runtime(
        workspaceRoot = workspaceRoot,
        gateway = gateway,
        eventSink = eventSink,
        subAgentContextPolicy = SubAgentContextPolicy(
          profileOverrides = mapOf("researcher" to SubAgentContextMode.DELEGATED),
        ),
      )

      val result = runtime.execute(
        task = promptTask("Ask the researcher to inspect the repo and continue."),
        hooks = runtimeHooks(),
      )

      assertEquals(ExecutionStatus.SUCCESS, result.status)
      assertEquals("Parent accepted the delegated researcher result.", result.stdout)
      val taskResultMetadata = eventSink.events
        .filterIsInstance<OpenCrayToolResultEvent>()
        .single()
        .result
        .metadata
      assertEquals("researcher", taskResultMetadata["delegationSubagentType"])
      assertEquals("delegated", taskResultMetadata["delegationContextMode"])
      assertEquals(
        SubAgentContextModeResolutionSource.POLICY_PROFILE_OVERRIDE.wireValue,
        taskResultMetadata["delegationContextModeSource"],
      )
      assertEquals(
        SubAgentContextModeResolutionSource.POLICY_PROFILE_OVERRIDE.wireValue,
        taskResultMetadata["subagentContextModeSource"],
      )
      assertTrue(gateway.requests[1].prompt.contains("Delegated parent context for this child run."))
      assertTrue(gateway.requests[1].prompt.contains("user_goal=Ask the researcher to inspect the repo and continue."))
      assertTrue(gateway.requests[1].prompt.contains("Inspect the repo layout and summarize it."))
    }

    @Test
    fun taskToolExplicitContextModeWinsOverRuntimeSubAgentContextPolicy() {
      val workspaceRoot =
        temporaryFolder.newFolder("subagent-policy-explicit-wins").toPath()
      val gateway = RecordingGateway(
        outputs = listOf(
          """{"type":"tool_call","tool_name":"Task","arguments":{"description":"keep parent summary","prompt":"Continue the repo investigation with inherited findings.","subagent_type":"general-purpose","context_mode":"delegated"}}""",
          """{"type":"final","answer":"Explicit delegated child finished."}""",
          """{"type":"final","answer":"Parent kept the explicit delegated result."}""",
        ),
      )
      val eventSink = RecordingEventSink()
      val runtime = runtime(
        workspaceRoot = workspaceRoot,
        gateway = gateway,
        eventSink = eventSink,
        subAgentContextPolicy = SubAgentContextPolicy(
          defaultContextMode = SubAgentContextMode.MINIMAL,
          profileOverrides = mapOf("general-purpose" to SubAgentContextMode.MINIMAL),
        ),
      )

      val result = runtime.execute(
        task = promptTask("Delegate the repo investigation and preserve useful context."),
        hooks = runtimeHooks(),
      )

      assertEquals(ExecutionStatus.SUCCESS, result.status)
      assertEquals("Parent kept the explicit delegated result.", result.stdout)
      val taskResultMetadata = eventSink.events
        .filterIsInstance<OpenCrayToolResultEvent>()
        .single()
        .result
        .metadata
      assertEquals("general-purpose", taskResultMetadata["delegationSubagentType"])
      assertEquals("delegated", taskResultMetadata["delegationContextMode"])
      assertEquals(
        SubAgentContextModeResolutionSource.EXPLICIT_REQUEST.wireValue,
        taskResultMetadata["delegationContextModeSource"],
      )
      assertEquals(
        SubAgentContextModeResolutionSource.EXPLICIT_REQUEST.wireValue,
        taskResultMetadata["subagentContextModeSource"],
      )
      assertTrue(gateway.requests[1].prompt.contains("Delegated parent context for this child run."))
      assertTrue(gateway.requests[1].prompt.contains("user_goal=Delegate the repo investigation and preserve useful context."))
    }

  @Test
  fun delegatedChildKeepsWorkspaceDiscoveryOutOfRecentObservationSummaries() {
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
    assertFalse(gateway.requests[2].prompt.contains("recent_observations:"))
    assertFalse(gateway.requests[2].prompt.contains("Read file_path=README.md"))
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
      listOf(
        OpenCraySubAgentPhase.STARTED,
        OpenCraySubAgentPhase.RESUMED,
        OpenCraySubAgentPhase.FAILED,
      ),
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
      listOf(
        OpenCraySubAgentPhase.STARTED,
        OpenCraySubAgentPhase.RESUMED,
        OpenCraySubAgentPhase.FAILED,
      ),
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
      listOf(
        OpenCraySubAgentPhase.STARTED,
        OpenCraySubAgentPhase.RESUMED,
        OpenCraySubAgentPhase.FAILED,
      ),
      subAgentEvents.map(OpenCraySubAgentEvent::phase),
    )
    assertEquals(
      listOf(
        SubAgentExecutionState.BACKGROUND_QUEUED,
        SubAgentExecutionState.BACKGROUND_RUNNING,
        SubAgentExecutionState.WAITING_APPROVAL,
      ),
      subAgentEvents.map(OpenCraySubAgentEvent::executionState),
    )
    assertEquals(
      listOf(
        SubAgentContinuationKind.BACKGROUND_RESUME,
        SubAgentContinuationKind.BACKGROUND_RESUME,
        SubAgentContinuationKind.PROMPT_RESUME,
      ),
      subAgentEvents.map(OpenCraySubAgentEvent::continuationKind),
    )
    assertEquals(listOf(true, true, true), subAgentEvents.map(OpenCraySubAgentEvent::resumable))
    assertEquals(listOf(false, false, true), subAgentEvents.map(OpenCraySubAgentEvent::requiresUserAction))
    assertEquals(listOf(false, false, false), subAgentEvents.map(OpenCraySubAgentEvent::isHighRisk))
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
      listOf(
        OpenCraySubAgentPhase.STARTED,
        OpenCraySubAgentPhase.RESUMED,
        OpenCraySubAgentPhase.FAILED,
      ),
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
      listOf(
        OpenCraySubAgentPhase.STARTED,
        OpenCraySubAgentPhase.RESUMED,
        OpenCraySubAgentPhase.FAILED,
      ),
      subAgentEvents.map(OpenCraySubAgentEvent::phase),
    )
    assertEquals(
      listOf(
        SubAgentExecutionState.BACKGROUND_QUEUED,
        SubAgentExecutionState.BACKGROUND_RUNNING,
        SubAgentExecutionState.WAITING_APPROVAL,
      ),
      subAgentEvents.map(OpenCraySubAgentEvent::executionState),
    )
    assertEquals(
      listOf(
        SubAgentContinuationKind.BACKGROUND_RESUME,
        SubAgentContinuationKind.BACKGROUND_RESUME,
        SubAgentContinuationKind.PROMPT_RESUME,
      ),
      subAgentEvents.map(OpenCraySubAgentEvent::continuationKind),
    )
    assertEquals(listOf(true, true, true), subAgentEvents.map(OpenCraySubAgentEvent::resumable))
    assertEquals(listOf(false, false, true), subAgentEvents.map(OpenCraySubAgentEvent::requiresUserAction))
    assertEquals(listOf(false, false, false), subAgentEvents.map(OpenCraySubAgentEvent::isHighRisk))
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
    assertEquals(
      listOf(
        SubAgentContinuationKind.BACKGROUND_RESUME,
        SubAgentContinuationKind.BACKGROUND_RESUME,
        SubAgentContinuationKind.NONE,
      ),
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
            """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-1"}}"""
          }

          2 -> {
            assertTrue(childFinished.await(5, TimeUnit.SECONDS))
            """{"type":"tool_call","tool_name":"send_input","arguments":{"agent_id":"child-1","message":"Also inspect docs/notes.md and mention it."}}"""
          }

          3 -> """{"type":"final","answer":"Completed child rejected more input."}"""
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
    assertEquals(5, gateway.requests.size)
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
  fun sendInputAutoResumesQueuedDetachedChildAndWaitAgentHarvestsIt() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-send-input-queued-auto-resume").toPath()
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val queuedHandle = SubAgentHandleState.queued(
      agentId = "child-queued",
      childRunId = "child-run-queued",
      childTaskId = "child-task-queued",
      description = "inspect readme",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "parent-run-queued",
      parentTaskId = "parent-task-queued",
      parentTurn = 0,
      depth = 1,
      activeSkillName = null,
      activeSkillActivationSource = null,
      createdAtEpochMs = 1_000L,
    )
    val childStarted = CountDownLatch(1)
    val childMayFinish = CountDownLatch(1)
    val gateway = ScriptedGateway { request ->
      childStarted.countDown()
      assertTrue(
        request.prompt.contains("After reading README.md, mention that the follow-up was received."),
      )
      assertTrue(childMayFinish.await(5, TimeUnit.SECONDS))
      """{"type":"final","answer":"README inspected with queued follow-up."}"""
    }
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      seededSubAgentHandles = listOf(queuedHandle),
      subAgentExecutionCoordinator = coordinator,
    )

    val sendInputResult = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"send_input","arguments":{"agent_id":"child-queued","message":"After reading README.md, mention that the follow-up was received."}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, sendInputResult.status)
    assertEquals("true", sendInputResult.metadata["autoResumed"])
    assertEquals("background_running", sendInputResult.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals("0", sendInputResult.metadata["mailboxPendingInputCount"])
    assertTrue(childStarted.await(5, TimeUnit.SECONDS))
    assertTrue(
      coordinator.activeExecution(
        SubAgentExecutionKey(
          parentRunId = "parent-run-queued",
          agentId = "child-queued",
        ),
      ) != null,
    )

    childMayFinish.countDown()
    Thread.sleep(100L)

    val waitResult = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-queued"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, waitResult.status)
    assertTrue(waitResult.stdout.contains("README inspected with queued follow-up."))
    assertEquals("completed", waitResult.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals(null, coordinator.activeExecution(SubAgentExecutionKey.from(queuedHandle)))
  }

  @Test
  fun sendInputQueuesMailboxForRunningDetachedChildAndAutoContinuesNextTurn() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-send-input-running-mailbox").toPath()
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val queuedHandle = SubAgentHandleState.queued(
      agentId = "child-running",
      childRunId = "child-run-running",
      childTaskId = "child-task-running",
      description = "inspect docs",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "parent-run-running",
      parentTaskId = "parent-task-running",
      parentTurn = 0,
      depth = 1,
      activeSkillName = null,
      activeSkillActivationSource = null,
      createdAtEpochMs = 1_000L,
    )
    val firstStarted = CountDownLatch(1)
    val firstMayFinish = CountDownLatch(1)
    val secondStarted = CountDownLatch(1)
    val secondMayFinish = CountDownLatch(1)
    var childTurn = 0
    val gateway = ScriptedGateway { request ->
      when (childTurn++) {
        0 -> {
          firstStarted.countDown()
          assertTrue(request.prompt.contains("Mention the first follow-up."))
          assertFalse(request.prompt.contains("Also inspect docs/notes.md and mention it."))
          assertTrue(firstMayFinish.await(5, TimeUnit.SECONDS))
          """{"type":"final","answer":"README inspected with first follow-up."}"""
        }

        1 -> {
          secondStarted.countDown()
          assertTrue(request.prompt.contains("Also inspect docs/notes.md and mention it."))
          assertTrue(secondMayFinish.await(5, TimeUnit.SECONDS))
          """{"type":"final","answer":"README and docs/notes.md inspected."}"""
        }

        else -> error("Unexpected child turn ${childTurn - 1}.")
      }
    }
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      seededSubAgentHandles = listOf(queuedHandle),
      subAgentExecutionCoordinator = coordinator,
    )

    val firstSendInputResult = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"send_input","arguments":{"agent_id":"child-running","message":"Mention the first follow-up."}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, firstSendInputResult.status)
    assertEquals("true", firstSendInputResult.metadata["autoResumed"])
    assertEquals("background_running", firstSendInputResult.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
    val executionKey = SubAgentExecutionKey.from(queuedHandle)
    val activeExecution = requireNotNull(
      coordinator.activeExecution(executionKey),
    )

    val secondSendInputResult = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"send_input","arguments":{"agent_id":"child-running","message":"Also inspect docs/notes.md and mention it."}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, secondSendInputResult.status)
    assertEquals("false", secondSendInputResult.metadata["autoResumed"])
    assertEquals("background_running", secondSendInputResult.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals("1", secondSendInputResult.metadata["mailboxPendingInputCount"])
    assertEquals("2", secondSendInputResult.metadata["supplementalInputCount"])

    val waitExecutor = Executors.newSingleThreadExecutor()
    try {
      val waitTask = FutureTask {
        runtime.execute(
          task = directToolCallTask(
            """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-running"}}""",
          ),
          hooks = runtimeHooks(),
        )
      }
      waitExecutor.execute(waitTask)
      firstMayFinish.countDown()
      assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
      assertSame(activeExecution, coordinator.activeExecution(executionKey))
      secondMayFinish.countDown()

      val waitResult = waitTask.get(5, TimeUnit.SECONDS)
      assertEquals(ExecutionStatus.SUCCESS, waitResult.status)
      assertTrue(waitResult.stdout.contains("README and docs/notes.md inspected."))
      assertEquals("completed", waitResult.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    } finally {
      waitExecutor.shutdownNow()
    }

    assertEquals(2, gateway.requests.size)
    assertTrue(
      gateway.requests[1].prompt.contains("Also inspect docs/notes.md and mention it."),
    )
    assertEquals(null, coordinator.activeExecution(executionKey))
  }

  @Test
  fun sendInputInterruptRestartsRunningDetachedChildAndReplaysInFlightMailbox() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-send-input-running-interrupt").toPath()
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val queuedHandle = SubAgentHandleState.queued(
      agentId = "child-running-interrupt",
      childRunId = "child-run-running-interrupt",
      childTaskId = "child-task-running-interrupt",
      description = "inspect docs",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "parent-run-running-interrupt",
      parentTaskId = "parent-task-running-interrupt",
      parentTurn = 0,
      depth = 1,
      activeSkillName = null,
      activeSkillActivationSource = null,
      createdAtEpochMs = 1_000L,
    )
    val firstStarted = CountDownLatch(1)
    val secondStarted = CountDownLatch(1)
    val secondMayFinish = CountDownLatch(1)
    var childTurn = 0
    val gateway = ScriptedGateway { request ->
      when (childTurn++) {
        0 -> {
          firstStarted.countDown()
          assertTrue(request.prompt.contains("Mention the first follow-up."))
          assertFalse(request.prompt.contains("Also inspect docs/notes.md and mention it."))
          try {
            Thread.sleep(30_000L)
            error("Interrupted child turn should not complete normally.")
          } catch (_: InterruptedException) {
            throw InterruptedException()
          }
        }

        1 -> {
          secondStarted.countDown()
          assertTrue(request.prompt.contains("Mention the first follow-up."))
          assertTrue(request.prompt.contains("Also inspect docs/notes.md and mention it."))
          assertTrue(secondMayFinish.await(5, TimeUnit.SECONDS))
          """{"type":"final","answer":"README and docs/notes.md inspected after redirect."}"""
        }

        else -> error("Unexpected child turn ${childTurn - 1}.")
      }
    }
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      seededSubAgentHandles = listOf(queuedHandle),
      subAgentExecutionCoordinator = coordinator,
    )

    val firstSendInputResult = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"send_input","arguments":{"agent_id":"child-running-interrupt","message":"Mention the first follow-up."}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, firstSendInputResult.status)
    assertEquals("true", firstSendInputResult.metadata["autoResumed"])
    assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

    val executionKey = SubAgentExecutionKey.from(queuedHandle)
    val firstExecution = requireNotNull(coordinator.activeExecution(executionKey))

    val secondSendInputResult = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"send_input","arguments":{"agent_id":"child-running-interrupt","message":"Also inspect docs/notes.md and mention it.","interrupt":true}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, secondSendInputResult.status)
    assertEquals("true", secondSendInputResult.metadata["interruptRequested"])
    assertEquals("true", secondSendInputResult.metadata["interruptedExistingExecution"])
    assertEquals("true", secondSendInputResult.metadata["autoResumed"])
    assertEquals("background_running", secondSendInputResult.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertTrue(secondStarted.await(5, TimeUnit.SECONDS))

    val restartedExecution = requireNotNull(coordinator.activeExecution(executionKey))
    assertNotSame(firstExecution, restartedExecution)

    secondMayFinish.countDown()

    val waitResult = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-running-interrupt"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, waitResult.status)
    assertTrue(waitResult.stdout.contains("README and docs/notes.md inspected after redirect."))
    assertEquals("completed", waitResult.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertEquals(2, gateway.requests.size)
    assertTrue(gateway.requests[1].prompt.contains("Mention the first follow-up."))
    assertTrue(gateway.requests[1].prompt.contains("Also inspect docs/notes.md and mention it."))
    assertEquals(null, coordinator.activeExecution(executionKey))
  }

  @Test
  fun waitAgentReturnsCancelledInsteadOfRestartingWhenAnotherRuntimeClosesDetachedChild() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-wait-close-race").toPath()
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val runningHandle = SubAgentHandleState.queued(
      agentId = "child-close-race",
      childRunId = "child-run-close-race",
      childTaskId = "child-task-close-race",
      description = "inspect docs",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "parent-run-close-race",
      parentTaskId = "parent-task-close-race",
      parentTurn = 0,
      depth = 1,
      activeSkillName = null,
      activeSkillActivationSource = null,
      createdAtEpochMs = 1_000L,
    ).copy(
      snapshot = SubAgentExecutionSnapshot.backgroundRunning(
        headline = "Detached delegated child run is still running in the background.",
      ),
      updatedAtEpochMs = 1_100L,
    )
    val waitGateway = RecordingGateway(outputs = emptyList())
    val closeGateway = RecordingGateway(outputs = emptyList())
    val waitRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = waitGateway,
      seededSubAgentHandles = listOf(runningHandle),
      subAgentExecutionCoordinator = coordinator,
    )
    val closeRuntime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = closeGateway,
      seededSubAgentHandles = listOf(runningHandle),
      subAgentExecutionCoordinator = coordinator,
    )
    val activeExecutor = Executors.newSingleThreadExecutor()
    val activeFuture = FutureTask<Unit> {
      Thread.sleep(30_000L)
    }
    val activeExecution = SubAgentActiveExecution(
      executor = activeExecutor,
      future = activeFuture,
      cancelRequested = AtomicBoolean(false),
      closed = AtomicBoolean(false),
    )
    coordinator.upsertHandle(runningHandle)
    coordinator.registerActiveExecution(
      SubAgentExecutionKey.from(runningHandle),
      activeExecution,
    )
    activeExecutor.execute(activeFuture)

    var waitResult: ExecutionResult? = null
    val waitThread = Thread {
      waitResult = waitRuntime.execute(
        task = directToolCallTask(
          """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-close-race"}}""",
        ),
        hooks = runtimeHooks(),
      )
    }

    try {
      waitThread.start()
      Thread.sleep(100L)

      val closeResult = closeRuntime.execute(
        task = directToolCallTask(
          """{"type":"tool_call","tool_name":"close_agent","arguments":{"agent_id":"child-close-race"}}""",
        ),
        hooks = runtimeHooks(),
      )

      waitThread.join(5_000L)

      assertEquals(ExecutionStatus.SUCCESS, closeResult.status)
      assertEquals("true", closeResult.metadata["closed"])
      assertFalse(waitThread.isAlive)
      assertEquals(ExecutionStatus.CANCELLED, waitResult?.status)
      assertEquals("SUBAGENT_CANCELLED", waitResult?.errorCode)
      val waitStderr = requireNotNull(waitResult).stderr
      assertTrue(
        waitStderr,
        waitStderr.contains("Subagent cancelled") ||
          waitStderr.contains("cancelled before wait_agent could harvest it."),
      )
      assertEquals(null, coordinator.currentHandle(SubAgentExecutionKey.from(runningHandle)))
      assertEquals(null, coordinator.activeExecution(SubAgentExecutionKey.from(runningHandle)))
      assertTrue(waitGateway.requests.isEmpty())
      assertTrue(closeGateway.requests.isEmpty())
    } finally {
      waitThread.interrupt()
      waitThread.join(1_000L)
      activeExecutor.shutdownNow()
    }
  }

  @Test
  fun coordinatorBackedSeededWaitAgentReturnsCancelledWhenHandleWasClosedBeforeWaitStarts() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-wait-close-stale-seed").toPath()
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val runningHandle = SubAgentHandleState.queued(
      agentId = "child-stale-close-race",
      childRunId = "child-run-stale-close-race",
      childTaskId = "child-task-stale-close-race",
      description = "inspect docs",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "parent-run-stale-close-race",
      parentTaskId = "parent-task-stale-close-race",
      parentTurn = 0,
      depth = 1,
      activeSkillName = null,
      activeSkillActivationSource = null,
      createdAtEpochMs = 1_000L,
    ).copy(
      snapshot = SubAgentExecutionSnapshot.backgroundRunning(
        headline = "Detached delegated child run is still running in the background.",
      ),
      updatedAtEpochMs = 1_100L,
    )
    coordinator.upsertHandle(runningHandle)
    coordinator.removeHandle(SubAgentExecutionKey.from(runningHandle))
    val gateway = RecordingGateway(outputs = emptyList())
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      seededSubAgentHandles = listOf(runningHandle),
      subAgentExecutionCoordinator = coordinator,
      seededDetachedSubAgentHandlesRequireCoordinatorOwnership = true,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-stale-close-race"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(
      "status=${result.status} errorCode=${result.errorCode} errorMessage=${result.errorMessage} stdout=${result.stdout} stderr=${result.stderr}",
      ExecutionStatus.CANCELLED,
      result.status,
    )
    assertEquals("SUBAGENT_CANCELLED", result.errorCode)
    assertTrue(result.stderr.contains("cancelled before wait_agent could harvest it."))
    assertTrue(gateway.requests.isEmpty())
  }

  @Test
  fun waitAgentReturnsCancelledFromCoordinatorClosedTombstoneWithoutSeededHandle() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-wait-close-tombstone").toPath()
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val closedHandle = SubAgentHandleState.queued(
      agentId = "child-closed-tombstone",
      childRunId = "child-run-closed-tombstone",
      childTaskId = "child-task-closed-tombstone",
      description = "inspect docs",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "parent-run-closed-tombstone",
      parentTaskId = "parent-task-closed-tombstone",
      parentTurn = 0,
      depth = 1,
      activeSkillName = null,
      activeSkillActivationSource = null,
      createdAtEpochMs = 1_000L,
    ).copy(
      snapshot = SubAgentExecutionSnapshot(
        state = SubAgentExecutionState.CANCELLED,
        continuationKind = SubAgentContinuationKind.NONE,
        resumable = false,
        requiresUserAction = false,
        isHighRisk = false,
        headline = "Delegated child run 'inspect docs' was cancelled before wait_agent could harvest it.",
      ),
      childExecutionStatus = ExecutionStatus.CANCELLED.name,
      updatedAtEpochMs = 1_200L,
    )
    coordinator.noteClosedHandle(closedHandle)
    val gateway = RecordingGateway(outputs = emptyList())
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      seededSubAgentHandles = emptyList(),
      subAgentExecutionCoordinator = coordinator,
      seededDetachedSubAgentHandlesRequireCoordinatorOwnership = true,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-closed-tombstone"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(
      "status=${result.status} errorCode=${result.errorCode} errorMessage=${result.errorMessage} stdout=${result.stdout} stderr=${result.stderr}",
      ExecutionStatus.CANCELLED,
      result.status,
    )
    assertEquals("SUBAGENT_CANCELLED", result.errorCode)
    assertTrue(result.stderr.contains("cancelled before wait_agent could harvest it."))
    assertTrue(gateway.requests.isEmpty())
  }

  @Test
  fun closeAgentReturnsSuccessFromCoordinatorClosedTombstoneWithoutSeededHandle() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-close-closed-tombstone").toPath()
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val closedHandle = SubAgentHandleState.queued(
      agentId = "child-close-closed-tombstone",
      childRunId = "child-run-close-closed-tombstone",
      childTaskId = "child-task-close-closed-tombstone",
      description = "inspect docs",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "parent-run-close-closed-tombstone",
      parentTaskId = "parent-task-close-closed-tombstone",
      parentTurn = 0,
      depth = 1,
      activeSkillName = null,
      activeSkillActivationSource = null,
      createdAtEpochMs = 1_000L,
    ).copy(
      snapshot = SubAgentExecutionSnapshot(
        state = SubAgentExecutionState.CANCELLED,
        continuationKind = SubAgentContinuationKind.NONE,
        resumable = false,
        requiresUserAction = false,
        isHighRisk = false,
        headline = "Delegated child run 'inspect docs' was closed.",
      ),
      childExecutionStatus = ExecutionStatus.CANCELLED.name,
      updatedAtEpochMs = 1_200L,
    )
    coordinator.noteClosedHandle(closedHandle)
    val gateway = RecordingGateway(outputs = emptyList())
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      seededSubAgentHandles = emptyList(),
      subAgentExecutionCoordinator = coordinator,
      seededDetachedSubAgentHandlesRequireCoordinatorOwnership = true,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"close_agent","arguments":{"agent_id":"child-close-closed-tombstone"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("true", result.metadata["closed"])
    assertEquals("child-close-closed-tombstone", result.metadata["agentId"])
    assertTrue(gateway.requests.isEmpty())
  }

  @Test
  fun listSubagentsIncludesCoordinatorClosedTombstones() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-list-closed-tombstone").toPath()
    val coordinator = InMemorySubAgentExecutionCoordinator()
    coordinator.noteClosedHandle(
      SubAgentHandleState.queued(
        agentId = "child-list-closed-tombstone",
        childRunId = "child-run-list-closed-tombstone",
        childTaskId = "child-task-list-closed-tombstone",
        description = "inspect docs",
        prompt = "Read README.md and summarize it.",
        subagentType = "researcher",
        contextMode = "minimal",
        parentRunId = "parent-run-list-closed-tombstone",
        parentTaskId = "parent-task-list-closed-tombstone",
        parentTurn = 0,
        depth = 1,
        activeSkillName = null,
        activeSkillActivationSource = null,
        createdAtEpochMs = 1_000L,
      ).copy(
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.CANCELLED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "Delegated child run 'inspect docs' was closed.",
        ),
        childExecutionStatus = ExecutionStatus.CANCELLED.name,
        updatedAtEpochMs = 1_200L,
      ),
    )
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = RecordingGateway(outputs = emptyList()),
      subAgentExecutionCoordinator = coordinator,
      seededDetachedSubAgentHandlesRequireCoordinatorOwnership = true,
    )

    val result = runtime.execute(
      task = directToolCallTask("""{"type":"tool_call","tool_name":"list_subagents","arguments":{}}"""),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("1", result.metadata["subagentCount"])
    assertEquals("0", result.metadata["openSubagentCount"])
    val payload = TEST_JSON.parseToJsonElement(result.stdout).jsonObject
    assertEquals("1", payload.getValue("count").jsonPrimitive.content)
    assertEquals("0", payload.getValue("openCount").jsonPrimitive.content)
    val handle = payload.getValue("subagents").jsonArray.single().jsonObject
    assertEquals("child-list-closed-tombstone", handle.getValue("agentId").jsonPrimitive.content)
    assertEquals("cancelled", handle.getValue("state").jsonPrimitive.content)
    assertEquals("false", handle.getValue("hasActiveExecution").jsonPrimitive.content)
  }

  @Test
  fun sendInputReturnsNotQueueableFromCoordinatorClosedTombstoneWithoutSeededHandle() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-send-input-closed-tombstone").toPath()
    val coordinator = InMemorySubAgentExecutionCoordinator()
    coordinator.noteClosedHandle(
      SubAgentHandleState.queued(
        agentId = "child-send-input-closed-tombstone",
        childRunId = "child-run-send-input-closed-tombstone",
        childTaskId = "child-task-send-input-closed-tombstone",
        description = "inspect docs",
        prompt = "Read README.md and summarize it.",
        subagentType = "researcher",
        contextMode = "minimal",
        parentRunId = "parent-run-send-input-closed-tombstone",
        parentTaskId = "parent-task-send-input-closed-tombstone",
        parentTurn = 0,
        depth = 1,
        activeSkillName = null,
        activeSkillActivationSource = null,
        createdAtEpochMs = 1_000L,
      ).copy(
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.CANCELLED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "Delegated child run 'inspect docs' was closed.",
        ),
        childExecutionStatus = ExecutionStatus.CANCELLED.name,
        updatedAtEpochMs = 1_200L,
      ),
    )
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = RecordingGateway(outputs = emptyList()),
      subAgentExecutionCoordinator = coordinator,
      seededDetachedSubAgentHandlesRequireCoordinatorOwnership = true,
    )

    val result = runtime.execute(
      task = directToolCallTask(
        """{"type":"tool_call","tool_name":"send_input","arguments":{"agent_id":"child-send-input-closed-tombstone","message":"follow up"}}""",
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("SUBAGENT_NOT_QUEUEABLE", result.errorCode)
    assertEquals("child-send-input-closed-tombstone", result.metadata["agentId"])
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
    val subAgentEvents = eventSink.events.filterIsInstance<OpenCraySubAgentEvent>()
    assertEquals("true", closeResultMetadata["closed"])
    assertEquals(
      listOf(OpenCraySubAgentPhase.STARTED, OpenCraySubAgentPhase.RESUMED, OpenCraySubAgentPhase.CANCELLED),
      subAgentEvents.map(OpenCraySubAgentEvent::phase),
    )
    assertEquals(listOf("child-1", "child-1", "child-1"), subAgentEvents.map(OpenCraySubAgentEvent::agentId))
    assertEquals(listOf(false, false, true), subAgentEvents.map(OpenCraySubAgentEvent::closed))
  }

  @Test
  fun parentCancellationCascadesToRunningDetachedChild() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-parent-cancel-cascade").toPath()
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val childStarted = CountDownLatch(1)
    val childMayFinish = CountDownLatch(1)
    val cancelRequested = AtomicBoolean(false)
    var parentTurn = 0
    val gateway = ScriptedGateway { request ->
      when {
        requestHasTool(request, "Read") && !requestHasTool(request, "spawn_agent") -> {
          childStarted.countDown()
          childMayFinish.await(30, TimeUnit.SECONDS)
          """{"type":"final","answer":"README says hello."}"""
        }

        requestHasTool(request, "spawn_agent") -> when (parentTurn++) {
          0 -> """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-cancelled","description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}"""
          1 -> {
            while (!cancelRequested.get()) {
              Thread.sleep(10)
            }
            """{"type":"commentary","text":"Parent run is about to be cancelled."}"""
          }

          else -> error("Unexpected extra parent turn after cancellation.")
        }

        else -> error("Unexpected prompt for ${request.requestId}.")
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
      subAgentExecutionCoordinator = coordinator,
    )
    val executionExecutor = Executors.newSingleThreadExecutor()
    try {
      val runTask = FutureTask {
        runtime.execute(
          task = promptTask("Launch a child and then cancel the parent."),
          hooks = runtimeHooks(isCancellationRequested = cancelRequested::get),
        )
      }
      executionExecutor.execute(runTask)

      assertTrue(childStarted.await(5, TimeUnit.SECONDS))
      cancelRequested.set(true)

      val result = runTask.get(5, TimeUnit.SECONDS)
      assertEquals(ExecutionStatus.CANCELLED, result.status)
      assertEquals("AGENT_CANCELLED", result.errorCode)
      assertTrue(gateway.requests.size in 2..3)

      val cancelledHandle = coordinator.allHandles().single()
      assertEquals("child-cancelled", cancelledHandle.agentId)
      assertEquals(SubAgentExecutionState.CANCELLED, cancelledHandle.snapshot.state)
      assertEquals(ExecutionStatus.CANCELLED.name, cancelledHandle.childExecutionStatus)
      assertTrue(
        cancelledHandle.snapshot.detailLines.contains("Parent run was cancelled by the user."),
      )
      assertEquals(null, coordinator.activeExecution(SubAgentExecutionKey.from(cancelledHandle)))
      assertEquals(
        listOf(OpenCraySubAgentPhase.STARTED, OpenCraySubAgentPhase.RESUMED, OpenCraySubAgentPhase.CANCELLED),
        eventSink.events
          .filterIsInstance<OpenCraySubAgentEvent>()
          .map(OpenCraySubAgentEvent::phase),
      )
    } finally {
      childMayFinish.countDown()
      executionExecutor.shutdownNow()
    }
  }

  @Test
  fun parentCancellationAlsoCancelsQueuedAndApprovalPausedDetachedChildren() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-parent-cancel-open-handles").toPath()
    val queuedHandle = SubAgentHandleState.queued(
      agentId = "child-queued-cancelled",
      childRunId = "child-run-queued-cancelled",
      childTaskId = "child-task-queued-cancelled",
      description = "Inspect README later",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "parent-run-queued-cancelled",
      parentTaskId = "parent-task-queued-cancelled",
      parentTurn = 0,
      depth = 1,
      activeSkillName = null,
      activeSkillActivationSource = null,
      createdAtEpochMs = 1_000L,
    )
    val waitingHandle = SubAgentHandleState(
      agentId = "child-waiting-cancelled",
      childRunId = "child-run-waiting-cancelled",
      childTaskId = "child-task-waiting-cancelled",
      description = "Edit notes after approval",
      prompt = "Replace TODO with DONE in notes.txt.",
      subagentType = "worker",
      contextMode = "delegated",
      parentRunId = "parent-run-waiting-cancelled",
      parentTaskId = "parent-task-waiting-cancelled",
      parentTurn = 1,
      depth = 1,
      snapshot = SubAgentExecutionSnapshot(
        state = SubAgentExecutionState.WAITING_APPROVAL,
        continuationKind = SubAgentContinuationKind.PROMPT_RESUME,
        resumable = true,
        requiresUserAction = true,
        isHighRisk = false,
        headline = "Waiting for edit approval.",
      ),
      pendingApprovalResume = SubAgentApprovalResume(
        approvedToolName = "Edit",
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 1,
        ),
        isHighRisk = false,
        agentId = "child-waiting-cancelled",
        childRunId = "child-run-waiting-cancelled",
        childTaskId = "child-task-waiting-cancelled",
      ),
      childPromptResumeState = OpenCrayPromptResumeState(
        turnIndex = 1,
        toolCallCount = 1,
      ),
      createdAtEpochMs = 1_100L,
      updatedAtEpochMs = 1_150L,
    )
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = RecordingGateway(outputs = emptyList()),
      promptResumeState = OpenCrayPromptResumeState(
        turnIndex = 0,
        toolCallCount = 0,
        subAgentHandles = listOf(queuedHandle, waitingHandle),
      ),
      eventSink = eventSink,
      subAgentExecutionCoordinator = coordinator,
    )

    val result = runtime.execute(
      task = promptTask("Cancel the parent immediately."),
      hooks = runtimeHooks(isCancellationRequested = { true }),
    )

    assertEquals(ExecutionStatus.CANCELLED, result.status)
    assertEquals("AGENT_CANCELLED", result.errorCode)
    val cancelledHandles = coordinator.allHandles()
      .associateBy(SubAgentHandleState::agentId)
    assertEquals(
      setOf("child-queued-cancelled", "child-waiting-cancelled"),
      cancelledHandles.keys,
    )
    cancelledHandles.values.forEach { handle ->
      assertEquals(SubAgentExecutionState.CANCELLED, handle.snapshot.state)
      assertEquals(ExecutionStatus.CANCELLED.name, handle.childExecutionStatus)
      assertTrue(
        handle.snapshot.detailLines.contains("Parent run was cancelled by the user."),
      )
      assertEquals(null, coordinator.activeExecution(SubAgentExecutionKey.from(handle)))
    }
    assertEquals(
      listOf(OpenCraySubAgentPhase.CANCELLED, OpenCraySubAgentPhase.CANCELLED),
      eventSink.events
        .filterIsInstance<OpenCraySubAgentEvent>()
        .map(OpenCraySubAgentEvent::phase),
    )
    assertEquals(
      listOf(
        SubAgentExecutionState.CANCELLED,
        SubAgentExecutionState.CANCELLED,
      ),
      eventSink.events
        .filterIsInstance<OpenCraySubAgentEvent>()
        .map(OpenCraySubAgentEvent::executionState),
    )
  }

  @Test
  fun unexpectedParentFailureCancelsOwnedRunningChildAndLeavesDetachedExecutionRunning() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-parent-exception-cascade").toPath()
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val ownedChildStarted = CountDownLatch(1)
    val cancelRequested = AtomicBoolean(false)
    var parentTurn = 0
    val gateway = ScriptedGateway { request ->
      when {
        requestHasTool(request, "Read") && !requestHasTool(request, "spawn_agent") -> {
          ownedChildStarted.countDown()
          while (!cancelRequested.get()) {
            Thread.sleep(10)
          }
          """{"type":"final","answer":"README says hello."}"""
        }

        requestHasTool(request, "spawn_agent") -> when (parentTurn++) {
          0 -> """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-orphaned","description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}"""
          else -> """{"type":"final","answer":"Leaving the child running."}"""
        }

        else -> error("Unexpected prompt for ${request.requestId}.")
      }
    }
    val detachedHandle = SubAgentHandleState(
      agentId = "child-detached-survivor",
      childRunId = "child-run-detached-survivor",
      childTaskId = "child-task-detached-survivor",
      description = "Detached survivor run",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "parent-run-detached-survivor",
      parentTaskId = "parent-task-detached-survivor",
      parentTurn = 0,
      depth = 1,
      snapshot = SubAgentExecutionSnapshot.backgroundQueued(
        headline = "Delegated child run queued from an earlier parent run.",
      ),
      createdAtEpochMs = 900L,
      updatedAtEpochMs = 950L,
    )
    val detachedExecutor = Executors.newSingleThreadExecutor()
    val detachedMayFinish = CountDownLatch(1)
    val detachedCancelRequested = AtomicBoolean(false)
    val detachedClosed = AtomicBoolean(false)
    val detachedFuture = FutureTask<Unit> {
      detachedMayFinish.await(30, TimeUnit.SECONDS)
    }
    val detachedExecution = SubAgentActiveExecution(
      executor = detachedExecutor,
      future = detachedFuture,
      cancelRequested = detachedCancelRequested,
      closed = detachedClosed,
    )
    val supplementCallCount = AtomicInteger(0)
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      seededSubAgentHandles = listOf(detachedHandle),
      subAgentExecutionCoordinator = coordinator,
      supplementInputProvider = { _, _ ->
        if (supplementCallCount.incrementAndGet() >= 2) {
          assertTrue(ownedChildStarted.await(10, TimeUnit.SECONDS))
          throw IllegalStateException("Simulated unexpected parent failure.")
        }
        emptyList()
      },
    )
    assertTrue(coordinator.beginExecution(detachedHandle, detachedExecution).started)
    detachedExecutor.execute(detachedFuture)
    val executionExecutor = Executors.newSingleThreadExecutor()
    try {
      val runTask = FutureTask {
        runtime.execute(
          task = promptTask("Launch a child then fail unexpectedly."),
          hooks = runtimeHooks(),
        )
      }
      executionExecutor.execute(runTask)

      assertTrue(ownedChildStarted.await(10, TimeUnit.SECONDS))
      try {
        runTask.get(15, TimeUnit.SECONDS)
        fail("Expected the parent run to fail unexpectedly.")
      } catch (executionError: ExecutionException) {
        assertTrue(executionError.cause is IllegalStateException)
      }

      val ownedKey = SubAgentExecutionKey(
        parentRunId = "prompt-task",
        agentId = "child-orphaned",
      )
      val cleanupDeadline = System.currentTimeMillis() + 5_000L
      while (coordinator.activeExecution(ownedKey) != null &&
        System.currentTimeMillis() < cleanupDeadline
      ) {
        Thread.sleep(25)
      }
      assertEquals(null, coordinator.activeExecution(ownedKey))
      val cancelledOwnedHandle = coordinator.allHandles().first { handle ->
        handle.agentId == "child-orphaned"
      }
      assertEquals(SubAgentExecutionState.CANCELLED, cancelledOwnedHandle.snapshot.state)
      assertEquals(
        ExecutionStatus.CANCELLED.name,
        cancelledOwnedHandle.childExecutionStatus,
      )
      assertTrue(
        cancelledOwnedHandle.snapshot.detailLines.contains("Parent run failed unexpectedly."),
      )

      assertSame(
        detachedExecution,
        coordinator.activeExecution(SubAgentExecutionKey.from(detachedHandle)),
      )
      assertFalse(detachedCancelRequested.get())
      assertEquals(
        SubAgentExecutionState.BACKGROUND_QUEUED,
        coordinator.currentHandle(SubAgentExecutionKey.from(detachedHandle))?.snapshot?.state,
      )
    } finally {
      cancelRequested.set(true)
      detachedMayFinish.countDown()
      executionExecutor.shutdownNow()
      detachedExecutor.shutdownNow()
    }
  }

  @Test
  fun waitAgentInterruptionPropagatesInsteadOfReportingRunningChildAsSuccess() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-wait-interrupt").toPath()
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val childStarted = CountDownLatch(1)
    val cancelRequested = AtomicBoolean(false)
    val waitingThreadRef = java.util.concurrent.atomic.AtomicReference<Thread>()
    var parentTurn = 0
    val gateway = ScriptedGateway { request ->
      when {
        requestHasTool(request, "Read") && !requestHasTool(request, "spawn_agent") -> {
          childStarted.countDown()
          while (!cancelRequested.get()) {
            Thread.sleep(10)
          }
          """{"type":"final","answer":"README says hello."}"""
        }

        requestHasTool(request, "spawn_agent") -> when (parentTurn++) {
          0 -> """{"type":"tool_call","tool_name":"spawn_agent","arguments":{"agent_id":"child-interrupted","description":"inspect readme","prompt":"Read README.md and summarize it.","subagent_type":"researcher"}}"""
          else -> {
            waitingThreadRef.set(Thread.currentThread())
            """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-interrupted"}}"""
          }
        }

        else -> error("Unexpected prompt for ${request.requestId}.")
      }
    }
    val eventSink = RecordingEventSink()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      eventSink = eventSink,
      subAgentExecutionCoordinator = coordinator,
    )
    val executionExecutor = Executors.newSingleThreadExecutor()
    try {
      val runTask = FutureTask {
        runtime.execute(
          task = promptTask("Launch a child and wait for it."),
          hooks = runtimeHooks(),
        )
      }
      executionExecutor.execute(runTask)

      assertTrue(childStarted.await(10, TimeUnit.SECONDS))
      val waitDeadline = System.currentTimeMillis() + 5_000L
      while (waitingThreadRef.get() == null && System.currentTimeMillis() < waitDeadline) {
        Thread.sleep(10)
      }
      val waitingThread = requireNotNull(waitingThreadRef.get())
      Thread.sleep(200L)
      waitingThread.interrupt()

      try {
        runTask.get(5, TimeUnit.SECONDS)
        fail("Expected the interrupted parent run to propagate the interruption.")
      } catch (executionError: ExecutionException) {
        assertTrue(executionError.cause is InterruptedException)
      }
      assertTrue(
        eventSink.events.filterIsInstance<OpenCrayToolResultEvent>().none { event ->
          event.call.toolName == "wait_agent"
        },
      )

      val ownedKey = SubAgentExecutionKey(
        parentRunId = "prompt-task",
        agentId = "child-interrupted",
      )
      val cleanupDeadline = System.currentTimeMillis() + 5_000L
      while (coordinator.activeExecution(ownedKey) != null &&
        System.currentTimeMillis() < cleanupDeadline
      ) {
        Thread.sleep(25)
      }
      assertEquals(null, coordinator.activeExecution(ownedKey))
      val cancelledHandle = coordinator.allHandles().first { handle ->
        handle.agentId == "child-interrupted"
      }
      assertEquals(SubAgentExecutionState.CANCELLED, cancelledHandle.snapshot.state)
      assertEquals(null, coordinator.activeExecution(SubAgentExecutionKey.from(cancelledHandle)))
    } finally {
      cancelRequested.set(true)
      executionExecutor.shutdownNow()
    }
  }

  @Test
  fun storedWaitResultMapsUnharvestedRunningHandleToFailedInsteadOfSuccess() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-wait-running-mapping").toPath()
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = RecordingGateway(outputs = emptyList()),
    )
    val runningHandle = SubAgentHandleState(
      agentId = "child-still-running",
      childRunId = "child-run-still-running",
      childTaskId = "child-task-still-running",
      description = "inspect docs",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "parent-run-still-running",
      parentTaskId = "parent-task-still-running",
      parentTurn = 1,
      depth = 1,
      snapshot = SubAgentExecutionSnapshot.backgroundRunning(
        headline = "Delegated child run is still running in the background.",
      ),
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
    )
    val waitCall = AgentToolCall(
      toolName = "wait_agent",
      arguments = kotlinx.serialization.json.JsonObject(emptyMap()),
    )

    val runningResult = runtime.storedSubAgentHandleResult(
      call = waitCall,
      handle = runningHandle,
    )
    assertEquals(AgentToolResultStatus.FAILED, runningResult.status)
    assertEquals(
      "Delegated child run is still running and was not harvested.",
      runningResult.errorMessage,
    )
    assertEquals(
      "background_running",
      runningResult.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE],
    )

    val queuedResult = runtime.storedSubAgentHandleResult(
      call = waitCall,
      handle = runningHandle.copy(
        snapshot = SubAgentExecutionSnapshot.backgroundQueued(
          headline = "Delegated child run queued to resume.",
        ),
      ),
    )
    assertEquals(AgentToolResultStatus.SUCCESS, queuedResult.status)
    assertEquals(
      "background_queued",
      queuedResult.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE],
    )
  }

  @Test
  fun parallelToolDispatchCancellationReturnsPromptlyAndLeavesBackgroundChildExecutionAlive() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-parallel-cancel").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val blockedRelease = CountDownLatch(1)
    val blockedCancelRequested = AtomicBoolean(false)
    val blockedClosed = AtomicBoolean(false)
    val blockedExecutor = Executors.newSingleThreadExecutor()
    val blockedFuture = FutureTask<Unit> {
      blockedRelease.await(30, TimeUnit.SECONDS)
    }
    val blockedExecution = SubAgentActiveExecution(
      executor = blockedExecutor,
      future = blockedFuture,
      cancelRequested = blockedCancelRequested,
      closed = blockedClosed,
    )
    val blockingHandle = SubAgentHandleState(
      agentId = "blocked-child",
      childRunId = "child-run-blocked",
      childTaskId = "child-task-blocked",
      description = "Blocked delegated child",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "prompt-task",
      parentTaskId = "parent-task-blocked",
      parentTurn = 0,
      depth = 1,
      snapshot = SubAgentExecutionSnapshot.backgroundRunning(
        headline = "Delegated child run is still running in the background.",
      ),
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
    )
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = RecordingGateway(outputs = emptyList()),
      subAgentExecutionCoordinator = coordinator,
    )
    assertTrue(coordinator.beginExecution(blockingHandle, blockedExecution).started)
    blockedExecutor.execute(blockedFuture)
    val cursor = OpenCrayAgentRuntime.PromptTurnCursor(
      transcript = mutableListOf(),
      sessionContext = AgentRuntimeSessionContext(),
      turn = 0,
      toolCallCount = 0,
      todoWriteUsed = false,
      activeSkillName = null,
      activeSkillActivationSource = null,
      activeSkillPinned = false,
      nextSyntheticToolCallSequence = 0,
      legacyJsonFallbackEnabled = true,
      responsesPreviousResponseId = null,
      responsesProviderLineageId = null,
      responsesLineageTrusted = false,
      responsesFullReplayRequired = false,
      responsesContinuationShape = null,
      responsesPendingMessages = mutableListOf(),
      replayToolResultProjections = linkedMapOf(),
      localContinuationEnvelope = null,
      subAgentHandles = linkedMapOf(),
      subAgentExecutionLock = Any(),
    )
    synchronized(cursor.subAgentExecutionLock) {
      cursor.subAgentHandles["blocked-child"] = blockingHandle
    }
    val steps = listOf(
      OpenCrayAgentRuntime.ParallelToolActionStep(
        index = 0,
        call = AgentToolCall(
          toolName = "wait_agent",
          arguments = buildJsonObject {
            put("agent_id", "blocked-child")
          },
        ),
      ),
      OpenCrayAgentRuntime.ParallelToolActionStep(
        index = 1,
        call = AgentToolCall(
          toolName = "Read",
          arguments = buildJsonObject {
            put("file_path", "README.md")
          },
        ),
      ),
    )
    val cancelRequested = AtomicBoolean(false)
    val dispatchExecutor = Executors.newSingleThreadExecutor()
    try {
      val dispatchFuture = dispatchExecutor.submit<List<OpenCrayAgentRuntime.ParallelToolDispatch>> {
        runtime.dispatchPromptToolCallsInParallel(
          task = promptTask("Wait and read in parallel."),
          turn = 0,
          calls = steps,
          transcript = emptyList(),
          cursor = cursor,
          hooks = runtimeHooks(isCancellationRequested = cancelRequested::get),
          activeSkillCapsule = null,
        )
      }

      Thread.sleep(600L)
      assertFalse(dispatchFuture.isDone)
      cancelRequested.set(true)

      val dispatches = dispatchFuture.get(5, TimeUnit.SECONDS)
      assertEquals(1, dispatches.size)
      assertEquals("Read", dispatches.single().result.toolName)
      assertEquals(AgentToolResultStatus.SUCCESS, dispatches.single().result.status)
      assertSame(
        blockedExecution,
        coordinator.activeExecution(SubAgentExecutionKey.from(blockingHandle)),
      )
      assertFalse(blockedCancelRequested.get())
    } finally {
      blockedRelease.countDown()
      dispatchExecutor.shutdownNow()
      blockedExecutor.shutdownNow()
    }
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
  fun directWaitAgentRegistersActiveExecutionWhileDetachedRecoveryRuns() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-cold-restart-active-execution").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val seededHandle = SubAgentHandleState(
      agentId = "child-cold-active",
      childRunId = "child-run-cold-active",
      childTaskId = "child-task-cold-active",
      description = "Inspect README",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "run-parent-cold-active",
      parentTaskId = "task-parent-cold-active",
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
    val childStarted = CountDownLatch(1)
    val childMayFinish = CountDownLatch(1)
    var childTurn = 0
    val gateway = ScriptedGateway { request ->
      childStarted.countDown()
      assertTrue(childMayFinish.await(5, TimeUnit.SECONDS))
      when (childTurn++) {
        0 -> """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}"""
        1 -> """{"type":"final","answer":"README says hello."}"""
        else -> error("Unexpected detached recovery child turn for ${request.requestId}.")
      }
    }
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      seededSubAgentHandles = listOf(seededHandle),
      subAgentExecutionCoordinator = coordinator,
    )
    val task = directToolCallTask(
      """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-cold-active"}}""",
    )
    val execution = Executors.newSingleThreadExecutor()
    val resultFuture = execution.submit<ExecutionResult> {
      runtime.execute(
        task = task,
        hooks = runtimeHooks(),
      )
    }

    assertTrue(childStarted.await(5, TimeUnit.SECONDS))
    val runningHandle = coordinator.allHandles().single()
    assertEquals(SubAgentExecutionState.BACKGROUND_RUNNING, runningHandle.snapshot.state)
    assertTrue(
      coordinator.activeExecution(SubAgentExecutionKey.from(runningHandle)) != null,
    )

    childMayFinish.countDown()
    val result = resultFuture.get(5, TimeUnit.SECONDS)

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    val completedHandle = coordinator.allHandles().single()
    assertEquals(SubAgentExecutionState.COMPLETED, completedHandle.snapshot.state)
    assertEquals(null, coordinator.activeExecution(SubAgentExecutionKey.from(completedHandle)))
    execution.shutdownNow()
  }

  @Test
  fun detachedRecoveryWaitDoesNotStartChildWithoutExplicitEnsureExecution() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-detached-recovery-no-start").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val seededHandle = SubAgentHandleState(
      agentId = "child-recovery-idle",
      childRunId = "child-run-recovery-idle",
      childTaskId = "child-task-recovery-idle",
      description = "Inspect README",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "run-parent-recovery-idle",
      parentTaskId = "task-parent-recovery-idle",
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
    val recoveryTask = AgentTask(
      id = "detached-recovery-no-start",
      type = AgentTaskType.SYSTEM,
      input = "internal:subagent_recovery_wait:child-recovery-idle",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "ALLOW_ALL",
      ),
      createdAtEpochMs = 1_000L,
    )

    val result = runtime.executeSubAgentRecoveryWait(
      task = recoveryTask,
      hooks = runtimeHooks(),
      agentId = "child-recovery-idle",
      parentRunId = "run-parent-recovery-idle",
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("background_queued", result.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    assertTrue(result.stdout.contains("queued to resume"))
    assertTrue(gateway.requests.isEmpty())
    assertTrue(
      coordinator.activeExecution(
        SubAgentExecutionKey(
          parentRunId = "run-parent-recovery-idle",
          agentId = "child-recovery-idle",
        ),
      ) == null,
    )
  }

  @Test
  fun detachedRecoveryEnsureStartsExecutionBeforeRecoveryWaitHarvestsIt() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-detached-recovery-ensure").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "hello".toByteArray(StandardCharsets.UTF_8),
    )
    val seededHandle = SubAgentHandleState(
      agentId = "child-recovery-started",
      childRunId = "child-run-recovery-started",
      childTaskId = "child-task-recovery-started",
      description = "Inspect README",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "run-parent-recovery-started",
      parentTaskId = "task-parent-recovery-started",
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
    val childStarted = CountDownLatch(1)
    val childMayFinish = CountDownLatch(1)
    var childTurn = 0
    val gateway = ScriptedGateway { request ->
      childStarted.countDown()
      assertTrue(childMayFinish.await(5, TimeUnit.SECONDS))
      when (childTurn++) {
        0 -> """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}"""
        1 -> """{"type":"final","answer":"README says hello."}"""
        else -> error("Unexpected detached recovery ensure child turn for ${request.requestId}.")
      }
    }
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = gateway,
      seededSubAgentHandles = listOf(seededHandle),
      subAgentExecutionCoordinator = coordinator,
    )
    val recoveryTask = AgentTask(
      id = "detached-recovery-with-ensure",
      type = AgentTaskType.SYSTEM,
      input = "internal:subagent_recovery_wait:child-recovery-started",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "ALLOW_ALL",
      ),
      createdAtEpochMs = 1_000L,
    )

    val startedHandle = runtime.ensureSubAgentRecoveryExecution(
      task = recoveryTask,
      hooks = runtimeHooks(),
      agentId = "child-recovery-started",
      parentRunId = "run-parent-recovery-started",
    )

    assertEquals(SubAgentExecutionState.BACKGROUND_RUNNING, startedHandle?.snapshot?.state)
    assertTrue(childStarted.await(5, TimeUnit.SECONDS))
    assertTrue(
      coordinator.activeExecution(
        SubAgentExecutionKey(
          parentRunId = "run-parent-recovery-started",
          agentId = "child-recovery-started",
        ),
      ) != null,
    )

    val execution = Executors.newSingleThreadExecutor()
    val resultFuture = execution.submit<ExecutionResult> {
      runtime.executeSubAgentRecoveryWait(
        task = recoveryTask,
        hooks = runtimeHooks(),
        agentId = "child-recovery-started",
        parentRunId = "run-parent-recovery-started",
      )
    }

    Thread.sleep(100L)
    assertFalse(resultFuture.isDone)

    childMayFinish.countDown()
    val result = resultFuture.get(5, TimeUnit.SECONDS)

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertTrue(result.stdout.contains("README says hello."))
    assertEquals("completed", result.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
    val completedHandle = coordinator.allHandles().single()
    assertEquals(SubAgentExecutionState.COMPLETED, completedHandle.snapshot.state)
    assertEquals(null, coordinator.activeExecution(SubAgentExecutionKey.from(completedHandle)))
    execution.shutdownNow()
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
      pendingApprovalResume = SubAgentApprovalResume(
        approvedToolName = "Edit",
        promptResumeState = OpenCrayPromptResumeState(
          turnIndex = 1,
          toolCallCount = 1,
        ),
        isHighRisk = false,
        agentId = "child-waiting",
        childRunId = "child-run-waiting",
        childTaskId = "child-task-waiting",
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
    val runningHandle = SubAgentHandleState(
      agentId = "child-running",
      childRunId = "child-run-running",
      childTaskId = "child-task-running",
      description = "Inspect docs",
      prompt = "Read docs/notes.md and summarize it.",
      subagentType = "worker",
      contextMode = "delegated",
      parentRunId = "parent-run-running",
      parentTaskId = "parent-task-running",
      parentTurn = 1,
      depth = 1,
      snapshot = SubAgentExecutionSnapshot.backgroundRunning(
        headline = "Docs inspection is still running.",
      ),
      createdAtEpochMs = 950L,
      updatedAtEpochMs = 1_200L,
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
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val activeExecutor = Executors.newSingleThreadExecutor()
    val activeFuture = FutureTask<Unit> { }
    coordinator.upsertHandle(runningHandle)
    coordinator.registerActiveExecution(
      SubAgentExecutionKey(
        parentRunId = runningHandle.parentRunId,
        agentId = runningHandle.agentId,
      ),
      SubAgentActiveExecution(
        executor = activeExecutor,
        future = activeFuture,
        cancelRequested = AtomicBoolean(false),
        closed = AtomicBoolean(false),
      ),
    )
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = RecordingGateway(outputs = emptyList()),
      promptResumeState = OpenCrayPromptResumeState(
        turnIndex = 0,
        toolCallCount = 0,
        subAgentHandles = listOf(waitingHandle, completedHandle),
      ),
      subAgentExecutionCoordinator = coordinator,
    )

    try {
      val result = runtime.execute(
        task = directToolCallTask("""{"type":"tool_call","tool_name":"list_subagents","arguments":{}}"""),
        hooks = runtimeHooks(),
      )

      assertEquals(ExecutionStatus.SUCCESS, result.status)
      assertEquals("3", result.metadata["subagentCount"])
      assertEquals("2", result.metadata["openSubagentCount"])
      val payload = TEST_JSON.parseToJsonElement(result.stdout).jsonObject
      assertEquals("3", payload.getValue("count").jsonPrimitive.content)
      assertEquals("2", payload.getValue("openCount").jsonPrimitive.content)
      val subagents = payload.getValue("subagents").jsonArray
      assertEquals(3, subagents.size)
      val firstHandle = subagents[0].jsonObject
      assertEquals("child-waiting", firstHandle.getValue("agentId").jsonPrimitive.content)
      assertEquals("parent-run-a", firstHandle.getValue("parentRunId").jsonPrimitive.content)
      assertEquals("worker", firstHandle.getValue("subagentType").jsonPrimitive.content)
      assertEquals("waiting_approval", firstHandle.getValue("state").jsonPrimitive.content)
      assertEquals("false", firstHandle.getValue("hasActiveExecution").jsonPrimitive.content)
      assertEquals("true", firstHandle.getValue("hasPendingApprovalResume").jsonPrimitive.content)
      assertEquals("Edit", firstHandle.getValue("pendingApprovalToolName").jsonPrimitive.content)
      assertEquals("child-run-waiting", firstHandle.getValue("pendingApprovalChildRunId").jsonPrimitive.content)
      assertEquals("child-task-waiting", firstHandle.getValue("pendingApprovalChildTaskId").jsonPrimitive.content)
      assertEquals("false", firstHandle.getValue("pendingApprovalIsHighRisk").jsonPrimitive.content)
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
      assertEquals("child-running", secondHandle.getValue("agentId").jsonPrimitive.content)
      assertEquals("background_running", secondHandle.getValue("state").jsonPrimitive.content)
      assertEquals("true", secondHandle.getValue("hasActiveExecution").jsonPrimitive.content)
      val thirdHandle = subagents[2].jsonObject
      assertEquals("child-done", thirdHandle.getValue("agentId").jsonPrimitive.content)
      assertEquals("completed", thirdHandle.getValue("state").jsonPrimitive.content)
      assertEquals("SUCCESS", thirdHandle.getValue("childExecutionStatus").jsonPrimitive.content)
      assertEquals("false", thirdHandle.getValue("hasActiveExecution").jsonPrimitive.content)
    } finally {
      activeExecutor.shutdownNow()
    }
  }

  @Test
  fun synchronizedSubAgentHandlesKeepsFinalizedCursorHandleOverStaleCoordinatorView() {
    val workspaceRoot = temporaryFolder.newFolder("subagent-sync-handles-race").toPath()
    val delegate = InMemorySubAgentExecutionCoordinator()
    val parentQueriedCoordinator = CountDownLatch(1)
    val childCommittedCursorWrite = CountDownLatch(1)
    val parentReadCoordinatorView = CountDownLatch(1)
    val gatingCoordinator =
      object : com.opencray.runtime.subagent.SubAgentExecutionCoordinator by delegate {
        override fun currentHandle(key: SubAgentExecutionKey): SubAgentHandleState? {
          parentQueriedCoordinator.countDown()
          assertTrue(childCommittedCursorWrite.await(10, TimeUnit.SECONDS))
          val view = delegate.currentHandle(key)
          parentReadCoordinatorView.countDown()
          return view
        }
      }
    val runningHandle = SubAgentHandleState(
      agentId = "child-race",
      childRunId = "child-run-race",
      childTaskId = "child-task-race",
      description = "inspect docs",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "prompt-task",
      parentTaskId = "parent-task-race",
      parentTurn = 0,
      depth = 1,
      snapshot = SubAgentExecutionSnapshot.backgroundRunning(
        headline = "Delegated child run is still running in the background.",
      ),
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_100L,
    )
    delegate.upsertHandle(runningHandle)
    val runtime = runtime(
      workspaceRoot = workspaceRoot,
      gateway = RecordingGateway(outputs = emptyList()),
      subAgentExecutionCoordinator = gatingCoordinator,
    )
    val cursor = OpenCrayAgentRuntime.PromptTurnCursor(
      transcript = mutableListOf(),
      sessionContext = AgentRuntimeSessionContext(),
      turn = 0,
      toolCallCount = 0,
      todoWriteUsed = false,
      activeSkillName = null,
      activeSkillActivationSource = null,
      activeSkillPinned = false,
      nextSyntheticToolCallSequence = 0,
      legacyJsonFallbackEnabled = true,
      responsesPreviousResponseId = null,
      responsesProviderLineageId = null,
      responsesLineageTrusted = false,
      responsesFullReplayRequired = false,
      responsesContinuationShape = null,
      responsesPendingMessages = mutableListOf(),
      replayToolResultProjections = linkedMapOf(),
      localContinuationEnvelope = null,
      subAgentHandles = linkedMapOf(),
      subAgentExecutionLock = Any(),
    )
    synchronized(cursor.subAgentExecutionLock) {
      cursor.subAgentHandles["child-race"] = runningHandle
    }
    val syncExecutor = Executors.newSingleThreadExecutor()
    try {
      val syncedFuture = syncExecutor.submit<List<SubAgentHandleState>> {
        runtime.synchronizedSubAgentHandles(cursor)
      }
      assertTrue(parentQueriedCoordinator.await(10, TimeUnit.SECONDS))
      val finalizedHandle = runningHandle.copy(
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.COMPLETED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "Delegated child run completed.",
        ),
        updatedAtEpochMs = 2_000L,
      )
      synchronized(cursor.subAgentExecutionLock) {
        cursor.subAgentHandles["child-race"] = finalizedHandle
      }
      childCommittedCursorWrite.countDown()
      assertTrue(parentReadCoordinatorView.await(10, TimeUnit.SECONDS))
      delegate.upsertHandle(finalizedHandle)
      val syncedHandles = syncedFuture.get(10, TimeUnit.SECONDS)
      assertEquals(listOf(finalizedHandle), syncedHandles)
      synchronized(cursor.subAgentExecutionLock) {
        assertSame(finalizedHandle, cursor.subAgentHandles["child-race"])
        assertEquals(SubAgentExecutionState.COMPLETED, cursor.subAgentHandles.getValue("child-race").snapshot.state)
      }
    } finally {
      syncExecutor.shutdownNow()
    }
  }

  @Test
  fun closedSubAgentHandlesBeyondCapacityEvictOldestAndKeepLiveHandles() {
    val coordinator = InMemorySubAgentExecutionCoordinator()
    val liveHandle = SubAgentHandleState(
      agentId = "child-live",
      childRunId = "child-run-live",
      childTaskId = "child-task-live",
      description = "inspect docs live",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = "minimal",
      parentRunId = "prompt-task",
      parentTaskId = "parent-task-live",
      parentTurn = 0,
      depth = 1,
      snapshot = SubAgentExecutionSnapshot.backgroundRunning(
        headline = "Delegated child run is still running in the background.",
      ),
      createdAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_050L,
    )
    coordinator.upsertHandle(liveHandle)
    val totalCloses = MAX_RETAINED_CLOSED_SUB_AGENT_HANDLES + 2
    val closedHandles = (0 until totalCloses).map { index ->
      SubAgentHandleState.queued(
        agentId = "child-closed-$index",
        childRunId = "child-run-closed-$index",
        childTaskId = "child-task-closed-$index",
        description = "inspect docs $index",
        prompt = "Read README.md and summarize it.",
        subagentType = "researcher",
        contextMode = "minimal",
        parentRunId = "prompt-task",
        parentTaskId = "parent-task-closed-$index",
        parentTurn = 0,
        depth = 1,
        activeSkillName = null,
        activeSkillActivationSource = null,
        createdAtEpochMs = 1_100L + index,
      ).copy(
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.CANCELLED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "Delegated child run 'inspect docs $index' was closed.",
        ),
        childExecutionStatus = ExecutionStatus.CANCELLED.name,
        updatedAtEpochMs = 1_200L + index,
      )
    }
    closedHandles.forEach(coordinator::noteClosedHandle)
    assertEquals(MAX_RETAINED_CLOSED_SUB_AGENT_HANDLES, coordinator.allClosedHandles().size)
    assertNull(coordinator.closedHandle(SubAgentExecutionKey("prompt-task", "child-closed-0")))
    assertNull(coordinator.closedHandle(SubAgentExecutionKey("prompt-task", "child-closed-1")))
    assertEquals(
      closedHandles.last(),
      coordinator.closedHandle(SubAgentExecutionKey("prompt-task", "child-closed-${totalCloses - 1}")),
    )
    assertEquals(liveHandle, coordinator.currentHandle(SubAgentExecutionKey.from(liveHandle)))
    assertEquals(listOf(liveHandle), coordinator.allHandles())
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
    seededDetachedSubAgentHandlesRequireCoordinatorOwnership: Boolean = false,
    subAgentExecutionCoordinator: com.opencray.runtime.subagent.SubAgentExecutionCoordinator =
      InMemorySubAgentExecutionCoordinator(),
    subAgentContextPolicy: SubAgentContextPolicy = SubAgentContextPolicy(),
    supplementInputProvider: (String, String) -> List<OpenCraySupplementInput> = { _, _ -> emptyList() },
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
      seededDetachedSubAgentHandlesRequireCoordinatorOwnership =
        seededDetachedSubAgentHandlesRequireCoordinatorOwnership,
      subAgentExecutionCoordinator = subAgentExecutionCoordinator,
      subAgentContextPolicy = subAgentContextPolicy,
      supplementInputProvider = supplementInputProvider,
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
