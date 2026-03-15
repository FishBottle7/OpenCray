package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.ModelProfile
import com.opencray.llm.ProviderRoute
import com.opencray.llm.ProviderRouting
import com.opencray.mcp.McpClientExposureReport
import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.AgentTodoStore
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.InMemoryAgentTodoStore
import com.opencray.runtime.OpenCrayAgentRuntime
import com.opencray.runtime.OpenCrayAgentRuntimeConfig
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.OpenCrayToolDispatcherConfig
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.memory.MemoryRecallRequest
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.memory.MemoryRetriever
import com.opencray.runtime.session.InMemorySessionTranscriptStore
import com.opencray.runtime.session.SessionTranscriptStore
import com.opencray.runtime.soul.MemoryBackedSoulProfileResolver
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class AppAgentSessionTaskRuntimeFactory(
  private val llmSettingsProvider: () -> LlmSettingsState,
  private val sessionContextFactory: ChatRuntimeSessionContextFactory,
  private val soulProfileProvider: () -> PersonalizationLocalStore.SoulProfile?,
  private val workspaceRootsProvider: () -> Set<Path>,
  private val skillsRootsProvider: () -> List<File>,
  private val mcpReportProvider: () -> McpClientExposureReport?,
  private val memoryRecordsProvider: () -> List<MemoryRecord> = { emptyList() },
  private val providerUserAgent: String = OpenCrayUserAgent.providerApi("0"),
  private val approvalRegistry: AgentTaskApprovalRegistry = AgentTaskApprovalRegistry(),
  private val transcriptStoreProvider: (String) -> SessionTranscriptStore = { InMemorySessionTranscriptStore() },
) : AgentSessionTaskRuntimeFactory {
  private val todoStoresBySession: ConcurrentMap<String, AgentTodoStore> = ConcurrentHashMap()
  private val transcriptStoresBySession: ConcurrentMap<String, SessionTranscriptStore> = ConcurrentHashMap()
  private val memoryRetriever: MemoryRetriever = MemoryRetriever()
  private val memoryBackedSoulResolver: MemoryBackedSoulProfileResolver = MemoryBackedSoulProfileResolver()
  private val replayJson: Json = Json { prettyPrint = false }

  override fun create(
    sessionId: String,
    eventSink: OpenCrayAgentRuntimeEventSink,
  ): SessionTaskRuntime = SessionTaskRuntime { task, hooks ->
    executeTask(
      sessionId = sessionId,
      task = task,
      hooks = hooks,
      eventSink = eventSink,
    )
  }

  private fun executeTask(
    sessionId: String,
    task: AgentTask,
    hooks: RuntimeExecutionHooks,
    eventSink: OpenCrayAgentRuntimeEventSink,
  ): ExecutionResult {
    val llmSettings = llmSettingsProvider().sanitized()
    if (!llmSettings.isConfigured()) {
      return ExecutionResult(
        taskId = task.id,
        status = ExecutionStatus.FAILED,
        errorCode = ERROR_CODE_MISSING_LLM_CONFIG,
        errorMessage = "LLM configuration is incomplete.",
        startedAtEpochMs = System.currentTimeMillis(),
        finishedAtEpochMs = System.currentTimeMillis(),
        metadata = task.metadata,
      )
    }

    val route = ProviderRoute(
      id = "route-${llmSettings.providerId.ifBlank { "openai-compatible" }}",
      providerId = llmSettings.providerId.ifBlank { "openai-compatible" },
      baseUrl = llmSettings.baseUrl,
      model = llmSettings.model,
      metadata = LlmProviderProtocols.routeMetadata(
        protocol = llmSettings.protocol,
        model = llmSettings.model,
        reasoningEffort = llmSettings.reasoningEffort,
      ),
    )
    val pendingMessageId = task.metadata[METADATA_PENDING_MESSAGE_ID].orEmpty()
    val visibleThroughMessageId = task.metadata[METADATA_VISIBLE_THROUGH_MESSAGE_ID].orEmpty()
    val approvalGrant = approvalRegistry.consumeApproved(sessionId, task.id)
    val transcriptStore = transcriptStoreForSession(sessionId)
    val memoryRecords = memoryRecordsProvider()
    val sessionContext = createSessionContext(
      sessionId = sessionId,
      visibleThroughMessageId = visibleThroughMessageId.takeIf(String::isNotBlank),
      excludedMessageIds = pendingMessageId.takeIf(String::isNotBlank)?.let(::setOf).orEmpty(),
      soulProfile = soulProfileProvider(),
      taskInput = task.input,
      transcriptStore = transcriptStore,
      memoryRecords = memoryRecords,
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = DefaultLiteLlmGateway(
        routingStore = InMemoryLiteLlmRoutingSettingsStore(
          ProviderRouting(
            activeProfileId = "profile-default",
            profiles = listOf(
              ModelProfile(
                id = "profile-default",
                displayName = "Default",
                primaryRouteId = route.id,
                routes = listOf(route),
              ),
            ),
          ),
        ),
        providerClient = OpenAiCompatibleLiteLlmProviderClient(
          userAgent = providerUserAgent,
        ),
      ),
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = workspaceRootsProvider(),
          skillsRoots = skillsRootsProvider(),
          mcpExposureReport = mcpReportProvider(),
          approvedTaskId = task.id.takeIf { approvalGrant != null },
          approvedToolName = approvalGrant?.toolName,
          todoStore = todoStoreForSession(sessionId),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        systemPrompt = llmSettings.systemPrompt.ifBlank {
          OpenCrayAgentRuntimeConfig.DEFAULT_OPENCRAY_SYSTEM_PROMPT
        },
        sessionContext = sessionContext,
        llmMetadata = task.metadata.filterKeys(::isLlmVisibleMetadataKey) + mapOf("sessionId" to sessionId),
        llmAuthHeaders = LlmProviderProtocols.authHeaders(
          protocol = llmSettings.protocol,
          apiKey = llmSettings.apiKey,
        ),
      ),
      eventSink = transcriptAwareEventSink(
        sessionId = sessionId,
        transcriptStore = transcriptStore,
        delegate = eventSink,
      ),
    )
    val result = runtime.execute(task, hooks)
    recordSuccessfulAssistantTurn(
      sessionId = sessionId,
      task = task,
      result = result,
    )
    return result
  }

  internal fun todoStoreForSession(sessionId: String): AgentTodoStore =
    todoStoresBySession.computeIfAbsent(sessionId) { InMemoryAgentTodoStore() }

  internal fun transcriptStoreForSession(sessionId: String): SessionTranscriptStore =
    transcriptStoresBySession.computeIfAbsent(sessionId, transcriptStoreProvider)

  internal fun recordSuccessfulToolInteraction(
    sessionId: String,
    event: OpenCrayToolResultEvent,
  ) {
    if (event.result.status != AgentToolResultStatus.SUCCESS) {
      return
    }
    recordSuccessfulToolInteraction(
      transcriptStore = transcriptStoreForSession(sessionId),
      event = event,
    )
  }

  internal fun recordApprovalRejection(
    sessionId: String,
    toolName: String?,
    isHighRisk: Boolean,
  ) {
    transcriptStoreForSession(sessionId).appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = buildString {
          append("approval_rejected")
          toolName
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { resolvedToolName ->
              append(" tool_name=")
              append(resolvedToolName)
            }
          append(" outcome=user_rejected")
          append(" executed=false")
          if (isHighRisk) {
            append(" risk=high_risk")
          }
          append(" next_step=await_user_instruction")
        },
      ),
    )
  }

  internal fun recordRunCancellation(
    sessionId: String,
    taskId: String,
    runId: String,
    toolName: String? = null,
  ) {
    transcriptStoreForSession(sessionId).appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.TOOL,
        content = buildString {
          append("run_cancelled")
          append(" task_id=")
          append(taskId)
          append(" run_id=")
          append(runId)
          toolName
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { resolvedToolName ->
              append(" tool_name=")
              append(resolvedToolName)
              append(" executed=false")
            }
          append(" outcome=user_cancelled")
          append(" next_step=await_user_instruction")
        },
      ),
    )
  }

  internal fun repairTerminalReplayFromRunSnapshots(
    sessionId: String,
    runs: List<AgentRunSnapshot>,
  ) {
    val store = transcriptStoreForSession(sessionId)
    runs
      .sortedBy(AgentRunSnapshot::acceptedAtEpochMs)
      .forEach { run ->
        val observation = terminalReplayObservationFor(
          existingMessages = store.snapshot(),
          run = run,
        ) ?: return@forEach
        store.appendIfDistinct(observation)
      }
  }

  internal fun recalledMemoryFor(
    sessionId: String,
    taskInput: String,
    memoryRecords: List<MemoryRecord> = memoryRecordsProvider(),
  ): MemoryRecallResult = memoryRetriever.retrieve(
    records = memoryRecords,
    request = MemoryRecallRequest(
      sessionId = sessionId,
      userInput = taskInput,
      workspaceId = AppWorkspaceIdentity.fromRoots(workspaceRootsProvider()),
    ),
  )

  internal fun effectiveSoulProfileFor(
    sessionId: String,
    soulProfile: PersonalizationLocalStore.SoulProfile?,
    memoryRecords: List<MemoryRecord> = memoryRecordsProvider(),
  ) = memoryBackedSoulResolver.overlay(
    baseProfile = sessionContextFactory.create(
      sessionId = sessionId,
      soulProfile = soulProfile,
    ).soulProfile,
    records = memoryRecords,
    sessionId = sessionId,
    workspaceId = AppWorkspaceIdentity.fromRoots(workspaceRootsProvider()),
  )

  private fun createSessionContext(
    sessionId: String,
    visibleThroughMessageId: String?,
    excludedMessageIds: Set<String>,
    soulProfile: PersonalizationLocalStore.SoulProfile?,
    taskInput: String,
    transcriptStore: SessionTranscriptStore,
    memoryRecords: List<MemoryRecord>,
  ): AgentRuntimeSessionContext {
    val baseContext = sessionContextFactory.create(
      sessionId = sessionId,
      visibleThroughMessageId = visibleThroughMessageId,
      excludedMessageIds = excludedMessageIds,
      soulProfile = soulProfile,
    )
    transcriptStore.seedIfEmpty(baseContext.conversation)
    taskInput.trim()
      .takeIf(String::isNotBlank)
      ?.let { normalizedInput ->
        transcriptStore.appendIfDistinct(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.USER,
            content = normalizedInput,
          ),
        )
      }
    return baseContext.copy(
      soulProfile = memoryBackedSoulResolver.overlay(
        baseProfile = baseContext.soulProfile,
        records = memoryRecords,
        sessionId = sessionId,
        workspaceId = AppWorkspaceIdentity.fromRoots(workspaceRootsProvider()),
      ),
      recalledMemory = recalledMemoryFor(
        sessionId = sessionId,
        taskInput = taskInput,
        memoryRecords = memoryRecords,
      ),
      conversation = transcriptStore.snapshot(),
    )
  }

  private fun transcriptAwareEventSink(
    sessionId: String,
    transcriptStore: SessionTranscriptStore,
    delegate: OpenCrayAgentRuntimeEventSink,
  ): OpenCrayAgentRuntimeEventSink = object : OpenCrayAgentRuntimeEventSink {
    override fun onRunEvent(task: AgentTask, event: OpenCrayAgentRunEvent) {
      if (event is OpenCrayToolResultEvent && event.result.status == AgentToolResultStatus.SUCCESS) {
        recordSuccessfulToolInteraction(
          transcriptStore = transcriptStore,
          event = event,
        )
      }
      delegate.onRunEvent(task, event)
    }
  }

  private fun recordSuccessfulAssistantTurn(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ) {
    if (task.type != com.opencray.core.contracts.AgentTaskType.PROMPT) {
      return
    }
    if (result.status != ExecutionStatus.SUCCESS) {
      return
    }
    result.stdout
      .trim()
      .takeIf(String::isNotBlank)
      ?.let { assistantText ->
        transcriptStoreForSession(sessionId).appendIfDistinct(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.ASSISTANT,
            content = assistantText,
          ),
        )
      }
  }

  private fun recordSuccessfulToolInteraction(
    transcriptStore: SessionTranscriptStore,
    event: OpenCrayToolResultEvent,
  ) {
    val callObservation = RuntimeConversationMessage(
      role = RuntimeConversationRole.TOOL,
      content = buildToolCallReplayContent(event),
    )
    val resultObservation = RuntimeConversationMessage(
      role = RuntimeConversationRole.TOOL,
      content = buildToolResultReplayContent(event),
    )
    appendIfMissing(
      transcriptStore = transcriptStore,
      message = callObservation,
    )
    appendIfMissing(
      transcriptStore = transcriptStore,
      message = resultObservation,
    )
  }

  private fun appendIfMissing(
    transcriptStore: SessionTranscriptStore,
    message: RuntimeConversationMessage,
  ) {
    val existingContents = transcriptStore.snapshot().mapTo(linkedSetOf(), RuntimeConversationMessage::content)
    if (message.content in existingContents) {
      return
    }
    transcriptStore.appendIfDistinct(message)
  }

  private fun buildToolCallReplayContent(event: OpenCrayToolResultEvent): String =
    "tool_call ${encodeReplayJsonObject {
      put("run_id", event.runId)
      put("task_id", event.taskId)
      put("turn", event.turn)
      put("tool_name", event.call.toolName)
      event.call.reason
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { reason ->
          put("reason", collapseReplayWhitespace(reason))
        }
      put("arguments", event.call.arguments)
    }}"

  private fun buildToolResultReplayContent(event: OpenCrayToolResultEvent): String =
    "tool_result ${encodeReplayJsonObject {
      put("run_id", event.runId)
      put("task_id", event.taskId)
      put("turn", event.turn)
      put("tool_name", event.result.toolName)
      put("status", event.result.status.name.lowercase())
      put("content_preview", previewForReplay(event.result.content))
      if (event.result.metadata.isNotEmpty()) {
        put(
          "metadata",
          buildJsonObject {
            event.result.metadata.toSortedMap().forEach { (key, value) ->
              put(key, value)
            }
          },
        )
      }
    }}"

  private fun encodeReplayJsonObject(builder: JsonObjectBuilder.() -> Unit): String =
    replayJson.encodeToString(
      serializer = JsonObject.serializer(),
      value = buildJsonObject(builder),
    )

  private fun previewForReplay(content: String): String =
    collapseReplayWhitespace(content).let { normalized ->
      if (normalized.length <= TOOL_REPLAY_PREVIEW_LIMIT_CHARS) {
        normalized
      } else {
        normalized.take(TOOL_REPLAY_PREVIEW_LIMIT_CHARS - 1).trimEnd() + "…"
      }
    }

  private fun collapseReplayWhitespace(content: String): String =
    content.replace(Regex("\\s+"), " ").trim()

  private fun terminalReplayObservationFor(
    existingMessages: List<RuntimeConversationMessage>,
    run: AgentRunSnapshot,
  ): RuntimeConversationMessage? {
    if (!run.isTerminal || hasTerminalReplayObservation(existingMessages, run)) {
      return null
    }
    return when {
      run.lifecycleState == QueueTaskLifecycleState.CANCELLED ||
        run.executionStatus == ExecutionStatus.CANCELLED -> RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = buildTerminalReplayContent(
            prefix = "run_cancelled",
            run = run,
            outcome = "restored_terminal_state",
          ),
        )

      run.lifecycleState == QueueTaskLifecycleState.FAILED ||
        run.executionStatus == ExecutionStatus.FAILED -> RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = buildTerminalReplayContent(
            prefix = if (run.attempt > 1) "retry_abandoned" else "run_interrupted",
            run = run,
            outcome = if (run.attempt > 1) "retry_budget_exhausted" else "terminal_failure",
          ),
        )

      else -> null
    }
  }

  private fun buildTerminalReplayContent(
    prefix: String,
    run: AgentRunSnapshot,
    outcome: String,
  ): String = buildString {
    append(prefix)
    append(" task_id=")
    append(run.taskId)
    append(" run_id=")
    append(run.runId)
    append(" outcome=")
    append(outcome)
    if (run.attempt > 0) {
      append(" attempt=")
      append(run.attempt)
    }
    run.errorCode
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { resolvedErrorCode ->
        append(" error_code=")
        append(resolvedErrorCode)
      }
    append(" next_step=await_user_instruction")
  }

  private fun hasTerminalReplayObservation(
    existingMessages: List<RuntimeConversationMessage>,
    run: AgentRunSnapshot,
  ): Boolean = existingMessages.any { message ->
    message.role == RuntimeConversationRole.TOOL &&
      message.content.contains("task_id=${run.taskId}") &&
      message.content.contains("run_id=${run.runId}") &&
      (
        message.content.startsWith("run_cancelled") ||
          message.content.startsWith("run_interrupted") ||
          message.content.startsWith("retry_abandoned")
        )
  }

  companion object {
    const val ERROR_CODE_MISSING_LLM_CONFIG: String = "MISSING_LLM_CONFIG"
    const val METADATA_HOST_PREFIX: String = "_host."
    const val METADATA_RUN_ID: String = "${METADATA_HOST_PREFIX}runId"
    const val METADATA_HOST_SESSION_ID: String = "${METADATA_HOST_PREFIX}sessionId"
    const val METADATA_PENDING_MESSAGE_ID: String = "${METADATA_HOST_PREFIX}pendingMessageId"
    const val METADATA_VISIBLE_THROUGH_MESSAGE_ID: String = "${METADATA_HOST_PREFIX}visibleThroughMessageId"
    private const val TOOL_REPLAY_PREVIEW_LIMIT_CHARS: Int = 240

    fun isLlmVisibleMetadataKey(key: String): Boolean = !key.startsWith(METADATA_HOST_PREFIX)
  }
}
