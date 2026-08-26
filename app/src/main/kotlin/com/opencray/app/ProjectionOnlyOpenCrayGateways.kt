package com.opencray.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LocalLlmConfigFacade
import com.opencray.app.facade.mcp.LocalMcpSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.media.LocalMediaSpeechSettingsFacade
import com.opencray.app.facade.media.MediaSpeechSettingsFacade
import com.opencray.app.facade.notifications.LocalNotificationSettingsFacade
import com.opencray.app.facade.notifications.NotificationSettingsFacade
import com.opencray.app.facade.personalization.LocalPersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationConfigSnapshot
import com.opencray.app.facade.personalization.PersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot
import com.opencray.app.facade.personalization.PersonalizationPresetSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot
import com.opencray.app.facade.safety.LocalSafetySettingsFacade
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.app.facade.safety.SafetySettingsLocationSnapshot
import com.opencray.app.facade.safety.SafetySettingsSnapshot
import com.opencray.app.facade.search.LocalNetworkSearchConfigFacade
import com.opencray.app.facade.search.NetworkSearchConfigFacade
import com.opencray.app.facade.settings.LocalSettingsFacade
import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsRouteId
import com.opencray.app.facade.settings.SettingsRowSnapshot
import com.opencray.app.facade.settings.SettingsSectionSnapshot
import com.opencray.app.facade.skills.LocalSkillsFacade
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.app.shell.AppShellDestination
import com.opencray.app.shell.AppShellStateStore
import java.util.Timer
import java.util.TimerTask
import org.opencray.app.R

internal class ProjectionOnlyOpenCrayShellGateway(
  private val stateStore: AppShellStateStore,
  private val localeTagProvider: () -> String,
  private val hostLabel: String,
  private val hostSummary: String,
  private val connectionStateProvider: () -> RuntimeServiceConnectionState?,
  private val projectionSnapshotProvider: () -> RuntimeServiceProjectionSnapshot? = { null },
  private val localRuntimeServerStateProvider: () -> LocalRuntimeServerState? = { null },
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
  private val hostLifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  private val pollIntervalMs: Long = DEFAULT_PROJECTION_SHELL_POLL_INTERVAL_MS,
) : OpenCrayShellGateway {
  override fun loadShellSnapshot(): Map<String, Any?> = buildMap {
    val projectionSnapshot = projectionSnapshotProvider()
    val destination = stateStore.load()
    put("initialTab", destination.selectedTab.routeKey)
    put("settingsSubpage", destination.settingsSubpage.routeKey)
    put("localeTag", localeTagProvider())
    put("hostLabel", hostLabel)
    put("hostSummary", hostSummary)
    put("isHostConnected", true)
    putRuntimeServiceDiagnosticsSnapshot(
      localRuntimeServerState = projectionSnapshot?.localRuntimeServerState
        ?: localRuntimeServerStateProvider(),
      hostLifecycle = hostLifecycleDescriptor,
      runtimeControllerLifecycle = projectionSnapshot?.runtimeControllerLifecycle,
      runtimeOwnerLifecycle = projectionSnapshot?.runtimeOwnerLifecycle,
      runtimeOwnerWorkSummary = projectionSnapshot?.runtimeOwnerWorkSummary,
      runtimeServiceLifecycle = projectionSnapshot?.serviceLifecycle,
      runtimeServiceWorkState = projectionSnapshot?.serviceWorkState,
      runtimeServiceKeepAliveState = projectionSnapshot?.serviceKeepAliveState,
      runtimeServiceOwnerLease = projectionSnapshot?.runtimeServiceOwnerLease,
      runtimeServiceInterruptedRunRepair = projectionSnapshot?.lastInterruptedRunRepair,
      runtimeServiceConnectionState = connectionStateProvider(),
    )
  }

  override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeProjectionWithPollingSnapshot(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = ::loadShellSnapshot,
      listener = listener,
      pollIntervalMs = pollIntervalMs,
    )

  override fun saveShellDestination(
    selectedTab: String,
    settingsSubpage: String?,
  ) {
    stateStore.save(
      AppShellDestination.fromRaw(
        selectedTabRaw = selectedTab,
        settingsSubpageRaw = settingsSubpage,
      ),
    )
  }
}

internal class ProjectionOnlyOpenCraySettingsGateway(
  private val settingsFacade: SettingsFacade,
  private val notificationSettingsFacade: NotificationSettingsFacade,
  private val scheduledTaskManager: AppScheduledTaskManager,
  private val networkSearchConfigFacade: NetworkSearchConfigFacade,
  private val mediaSpeechSettingsFacade: MediaSpeechSettingsFacade,
  private val sandboxSettingsRepository: SandboxSettingsRepository,
  private val llmConfigFacade: LlmConfigFacade,
  private val personalizationFacade: PersonalizationFacade,
  private val mcpSettingsFacade: McpSettingsFacade,
  private val safetySettingsFacade: SafetySettingsFacade,
  private val strongBackgroundSettingsAccess: StrongBackgroundSettingsAccess,
  private val localeTagProvider: () -> String,
  private val connectionStateProvider: () -> RuntimeServiceConnectionState,
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
) : OpenCraySettingsGateway {
  override fun loadSettingsOverview(): Map<String, Any?> =
    settingsFacade.loadOverview().toGatewayMap()

  override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeProjectionWithInitial(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = ::loadSettingsOverview,
      listener = listener,
    )

  override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> {
    val routeId = SettingsRouteId.fromWireValue(routeIdRaw) ?: SettingsRouteId.WORKSPACE_ACCESS
    return settingsFacade.loadDetail(routeId).toGatewayMap()
  }

  override fun loadNotificationSettings(): Map<String, Any?> =
    notificationSettingsFacade.load().toGatewayMap()

  override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> =
    throw writeUnavailable("saveNotificationSettings")

  override fun loadScheduledTasks(): Map<String, Any?> =
    scheduledTaskManager.loadScheduledTasksGatewayMap()

  override fun loadScheduledTask(scheduleId: String): Map<String, Any?> =
    scheduledTaskManager.loadScheduledTaskGatewayMap(scheduleId)

  override fun updateScheduledTaskEnabled(
    scheduleId: String,
    enabled: Boolean,
  ): Map<String, Any?> = throw writeUnavailable("updateScheduledTaskEnabled")

  override fun runScheduledTaskNow(scheduleId: String): Map<String, Any?> =
    throw writeUnavailable("runScheduledTaskNow")

  override fun snoozeScheduledTask(
    scheduleId: String,
    durationMinutes: Int,
  ): Map<String, Any?> = throw writeUnavailable("snoozeScheduledTask")

  override fun loadStrongBackgroundSnapshot(): Map<String, Any?> = buildMap {
    putAll(strongBackgroundSettingsAccess.loadSnapshot())
    put("runtimeServiceConnectionState", connectionStateProvider().snapshotMap())
  }

  override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> =
    strongBackgroundSettingsAccess.performAction(actionId)

  override fun loadNetworkSearchConfig(): Map<String, Any?> =
    networkSearchConfigFacade.load().toGatewayMap()

  override fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?> =
    throw writeUnavailable("saveNetworkSearchConfig")

  override fun loadMediaSpeechConfig(): Map<String, Any?> =
    mediaSpeechSettingsFacade.load().toGatewayMap()

  override fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?> =
    throw writeUnavailable("saveMediaSpeechConfig")

  override fun loadSandboxSettings(): Map<String, Any?> =
    sandboxSettingsRepository.load().toGatewayMap(localeTagProvider())

  override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> =
    throw writeUnavailable("saveSandboxSettings")

  override fun loadLlmConfig(): Map<String, Any?> =
    llmConfigFacade.load().toGatewayMap()

  override fun saveLlmConfig(
    enabled: Boolean,
    streamingEnabled: Boolean?,
    providerMode: String,
    providerId: String,
    selectedProviderOptionId: String,
    protocol: String,
    providerName: String,
    providerNotes: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    systemPrompt: String,
    openAiPromptCacheKeyStrategy: String?,
    openAiPromptCacheRetention: String?,
    anthropicPromptCachingEnabled: Boolean?,
    anthropicPromptCacheTtl: String?,
    contextBudgetPreset: String?,
    contextBudgetReservedOutputTokens: Int?,
    contextBudgetSafetyMarginTokens: Int?,
    contextBudgetEffectiveInputPercent: Double?,
    selectedOnDeviceModelId: String,
    onDeviceMaxContextWindow: Int,
    onDeviceMaxTokens: Int,
    onDeviceTopK: Int,
    onDeviceTopP: Double,
    onDeviceTemperature: Double,
    onDeviceAccelerator: String,
    onDeviceThinkingEnabled: Boolean,
    onDeviceLiteModeEnabled: Boolean,
    contextWindowTokensOverride: Int?,
  ): Map<String, Any?> = throw writeUnavailable("saveLlmConfig")

  override fun saveCustomLlmProvider(
    selectedProviderOptionId: String,
    streamingEnabled: Boolean?,
    protocol: String,
    providerName: String,
    providerNotes: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    systemPrompt: String,
    openAiPromptCacheKeyStrategy: String?,
    openAiPromptCacheRetention: String?,
    anthropicPromptCachingEnabled: Boolean?,
    anthropicPromptCacheTtl: String?,
    contextBudgetPreset: String?,
    contextBudgetReservedOutputTokens: Int?,
    contextBudgetSafetyMarginTokens: Int?,
    contextBudgetEffectiveInputPercent: Double?,
    contextWindowTokensOverride: Int?,
  ): Map<String, Any?> = throw writeUnavailable("saveCustomLlmProvider")

  override fun validateLlmConfig(
    providerId: String,
    protocol: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    contextWindowTokensOverride: Int?,
  ): Map<String, Any?> = throw writeUnavailable("validateLlmConfig")

  override fun downloadOnDeviceLlmModel(modelId: String): Map<String, Any?> =
    throw writeUnavailable("downloadOnDeviceLlmModel")

  override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> =
    throw writeUnavailable("cancelOnDeviceLlmModelDownload")

  override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> =
    throw writeUnavailable("deleteOnDeviceLlmModel")

  override fun loadPersonalizationConfig(): Map<String, Any?> =
    personalizationFacade.load().toGatewayMap()

  override fun savePersonalizationConfig(
    presetId: String,
    customLabel: String,
    customGuidance: String,
  ): Map<String, Any?> = throw writeUnavailable("savePersonalizationConfig")

  override fun setAppLanguage(languageId: String): Map<String, Any?> =
    throw writeUnavailable("setAppLanguage")

  override fun runPersonalizationReset(scopeId: String): Map<String, Any?> =
    throw writeUnavailable("runPersonalizationReset")

  override fun loadMcpSettings(): Map<String, Any?> =
    mcpSettingsFacade.load().toGatewayMap()

  override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> =
    throw writeUnavailable("setMcpMasterEnabled")

  override fun setMcpServerEnabled(
    serverId: String,
    enabled: Boolean,
  ): Map<String, Any?> = throw writeUnavailable("setMcpServerEnabled")

  override fun loadSafetySettings(): Map<String, Any?> =
    safetySettingsFacade.load().toGatewayMap()

  override fun saveSafetySettings(
    automationModeId: String,
    rollbackJournalEnabled: Boolean,
    maxFilesPerBatch: Int,
    maxAgentTurns: Int,
    maxToolCalls: Int,
    undoWindowHours: Int,
    fileChangesPolicyId: String,
    fileDeletesPolicyId: String,
    shellCommandsPolicyId: String,
    externalAccessModeId: String,
    photoLibraryEnabled: Boolean,
    downloadsEnabled: Boolean,
    documentsEnabled: Boolean,
    recordingsEnabled: Boolean,
    workspaceAccessProfileId: String,
    readOnlyOutsideWorkspace: Boolean,
    liveContextModeId: String,
    memoryToolsEnabled: Boolean,
    subAgentContextDefaultModeId: String?,
    subAgentContextProfileOverrides: Map<String, String>,
  ): Map<String, Any?> = throw writeUnavailable("saveSafetySettings")

  private fun writeUnavailable(operation: String): IllegalStateException = IllegalStateException(
    serviceOwnedGatewayUnavailableMessage(
      surface = "Settings",
      operation = operation,
      connectionState = connectionStateProvider(),
    ),
  )
}

internal class ProjectionOnlyOpenCraySkillsGateway(
  private val skillsFacade: SkillsFacade,
  private val connectionStateProvider: () -> RuntimeServiceConnectionState,
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
) : OpenCraySkillsGateway {
  override fun loadSkillsSnapshot(
    query: String,
    suggestedLimit: Int,
  ): Map<String, Any?> = skillsFacade.loadSnapshot(
    query = query,
    suggestedLimit = suggestedLimit,
  ).toGatewayMap()

  override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeProjectionWithInitial(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = { loadSkillsSnapshot(query = "", suggestedLimit = 0) },
      listener = listener,
    )

  override fun setSkillEnabled(skillId: String, enabled: Boolean) {
    throw writeUnavailable("setSkillEnabled")
  }

  override fun installSuggestedSkill(skillId: String): String =
    throw writeUnavailable("installSuggestedSkill")

  override fun installSkillSource(
    sourceRef: String,
    selectedSkillName: String,
  ): String = throw writeUnavailable("installSkillSource")

  override fun installSkillSourceBatch(
    sourceRef: String,
    selectedSkillNames: List<String>,
  ): String = throw writeUnavailable("installSkillSourceBatch")

  override fun inspectSkillSource(sourceRef: String): Map<String, Any?> =
    throw writeUnavailable("inspectSkillSource")

  override fun deleteInstalledSkill(skillId: String): String =
    throw writeUnavailable("deleteInstalledSkill")

  override fun refreshSkills(): String =
    throw writeUnavailable("refreshSkills")

  override fun checkInstalledSkillUpdates(skillId: String): String =
    throw writeUnavailable("checkInstalledSkillUpdates")

  override fun updateInstalledSkill(skillId: String): String =
    throw writeUnavailable("updateInstalledSkill")

  override fun loadSkillInstructions(skillId: String): Map<String, Any?> {
    val instructions = requireNotNull(skillsFacade.loadInstructions(skillId)) {
      "Skill '$skillId' is unavailable."
    }
    return instructions.toGatewayMap()
  }

  override fun loadSuggestedSkillInstructions(
    sourceRef: String,
    selectedSkillName: String,
  ): Map<String, Any?> {
    val instructions = requireNotNull(
      skillsFacade.loadSuggestedInstructions(
        sourceRef = sourceRef,
        selectedSkillName = selectedSkillName,
      ),
    ) {
      "Skill source '$sourceRef' is unavailable."
    }
    return instructions.toGatewayMap()
  }

  override fun activateSkillsInstallSource(sourceId: String): String =
    throw writeUnavailable("activateSkillsInstallSource")

  private fun writeUnavailable(operation: String): IllegalStateException = IllegalStateException(
    serviceOwnedGatewayUnavailableMessage(
      surface = "Skills",
      operation = operation,
      connectionState = connectionStateProvider(),
    ),
  )
}

internal fun projectionOnlyOpenCrayShellGateway(
  context: Context,
  serviceClient: OpenCrayRuntimeServiceClient,
  hostLifecycleDescriptor: HostRuntimeLifecycleDescriptor,
): OpenCrayShellGateway {
  val appContext = context.applicationContext
  val localizedContext = OpenCrayLocaleManager.wrap(appContext)
  return ProjectionOnlyOpenCrayShellGateway(
    stateStore = AppShellStateStore.fromContext(appContext),
    localeTagProvider = { LocaleSettingsStore.fromContext(appContext).loadLanguage().tag },
    hostLabel = localizedContext.getString(R.string.flutter_host_label_android),
    hostSummary = localizedContext.getString(R.string.flutter_host_summary_android),
    connectionStateProvider = serviceClient::peekConnectionState,
    projectionSnapshotProvider = serviceClient::peekProjectionSnapshot,
    mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
    hostLifecycleDescriptor = hostLifecycleDescriptor,
  )
}

internal fun projectionOnlyOpenCraySettingsGateway(
  context: Context,
  connectionStateProvider: () -> RuntimeServiceConnectionState,
): OpenCraySettingsGateway {
  val appContext = context.applicationContext
  return ProjectionOnlyOpenCraySettingsGateway(
    settingsFacade = LocalSettingsFacade.fromContext(appContext),
    notificationSettingsFacade = LocalNotificationSettingsFacade.fromContext(appContext),
    scheduledTaskManager = AppScheduledTaskManager.fromContext(appContext),
    networkSearchConfigFacade = LocalNetworkSearchConfigFacade.fromContext(appContext),
    mediaSpeechSettingsFacade = LocalMediaSpeechSettingsFacade.fromContext(appContext),
    sandboxSettingsRepository = SandboxSettingsRepository.fromContext(appContext),
    llmConfigFacade = LocalLlmConfigFacade.fromContext(appContext),
    personalizationFacade = LocalPersonalizationFacade.fromContext(appContext),
    mcpSettingsFacade = LocalMcpSettingsFacade.fromContext(appContext),
    safetySettingsFacade = LocalSafetySettingsFacade.fromContext(appContext),
    strongBackgroundSettingsAccess = AndroidStrongBackgroundSettingsAccess.fromContext(appContext),
    localeTagProvider = { LocaleSettingsStore.fromContext(appContext).loadLanguage().tag },
    connectionStateProvider = connectionStateProvider,
    mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
  )
}

internal fun projectionOnlyOpenCraySkillsGateway(
  context: Context,
  connectionStateProvider: () -> RuntimeServiceConnectionState,
): OpenCraySkillsGateway = ProjectionOnlyOpenCraySkillsGateway(
  skillsFacade = LocalSkillsFacade.fromContext(context.applicationContext),
  connectionStateProvider = connectionStateProvider,
  mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
)

internal fun observeProjectionWithInitial(
  mainThreadPoster: MainThreadPoster,
  payloadProvider: () -> Map<String, Any?>,
  listener: (Map<String, Any?>) -> Unit,
): () -> Unit {
  mainThreadPoster.post {
    listener(payloadProvider())
  }
  return { }
}

internal const val PROJECTION_POLL_FAILURE_THRESHOLD = 8
internal const val PROJECTION_POLL_BACKOFF_INITIAL_MS = 250L
internal const val PROJECTION_POLL_BACKOFF_MAX_MS = 5_000L
private const val PROJECTION_POLL_LOG_TAG = "OpenCrayProjectionPoll"

internal fun projectionPollBackoffDelayMs(
  failureCount: Int,
  backoffInitialMs: Long,
  backoffMaxMs: Long,
): Long {
  val shift = (failureCount - 1).coerceIn(0, 16)
  return (backoffInitialMs shl shift).coerceIn(1L, backoffMaxMs)
}

internal fun <S> startProjectionPollingObserver(
  timerName: String,
  streamKey: String,
  pollIntervalMs: Long,
  mainThreadPoster: MainThreadPoster,
  initialState: S,
  readPayload: () -> Map<String, Any?>,
  advance: (previous: S, payload: Map<String, Any?>) -> Pair<S, List<Map<String, Any?>>>,
  deliver: (List<Map<String, Any?>>) -> Unit,
  onError: (Throwable) -> Unit = {},
  initialDelivery: (() -> Unit)? = null,
  failureThreshold: Int = PROJECTION_POLL_FAILURE_THRESHOLD,
  backoffInitialMs: Long = PROJECTION_POLL_BACKOFF_INITIAL_MS,
  backoffMaxMs: Long = PROJECTION_POLL_BACKOFF_MAX_MS,
): () -> Unit {
  val lock = Any()
  var disposed = false
  var state = initialState
  var consecutiveFailures = 0
  var lastSessionId = ""
  val effectiveFailureThreshold = failureThreshold.coerceAtLeast(1)
  val timer = Timer(timerName, true)

  fun logReadFailure(throwable: Throwable, failureCount: Int) {
    runCatching {
      val sessionPart = if (lastSessionId.isBlank()) "" else " sessionId=$lastSessionId"
      Log.w(
        PROJECTION_POLL_LOG_TAG,
        "projection poll read failed stream=$streamKey$sessionPart consecutiveFailures=$failureCount",
        throwable,
      )
    }
  }

  fun attemptOutcome(): Long {
    val result = runCatching(readPayload)
    synchronized(lock) {
      if (disposed) {
        return -1L
      }
      result.fold(
        onSuccess = { payload ->
          consecutiveFailures = 0
          lastSessionId = (payload["sessionId"] as? String)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            .orEmpty()
          val (nextState, events) = advance(state, payload)
          state = nextState
          if (events.isNotEmpty()) {
            mainThreadPoster.post {
              synchronized(lock) {
                if (disposed) {
                  return@post
                }
              }
              deliver(events)
            }
          }
        },
        onFailure = { throwable ->
          consecutiveFailures += 1
          logReadFailure(throwable, consecutiveFailures)
          if (consecutiveFailures < effectiveFailureThreshold) {
            return projectionPollBackoffDelayMs(
              failureCount = consecutiveFailures,
              backoffInitialMs = backoffInitialMs,
              backoffMaxMs = backoffMaxMs,
            )
          }
          mainThreadPoster.post {
            synchronized(lock) {
              if (disposed) {
                return@post
              }
            }
            onError(throwable)
          }
          return -1L
        },
      )
      return pollIntervalMs.coerceAtLeast(1L)
    }
  }

  fun scheduleNext(delayMs: Long) {
    runCatching {
      timer.schedule(
        object : TimerTask() {
          override fun run() {
            val nextDelay = runCatching { attemptOutcome() }.getOrElse { throwable ->
              runCatching {
                Log.e(
                  PROJECTION_POLL_LOG_TAG,
                  "projection poll dispatch failed stream=$streamKey",
                  throwable,
                )
              }
              -1L
            }
            if (nextDelay >= 0L) {
              scheduleNext(nextDelay)
            }
          }
        },
        delayMs,
      )
    }
  }

  initialDelivery?.let { block ->
    mainThreadPoster.post {
      synchronized(lock) {
        if (disposed) {
          return@post
        }
      }
      block()
    }
  }
  scheduleNext(0L)
  return {
    synchronized(lock) {
      disposed = true
    }
    timer.cancel()
  }
}

internal fun observeProjectionWithPollingSnapshot(
  mainThreadPoster: MainThreadPoster,
  payloadProvider: () -> Map<String, Any?>,
  listener: (Map<String, Any?>) -> Unit,
  pollIntervalMs: Long,
  streamKey: String = "projection",
  onError: (Throwable) -> Unit = {},
  failureThreshold: Int = PROJECTION_POLL_FAILURE_THRESHOLD,
  backoffInitialMs: Long = PROJECTION_POLL_BACKOFF_INITIAL_MS,
  backoffMaxMs: Long = PROJECTION_POLL_BACKOFF_MAX_MS,
): () -> Unit {
  var latestPayload: Map<String, Any?>? = runCatching(payloadProvider).getOrNull()
  return startProjectionPollingObserver(
    timerName = "projection-shell-gateway-observer",
    streamKey = streamKey,
    pollIntervalMs = pollIntervalMs,
    mainThreadPoster = mainThreadPoster,
    initialState = latestPayload,
    readPayload = payloadProvider,
    advance = { previous, payload ->
      if (payload == previous) {
        previous to emptyList()
      } else {
        payload to listOf(payload)
      }
    },
    deliver = { events -> listener(events.single()) },
    onError = onError,
    initialDelivery = latestPayload?.let { payload -> { listener(payload) } },
    failureThreshold = failureThreshold,
    backoffInitialMs = backoffInitialMs,
    backoffMaxMs = backoffMaxMs,
  )
}

internal fun observeLiveAssistantDraftsWithPollingSnapshot(
  mainThreadPoster: MainThreadPoster,
  runtimePayloadProvider: () -> Map<String, Any?>,
  listener: (Map<String, Any?>) -> Unit,
  pollIntervalMs: Long,
  streamKey: String = "live_assistant_draft",
  onError: (Throwable) -> Unit = {},
  failureThreshold: Int = PROJECTION_POLL_FAILURE_THRESHOLD,
  backoffInitialMs: Long = PROJECTION_POLL_BACKOFF_INITIAL_MS,
  backoffMaxMs: Long = PROJECTION_POLL_BACKOFF_MAX_MS,
): () -> Unit =
  startProjectionPollingObserver(
    timerName = "projection-draft-gateway-observer",
    streamKey = streamKey,
    pollIntervalMs = pollIntervalMs,
    mainThreadPoster = mainThreadPoster,
    initialState = runCatching {
      polledLiveAssistantDrafts(runtimePayloadProvider())
    }.getOrDefault(emptyMap()),
    readPayload = runtimePayloadProvider,
    advance = { previous, payload ->
      val nextDrafts = polledLiveAssistantDrafts(payload)
      nextDrafts to diffPolledLiveAssistantDrafts(previous, nextDrafts)
    },
    deliver = { events -> events.forEach(listener) },
    onError = onError,
    failureThreshold = failureThreshold,
    backoffInitialMs = backoffInitialMs,
    backoffMaxMs = backoffMaxMs,
  )

private data class PolledLiveAssistantDraft(
  val sessionId: String,
  val runId: String,
  val taskId: String,
  val pendingMessageId: String,
  val text: String,
  val updatedAtEpochMs: Long,
)

private fun polledLiveAssistantDrafts(
  payload: Map<String, Any?>,
): Map<String, PolledLiveAssistantDraft> {
  val sessionId = (payload["sessionId"] as? String)
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return emptyMap()
  @Suppress("UNCHECKED_CAST")
  val drafts = payload["liveAssistantDrafts"] as? List<Map<String, Any?>> ?: return emptyMap()
  return drafts.mapNotNull { draft ->
    val pendingMessageId = (draft["pendingMessageId"] as? String)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return@mapNotNull null
    val runId = (draft["runId"] as? String)?.trim().orEmpty()
    val taskId = (draft["taskId"] as? String)?.trim().orEmpty()
    val text = (draft["text"] as? String)?.trim().orEmpty()
    val updatedAtEpochMs = (draft["updatedAtEpochMs"] as? Number)?.toLong() ?: 0L
    "${sessionId}:${pendingMessageId}" to PolledLiveAssistantDraft(
      sessionId = sessionId,
      runId = runId,
      taskId = taskId,
      pendingMessageId = pendingMessageId,
      text = text,
      updatedAtEpochMs = updatedAtEpochMs,
    )
  }.toMap(linkedMapOf())
}

private fun diffPolledLiveAssistantDrafts(
  previous: Map<String, PolledLiveAssistantDraft>,
  current: Map<String, PolledLiveAssistantDraft>,
): List<Map<String, Any?>> {
  val events = mutableListOf<Map<String, Any?>>()
  current.forEach { (key, draft) ->
    val prior = previous[key]
    if (prior != draft) {
      events += draft.toEventPayload(cleared = false)
    }
  }
  previous.forEach { (key, draft) ->
    if (key !in current) {
      events += draft.toEventPayload(
        cleared = true,
        textOverride = "",
      )
    }
  }
  return events
}

private fun PolledLiveAssistantDraft.toEventPayload(
  cleared: Boolean,
  textOverride: String? = null,
): Map<String, Any?> = mapOf(
  "sessionId" to sessionId,
  "runId" to runId,
  "taskId" to taskId,
  "pendingMessageId" to pendingMessageId,
  "text" to (textOverride ?: text),
  "updatedAtEpochMs" to updatedAtEpochMs,
  "cleared" to cleared,
)

private const val DEFAULT_PROJECTION_SHELL_POLL_INTERVAL_MS: Long = 350L

private fun SettingsOverviewSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "eyebrow" to eyebrow,
  "title" to title,
  "subtitle" to subtitle,
  "deviceTitle" to deviceTitle,
  "deviceSummary" to deviceSummary,
  "entries" to entries.map { entry ->
    mapOf(
      "routeId" to entry.routeId.wireValue,
      "title" to entry.title,
    )
  },
)

private fun SettingsDetailSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "routeId" to routeId.wireValue,
  "title" to title,
  "subtitle" to subtitle,
  "sections" to sections.map { section -> section.toGatewayMap() },
)

private fun SettingsSectionSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "helperText" to helperText,
  "rows" to rows.map { row -> row.toGatewayMap() },
  "segmentedOptions" to segmentedOptions,
  "segmentedIndex" to segmentedIndex,
  "inlinePanelText" to inlinePanelText,
  "backgroundTone" to backgroundTone.wireValue,
)

private fun SettingsRowSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "subtitle" to subtitle,
  "trailingKind" to trailingKind.wireValue,
  "toggleValue" to toggleValue,
  "valueLabel" to valueLabel,
)

private fun PersonalizationConfigSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "subtitle" to subtitle,
  "introTitle" to introTitle,
  "introBody" to introBody,
  "introHelper" to introHelper,
  "presetsTitle" to presetsTitle,
  "presetsHelper" to presetsHelper,
  "presets" to presets.map { preset -> preset.toGatewayMap() },
  "selectedPresetId" to selectedPresetId,
  "customOverlayTitle" to customOverlayTitle,
  "customOverlayHelper" to customOverlayHelper,
  "customLabelHint" to customLabelHint,
  "customLabelHelper" to customLabelHelper,
  "customGuidanceHint" to customGuidanceHint,
  "customGuidanceHelper" to customGuidanceHelper,
  "customLabel" to customLabel,
  "customGuidance" to customGuidance,
  "behaviorDefaultsTitle" to behaviorDefaultsTitle,
  "appLanguageTitle" to appLanguageTitle,
  "appLanguageOptions" to appLanguageOptions.map { option -> option.toGatewayMap() },
  "selectedAppLanguageId" to selectedAppLanguageId,
  "livePreviewTitle" to livePreviewTitle,
  "livePreviewName" to livePreviewName,
  "livePreviewSummary" to livePreviewSummary,
  "queueTitle" to queueTitle,
  "queueBody" to queueBody,
  "queueIsIdle" to queueIsIdle,
  "lastResetTitle" to lastResetTitle,
  "lastResetMessage" to lastResetMessage,
  "resetActions" to resetActions.map { action -> action.toGatewayMap() },
)

private fun PersonalizationPresetSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "summary" to summary,
  "voice" to voice,
  "status" to status,
  "isSelected" to isSelected,
)

private fun PersonalizationLanguageOptionSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "isSelected" to isSelected,
)

private fun PersonalizationResetActionSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "scopeId" to scope.wireValue,
  "title" to title,
  "scopeBody" to scopeBody,
  "retainBody" to retainBody,
  "confirmationToken" to confirmationToken,
  "inputHint" to inputHint,
  "disabledGuidance" to disabledGuidance,
  "typeExactGuidance" to typeExactGuidance,
  "armedGuidance" to armedGuidance,
  "isInputEnabled" to isInputEnabled,
)

private fun SafetySettingsSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "automationModeId" to automationMode.wireValue,
  "rollbackJournalEnabled" to rollbackJournalEnabled,
  "maxFilesPerBatch" to maxFilesPerBatch,
  "maxAgentTurns" to maxAgentTurns,
  "maxToolCalls" to maxToolCalls,
  "undoWindowHours" to undoWindowHours,
  "fileChangesPolicyId" to fileChangesPolicy.wireValue,
  "fileDeletesPolicyId" to fileDeletesPolicy.wireValue,
  "shellCommandsPolicyId" to shellCommandsPolicy.wireValue,
  "externalAccessModeId" to externalAccessMode.wireValue,
  "locations" to locations.map { location -> location.toGatewayMap() },
  "workspaceAccessProfileId" to workspaceAccessProfile.wireValue,
  "readOnlyOutsideWorkspace" to readOnlyOutsideWorkspace,
  "liveContextModeId" to liveContextMode.wireValue,
  "memoryToolsEnabled" to memoryToolsEnabled,
)

private fun SafetySettingsLocationSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "enabled" to enabled,
)
