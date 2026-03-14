package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SessionTaskRuntime
import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.ModelProfile
import com.opencray.llm.ProviderRoute
import com.opencray.llm.ProviderRouting
import com.opencray.mcp.McpClientExposureReport
import com.opencray.runtime.AgentTodoStore
import com.opencray.runtime.InMemoryAgentTodoStore
import com.opencray.runtime.OpenCrayAgentRuntime
import com.opencray.runtime.OpenCrayAgentRuntimeConfig
import com.opencray.runtime.OpenCrayAgentRuntimeEventSink
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.OpenCrayToolDispatcherConfig
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

internal class AppAgentSessionTaskRuntimeFactory(
  private val llmSettingsProvider: () -> LlmSettingsState,
  private val sessionContextFactory: ChatRuntimeSessionContextFactory,
  private val soulProfileProvider: () -> PersonalizationLocalStore.SoulProfile?,
  private val workspaceRootsProvider: () -> Set<Path>,
  private val skillsRootsProvider: () -> List<File>,
  private val mcpReportProvider: () -> McpClientExposureReport?,
) : AgentSessionTaskRuntimeFactory {
  private val todoStoresBySession: ConcurrentMap<String, AgentTodoStore> = ConcurrentHashMap()

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
        providerClient = OpenAiCompatibleLiteLlmProviderClient(),
      ),
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = workspaceRootsProvider(),
          skillsRoots = skillsRootsProvider(),
          mcpExposureReport = mcpReportProvider(),
          todoStore = todoStoreForSession(sessionId),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        systemPrompt = llmSettings.systemPrompt.ifBlank {
          OpenCrayAgentRuntimeConfig.DEFAULT_OPENCRAY_SYSTEM_PROMPT
        },
        sessionContext = sessionContextFactory.create(
          sessionId = sessionId,
          visibleThroughMessageId = visibleThroughMessageId.takeIf(String::isNotBlank),
          excludedMessageIds = pendingMessageId.takeIf(String::isNotBlank)?.let(::setOf).orEmpty(),
          soulProfile = soulProfileProvider(),
        ),
        llmMetadata = task.metadata.filterKeys(::isLlmVisibleMetadataKey) + mapOf("sessionId" to sessionId),
        llmAuthHeaders = LlmProviderProtocols.authHeaders(
          protocol = llmSettings.protocol,
          apiKey = llmSettings.apiKey,
        ),
      ),
      eventSink = eventSink,
    )
    return runtime.execute(task, hooks)
  }

  internal fun todoStoreForSession(sessionId: String): AgentTodoStore =
    todoStoresBySession.computeIfAbsent(sessionId) { InMemoryAgentTodoStore() }

  companion object {
    const val ERROR_CODE_MISSING_LLM_CONFIG: String = "MISSING_LLM_CONFIG"
    const val METADATA_HOST_PREFIX: String = "_host."
    const val METADATA_HOST_SESSION_ID: String = "${METADATA_HOST_PREFIX}sessionId"
    const val METADATA_PENDING_MESSAGE_ID: String = "${METADATA_HOST_PREFIX}pendingMessageId"
    const val METADATA_VISIBLE_THROUGH_MESSAGE_ID: String = "${METADATA_HOST_PREFIX}visibleThroughMessageId"

    fun isLlmVisibleMetadataKey(key: String): Boolean = !key.startsWith(METADATA_HOST_PREFIX)
  }
}
