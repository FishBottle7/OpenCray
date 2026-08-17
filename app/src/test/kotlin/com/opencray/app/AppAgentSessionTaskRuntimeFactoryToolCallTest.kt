package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.policy.SafetyAutomationMode
import com.opencray.policy.SafetySettingsMetadataKeys
import com.opencray.policy.ToolPolicyOverride
import com.opencray.runtime.NoOpOpenCrayAgentRuntimeEventSink
import com.opencray.runtime.context.RuntimeConversationAttachment
import com.opencray.runtime.context.RuntimeConversationAttachmentKind
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentResultMetadataKeys
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.skills.SkillInstallManifestStore
import com.opencray.runtime.skills.SkillPackageManager
import com.opencray.skills.SkillLoader
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactoryToolCallTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun toolCallTaskDoesNotRequireConfiguredLlm() {
    val workspaceRoot = temporaryFolder.newFolder("workspace").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-call"))
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val runtime = runtimeFactory.create(
      sessionId = chatStore.loadState().activeSession.sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "tool-call-without-llm",
      type = AgentTaskType.TOOL_CALL,
      input =
        """{"type":"tool_call","tool_name":"TodoWrite","arguments":{"todos":[{"content":"Ship update entry","status":"in_progress"}]}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for direct tool call test.") },
      ),
    )

    assertEquals(
      "status=${result.status} errorCode=${result.errorCode} errorMessage=${result.errorMessage} stdout=${result.stdout} stderr=${result.stderr}",
      ExecutionStatus.SUCCESS,
      result.status,
    )
    assertTrue(result.stdout.contains("Ship update entry"))
    assertEquals(1, runtimeFactory.todoStoreForSession(chatStore.loadState().activeSession.sessionId).snapshot().size)
  }

  @Test
  fun toolCallTaskCanCreateScheduledTaskThroughRuntimeManager() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-scheduled-tool-call").toPath()
    val scheduledStorageRoot = temporaryFolder.newFolder("scheduled-tool-call-storage").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-scheduled-tool-call"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val specStore = inMemoryScheduledTaskSpecStoreFactory().create()
    val runRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create()
    val triggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory().create()
    val triggerRegistrar = RecordingScheduledTriggerRegistrar()
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      scheduledTaskManagerProvider = {
        AppScheduledTaskManager(
          storageRootPath = scheduledStorageRoot,
          chatSessionStore = chatStore,
          specStore = specStore,
          runRecordStore = runRecordStore,
          triggerRegistrar = triggerRegistrar,
          triggerSyncStateStore = triggerSyncStateStore,
          clock = { 10_000L },
        )
      },
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "tool-call-scheduled-create",
      type = AgentTaskType.TOOL_CALL,
      input =
        """
        {
          "type":"tool_call",
          "tool_name":"ScheduledTaskCreate",
          "arguments":{
            "prompt":"Summarize the session status",
            "trigger":{
              "after":"PT1M"
            }
          }
        }
        """.trimIndent(),
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      metadata = mapOf(
        "chatMode" to "DEVELOPER",
        "_host.sessionId" to sessionId,
      ),
      createdAtEpochMs = 1_000L,
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for scheduled task tool call test.") },
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("schedule_task", result.metadata["capabilityKind"])
    assertEquals("scheduling", result.metadata["intentCategory"])
    assertEquals("create_scheduled_task", result.metadata["schedulingIntentKind"])
    assertEquals("after", result.metadata["scheduleTriggerKind"])
    assertTrue(result.stdout.contains("Scheduled task created."))
    assertTrue(result.stdout.contains("session_id=$sessionId"))
    val spec = specStore.list().single()
    assertEquals(sessionId, spec.sessionId)
    assertEquals("Summarize the session status", spec.title)
    assertEquals("Summarize the session status", spec.payload.prompt)
    assertTrue(spec.trigger is ScheduledTrigger.After)
    assertEquals(60_000L, (spec.trigger as ScheduledTrigger.After).delayMs)
    assertEquals(10_000L, (spec.trigger as ScheduledTrigger.After).createdAtEpochMs)
    assertEquals(listOf(spec.scheduleId), triggerRegistrar.syncedScheduleIds)
    assertEquals(linkedSetOf(spec.scheduleId), triggerSyncStateStore.loadScheduleIds())
  }

  @Test
  fun promptTaskReturnsMissingLlmConfigWhenOnDeviceModelIsNotReady() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-on-device-llm").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-on-device-llm"))
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = {
        LlmSettingsState(
          enabled = true,
          providerMode = LlmProviderModes.ON_DEVICE_MODEL,
          selectedOnDeviceModelId = "gemma-4-e2b-it",
        )
      },
      onDeviceModelReadyProvider = { false },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val runtime = runtimeFactory.create(
      sessionId = chatStore.loadState().activeSession.sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "prompt-on-device-llm",
      type = AgentTaskType.PROMPT,
      input = "Explain the current repo structure.",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for on-device runtime guard test.") },
      ),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals(
      AppAgentSessionTaskRuntimeFactory.ERROR_CODE_MISSING_LLM_CONFIG,
      result.errorCode,
    )
    assertEquals(
      "LLM configuration is incomplete.",
      result.errorMessage,
    )
  }

  @Test
  fun promptTaskBindsLiteRtAutomaticToolExecutionContextForOnDeviceDebugMode() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-on-device-auto-tool-context").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-on-device-auto-tool-context"))
    val providerClient = RecordingLiteRtContextProviderClient(
      result = LiteLlmProviderResult.Failure(
        errorCode = "TEST_STOP",
        errorMessage = "Stop after provider capture.",
      ),
    )
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = {
        LlmSettingsState(
          enabled = true,
          providerMode = LlmProviderModes.ON_DEVICE_MODEL,
          providerId = "",
          selectedOnDeviceModelId = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
        )
      },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      providerClient = providerClient,
      enableLiteRtDevAutomaticToolExecution = true,
    )
    val runtime = runtimeFactory.create(
      sessionId = chatStore.loadState().activeSession.sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "prompt-on-device-auto-tool-context",
      type = AgentTaskType.PROMPT,
      input = "Summarize the project layout.",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for on-device automatic tool binding test.") },
      ),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("TEST_STOP", result.errorCode)
    assertEquals(1, providerClient.requestCount)
    assertEquals(
      LlmProviderModes.ON_DEVICE_MODEL,
      providerClient.recordedRequest?.route?.metadata?.get(LiteRtOnDeviceMetadataKeys.PROVIDER_MODE),
    )
    assertEquals(
      OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
      providerClient.recordedRequest?.route?.model,
    )
    assertTrue(providerClient.recordedRequest?.route?.baseUrl.isNullOrBlank())
    val context = checkNotNull(providerClient.recordedAutomaticToolExecutionContext)
    assertEquals(task.id, context.task.id)
    assertEquals(null, LiteRtAutomaticToolExecutionRegistry.current())
  }

  @Test
  fun promptTaskInjectsLocalizedThinkingLabelIntoOnDeviceRouteMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-on-device-thinking-label").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-on-device-thinking-label"))
    val providerClient = RecordingLiteRtContextProviderClient(
      result = LiteLlmProviderResult.Failure(
        errorCode = "TEST_STOP",
        errorMessage = "Stop after provider capture.",
      ),
    )
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = {
        LlmSettingsState(
          enabled = true,
          providerMode = LlmProviderModes.ON_DEVICE_MODEL,
          providerId = "",
          selectedOnDeviceModelId = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
        )
      },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      providerClient = providerClient,
      onDeviceThinkingTextProvider = { "思考中…" },
    )
    val runtime = runtimeFactory.create(
      sessionId = chatStore.loadState().activeSession.sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "prompt-on-device-thinking-label",
      type = AgentTaskType.PROMPT,
      input = "你好",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for on-device thinking label test.") },
      ),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("TEST_STOP", result.errorCode)
    assertEquals(
      "思考中…",
      providerClient.recordedRequest?.route?.metadata?.get(LiteRtOnDeviceMetadataKeys.THINKING_LABEL),
    )
  }

  @Test
  fun promptTaskFallsBackToCanonicalThinkingLabelForOnDeviceRouteMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-on-device-thinking-label-default").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-on-device-thinking-label-default"))
    val providerClient = RecordingLiteRtContextProviderClient(
      result = LiteLlmProviderResult.Failure(
        errorCode = "TEST_STOP",
        errorMessage = "Stop after provider capture.",
      ),
    )
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = {
        LlmSettingsState(
          enabled = true,
          providerMode = LlmProviderModes.ON_DEVICE_MODEL,
          providerId = "",
          selectedOnDeviceModelId = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
        )
      },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      providerClient = providerClient,
    )
    val runtime = runtimeFactory.create(
      sessionId = chatStore.loadState().activeSession.sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "prompt-on-device-thinking-label-default",
      type = AgentTaskType.PROMPT,
      input = "Hello",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for default on-device thinking label test.") },
      ),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("TEST_STOP", result.errorCode)
    assertEquals(
      "Thinking…",
      providerClient.recordedRequest?.route?.metadata?.get(LiteRtOnDeviceMetadataKeys.THINKING_LABEL),
    )
  }

  @Test
  fun promptTaskSuppressesToolExposureWhenOnDeviceLiteModeEnabled() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-on-device-lite-mode").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-on-device-lite-mode"))
    val providerClient = RecordingLiteRtContextProviderClient(
      result = LiteLlmProviderResult.Failure(
        errorCode = "TEST_STOP",
        errorMessage = "Stop after provider capture.",
      ),
    )
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = {
        LlmSettingsState(
          enabled = true,
          providerMode = LlmProviderModes.ON_DEVICE_MODEL,
          providerId = "",
          selectedOnDeviceModelId = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
          onDeviceMaxContextWindow = 32_768,
          onDeviceMaxTokens = 4_096,
          onDeviceLiteModeEnabled = true,
        )
      },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      providerClient = providerClient,
    )
    val runtime = runtimeFactory.create(
      sessionId = chatStore.loadState().activeSession.sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "prompt-on-device-lite-mode",
      type = AgentTaskType.PROMPT,
      input = "你好",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for on-device lite mode test.") },
      ),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("TEST_STOP", result.errorCode)
    val request = checkNotNull(providerClient.recordedRequest)
    assertEquals(
      "true",
      request.route.metadata[LiteRtOnDeviceMetadataKeys.LITE_MODE_ENABLED],
    )
    assertEquals(
      "minimal",
      request.route.metadata["toolProtocolDetailMode"],
    )
    assertEquals(
      "7168",
      request.route.metadata["context_window_tokens"],
    )
    assertTrue(request.request.tools.isEmpty())
    assertTrue(request.request.builtinTools.isEmpty())
  }

  @Test
  fun releaseSessionEvictsSessionScopedCaches() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-release-session-caches").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-release-session-caches"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val initialTodoStore = runtimeFactory.todoStoreForSession(sessionId)
    val initialObservationTracker = runtimeFactory.managedProcessObservationTrackerForSession(sessionId)

    runtimeFactory.releaseSession(sessionId)

    val recreatedTodoStore = runtimeFactory.todoStoreForSession(sessionId)
    val recreatedObservationTracker = runtimeFactory.managedProcessObservationTrackerForSession(sessionId)

    assertTrue(initialTodoStore !== recreatedTodoStore)
    assertTrue(initialObservationTracker !== recreatedObservationTracker)
  }

  @Test
  fun processObservationCursorPersistsAcrossTaskRuntimesWithinSameSession() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-process-observation-session").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-process-observation-session"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val processRegistry = SequencedObservationProcessRegistry(
      startedPlan = observationPlan(
        stdout = "booting",
        cursor = "host_seq_1",
      ),
      readPlans = mutableListOf(
        observationPlan(
          stdout = "booting\nready",
          cursor = "host_seq_2",
        ),
      ),
      waitPlans = mutableListOf(
        observationPlan(
          status = ManagedProcessStatus.SUCCESS,
          stdout = "booting\nready\ndone",
          cursor = "host_seq_3",
          exitCode = 0,
          finishedAtEpochMs = 1_250L,
        ),
      ),
    )
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      processRegistryProvider = { processRegistry },
    )
    val startRuntime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val readRuntime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val waitRuntime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )

    val startResult = startRuntime.execute(
      developerToolCallTask(
        id = "process-observation-start",
        toolName = "ProcessStart",
        argumentsJson =
          """{"command":"npm","args":["run","dev"],"working_directory":".","timeout_ms":120000}""",
      ),
      runtimeHooks("Retry was not expected for process observation start."),
    )
    val processId = requireNotNull(startResult.metadata["processId"])

    val firstRead = readRuntime.execute(
      developerToolCallTask(
        id = "process-observation-read-1",
        toolName = "ProcessRead",
        argumentsJson = """{"process_id":"$processId"}""",
      ),
      runtimeHooks("Retry was not expected for first process observation read."),
    )
    val secondRead = readRuntime.execute(
      developerToolCallTask(
        id = "process-observation-read-2",
        toolName = "ProcessRead",
        argumentsJson = """{"process_id":"$processId"}""",
      ),
      runtimeHooks("Retry was not expected for second process observation read."),
    )
    val waitResult = waitRuntime.execute(
      developerToolCallTask(
        id = "process-observation-wait",
        toolName = "ProcessWait",
        argumentsJson = """{"process_id":"$processId","timeout_ms":250}""",
      ),
      runtimeHooks("Retry was not expected for process observation wait."),
    )

    assertEquals(ExecutionStatus.SUCCESS, firstRead.status)
    assertTrue(firstRead.stdout.contains("sandbox_command_observation_delivery_mode=full_snapshot"))
    assertEquals("full_snapshot", firstRead.metadata["sandboxCommandObservationDeliveryMode"])
    assertEquals("host_seq_1", firstRead.metadata["sandboxCommandObservationCursorAfter"])

    assertEquals(ExecutionStatus.SUCCESS, secondRead.status)
    assertTrue(secondRead.stdout.contains("sandbox_command_observation_delivery_mode=delta"))
    assertTrue(secondRead.stdout.contains("sandbox_command_observation_cursor_before=host_seq_1"))
    assertTrue(secondRead.stdout.contains("sandbox_command_observation_cursor_after=host_seq_2"))
    assertTrue(secondRead.stdout.contains("[stdout]"))
    assertTrue(secondRead.stdout.contains("ready"))
    assertFalse(secondRead.stdout.contains("[stdout]\nbooting"))
    assertEquals("delta", secondRead.metadata["sandboxCommandObservationDeliveryMode"])
    assertEquals("host_seq_1", secondRead.metadata["sandboxCommandObservationCursorBefore"])
    assertEquals("host_seq_2", secondRead.metadata["sandboxCommandObservationCursorAfter"])

    assertEquals(ExecutionStatus.SUCCESS, waitResult.status)
    assertTrue(waitResult.stdout.contains("sandbox_command_observation_delivery_mode=delta"))
    assertTrue(waitResult.stdout.contains("sandbox_command_observation_cursor_before=host_seq_2"))
    assertTrue(waitResult.stdout.contains("sandbox_command_observation_cursor_after=host_seq_3"))
    assertTrue(waitResult.stdout.contains("[stdout]"))
    assertTrue(waitResult.stdout.contains("done"))
    assertFalse(waitResult.stdout.contains("[stdout]\nbooting"))
    assertEquals("delta", waitResult.metadata["sandboxCommandObservationDeliveryMode"])
    assertEquals("host_seq_2", waitResult.metadata["sandboxCommandObservationCursorBefore"])
    assertEquals("host_seq_3", waitResult.metadata["sandboxCommandObservationCursorAfter"])
  }

  @Test
  fun directWaitAgentCanUseDurableSessionHandlesWithoutCheckpointResumeState() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-tool-call-wait-agent").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-call-wait-agent"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    runtimeFactory.subAgentHandleStoreForSession(sessionId).upsert(
      SubAgentHandleState(
        agentId = "child-durable",
        childRunId = "child-run-durable",
        childTaskId = "child-task-durable",
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
      ),
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "tool-call-wait-agent-durable",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-durable"}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for durable wait_agent test.") },
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertTrue(result.stdout.contains("README says hello."))
    assertEquals("child-durable", result.metadata["agentId"])
    assertEquals("child-run-durable", result.metadata["childRunId"])
    assertEquals("completed", result.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
  }

  @Test
  fun detachedControlRecoveryWaitTargetsExactHandleKeyWithoutJsonToolPayload() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-detached-control-wait").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-detached-control-wait"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    runtimeFactory.subAgentHandleStoreForSession(sessionId).upsert(
      SubAgentHandleState(
        agentId = "child-shared",
        childRunId = "child-run-old",
        childTaskId = "child-task-old",
        description = "Old child",
        prompt = "Inspect old task.",
        subagentType = "researcher",
        contextMode = "minimal",
        parentRunId = "run-parent-old",
        parentTaskId = "task-parent-old",
        parentTurn = 1,
        depth = 1,
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.FAILED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "Old child failed.",
        ),
        childExecutionStatus = ExecutionStatus.FAILED.name,
        createdAtEpochMs = 800L,
        updatedAtEpochMs = 900L,
      ),
    )
    runtimeFactory.subAgentHandleStoreForSession(sessionId).upsert(
      SubAgentHandleState(
        agentId = "child-shared",
        childRunId = "child-run-target",
        childTaskId = "child-task-target",
        description = "Target child",
        prompt = "Inspect target task.",
        subagentType = "researcher",
        contextMode = "minimal",
        parentRunId = "run-parent-target",
        parentTaskId = "task-parent-target",
        parentTurn = 1,
        depth = 1,
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.COMPLETED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "Target child completed.",
        ),
        childExecutionStatus = ExecutionStatus.SUCCESS.name,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_100L,
      ),
    )
    val task = AgentTask(
      id = "detached-control-exact-handle",
      type = AgentTaskType.SYSTEM,
      input = "internal:subagent_recovery_wait:child-shared",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_200L,
      metadata = mapOf(
        METADATA_SYNTHETIC_SUBAGENT_TASK_KIND to SYNTHETIC_SUBAGENT_TASK_KIND_RECOVERY_WAIT,
        METADATA_SUBAGENT_RECOVERY_AGENT_ID to "child-shared",
        METADATA_SUBAGENT_RECOVERY_PARENT_RUN_ID to "run-parent-target",
      ),
    )

    val result = requireNotNull(
      runtimeFactory.executeSubAgentRecoveryTask(
        sessionId = sessionId,
        task = task,
        hooks = RuntimeExecutionHooks(
          isCancellationRequested = { false },
          requestRetry = { _ -> error("Retry was not expected for detached control wait test.") },
        ),
        eventSink = NoOpOpenCrayAgentRuntimeEventSink,
        agentId = "child-shared",
        parentRunId = "run-parent-target",
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertTrue(result.stdout.contains("Target child completed."))
    assertEquals("child-shared", result.metadata["agentId"])
    assertEquals("child-run-target", result.metadata["childRunId"])
    assertEquals("completed", result.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
  }

  @Test
  fun prepareSessionContextDoesNotAppendToolCallPayloadAsUserMessage() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-tool-call-context").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-call-context"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val toolPayload =
      """{"type":"tool_call","tool_name":"SkillsFind","arguments":{"query":"android"}}"""

    val prepared = runtimeFactory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-tool-call-context",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.TOOL_CALL,
      taskId = "tool-call-context",
      taskInput = toolPayload,
      transcriptStore = runtimeFactory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
    )

    assertFalse(
      prepared.sessionContext.conversation.any { message ->
        message.role == RuntimeConversationRole.USER && message.content == toolPayload
      },
    )
    assertFalse(
      runtimeFactory.transcriptStoreForSession(sessionId).snapshot().any { message ->
        message.role == RuntimeConversationRole.USER && message.content == toolPayload
      },
    )
  }

  @Test
  fun prepareSessionContextCanSkipAppendingPromptInputDuringApprovalResume() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-prompt-resume-context").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-prompt-resume-context"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendMessage(
      sessionId = sessionId,
      role = com.opencray.persistence.model.ChatTranscriptRole.USER,
      text = "Write the note.",
    )
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val prepared = runtimeFactory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-prompt-resume-context",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "prompt-resume-context",
      taskInput = "Write the note.",
      transcriptStore = runtimeFactory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
      appendTaskInputToTranscript = false,
    )

    assertEquals(
      1,
      prepared.sessionContext.conversation.count { message ->
        message.role == RuntimeConversationRole.USER && message.content == "Write the note."
      },
    )
  }

  @Test
  fun firstPromptRunUsesStructuredChatTurnOnceWhenChatStoreAlreadyContainsSubmittedTurn() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-first-prompt-structured").toPath()
    val chatStore = ChatSessionLocalStore(
      temporaryFolder.newFolder("chat-store-first-prompt-structured"),
    )
    val sessionId = chatStore.loadState().activeSession.sessionId
    Files.createDirectories(workspaceRoot.resolve("uploads"))
    Files.write(
      workspaceRoot.resolve("uploads").resolve("diagram.png"),
      byteArrayOf(1, 2, 3, 4),
    )
    val attachment = ChatAttachmentEntry(
      attachmentId = "attachment-image-1",
      kind = ChatAttachmentKind.IMAGE,
      displayName = "diagram.png",
      localPath = "uploads/diagram.png",
      mimeType = "image/png",
      sizeBytes = 4,
    )
    val pendingMessageId = "assistant-pending-first-prompt"
    val promptUserText = "Describe the attachment."
    chatStore.appendSubmittedTurn(
      sessionId = sessionId,
      userText = promptUserText,
      assistantMessageId = pendingMessageId,
      assistantPlaceholderText = "Thinking",
      attachments = listOf(attachment),
    )
    val providerClient = RecordingLiteRtContextProviderClient(
      result = LiteLlmProviderResult.Failure(
        errorCode = "TEST_STOP",
        errorMessage = "Stop after request capture.",
      ),
    )
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = {
        LlmSettingsState(
          enabled = true,
          providerMode = LlmProviderModes.ON_DEVICE_MODEL,
          providerId = "",
          selectedOnDeviceModelId = OnDeviceLlmCatalog.GEMMA_4_E2B_IT,
        )
      },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      providerClient = providerClient,
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val absoluteAttachmentPath = workspaceRoot
      .resolve("uploads")
      .resolve("diagram.png")
      .toAbsolutePath()
      .normalize()
      .toString()
      .replace('\\', '/')
    val runtimeAttachmentsJson = Json.encodeToString(
      ListSerializer(RuntimeConversationAttachment.serializer()),
      listOf(
        RuntimeConversationAttachment(
          attachmentId = attachment.attachmentId,
          kind = RuntimeConversationAttachmentKind.IMAGE,
          displayName = attachment.displayName,
          filePath = absoluteAttachmentPath,
          mimeType = attachment.mimeType,
        ),
      ),
    )
    val task = AgentTask(
      id = "prompt-first-prompt-structured",
      type = AgentTaskType.PROMPT,
      input = ChatRuntimeTextFormatter.format(
        text = promptUserText,
        commandLabel = null,
        attachments = listOf(attachment),
      ),
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
      metadata = mapOf(
        AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
        AppAgentSessionTaskRuntimeFactory.METADATA_VISIBLE_THROUGH_MESSAGE_ID to pendingMessageId,
        AppAgentSessionTaskRuntimeFactory.METADATA_PROMPT_USER_TEXT to promptUserText,
        AppAgentSessionTaskRuntimeFactory.METADATA_PROMPT_RUNTIME_ATTACHMENTS_JSON to runtimeAttachmentsJson,
      ),
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for request capture test.") },
      ),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("TEST_STOP", result.errorCode)
    val promptMessages = checkNotNull(providerClient.recordedRequest)
      .request
      .messages
      .filter { message ->
        message.role == com.opencray.llm.LiteLlmGatewayMessageRole.USER &&
          message.content == promptUserText
      }
    assertEquals(
      promptMessages.joinToString(separator = "\n---\n") { message ->
        "content=${message.content} attachments=${message.attachments.map { attachment -> "${attachment.attachmentId}:${attachment.displayName}:${attachment.filePath}" }}"
      },
      1,
      promptMessages.size,
    )
    assertEquals(promptUserText, promptMessages.single().content)
    assertEquals(1, promptMessages.single().attachments.size)
    assertEquals("diagram.png", promptMessages.single().attachments.single().displayName)
    assertEquals(absoluteAttachmentPath, promptMessages.single().attachments.single().filePath)

    val transcriptUserMessages = runtimeFactory
      .transcriptStoreForSession(sessionId)
      .snapshot()
      .filter { message -> message.role == RuntimeConversationRole.USER }
    assertEquals(1, transcriptUserMessages.size)
    assertEquals(promptUserText, transcriptUserMessages.single().content)
    assertEquals(1, transcriptUserMessages.single().attachments.size)
    assertEquals("diagram.png", transcriptUserMessages.single().attachments.single().displayName)
  }

  @Test
  fun hostUiPreapprovedToolCallAllowsMatchingToolWithoutChatApproval() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-host-ui-preapproved").toPath()
    val targetFile = workspaceRoot.resolve("delete-me.txt")
    targetFile.toFile().writeText("remove")
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-host-ui-preapproved"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "host-ui-preapproved-delete",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"workspace_delete_file","arguments":{"path":"delete-me.txt"}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
      metadata = mapOf(
        SafetySettingsMetadataKeys.FILE_DELETES_POLICY_ID to ToolPolicyOverride.ASK.wireValue,
        RunLifecycleMetadataKeys.SUBMISSION_SOURCE to RunSubmissionSources.HOST_UI_TOOL_ACTION,
        RunLifecycleMetadataKeys.PREAPPROVED_TOOL_NAME to "workspace_delete_file",
      ),
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for host-ui preapproval test.") },
      ),
    )

    assertEquals(
      "status=${result.status} errorCode=${result.errorCode} errorMessage=${result.errorMessage} stdout=${result.stdout} stderr=${result.stderr}",
      ExecutionStatus.SUCCESS,
      result.status,
    )
    assertFalse(Files.exists(targetFile))
    assertEquals("USER_APPROVED_RETRY", result.metadata["policyReasonCode"])
  }

  @Test
  fun hostUiPreapprovedToolCallRequiresExactToolMatch() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-host-ui-mismatch").toPath()
    val targetFile = workspaceRoot.resolve("delete-me.txt")
    targetFile.toFile().writeText("remove")
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-host-ui-mismatch"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "host-ui-preapproved-delete-mismatch",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"workspace_delete_file","arguments":{"path":"delete-me.txt"}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
      metadata = mapOf(
        SafetySettingsMetadataKeys.FILE_DELETES_POLICY_ID to ToolPolicyOverride.ASK.wireValue,
        RunLifecycleMetadataKeys.SUBMISSION_SOURCE to RunSubmissionSources.HOST_UI_TOOL_ACTION,
        RunLifecycleMetadataKeys.PREAPPROVED_TOOL_NAME to "workspace_write_file",
      ),
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for host-ui mismatch test.") },
      ),
    )

    assertEquals(ExecutionStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertTrue(Files.exists(targetFile))
  }

  @Test
  fun hostUiPreapprovedSkillsAddAllowsNestedApprovalGates() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-host-ui-skills-add").toPath()
    val sourceRoot = temporaryFolder.newFolder("external-skill-source")
    writeSkill(
      root = sourceRoot,
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Installs from an external source.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read ]
      """.trimIndent(),
      body = """
        # Find Skills

        External install fixture.
      """.trimIndent(),
    )
    val packageManager = createSkillPackageManager("skills-add")
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-add"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      safetySettingsProvider = { SafetySettingsState(automationMode = SafetyAutomationMode.SAFE) },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      readRootsProvider = { setOf(workspaceRoot, sourceRoot.toPath()) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      skillPackageManagerProvider = { packageManager },
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val sourceRef = jsonPath(sourceRoot)
    val task = AgentTask(
      id = "host-ui-preapproved-skills-add",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"SkillsAdd","arguments":{"sourceRef":"$sourceRef","skill":"find-skills"}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
      metadata = hostUiPreapprovedTaskMetadata(
        toolName = "SkillsAdd",
        automationMode = SafetyAutomationMode.SAFE,
      ),
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for host-ui skills add test.") },
      ),
    )

    assertTrue(
      "status=${result.status} errorCode=${result.errorCode} errorMessage=${result.errorMessage} stdout=${result.stdout} stderr=${result.stderr}",
      result.status == ExecutionStatus.SUCCESS,
    )
    assertEquals("USER_APPROVED_RETRY", result.metadata["policyReasonCode"])
    assertTrue(Files.exists(packageManager.managedRootPath().toPath().resolve("find-skills").resolve("SKILL.md")))
  }

  @Test
  fun hostUiPreapprovedSkillsFindAllowsNestedNetworkGate() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-host-ui-skills-find").toPath()
    val packageManager = createSkillPackageManager("skills-find")
    writeSkill(
      root = packageManager.catalogRootPath(),
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Search fixture from catalog.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read ]
      """.trimIndent(),
      body = """
        # Find Skills

        Catalog search fixture.
      """.trimIndent(),
    )
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-find"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      safetySettingsProvider = { SafetySettingsState(automationMode = SafetyAutomationMode.SAFE) },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      skillPackageManagerProvider = { packageManager },
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "host-ui-preapproved-skills-find",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"SkillsFind","arguments":{"query":"find","max_results":4}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
      metadata = hostUiPreapprovedTaskMetadata(
        toolName = "SkillsFind",
        automationMode = SafetyAutomationMode.SAFE,
      ),
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for host-ui skills find test.") },
      ),
    )

    assertTrue(
      "status=${result.status} errorCode=${result.errorCode} errorMessage=${result.errorMessage} stdout=${result.stdout} stderr=${result.stderr}",
      result.status == ExecutionStatus.SUCCESS,
    )
    assertTrue(result.stdout.contains("find-skills"))
  }

  @Test
  fun hostUiPreapprovedSkillsInspectAllowsNestedLocalReadGate() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-host-ui-skills-inspect").toPath()
    val sourceRoot = temporaryFolder.newFolder("external-skill-inspect-source")
    writeSkill(
      root = sourceRoot,
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Inspect fixture from external source.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read ]
      """.trimIndent(),
      body = """
        # Find Skills

        Inspect fixture.
      """.trimIndent(),
    )
    val packageManager = createSkillPackageManager("skills-inspect")
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-inspect"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      safetySettingsProvider = { SafetySettingsState(automationMode = SafetyAutomationMode.SAFE) },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      readRootsProvider = { setOf(workspaceRoot, sourceRoot.toPath()) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      skillPackageManagerProvider = { packageManager },
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val sourceRef = jsonPath(sourceRoot)
    val task = AgentTask(
      id = "host-ui-preapproved-skills-inspect",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"SkillsInspect","arguments":{"sourceRef":"$sourceRef"}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
      metadata = hostUiPreapprovedTaskMetadata(
        toolName = "SkillsInspect",
        automationMode = SafetyAutomationMode.SAFE,
      ),
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for host-ui skills inspect test.") },
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertTrue(result.stdout.contains("inspection"))
    assertTrue(result.stdout.contains("find-skills"))
  }

  @Test
  fun hostUiPreapprovedSkillsUpdateAllowsNestedApprovalGates() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-host-ui-skills-update").toPath()
    val sourceRoot = temporaryFolder.newFolder("external-skill-update-source")
    writeSkill(
      root = sourceRoot,
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Updates from an external source.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read ]
      """.trimIndent(),
      body = """
        # Find Skills

        External update fixture.
      """.trimIndent(),
    )
    val packageManager = createSkillPackageManager("skills-update")
    val sourceReport = SkillLoader.load(sourceRoot)
    assertEquals(
      "invalid=${sourceReport.invalidSkills.map { diagnostic -> diagnostic.detail }}",
      listOf("find-skills"),
      sourceReport.loadedSkills.map { skill -> skill.name },
    )
    val installAttempt = packageManager.installFromLocalSource(
      sourcePath = sourceRoot,
      sourceRef = sourceRoot.invariantSeparatorsPath,
      selectedSkillName = "find-skills",
    )
    assertNotNull("errorCode=${installAttempt.errorCode} errorMessage=${installAttempt.errorMessage}", installAttempt.result)

    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-update"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      safetySettingsProvider = { SafetySettingsState(automationMode = SafetyAutomationMode.SAFE) },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      readRootsProvider = { setOf(workspaceRoot, sourceRoot.toPath()) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      skillPackageManagerProvider = { packageManager },
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "host-ui-preapproved-skills-update",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"SkillsUpdate","arguments":{"skillId":"find-skills"}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
      metadata = hostUiPreapprovedTaskMetadata(
        toolName = "SkillsUpdate",
        automationMode = SafetyAutomationMode.SAFE,
      ),
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for host-ui skills update test.") },
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertTrue(result.stdout.contains("find-skills"))
  }

  private fun createSkillPackageManager(name: String): SkillPackageManager = SkillPackageManager(
    managedRoot = temporaryFolder.newFolder("$name-managed"),
    catalogRoot = temporaryFolder.newFolder("$name-catalog"),
    manifestStore = SkillInstallManifestStore.fromFile(
      temporaryFolder.newFile("$name-manifest.json"),
    ),
  )

  private fun writeSkill(
    root: File,
    relativeDirectory: String,
    frontMatter: String,
    body: String,
  ): File {
    val skillDirectory = root.resolve(relativeDirectory)
    Files.createDirectories(skillDirectory.toPath())
    val skillFile = skillDirectory.resolve("SKILL.md")
    val content = buildString {
      appendLine("---")
      appendLine(frontMatter)
      appendLine("---")
      appendLine(body)
    }
    Files.write(skillFile.toPath(), content.toByteArray(StandardCharsets.UTF_8))
    return skillFile
  }

  private fun jsonPath(file: File): String = file.invariantSeparatorsPath.replace("/", "\\/")

  private fun developerToolCallTask(
    id: String,
    toolName: String,
    argumentsJson: String,
  ): AgentTask = AgentTask(
    id = id,
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call","tool_name":"$toolName","arguments":$argumentsJson}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    metadata = mapOf("chatMode" to "DEVELOPER"),
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(retryError: String): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _ -> error(retryError) },
  )

  private fun hostUiPreapprovedTaskMetadata(
    toolName: String,
    automationMode: SafetyAutomationMode? = null,
  ): Map<String, String> = buildMap {
    put(RunLifecycleMetadataKeys.SUBMISSION_SOURCE, RunSubmissionSources.HOST_UI_TOOL_ACTION)
    put(RunLifecycleMetadataKeys.PREAPPROVED_TOOL_NAME, toolName)
    automationMode?.let { mode ->
      put(SafetySettingsMetadataKeys.CHAT_MODE, mode.chatMetadataLabel)
      put(SafetySettingsMetadataKeys.EXECUTION_MODE, mode.executionMode.name)
    }
  }

  private class RecordingScheduledTriggerRegistrar : ScheduledTriggerRegistrar {
    val syncedScheduleIds = mutableListOf<String>()

    override fun syncSpec(spec: ScheduledTaskSpec) = Unit

    override fun syncAll(specs: List<ScheduledTaskSpec>) {
      syncedScheduleIds += specs.map(ScheduledTaskSpec::scheduleId)
    }

    override fun cancel(scheduleId: String) = Unit
  }

  private class RecordingLiteRtContextProviderClient(
    private val result: LiteLlmProviderResult,
  ) : LiteLlmProviderClient {
    var requestCount: Int = 0
      private set
    var recordedRequest: LiteLlmProviderRequest? = null
      private set
    var recordedAutomaticToolExecutionContext: LiteRtAutomaticToolExecutionContext? = null
      private set

    override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
      requestCount += 1
      recordedRequest = request
      recordedAutomaticToolExecutionContext = LiteRtAutomaticToolExecutionRegistry.current()
      return result
    }
  }

  private data class ObservationSnapshotPlan(
    val status: ManagedProcessStatus,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val finishedAtEpochMs: Long?,
    val metadata: Map<String, String>,
  ) {
    fun toSnapshot(
      request: ManagedProcessStartRequest,
      updatedAtEpochMs: Long,
    ): ManagedProcessSnapshot = ManagedProcessSnapshot(
      processId = request.processId,
      taskId = request.taskId,
      command = request.command,
      args = request.args,
      workingDirectory = request.workingDirectory,
      status = status,
      processStarted = true,
      timeoutMs = request.timeoutMs,
      stdout = stdout,
      stderr = stderr,
      exitCode = exitCode,
      startedAtEpochMs = 1_000L,
      updatedAtEpochMs = updatedAtEpochMs,
      finishedAtEpochMs = finishedAtEpochMs,
      metadata = request.metadata + metadata,
    )

    fun advance(
      existing: ManagedProcessSnapshot,
      updatedAtEpochMs: Long,
    ): ManagedProcessSnapshot = existing.copy(
      status = status,
      stdout = stdout,
      stderr = stderr,
      exitCode = exitCode,
      updatedAtEpochMs = updatedAtEpochMs,
      finishedAtEpochMs = finishedAtEpochMs,
      metadata = existing.metadata + metadata,
    )
  }

  private class SequencedObservationProcessRegistry(
    private val startedPlan: ObservationSnapshotPlan,
    private val readPlans: MutableList<ObservationSnapshotPlan> = mutableListOf(),
    private val waitPlans: MutableList<ObservationSnapshotPlan> = mutableListOf(),
  ) : AgentProcessRegistry {
    private val snapshotsById = linkedMapOf<String, ManagedProcessSnapshot>()
    private var updatedAtEpochMs: Long = 1_000L

    override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
      val snapshot = startedPlan.toSnapshot(
        request = request,
        updatedAtEpochMs = updatedAtEpochMs,
      )
      snapshotsById[request.processId] = snapshot
      return snapshot
    }

    override fun list(): List<ManagedProcessSnapshot> = snapshotsById.values.toList()

    override fun read(processId: String): ManagedProcessSnapshot? {
      val current = snapshotsById[processId] ?: return null
      readPlans.removeFirstOrNull()?.let { nextPlan ->
        updatedAtEpochMs += 100L
        snapshotsById[processId] = nextPlan.advance(
          existing = current,
          updatedAtEpochMs = updatedAtEpochMs,
        )
      }
      return current
    }

    override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? {
      val current = snapshotsById[processId] ?: return null
      val nextPlan = waitPlans.removeFirstOrNull() ?: return current
      updatedAtEpochMs += timeoutMs.coerceAtLeast(1L)
      val waited = nextPlan.advance(
        existing = current,
        updatedAtEpochMs = updatedAtEpochMs,
      )
      snapshotsById[processId] = waited
      return waited
    }

    override fun terminate(processId: String): ManagedProcessSnapshot? {
      val existing = snapshotsById[processId] ?: return null
      val terminated = existing.copy(
        status = ManagedProcessStatus.CANCELLED,
        exitCode = 137,
        errorCode = "CANCELLED",
        errorMessage = "Managed process terminated.",
        updatedAtEpochMs = existing.updatedAtEpochMs + 1L,
        finishedAtEpochMs = existing.updatedAtEpochMs + 1L,
        cancelled = true,
      )
      snapshotsById[processId] = terminated
      return terminated
    }
  }

  private fun observationPlan(
    status: ManagedProcessStatus = ManagedProcessStatus.RUNNING,
    stdout: String = "",
    stderr: String = "",
    cursor: String,
    exitCode: Int? = null,
    finishedAtEpochMs: Long? = null,
  ): ObservationSnapshotPlan = ObservationSnapshotPlan(
    status = status,
    stdout = stdout,
    stderr = stderr,
    exitCode = exitCode,
    finishedAtEpochMs = finishedAtEpochMs,
    metadata = mapOf(
      "sandboxCommandObservationMode" to "host_managed_snapshot",
      "sandboxCommandObservationCursor" to cursor,
      "sandboxCommandObservationStdoutBytes" to
        stdout.toByteArray(StandardCharsets.UTF_8).size.toString(),
      "sandboxCommandObservationStderrBytes" to
        stderr.toByteArray(StandardCharsets.UTF_8).size.toString(),
    ),
  )
}
