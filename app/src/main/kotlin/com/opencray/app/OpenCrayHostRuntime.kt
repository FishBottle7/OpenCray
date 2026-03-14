package com.opencray.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.opencray.app.facade.llm.EmptyLlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmProviderOptionSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.LocalLlmConfigFacade
import com.opencray.app.facade.llm.SaveLlmConfigRequest
import com.opencray.app.facade.llm.ValidateLlmConfigRequest
import com.opencray.app.facade.mcp.EmptyMcpSettingsFacade
import com.opencray.app.facade.mcp.LocalMcpSettingsFacade
import com.opencray.app.facade.mcp.McpServerSettingsSnapshot
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsSnapshot
import com.opencray.app.facade.personalization.EmptyPersonalizationFacade
import com.opencray.app.facade.personalization.LocalPersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationConfigSnapshot
import com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot
import com.opencray.app.facade.personalization.PersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationPresetSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetScope
import com.opencray.app.facade.personalization.SavePersonalizationConfigRequest
import com.opencray.app.facade.skills.EmptySkillsFacade
import com.opencray.app.facade.skills.InstallSourceSnapshot
import com.opencray.app.facade.skills.InstalledSkillSnapshot
import com.opencray.app.facade.skills.LocalSkillsFacade
import com.opencray.app.facade.skills.SkillInstructionsSnapshot
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.app.facade.skills.SkillsSnapshot
import com.opencray.app.facade.skills.SuggestedSkillSnapshot
import com.opencray.app.facade.settings.LocalSettingsFacade
import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsRouteId
import com.opencray.app.facade.settings.SettingsRowSnapshot
import com.opencray.app.facade.settings.SettingsSectionSnapshot
import com.opencray.app.shell.AppShellStateStore
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.opencray.app.R

internal class OpenCrayHostRuntime private constructor(
  private val appContext: Context?,
  private val stateStore: AppShellStateStore,
  private val chatSessionStore: ChatSessionLocalStore,
  private var settingsFacade: SettingsFacade,
  private var llmConfigFacade: LlmConfigFacade,
  private var personalizationFacade: PersonalizationFacade,
  private var mcpSettingsFacade: McpSettingsFacade,
  private var skillsFacade: SkillsFacade,
  private val sessionRuntimeManager: AgentSessionRuntimeManager,
  private var strings: HostRuntimeStrings,
  private val mainThreadPoster: MainThreadPoster,
) {
  private val lock = Any()
  private val shellListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val settingsOverviewListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val skillsListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val chatListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()

  init {
    sessionRuntimeManager.observe(
      object : AgentSessionRuntimeListener {
        override fun onTaskStarted(sessionId: String, task: AgentTask) {
          emitChatSnapshot()
        }

        override fun onTaskFinished(sessionId: String, task: AgentTask, result: ExecutionResult) {
          val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
            ?.takeIf(String::isNotBlank)
            ?: return
          synchronized(lock) {
            chatSessionStore.replaceMessage(
              sessionId = sessionId,
              messageId = pendingMessageId,
              role = ChatTranscriptRole.ASSISTANT,
              text = finalTextFor(result),
            )
          }
          emitChatSnapshot()
        }
      },
    )
    ensureActiveSessionResumed()
  }

  fun loadShellSnapshot(): Map<String, Any?> = mapOf(
    "initialTab" to stateStore.load().selectedTab.routeKey,
    "localeTag" to strings.localeTag,
    "hostLabel" to strings.shellHostLabel,
    "hostSummary" to strings.shellHostSummary,
    "isHostConnected" to true,
  )

  fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = shellListeners,
      initialPayload = loadShellSnapshot(),
      listener = listener,
    )

  fun loadSettingsOverview(): Map<String, Any?> =
    synchronized(lock) { settingsFacade.loadOverview() }.toMap()

  fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = settingsOverviewListeners,
      initialPayload = loadSettingsOverview(),
      listener = listener,
    )

  fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> {
    val routeId = SettingsRouteId.fromWireValue(routeIdRaw) ?: SettingsRouteId.WORKSPACE_ACCESS
    return synchronized(lock) { settingsFacade.loadDetail(routeId) }.toMap()
  }

  fun loadLlmConfig(): Map<String, Any?> =
    synchronized(lock) { llmConfigFacade.load() }.toMap()

  fun saveLlmConfig(
    enabled: Boolean,
    providerId: String,
    protocol: String,
    providerName: String,
    providerNotes: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
    systemPrompt: String,
  ): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      llmConfigFacade.save(
        SaveLlmConfigRequest(
          enabled = enabled,
          providerId = providerId,
          protocol = protocol,
          providerName = providerName,
          providerNotes = providerNotes,
          baseUrl = baseUrl,
          apiKey = apiKey,
          model = model,
          reasoningEffort = reasoningEffort,
          systemPrompt = systemPrompt,
        ),
      )
    }
    return snapshot.toMap()
  }

  fun validateLlmConfig(
    providerId: String,
    protocol: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
  ): Map<String, Any?> =
    synchronized(lock) {
      llmConfigFacade.validate(
        ValidateLlmConfigRequest(
          providerId = providerId,
          protocol = protocol,
          baseUrl = baseUrl,
          apiKey = apiKey,
          model = model,
          reasoningEffort = reasoningEffort,
        ),
      )
    }.toMap()

  fun loadPersonalizationConfig(): Map<String, Any?> =
    synchronized(lock) { personalizationFacade.load() }.toMap()

  fun savePersonalizationConfig(
    presetId: String,
    customLabel: String,
    customGuidance: String,
  ): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      personalizationFacade.save(
        SavePersonalizationConfigRequest(
          presetId = presetId,
          customLabel = customLabel,
          customGuidance = customGuidance,
        ),
      )
    }
    emitSettingsOverview()
    return snapshot.toMap()
  }

  fun setAppLanguage(languageId: String): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      val updated = personalizationFacade.setAppLanguage(languageId)
      if (appContext == null) {
        updated
      } else {
        refreshLocalizedResourcesLocked()
        personalizationFacade.load()
      }
    }
    emitShellSnapshot()
    emitSettingsOverview()
    emitSkillsSnapshot()
    emitChatSnapshot()
    return snapshot.toMap()
  }

  fun runPersonalizationReset(scopeId: String): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      personalizationFacade.reset(PersonalizationResetScope.fromWireValue(scopeId))
    }
    emitSettingsOverview()
    return snapshot.toMap()
  }

  fun loadMcpSettings(): Map<String, Any?> =
    synchronized(lock) { mcpSettingsFacade.load() }.toMap()

  fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> =
    synchronized(lock) { mcpSettingsFacade.setMasterEnabled(enabled) }.toMap()

  fun setMcpServerEnabled(
    serverId: String,
    enabled: Boolean,
  ): Map<String, Any?> = synchronized(lock) {
    mcpSettingsFacade.setServerEnabled(serverId = serverId, enabled = enabled)
  }.toMap()

  fun loadSkillsSnapshot(query: String = ""): Map<String, Any?> =
    synchronized(lock) { skillsFacade.loadSnapshot(query) }.toMap()

  fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = skillsListeners,
      initialPayload = loadSkillsSnapshot(),
      listener = listener,
    )

  fun setSkillEnabled(skillId: String, enabled: Boolean) {
    synchronized(lock) {
      require(skillsFacade.setSkillEnabled(skillId = skillId, enabled = enabled)) {
        "Skill '$skillId' is not installed."
      }
    }
    emitSkillsSnapshot()
  }

  fun installSuggestedSkill(skillId: String): String {
    val installed = synchronized(lock) {
      skillsFacade.installSuggestedSkill(skillId)
    }
    require(installed) {
      "Unable to install '$skillId' from the local catalog."
    }
    emitSkillsSnapshot()
    return strings.skillInstalled(skillId)
  }

  fun deleteInstalledSkill(skillId: String): String {
    val deleted = synchronized(lock) {
      skillsFacade.deleteInstalledSkill(skillId)
    }
    require(deleted) {
      "Unable to remove '$skillId'."
    }
    emitSkillsSnapshot()
    return strings.skillRemoved(skillId)
  }

  fun refreshSkills(): String {
    synchronized(lock) {
      skillsFacade.refresh()
    }
    emitSkillsSnapshot()
    return strings.skillsReloaded
  }

  fun loadSkillInstructions(skillId: String): Map<String, Any?> {
    val instructions = synchronized(lock) {
      skillsFacade.loadInstructions(skillId)
    }
    requireNotNull(instructions) {
      "Skill '$skillId' is unavailable."
    }
    return instructions.toMap()
  }

  fun activateSkillsInstallSource(sourceId: String): String =
    synchronized(lock) { skillsFacade.activateInstallSource(sourceId) }

  fun loadChatSnapshot(): Map<String, Any?> = synchronized(lock) {
    val chatState = chatSessionStore.loadState()
    val activeSession = chatState.activeSession
    val visibleMessages = activeSession.messages.filter(::isVisibleChatMessage)
    val pendingCount = pendingTaskCount(activeSession.sessionId)
    val activeSessionTitle = displaySessionTitle(activeSession.title)
    mapOf(
      "screenTitle" to strings.chatScreenTitle,
      "modeLabel" to strings.chatModeLabel,
      "sessionButtonLabel" to strings.chatSessionButtonLabel,
      "composerPlaceholder" to strings.composerPlaceholder,
      "summary" to mapOf(
        "title" to activeSessionTitle,
        "badge" to strings.chatMessagesBadge(visibleMessages.size),
        "body" to if (pendingCount > 0) {
          strings.chatSummaryReplyInProgress
        } else if (visibleMessages.isEmpty()) {
          strings.chatSummaryStartNewSession
        } else {
          strings.chatSummaryRestored
        },
      ),
      "messages" to visibleMessages.map(::chatMessageToMap),
      "drawer" to mapOf(
        "eyebrow" to strings.chatRecentSessionsEyebrow,
        "title" to strings.chatRecentSessionsTitle,
        "ctaLabel" to strings.chatNewSessionLabel,
        "sessions" to chatState.sessions.map { session ->
          mapOf(
            "sessionId" to session.sessionId,
            "title" to displaySessionTitle(session.title),
            "preview" to session.lastMessagePreview,
            "meta" to strings.chatMessagesBadge(session.messageCount),
            "isSelected" to (session.sessionId == activeSession.sessionId),
          )
        },
      ),
      "isInputEnabled" to true,
    )
  }

  fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = chatListeners,
      initialPayload = loadChatSnapshot(),
      listener = listener,
    )

  fun createChatSession() {
    synchronized(lock) {
      val createdState = chatSessionStore.createSession()
      sessionRuntimeManager.forSession(createdState.activeSession.sessionId).resume()
    }
    emitChatSnapshot()
  }

  fun selectChatSession(sessionId: String) {
    synchronized(lock) {
      val selectedState = chatSessionStore.selectSession(sessionId)
      sessionRuntimeManager.forSession(selectedState.activeSession.sessionId).resume()
    }
    emitChatSnapshot()
  }

  fun submitChatMessage(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) {
      return
    }

    synchronized(lock) {
      val activeState = chatSessionStore.loadState()
      val sessionId = activeState.activeSession.sessionId
      val handle = sessionRuntimeManager.forSession(sessionId)
      val pendingMessageId = chatSessionStore.reserveMessageId(ChatTranscriptRole.ASSISTANT)
      val submittedTask = handle.submitPrompt(
        userText = ChatRuntimeTextFormatter.format(
          text = trimmed,
          commandLabel = null,
          attachments = emptyList(),
        ),
        pendingMessageId = pendingMessageId,
        visibleThroughMessageId = pendingMessageId,
        policyDecision = PolicyDecision(
          outcome = PolicyDecisionOutcome.ALLOW,
          reasonCode = "FLUTTER_CHAT_ALLOW",
          detail = "Flutter chat currently runs with host-managed allow mode.",
        ),
        metadata = mapOf(
          "chatMode" to DEFAULT_MODE_LABEL,
        ),
      )
      try {
        chatSessionStore.appendSubmittedTurn(
          sessionId = sessionId,
          userText = trimmed,
          assistantMessageId = pendingMessageId,
          assistantPlaceholderText = strings.agentThinking,
        )
      } catch (throwable: Throwable) {
        handle.requestCancel(submittedTask.id)
        throw throwable
      }
      handle.ensureProcessing()
    }
    emitChatSnapshot()
  }

  private fun ensureActiveSessionResumed() {
    val activeSessionId = synchronized(lock) { chatSessionStore.loadState().activeSession.sessionId }
    sessionRuntimeManager.forSession(activeSessionId).resume()
  }

  private fun pendingTaskCount(sessionId: String): Int = sessionRuntimeManager.forSession(sessionId)
    .snapshot()
    .tasks
    .count { taskSnapshot ->
      when (taskSnapshot.lifecycleState) {
        com.opencray.core.orchestrator.QueueTaskLifecycleState.QUEUED,
        com.opencray.core.orchestrator.QueueTaskLifecycleState.RUNNING,
        com.opencray.core.orchestrator.QueueTaskLifecycleState.RETRY_PENDING,
        com.opencray.core.orchestrator.QueueTaskLifecycleState.CANCEL_REQUESTED,
        -> true

        com.opencray.core.orchestrator.QueueTaskLifecycleState.COMPLETED,
        com.opencray.core.orchestrator.QueueTaskLifecycleState.CANCELLED,
        com.opencray.core.orchestrator.QueueTaskLifecycleState.FAILED,
        -> false
      }
    }

  private fun isVisibleChatMessage(message: ChatTranscriptMessageEntry): Boolean =
    message.role != ChatTranscriptRole.SYSTEM

  private fun displaySessionTitle(rawTitle: String): String =
    if (rawTitle == ChatSessionLocalStore.DEFAULT_SESSION_TITLE) {
      strings.chatDefaultSessionTitle
    } else {
      rawTitle
    }

  private fun chatMessageToMap(message: ChatTranscriptMessageEntry): Map<String, Any?> {
    val resolvedText = message.text ?: chatSessionStore.promptTemplateBody(message.promptTemplateRefId).orEmpty()
    val kind = when (message.role) {
      ChatTranscriptRole.USER -> "outbound"
      ChatTranscriptRole.ASSISTANT -> "inbound"
      ChatTranscriptRole.TOOL -> "timeline"
      ChatTranscriptRole.SYSTEM -> "timeline"
    }
    return mapOf(
      "kind" to kind,
      "text" to resolvedText,
      "meta" to "",
    )
  }

  private fun finalTextFor(result: ExecutionResult): String = when (result.status) {
    ExecutionStatus.SUCCESS -> result.stdout.ifBlank { strings.agentEmptyAnswer }
    ExecutionStatus.CANCELLED -> strings.agentCancelled
    ExecutionStatus.FAILED -> if (result.errorCode == AppAgentSessionTaskRuntimeFactory.ERROR_CODE_MISSING_LLM_CONFIG) {
      strings.agentMissingLlm
    } else {
      strings.agentFailed(result.errorMessage ?: result.errorCode ?: result.status.name)
    }

    else -> strings.agentFailed(result.errorMessage ?: result.errorCode ?: result.status.name)
  }

  internal fun currentMcpExposureReport() =
    synchronized(lock) { mcpSettingsFacade.currentExposureReport() }

  internal fun currentEnabledSkillRoots() =
    synchronized(lock) { skillsFacade.enabledSkillRoots() }

  private fun refreshLocalizedResourcesLocked() {
    val baseContext = appContext ?: return
    val localizedContext = OpenCrayLocaleManager.wrap(baseContext)
    settingsFacade = LocalSettingsFacade.fromContext(localizedContext)
    llmConfigFacade = LocalLlmConfigFacade.fromContext(localizedContext)
    personalizationFacade = LocalPersonalizationFacade.createForTest(
      context = localizedContext,
      store = PersonalizationLocalStore.fromContext(baseContext),
      queueIdleProvider = {
        val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
        pendingTaskCount(activeSessionId) == 0
      },
    )
    mcpSettingsFacade = LocalMcpSettingsFacade.createForTest(
      context = localizedContext,
      settingsStore = McpSettingsStore.fromContext(baseContext),
      registryStore = AppMcpRegistryStore.fromContext(baseContext),
    )
    skillsFacade = LocalSkillsFacade.fromContext(localizedContext)
    strings = localizedHostRuntimeStrings(localizedContext)
  }

  private fun SettingsOverviewSnapshot.toMap(): Map<String, Any?> = mapOf(
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

  private fun SettingsDetailSnapshot.toMap(): Map<String, Any?> = mapOf(
    "routeId" to routeId.wireValue,
    "title" to title,
    "subtitle" to subtitle,
    "sections" to sections.map { section -> section.toMap() },
  )

  private fun SettingsSectionSnapshot.toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "helperText" to helperText,
    "rows" to rows.map { row -> row.toMap() },
    "segmentedOptions" to segmentedOptions,
    "segmentedIndex" to segmentedIndex,
    "inlinePanelText" to inlinePanelText,
    "backgroundTone" to backgroundTone.wireValue,
  )

  private fun SettingsRowSnapshot.toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "subtitle" to subtitle,
    "trailingKind" to trailingKind.wireValue,
    "toggleValue" to toggleValue,
    "valueLabel" to valueLabel,
  )

  private fun LlmConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
    "enabled" to enabled,
    "providerId" to providerId,
    "protocol" to protocol,
    "providerOptions" to providerOptions.map { option -> option.toMap() },
    "providerName" to providerName,
    "providerNotes" to providerNotes,
    "baseUrl" to baseUrl,
    "apiKey" to apiKey,
    "model" to model,
    "reasoningEffort" to reasoningEffort,
    "systemPrompt" to systemPrompt,
    "helperText" to helperText,
  )

  private fun LlmProviderOptionSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "subtitle" to subtitle,
    "defaultBaseUrl" to defaultBaseUrl,
    "defaultModel" to defaultModel,
    "isCustom" to isCustom,
  )

  private fun LlmValidationResult.toMap(): Map<String, Any?> = mapOf(
    "isSuccess" to isSuccess,
    "message" to message,
  )

  private fun PersonalizationConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
    "title" to title,
    "subtitle" to subtitle,
    "introTitle" to introTitle,
    "introBody" to introBody,
    "introHelper" to introHelper,
    "presetsTitle" to presetsTitle,
    "presetsHelper" to presetsHelper,
    "presets" to presets.map { preset -> preset.toMap() },
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
    "appLanguageOptions" to appLanguageOptions.map { option -> option.toMap() },
    "selectedAppLanguageId" to selectedAppLanguageId,
    "livePreviewTitle" to livePreviewTitle,
    "livePreviewName" to livePreviewName,
    "livePreviewSummary" to livePreviewSummary,
    "queueTitle" to queueTitle,
    "queueBody" to queueBody,
    "queueIsIdle" to queueIsIdle,
    "lastResetTitle" to lastResetTitle,
    "lastResetMessage" to lastResetMessage,
    "resetActions" to resetActions.map { action -> action.toMap() },
  )

  private fun PersonalizationPresetSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "summary" to summary,
    "voice" to voice,
    "status" to status,
    "isSelected" to isSelected,
  )

  private fun PersonalizationLanguageOptionSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "isSelected" to isSelected,
  )

  private fun PersonalizationResetActionSnapshot.toMap(): Map<String, Any?> = mapOf(
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

  private fun McpSettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
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
    "servers" to servers.map { server -> server.toMap() },
  )

  private fun McpServerSettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
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

  private fun SkillsSnapshot.toMap(): Map<String, Any?> = mapOf(
    "installedSkills" to installedSkills.map { skill -> skill.toMap() },
    "installSources" to installSources.map { source -> source.toMap() },
    "suggestedSkills" to suggestedSkills.map { suggestion -> suggestion.toMap() },
  )

  private fun InstalledSkillSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "description" to description,
    "isEnabled" to isEnabled,
    "sourceDirectoryPath" to sourceDirectoryPath,
    "canDelete" to canDelete,
  )

  private fun InstallSourceSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "subtitle" to subtitle,
    "actionLabel" to actionLabel,
    "isAvailable" to isAvailable,
  )

  private fun SuggestedSkillSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "description" to description,
  )

  private fun SkillInstructionsSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "description" to description,
    "body" to body,
    "sourceDirectoryPath" to sourceDirectoryPath,
    "isEnabled" to isEnabled,
    "canDelete" to canDelete,
  )

  private fun observeWithInitial(
    listeners: LinkedHashSet<(Map<String, Any?>) -> Unit>,
    initialPayload: Map<String, Any?>,
    listener: (Map<String, Any?>) -> Unit,
  ): () -> Unit {
    synchronized(lock) {
      listeners += listener
    }
    mainThreadPoster.post { listener(initialPayload) }
    return {
      synchronized(lock) {
        listeners -= listener
      }
    }
  }

  private fun emitChatSnapshot() {
    val payload = loadChatSnapshot()
    emitSnapshot(chatListeners, payload)
  }

  private fun emitShellSnapshot() {
    val payload = loadShellSnapshot()
    emitSnapshot(shellListeners, payload)
  }

  private fun emitSettingsOverview() {
    val payload = loadSettingsOverview()
    emitSnapshot(settingsOverviewListeners, payload)
  }

  private fun emitSkillsSnapshot() {
    val payload = loadSkillsSnapshot()
    emitSnapshot(skillsListeners, payload)
  }

  private fun emitSnapshot(
    listeners: LinkedHashSet<(Map<String, Any?>) -> Unit>,
    payload: Map<String, Any?>,
  ) {
    val currentListeners = synchronized(lock) { listeners.toList() }
    if (currentListeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      currentListeners.forEach { listener -> listener(payload) }
    }
  }

  companion object {
    private const val DEFAULT_MODE_LABEL: String = "AUTO"

    @Volatile
    private var instance: OpenCrayHostRuntime? = null

    fun fromContext(context: Context): OpenCrayHostRuntime =
      instance ?: synchronized(this) {
        instance ?: createFromContext(context.applicationContext).also { created ->
          instance = created
        }
      }

    internal fun createForTest(
      stateStore: AppShellStateStore,
      chatSessionStore: ChatSessionLocalStore,
      settingsFacade: SettingsFacade,
      llmConfigFacade: LlmConfigFacade = EmptyLlmConfigFacade,
      personalizationFacade: PersonalizationFacade = EmptyPersonalizationFacade,
      mcpSettingsFacade: McpSettingsFacade = EmptyMcpSettingsFacade,
      skillsFacade: SkillsFacade = EmptySkillsFacade,
      sessionRuntimeManager: AgentSessionRuntimeManager,
      strings: HostRuntimeStrings = HostRuntimeStrings(
        localeTag = "en",
        shellHostLabel = "HOST CONNECTED",
        shellHostSummary = "Android host bridge is attached to the live app runtime.",
        chatScreenTitle = "Chat",
        chatModeLabel = "AUTO",
        chatSessionButtonLabel = "Sessions",
        chatRecentSessionsEyebrow = "Recent sessions",
        chatRecentSessionsTitle = "Recent sessions",
        chatNewSessionLabel = "New session",
        chatDefaultSessionTitle = "New chat",
        chatMessagesBadge = { count -> "$count messages" },
        chatSummaryReplyInProgress = "Reply in progress",
        chatSummaryStartNewSession = "Start a new session",
        chatSummaryRestored = "Local transcript is restored into the runtime window for each task.",
        skillInstalled = { skillId -> "Installed $skillId." },
        skillRemoved = { skillId -> "Removed $skillId." },
        skillsReloaded = "Reloaded skills from local storage.",
        composerPlaceholder = "Message OpenCray",
        agentThinking = "Thinking",
        agentCancelled = "Cancelled",
        agentMissingLlm = "Missing LLM",
        agentEmptyAnswer = "The model returned an empty answer.",
        agentFailed = { detail -> "Failed: $detail" },
      ),
      mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
    ): OpenCrayHostRuntime = OpenCrayHostRuntime(
      appContext = null,
      stateStore = stateStore,
      chatSessionStore = chatSessionStore,
      settingsFacade = settingsFacade,
      llmConfigFacade = llmConfigFacade,
      personalizationFacade = personalizationFacade,
      mcpSettingsFacade = mcpSettingsFacade,
      skillsFacade = skillsFacade,
      sessionRuntimeManager = sessionRuntimeManager,
      strings = strings,
      mainThreadPoster = mainThreadPoster,
    )

    private fun createFromContext(appContext: Context): OpenCrayHostRuntime {
      BuiltinSkillsSeeder.fromContext(appContext).seedBundledSkillsIfNeeded()
      val localizedContext = OpenCrayLocaleManager.wrap(appContext)
      val llmSettingsStore = LlmSettingsStore.fromContext(appContext)
      val personalizationStore = PersonalizationLocalStore.fromContext(appContext)
      val chatSessionStore = ChatSessionLocalStore.fromContext(appContext)
      val skillsFacade = LocalSkillsFacade.fromContext(localizedContext)
      val mcpSettingsStore = McpSettingsStore.fromContext(appContext)
      val mcpRegistryStore = AppMcpRegistryStore.fromContext(appContext)
      val chatExecutor: ExecutorService = Executors.newSingleThreadExecutor()
      val chatContextFactory = ChatRuntimeSessionContextFactory(chatSessionStore)
      lateinit var hostRuntime: OpenCrayHostRuntime
      val personalizationFacade = LocalPersonalizationFacade.createForTest(
        context = localizedContext,
        store = personalizationStore,
        queueIdleProvider = {
          val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
          hostRuntime.pendingTaskCount(activeSessionId) == 0
        },
      )
      val mcpSettingsFacade = LocalMcpSettingsFacade.createForTest(
        context = localizedContext,
        settingsStore = mcpSettingsStore,
        registryStore = mcpRegistryStore,
      )
      hostRuntime = OpenCrayHostRuntime(
        appContext = appContext,
        stateStore = AppShellStateStore.fromContext(appContext),
        chatSessionStore = chatSessionStore,
        settingsFacade = LocalSettingsFacade.fromContext(localizedContext),
        llmConfigFacade = LocalLlmConfigFacade.fromContext(localizedContext),
        personalizationFacade = personalizationFacade,
        mcpSettingsFacade = mcpSettingsFacade,
        skillsFacade = skillsFacade,
        sessionRuntimeManager = DefaultAgentSessionRuntimeManager(
          agentId = "opencray-flutter-host",
          runtimeFactory = AppAgentSessionTaskRuntimeFactory(
            llmSettingsProvider = { llmSettingsStore.load() },
            sessionContextFactory = chatContextFactory,
            soulProfileProvider = { personalizationStore.loadSoulProfile() },
            workspaceRootsProvider = { setOf(AppAgentWorkspace.ensureRootForContext(appContext)) },
            skillsRootsProvider = { hostRuntime.currentEnabledSkillRoots() },
            mcpReportProvider = { hostRuntime.currentMcpExposureReport() },
          ),
          snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(appContext),
          executor = chatExecutor,
        ),
        strings = localizedHostRuntimeStrings(localizedContext),
        mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
      )
      return hostRuntime
    }

    private fun localizedHostRuntimeStrings(context: Context): HostRuntimeStrings = HostRuntimeStrings(
      localeTag = LocaleSettingsStore.fromContext(context).loadLanguage().tag,
      shellHostLabel = context.getString(R.string.flutter_host_label_android),
      shellHostSummary = context.getString(R.string.flutter_host_summary_android),
      chatScreenTitle = context.getString(R.string.shell_tab_chat),
      chatModeLabel = context.getString(R.string.chat_mode_auto),
      chatSessionButtonLabel = context.getString(R.string.chat_sessions_button),
      chatRecentSessionsEyebrow = context.getString(R.string.chat_recent_sessions_eyebrow),
      chatRecentSessionsTitle = context.getString(R.string.chat_recent_sessions_title),
      chatNewSessionLabel = context.getString(R.string.chat_new_session),
      chatDefaultSessionTitle = context.getString(R.string.chat_default_session_title),
      chatMessagesBadge = { count ->
        context.getString(R.string.chat_messages_badge, count)
      },
      chatSummaryReplyInProgress = context.getString(R.string.chat_summary_reply_in_progress),
      chatSummaryStartNewSession = context.getString(R.string.chat_summary_start_new_session),
      chatSummaryRestored = context.getString(R.string.chat_summary_restored),
      skillInstalled = { skillId ->
        context.getString(R.string.skills_message_installed, skillId)
      },
      skillRemoved = { skillId ->
        context.getString(R.string.skills_message_removed, skillId)
      },
      skillsReloaded = context.getString(R.string.skills_message_reloaded),
      composerPlaceholder = context.getString(R.string.chat_message_opencray),
      agentThinking = context.getString(R.string.chat_agent_thinking),
      agentCancelled = context.getString(R.string.chat_agent_cancelled),
      agentMissingLlm = context.getString(R.string.chat_agent_missing_llm),
      agentEmptyAnswer = context.getString(
        R.string.chat_agent_failed,
        "The model returned an empty answer.",
      ),
      agentFailed = { detail ->
        context.getString(R.string.chat_agent_failed, detail)
      },
    )
  }
}

internal data class HostRuntimeStrings(
  val localeTag: String,
  val shellHostLabel: String,
  val shellHostSummary: String,
  val chatScreenTitle: String,
  val chatModeLabel: String,
  val chatSessionButtonLabel: String,
  val chatRecentSessionsEyebrow: String,
  val chatRecentSessionsTitle: String,
  val chatNewSessionLabel: String,
  val chatDefaultSessionTitle: String,
  val chatMessagesBadge: (Int) -> String,
  val chatSummaryReplyInProgress: String,
  val chatSummaryStartNewSession: String,
  val chatSummaryRestored: String,
  val skillInstalled: (String) -> String,
  val skillRemoved: (String) -> String,
  val skillsReloaded: String,
  val composerPlaceholder: String,
  val agentThinking: String,
  val agentCancelled: String,
  val agentMissingLlm: String,
  val agentEmptyAnswer: String,
  val agentFailed: (String) -> String,
)

internal fun interface MainThreadPoster {
  fun post(action: () -> Unit)
}

internal class HandlerMainThreadPoster(
  private val handler: Handler,
) : MainThreadPoster {
  override fun post(action: () -> Unit) {
    handler.post(action)
  }
}

internal object ImmediateMainThreadPoster : MainThreadPoster {
  override fun post(action: () -> Unit) {
    action()
  }
}
