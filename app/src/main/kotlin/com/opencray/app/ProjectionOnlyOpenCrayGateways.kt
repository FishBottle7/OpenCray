package com.opencray.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmProviderOptionSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.LocalLlmConfigFacade
import com.opencray.app.facade.mcp.LocalMcpSettingsFacade
import com.opencray.app.facade.mcp.McpServerSettingsSnapshot
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsSnapshot
import com.opencray.app.facade.media.LocalMediaSpeechSettingsFacade
import com.opencray.app.facade.media.MediaProviderSnapshot
import com.opencray.app.facade.media.MediaSpeechConfigSnapshot
import com.opencray.app.facade.media.MediaSpeechSettingsFacade
import com.opencray.app.facade.media.OnDeviceSttSnapshot
import com.opencray.app.facade.media.VoiceProviderSnapshot
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
import com.opencray.app.facade.search.NetworkSearchConfigSnapshot
import com.opencray.app.facade.search.NetworkSearchSlotSnapshot
import com.opencray.app.facade.settings.LocalSettingsFacade
import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsRouteId
import com.opencray.app.facade.settings.SettingsRowSnapshot
import com.opencray.app.facade.settings.SettingsSectionSnapshot
import com.opencray.app.facade.skills.InstallSourceSnapshot
import com.opencray.app.facade.skills.InstalledSkillSnapshot
import com.opencray.app.facade.skills.LocalSkillsFacade
import com.opencray.app.facade.skills.SkillInstructionsSnapshot
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.app.facade.skills.SkillsSnapshot
import com.opencray.app.facade.skills.SuggestedSkillSnapshot
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
  private val bridgeSnapshotProvider: () -> OpenCrayRuntimeServiceBridgeSnapshot? = { null },
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
  private val hostLifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
  private val pollIntervalMs: Long = DEFAULT_PROJECTION_SHELL_POLL_INTERVAL_MS,
) : OpenCrayShellGateway {
  override fun loadShellSnapshot(): Map<String, Any?> = buildMap {
    put("initialTab", stateStore.load().selectedTab.routeKey)
    put("localeTag", localeTagProvider())
    put("hostLabel", hostLabel)
    put("hostSummary", hostSummary)
    put("isHostConnected", true)
    put("localRuntimeServerState", OpenCrayLocalRuntimeServerRegistry.peekState().snapshotMap())
    put("hostLifecycle", hostLifecycleDescriptor.snapshotMap())
    bridgeSnapshotProvider()?.runtimeAccess?.lifecycleDescriptor?.snapshotMap()?.let { lifecycle ->
      put("runtimeOwnerLifecycle", lifecycle)
    }
    bridgeSnapshotProvider()?.runtimeAccess?.hostAccess?.activeWorkSummary()?.snapshotMap()?.let { summary ->
      put("runtimeOwnerWorkSummary", summary)
    }
    bridgeSnapshotProvider()?.serviceLifecycle?.snapshotMap()?.let { lifecycle ->
      put("runtimeServiceLifecycle", lifecycle)
    }
    bridgeSnapshotProvider()?.serviceWorkState?.snapshotMap()?.let { workState ->
      put("runtimeServiceWorkState", workState)
    }
    bridgeSnapshotProvider()?.serviceKeepAliveState?.snapshotMap()?.let { keepAliveState ->
      put("runtimeServiceKeepAliveState", keepAliveState)
    }
    connectionStateProvider()?.snapshotMap()?.let { state ->
      put("runtimeServiceConnectionState", state)
    }
  }

  override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeProjectionWithPollingSnapshot(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = ::loadShellSnapshot,
      listener = listener,
      pollIntervalMs = pollIntervalMs,
    )
}

internal class ProjectionOnlyOpenCraySettingsGateway(
  private val settingsFacade: SettingsFacade,
  private val networkSearchConfigFacade: NetworkSearchConfigFacade,
  private val mediaSpeechSettingsFacade: MediaSpeechSettingsFacade,
  private val llmConfigFacade: LlmConfigFacade,
  private val personalizationFacade: PersonalizationFacade,
  private val mcpSettingsFacade: McpSettingsFacade,
  private val safetySettingsFacade: SafetySettingsFacade,
  private val strongBackgroundSettingsAccess: StrongBackgroundSettingsAccess,
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

  override fun loadLlmConfig(): Map<String, Any?> =
    llmConfigFacade.load().toGatewayMap()

  override fun saveLlmConfig(
    enabled: Boolean,
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
  ): Map<String, Any?> = throw writeUnavailable("saveLlmConfig")

  override fun saveCustomLlmProvider(
    selectedProviderOptionId: String,
    protocol: String,
    providerName: String,
    providerNotes: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    systemPrompt: String,
  ): Map<String, Any?> = throw writeUnavailable("saveCustomLlmProvider")

  override fun validateLlmConfig(
    providerId: String,
    protocol: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
  ): Map<String, Any?> = throw writeUnavailable("validateLlmConfig")

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
  override fun loadSkillsSnapshot(query: String): Map<String, Any?> {
    val baseSnapshot = skillsFacade.loadSnapshot(query = "")
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) {
      return baseSnapshot.toGatewayMap()
    }
    return baseSnapshot.copy(
      suggestedSkills = baseSnapshot.suggestedSkills.filter { suggestion ->
        suggestion.name.contains(normalizedQuery, ignoreCase = true) ||
          suggestion.description.contains(normalizedQuery, ignoreCase = true)
      },
    ).toGatewayMap()
  }

  override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeProjectionWithInitial(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = { loadSkillsSnapshot(query = "") },
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
): OpenCrayShellGateway {
  val appContext = context.applicationContext
  val localizedContext = OpenCrayLocaleManager.wrap(appContext)
  return ProjectionOnlyOpenCrayShellGateway(
    stateStore = AppShellStateStore.fromContext(appContext),
    localeTagProvider = { LocaleSettingsStore.fromContext(appContext).loadLanguage().tag },
    hostLabel = localizedContext.getString(R.string.flutter_host_label_android),
    hostSummary = localizedContext.getString(R.string.flutter_host_summary_android),
    connectionStateProvider = serviceClient::loadConnectionState,
    bridgeSnapshotProvider = {
      runCatching { serviceClient.loadSnapshot().bridgeSnapshot }.getOrNull()
    },
    mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
  )
}

internal fun projectionOnlyOpenCraySettingsGateway(
  context: Context,
  connectionStateProvider: () -> RuntimeServiceConnectionState,
): OpenCraySettingsGateway {
  val appContext = context.applicationContext
  return ProjectionOnlyOpenCraySettingsGateway(
    settingsFacade = LocalSettingsFacade.fromContext(appContext),
    networkSearchConfigFacade = LocalNetworkSearchConfigFacade.fromContext(appContext),
    mediaSpeechSettingsFacade = LocalMediaSpeechSettingsFacade.fromContext(appContext),
    llmConfigFacade = LocalLlmConfigFacade.fromContext(appContext),
    personalizationFacade = LocalPersonalizationFacade.fromContext(appContext),
    mcpSettingsFacade = LocalMcpSettingsFacade.fromContext(appContext),
    safetySettingsFacade = LocalSafetySettingsFacade.fromContext(appContext),
    strongBackgroundSettingsAccess = AndroidStrongBackgroundSettingsAccess.fromContext(appContext),
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

internal fun observeProjectionWithPollingSnapshot(
  mainThreadPoster: MainThreadPoster,
  payloadProvider: () -> Map<String, Any?>,
  listener: (Map<String, Any?>) -> Unit,
  pollIntervalMs: Long,
): () -> Unit {
  val lock = Any()
  var disposed = false
  var latestPayload = payloadProvider()
  mainThreadPoster.post {
    listener(latestPayload)
  }
  val timer = Timer("projection-shell-gateway-observer", true)
  timer.scheduleAtFixedRate(
    object : TimerTask() {
      override fun run() {
        val nextPayload = runCatching(payloadProvider).getOrNull() ?: return
        val shouldEmit = synchronized(lock) {
          if (disposed || nextPayload == latestPayload) {
            false
          } else {
            latestPayload = nextPayload
            true
          }
        }
        if (!shouldEmit) {
          return
        }
        mainThreadPoster.post {
          synchronized(lock) {
            if (disposed) {
              return@post
            }
          }
          listener(nextPayload)
        }
      }
    },
    pollIntervalMs.coerceAtLeast(1L),
    pollIntervalMs.coerceAtLeast(1L),
  )
  return {
    synchronized(lock) {
      disposed = true
    }
    timer.cancel()
  }
}

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

private fun NetworkSearchConfigSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "localeTag" to localeTag,
  "title" to title,
  "subtitle" to subtitle,
  "slots" to slots.map { slot -> slot.toGatewayMap() },
)

private fun NetworkSearchSlotSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "providerId" to providerId,
  "label" to label,
  "baseUrl" to baseUrl,
  "model" to model,
  "apiKey" to apiKey,
  "enabled" to enabled,
)

private fun MediaSpeechConfigSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "localeTag" to localeTag,
  "title" to title,
  "subtitle" to subtitle,
  "imageGeneration" to imageGeneration.toGatewayMap(),
  "voiceGeneration" to voiceGeneration.toGatewayMap(),
  "sttRouteId" to sttRouteId,
  "externalStt" to externalStt.toGatewayMap(),
  "onDeviceModel" to onDeviceModel.toGatewayMap(),
)

private fun MediaProviderSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "provider" to provider,
  "baseUrl" to baseUrl,
  "endpoint" to endpoint,
  "model" to model,
)

private fun VoiceProviderSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "provider" to provider,
  "baseUrl" to baseUrl,
  "endpoint" to endpoint,
  "voicePreset" to voicePreset,
)

private fun OnDeviceSttSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "modelPackage" to modelPackage,
  "downloadStatus" to downloadStatus,
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

private fun LlmConfigSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "localeTag" to localeTag,
  "enabled" to enabled,
  "providerId" to providerId,
  "selectedProviderOptionId" to selectedProviderOptionId,
  "protocol" to protocol,
  "providerOptions" to providerOptions.map { option -> option.toGatewayMap() },
  "providerName" to providerName,
  "providerNotes" to providerNotes,
  "baseUrl" to baseUrl,
  "apiKey" to apiKey,
  "model" to model,
  "reasoningEffort" to reasoningEffort,
  "systemPrompt" to systemPrompt,
  "helperText" to helperText,
)

private fun LlmProviderOptionSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "providerId" to providerId,
  "title" to title,
  "subtitle" to subtitle,
  "defaultBaseUrl" to defaultBaseUrl,
  "defaultModel" to defaultModel,
  "protocol" to protocol,
  "apiKey" to apiKey,
  "isCustom" to isCustom,
)

private fun LlmValidationResult.toGatewayMap(): Map<String, Any?> = mapOf(
  "isSuccess" to isSuccess,
  "message" to message,
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

private fun McpSettingsSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "subtitle" to subtitle,
  "masterTitle" to masterTitle,
  "masterSummary" to masterSummary,
  "masterEnabled" to masterEnabled,
  "summaryLine" to summaryLine,
  "serversTitle" to serversTitle,
  "serversHelper" to serversHelper,
  "masterDisabledTitle" to masterDisabledTitle,
  "masterDisabledBody" to masterDisabledBody,
  "servers" to servers.map { server -> server.toGatewayMap() },
)

private fun McpServerSettingsSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "statusLabel" to statusLabel,
  "statusTone" to statusTone,
  "trustLine" to trustLine,
  "authLine" to authLine,
  "readinessLine" to readinessLine,
  "transportLine" to transportLine,
  "exposureLine" to exposureLine,
  "guidance" to guidance,
  "actionLabel" to actionLabel,
  "actionTurnsOn" to actionTurnsOn,
  "isActionEnabled" to isActionEnabled,
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

private fun SkillsSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "installedSkills" to installedSkills.map { skill -> skill.toGatewayMap() },
  "installSources" to installSources.map { source -> source.toGatewayMap() },
  "suggestedSkills" to suggestedSkills.map { suggestion -> suggestion.toGatewayMap() },
)

private fun InstalledSkillSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "name" to name,
  "description" to description,
  "isEnabled" to isEnabled,
  "sourceDirectoryPath" to sourceDirectoryPath,
  "canDelete" to canDelete,
)

private fun InstallSourceSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "subtitle" to subtitle,
  "actionLabel" to actionLabel,
  "isAvailable" to isAvailable,
)

private fun SuggestedSkillSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "name" to name,
  "description" to description,
  "sourceRef" to sourceRef,
  "sourceLabel" to sourceLabel,
)

private fun SkillInstructionsSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "name" to name,
  "description" to description,
  "body" to body,
  "sourceDirectoryPath" to sourceDirectoryPath,
  "isEnabled" to isEnabled,
  "canDelete" to canDelete,
)
