package com.opencray.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.opencray.app.facade.llm.EmptyLlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmProviderOptionSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.LocalLlmConfigFacade
import com.opencray.app.facade.llm.SaveCustomLlmProviderRequest
import com.opencray.app.facade.llm.SaveLlmConfigRequest
import com.opencray.app.facade.llm.ValidateLlmConfigRequest
import com.opencray.runtime.memory.MemoryCandidateExtractor
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
import com.opencray.app.facade.search.EmptyNetworkSearchConfigFacade
import com.opencray.app.facade.search.LocalNetworkSearchConfigFacade
import com.opencray.app.facade.search.NetworkSearchConfigFacade
import com.opencray.app.facade.search.NetworkSearchConfigSnapshot
import com.opencray.app.facade.search.NetworkSearchSlotSnapshot
import com.opencray.app.facade.search.SaveNetworkSearchConfigRequest
import com.opencray.app.facade.search.SaveNetworkSearchSlotRequest
import com.opencray.app.facade.safety.EmptySafetySettingsFacade
import com.opencray.app.facade.safety.LocalSafetySettingsFacade
import com.opencray.app.facade.safety.SaveSafetySettingsRequest
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.app.facade.safety.SafetySettingsLocationSnapshot
import com.opencray.app.facade.safety.SafetySettingsSnapshot
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
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayApprovalEvent
import com.opencray.runtime.OpenCrayApprovalPhase
import com.opencray.runtime.OpenCrayAgentRunEvent
import com.opencray.runtime.OpenCrayAssistantEvent
import com.opencray.runtime.OpenCrayCancellationEvent
import com.opencray.runtime.OpenCrayLifecycleEvent
import com.opencray.runtime.OpenCrayMemoryRetrievalEvent
import com.opencray.runtime.OpenCrayMemoryWriteEvent
import com.opencray.runtime.OpenCrayProgressEvent
import com.opencray.runtime.OpenCrayRunLifecyclePhase
import com.opencray.runtime.OpenCrayToolCallEvent
import com.opencray.runtime.OpenCrayToolResultEvent
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.policy.SafetyAutomationMode
import com.opencray.policy.SafetySettingsMetadataKeys
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemorySoulExtensionKeys
import com.opencray.runtime.skills.SkillInstallManifestStore
import com.opencray.runtime.skills.SkillPackageManager
import com.opencray.runtime.soul.MemoryBackedSoulProfileResolver
import com.opencray.runtime.soul.InteractionPreferenceDebugProjection
import com.opencray.runtime.soul.InteractionPreferenceState
import com.opencray.runtime.soul.PreferenceAxisState
import com.opencray.runtime.soul.PreferredAddressState
import com.opencray.runtime.soul.RuntimeSoulProfileSeedFactory
import com.opencray.runtime.soul.RelationshipState
import com.opencray.runtime.soul.RelationshipStateDebugProjection
import com.opencray.runtime.soul.SoulGateCheck
import com.opencray.runtime.soul.SoulProfile
import com.opencray.runtime.soul.SoulProfileExtensionKeys
import com.opencray.runtime.soul.SoulPlasticity
import com.opencray.runtime.soul.SoulProfileResolver
import java.io.File
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.opencray.app.R

internal class OpenCrayHostRuntime private constructor(
  private val appContext: Context?,
  private val stateStore: AppShellStateStore,
  private val chatSessionStore: ChatSessionLocalStore,
  private var settingsFacade: SettingsFacade,
  private var networkSearchConfigFacade: NetworkSearchConfigFacade,
  private var llmConfigFacade: LlmConfigFacade,
  private var personalizationFacade: PersonalizationFacade,
  private val personalizationLocalStore: PersonalizationLocalStore? = null,
  private val workspaceSoulProfileStore: WorkspaceSoulProfileStore = WorkspaceSoulProfileStore(),
  private var mcpSettingsFacade: McpSettingsFacade,
  private var safetySettingsFacade: SafetySettingsFacade,
  private var skillsFacade: SkillsFacade,
  private val workspaceRootProvider: (() -> Path)?,
  private val approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot = {
    ApprovedReadRootsSnapshot(
      roots = emptySet(),
      summary = "workspace=unavailable",
    )
  },
  private val workspaceSnapshotProvider: () -> Map<String, Any?>,
  private val sessionRuntimeManager: AgentSessionRuntimeManager,
  private val transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage> = { emptyList() },
  private val approvalRegistry: AgentTaskApprovalRegistry,
  private val memoryIngestionCoordinator: ChatMemoryIngestionCoordinator? = null,
  private val approvalReplayRecorder: (String, String, String, String?, Boolean) -> Unit = { _, _, _, _, _ -> },
  private val approvalApprovedReplayRecorder: (String, String, String, String?, Boolean) -> Unit = { _, _, _, _, _ -> },
  private val runCancellationReplayRecorder: (String, String, String, String?) -> Unit = { _, _, _, _ -> },
  private val terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit = { _, _ -> },
  private var strings: HostRuntimeStrings,
  private val mainThreadPoster: MainThreadPoster,
) {
  private val lock = Any()
  private val soulProfileResolver = SoulProfileResolver()
  private val runtimeSoulProfileSeedFactory = RuntimeSoulProfileSeedFactory()
  private val memoryBackedSoulProfileResolver = MemoryBackedSoulProfileResolver()
  private val shellListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val settingsOverviewListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val skillsListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val chatListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val chatRuntimeListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val pendingApprovalsBySession = linkedMapOf<String, LinkedHashMap<String, PendingApprovalSnapshot>>()
  private val runtimeEventsBySession = linkedMapOf<String, ArrayDeque<OpenCrayAgentRunEvent>>()
  private val unreadChatMessageCountsBySession = linkedMapOf<String, Int>()

  init {
    sessionRuntimeManager.observe(
      object : AgentSessionRuntimeListener {
        override fun onTaskStarted(sessionId: String, task: AgentTask) {
          val shouldEmit = synchronized(lock) { hasSessionLocked(sessionId) }
          if (!shouldEmit) {
            return
          }
          emitChatSnapshot()
          emitChatRuntimeSnapshot()
        }

        override fun onTaskFinished(sessionId: String, task: AgentTask, result: ExecutionResult) {
          val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
            ?.takeIf(String::isNotBlank)
          val completedTurn = synchronized(lock) {
            if (!hasSessionLocked(sessionId)) {
              return@synchronized null
            }
            val activeSessionIdBeforeUpdate = chatSessionStore.loadState().activeSession.sessionId
            val finalText = finalTextFor(result)
            if (isApprovalRequiredResult(result)) {
              val approval = recordPendingApprovalLocked(
                sessionId = sessionId,
                task = task,
                result = result,
              )
              recordRuntimeEventLocked(
                sessionId = sessionId,
                event = approvalRequiredRuntimeEvent(
                  approval = approval,
                  emittedAtEpochMs = result.finishedAtEpochMs,
                ),
              )
            } else {
              clearPendingApprovalLocked(sessionId = sessionId, taskId = task.id)
              approvalRegistry.clear(sessionId = sessionId, taskId = task.id)
            }
            pendingMessageId?.let { messageId ->
              chatSessionStore.replaceMessage(
                sessionId = sessionId,
                messageId = messageId,
                role = ChatTranscriptRole.ASSISTANT,
                text = finalText,
              )
            }
            incrementUnreadIfBackgroundUpdateLocked(
              sessionId = sessionId,
              activeSessionId = activeSessionIdBeforeUpdate,
              text = finalText,
            )
            CompletedTurnForMemoryIngestion(
              sessionId = sessionId,
              task = task,
              result = result,
              userInput = resolvedUserTextLocked(
                sessionId = sessionId,
                pendingMessageId = pendingMessageId,
                task = task,
              ),
              assistantOutput = finalText,
              toolObservations = successfulToolObservationsLocked(sessionId = sessionId, task = task),
            )
          } ?: return
          val ingestionSummary = runCatching {
            memoryIngestionCoordinator?.ingestCompletedTurn(
              sessionId = completedTurn.sessionId,
              task = completedTurn.task,
              result = completedTurn.result,
              userInput = completedTurn.userInput,
              assistantOutput = completedTurn.assistantOutput,
              toolObservations = completedTurn.toolObservations,
            )
          }.getOrNull()
          if (ingestionSummary != null && !ingestionSummary.isEmpty) {
            synchronized(lock) {
              if (!hasSessionLocked(completedTurn.sessionId)) {
                return@synchronized
              }
              recordRuntimeEventLocked(
                sessionId = completedTurn.sessionId,
                event = OpenCrayMemoryWriteEvent(
                  runId = runIdFor(completedTurn.task),
                  taskId = completedTurn.task.id,
                  writtenRecordIds = ingestionSummary.writtenRecords.map { record -> record.id },
                  writtenKinds = ingestionSummary.writtenRecords.mapNotNull { record ->
                    record.extensions["kind"]
                  }.distinct().sorted(),
                  resolvedRecordIds = ingestionSummary.resolvedRecords.map { record -> record.id },
                  reaffirmedRecordIds = ingestionSummary.reaffirmedRecords.map { record -> record.id },
                  expiredRecordIds = ingestionSummary.expiredRecordIds,
                  emittedAtEpochMs = completedTurn.result.finishedAtEpochMs,
                ),
              )
            }
          }
          repairTerminalReplay(completedTurn.sessionId)
          emitChatSnapshot()
          emitChatRuntimeSnapshot()
        }

        override fun onRunEvent(sessionId: String, task: AgentTask, event: OpenCrayAgentRunEvent) {
          val shouldEmit = synchronized(lock) {
            if (!hasSessionLocked(sessionId)) {
              return@synchronized false
            }
            recordRuntimeEventLocked(sessionId = sessionId, event = event)
            true
          }
          if (!shouldEmit) {
            return
          }
          emitChatSnapshot()
          emitChatRuntimeSnapshot()
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

  fun loadNetworkSearchConfig(): Map<String, Any?> =
    synchronized(lock) { networkSearchConfigFacade.load() }.toMap()

  fun saveNetworkSearchConfig(
    slots: List<Map<String, Any?>>,
  ): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      networkSearchConfigFacade.save(
        SaveNetworkSearchConfigRequest(
          slots = slots.map { slot ->
            SaveNetworkSearchSlotRequest(
              id = slot["id"]?.toString().orEmpty(),
              providerId = slot["providerId"]?.toString().orEmpty(),
              label = slot["label"]?.toString().orEmpty(),
              apiKey = slot["apiKey"]?.toString().orEmpty(),
              enabled = slot["enabled"] as? Boolean ?: true,
            )
          },
        ),
      )
    }
    emitSettingsOverview()
    return snapshot.toMap()
  }

  fun loadLlmConfig(): Map<String, Any?> =
    synchronized(lock) { llmConfigFacade.load() }.toMap()

  fun saveLlmConfig(
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
  ): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      llmConfigFacade.save(
        SaveLlmConfigRequest(
          enabled = enabled,
          providerId = providerId,
          selectedProviderOptionId = selectedProviderOptionId,
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

  fun saveCustomLlmProvider(
    selectedProviderOptionId: String,
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
      llmConfigFacade.saveCustomProvider(
        SaveCustomLlmProviderRequest(
          selectedProviderOptionId = selectedProviderOptionId,
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

  fun loadSafetySettings(): Map<String, Any?> =
    synchronized(lock) { safetySettingsFacade.load() }.toMap()

  fun saveSafetySettings(
    automationModeId: String,
    rollbackJournalEnabled: Boolean,
    maxFilesPerBatch: Int,
    maxAgentTurns: Int = SafetySettingsState.DEFAULT_MAX_AGENT_TURNS,
    maxToolCalls: Int = SafetySettingsState.DEFAULT_MAX_TOOL_CALLS,
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
  ): Map<String, Any?> {
    val snapshot = synchronized(lock) {
      safetySettingsFacade.save(
        SaveSafetySettingsRequest(
          automationModeId = automationModeId,
          rollbackJournalEnabled = rollbackJournalEnabled,
          maxFilesPerBatch = maxFilesPerBatch,
          maxAgentTurns = maxAgentTurns,
          maxToolCalls = maxToolCalls,
          undoWindowHours = undoWindowHours,
          fileChangesPolicyId = fileChangesPolicyId,
          fileDeletesPolicyId = fileDeletesPolicyId,
          shellCommandsPolicyId = shellCommandsPolicyId,
          externalAccessModeId = externalAccessModeId,
          photoLibraryEnabled = photoLibraryEnabled,
          downloadsEnabled = downloadsEnabled,
          documentsEnabled = documentsEnabled,
          recordingsEnabled = recordingsEnabled,
          workspaceAccessProfileId = workspaceAccessProfileId,
          readOnlyOutsideWorkspace = readOnlyOutsideWorkspace,
        ),
      )
    }
    emitChatSnapshot()
    return snapshot.toMap()
  }

  fun loadSkillsSnapshot(query: String = ""): Map<String, Any?> =
    synchronized(lock) { skillsFacade.loadSnapshot(query) }.toMap()

  fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = skillsListeners,
      initialPayload = loadSkillsSnapshot(),
      listener = listener,
    )

  fun loadFilesSnapshot(): Map<String, Any?> = synchronized(lock) {
    workspaceSnapshotProvider()
  }

  fun loadWorkspaceImagePreview(
    relativePath: String,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceImagePreviewer.loadPreview(
      workspaceRoot = requireWorkspaceRoot(),
      relativePath = relativePath,
    )
  }

  fun loadWorkspaceTextPreview(
    relativePath: String,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceTextPreviewer.loadPreview(
      workspaceRoot = requireWorkspaceRoot(),
      relativePath = relativePath,
    )
  }

  fun loadWorkspaceTextDocument(
    relativePath: String,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceTextDocumentStore.loadDocument(
      workspaceRoot = requireWorkspaceRoot(),
      relativePath = relativePath,
    )
  }

  fun createWorkspaceFolder(
    parentRelativePath: String,
    name: String,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceFileOperations.createDirectory(
      workspaceRoot = requireWorkspaceRoot(),
      parentRelativePath = parentRelativePath,
      name = name,
    )
    workspaceSnapshotProvider()
  }

  fun createWorkspaceTextFile(
    parentRelativePath: String,
    name: String,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceTextDocumentStore.createFile(
      workspaceRoot = requireWorkspaceRoot(),
      parentRelativePath = parentRelativePath,
      name = name,
    )
    workspaceSnapshotProvider()
  }

  fun renameWorkspaceEntry(
    targetRelativePath: String,
    newName: String,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceFileOperations.renameEntry(
      workspaceRoot = requireWorkspaceRoot(),
      targetRelativePath = targetRelativePath,
      newName = newName,
    )
    workspaceSnapshotProvider()
  }

  fun deleteWorkspaceEntries(
    relativePaths: List<String>,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceFileOperations.deleteEntries(
      workspaceRoot = requireWorkspaceRoot(),
      relativePaths = relativePaths,
    )
    workspaceSnapshotProvider()
  }

  fun saveWorkspaceTextDocument(
    targetRelativePath: String,
    content: String,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceTextDocumentStore.saveDocument(
      workspaceRoot = requireWorkspaceRoot(),
      targetRelativePath = targetRelativePath,
      content = content,
    )
    workspaceSnapshotProvider()
  }

  fun pasteWorkspaceEntries(
    sourceRelativePaths: List<String>,
    destinationRelativePath: String,
    move: Boolean,
  ): Map<String, Any?> = synchronized(lock) {
    AppAgentWorkspaceFileOperations.pasteEntries(
      workspaceRoot = requireWorkspaceRoot(),
      sourceRelativePaths = sourceRelativePaths,
      destinationRelativePath = destinationRelativePath,
      move = move,
    )
    workspaceSnapshotProvider()
  }

  fun shareWorkspaceEntries(
    relativePaths: List<String>,
  ) {
    val context = requireNotNull(appContext) {
      "Workspace sharing is unavailable."
    }
    val shareAction = {
      synchronized(lock) {
        AppAgentWorkspaceSharer.shareEntries(
          appContext = context,
          workspaceRoot = requireWorkspaceRoot(),
          relativePaths = relativePaths,
        )
      }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      shareAction()
      return
    }
    val completion = CountDownLatch(1)
    var failure: Throwable? = null
    mainThreadPoster.post {
      runCatching(shareAction)
        .onFailure { throwable -> failure = throwable }
      completion.countDown()
    }
    completion.await()
    failure?.let { throwable -> throw throwable }
  }

  fun showNativeToast(message: String) {
    val normalizedMessage = message.trim()
    if (normalizedMessage.isEmpty()) {
      return
    }
    val context = appContext ?: return
    val showAction = {
      Toast.makeText(context, normalizedMessage, Toast.LENGTH_SHORT).show()
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      showAction()
      return
    }
    val completion = CountDownLatch(1)
    var failure: Throwable? = null
    mainThreadPoster.post {
      runCatching(showAction)
        .onFailure { throwable -> failure = throwable }
      completion.countDown()
    }
    completion.await()
    failure?.let { throwable -> throw throwable }
  }

  private fun requireWorkspaceRoot(): Path =
    requireNotNull(workspaceRootProvider?.invoke()) {
      "Workspace file operations are unavailable."
    }.toAbsolutePath().normalize()

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
    val runs = sessionRuntimeManager.forSession(activeSession.sessionId).listRuns()
    val recentEvents = mergedRuntimeEventsLocked(
      sessionId = activeSession.sessionId,
      runs = runs,
    )
    val renderedMessages = renderedChatMessagesLocked(
      visibleMessages = visibleMessages,
      runs = runs,
      runtimeEvents = recentEvents,
    )
    val pendingCount = pendingTaskCount(activeSession.sessionId)
    val pendingApprovals = pendingApprovalsForSession(activeSession.sessionId)
    val activeSessionTitle = displaySessionTitle(activeSession.title)
    mapOf(
      "screenTitle" to strings.chatScreenTitle,
      "modeLabel" to currentChatModeLabelLocked(),
      "sessionButtonLabel" to strings.chatSessionButtonLabel,
      "composerPlaceholder" to strings.composerPlaceholder,
      "summary" to mapOf(
        "title" to activeSessionTitle,
        "badge" to strings.chatMessagesBadge(renderedMessages.size),
        "body" to if (pendingApprovals.isNotEmpty()) {
          strings.chatSummaryApprovalRequired
        } else if (pendingCount > 0) {
          strings.chatSummaryReplyInProgress
        } else if (visibleMessages.isEmpty()) {
          strings.chatSummaryStartNewSession
        } else {
          strings.chatSummaryRestored
        },
      ),
      "messages" to renderedMessages,
      "pendingApprovals" to pendingApprovals.map { approval ->
        mapOf(
          "runId" to approval.runId,
          "taskId" to approval.taskId,
          "pendingMessageId" to approval.pendingMessageId,
          "toolName" to approval.toolName,
          "requestSummary" to approval.requestSummary,
          "primaryDetail" to approval.primaryDetail,
          "pathDetails" to approval.pathDetails,
          "workingDirectory" to approval.workingDirectory,
          "reason" to approval.reason,
          "message" to approval.message,
          "risk" to if (approval.isHighRisk) "high_risk" else "standard",
          "isHighRisk" to approval.isHighRisk,
          "title" to approval.title,
          "body" to approval.body,
          "approveLabel" to strings.chatApprovalApproveLabel,
          "rejectLabel" to strings.chatApprovalRejectLabel,
        )
      },
      "drawer" to mapOf(
        "eyebrow" to strings.chatRecentSessionsEyebrow,
        "title" to strings.chatRecentSessionsTitle,
        "ctaLabel" to strings.chatNewSessionLabel,
        "sessions" to chatState.sessions.map { session ->
          val unreadCount = unreadCountForSessionLocked(
            sessionId = session.sessionId,
            activeSessionId = activeSession.sessionId,
          )
          mapOf(
            "sessionId" to session.sessionId,
            "title" to displaySessionTitle(session.title),
            "preview" to sanitizeDrawerPreviewText(session.lastMessagePreview),
            "meta" to strings.chatMessagesBadge(session.messageCount),
            "isSelected" to (session.sessionId == activeSession.sessionId),
            "unreadCount" to unreadCount,
          )
        },
      ),
      "runtimeActivity" to runtimeActivitySnapshotMap(
        sessionId = activeSession.sessionId,
        runs = runs,
        recentEvents = recentEvents,
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

  fun loadChatRuntimeSnapshot(): Map<String, Any?> = synchronized(lock) {
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    runtimeActivitySnapshotLocked(activeSessionId)
  }

  fun loadChatRunSnapshot(runId: String): Map<String, Any?>? = synchronized(lock) {
    findRunSnapshotLocked(runId)?.let(::runSnapshotToMap)
  }

  fun waitForChatRun(
    runId: String,
    timeoutMs: Long = DEFAULT_RUN_WAIT_TIMEOUT_MS,
  ): Map<String, Any?>? = waitForRunSnapshot(runId, timeoutMs)?.let(::runSnapshotToMap)

  fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit =
    observeWithInitial(
      listeners = chatRuntimeListeners,
      initialPayload = loadChatRuntimeSnapshot(),
      listener = listener,
    )

  fun loadMemoryDebugSnapshot(): Map<String, Any?> = synchronized(lock) {
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val workspaceId = currentWorkspaceIdLocked()
    val observedAtEpochMs = System.currentTimeMillis()
    val records = personalizationLocalStore
      ?.listMemoryRecords()
      .orEmpty()
      .sortedWith(
        compareByDescending<MemoryRecord> { record -> record.updatedAtEpochMs }
          .thenBy { record -> record.id },
      )
    mapOf(
      "sessionId" to sessionId,
      "workspaceId" to workspaceId,
      "observedAtEpochMs" to observedAtEpochMs,
      "records" to records.map { record ->
        memoryDebugRecordToMap(record = record, observedAtEpochMs = observedAtEpochMs)
      },
    )
  }

  fun loadSoulDebugSnapshot(): Map<String, Any?> = synchronized(lock) {
    val sessionId = chatSessionStore.loadState().activeSession.sessionId
    val workspaceId = currentWorkspaceIdLocked()
    val workspaceRoot = currentWorkspaceRootLocked()
    val observedAtEpochMs = System.currentTimeMillis()
    val storedSoulDocument = workspaceSoulProfileStore.loadSoulDocument(workspaceRoot)
    val storedSoulProfile = storedSoulDocument?.profile
    val baseRuntimeSoul = storedSoulProfile?.toRuntimeSoulProfile()
    val baseResolvedSoul = resolveSoulProfile(baseRuntimeSoul)
    val allMemoryRecords = personalizationLocalStore?.listMemoryRecords().orEmpty()
    val overlayRecords = applicableSoulOverlayRecords(
      records = allMemoryRecords,
      sessionId = sessionId,
      workspaceId = workspaceId,
    )
    val overlayDebug = memoryBackedSoulProfileResolver.inspectOverlay(
      baseProfile = baseRuntimeSoul,
      records = allMemoryRecords,
      sessionId = sessionId,
      workspaceId = workspaceId,
    )
    val effectiveRuntimeSoul = overlayDebug.effectiveProfile
    val effectiveResolvedSoul = resolveSoulProfile(effectiveRuntimeSoul)
    mapOf(
      "sessionId" to sessionId,
      "workspaceId" to workspaceId,
      "observedAtEpochMs" to observedAtEpochMs,
      "storedSoul" to storedSoulProfile?.let { profile ->
        storedSoulProfileToMap(
          profile = profile,
          document = storedSoulDocument,
        )
      },
      "baseSoul" to soulProfileToMap(
        resolvedProfile = baseResolvedSoul,
        runtimeProfile = baseRuntimeSoul,
      ),
      "effectiveSoul" to soulProfileToMap(
        resolvedProfile = effectiveResolvedSoul,
        runtimeProfile = effectiveRuntimeSoul,
      ),
      "overlayRecords" to overlayRecords
        .sortedWith(soulOverlayDisplayComparator())
        .map { record ->
          memoryDebugRecordToMap(record = record, observedAtEpochMs = observedAtEpochMs)
        },
      "interactionPreferenceDebug" to overlayDebug.interactionPreferenceDebug
        ?.let(::interactionPreferenceDebugToMap),
      "relationshipStateDebug" to overlayDebug.relationshipStateDebug
        ?.let(::relationshipStateDebugToMap),
      "fieldSources" to soulFieldSources(
        baseRuntimeSoul = baseRuntimeSoul,
        effectiveRuntimeSoul = effectiveRuntimeSoul,
        baseResolvedSoul = baseResolvedSoul,
        effectiveResolvedSoul = effectiveResolvedSoul,
        overlayRecords = overlayRecords,
        interactionPreferenceDebug = overlayDebug.interactionPreferenceDebug,
        relationshipStateDebug = overlayDebug.relationshipStateDebug,
      ),
    )
  }

  fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> = synchronized(lock) {
    val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
    val workspaceId = currentWorkspaceIdLocked()
    val observedAtEpochMs = System.currentTimeMillis()
    val records = personalizationLocalStore
      ?.listMemoryRecords()
      .orEmpty()
      .sortedWith(
        compareByDescending<MemoryRecord> { record -> record.updatedAtEpochMs }
          .thenBy { record -> record.id },
      )
    val allRuns = chatSessionStore.loadState().sessions
      .mapTo(linkedSetOf()) { session -> session.sessionId }
      .flatMap { sessionId ->
        sessionRuntimeManager.forSession(sessionId).listRuns()
      }
    val runsByTaskId = allRuns.associateBy(AgentRunSnapshot::taskId)
    val runsById = allRuns.associateBy(AgentRunSnapshot::runId)
    val promptRecallsByRecordId = linkedMapOf<String, LinkedHashMap<String, Map<String, Any?>>>()
    val toolRetrievalsByRecordId = linkedMapOf<String, LinkedHashMap<String, Map<String, Any?>>>()
    val maintenanceByRecordId = linkedMapOf<String, LinkedHashMap<String, Map<String, Any?>>>()

    allRuns.forEach { run ->
      parseSelectedMemoryTrace(run.resultMetadata["contextMemorySelectedSummary"].orEmpty())
        .forEach memorySelection@{ selected ->
          val recordId = selected["id"] as? String ?: return@memorySelection
          rememberDebugLink(
            target = promptRecallsByRecordId,
            recordId = recordId,
            uniqueKey = "prompt:${run.runId}:$recordId",
            payload = buildMap {
              put("occurredAtEpochMs", run.updatedAtEpochMs)
              put("run", debugRunLinkToMap(run))
              selected["score"]?.let { score -> put("score", score) }
              val matchedTerms = selected["matchedTerms"] as? List<*>
              if (!matchedTerms.isNullOrEmpty()) {
                put("matchedTerms", matchedTerms)
              }
            },
          )
        }
      splitDebugCsv(run.resultMetadata["contextMemoryFlushWrittenRecordIds"])
        .forEach { recordId ->
          rememberDebugLink(
            target = maintenanceByRecordId,
            recordId = recordId,
            uniqueKey = "flush:${run.runId}:$recordId",
            payload = buildMap {
              put("action", "flush_written")
              put("occurredAtEpochMs", run.updatedAtEpochMs)
              put("run", debugRunLinkToMap(run))
            },
          )
        }
      (run.lastEvent as? OpenCrayMemoryWriteEvent)
        ?.let { event ->
          rememberMemoryWriteActions(
            target = maintenanceByRecordId,
            run = run,
            event = event,
          )
        }
      (run.lastEvent as? OpenCrayMemoryRetrievalEvent)
        ?.let { event ->
          rememberMemoryRetrievalLinks(
            target = toolRetrievalsByRecordId,
            run = run,
            event = event,
          )
        }
    }

    runtimeEventsBySession.values
      .flatten()
      .forEach runtimeEvent@{ event ->
        when (event) {
          is OpenCrayMemoryWriteEvent -> {
            val run = runsById[event.runId] ?: return@runtimeEvent
            rememberMemoryWriteActions(
              target = maintenanceByRecordId,
              run = run,
              event = event,
            )
          }

          is OpenCrayMemoryRetrievalEvent -> {
            val run = runsById[event.runId] ?: return@runtimeEvent
            rememberMemoryRetrievalLinks(
              target = toolRetrievalsByRecordId,
              run = run,
              event = event,
            )
          }

          else -> Unit
        }
      }

    mapOf(
      "sessionId" to activeSessionId,
      "workspaceId" to workspaceId,
      "observedAtEpochMs" to observedAtEpochMs,
      "records" to records.map { record ->
        val metadata = debugMemoryMetadata(record)
        mapOf(
          "recordId" to record.id,
          "sourceSessionId" to metadata?.sourceSessionId.orEmpty(),
          "sourceTaskId" to metadata?.sourceTaskId.orEmpty(),
          "sourceRun" to metadata
            ?.sourceTaskId
            ?.let(runsByTaskId::get)
            ?.let(::debugRunLinkToMap),
          "promptRecalls" to finalizeDebugLinks(promptRecallsByRecordId[record.id]),
          "toolRetrievals" to finalizeDebugLinks(toolRetrievalsByRecordId[record.id]),
          "maintenanceActions" to finalizeDebugLinks(maintenanceByRecordId[record.id]),
        )
      },
    )
  }

  fun createChatSession() {
    val sessionId = synchronized(lock) {
      val createdState = chatSessionStore.createSession()
      clearUnreadCountLocked(createdState.activeSession.sessionId)
      sessionRuntimeManager.forSession(createdState.activeSession.sessionId).resume()
      createdState.activeSession.sessionId
    }
    repairTerminalReplay(sessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun selectChatSession(sessionId: String) {
    val resolvedSessionId = synchronized(lock) {
      val selectedState = chatSessionStore.selectSession(sessionId)
      clearUnreadCountLocked(selectedState.activeSession.sessionId)
      sessionRuntimeManager.forSession(selectedState.activeSession.sessionId).resume()
      selectedState.activeSession.sessionId
    }
    repairTerminalReplay(resolvedSessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun copyChatSession(sessionId: String) {
    val copiedSessionId = synchronized(lock) {
      val copiedState = chatSessionStore.copySession(sessionId)
      clearUnreadCountLocked(copiedState.activeSession.sessionId)
      sessionRuntimeManager.forSession(copiedState.activeSession.sessionId).resume()
      copiedState.activeSession.sessionId
    }
    repairTerminalReplay(copiedSessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  ) {
    val branchedSessionId = synchronized(lock) {
      if (!hasSessionLocked(sessionId) || messageId.isBlank()) {
        return@synchronized null
      }
      val branchedState = chatSessionStore.branchSessionFromMessage(sessionId, messageId)
      clearUnreadCountLocked(branchedState.activeSession.sessionId)
      sessionRuntimeManager.forSession(branchedState.activeSession.sessionId).resume()
      branchedState.activeSession.sessionId
    } ?: return
    repairTerminalReplay(branchedSessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun deleteChatSession(sessionId: String) {
    val resolvedSessionId = synchronized(lock) {
      if (!hasSessionLocked(sessionId)) {
        return@synchronized null
      }
      discardSessionLocked(sessionId)
      val updatedState = chatSessionStore.deleteSession(sessionId)
      clearUnreadCountLocked(updatedState.activeSession.sessionId)
      sessionRuntimeManager.forSession(updatedState.activeSession.sessionId).resume()
      updatedState.activeSession.sessionId
    } ?: return
    repairTerminalReplay(resolvedSessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    val resolvedSessionId = synchronized(lock) {
      if (!hasSessionLocked(sessionId) || messageId.isBlank()) {
        return@synchronized null
      }
      cancelRunsForPendingMessageIdsLocked(sessionId, setOf(messageId))
      chatSessionStore.deleteMessage(sessionId, messageId)
      sessionId
    } ?: return
    repairTerminalReplay(resolvedSessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun recallChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    val resolvedSessionId = synchronized(lock) {
      if (!hasSessionLocked(sessionId) || messageId.isBlank()) {
        return@synchronized null
      }
      val session = chatSessionStore.loadSession(sessionId) ?: return@synchronized null
      val recallIndex = session.messages.indexOfFirst { message -> message.messageId == messageId }
      if (recallIndex < 0 || session.messages[recallIndex].role != ChatTranscriptRole.USER) {
        return@synchronized null
      }
      cancelRunsForPendingMessageIdsLocked(
        sessionId = sessionId,
        pendingMessageIds = session.messages
          .drop(recallIndex)
          .mapTo(linkedSetOf()) { message -> message.messageId },
      )
      chatSessionStore.recallMessageCascade(sessionId, messageId)
      sessionId
    } ?: return
    repairTerminalReplay(resolvedSessionId)
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun approveChatApproval(taskId: String) {
    synchronized(lock) {
      val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
      val approval = pendingApprovalForIdentifier(activeSessionId, taskId)
        ?: error("Pending approval '$taskId' is unavailable.")
      approvalRegistry.markApproved(activeSessionId, approval.taskId, toolName = approval.toolName)
      val resumed = sessionRuntimeManager.forSession(activeSessionId).requestResumeTask(approval.taskId)
      if (!resumed) {
        approvalRegistry.clear(activeSessionId, approval.taskId)
        error("Unable to resume pending approval '$taskId'.")
      }
      approvalApprovedReplayRecorder(
        activeSessionId,
        approval.taskId,
        approval.runId,
        approval.toolName,
        approval.isHighRisk,
      )
      clearPendingApprovalLocked(activeSessionId, approval.taskId)
      recordRuntimeEventLocked(
        sessionId = activeSessionId,
        event = approvalResultRuntimeEvent(
          approval = approval,
          phase = OpenCrayApprovalPhase.APPROVED,
          emittedAtEpochMs = System.currentTimeMillis(),
        ),
      )
      approval.pendingMessageId?.let { pendingMessageId ->
        chatSessionStore.replaceMessage(
          sessionId = activeSessionId,
          messageId = pendingMessageId,
          role = ChatTranscriptRole.ASSISTANT,
          text = strings.agentThinking,
        )
      }
      chatSessionStore.appendMessage(
        sessionId = activeSessionId,
        role = ChatTranscriptRole.TOOL,
        text = strings.chatApprovalApproved,
      )
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun rejectChatApproval(taskId: String) {
    synchronized(lock) {
      val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
      val approval = pendingApprovalForIdentifier(activeSessionId, taskId)
        ?: error("Pending approval '$taskId' is unavailable.")
      approvalReplayRecorder(
        activeSessionId,
        approval.taskId,
        approval.runId,
        approval.toolName,
        approval.isHighRisk,
      )
      approvalRegistry.markRejected(activeSessionId, approval.taskId)
      sessionRuntimeManager.forSession(activeSessionId).requestCancel(approval.taskId)
      clearPendingApprovalLocked(activeSessionId, approval.taskId)
      recordRuntimeEventLocked(
        sessionId = activeSessionId,
        event = approvalResultRuntimeEvent(
          approval = approval,
          phase = OpenCrayApprovalPhase.REJECTED,
          emittedAtEpochMs = System.currentTimeMillis(),
        ),
      )
      chatSessionStore.appendMessage(
        sessionId = activeSessionId,
        role = ChatTranscriptRole.TOOL,
        text = strings.chatApprovalRejected,
      )
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun cancelChatRun(taskIdOrRunId: String) {
    synchronized(lock) {
      val run = findRunSnapshotForIdentifierLocked(taskIdOrRunId)
        ?: error("Run '$taskIdOrRunId' is unavailable.")
      val approval = pendingApprovalForIdentifier(run.sessionId, run.taskId)
      val cancelled = sessionRuntimeManager.forSession(run.sessionId).requestCancel(run.taskId)
      if (!cancelled) {
        error("Unable to cancel run '$taskIdOrRunId'.")
      }
      runCancellationReplayRecorder(
        run.sessionId,
        run.taskId,
        run.runId,
        approval?.toolName,
      )
      clearPendingApprovalLocked(run.sessionId, run.taskId)
      approvalRegistry.clear(run.sessionId, run.taskId)
      recordRuntimeEventLocked(
        sessionId = run.sessionId,
        event = cancellationRuntimeEvent(
          run = run,
          approval = approval,
          emittedAtEpochMs = System.currentTimeMillis(),
        ),
      )
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
  }

  fun submitChatMessage(text: String): Map<String, Any?>? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) {
      return null
    }

    val submission = synchronized(lock) {
      val safetySettings = safetySettingsFacade.load()
      val activeState = chatSessionStore.loadState()
      val sessionId = activeState.activeSession.sessionId
      val handle = sessionRuntimeManager.forSession(sessionId)
      val pendingMessageId = chatSessionStore.reserveMessageId(ChatTranscriptRole.ASSISTANT)
      val submittedRun = handle.submitPrompt(
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
        ),
        metadata = safetyMetadataForTask(safetySettings),
      )
      try {
        chatSessionStore.appendSubmittedTurn(
          sessionId = sessionId,
          userText = trimmed,
          assistantMessageId = pendingMessageId,
          assistantPlaceholderText = strings.agentThinking,
        )
      } catch (throwable: Throwable) {
        handle.requestCancel(submittedRun.taskId)
        throw throwable
      }
      handle.ensureProcessing()
      submittedRun
    }
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
    return runSubmissionToMap(submission)
  }

  private fun ensureActiveSessionResumed() {
    val activeSessionId = synchronized(lock) { chatSessionStore.loadState().activeSession.sessionId }
    sessionRuntimeManager.forSession(activeSessionId).resume()
    repairTerminalReplay(activeSessionId)
  }

  private fun repairTerminalReplay(sessionId: String) {
    val runs = synchronized(lock) {
      sessionRuntimeManager.forSession(sessionId).listRuns()
    }
    terminalReplayRepairer(sessionId, runs)
  }

  private fun hasSessionLocked(sessionId: String): Boolean = chatSessionStore.loadState().sessions
    .any { session -> session.sessionId == sessionId }

  private fun discardSessionLocked(sessionId: String) {
    val handle = sessionRuntimeManager.forSession(sessionId)
    handle.listRuns()
      .filterNot(AgentRunSnapshot::isTerminal)
      .forEach { run ->
        handle.requestCancel(run.taskId)
      }
    handle.terminateRunningManagedProcesses()
    approvalRegistry.retainKnownTasks(sessionId, emptySet())
    pendingApprovalsBySession.remove(sessionId)
    runtimeEventsBySession.remove(sessionId)
    unreadChatMessageCountsBySession.remove(sessionId)
    sessionRuntimeManager.release(sessionId)
  }

  private fun cancelRunsForPendingMessageIdsLocked(
    sessionId: String,
    pendingMessageIds: Set<String>,
  ) {
    if (pendingMessageIds.isEmpty()) {
      return
    }
    sessionRuntimeManager.forSession(sessionId).requestCancelForPendingMessageIds(pendingMessageIds)
    val sessionApprovals = pendingApprovalsBySession[sessionId] ?: return
    val iterator = sessionApprovals.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (entry.value.pendingMessageId !in pendingMessageIds) {
        continue
      }
      approvalRegistry.clear(sessionId, entry.value.taskId)
      iterator.remove()
    }
    if (sessionApprovals.isEmpty()) {
      pendingApprovalsBySession.remove(sessionId)
    }
  }

  // Use run projection here so chat state settles immediately when a result is already known.
  private fun pendingTaskCount(sessionId: String): Int = sessionRuntimeManager.forSession(sessionId)
    .listRuns()
    .count { run -> !run.isTerminal }

  private fun pendingApprovalsForSession(sessionId: String): List<PendingApprovalSnapshot> {
    val sessionHandle = sessionRuntimeManager.forSession(sessionId)
    val snapshot = sessionHandle.snapshot()
    val runsByTaskId = sessionHandle.listRuns().associateBy(AgentRunSnapshot::taskId)
    val transientApprovals = pendingApprovalsBySession[sessionId].orEmpty()
    approvalRegistry.retainKnownTasks(
      sessionId = sessionId,
      taskIds = snapshot.tasks.mapTo(linkedSetOf()) { taskSnapshot -> taskSnapshot.task.id } + transientApprovals.keys,
    )

    val combined = linkedMapOf<String, PendingApprovalSnapshot>()
    snapshot.tasks
      .asSequence()
      .filter { taskSnapshot ->
        (taskSnapshot.lifecycleState == QueueTaskLifecycleState.SUSPENDED ||
          taskSnapshot.lifecycleState == QueueTaskLifecycleState.FAILED) &&
          isApprovalRequiredError(taskSnapshot.lastErrorCode)
      }
      .map { taskSnapshot ->
        val isHighRisk = taskSnapshot.lastErrorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED
        val runSnapshot = runsByTaskId[taskSnapshot.task.id]
        pendingApprovalSnapshot(
          runId = runSnapshot?.runId ?: runIdFor(taskSnapshot.task),
          taskId = taskSnapshot.task.id,
          pendingMessageId = runSnapshot?.pendingMessageId
            ?: taskSnapshot.task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
              ?.takeIf(String::isNotBlank),
          isHighRisk = isHighRisk,
          metadata = runSnapshot?.resultMetadata.orEmpty(),
          errorBody = sanitizeApprovalBody(
            body = runSnapshot?.errorMessage ?: taskSnapshot.lastErrorMessage,
            isHighRisk = isHighRisk,
          ),
          toolReason = runSnapshot?.resultMetadata?.get("toolReason")
            ?: runSnapshot?.lastEvent?.let(::toolReasonFromEvent),
        )
      }
      .forEach { approval ->
        combined[approval.taskId] = approval
      }
    transientApprovals.values.forEach { approval ->
      combined[approval.taskId] = approval
    }
    return combined.values.filter { approval ->
      !approvalRegistry.isApproved(sessionId, approval.taskId) &&
        !approvalRegistry.isRejected(sessionId, approval.taskId)
    }
  }

  private fun pendingApprovalForIdentifier(
    sessionId: String,
    taskIdOrRunId: String,
  ): PendingApprovalSnapshot? = pendingApprovalsForSession(sessionId)
    .firstOrNull { approval ->
      approval.taskId == taskIdOrRunId || approval.runId == taskIdOrRunId
    }

  private fun recordPendingApprovalLocked(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
  ): PendingApprovalSnapshot {
    val isHighRisk = result.errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED
    val approval = pendingApprovalSnapshot(
      runId = runIdFor(task),
      taskId = task.id,
      pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
        ?.takeIf(String::isNotBlank),
      isHighRisk = isHighRisk,
      metadata = result.metadata,
      errorBody = sanitizeApprovalBody(
        body = result.errorMessage,
        isHighRisk = isHighRisk,
      ),
      toolReason = result.metadata["toolReason"],
    )
    pendingApprovalsBySession
      .getOrPut(sessionId) { linkedMapOf() }[task.id] = approval
    return approval
  }

  private fun clearPendingApprovalLocked(sessionId: String, taskId: String) {
    val sessionApprovals = pendingApprovalsBySession[sessionId] ?: return
    sessionApprovals.remove(taskId)
    if (sessionApprovals.isEmpty()) {
      pendingApprovalsBySession.remove(sessionId)
    }
  }

  private fun incrementUnreadIfBackgroundUpdateLocked(
    sessionId: String,
    activeSessionId: String,
    text: String?,
  ) {
    if (sessionId == activeSessionId) {
      return
    }
    val normalized = text?.trim().orEmpty()
    if (normalized.isEmpty()) {
      return
    }
    unreadChatMessageCountsBySession[sessionId] =
      (unreadChatMessageCountsBySession[sessionId] ?: 0) + 1
  }

  private fun clearUnreadCountLocked(sessionId: String) {
    unreadChatMessageCountsBySession.remove(sessionId)
  }

  private fun unreadCountForSessionLocked(
    sessionId: String,
    activeSessionId: String,
  ): Int = if (sessionId == activeSessionId) {
    0
  } else {
    unreadChatMessageCountsBySession[sessionId] ?: 0
  }

  private fun recordRuntimeEventLocked(sessionId: String, event: OpenCrayAgentRunEvent) {
    val events = runtimeEventsBySession.getOrPut(sessionId) { ArrayDeque() }
    events += event
    while (events.size > MAX_RUNTIME_EVENT_HISTORY) {
      events.removeFirst()
    }
  }

  private fun successfulToolObservationsLocked(sessionId: String, task: AgentTask): List<String> {
    val runId = runIdFor(task)
    return runtimeEventsBySession[sessionId]
      ?.asSequence()
      ?.filter { event -> event.runId == runId }
      ?.mapNotNull { event ->
        (event as? OpenCrayToolResultEvent)
          ?.takeIf { toolEvent -> toolEvent.result.status == AgentToolResultStatus.SUCCESS }
          ?.result
          ?.content
          ?.trim()
          ?.takeIf(String::isNotBlank)
      }
      ?.distinct()
      ?.toList()
      .orEmpty()
  }

  private fun resolvedUserTextLocked(
    sessionId: String,
    pendingMessageId: String?,
    task: AgentTask,
  ): String {
    val messageId = pendingMessageId?.takeIf(String::isNotBlank) ?: return task.input
    val messages = chatSessionStore.loadSession(sessionId)?.messages.orEmpty()
    val assistantIndex = messages.indexOfFirst { message -> message.messageId == messageId }
    if (assistantIndex <= 0) {
      return task.input
    }
    return messages
      .take(assistantIndex)
      .lastOrNull { message -> message.role == ChatTranscriptRole.USER }
      ?.text
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: task.input
  }

  private fun runtimeActivitySnapshotLocked(sessionId: String): Map<String, Any?> {
    val runs = sessionRuntimeManager.forSession(sessionId).listRuns()
    val recentEvents = mergedRuntimeEventsLocked(
      sessionId = sessionId,
      runs = runs,
    )
    return runtimeActivitySnapshotMap(
      sessionId = sessionId,
      runs = runs,
      recentEvents = recentEvents,
    )
  }

  private fun runtimeActivitySnapshotMap(
    sessionId: String,
    runs: List<AgentRunSnapshot>,
    recentEvents: List<OpenCrayAgentRunEvent>,
  ): Map<String, Any?> {
    return mapOf(
      "sessionId" to sessionId,
      "activeRuns" to runs
        .filter(AgentRunSnapshot::isActive)
        .map { run ->
          runSnapshotToMap(
            run.copy(
              lastEvent = run.lastEvent ?: recentEvents.lastOrNull { event -> event.runId == run.runId },
            ),
          )
        },
      "events" to recentEvents.map(::runtimeEventToMap),
    )
  }

  private fun renderedChatMessagesLocked(
    visibleMessages: List<ChatTranscriptMessageEntry>,
    runs: List<AgentRunSnapshot>,
    runtimeEvents: List<OpenCrayAgentRunEvent>,
  ): List<Map<String, Any?>> {
    val projectedMessages = projectedRuntimeMessagesForChatLocked(
      runs = runs,
      runtimeEvents = runtimeEvents,
    )
    if (projectedMessages.isEmpty()) {
      return visibleMessages.map(::chatMessageToMap)
    }
    val visibleMessageIds = visibleMessages
      .mapTo(linkedSetOf(), ChatTranscriptMessageEntry::messageId)
    val projectedByAnchor = projectedMessages
      .mapNotNull { projection ->
        val anchorMessageId = projection.anchorMessageId ?: return@mapNotNull null
        if (anchorMessageId !in visibleMessageIds) {
          return@mapNotNull null
        }
        anchorMessageId to projection
      }
      .groupBy(
        keySelector = Pair<String, ProjectedRuntimeChatMessage>::first,
        valueTransform = Pair<String, ProjectedRuntimeChatMessage>::second,
      )
    val merged = ArrayList<Map<String, Any?>>(visibleMessages.size + projectedMessages.size)
    visibleMessages.forEach { message ->
      projectedByAnchor[message.messageId]?.forEach { projection ->
        merged += projection.snapshot
      }
      merged += chatMessageToMap(message)
    }
    return merged
  }

  private fun projectedRuntimeMessagesForChatLocked(
    runs: List<AgentRunSnapshot>,
    runtimeEvents: List<OpenCrayAgentRunEvent>,
  ): List<ProjectedRuntimeChatMessage> {
    if (runtimeEvents.isEmpty()) {
      return emptyList()
    }
    val pendingMessageIdByRunId = linkedMapOf<String, String>()
    val pendingMessageIdByTaskId = linkedMapOf<String, String>()
    runs.forEach { run ->
      val pendingMessageId = run.pendingMessageId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return@forEach
      pendingMessageIdByRunId[run.runId] = pendingMessageId
      pendingMessageIdByTaskId[run.taskId] = pendingMessageId
    }
    val orderedEvents = runtimeEvents
      .withIndex()
      .sortedWith(
        compareBy<IndexedValue<OpenCrayAgentRunEvent>> { indexed ->
          indexed.value.emittedAtEpochMs
        }.thenBy(IndexedValue<OpenCrayAgentRunEvent>::index),
      )
      .map(IndexedValue<OpenCrayAgentRunEvent>::value)
    return orderedEvents.mapNotNull { event ->
      val anchorMessageId = pendingMessageIdByRunId[event.runId]
        ?: pendingMessageIdByTaskId[event.taskId]
        ?: return@mapNotNull null
      val text = projectedRuntimeMessageText(
        event = event,
      ) ?: return@mapNotNull null
      if (text.isBlank()) {
        return@mapNotNull null
      }
      ProjectedRuntimeChatMessage(
        anchorMessageId = anchorMessageId,
        snapshot = chatMessageSnapshotMap(
          messageId = runtimeProjectedMessageId(event),
          kind = "inbound",
          text = text,
          isEphemeral = true,
        ),
      )
    }
  }

  private fun projectedRuntimeMessageText(
    event: OpenCrayAgentRunEvent,
  ): String? = when (event) {
    is OpenCrayProgressEvent -> chatProgressText(event)
    is OpenCrayApprovalEvent -> null
    is OpenCrayToolCallEvent -> null
    is OpenCrayToolResultEvent -> null
    is OpenCrayCancellationEvent -> null
    else -> null
  }

  private fun chatProgressText(event: OpenCrayProgressEvent): String {
    val stage = event.stage?.trim().orEmpty()
    val text = event.text.trim()
    return when {
      stage.isEmpty() -> text
      text.isEmpty() -> stage
      else -> "$stage\n\n$text"
    }
  }

  private fun chatToolCallText(event: OpenCrayToolCallEvent): String {
    val toolName = event.call.toolName.trim().takeIf(String::isNotBlank) ?: "Tool"
    val summary = toolActionSummary(
      toolName = toolName,
      arguments = event.call.arguments,
    )
    val reason = event.call.reason
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(::toolReasonText)
    return joinProjectedChatSections(
      summary,
      reason,
    )
  }

  private fun chatToolResultText(
    event: OpenCrayToolResultEvent,
    pairedToolCall: OpenCrayToolCallEvent?,
  ): String {
    val toolName = event.result.toolName.trim().takeIf(String::isNotBlank)
      ?: event.call.toolName.trim().takeIf(String::isNotBlank)
      ?: "Tool"
    val summary = toolResultActionSummary(
      toolName = toolName,
      event = event,
      pairedToolCall = pairedToolCall,
    )
    val resultSummary = toolResultMetadataSummary(
      toolName = toolName,
      metadata = event.result.metadata,
    )
    val body = toolResultBodyText(event.result)
    return joinProjectedChatSections(
      summary,
      resultSummary,
      body,
    )
  }

  private fun previousToolCallEvent(
    orderedEvents: List<OpenCrayAgentRunEvent>,
    beforeIndex: Int,
    resultEvent: OpenCrayToolResultEvent,
  ): OpenCrayToolCallEvent? {
    val normalizedToolName = resultEvent.result.toolName.trim().takeIf(String::isNotBlank)
      ?: resultEvent.call.toolName.trim().takeIf(String::isNotBlank)
      ?: return null
    for (index in beforeIndex - 1 downTo 0) {
      val candidate = orderedEvents[index] as? OpenCrayToolCallEvent ?: continue
      if (candidate.runId != resultEvent.runId && candidate.taskId != resultEvent.taskId) {
        continue
      }
      if (candidate.call.toolName.trim().equals(normalizedToolName, ignoreCase = true)) {
        return candidate
      }
    }
    return null
  }

  private fun toolActionSummary(
    toolName: String,
    arguments: JsonObject,
  ): String {
    val normalizedToolName = toolName.trim()
    val fallback = if (isChineseHostLocale()) {
      "调用工具 $normalizedToolName"
    } else {
      "Call $normalizedToolName"
    }
    return when (normalizedToolName) {
      "Read",
      "workspace_read_file" -> {
        val path = arguments.replayString("file_path") ?: arguments.replayString("path")
        if (path == null) {
          fallback
        } else {
          val range = readRangeSummary(
            offset = arguments.replayInt("offset"),
            limit = arguments.replayInt("limit"),
          )
          if (isChineseHostLocale()) {
            "读取 $path${if (range.isNotEmpty()) "，$range" else ""}"
          } else {
            "Read $path${if (range.isNotEmpty()) " $range" else ""}"
          }
        }
      }

      "Grep" -> {
        val pattern = arguments.replayString("pattern")
        if (pattern == null) {
          fallback
        } else {
          val path = arguments.replayString("path") ?: "."
          val glob = arguments.replayString("glob")
          if (isChineseHostLocale()) {
            buildString {
              append("在 $path 中搜索 \"$pattern\"")
              glob?.let { append("，glob: $it") }
            }
          } else {
            buildString {
              append("Search \"$pattern\" in $path")
              glob?.let { append(" (glob: $it)") }
            }
          }
        }
      }

      "Glob" -> {
        val pattern = arguments.replayString("pattern")
        if (pattern == null) {
          fallback
        } else {
          val path = arguments.replayString("path") ?: "."
          if (isChineseHostLocale()) {
            "在 $path 中匹配 $pattern"
          } else {
            "Match $pattern in $path"
          }
        }
      }

      "LS",
      "workspace_list_files" -> {
        val path = arguments.replayString("path")
          ?: arguments.replayString("file_path")
          ?: "."
        if (isChineseHostLocale()) {
          "列出 $path"
        } else {
          "List $path"
        }
      }

      "Write",
      "workspace_write_file" -> {
        val path = arguments.replayString("file_path") ?: arguments.replayString("path")
        if (path == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "写入 $path"
        } else {
          "Write $path"
        }
      }

      "Edit" -> {
        val path = arguments.replayString("file_path") ?: arguments.replayString("path")
        if (path == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "编辑 $path"
        } else {
          "Edit $path"
        }
      }

      "MultiEdit" -> {
        val path = arguments.replayString("file_path") ?: arguments.replayString("path")
        if (path == null) {
          fallback
        } else {
          val editCount = arguments.replayArraySize("edits") ?: 0
          if (editCount > 0) {
            if (isChineseHostLocale()) {
              "对 $path 应用 $editCount 处编辑"
            } else {
              "Apply $editCount edit(s) to $path"
            }
          } else if (isChineseHostLocale()) {
            "批量编辑 $path"
          } else {
            "MultiEdit $path"
          }
        }
      }

      "TodoWrite" -> {
        val todoCount = arguments.replayArraySize("todos") ?: 0
        if (todoCount > 0) {
          if (isChineseHostLocale()) {
            "更新 $todoCount 条待办"
          } else {
            "Update $todoCount todo(s)"
          }
        } else if (isChineseHostLocale()) {
          "读取当前待办列表"
        } else {
          "Read current todo list"
        }
      }

      "ImportFile",
      "workspace_import_file" -> {
        val sourcePath = arguments.replayString("source_path")
        val destinationPath = arguments.replayString("destination_path")
        if (sourcePath == null || destinationPath == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "导入 $sourcePath 到 $destinationPath"
        } else {
          "Import $sourcePath to $destinationPath"
        }
      }

      "Bash",
      "command_exec" -> {
        val command = arguments.replayString("command")
        if (command == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "运行命令 $command"
        } else {
          "Run command $command"
        }
      }

      "python_exec" -> {
        val scriptPath = arguments.replayString("script_path")
        if (scriptPath == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "运行 Python 脚本 $scriptPath"
        } else {
          "Run Python script $scriptPath"
        }
      }

      "ProcessStart" -> {
        val scriptPath = arguments.replayString("script_path")
        val command = arguments.replayString("command")
        when {
          scriptPath != null && isChineseHostLocale() -> "启动后台 Python 进程 $scriptPath"
          scriptPath != null -> "Start background Python process $scriptPath"
          command != null && isChineseHostLocale() -> "启动后台进程 $command"
          command != null -> "Start background process $command"
          else -> fallback
        }
      }

      "ProcessRead" -> {
        val processId = arguments.replayString("process_id")
        if (processId == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "读取进程 $processId 的输出"
        } else {
          "Read output for process $processId"
        }
      }

      "ProcessWait" -> {
        val processId = arguments.replayString("process_id")
        if (processId == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "等待进程 $processId"
        } else {
          "Wait for process $processId"
        }
      }

      "ProcessTerminate" -> {
        val processId = arguments.replayString("process_id")
        if (processId == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "终止进程 $processId"
        } else {
          "Terminate process $processId"
        }
      }

      "WebFetch" -> {
        val url = arguments.replayString("url")
        if (url == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "抓取网页 $url"
        } else {
          "Fetch $url"
        }
      }

      "WebSearch" -> {
        val query = arguments.replayString("query")
        if (query == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "搜索网络 \"$query\""
        } else {
          "Search the web for \"$query\""
        }
      }

      else -> fallback
    }
  }

  private fun toolResultActionSummary(
    toolName: String,
    event: OpenCrayToolResultEvent,
    pairedToolCall: OpenCrayToolCallEvent?,
  ): String {
    event.call.arguments.takeIf { arguments -> arguments.isNotEmpty() }?.let { arguments ->
      return toolActionSummary(toolName = toolName, arguments = arguments)
    }
    pairedToolCall?.call?.arguments?.takeIf { arguments -> arguments.isNotEmpty() }?.let { arguments ->
      return toolActionSummary(toolName = toolName, arguments = arguments)
    }
    return toolActionSummaryFromResultMetadata(
      toolName = toolName,
      metadata = event.result.metadata,
    )
  }

  private fun toolActionSummaryFromResultMetadata(
    toolName: String,
    metadata: Map<String, String>,
  ): String {
    val normalizedToolName = toolName.trim()
    val fallback = if (isChineseHostLocale()) {
      "工具 $normalizedToolName 已返回结果"
    } else {
      "$normalizedToolName returned a result"
    }
    return when (normalizedToolName) {
      "Read",
      "workspace_read_file" -> {
        val path = metadataValue(metadata, "filePath")
        if (path == null) {
          fallback
        } else {
          val range = readRangeSummary(
            offset = metadataInt(metadata, "offset"),
            limit = metadataInt(metadata, "limit"),
          )
          if (isChineseHostLocale()) {
            "读取 $path${if (range.isNotEmpty()) "，$range" else ""}"
          } else {
            "Read $path${if (range.isNotEmpty()) " $range" else ""}"
          }
        }
      }

      "LS",
      "workspace_list_files" -> {
        val path = metadataValue(metadata, "path") ?: "."
        if (isChineseHostLocale()) {
          "列出 $path"
        } else {
          "List $path"
        }
      }

      "Grep" -> {
        val pattern = metadataValue(metadata, "pattern")
        if (pattern == null) {
          fallback
        } else {
          val path = metadataValue(metadata, "path") ?: "."
          if (isChineseHostLocale()) {
            "在 $path 中搜索 \"$pattern\""
          } else {
            "Search \"$pattern\" in $path"
          }
        }
      }

      "Glob" -> {
        val pattern = metadataValue(metadata, "pattern")
        if (pattern == null) {
          fallback
        } else {
          val path = metadataValue(metadata, "path") ?: "."
          if (isChineseHostLocale()) {
            "在 $path 中匹配 $pattern"
          } else {
            "Match $pattern in $path"
          }
        }
      }

      "Write",
      "workspace_write_file",
      "Edit",
      "MultiEdit" -> {
        val path = metadataValue(metadata, "filePath")
        if (path == null) {
          fallback
        } else if (isChineseHostLocale()) {
          "更新 $path"
        } else {
          "Update $path"
        }
      }

      else -> fallback
    }
  }

  private fun toolResultMetadataSummary(
    toolName: String,
    metadata: Map<String, String>,
  ): String? {
    return when (toolName.trim()) {
      "LS",
      "workspace_list_files" -> {
        val entryCount = metadataInt(metadata, "entryCount") ?: return null
        val path = metadataValue(metadata, "path")
        if (isChineseHostLocale()) {
          if (path == null) {
            "列出了 $entryCount 项"
          } else {
            "在 $path 中列出了 $entryCount 项"
          }
        } else if (path == null) {
          "Listed $entryCount entr${if (entryCount == 1) "y" else "ies"}"
        } else {
          "Listed $entryCount entr${if (entryCount == 1) "y" else "ies"} in $path"
        }
      }

      "Read",
      "workspace_read_file" -> {
        val returnedLineCount = metadataInt(metadata, "returnedLineCount")
        val totalLineCount = metadataInt(metadata, "totalLineCount")
        val truncated = metadataBoolean(metadata, "truncated") == true
        val filePath = metadataValue(metadata, "filePath")
        if (returnedLineCount == null && totalLineCount == null && !truncated && filePath == null) {
          null
        } else if (isChineseHostLocale()) {
          buildList {
            filePath?.let(::add)
            returnedLineCount?.let { add("返回 $it 行") }
            totalLineCount?.let { add("文件总计 $it 行") }
            if (truncated) {
              add("结果已按读取预算截断")
            }
          }.joinToString(separator = "，").takeIf(String::isNotBlank)
        } else {
          buildList {
            returnedLineCount?.let { count ->
              add(if (count == 1) "Returned 1 line" else "Returned $count lines")
            }
            filePath?.let { path -> add("from $path") }
            totalLineCount?.let { count ->
              add(if (count == 1) "(1-line file)" else "($count-line file)")
            }
            if (truncated) {
              add("Output truncated to the read budget.")
            }
          }.joinToString(separator = " ").takeIf(String::isNotBlank)
        }
      }

      "Grep" -> {
        val matchCount = metadataInt(metadata, "matchCount") ?: return null
        val pattern = metadataValue(metadata, "pattern")
        val path = metadataValue(metadata, "path") ?: "."
        if (isChineseHostLocale()) {
          if (pattern == null) {
            "在 $path 中找到 $matchCount 处匹配"
          } else {
            "在 $path 中为 \"$pattern\" 找到 $matchCount 处匹配"
          }
        } else if (pattern == null) {
          if (matchCount == 1) {
            "Found 1 match in $path"
          } else {
            "Found $matchCount matches in $path"
          }
        } else if (matchCount == 1) {
          "Found 1 match for \"$pattern\" in $path"
        } else {
          "Found $matchCount matches for \"$pattern\" in $path"
        }
      }

      "Glob" -> {
        val matchCount = metadataInt(metadata, "matchCount") ?: return null
        val pattern = metadataValue(metadata, "pattern")
        val path = metadataValue(metadata, "path") ?: "."
        if (isChineseHostLocale()) {
          if (pattern == null) {
            "在 $path 中匹配到 $matchCount 个路径"
          } else {
            "在 $path 中为 $pattern 匹配到 $matchCount 个路径"
          }
        } else if (pattern == null) {
          "Matched $matchCount path(s) in $path"
        } else {
          "Matched $matchCount path(s) for $pattern in $path"
        }
      }

      "Edit" -> {
        val replacementCount = metadataInt(metadata, "replacementCount") ?: return null
        val filePath = metadataValue(metadata, "filePath")
        if (isChineseHostLocale()) {
          if (filePath == null) {
            "应用了 $replacementCount 处替换"
          } else {
            "在 $filePath 中应用了 $replacementCount 处替换"
          }
        } else if (filePath == null) {
          "Applied $replacementCount replacement(s)"
        } else {
          "Applied $replacementCount replacement(s) in $filePath"
        }
      }

      "MultiEdit" -> {
        val replacementCount = metadataInt(metadata, "replacementCount")
        val editCount = metadataInt(metadata, "editCount")
        val filePath = metadataValue(metadata, "filePath")
        if (replacementCount == null && editCount == null && filePath == null) {
          null
        } else if (isChineseHostLocale()) {
          buildList {
            filePath?.let(::add)
            replacementCount?.let { add("$it 处替换") }
            editCount?.let { add("$it 个编辑块") }
          }.joinToString(separator = "，").takeIf(String::isNotBlank)?.let { "应用了 $it" }
        } else {
          buildList {
            replacementCount?.let { add("$it replacement(s)") }
            editCount?.let { add("across $it edit(s)") }
            filePath?.let { add("in $it") }
          }.joinToString(separator = " ").takeIf(String::isNotBlank)?.let { "Applied $it" }
        }
      }

      "TodoWrite" -> {
        val todoCount = metadataInt(metadata, "todoCount") ?: return null
        val mutated = metadataBoolean(metadata, "mutated")
        if (isChineseHostLocale()) {
          if (mutated == true) {
            "待办列表已更新，共 $todoCount 项"
          } else {
            "当前待办列表共 $todoCount 项"
          }
        } else if (mutated == true) {
          "Updated the todo list to $todoCount item(s)"
        } else {
          "Current todo list has $todoCount item(s)"
        }
      }

      else -> null
    }
  }

  private fun toolResultBodyText(result: AgentToolResult): String? {
    result.errorMessage
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let { return it }
    val preview = result.content.trim()
      .takeIf(String::isNotBlank)
      ?.takeUnless { content ->
        content.equals("Tool finished.", ignoreCase = true) ||
          content.equals("Tool completed.", ignoreCase = true)
      }
    if (preview != null) {
      return preview
    }
    return when (result.status) {
      AgentToolResultStatus.DENIED -> if (isChineseHostLocale()) {
        "工具调用被拒绝。"
      } else {
        "Tool call denied."
      }

      AgentToolResultStatus.CANCELLED -> if (isChineseHostLocale()) {
        "工具调用已取消。"
      } else {
        "Tool call cancelled."
      }

      AgentToolResultStatus.TIMEOUT -> if (isChineseHostLocale()) {
        "工具调用超时。"
      } else {
        "Tool call timed out."
      }

      AgentToolResultStatus.FAILED -> if (isChineseHostLocale()) {
        "工具调用失败。"
      } else {
        "Tool call failed."
      }

      AgentToolResultStatus.SUCCESS -> null
    }
  }

  private fun readRangeSummary(offset: Int?, limit: Int?): String {
    if (offset == null && limit == null) {
      return ""
    }
    if (isChineseHostLocale()) {
      if (offset != null && limit != null) {
        val endLine = offset + limit - 1
        return "第 $offset-$endLine 行"
      }
      if (offset != null) {
        return "从第 $offset 行开始"
      }
      return "前 $limit 行"
    }
    if (offset != null && limit != null) {
      val endLine = offset + limit - 1
      return "lines $offset-$endLine"
    }
    if (offset != null) {
      return "from line $offset"
    }
    return "first $limit lines"
  }

  private fun toolReasonText(reason: String): String =
    if (isChineseHostLocale()) {
      "原因：$reason"
    } else {
      "Reason: $reason"
    }

  private fun joinProjectedChatSections(vararg sections: String?): String =
    sections
      .mapNotNull { section -> section?.trim()?.takeIf(String::isNotBlank) }
      .joinToString(separator = "\n\n")

  private fun isChineseHostLocale(): Boolean =
    strings.localeTag.trim().lowercase(Locale.US).startsWith("zh")

  private fun metadataValue(metadata: Map<String, String>, key: String): String? =
    metadata[key]
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun metadataInt(metadata: Map<String, String>, key: String): Int? =
    metadataValue(metadata, key)?.toIntOrNull()

  private fun metadataBoolean(metadata: Map<String, String>, key: String): Boolean? =
    when (metadataValue(metadata, key)?.lowercase(Locale.US)) {
      "true" -> true
      "false" -> false
      else -> null
    }

  private fun JsonObject.replayArraySize(key: String): Int? =
    (this[key] as? JsonArray)?.size

  private fun runtimeProjectedMessageId(event: OpenCrayAgentRunEvent): String = when (event) {
    is OpenCrayProgressEvent -> "runtime-progress-${event.runId}-${event.emittedAtEpochMs}"
    is OpenCrayApprovalEvent -> "runtime-approval-${event.phase.name.lowercase(Locale.US)}-${event.runId}-${event.emittedAtEpochMs}"
    is OpenCrayToolCallEvent -> "runtime-tool-call-${event.runId}-${event.turn}-${event.emittedAtEpochMs}"
    is OpenCrayToolResultEvent -> "runtime-tool-result-${event.runId}-${event.turn}-${event.emittedAtEpochMs}"
    is OpenCrayCancellationEvent -> "runtime-cancelled-${event.runId}-${event.emittedAtEpochMs}"
    else -> "runtime-event-${event.runId}-${event.emittedAtEpochMs}"
  }

  private fun mergedRuntimeEventsLocked(
    sessionId: String,
    runs: List<AgentRunSnapshot>,
  ): List<OpenCrayAgentRunEvent> {
    val liveEvents = runtimeEventsBySession[sessionId]?.toList().orEmpty()
    val replayedEvents = replayedRuntimeEventsLocked(
      sessionId = sessionId,
      runs = runs,
      liveEvents = liveEvents,
    )
    if (replayedEvents.isEmpty()) {
      return liveEvents
    }
    val merged = ArrayList<OpenCrayAgentRunEvent>(replayedEvents.size + liveEvents.size)
    val seen = linkedSetOf<String>()
    (replayedEvents + liveEvents).forEach { event ->
      if (seen.add(runtimeEventDedupKey(event))) {
        merged += event
      }
    }
    supplementalApprovalEventsLocked(
      sessionId = sessionId,
      runs = runs,
      existingEvents = merged,
    ).forEach { event ->
      if (seen.add(runtimeEventDedupKey(event))) {
        merged += event
      }
    }
    return merged.takeLast(MAX_RUNTIME_EVENT_HISTORY)
  }

  private fun supplementalApprovalEventsLocked(
    sessionId: String,
    runs: List<AgentRunSnapshot>,
    existingEvents: List<OpenCrayAgentRunEvent>,
  ): List<OpenCrayAgentRunEvent> {
    val approvals = pendingApprovalsForSession(sessionId)
    if (approvals.isEmpty()) {
      return emptyList()
    }
    val runsByTaskId = runs.associateBy(AgentRunSnapshot::taskId)
    return approvals.mapNotNull { approval ->
      val alreadyPresent = existingEvents.any { event ->
        event is OpenCrayApprovalEvent &&
          event.phase == OpenCrayApprovalPhase.REQUIRED &&
          (event.taskId == approval.taskId || event.runId == approval.runId)
      }
      if (alreadyPresent) {
        return@mapNotNull null
      }
      val run = runsByTaskId[approval.taskId]
      approvalRequiredRuntimeEvent(
        approval = approval,
        emittedAtEpochMs = run?.updatedAtEpochMs ?: System.currentTimeMillis(),
      )
    }
  }

  private fun replayedRuntimeEventsLocked(
    sessionId: String,
    runs: List<AgentRunSnapshot>,
    liveEvents: List<OpenCrayAgentRunEvent>,
  ): List<OpenCrayAgentRunEvent> {
    val transcriptMessages = runCatching {
      transcriptMessagesProvider(sessionId)
    }.getOrDefault(emptyList())
    if (transcriptMessages.isEmpty()) {
      return emptyList()
    }
    val replayedEvents = transcriptMessages.mapIndexedNotNull { index, message ->
      parseReplayedRuntimeEvent(
        message = message,
        sourceIndex = index,
      )
    }
    if (replayedEvents.isEmpty()) {
      return emptyList()
    }
    return assignReplayEmissionTimes(
      replayedEvents = replayedEvents,
      runs = runs,
      liveEvents = liveEvents,
    )
  }

  private fun parseReplayedRuntimeEvent(
    message: RuntimeConversationMessage,
    sourceIndex: Int,
  ): ReplayedRuntimeEvent? {
    if (message.role != RuntimeConversationRole.TOOL) {
      return null
    }
    val content = message.content.trim()
    if (content.isEmpty()) {
      return null
    }
    val event = when {
      content.startsWith("tool_call ") -> parseReplayedToolCallEvent(
        payload = content.removePrefix("tool_call ").trim(),
      )

      content.startsWith("tool_result ") -> parseReplayedToolResultEvent(
        payload = content.removePrefix("tool_result ").trim(),
      )

      content.startsWith("progress ") -> parseReplayedProgressEvent(
        payload = content.removePrefix("progress ").trim(),
      )

      content.startsWith("approval_approved") -> parseReplayedApprovalEvent(
        content = content,
        phase = OpenCrayApprovalPhase.APPROVED,
      )

      content.startsWith("approval_rejected") -> parseReplayedApprovalEvent(
        content = content,
        phase = OpenCrayApprovalPhase.REJECTED,
      )

      content.startsWith("run_cancelled") -> parseReplayedCancellationEvent(
        content = content,
      )

      else -> null
    } ?: return null
    return ReplayedRuntimeEvent(
      sourceIndex = sourceIndex,
      event = event,
    )
  }

  private fun parseReplayedToolCallEvent(payload: String): OpenCrayToolCallEvent? {
    val decoded = decodeReplayPayload(payload) ?: return null
    val identifiers = replayIdentifiers(decoded) ?: return null
    val toolName = decoded.replayString("tool_name") ?: return null
    return OpenCrayToolCallEvent(
      runId = identifiers.first,
      taskId = identifiers.second,
      turn = decoded.replayInt("turn") ?: 0,
      call = AgentToolCall(
        toolName = toolName,
        arguments = decoded.replayObject("arguments") ?: JsonObject(emptyMap()),
        reason = decoded.replayString("reason"),
      ),
      emittedAtEpochMs = 0L,
    )
  }

  private fun parseReplayedToolResultEvent(payload: String): OpenCrayToolResultEvent? {
    val decoded = decodeReplayPayload(payload) ?: return null
    val identifiers = replayIdentifiers(decoded) ?: return null
    val toolName = decoded.replayString("tool_name") ?: return null
    val status = decoded.replayString("status")
      ?.let(::parseReplayToolResultStatus)
      ?: AgentToolResultStatus.SUCCESS
    return OpenCrayToolResultEvent(
      runId = identifiers.first,
      taskId = identifiers.second,
      turn = decoded.replayInt("turn") ?: 0,
      call = AgentToolCall(toolName = toolName),
      result = AgentToolResult(
        toolName = toolName,
        status = status,
        content = decoded.replayString("content_preview")
          ?.takeIf(String::isNotBlank)
          ?: "Tool finished.",
        errorCode = decoded.replayString("error_code"),
        errorMessage = decoded.replayString("error_message"),
        metadata = decoded.replayStringMap("metadata"),
      ),
      emittedAtEpochMs = 0L,
    )
  }

  private fun parseReplayedProgressEvent(payload: String): OpenCrayProgressEvent? {
    val decoded = decodeReplayPayload(payload) ?: return null
    val identifiers = replayIdentifiers(decoded) ?: return null
    return OpenCrayProgressEvent(
      runId = identifiers.first,
      taskId = identifiers.second,
      turn = decoded.replayInt("turn") ?: 0,
      text = decoded.replayString("text") ?: return null,
      stage = decoded.replayString("stage"),
      emittedAtEpochMs = 0L,
    )
  }

  private fun parseReplayedApprovalEvent(
    content: String,
    phase: OpenCrayApprovalPhase,
  ): OpenCrayApprovalEvent? {
    val fields = replayTokenFields(content)
    val runId = fields["run_id"]?.trim()?.takeIf { value -> value.isNotBlank() }
      ?: return null
    val taskId = fields["task_id"]?.trim()?.takeIf { value -> value.isNotBlank() }
      ?: runId
    val toolName = fields["tool_name"]?.trim()?.takeIf(String::isNotBlank)
    val isHighRisk = fields["risk"]?.trim()?.equals("high_risk", ignoreCase = true) == true
    return OpenCrayApprovalEvent(
      runId = runId,
      taskId = taskId,
      phase = phase,
      toolName = toolName,
      text = when (phase) {
        OpenCrayApprovalPhase.REQUIRED -> strings.chatSummaryApprovalRequired
        OpenCrayApprovalPhase.APPROVED -> strings.chatApprovalApproved
        OpenCrayApprovalPhase.REJECTED -> strings.chatApprovalRejected
      },
      isHighRisk = isHighRisk,
      emittedAtEpochMs = 0L,
    )
  }

  private fun parseReplayedCancellationEvent(content: String): OpenCrayCancellationEvent? {
    val fields = replayTokenFields(content)
    val runId = fields["run_id"]?.trim()?.takeIf { value -> value.isNotBlank() }
      ?: return null
    val taskId = fields["task_id"]?.trim()?.takeIf { value -> value.isNotBlank() }
      ?: runId
    val toolName = fields["tool_name"]?.trim()?.takeIf(String::isNotBlank)
    val outcome = fields["outcome"]?.trim()?.takeIf(String::isNotBlank)
    return OpenCrayCancellationEvent(
      runId = runId,
      taskId = taskId,
      toolName = toolName,
      outcome = outcome,
      text = cancellationTimelineText(toolName = toolName),
      emittedAtEpochMs = 0L,
    )
  }

  private fun decodeReplayPayload(payload: String): JsonObject? =
    runCatching {
      replayJson.parseToJsonElement(payload).jsonObject
    }.getOrNull()

  private fun replayTokenFields(content: String): Map<String, String> =
    content
      .trim()
      .split(' ')
      .drop(1)
      .mapNotNull { token ->
        val separatorIndex = token.indexOf('=')
        if (separatorIndex <= 0 || separatorIndex >= token.lastIndex) {
          return@mapNotNull null
        }
        val key = token.substring(0, separatorIndex).trim()
        val value = token.substring(separatorIndex + 1).trim()
        if (key.isEmpty() || value.isEmpty()) {
          null
        } else {
          key to value
        }
      }
      .toMap(linkedMapOf())

  private fun replayIdentifiers(payload: JsonObject): Pair<String, String>? {
    val runId = payload.replayString("run_id")
      ?: payload.replayString("task_id")
      ?: return null
    val taskId = payload.replayString("task_id") ?: runId
    return runId to taskId
  }

  private fun parseReplayToolResultStatus(raw: String): AgentToolResultStatus? =
    AgentToolResultStatus.entries.firstOrNull { status ->
      status.name.equals(raw, ignoreCase = true)
    }

  private fun assignReplayEmissionTimes(
    replayedEvents: List<ReplayedRuntimeEvent>,
    runs: List<AgentRunSnapshot>,
    liveEvents: List<OpenCrayAgentRunEvent>,
  ): List<OpenCrayAgentRunEvent> {
    val replayCountByRun = replayedEvents.groupingBy { replay ->
      replayRunGroupKey(
        event = replay.event,
        sourceIndex = replay.sourceIndex,
      )
    }.eachCount()
    val emittedCountByRun = linkedMapOf<String, Int>()
    val runsByRunId = runs.associateBy(AgentRunSnapshot::runId)
    val runsByTaskId = runs.associateBy(AgentRunSnapshot::taskId)
    return replayedEvents.map { replay ->
      val groupKey = replayRunGroupKey(
        event = replay.event,
        sourceIndex = replay.sourceIndex,
      )
      val emittedCount = emittedCountByRun[groupKey] ?: 0
      emittedCountByRun[groupKey] = emittedCount + 1
      val liveEventsForRun = liveEvents.filter { liveEvent ->
        liveEvent.runId == replay.event.runId ||
          (replay.event.runId.isBlank() && liveEvent.taskId == replay.event.taskId)
      }
      val lifecycleStartAt = liveEventsForRun
        .mapNotNull { liveEvent ->
          (liveEvent as? OpenCrayLifecycleEvent)
            ?.takeIf { event -> event.phase == OpenCrayRunLifecyclePhase.START }
            ?.emittedAtEpochMs
        }
        .minOrNull()
      val firstLiveAt = liveEventsForRun.minOfOrNull(OpenCrayAgentRunEvent::emittedAtEpochMs)
      val runSnapshot = runsByRunId[replay.event.runId] ?: runsByTaskId[replay.event.taskId]
      val replayBaseTime = when {
        lifecycleStartAt != null -> lifecycleStartAt + 1L
        firstLiveAt != null -> (firstLiveAt - (replayCountByRun[groupKey] ?: 1).toLong()).coerceAtLeast(1L)
        runSnapshot != null -> (runSnapshot.updatedAtEpochMs - (replayCountByRun[groupKey] ?: 1).toLong()).coerceAtLeast(1L)
        else -> (replay.sourceIndex + 1).toLong()
      }
      replay.event.withEmittedAtEpochMs(replayBaseTime + emittedCount.toLong())
    }
  }

  private fun replayRunGroupKey(
    event: OpenCrayAgentRunEvent,
    sourceIndex: Int,
  ): String = event.runId
    .takeIf(String::isNotBlank)
    ?: event.taskId
      .takeIf(String::isNotBlank)
      ?: "replay-$sourceIndex"

  private fun runtimeEventDedupKey(event: OpenCrayAgentRunEvent): String = when (event) {
    is OpenCrayLifecycleEvent -> listOf(
      "lifecycle",
      event.runId,
      event.taskId,
      event.turn.orEmptyString(),
      event.phase.name,
      event.status?.name.orEmpty(),
      event.errorCode.orEmpty(),
      event.errorMessage.orEmpty(),
    ).joinToString(separator = "|")

    is OpenCrayAssistantEvent -> listOf(
      "assistant",
      event.runId,
      event.taskId,
      event.turn.toString(),
      event.responseFormat,
      event.isFinal.toString(),
      event.text,
    ).joinToString(separator = "|")

    is OpenCrayProgressEvent -> listOf(
      "progress",
      event.runId,
      event.taskId,
      event.turn.toString(),
      event.stage.orEmpty(),
      event.text,
    ).joinToString(separator = "|")

    is OpenCrayApprovalEvent -> listOf(
      "approval",
      event.runId,
      event.taskId,
      event.turn.orEmptyString(),
      event.phase.name,
      event.toolName.orEmpty(),
      event.isHighRisk.toString(),
      event.text,
    ).joinToString(separator = "|")

    is OpenCrayToolCallEvent -> listOf(
      "tool_call",
      event.runId,
      event.taskId,
      event.turn.toString(),
      event.call.toolName,
      event.call.reason.orEmpty(),
      event.call.arguments.toString(),
    ).joinToString(separator = "|")

    is OpenCrayToolResultEvent -> listOf(
      "tool_result",
      event.runId,
      event.taskId,
      event.turn.toString(),
      event.result.toolName,
      event.result.status.name,
      event.result.errorCode.orEmpty(),
      event.result.errorMessage.orEmpty(),
    ).joinToString(separator = "|")

    is OpenCrayMemoryRetrievalEvent -> listOf(
      "memory_retrieval",
      event.runId,
      event.taskId,
      event.turn.toString(),
      event.toolName,
      event.operation,
      event.query.orEmpty(),
      event.path.orEmpty(),
    ).joinToString(separator = "|")

    is OpenCrayMemoryWriteEvent -> listOf(
      "memory_write",
      event.runId,
      event.taskId,
      event.turn.orEmptyString(),
      event.writtenRecordIds.joinToString(separator = ","),
      event.resolvedRecordIds.joinToString(separator = ","),
      event.reaffirmedRecordIds.joinToString(separator = ","),
      event.expiredRecordIds.joinToString(separator = ","),
    ).joinToString(separator = "|")

    is OpenCrayCancellationEvent -> listOf(
      "cancelled",
      event.runId,
      event.taskId,
      event.turn.orEmptyString(),
      event.toolName.orEmpty(),
      event.outcome.orEmpty(),
      event.text,
    ).joinToString(separator = "|")
  }

  private fun findRunSnapshotLocked(runId: String): AgentRunSnapshot? {
    val sessionIds = chatSessionStore.loadState().sessions
      .mapTo(linkedSetOf()) { session -> session.sessionId }
    return sessionIds.firstNotNullOfOrNull { sessionId ->
      sessionRuntimeManager.forSession(sessionId).findRun(runId)
    }
  }

  private fun findRunSnapshotForIdentifierLocked(runIdOrTaskId: String): AgentRunSnapshot? {
    val byRunId = findRunSnapshotLocked(runIdOrTaskId)
    if (byRunId != null) {
      return byRunId
    }
    val sessionIds = chatSessionStore.loadState().sessions
      .mapTo(linkedSetOf()) { session -> session.sessionId }
    return sessionIds.firstNotNullOfOrNull { sessionId ->
      sessionRuntimeManager.forSession(sessionId)
        .listRuns()
        .firstOrNull { run -> run.taskId == runIdOrTaskId }
    }
  }

  private fun waitForRunSnapshot(runId: String, timeoutMs: Long): AgentRunSnapshot? {
    val boundedTimeoutMs = timeoutMs.coerceAtLeast(0L)
    val existing = synchronized(lock) { findRunSnapshotLocked(runId) }
    if (existing != null) {
      return sessionRuntimeManager.forSession(existing.sessionId).waitForRun(runId, boundedTimeoutMs)
    }
    val deadline = System.currentTimeMillis() + boundedTimeoutMs
    while (true) {
      val discovered = synchronized(lock) { findRunSnapshotLocked(runId) }
      if (discovered != null) {
        return sessionRuntimeManager.forSession(discovered.sessionId).waitForRun(
          runId,
          (deadline - System.currentTimeMillis()).coerceAtLeast(0L),
        )
      }
      if (System.currentTimeMillis() >= deadline) {
        return null
      }
      Thread.sleep(RUN_LOOKUP_POLL_INTERVAL_MS)
    }
  }

  private fun runSubmissionToMap(submission: AgentRunSubmission): Map<String, Any?> = mapOf(
    "sessionId" to submission.sessionId,
    "runId" to submission.runId,
    "taskId" to submission.taskId,
    "acceptedAtEpochMs" to submission.acceptedAtEpochMs,
  )

  private fun runSnapshotToMap(run: AgentRunSnapshot): Map<String, Any?> = mapOf(
    "sessionId" to run.sessionId,
    "runId" to run.runId,
    "taskId" to run.taskId,
    "acceptedAtEpochMs" to run.acceptedAtEpochMs,
    "updatedAtEpochMs" to run.updatedAtEpochMs,
    "lifecycleState" to run.lifecycleState?.name?.lowercase(),
    "taskState" to run.taskState?.name?.lowercase(),
    "attempt" to run.attempt,
    "executionStatus" to run.executionStatus?.name?.lowercase(),
    "errorCode" to run.errorCode,
    "errorMessage" to run.errorMessage,
    "responseFormat" to run.responseFormat,
    "memoryTrace" to memoryTraceFromMetadata(run.resultMetadata),
    "memoryFlush" to memoryFlushFromMetadata(run.resultMetadata),
    "bootstrap" to bootstrapFromMetadata(run.resultMetadata),
    "durableCompaction" to durableCompactionFromMetadata(run.resultMetadata),
    "skillInventory" to skillInventoryFromMetadata(run.resultMetadata),
    "activeSkill" to activeSkillFromMetadata(run.resultMetadata),
    "pendingMessageId" to run.pendingMessageId,
    "managedProcessIds" to run.managedProcessIds,
    "runningManagedProcessCount" to run.runningManagedProcessCount,
    "hasLiveManagedProcesses" to run.hasLiveManagedProcesses,
    "isActive" to run.isActive,
    "isTerminal" to run.isTerminal,
    "lastEvent" to run.lastEvent?.let(::runtimeEventToMap),
  )

  private fun memoryTraceFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val matchedCount = metadata["contextMatchedMemoryCount"]?.toIntOrNull()
    val injectedCount = metadata["contextInjectedMemoryCount"]?.toIntOrNull()
    val omittedCount = metadata["contextOmittedMemoryCount"]?.toIntOrNull()
    val queryTerms = metadata["contextMemoryQueryTerms"]
      .orEmpty()
      .split(',')
      .map(String::trim)
      .filter(String::isNotBlank)
    val selected = parseSelectedMemoryTrace(metadata["contextMemorySelectedSummary"].orEmpty())
    val omitted = parseOmittedMemoryTrace(metadata["contextMemoryOmittedSummary"].orEmpty())
    val filteredCounts = parseFilteredMemoryCounts(metadata["contextMemoryFilteredCounts"].orEmpty())
    if (
      matchedCount == null &&
      injectedCount == null &&
      omittedCount == null &&
      queryTerms.isEmpty() &&
      selected.isEmpty() &&
      omitted.isEmpty() &&
      filteredCounts.isEmpty()
    ) {
      return null
    }
    return buildMap {
      matchedCount?.let { put("matchedRecordCount", it) }
      injectedCount?.let { put("injectedRecordCount", it) }
      omittedCount?.let { put("omittedRecordCount", it) }
      if (queryTerms.isNotEmpty()) {
        put("queryTerms", queryTerms)
      }
      if (selected.isNotEmpty()) {
        put("selected", selected)
      }
      if (omitted.isNotEmpty()) {
        put("omitted", omitted)
      }
      if (filteredCounts.isNotEmpty()) {
        put("filteredCounts", filteredCounts)
      }
    }
  }

  private fun memoryFlushFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val outcome = metadata["contextMemoryFlushOutcome"]?.takeIf(String::isNotBlank)
    val omittedMessageCount = metadata["contextMemoryFlushOmittedMessageCount"]?.toIntOrNull()
    val omittedCharCount = metadata["contextMemoryFlushOmittedCharCount"]?.toIntOrNull()
    val signature = metadata["contextMemoryFlushSignature"]?.takeIf(String::isNotBlank)
    val candidateCount = metadata["contextMemoryFlushCandidateCount"]?.toIntOrNull()
    val writtenRecordCount = metadata["contextMemoryFlushWrittenRecordCount"]?.toIntOrNull()
    val writtenKinds = metadata["contextMemoryFlushWrittenKinds"]
      .orEmpty()
      .split(',')
      .map(String::trim)
      .filter(String::isNotBlank)
    val writtenRecordIds = metadata["contextMemoryFlushWrittenRecordIds"]
      .orEmpty()
      .split(',')
      .map(String::trim)
      .filter(String::isNotBlank)
    if (
      outcome == null &&
      omittedMessageCount == null &&
      omittedCharCount == null &&
      signature == null &&
      candidateCount == null &&
      writtenRecordCount == null &&
      writtenKinds.isEmpty() &&
      writtenRecordIds.isEmpty()
    ) {
      return null
    }
    return buildMap {
      outcome?.let { put("outcome", it) }
      omittedMessageCount?.let { put("omittedMessageCount", it) }
      omittedCharCount?.let { put("omittedCharCount", it) }
      signature?.let { put("signature", it) }
      candidateCount?.let { put("candidateCount", it) }
      writtenRecordCount?.let { put("writtenRecordCount", it) }
      if (writtenKinds.isNotEmpty()) {
        put("writtenKinds", writtenKinds)
      }
      if (writtenRecordIds.isNotEmpty()) {
        put("writtenRecordIds", writtenRecordIds)
      }
    }
  }

  private fun bootstrapFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val mode = metadata["contextBootstrapMode"]?.takeIf(String::isNotBlank)
    val visibleFileCount = metadata["contextBootstrapVisibleFileCount"]?.toIntOrNull()
    val injectedFileCount = metadata["contextBootstrapInjectedFileCount"]?.toIntOrNull()
    val omittedFileCount = metadata["contextBootstrapOmittedFileCount"]?.toIntOrNull()
    val truncatedFileCount = metadata["contextBootstrapTruncatedFileCount"]?.toIntOrNull()
    val files = parseBootstrapFileTrace(metadata["contextBootstrapFileSummary"].orEmpty())
    if (
      mode == null &&
      visibleFileCount == null &&
      injectedFileCount == null &&
      omittedFileCount == null &&
      truncatedFileCount == null &&
      files.isEmpty()
    ) {
      return null
    }
    return buildMap {
      mode?.let { put("mode", it) }
      visibleFileCount?.let { put("visibleFileCount", it) }
      injectedFileCount?.let { put("injectedFileCount", it) }
      omittedFileCount?.let { put("omittedFileCount", it) }
      truncatedFileCount?.let { put("truncatedFileCount", it) }
      if (files.isNotEmpty()) {
        put("files", files)
      }
    }
  }

  private fun skillInventoryFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val visibleCount = metadata["contextVisibleSkillCount"]?.toIntOrNull()
    val injectedCount = metadata["contextInjectedSkillCount"]?.toIntOrNull()
    val omittedCount = metadata["contextOmittedSkillCount"]?.toIntOrNull()
    val implicitCount = metadata["contextImplicitSkillCount"]?.toIntOrNull()
    val invalidCount = metadata["contextInvalidSkillCount"]?.toIntOrNull()
    val omittedTraceCount = metadata["contextVisibleSkillTraceOmittedCount"]?.toIntOrNull()
    val skills = parseVisibleSkillTrace(metadata["contextVisibleSkillSummary"].orEmpty())
    if (
      visibleCount == null &&
      injectedCount == null &&
      omittedCount == null &&
      implicitCount == null &&
      invalidCount == null &&
      omittedTraceCount == null &&
      skills.isEmpty()
    ) {
      return null
    }
    return buildMap {
      visibleCount?.let { put("visibleSkillCount", it) }
      injectedCount?.let { put("injectedSkillCount", it) }
      omittedCount?.let { put("omittedSkillCount", it) }
      implicitCount?.let { put("implicitSkillCount", it) }
      invalidCount?.let { put("invalidSkillCount", it) }
      omittedTraceCount?.let { put("omittedTraceSkillCount", it) }
      if (skills.isNotEmpty()) {
        put("skills", skills)
      }
    }
  }

  private fun durableCompactionFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val compactedThisRun = metadata["contextDurableCompactionCompactedThisRun"]?.toBooleanStrictOrNull()
    val sourceTranscriptMessageCount =
      metadata["contextDurableCompactionSourceTranscriptMessageCount"]?.toIntOrNull()
    val retainedTranscriptMessageCount =
      metadata["contextDurableCompactionRetainedTranscriptMessageCount"]?.toIntOrNull()
    val latestCompactedMessageCount =
      metadata["contextDurableCompactionLatestMessageCount"]?.toIntOrNull()
    val includedSummaryCount =
      metadata["contextDurableCompactionIncludedSummaryCount"]?.toIntOrNull()
    val omittedSummaryCount =
      metadata["contextDurableCompactionOmittedSummaryCount"]?.toIntOrNull()
    val totalCompactedMessageCount =
      metadata["contextDurableCompactionTotalCompactedMessageCount"]?.toIntOrNull()
    val latestCompactedAtEpochMs =
      metadata["contextDurableCompactionLatestAtEpochMs"]?.toLongOrNull()
    val totalSummaryCount = if (includedSummaryCount != null || omittedSummaryCount != null) {
      (includedSummaryCount ?: 0) + (omittedSummaryCount ?: 0)
    } else {
      null
    }
    if (
      compactedThisRun == null &&
      sourceTranscriptMessageCount == null &&
      retainedTranscriptMessageCount == null &&
      latestCompactedMessageCount == null &&
      includedSummaryCount == null &&
      omittedSummaryCount == null &&
      totalCompactedMessageCount == null &&
      latestCompactedAtEpochMs == null
    ) {
      return null
    }
    return buildMap {
      compactedThisRun?.let { put("compactedThisRun", it) }
      sourceTranscriptMessageCount?.let { put("sourceTranscriptMessageCount", it) }
      retainedTranscriptMessageCount?.let { put("retainedTranscriptMessageCount", it) }
      latestCompactedMessageCount?.let { put("latestCompactedMessageCount", it) }
      includedSummaryCount?.let { put("includedSummaryCount", it) }
      omittedSummaryCount?.let { put("omittedSummaryCount", it) }
      totalCompactedMessageCount?.let { put("totalCompactedMessageCount", it) }
      totalSummaryCount?.let { put("totalSummaryCount", it) }
      latestCompactedAtEpochMs?.let { put("latestCompactedAtEpochMs", it) }
    }
  }

  private fun currentWorkspaceIdLocked(): String? = runCatching {
    workspaceRootProvider?.invoke()
  }.getOrNull()?.let { workspaceRoot ->
    AppWorkspaceIdentity.fromRoots(setOf(workspaceRoot))
  }

  private fun currentWorkspaceRootLocked(): Path? = runCatching {
    workspaceRootProvider?.invoke()
  }.getOrNull()

  private fun resolveSoulProfile(runtimeProfile: RuntimeSoulProfile?): SoulProfile? =
    soulProfileResolver.resolve(runtimeSoulProfileSeedFactory.create(runtimeProfile))

  private fun applicableSoulOverlayRecordsLocked(
    sessionId: String,
    workspaceId: String?,
  ): List<MemoryRecord> = applicableSoulOverlayRecords(
    records = personalizationLocalStore?.listMemoryRecords().orEmpty(),
    sessionId = sessionId,
    workspaceId = workspaceId,
  )

  private fun applicableSoulOverlayRecords(
    records: List<MemoryRecord>,
    sessionId: String,
    workspaceId: String?,
  ): List<MemoryRecord> = records.filter { record ->
      val metadata = debugMemoryMetadata(record) ?: return@filter false
      metadata.kind == DEBUG_MEMORY_KIND_USER_PREFERENCE &&
        metadata.status == DEBUG_MEMORY_STATUS_ACTIVE &&
        metadata.preferenceKey in SUPPORTED_SOUL_PREFERENCE_KEYS &&
        !metadata.preferenceValue.isNullOrBlank() &&
        debugSoulScopeMatches(
          scope = metadata.scope,
          sourceSessionId = metadata.sourceSessionId,
          recordWorkspaceId = metadata.workspaceId,
          requestSessionId = sessionId,
          requestWorkspaceId = workspaceId,
        )
    }

  private fun debugSoulScopeMatches(
    scope: String,
    sourceSessionId: String?,
    recordWorkspaceId: String?,
    requestSessionId: String,
    requestWorkspaceId: String?,
  ): Boolean = when (scope) {
    DEBUG_MEMORY_SCOPE_USER -> true
    DEBUG_MEMORY_SCOPE_SESSION -> sourceSessionId == requestSessionId
    DEBUG_MEMORY_SCOPE_WORKSPACE -> {
      val normalizedRecordWorkspaceId = recordWorkspaceId?.takeIf(String::isNotBlank)
      val normalizedRequestWorkspaceId = requestWorkspaceId?.takeIf(String::isNotBlank)
      when {
        normalizedRecordWorkspaceId == null && normalizedRequestWorkspaceId == null -> true
        normalizedRecordWorkspaceId != null && normalizedRequestWorkspaceId != null ->
          normalizedRecordWorkspaceId == normalizedRequestWorkspaceId
        else -> false
      }
    }

    else -> false
  }

  private fun storedSoulProfileToMap(
    profile: PersonalizationLocalStore.SoulProfile,
    document: WorkspaceSoulDocument?,
  ): Map<String, Any?> = buildMap {
    document?.relativePath?.let { relativePath ->
      put("relativePath", relativePath)
    }
    profile.presetName
      .takeIf(String::isNotBlank)
      ?.let { put("presetName", it) }
    profile.customLabel
      .takeIf(String::isNotBlank)
      ?.let { put("displayName", it) }
    profile.customGuidance
      .takeIf(String::isNotBlank)
      ?.let { put("customGuidance", it) }
    if (profile.extensions.isNotEmpty()) {
      put("extensions", profile.extensions.toSortedMap())
    }
  }

  private fun soulProfileToMap(
    resolvedProfile: SoulProfile?,
    runtimeProfile: RuntimeSoulProfile?,
  ): Map<String, Any?>? {
    if (resolvedProfile == null && runtimeProfile == null) {
      return null
    }
    return buildMap {
      (runtimeProfile?.presetName ?: resolvedProfile?.presetName)
        ?.takeIf(String::isNotBlank)
        ?.let { put("presetName", it) }
      (runtimeProfile?.displayName ?: resolvedProfile?.displayName)
        ?.takeIf(String::isNotBlank)
        ?.let { put("displayName", it) }
      (runtimeProfile?.voice ?: resolvedProfile?.voice)
        ?.takeIf(String::isNotBlank)
        ?.let { put("voice", it) }
      resolvedProfile?.preferredNaming
        ?.takeIf(String::isNotBlank)
        ?.let { put("preferredNaming", it) }
      resolvedProfile?.preferredAddressStyle?.name?.lowercase(Locale.US)
        ?.let { put("preferredAddressStyle", it) }
      resolvedProfile?.intimacyPermissionBand?.name?.lowercase(Locale.US)
        ?.let { put("intimacyPermissionBand", it) }
      resolvedProfile?.playfulnessPermissionBand?.name?.lowercase(Locale.US)
        ?.let { put("playfulnessPermissionBand", it) }
      resolvedProfile?.highIntimacyBehaviorAllowed
        ?.let { put("highIntimacyBehaviorAllowed", it.toString()) }
      resolvedProfile?.playfulAffectionAllowed
        ?.let { put("playfulAffectionAllowed", it.toString()) }
      (runtimeProfile?.customGuidance ?: resolvedProfile?.customGuidance)
        ?.takeIf(String::isNotBlank)
        ?.let { put("customGuidance", it) }
      resolvedProfile?.tone?.name?.lowercase(Locale.US)?.let { put("tone", it) }
      resolvedProfile?.verbosity?.name?.lowercase(Locale.US)?.let { put("verbosity", it) }
      resolvedProfile?.userRelationshipStyle?.name?.lowercase(Locale.US)
        ?.let { put("userRelationshipStyle", it) }
      resolvedProfile?.riskTolerance?.name?.lowercase(Locale.US)
        ?.let { put("riskTolerance", it) }
      resolvedProfile?.toolUseBias?.name?.lowercase(Locale.US)
        ?.let { put("toolUseBias", it) }
      if (!resolvedProfile?.escalationRules.isNullOrEmpty()) {
        put("escalationRules", resolvedProfile?.escalationRules.orEmpty())
      }
      if (!resolvedProfile?.forbiddenBehaviors.isNullOrEmpty()) {
        put("forbiddenBehaviors", resolvedProfile?.forbiddenBehaviors.orEmpty())
      }
      if (!resolvedProfile?.collaborationPreferences.isNullOrEmpty()) {
        put("collaborationPreferences", resolvedProfile?.collaborationPreferences.orEmpty())
      }
      if (!runtimeProfile?.extensions.isNullOrEmpty()) {
        put("extensions", runtimeProfile?.extensions.orEmpty().toSortedMap())
      }
    }
  }

  private fun interactionPreferenceDebugToMap(
    projection: InteractionPreferenceDebugProjection,
  ): Map<String, Any?> = buildMap {
    put("scope", projection.sourceScope.name.lowercase(Locale.US))
    projection.snapshotRecordId?.takeIf(String::isNotBlank)?.let { put("snapshotRecordId", it) }
    projection.preferredNaming?.takeIf(String::isNotBlank)?.let { put("preferredNaming", it) }
    projection.preferredAddressStyle?.name?.lowercase(Locale.US)
      ?.let { put("preferredAddressStyle", it) }
    projection.derivedRelationshipStyle?.takeIf(String::isNotBlank)
      ?.let { put("derivedRelationshipStyle", it) }
    put("state", interactionPreferenceStateToMap(projection.state))
  }

  private fun interactionPreferenceStateToMap(
    state: InteractionPreferenceState,
  ): Map<String, Any?> = buildMap {
    put("warmth", preferenceAxisStateToMap(state.warmth))
    put("formality", preferenceAxisStateToMap(state.formality))
    put("initiative", preferenceAxisStateToMap(state.initiative))
    put("addressStyle", preferredAddressStateToMap(state.addressStyle))
    state.preferredNaming?.takeIf(String::isNotBlank)?.let { put("preferredNaming", it) }
    put("preferredNamingSupport", state.preferredNamingSupport)
    state.lastUpdatedAtEpochMs?.let { put("lastUpdatedAtEpochMs", it) }
  }

  private fun preferenceAxisStateToMap(
    state: PreferenceAxisState,
  ): Map<String, Any?> = buildMap {
    put("offset", state.offset)
    put("higherSupport", state.higherSupport)
    put("lowerSupport", state.lowerSupport)
    state.lastUpdatedAtEpochMs?.let { put("lastUpdatedAtEpochMs", it) }
  }

  private fun preferredAddressStateToMap(
    state: PreferredAddressState,
  ): Map<String, Any?> = buildMap {
    put("selectedStyle", state.selectedStyle.name.lowercase(Locale.US))
    put("neutralSupport", state.neutralSupport)
    put("friendlySupport", state.friendlySupport)
    put("intimateSupport", state.intimateSupport)
    state.lastUpdatedAtEpochMs?.let { put("lastUpdatedAtEpochMs", it) }
  }

  private fun relationshipStateDebugToMap(
    projection: RelationshipStateDebugProjection,
  ): Map<String, Any?> = buildMap {
    put("scope", projection.sourceScope.name.lowercase(Locale.US))
    projection.snapshotRecordId?.takeIf(String::isNotBlank)?.let { put("snapshotRecordId", it) }
    if (projection.appliedEventRecordIds.isNotEmpty()) {
      put("appliedEventRecordIds", projection.appliedEventRecordIds)
    }
    put("state", relationshipStateToMap(projection.state))
    put("recentNegativeGuardActive", projection.recentNegativeGuardActive)
    put("supportiveStyleUnlocked", projection.supportiveStyleUnlocked)
    put("supportiveStyleChecks", projection.supportiveStyleChecks.map(::soulGateCheckToMap))
    put("warmToneUnlocked", projection.warmToneUnlocked)
    put("warmToneChecks", projection.warmToneChecks.map(::soulGateCheckToMap))
    projection.derivedAddressStyle?.name?.lowercase(Locale.US)
      ?.let { put("derivedAddressStyle", it) }
    put("friendlyAddressChecks", projection.friendlyAddressChecks.map(::soulGateCheckToMap))
    put("intimateAddressChecks", projection.intimateAddressChecks.map(::soulGateCheckToMap))
    put("intimacyPermissionBand", projection.intimacyPermissionBand.name.lowercase(Locale.US))
    put("playfulnessPermissionBand", projection.playfulnessPermissionBand.name.lowercase(Locale.US))
    put("highIntimacyBehaviorAllowed", projection.highIntimacyBehaviorAllowed)
    put("highIntimacyChecks", projection.highIntimacyChecks.map(::soulGateCheckToMap))
    put("playfulAffectionAllowed", projection.playfulAffectionAllowed)
    put("playfulAffectionChecks", projection.playfulAffectionChecks.map(::soulGateCheckToMap))
  }

  private fun relationshipStateToMap(
    state: RelationshipState,
  ): Map<String, Any?> = buildMap {
    put("familiarity", state.familiarity)
    put("trust", state.trust)
    put("safety", state.safety)
    put("intimacyPermission", state.intimacyPermission)
    put("playfulnessPermission", state.playfulnessPermission)
    put("affectionTendency", state.affectionTendency)
    put("reciprocity", state.reciprocity)
    state.lastPositiveEventAtEpochMs?.let { put("lastPositiveEventAtEpochMs", it) }
    state.lastNegativeEventAtEpochMs?.let { put("lastNegativeEventAtEpochMs", it) }
    state.lastUpdatedAtEpochMs?.let { put("lastUpdatedAtEpochMs", it) }
  }

  private fun soulGateCheckToMap(
    check: SoulGateCheck,
  ): Map<String, Any?> = buildMap {
    put("key", check.key)
    put("passed", check.passed)
    check.currentValue?.let { put("currentValue", it) }
    check.threshold?.let { put("threshold", it) }
    check.actualBoolean?.let { put("actualBoolean", it) }
    check.expectedBoolean?.let { put("expectedBoolean", it) }
  }

  private fun soulFieldSources(
    baseRuntimeSoul: RuntimeSoulProfile?,
    effectiveRuntimeSoul: RuntimeSoulProfile?,
    baseResolvedSoul: SoulProfile?,
    effectiveResolvedSoul: SoulProfile?,
    overlayRecords: List<MemoryRecord>,
    interactionPreferenceDebug: InteractionPreferenceDebugProjection?,
    relationshipStateDebug: RelationshipStateDebugProjection?,
  ): List<Map<String, Any?>> {
    val effectiveFieldValues = soulFieldValues(
      resolvedProfile = effectiveResolvedSoul,
      runtimeProfile = effectiveRuntimeSoul,
    )
    if (effectiveFieldValues.isEmpty()) {
      return emptyList()
    }
    val baseFieldValues = soulFieldValues(
      resolvedProfile = baseResolvedSoul,
      runtimeProfile = baseRuntimeSoul,
    )
    val overlayFieldSources = linkedMapOf<String, SoulFieldContribution>()
    overlayRecords
      .mapNotNull { record ->
        val metadata = debugMemoryMetadata(record) ?: return@mapNotNull null
        record to metadata
      }
      .sortedWith(
        compareBy<Pair<MemoryRecord, DebugMemoryMetadata>>(
          { (_, metadata) -> soulScopePriority(metadata.scope) },
          { (record, metadata) -> metadata.lastConfirmedAtEpochMs ?: record.updatedAtEpochMs },
          { (record, _) -> record.id },
        ),
      )
      .forEach { (record, metadata) ->
        overlayFieldContributions(record, metadata).forEach { contribution ->
          overlayFieldSources[contribution.field] = contribution
        }
      }
    val directFieldSources = overlayFieldSources.mapValues { (_, contribution) ->
      ResolvedSoulFieldSource(
        field = contribution.field,
        value = contribution.value,
        sourceType = "memory_overlay",
        sourceLabel = soulOverlaySourceLabel(contribution.metadata.scope),
        recordId = contribution.record.id,
        preferenceKey = contribution.metadata.preferenceKey.orEmpty(),
        sourceScope = contribution.metadata.scope,
        sourceDetail = contribution.metadata.preferenceTemporality?.let { temporality ->
          "${temporality.replaceFirstChar { ch -> ch.uppercaseChar() }} preference"
        }.orEmpty(),
      )
    }
    val interactionFieldSources = interactionPreferenceFieldSources(interactionPreferenceDebug)
    val relationshipFieldSources = relationshipStateFieldSources(relationshipStateDebug)

    return SOUL_FIELD_ORDER.mapNotNull { field ->
      val value = effectiveFieldValues[field] ?: return@mapNotNull null
      val resolvedSource = prioritizedFieldSources(
        field = field,
        directFieldSources = directFieldSources,
        interactionFieldSources = interactionFieldSources,
        relationshipFieldSources = relationshipFieldSources,
      ).firstOrNull { source -> source.value == value }
      if (resolvedSource != null) {
        fieldSourceToMap(resolvedSource)
      } else if (baseFieldValues.containsKey(field)) {
        fieldSourceToMap(
          ResolvedSoulFieldSource(
            field = field,
            value = value,
            sourceType = "stored_soul",
            sourceLabel = if (field == SOUL_FIELD_PRESET_NAME) {
              "stored soul preset"
            } else {
              "stored soul"
            },
          ),
        )
      } else {
        null
      }
    }
  }

  private fun soulFieldValues(
    resolvedProfile: SoulProfile?,
    runtimeProfile: RuntimeSoulProfile?,
  ): Map<String, String> = linkedMapOf<String, String>().apply {
    (runtimeProfile?.presetName ?: resolvedProfile?.presetName)
      ?.takeIf(String::isNotBlank)
      ?.let { put(SOUL_FIELD_PRESET_NAME, it) }
    (runtimeProfile?.displayName ?: resolvedProfile?.displayName)
      ?.takeIf(String::isNotBlank)
      ?.let { put(SOUL_FIELD_DISPLAY_NAME, it) }
    (runtimeProfile?.voice ?: resolvedProfile?.voice)
      ?.takeIf(String::isNotBlank)
      ?.let { put(SOUL_FIELD_VOICE, it) }
    resolvedProfile?.preferredNaming
      ?.takeIf(String::isNotBlank)
      ?.let { put(SOUL_FIELD_PREFERRED_NAMING, it) }
    resolvedProfile?.preferredAddressStyle?.name?.lowercase(Locale.US)
      ?.let { put(SOUL_FIELD_PREFERRED_ADDRESS_STYLE, it) }
    resolvedProfile?.intimacyPermissionBand?.name?.lowercase(Locale.US)
      ?.let { put(SOUL_FIELD_INTIMACY_PERMISSION_BAND, it) }
    resolvedProfile?.playfulnessPermissionBand?.name?.lowercase(Locale.US)
      ?.let { put(SOUL_FIELD_PLAYFULNESS_PERMISSION_BAND, it) }
    resolvedProfile?.highIntimacyBehaviorAllowed
      ?.let { put(SOUL_FIELD_HIGH_INTIMACY_BEHAVIOR_ALLOWED, it.toString()) }
    resolvedProfile?.playfulAffectionAllowed
      ?.let { put(SOUL_FIELD_PLAYFUL_AFFECTION_ALLOWED, it.toString()) }
    (runtimeProfile?.customGuidance ?: resolvedProfile?.customGuidance)
      ?.takeIf(String::isNotBlank)
      ?.let { put(SOUL_FIELD_CUSTOM_GUIDANCE, it) }
    resolvedProfile?.tone?.name?.lowercase(Locale.US)?.let { put(SOUL_FIELD_TONE, it) }
    resolvedProfile?.verbosity?.name?.lowercase(Locale.US)?.let { put(SOUL_FIELD_VERBOSITY, it) }
    resolvedProfile?.userRelationshipStyle?.name?.lowercase(Locale.US)
      ?.let { put(SOUL_FIELD_USER_RELATIONSHIP_STYLE, it) }
    resolvedProfile?.riskTolerance?.name?.lowercase(Locale.US)
      ?.let { put(SOUL_FIELD_RISK_TOLERANCE, it) }
    resolvedProfile?.toolUseBias?.name?.lowercase(Locale.US)
      ?.let { put(SOUL_FIELD_TOOL_USE_BIAS, it) }
    resolvedProfile?.escalationRules
      ?.takeIf(List<String>::isNotEmpty)
      ?.joinToString(separator = " | ")
      ?.let { put(SOUL_FIELD_ESCALATION_RULES, it) }
    resolvedProfile?.forbiddenBehaviors
      ?.takeIf(List<String>::isNotEmpty)
      ?.joinToString(separator = " | ")
      ?.let { put(SOUL_FIELD_FORBIDDEN_BEHAVIORS, it) }
    resolvedProfile?.collaborationPreferences
      ?.takeIf(List<String>::isNotEmpty)
      ?.joinToString(separator = " | ")
      ?.let { put(SOUL_FIELD_COLLABORATION_PREFERENCES, it) }
  }

  private fun overlayFieldContributions(
    record: MemoryRecord,
    metadata: DebugMemoryMetadata,
  ): List<SoulFieldContribution> {
    val contributions = mutableListOf<SoulFieldContribution>()
    var hasTypedDisplayName = false
    var hasTypedVoice = false
    var hasTypedTone = false
    var hasTypedVerbosity = false
    var hasTypedPreferredNaming = false
    var hasTypedPreferredAddressStyle = false

    fun addScalar(field: String, raw: String?) {
      val normalized = normalizeDebugSoulScalarOrNull(raw) ?: return
      contributions += SoulFieldContribution(
        field = field,
        value = normalized,
        record = record,
        metadata = metadata,
      )
      when (field) {
        SOUL_FIELD_DISPLAY_NAME -> hasTypedDisplayName = true
        SOUL_FIELD_VOICE -> hasTypedVoice = true
        SOUL_FIELD_PREFERRED_NAMING -> hasTypedPreferredNaming = true
      }
    }

    fun addKey(field: String, raw: String?) {
      val normalized = normalizeDebugSoulKeyOrNull(raw) ?: return
      contributions += SoulFieldContribution(
        field = field,
        value = normalized,
        record = record,
        metadata = metadata,
      )
      when (field) {
        SOUL_FIELD_TONE -> hasTypedTone = true
        SOUL_FIELD_VERBOSITY -> hasTypedVerbosity = true
        SOUL_FIELD_PREFERRED_ADDRESS_STYLE -> hasTypedPreferredAddressStyle = true
      }
    }

    addScalar(SOUL_FIELD_DISPLAY_NAME, record.extensions[MemorySoulExtensionKeys.DISPLAY_NAME])
    addScalar(SOUL_FIELD_VOICE, record.extensions[MemorySoulExtensionKeys.VOICE])
    addScalar(SOUL_FIELD_PREFERRED_NAMING, record.extensions[MemorySoulExtensionKeys.PREFERRED_NAMING])
    addKey(SOUL_FIELD_TONE, record.extensions[MemorySoulExtensionKeys.TONE])
    addKey(SOUL_FIELD_VERBOSITY, record.extensions[MemorySoulExtensionKeys.VERBOSITY])
    addKey(
      SOUL_FIELD_PREFERRED_ADDRESS_STYLE,
      record.extensions[MemorySoulExtensionKeys.PREFERRED_ADDRESS_STYLE],
    )
    addKey(
      SOUL_FIELD_USER_RELATIONSHIP_STYLE,
      record.extensions[MemorySoulExtensionKeys.USER_RELATIONSHIP_STYLE],
    )
    addKey(
      SOUL_FIELD_RISK_TOLERANCE,
      record.extensions[MemorySoulExtensionKeys.RISK_TOLERANCE],
    )
    addKey(SOUL_FIELD_TOOL_USE_BIAS, record.extensions[MemorySoulExtensionKeys.TOOL_USE_BIAS])

    when (metadata.preferenceKey) {
      MemoryPreferenceKeys.AGENT_DISPLAY_NAME -> {
        if (!hasTypedDisplayName) {
          normalizeDebugSoulScalarOrNull(metadata.preferenceValue)?.let { value ->
            contributions += SoulFieldContribution(
              field = SOUL_FIELD_DISPLAY_NAME,
              value = value,
              record = record,
              metadata = metadata,
            )
          }
        }
      }

      MemoryPreferenceKeys.AGENT_STYLE_PROFILE -> {
        if (!hasTypedTone) {
          when (normalizeDebugSoulKeyOrNull(metadata.preferenceValue)) {
            "warm" -> contributions += SoulFieldContribution(
              field = SOUL_FIELD_TONE,
              value = "warm",
              record = record,
              metadata = metadata,
            )

            "serious" -> contributions += SoulFieldContribution(
              field = SOUL_FIELD_TONE,
              value = "steady",
              record = record,
              metadata = metadata,
            )
          }
        }
        if (!hasTypedVoice) {
          when (normalizeDebugSoulKeyOrNull(metadata.preferenceValue)) {
            "warm" -> contributions += SoulFieldContribution(
              field = SOUL_FIELD_VOICE,
              value = "warm and gentle",
              record = record,
              metadata = metadata,
            )

            "serious" -> contributions += SoulFieldContribution(
              field = SOUL_FIELD_VOICE,
              value = "serious and formal",
              record = record,
              metadata = metadata,
            )
          }
        }
      }

      MemoryPreferenceKeys.AGENT_VERBOSITY -> {
        if (!hasTypedVerbosity) {
          when (normalizeDebugSoulKeyOrNull(metadata.preferenceValue)) {
            "terse",
            "balanced",
            "expansive",
            -> contributions += SoulFieldContribution(
              field = SOUL_FIELD_VERBOSITY,
              value = normalizeDebugSoulKeyOrNull(metadata.preferenceValue).orEmpty(),
              record = record,
              metadata = metadata,
            )
          }
        }
      }

      MemoryPreferenceKeys.USER_PREFERRED_NAME -> {
        if (!hasTypedPreferredNaming) {
          normalizeDebugSoulScalarOrNull(metadata.preferenceValue)?.let { value ->
            contributions += SoulFieldContribution(
              field = SOUL_FIELD_PREFERRED_NAMING,
              value = value,
              record = record,
              metadata = metadata,
            )
          }
        }
      }

      MemoryPreferenceKeys.USER_ADDRESS_STYLE -> {
        if (!hasTypedPreferredAddressStyle) {
          normalizeDebugSoulKeyOrNull(metadata.preferenceValue)?.let { normalized ->
            when (normalized) {
              "neutral",
              "friendly",
              "intimate",
              -> contributions += SoulFieldContribution(
                field = SOUL_FIELD_PREFERRED_ADDRESS_STYLE,
                value = normalized,
                record = record,
                metadata = metadata,
              )
            }
          }
        }
      }
    }

    return contributions
  }

  private fun interactionPreferenceFieldSources(
    projection: InteractionPreferenceDebugProjection?,
  ): Map<String, ResolvedSoulFieldSource> {
    if (projection == null) {
      return emptyMap()
    }
    val sourceLabel = when (projection.sourceScope) {
      MemoryScope.WORKSPACE -> "workspace interaction preference"
      MemoryScope.SESSION -> "session interaction preference"
      MemoryScope.USER -> "user interaction preference"
    }
    val sourceScope = projection.sourceScope.name.lowercase(Locale.US)
    val recordId = projection.snapshotRecordId.orEmpty()
    val fields = linkedMapOf<String, ResolvedSoulFieldSource>()
    projection.preferredNaming
      ?.takeIf(String::isNotBlank)
      ?.let { preferredNaming ->
        fields[SOUL_FIELD_PREFERRED_NAMING] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_PREFERRED_NAMING,
          value = preferredNaming,
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference snapshot",
        )
      }
    projection.preferredAddressStyle
      ?.name
      ?.lowercase(Locale.US)
      ?.let { preferredAddressStyle ->
        fields[SOUL_FIELD_PREFERRED_ADDRESS_STYLE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_PREFERRED_ADDRESS_STYLE,
          value = preferredAddressStyle,
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference snapshot",
        )
      }
    when (projection.derivedRelationshipStyle) {
      "warm" -> {
        fields[SOUL_FIELD_TONE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_TONE,
          value = "warm",
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference style",
        )
        fields[SOUL_FIELD_VOICE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_VOICE,
          value = "warm and gentle",
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference style",
        )
        fields[SOUL_FIELD_USER_RELATIONSHIP_STYLE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_USER_RELATIONSHIP_STYLE,
          value = "supportive",
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference style",
        )
      }

      "serious" -> {
        fields[SOUL_FIELD_TONE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_TONE,
          value = "steady",
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference style",
        )
        fields[SOUL_FIELD_VOICE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_VOICE,
          value = "serious and formal",
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference style",
        )
        fields[SOUL_FIELD_USER_RELATIONSHIP_STYLE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_USER_RELATIONSHIP_STYLE,
          value = "direct",
          sourceType = "interaction_preference",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = "Projected interaction-preference style",
        )
      }
    }
    return fields
  }

  private fun relationshipStateFieldSources(
    projection: RelationshipStateDebugProjection?,
  ): Map<String, ResolvedSoulFieldSource> {
    if (projection == null) {
      return emptyMap()
    }
    val sourceLabel = when (projection.sourceScope) {
      MemoryScope.WORKSPACE -> "workspace relationship state"
      MemoryScope.SESSION -> "session relationship state"
      MemoryScope.USER -> "user relationship state"
    }
    val sourceScope = projection.sourceScope.name.lowercase(Locale.US)
    val recordId = projection.snapshotRecordId.orEmpty()
    val eventSuffix = if (projection.appliedEventRecordIds.isNotEmpty()) {
      " + ${projection.appliedEventRecordIds.size} event(s)"
    } else {
      ""
    }
    val fields = linkedMapOf<String, ResolvedSoulFieldSource>()
    if (projection.supportiveStyleUnlocked) {
      fields[SOUL_FIELD_USER_RELATIONSHIP_STYLE] = ResolvedSoulFieldSource(
        field = SOUL_FIELD_USER_RELATIONSHIP_STYLE,
        value = "supportive",
        sourceType = "relationship_state",
        sourceLabel = sourceLabel,
        recordId = recordId,
        sourceScope = sourceScope,
        sourceDetail = "Derived from relationship gates$eventSuffix",
      )
    }
    if (projection.warmToneUnlocked) {
      fields[SOUL_FIELD_TONE] = ResolvedSoulFieldSource(
        field = SOUL_FIELD_TONE,
        value = "warm",
        sourceType = "relationship_state",
        sourceLabel = sourceLabel,
        recordId = recordId,
        sourceScope = sourceScope,
        sourceDetail = "Derived from relationship gates$eventSuffix",
      )
      fields[SOUL_FIELD_VOICE] = ResolvedSoulFieldSource(
        field = SOUL_FIELD_VOICE,
        value = "warm and gentle",
        sourceType = "relationship_state",
        sourceLabel = sourceLabel,
        recordId = recordId,
        sourceScope = sourceScope,
        sourceDetail = "Derived from relationship gates$eventSuffix",
      )
    }
    projection.derivedAddressStyle
      ?.name
      ?.lowercase(Locale.US)
      ?.let { derivedAddressStyle ->
        fields[SOUL_FIELD_PREFERRED_ADDRESS_STYLE] = ResolvedSoulFieldSource(
          field = SOUL_FIELD_PREFERRED_ADDRESS_STYLE,
          value = derivedAddressStyle,
          sourceType = "relationship_state",
          sourceLabel = sourceLabel,
          recordId = recordId,
          sourceScope = sourceScope,
          sourceDetail = if (projection.recentNegativeGuardActive) {
            "Derived from relationship gates with recent-negative guard"
          } else {
            "Derived from relationship gates$eventSuffix"
          },
        )
      }
    fields[SOUL_FIELD_INTIMACY_PERMISSION_BAND] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_INTIMACY_PERMISSION_BAND,
      value = projection.intimacyPermissionBand.name.lowercase(Locale.US),
      sourceType = "relationship_state",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = "Derived from relationship-state score band",
    )
    fields[SOUL_FIELD_PLAYFULNESS_PERMISSION_BAND] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_PLAYFULNESS_PERMISSION_BAND,
      value = projection.playfulnessPermissionBand.name.lowercase(Locale.US),
      sourceType = "relationship_state",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = "Derived from relationship-state score band",
    )
    fields[SOUL_FIELD_HIGH_INTIMACY_BEHAVIOR_ALLOWED] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_HIGH_INTIMACY_BEHAVIOR_ALLOWED,
      value = projection.highIntimacyBehaviorAllowed.toString(),
      sourceType = "relationship_state",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = if (projection.recentNegativeGuardActive) {
        "Relationship gate blocked by recent-negative guard"
      } else {
        "Relationship gate derived from trust/safety/reciprocity/intimacy"
      },
    )
    fields[SOUL_FIELD_PLAYFUL_AFFECTION_ALLOWED] = ResolvedSoulFieldSource(
      field = SOUL_FIELD_PLAYFUL_AFFECTION_ALLOWED,
      value = projection.playfulAffectionAllowed.toString(),
      sourceType = "relationship_state",
      sourceLabel = sourceLabel,
      recordId = recordId,
      sourceScope = sourceScope,
      sourceDetail = if (projection.recentNegativeGuardActive) {
        "Relationship gate blocked by recent-negative guard"
      } else {
        "Relationship gate derived from playfulness/safety/reciprocity"
      },
    )
    return fields
  }

  private fun prioritizedFieldSources(
    field: String,
    directFieldSources: Map<String, ResolvedSoulFieldSource>,
    interactionFieldSources: Map<String, ResolvedSoulFieldSource>,
    relationshipFieldSources: Map<String, ResolvedSoulFieldSource>,
  ): List<ResolvedSoulFieldSource> = when (field) {
    SOUL_FIELD_TONE,
    SOUL_FIELD_VOICE,
    SOUL_FIELD_USER_RELATIONSHIP_STYLE,
    -> listOfNotNull(
      directFieldSources[field],
      relationshipFieldSources[field],
      interactionFieldSources[field],
    )

    SOUL_FIELD_PREFERRED_ADDRESS_STYLE -> listOfNotNull(
      directFieldSources[field],
      interactionFieldSources[field],
      relationshipFieldSources[field],
    )

    SOUL_FIELD_PREFERRED_NAMING -> listOfNotNull(
      directFieldSources[field],
      interactionFieldSources[field],
    )

    SOUL_FIELD_INTIMACY_PERMISSION_BAND,
    SOUL_FIELD_PLAYFULNESS_PERMISSION_BAND,
    SOUL_FIELD_HIGH_INTIMACY_BEHAVIOR_ALLOWED,
    SOUL_FIELD_PLAYFUL_AFFECTION_ALLOWED,
    -> listOfNotNull(relationshipFieldSources[field])

    else -> listOfNotNull(directFieldSources[field])
  }

  private fun fieldSourceToMap(
    source: ResolvedSoulFieldSource,
  ): Map<String, Any?> = buildMap {
    put("field", source.field)
    put("value", source.value)
    put("sourceType", source.sourceType)
    put("sourceLabel", source.sourceLabel)
    if (source.recordId.isNotBlank()) {
      put("recordId", source.recordId)
    }
    if (source.preferenceKey.isNotBlank()) {
      put("preferenceKey", source.preferenceKey)
    }
    if (source.sourceScope.isNotBlank()) {
      put("sourceScope", source.sourceScope)
    }
    if (source.sourceDetail.isNotBlank()) {
      put("sourceDetail", source.sourceDetail)
    }
  }

  private fun soulOverlayDisplayComparator(): Comparator<MemoryRecord> =
    compareByDescending<MemoryRecord> { record ->
      debugMemoryMetadata(record)?.let { metadata -> soulScopePriority(metadata.scope) } ?: 0
    }.thenByDescending { record ->
      debugMemoryMetadata(record)?.lastConfirmedAtEpochMs ?: record.updatedAtEpochMs
    }.thenBy { record ->
      record.id
    }

  private fun soulOverlaySourceLabel(scope: String): String = when (scope) {
    DEBUG_MEMORY_SCOPE_SESSION -> "session memory"
    DEBUG_MEMORY_SCOPE_WORKSPACE -> "workspace memory"
    else -> "user memory"
  }

  private fun soulScopePriority(scope: String): Int = when (scope) {
    DEBUG_MEMORY_SCOPE_WORKSPACE -> 1
    DEBUG_MEMORY_SCOPE_USER -> 2
    DEBUG_MEMORY_SCOPE_SESSION -> 3
    else -> 0
  }

  private fun memoryDebugRecordToMap(
    record: MemoryRecord,
    observedAtEpochMs: Long,
  ): Map<String, Any?> {
    val metadata = debugMemoryMetadata(record)
    val expiryReferenceEpochMs = metadata?.lastConfirmedAtEpochMs ?: record.updatedAtEpochMs
    val isExpired = metadata?.ttlMs?.let { ttlMs ->
      expiryReferenceEpochMs + ttlMs <= observedAtEpochMs
    } ?: false
    return buildMap {
      put("id", record.id)
      put("content", record.content)
      put("recordVersion", record.recordVersion)
      put("createdAtEpochMs", record.createdAtEpochMs)
      put("updatedAtEpochMs", record.updatedAtEpochMs)
      if (record.tags.isNotEmpty()) {
        put("tags", record.tags)
      }
      if (record.extensions.isNotEmpty()) {
        put("extensions", record.extensions.toSortedMap())
      }
      metadata?.kind?.let { put("kind", it) }
      metadata?.scope?.let { put("scope", it) }
      metadata?.status?.let { put("status", it) }
      metadata?.source?.let { put("source", it) }
      metadata?.sourceSessionId?.let { put("sourceSessionId", it) }
      metadata?.sourceTaskId?.let { put("sourceTaskId", it) }
      metadata?.workspaceId?.let { put("workspaceId", it) }
      metadata?.preferenceKey?.let { put("preferenceKey", it) }
      metadata?.preferenceValue?.let { put("preferenceValue", it) }
      metadata?.preferenceTemporality?.let { put("preferenceTemporality", it) }
      metadata?.lastConfirmedAtEpochMs?.let { put("lastConfirmedAtEpochMs", it) }
      metadata?.resolvedAtEpochMs?.let { put("resolvedAtEpochMs", it) }
      metadata?.ttlMs?.let { put("ttlMs", it) }
      metadata?.resolutionReason?.let { put("resolutionReason", it) }
      metadata?.supersededBy?.let { put("supersededBy", it) }
      put("isExpired", isExpired)
    }
  }

  private fun debugRunLinkToMap(run: AgentRunSnapshot): Map<String, Any?> = buildMap {
    put("sessionId", run.sessionId)
    put("runId", run.runId)
    put("taskId", run.taskId)
    put("acceptedAtEpochMs", run.acceptedAtEpochMs)
    put("updatedAtEpochMs", run.updatedAtEpochMs)
    run.lifecycleState?.name?.lowercase()?.let { lifecycleState ->
      put("lifecycleState", lifecycleState)
    }
    run.executionStatus?.name?.lowercase()?.let { executionStatus ->
      put("executionStatus", executionStatus)
    }
    run.errorCode?.takeIf(String::isNotBlank)?.let { errorCode ->
      put("errorCode", errorCode)
    }
  }

  private fun rememberMemoryWriteActions(
    target: MutableMap<String, LinkedHashMap<String, Map<String, Any?>>>,
    run: AgentRunSnapshot,
    event: OpenCrayMemoryWriteEvent,
  ) {
    rememberMemoryActionIds(
      target = target,
      run = run,
      action = "written",
      occurredAtEpochMs = event.emittedAtEpochMs,
      recordIds = event.writtenRecordIds,
    )
    rememberMemoryActionIds(
      target = target,
      run = run,
      action = "resolved",
      occurredAtEpochMs = event.emittedAtEpochMs,
      recordIds = event.resolvedRecordIds,
    )
    rememberMemoryActionIds(
      target = target,
      run = run,
      action = "reaffirmed",
      occurredAtEpochMs = event.emittedAtEpochMs,
      recordIds = event.reaffirmedRecordIds,
    )
    rememberMemoryActionIds(
      target = target,
      run = run,
      action = "expired",
      occurredAtEpochMs = event.emittedAtEpochMs,
      recordIds = event.expiredRecordIds,
    )
  }

  private fun rememberMemoryActionIds(
    target: MutableMap<String, LinkedHashMap<String, Map<String, Any?>>>,
    run: AgentRunSnapshot,
    action: String,
    occurredAtEpochMs: Long,
    recordIds: List<String>,
  ) {
    recordIds.forEach { recordId ->
      rememberDebugLink(
        target = target,
        recordId = recordId,
        uniqueKey = "$action:${run.runId}:$occurredAtEpochMs:$recordId",
        payload = mapOf(
          "action" to action,
          "occurredAtEpochMs" to occurredAtEpochMs,
          "run" to debugRunLinkToMap(run),
        ),
      )
    }
  }

  private fun rememberMemoryRetrievalLinks(
    target: MutableMap<String, LinkedHashMap<String, Map<String, Any?>>>,
    run: AgentRunSnapshot,
    event: OpenCrayMemoryRetrievalEvent,
  ) {
    event.recordIds.forEach { recordId ->
      rememberDebugLink(
        target = target,
        recordId = recordId,
        uniqueKey = "retrieval:${run.runId}:${event.emittedAtEpochMs}:$recordId:${event.operation}",
        payload = buildMap {
          put("occurredAtEpochMs", event.emittedAtEpochMs)
          put("run", debugRunLinkToMap(run))
          put("toolName", event.toolName)
          put("operation", event.operation)
          event.query?.let { query -> put("query", query) }
          if (event.queryTerms.isNotEmpty()) {
            put("queryTerms", event.queryTerms)
          }
          if (event.paths.isNotEmpty()) {
            put("paths", event.paths)
          }
          if (event.lineRanges.isNotEmpty()) {
            put("lineRanges", event.lineRanges)
          }
          event.path?.let { path -> put("path", path) }
          event.fromLine?.let { fromLine -> put("fromLine", fromLine) }
          event.returnedLineCount?.let { returnedLineCount ->
            put("returnedLineCount", returnedLineCount)
          }
        },
      )
    }
  }

  private fun rememberDebugLink(
    target: MutableMap<String, LinkedHashMap<String, Map<String, Any?>>>,
    recordId: String,
    uniqueKey: String,
    payload: Map<String, Any?>,
  ) {
    if (recordId.isBlank()) {
      return
    }
    target.getOrPut(recordId) { linkedMapOf() }[uniqueKey] = payload
  }

  private fun finalizeDebugLinks(
    raw: LinkedHashMap<String, Map<String, Any?>>?,
  ): List<Map<String, Any?>> = raw
    ?.values
    .orEmpty()
    .sortedByDescending { entry ->
      entry["occurredAtEpochMs"] as? Long
        ?: (entry["occurredAtEpochMs"] as? Int)?.toLong()
        ?: 0L
    }
    .take(MAX_DEBUG_LINKS_PER_RECORD)

  private fun splitDebugCsv(raw: String?): List<String> = raw
    .orEmpty()
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)

  private fun debugMemoryMetadata(record: MemoryRecord): DebugMemoryMetadata? {
    val kind = debugParseTaggedMemoryValue(
      extensionValue = record.extensions[MemoryRecordExtensionKeys.KIND],
      tags = record.tags,
      tagPrefix = "kind:",
    ) ?: return null
    val scope = debugParseTaggedMemoryValue(
      extensionValue = record.extensions[MemoryRecordExtensionKeys.SCOPE],
      tags = record.tags,
      tagPrefix = "scope:",
    ) ?: return null
    val status = debugParseTaggedMemoryValue(
      extensionValue = record.extensions[MemoryRecordExtensionKeys.STATUS],
      tags = record.tags,
      tagPrefix = "status:",
    ) ?: return null
    return DebugMemoryMetadata(
      kind = kind,
      scope = scope,
      status = status,
      source = normalizeDebugSoulKeyOrNull(record.extensions[MemoryRecordExtensionKeys.SOURCE]),
      sourceSessionId = record.extensions[MemoryRecordExtensionKeys.SOURCE_SESSION_ID]
        ?.takeIf(String::isNotBlank),
      sourceTaskId = record.extensions[MemoryRecordExtensionKeys.SOURCE_TASK_ID]
        ?.takeIf(String::isNotBlank),
      workspaceId = record.extensions[MemoryRecordExtensionKeys.WORKSPACE_ID]
        ?.takeIf(String::isNotBlank),
      ttlMs = record.extensions[MemoryRecordExtensionKeys.TTL_MS]?.toLongOrNull(),
      lastConfirmedAtEpochMs =
        record.extensions[MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS]?.toLongOrNull(),
      resolvedAtEpochMs =
        record.extensions[MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS]?.toLongOrNull(),
      resolutionReason = normalizeDebugSoulKeyOrNull(
        record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON],
      ),
      supersededBy = record.extensions[MemoryRecordExtensionKeys.SUPERSEDED_BY]
        ?.takeIf(String::isNotBlank),
      preferenceKey = normalizeDebugSoulKeyOrNull(
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY],
      ),
      preferenceValue = normalizeDebugSoulScalarOrNull(
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_VALUE],
      ),
      preferenceTemporality = normalizeDebugSoulKeyOrNull(
        record.extensions[MemoryRecordExtensionKeys.PREFERENCE_TEMPORALITY],
      ),
    )
  }

  private fun debugParseTaggedMemoryValue(
    extensionValue: String?,
    tags: List<String>,
    tagPrefix: String,
  ): String? {
    normalizeDebugSoulKeyOrNull(extensionValue)?.let { return it }
    return tags
      .firstOrNull { tag -> tag.startsWith(tagPrefix) }
      ?.substringAfter(tagPrefix)
      ?.let(::normalizeDebugSoulKeyOrNull)
  }

  private fun normalizeDebugSoulKeyOrNull(raw: String?): String? =
    raw
      ?.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
      ?.replace(Regex("[\\s\\-]+"), "_")
      ?.replace(Regex("_+"), "_")
      ?.trim('_')
      ?.lowercase(Locale.US)
      ?.takeIf(String::isNotEmpty)

  private fun normalizeDebugSoulScalarOrNull(raw: String?): String? =
    raw
      ?.replace(Regex("\\s+"), " ")
      ?.trim()
      ?.takeIf(String::isNotEmpty)

  private fun PersonalizationLocalStore.SoulProfile.toRuntimeSoulProfile(): RuntimeSoulProfile =
    RuntimeSoulProfile(
      presetName = presetName.ifBlank { null },
      displayName = customLabel.ifBlank { null },
      customGuidance = customGuidance.ifBlank { null },
      extensions = extensions.filter { (key, value) ->
        key.isNotBlank() &&
          value.isNotBlank() &&
          key.trim().lowercase(Locale.US) !in RESERVED_SOUL_PROFILE_KEYS
      },
    )

  private fun activeSkillFromMetadata(metadata: Map<String, String>): Map<String, Any?>? {
    val name = metadata["contextActiveSkillName"]?.takeIf(String::isNotBlank)
    val relativePath = metadata["contextActiveSkillRelativePath"]?.takeIf(String::isNotBlank)
    val invocationControl = metadata["contextActiveSkillInvocationControl"]?.takeIf(String::isNotBlank)
    val executionContext = metadata["contextActiveSkillExecutionContext"]?.takeIf(String::isNotBlank)
    val activationSource = metadata["contextActiveSkillActivationSource"]?.takeIf(String::isNotBlank)
    val toolRestrictionEnabled = metadata["contextActiveSkillToolRestrictionEnabled"]?.toBooleanStrictOrNull()
    val truncated = metadata["contextActiveSkillTruncated"]?.toBooleanStrictOrNull()
    val allowedToolKeys = metadata["contextActiveSkillAllowedTools"]
      .orEmpty()
      .split(',')
      .map(String::trim)
      .filter(String::isNotBlank)
    if (
      name == null &&
      relativePath == null &&
      invocationControl == null &&
      executionContext == null &&
      activationSource == null &&
      toolRestrictionEnabled == null &&
      truncated == null &&
      allowedToolKeys.isEmpty()
    ) {
      return null
    }
    return buildMap {
      name?.let { put("name", it) }
      relativePath?.let { put("relativePath", it) }
      invocationControl?.let { put("invocationControl", it) }
      executionContext?.let { put("executionContext", it) }
      activationSource?.let { put("activationSource", it) }
      toolRestrictionEnabled?.let { put("toolRestrictionEnabled", it) }
      truncated?.let { put("truncated", it) }
      if (allowedToolKeys.isNotEmpty()) {
        put("allowedToolKeys", allowedToolKeys)
      }
    }
  }

  private fun parseBootstrapFileTrace(raw: String): List<Map<String, Any?>> = raw
    .split(';')
    .map(String::trim)
    .filter(String::isNotBlank)
    .mapNotNull { token ->
      val match = BOOTSTRAP_FILE_TRACE_REGEX.matchEntire(token) ?: return@mapNotNull null
      mapOf(
        "name" to match.groupValues[1],
        "relativePath" to match.groupValues[2],
        "sourceCharCount" to match.groupValues[3].toIntOrNull(),
        "injectedCharCount" to match.groupValues[4].toIntOrNull(),
        "truncated" to match.groupValues[5].toBooleanStrictOrNull(),
      )
    }

  private fun parseSelectedMemoryTrace(raw: String): List<Map<String, Any?>> = raw
    .split(';')
    .map(String::trim)
    .filter(String::isNotBlank)
    .mapNotNull { token ->
      val match = MEMORY_SELECTED_TRACE_REGEX.matchEntire(token) ?: return@mapNotNull null
      val matchedTerms = match.groupValues[3]
        .split('|')
        .map(String::trim)
        .filter(String::isNotBlank)
      mapOf(
        "id" to match.groupValues[1],
        "score" to match.groupValues[2].toIntOrNull(),
        "matchedTerms" to matchedTerms,
      )
    }

  private fun parseOmittedMemoryTrace(raw: String): List<Map<String, Any?>> = raw
    .split(';')
    .map(String::trim)
    .filter(String::isNotBlank)
    .mapNotNull { token ->
      val id = token.substringBefore(':').trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
      val reason = token.substringAfter(':', missingDelimiterValue = "").trim().takeIf(String::isNotBlank)
        ?: return@mapNotNull null
      mapOf(
        "id" to id,
        "reason" to reason,
      )
    }

  private fun parseVisibleSkillTrace(raw: String): List<Map<String, Any?>> = raw
    .split(';')
    .map(String::trim)
    .filter(String::isNotBlank)
    .mapNotNull { token ->
      val match = VISIBLE_SKILL_TRACE_REGEX.matchEntire(token) ?: return@mapNotNull null
      mapOf(
        "name" to match.groupValues[1],
        "relativePath" to match.groupValues[2],
        "invocationControl" to match.groupValues[3],
        "userInvocable" to match.groupValues[4].toBooleanStrictOrNull(),
        "executionContext" to match.groupValues[5],
      )
    }

  private fun parseFilteredMemoryCounts(raw: String): Map<String, Int> = raw
    .split(',')
    .map(String::trim)
    .filter(String::isNotBlank)
    .mapNotNull { token ->
      val reason = token.substringBefore(':').trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
      val count = token.substringAfter(':', missingDelimiterValue = "").trim().toIntOrNull()
        ?: return@mapNotNull null
      reason to count
    }
    .toMap(linkedMapOf())

  private fun JsonObject.replayString(key: String): String? =
    (this[key] as? JsonPrimitive)
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun JsonObject.replayInt(key: String): Int? =
    (this[key] as? JsonPrimitive)
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.toIntOrNull()

  private fun JsonObject.replayObject(key: String): JsonObject? =
    this[key] as? JsonObject

  private fun JsonObject.replayStringMap(key: String): Map<String, String> =
    replayObject(key)
      ?.mapNotNull { (entryKey, entryValue) ->
        (entryValue as? JsonPrimitive)
          ?.content
          ?.trim()
          ?.takeIf { value -> value.isNotBlank() }
          ?.let { value -> entryKey to value }
      }
      ?.toMap(linkedMapOf())
      .orEmpty()

  private fun runIdFor(task: AgentTask): String =
    task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
      ?.takeIf(String::isNotBlank)
      ?: task.id

  private fun runtimeEventToMap(event: OpenCrayAgentRunEvent): Map<String, Any?> = when (event) {
    is OpenCrayLifecycleEvent -> mapOf(
      "kind" to "lifecycle",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "phase" to event.phase.name.lowercase(),
      "status" to event.status?.name?.lowercase(),
      "errorCode" to event.errorCode,
      "errorMessage" to event.errorMessage,
    )
    is OpenCrayAssistantEvent -> mapOf(
      "kind" to "assistant",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "responseFormat" to event.responseFormat,
      "isFinal" to event.isFinal,
      "text" to event.text,
    )
    is OpenCrayProgressEvent -> mapOf(
      "kind" to "progress",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "text" to event.text,
      "stage" to event.stage,
    )
    is OpenCrayApprovalEvent -> mapOf(
      "kind" to if (event.phase == OpenCrayApprovalPhase.REQUIRED) "approval_wait" else "approval_result",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.toolName,
      "text" to event.text,
      "stage" to event.phase.name.lowercase(),
      "status" to event.phase.name.lowercase(),
      "isHighRisk" to event.isHighRisk,
    )
    is OpenCrayToolCallEvent -> mapOf(
      "kind" to "tool_call",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.call.toolName,
      "toolReason" to event.call.reason,
      "argumentsJson" to event.call.arguments.toString(),
    )
    is OpenCrayToolResultEvent -> mapOf(
      "kind" to "tool_result",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.call.toolName,
      "toolStatus" to event.result.status.name.lowercase(),
      "errorCode" to event.result.errorCode,
      "errorMessage" to event.result.errorMessage,
      "contentPreview" to event.result.content.take(MAX_RUNTIME_EVENT_PREVIEW_CHARS),
      "resultMetadata" to toolResultMetadataSnapshot(event.result.metadata),
    )
    is OpenCrayMemoryRetrievalEvent -> buildMap<String, Any?> {
      put("kind", "memory_retrieval")
      put("runId", event.runId)
      put("taskId", event.taskId)
      put("turn", event.turn)
      put("emittedAtEpochMs", event.emittedAtEpochMs)
      put("toolName", event.toolName)
      put("operation", event.operation)
      event.query?.let { query -> put("query", query) }
      if (event.queryTerms.isNotEmpty()) {
        put("queryTerms", event.queryTerms)
      }
      event.resultCount?.let { resultCount -> put("resultCount", resultCount) }
      event.corpusFileCount?.let { corpusFileCount -> put("corpusFileCount", corpusFileCount) }
      if (event.recordIds.isNotEmpty()) {
        put("recordIds", event.recordIds)
      }
      if (event.paths.isNotEmpty()) {
        put("paths", event.paths)
      }
      if (event.lineRanges.isNotEmpty()) {
        put("lineRanges", event.lineRanges)
      }
      event.path?.let { path -> put("path", path) }
      event.fromLine?.let { fromLine -> put("fromLine", fromLine) }
      event.returnedLineCount?.let { returnedLineCount -> put("returnedLineCount", returnedLineCount) }
      event.totalLineCount?.let { totalLineCount -> put("totalLineCount", totalLineCount) }
    }
    is OpenCrayMemoryWriteEvent -> mapOf(
      "kind" to "memory_write",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "writtenRecordIds" to event.writtenRecordIds,
      "writtenKinds" to event.writtenKinds,
      "resolvedRecordIds" to event.resolvedRecordIds,
      "reaffirmedRecordIds" to event.reaffirmedRecordIds,
      "expiredRecordIds" to event.expiredRecordIds,
    )
    is OpenCrayCancellationEvent -> mapOf(
      "kind" to "cancelled",
      "runId" to event.runId,
      "taskId" to event.taskId,
      "turn" to event.turn,
      "emittedAtEpochMs" to event.emittedAtEpochMs,
      "toolName" to event.toolName,
      "text" to event.text,
      "stage" to event.outcome,
      "status" to event.outcome,
    )
  }

  private fun isApprovalRequiredResult(result: ExecutionResult): Boolean =
    result.status == ExecutionStatus.DENIED && isApprovalRequiredError(result.errorCode)

  private fun isApprovalRequiredError(errorCode: String?): Boolean =
    errorCode == ERROR_APPROVAL_REQUIRED || errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED

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
    val visibleText = when (message.role) {
      ChatTranscriptRole.ASSISTANT -> sanitizePotentialInternalAgentText(
        text = resolvedText,
        fallback = strings.agentInternalPayloadHidden,
      )

      else -> resolvedText
    }
    return chatMessageSnapshotMap(
      messageId = message.messageId,
      kind = kind,
      text = visibleText,
    )
  }

  private fun chatMessageSnapshotMap(
    messageId: String,
    kind: String,
    text: String,
    meta: String = "",
    isEphemeral: Boolean = false,
  ): Map<String, Any?> = mapOf(
    "messageId" to messageId,
    "kind" to kind,
    "text" to text,
    "meta" to meta,
    "isEphemeral" to isEphemeral,
  )

  private fun finalTextFor(result: ExecutionResult): String {
    val rawText = when (result.status) {
      ExecutionStatus.SUCCESS -> result.stdout.ifBlank { strings.agentEmptyAnswer }
      ExecutionStatus.CANCELLED -> strings.agentCancelled
      ExecutionStatus.DENIED -> result.errorMessage ?: strings.agentFailed(
        result.errorCode ?: result.status.name,
      )
      ExecutionStatus.FAILED -> if (result.errorCode == AppAgentSessionTaskRuntimeFactory.ERROR_CODE_MISSING_LLM_CONFIG) {
        strings.agentMissingLlm
      } else {
        strings.agentFailed(result.errorMessage ?: result.errorCode ?: result.status.name)
      }

      else -> strings.agentFailed(result.errorMessage ?: result.errorCode ?: result.status.name)
    }
    return sanitizePotentialInternalAgentText(
      text = rawText,
      fallback = when (result.status) {
        ExecutionStatus.SUCCESS -> strings.agentInternalPayloadHidden
        ExecutionStatus.DENIED -> approvalFallbackBody(
          isHighRisk = result.errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED,
        )
        else -> strings.agentInternalPayloadHidden
      },
    )
  }

  private fun sanitizeApprovalBody(body: String?, isHighRisk: Boolean): String {
    val fallback = approvalFallbackBody(isHighRisk = isHighRisk)
    val resolved = body?.takeIf(String::isNotBlank) ?: return fallback
    return sanitizePotentialInternalAgentText(
      text = resolved,
      fallback = fallback,
    )
  }

  private fun approvalFallbackBody(isHighRisk: Boolean): String = if (isHighRisk) {
    strings.chatHighRiskApprovalRequiredBody
  } else {
    strings.chatSummaryApprovalRequired
  }

  private fun sanitizeDrawerPreviewText(text: String): String {
    val restoredFallback = restoreKnownPreviewFallback(text.trim())
    if (restoredFallback != null) {
      return restoredFallback
    }
    return sanitizePotentialInternalAgentText(
      text = text,
      fallback = strings.agentInternalPayloadHidden,
    )
  }

  private fun restoreKnownPreviewFallback(text: String): String? {
    val knownFallbacks = listOf(
      strings.agentInternalPayloadHidden,
      strings.chatSummaryApprovalRequired,
      strings.chatHighRiskApprovalRequiredBody,
    )
    return knownFallbacks.firstOrNull { fallback ->
      text == fallback.take(DRAWER_PREVIEW_MAX_CHARS).trimEnd()
    }
  }

  private fun pendingApprovalSnapshot(
    runId: String,
    taskId: String,
    pendingMessageId: String?,
    isHighRisk: Boolean,
    metadata: Map<String, String>,
    errorBody: String,
    toolReason: String?,
  ): PendingApprovalSnapshot {
    val toolName = approvalToolName(metadata)
    val requestSummary = approvalRequestSummary(metadata)
    val primaryDetail = approvalPrimaryDetailValue(metadata)
    val pathDetails = approvalPathDetailLines(metadata)
    val workingDirectory = approvalWorkingDirectoryValue(metadata)
    val reason = approvalReasonValue(toolReason)
    return PendingApprovalSnapshot(
      runId = runId,
      taskId = taskId,
      pendingMessageId = pendingMessageId,
      toolName = toolName,
      requestSummary = requestSummary,
      primaryDetail = primaryDetail,
      pathDetails = pathDetails,
      workingDirectory = workingDirectory,
      reason = reason,
      message = errorBody,
      isHighRisk = isHighRisk,
      title = if (isHighRisk) {
        strings.chatHighRiskApprovalRequiredTitle
      } else {
        strings.chatApprovalRequiredTitle
      },
      body = composeApprovalBody(
        body = errorBody,
        toolReason = toolReason,
        metadata = metadata,
      ),
    )
  }

  private fun approvalToolName(metadata: Map<String, String>): String? =
    metadata["normalizedToolName"]
      ?.takeIf(String::isNotBlank)
      ?: metadata["canonicalToolName"]
        ?.takeIf(String::isNotBlank)
      ?: metadata["toolName"]
        ?.takeIf(String::isNotBlank)

  private fun toolReasonFromEvent(event: OpenCrayAgentRunEvent): String? = when (event) {
    is OpenCrayToolCallEvent -> event.call.reason
    else -> null
  }

  private fun composeApprovalBody(
    body: String,
    toolReason: String?,
    metadata: Map<String, String>,
  ): String {
    val details = mutableListOf<String>()
    approvalPrimaryDetailLine(metadata)?.let(details::add)
    approvalPathDetailLines(metadata).forEach(details::add)
    approvalWorkingDirectoryLine(metadata)?.let(details::add)
    approvalReasonLine(toolReason)?.let(details::add)
    if (details.isEmpty()) {
      return body
    }
    return buildString {
      details.forEach { line -> appendLine(line) }
      appendLine()
      append(body)
    }.trim()
  }

  private fun approvalRequiredRuntimeEvent(
    approval: PendingApprovalSnapshot,
    emittedAtEpochMs: Long,
  ): OpenCrayApprovalEvent = OpenCrayApprovalEvent(
    runId = approval.runId,
    taskId = approval.taskId,
    phase = OpenCrayApprovalPhase.REQUIRED,
    toolName = approval.toolName,
    text = approvalTimelineText(approval),
    isHighRisk = approval.isHighRisk,
    emittedAtEpochMs = emittedAtEpochMs,
  )

  private fun approvalResultRuntimeEvent(
    approval: PendingApprovalSnapshot,
    phase: OpenCrayApprovalPhase,
    emittedAtEpochMs: Long,
  ): OpenCrayApprovalEvent = OpenCrayApprovalEvent(
    runId = approval.runId,
    taskId = approval.taskId,
    phase = phase,
    toolName = approval.toolName,
    text = when (phase) {
      OpenCrayApprovalPhase.REQUIRED -> approvalTimelineText(approval)
      OpenCrayApprovalPhase.APPROVED -> strings.chatApprovalApproved
      OpenCrayApprovalPhase.REJECTED -> strings.chatApprovalRejected
    },
    isHighRisk = approval.isHighRisk,
    emittedAtEpochMs = emittedAtEpochMs,
  )

  private fun cancellationRuntimeEvent(
    run: AgentRunSnapshot,
    approval: PendingApprovalSnapshot?,
    emittedAtEpochMs: Long,
  ): OpenCrayCancellationEvent = OpenCrayCancellationEvent(
    runId = run.runId,
    taskId = run.taskId,
    toolName = approval?.toolName,
    outcome = "user_cancelled",
    text = cancellationTimelineText(toolName = approval?.toolName),
    emittedAtEpochMs = emittedAtEpochMs,
  )

  private fun approvalTimelineText(approval: PendingApprovalSnapshot): String {
    val title = approval.title.trim()
    val body = approval.body.trim()
    return when {
      title.isEmpty() -> body
      body.isEmpty() -> title
      else -> "$title\n\n$body"
    }
  }

  private fun cancellationTimelineText(toolName: String?): String {
    val resolvedToolName = toolName?.trim()?.takeIf { value -> value.isNotBlank() }
    return if (isChineseHostLocale()) {
      if (resolvedToolName == null) {
        "本次运行已取消，等待你的下一步指示。"
      } else {
        "已取消待审批的 $resolvedToolName 请求，等待你的下一步指示。"
      }
    } else if (resolvedToolName == null) {
      "Run cancelled. The agent is waiting for your next instruction."
    } else {
      "Cancelled the pending $resolvedToolName request. The agent is waiting for your next instruction."
    }
  }

  private fun approvalRequestSummary(metadata: Map<String, String>): String? =
    metadata["targetSummary"]?.trim()?.takeIf(String::isNotBlank)
      ?: approvalPrimaryDetailValue(metadata)

  private fun approvalPrimaryDetailValue(metadata: Map<String, String>): String? {
    metadata["scriptPath"]?.takeIf(String::isNotBlank)?.let { scriptPath ->
      return scriptPath
    }
    shellCommandSummary(metadata)?.let { command ->
      return command
    }
    metadata["query"]?.takeIf(String::isNotBlank)?.let { query ->
      return query
    }
    metadata["requestedUrl"]?.takeIf(String::isNotBlank)?.let { url ->
      return url
    }
    metadata["finalUrl"]?.takeIf(String::isNotBlank)?.let { url ->
      return url
    }
    metadata["processId"]?.takeIf(String::isNotBlank)?.let { processId ->
      if (metadata["targetKind"] == "process") {
        return processId
      }
    }
    metadata["targetSummary"]?.takeIf(String::isNotBlank)?.let { summary ->
      val primaryTargetPath = metadata["primaryTargetPath"]?.trim().orEmpty()
      val secondaryTargetPath = metadata["secondaryTargetPath"]?.trim().orEmpty()
      val duplicateSummaries = buildSet {
        if (primaryTargetPath.isNotEmpty()) {
          add(primaryTargetPath)
        }
        if (secondaryTargetPath.isNotEmpty()) {
          add(secondaryTargetPath)
        }
        if (primaryTargetPath.isNotEmpty() && secondaryTargetPath.isNotEmpty()) {
          add("$primaryTargetPath -> $secondaryTargetPath")
        }
      }
      if (summary !in duplicateSummaries) {
        return summary
      }
    }
    return null
  }

  private fun approvalPrimaryDetailLine(metadata: Map<String, String>): String? =
    when {
      metadata["scriptPath"]?.isNotBlank() == true ->
        approvalPrimaryDetailValue(metadata)?.let { detail ->
          "${approvalLabel("script")}: $detail"
        }
      shellCommandSummary(metadata) != null ->
        approvalPrimaryDetailValue(metadata)?.let { detail ->
          "${approvalLabel("command")}: $detail"
        }
      metadata["query"]?.isNotBlank() == true ->
        approvalPrimaryDetailValue(metadata)?.let { detail ->
          "${approvalLabel("query")}: $detail"
        }
      metadata["requestedUrl"]?.isNotBlank() == true || metadata["finalUrl"]?.isNotBlank() == true ->
        approvalPrimaryDetailValue(metadata)?.let { detail ->
          "${approvalLabel("url")}: $detail"
        }
      metadata["processId"]?.isNotBlank() == true && metadata["targetKind"] == "process" ->
        approvalPrimaryDetailValue(metadata)?.let { detail ->
          "${approvalLabel("process")}: $detail"
        }
      else ->
        approvalPrimaryDetailValue(metadata)?.let { detail ->
          "${approvalLabel("request")}: $detail"
        }
    }
  private fun approvalPathDetailLines(metadata: Map<String, String>): List<String> {
    val sourcePath = metadata["sourcePath"]?.trim().orEmpty()
    val destinationPath = metadata["destinationPath"]?.trim().orEmpty()
    if (sourcePath.isNotEmpty() || destinationPath.isNotEmpty()) {
      return buildList {
        if (sourcePath.isNotEmpty()) {
          add("${approvalLabel("from")}: $sourcePath")
        }
        if (destinationPath.isNotEmpty()) {
          add("${approvalLabel("to")}: $destinationPath")
        }
      }
    }
    val primaryTargetPath = metadata["primaryTargetPath"]?.trim().orEmpty()
    val secondaryTargetPath = metadata["secondaryTargetPath"]?.trim().orEmpty()
    val scriptPath = metadata["scriptPath"]?.trim().orEmpty()
    val workingDirectory = metadata["workingDirectory"]?.trim().orEmpty()
    return buildList {
      if (
        primaryTargetPath.isNotEmpty() &&
        primaryTargetPath != scriptPath &&
        primaryTargetPath != workingDirectory
      ) {
        add("${approvalLabel("target")}: $primaryTargetPath")
      }
      if (secondaryTargetPath.isNotEmpty()) {
        add("${approvalLabel("to")}: $secondaryTargetPath")
      }
    }
  }

  private fun approvalWorkingDirectoryValue(metadata: Map<String, String>): String? =
    metadata["workingDirectory"]?.trim()?.takeIf(String::isNotBlank)

  private fun approvalWorkingDirectoryLine(metadata: Map<String, String>): String? {
    val workingDirectory = approvalWorkingDirectoryValue(metadata).orEmpty()
    if (workingDirectory.isEmpty()) {
      return null
    }
    return "${approvalLabel("working_directory")}: $workingDirectory"
  }

  private fun approvalReasonValue(toolReason: String?): String? =
    sanitizePotentialInternalAgentText(
      text = toolReason?.trim().orEmpty(),
      fallback = "",
    ).trim().takeIf(String::isNotBlank)

  private fun approvalReasonLine(toolReason: String?): String? {
    val reason = approvalReasonValue(toolReason) ?: return null
    return "${approvalLabel("reason")}: $reason"
  }

  private fun shellCommandSummary(metadata: Map<String, String>): String? {
    metadata["shellCommand"]?.takeIf(String::isNotBlank)?.let { return it }
    val command = metadata["command"]?.trim().orEmpty()
    if (command.isEmpty()) {
      return null
    }
    val args = metadata["args"]
      ?.split('\u0000')
      ?.map(String::trim)
      ?.filter(String::isNotEmpty)
      .orEmpty()
    return buildString {
      append(command)
      if (args.isNotEmpty()) {
        append(' ')
        append(args.joinToString(separator = " "))
      }
    }.trim()
  }

  private fun approvalLabel(kind: String): String {
    val isChinese = strings.localeTag.startsWith("zh", ignoreCase = true)
    return when (kind) {
      "command" -> if (isChinese) "命令" else "Command"
      "script" -> if (isChinese) "脚本" else "Script"
      "query" -> if (isChinese) "查询" else "Query"
      "url" -> if (isChinese) "地址" else "URL"
      "process" -> if (isChinese) "进程" else "Process"
      "request" -> if (isChinese) "操作" else "Request"
      "from" -> if (isChinese) "来源" else "From"
      "to" -> if (isChinese) "目标" else "To"
      "target" -> if (isChinese) "目标" else "Target"
      "working_directory" -> if (isChinese) "工作目录" else "Working directory"
      "reason" -> if (isChinese) "理由" else "Agent reason"
      else -> if (isChinese) "详情" else "Details"
    }
  }

  private fun sanitizePotentialInternalAgentText(text: String, fallback: String): String {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return text
    return if (looksLikeInternalToolPayload(trimmed)) fallback else text
  }

  private fun toolResultMetadataSnapshot(metadata: Map<String, String>): Map<String, String> {
    val allowedKeys = setOf(
      "path",
      "filePath",
      "sourcePath",
      "destinationPath",
      "pattern",
      "glob",
      "entryCount",
      "matchCount",
      "byteCount",
      "totalLineCount",
      "offset",
      "limit",
      "returnedLineCount",
      "truncated",
      "replacementCount",
      "editCount",
      "todoCount",
      "mutated",
      "workingDirectory",
      "processId",
      "shellCommand",
      "scriptPath",
      "checkpointEntryCount",
    )
    return buildMap {
      allowedKeys.forEach { key ->
        metadata[key]
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?.let { value -> put(key, value) }
      }
    }
  }

  private fun looksLikeInternalToolPayload(text: String): Boolean {
    val jsonCandidate = extractEmbeddedJsonObject(text) ?: return false
    val normalized = jsonCandidate.lowercase()
    val explicitToolAction =
      "\"type\"" in normalized &&
        ("\"tool_call\"" in normalized || "\"tool\"" in normalized)
    val toolArgumentShape = "\"tool_name\"" in normalized && "\"arguments\"" in normalized
    return explicitToolAction || toolArgumentShape
  }

  private fun extractEmbeddedJsonObject(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      return trimmed
    }
    var depth = 0
    var startIndex = -1
    var inString = false
    var escaped = false
    for ((index, character) in raw.withIndex()) {
      when {
        inString && escaped -> escaped = false
        inString && character == '\\' -> escaped = true
        character == '"' -> inString = !inString
        !inString && character == '{' -> {
          if (depth == 0) {
            startIndex = index
          }
          depth += 1
        }

        !inString && character == '}' -> {
          depth -= 1
          if (depth == 0 && startIndex >= 0) {
            return raw.substring(startIndex, index + 1)
          }
        }
      }
    }
    return null
  }

  internal fun currentMcpExposureReport() =
    synchronized(lock) { mcpSettingsFacade.currentExposureReport() }

  internal fun currentEnabledSkillRoots() =
    synchronized(lock) { skillsFacade.enabledSkillRoots() }

  private fun currentChatModeLabelLocked(): String =
    chatModeLabelFor(safetySettingsFacade.load().automationMode)

  private fun chatModeLabelFor(mode: SafetyAutomationMode): String = when (mode) {
    SafetyAutomationMode.SAFE -> strings.chatModeSafeLabel
    SafetyAutomationMode.AUTO -> strings.chatModeLabel
    SafetyAutomationMode.DEV -> strings.chatModeDeveloperLabel
  }

  private fun safetyMetadataForTask(
    snapshot: SafetySettingsSnapshot,
  ): Map<String, String> = buildMap {
    val approvedReadRoots = approvedReadRootsProvider()
    put(SafetySettingsMetadataKeys.CHAT_MODE, snapshot.automationMode.chatMetadataLabel)
    put(
      SafetySettingsMetadataKeys.EXECUTION_MODE,
      snapshot.automationMode.executionMode.name,
    )
    put(
      SafetySettingsMetadataKeys.FILE_CHANGES_POLICY_ID,
      snapshot.fileChangesPolicy.wireValue,
    )
    put(
      SafetySettingsMetadataKeys.FILE_DELETES_POLICY_ID,
      snapshot.fileDeletesPolicy.wireValue,
    )
    put(
      SafetySettingsMetadataKeys.SHELL_COMMANDS_POLICY_ID,
      snapshot.shellCommandsPolicy.wireValue,
    )
    put(
      SafetySettingsMetadataKeys.EXTERNAL_ACCESS_MODE_ID,
      snapshot.externalAccessMode.wireValue,
    )
    put(
      SafetySettingsMetadataKeys.WORKSPACE_ACCESS_PROFILE_ID,
      snapshot.workspaceAccessProfile.wireValue,
    )
    put(
      SafetySettingsMetadataKeys.READ_ONLY_OUTSIDE_WORKSPACE,
      snapshot.readOnlyOutsideWorkspace.toString(),
    )
    put(
      SafetySettingsMetadataKeys.APPROVED_READ_ROOTS,
      approvedReadRoots.summary,
    )
  }

  private fun refreshLocalizedResourcesLocked() {
    val baseContext = appContext ?: return
    val localizedContext = OpenCrayLocaleManager.wrap(baseContext)
    settingsFacade = LocalSettingsFacade.fromContext(localizedContext)
    llmConfigFacade = LocalLlmConfigFacade.fromContext(localizedContext)
    personalizationFacade = LocalPersonalizationFacade.createForTest(
      context = localizedContext,
      store = PersonalizationLocalStore.fromContext(baseContext),
      soulProfileStore = workspaceSoulProfileStore,
      workspaceRootProvider = { AppAgentWorkspace.ensureRootForContext(baseContext) },
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
    safetySettingsFacade = LocalSafetySettingsFacade.fromContext(baseContext)
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

  private fun NetworkSearchConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
    "localeTag" to localeTag,
    "title" to title,
    "subtitle" to subtitle,
    "slots" to slots.map { slot -> slot.toMap() },
  )

  private fun NetworkSearchSlotSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "providerId" to providerId,
    "label" to label,
    "apiKey" to apiKey,
    "enabled" to enabled,
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
    "localeTag" to localeTag,
    "enabled" to enabled,
    "providerId" to providerId,
    "selectedProviderOptionId" to selectedProviderOptionId,
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
    "providerId" to providerId,
    "title" to title,
    "subtitle" to subtitle,
    "defaultBaseUrl" to defaultBaseUrl,
    "defaultModel" to defaultModel,
    "protocol" to protocol,
    "apiKey" to apiKey,
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

  private fun SafetySettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
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
    "locations" to locations.map { location -> location.toMap() },
    "workspaceAccessProfileId" to workspaceAccessProfile.wireValue,
    "readOnlyOutsideWorkspace" to readOnlyOutsideWorkspace,
  )

  private fun SafetySettingsLocationSnapshot.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "enabled" to enabled,
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
    emitSnapshotLazy(chatListeners, ::loadChatSnapshot)
  }

  private fun emitChatRuntimeSnapshot() {
    emitSnapshotLazy(chatRuntimeListeners, ::loadChatRuntimeSnapshot)
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

  private fun emitSnapshotLazy(
    listeners: LinkedHashSet<(Map<String, Any?>) -> Unit>,
    payloadProvider: () -> Map<String, Any?>,
  ) {
    val currentListeners = synchronized(lock) { listeners.toList() }
    if (currentListeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      val payload = payloadProvider()
      currentListeners.forEach { listener -> listener(payload) }
    }
  }

  companion object {
    private const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    private const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
    private const val DEFAULT_RUN_WAIT_TIMEOUT_MS: Long = 15_000L
    private const val RUN_LOOKUP_POLL_INTERVAL_MS: Long = 50L
    private const val MAX_RUNTIME_EVENT_HISTORY: Int = 24
    private const val MAX_RUNTIME_EVENT_PREVIEW_CHARS: Int = 240
    private const val DRAWER_PREVIEW_MAX_CHARS: Int = 52
    private val replayJson: Json = Json { ignoreUnknownKeys = true }
    private val MEMORY_SELECTED_TRACE_REGEX: Regex = Regex("""^(.+?)@(\d+)(?:\[(.*)])?$""")
    private val BOOTSTRAP_FILE_TRACE_REGEX: Regex =
      Regex("""^(.+?)@(.+)\[(\d+)\|(\d+)\|(true|false)]$""")
    private val VISIBLE_SKILL_TRACE_REGEX: Regex =
      Regex("""^([a-z0-9-]+)@(.+)\[([^\]|]+)\|(true|false)\|([^\]|]+)]$""")
    private val RESERVED_SOUL_PROFILE_KEYS: Set<String> = setOf(
      "preset",
      "custom_guidance",
    )
    private val SUPPORTED_SOUL_PREFERENCE_KEYS: Set<String> = setOf(
      MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
      MemoryPreferenceKeys.AGENT_STYLE_PROFILE,
      MemoryPreferenceKeys.AGENT_VERBOSITY,
      MemoryPreferenceKeys.USER_PREFERRED_NAME,
      MemoryPreferenceKeys.USER_ADDRESS_STYLE,
    )
    private const val DEBUG_MEMORY_KIND_USER_PREFERENCE: String = "user_preference"
    private const val DEBUG_MEMORY_STATUS_ACTIVE: String = "active"
    private const val DEBUG_MEMORY_SCOPE_USER: String = "user"
    private const val DEBUG_MEMORY_SCOPE_WORKSPACE: String = "workspace"
    private const val DEBUG_MEMORY_SCOPE_SESSION: String = "session"
    private const val MAX_DEBUG_LINKS_PER_RECORD: Int = 8
    private const val SOUL_FIELD_PRESET_NAME: String = "presetName"
    private const val SOUL_FIELD_DISPLAY_NAME: String = "displayName"
    private const val SOUL_FIELD_VOICE: String = "voice"
    private const val SOUL_FIELD_PREFERRED_NAMING: String = "preferredNaming"
    private const val SOUL_FIELD_PREFERRED_ADDRESS_STYLE: String = "preferredAddressStyle"
    private const val SOUL_FIELD_INTIMACY_PERMISSION_BAND: String = "intimacyPermissionBand"
    private const val SOUL_FIELD_PLAYFULNESS_PERMISSION_BAND: String = "playfulnessPermissionBand"
    private const val SOUL_FIELD_HIGH_INTIMACY_BEHAVIOR_ALLOWED: String = "highIntimacyBehaviorAllowed"
    private const val SOUL_FIELD_PLAYFUL_AFFECTION_ALLOWED: String = "playfulAffectionAllowed"
    private const val SOUL_FIELD_CUSTOM_GUIDANCE: String = "customGuidance"
    private const val SOUL_FIELD_TONE: String = "tone"
    private const val SOUL_FIELD_VERBOSITY: String = "verbosity"
    private const val SOUL_FIELD_USER_RELATIONSHIP_STYLE: String = "userRelationshipStyle"
    private const val SOUL_FIELD_RISK_TOLERANCE: String = "riskTolerance"
    private const val SOUL_FIELD_TOOL_USE_BIAS: String = "toolUseBias"
    private const val SOUL_FIELD_ESCALATION_RULES: String = "escalationRules"
    private const val SOUL_FIELD_FORBIDDEN_BEHAVIORS: String = "forbiddenBehaviors"
    private const val SOUL_FIELD_COLLABORATION_PREFERENCES: String = "collaborationPreferences"
    private val SOUL_FIELD_ORDER: List<String> = listOf(
      SOUL_FIELD_PRESET_NAME,
      SOUL_FIELD_DISPLAY_NAME,
      SOUL_FIELD_VOICE,
      SOUL_FIELD_PREFERRED_NAMING,
      SOUL_FIELD_PREFERRED_ADDRESS_STYLE,
      SOUL_FIELD_INTIMACY_PERMISSION_BAND,
      SOUL_FIELD_PLAYFULNESS_PERMISSION_BAND,
      SOUL_FIELD_HIGH_INTIMACY_BEHAVIOR_ALLOWED,
      SOUL_FIELD_PLAYFUL_AFFECTION_ALLOWED,
      SOUL_FIELD_CUSTOM_GUIDANCE,
      SOUL_FIELD_TONE,
      SOUL_FIELD_VERBOSITY,
      SOUL_FIELD_USER_RELATIONSHIP_STYLE,
      SOUL_FIELD_RISK_TOLERANCE,
      SOUL_FIELD_TOOL_USE_BIAS,
      SOUL_FIELD_ESCALATION_RULES,
      SOUL_FIELD_FORBIDDEN_BEHAVIORS,
      SOUL_FIELD_COLLABORATION_PREFERENCES,
    )

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
      networkSearchConfigFacade: NetworkSearchConfigFacade = EmptyNetworkSearchConfigFacade,
      llmConfigFacade: LlmConfigFacade = EmptyLlmConfigFacade,
      personalizationFacade: PersonalizationFacade = EmptyPersonalizationFacade,
      personalizationLocalStore: PersonalizationLocalStore? = null,
      workspaceSoulProfileStore: WorkspaceSoulProfileStore = WorkspaceSoulProfileStore(),
      mcpSettingsFacade: McpSettingsFacade = EmptyMcpSettingsFacade,
      safetySettingsFacade: SafetySettingsFacade = EmptySafetySettingsFacade,
      skillsFacade: SkillsFacade = EmptySkillsFacade,
      workspaceRootProvider: (() -> Path)? = null,
      approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot = {
        workspaceRootProvider?.invoke()?.let { workspaceRoot ->
          val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
          ApprovedReadRootsSnapshot(
            roots = setOf(normalizedWorkspaceRoot),
            summary = "workspace=${normalizedWorkspaceRoot.toString().replace('\\', '/')}",
          )
        } ?: ApprovedReadRootsSnapshot(
          roots = emptySet(),
          summary = "workspace=unavailable",
        )
      },
      workspaceSnapshotProvider: () -> Map<String, Any?> = {
        WorkspaceTreeSnapshot(
          rootName = AppAgentWorkspace.DIRECTORY_NAME,
          rootPath = AppAgentWorkspace.DIRECTORY_NAME,
          availableBytes = 0L,
          directoryCount = 0,
          fileCount = 0,
          entryCount = 0,
          isTruncated = false,
          children = emptyList(),
        ).toMap()
      },
      sessionRuntimeManager: AgentSessionRuntimeManager,
      transcriptMessagesProvider: (String) -> List<RuntimeConversationMessage> = { emptyList() },
      approvalRegistry: AgentTaskApprovalRegistry = AgentTaskApprovalRegistry(),
      memoryIngestionCoordinator: ChatMemoryIngestionCoordinator? = null,
      approvalReplayRecorder: (String, String, String, String?, Boolean) -> Unit = { _, _, _, _, _ -> },
      approvalApprovedReplayRecorder: (String, String, String, String?, Boolean) -> Unit = { _, _, _, _, _ -> },
      runCancellationReplayRecorder: (String, String, String, String?) -> Unit = { _, _, _, _ -> },
      terminalReplayRepairer: (String, List<AgentRunSnapshot>) -> Unit = { _, _ -> },
      strings: HostRuntimeStrings = HostRuntimeStrings(
        localeTag = "en",
        shellHostLabel = "HOST CONNECTED",
        shellHostSummary = "Android host bridge is attached to the live app runtime.",
        chatScreenTitle = "Chat",
        chatModeLabel = "AUTO",
        chatModeSafeLabel = "SAFE",
        chatModeDeveloperLabel = "DEV",
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
      networkSearchConfigFacade = networkSearchConfigFacade,
      llmConfigFacade = llmConfigFacade,
      personalizationFacade = personalizationFacade,
      personalizationLocalStore = personalizationLocalStore,
      workspaceSoulProfileStore = workspaceSoulProfileStore,
      mcpSettingsFacade = mcpSettingsFacade,
      safetySettingsFacade = safetySettingsFacade,
      skillsFacade = skillsFacade,
      workspaceRootProvider = workspaceRootProvider,
      approvedReadRootsProvider = approvedReadRootsProvider,
      workspaceSnapshotProvider = workspaceSnapshotProvider,
      sessionRuntimeManager = sessionRuntimeManager,
      transcriptMessagesProvider = transcriptMessagesProvider,
      approvalRegistry = approvalRegistry,
      memoryIngestionCoordinator = memoryIngestionCoordinator,
      approvalReplayRecorder = approvalReplayRecorder,
      approvalApprovedReplayRecorder = approvalApprovedReplayRecorder,
      runCancellationReplayRecorder = runCancellationReplayRecorder,
      terminalReplayRepairer = terminalReplayRepairer,
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
      val webSearchSettingsStore = WebSearchSettingsStore.fromContext(appContext)
      val providerUserAgent = OpenCrayUserAgent.fromContext(appContext)
      val chatExecutor: ExecutorService = Executors.newSingleThreadExecutor()
      val chatContextFactory = ChatRuntimeSessionContextFactory(chatSessionStore)
      val approvalRegistry = AgentTaskApprovalRegistry()
      val workspaceRootProvider = { AppAgentWorkspace.ensureRootForContext(appContext) }
      val workspaceRootsProvider = { setOf(workspaceRootProvider()) }
      val soulProfileStore = WorkspaceSoulProfileStore()
      val safetySettingsFacade = LocalSafetySettingsFacade.fromContext(appContext)
      val approvedReadRootsProvider = {
        ApprovedReadRootsResolver.resolve(
          context = appContext,
          workspaceRoot = workspaceRootProvider(),
          safetySettings = safetySettingsFacade.load(),
        )
      }
      val workspaceSnapshotProvider = {
        AppAgentWorkspaceSnapshotFactory.createSnapshot(
          workspaceRootProvider(),
        ).toMap()
      }
      val pythonRuntime = P4aPythonRuntime.fromContext(appContext)
      val compactionStoreFactory = FileBackedAgentSessionCompactionStoreFactory.fromContext(appContext)
      val transcriptStoreFactory = FileBackedAgentSessionTranscriptStoreFactory.fromContext(appContext)
      val processRegistryFactory = FileBackedAgentProcessRegistryFactory(
        runtimeRootDirectory = File(
          appContext.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
        controllerFactory = com.opencray.runtime.process.RoutedManagedProcessControllerFactory(
          workspaceRoot = workspaceRootProvider(),
          pythonRuntime = pythonRuntime,
        ),
      )
      val liteLlmProviderClient = OpenAiCompatibleLiteLlmProviderClient(
        userAgent = providerUserAgent,
      )
      val userMemoryIntentInterpreter = LiteLlmUserMemoryIntentInterpreter(
        llmSettingsProvider = { llmSettingsStore.load() },
        providerClient = liteLlmProviderClient,
      )
      val taskCommitmentIntentInterpreter = LiteLlmTaskCommitmentIntentInterpreter(
        llmSettingsProvider = { llmSettingsStore.load() },
        providerClient = liteLlmProviderClient,
      )
      val relationshipEventInterpreter = LiteLlmRelationshipEventInterpreter(
        llmSettingsProvider = { llmSettingsStore.load() },
        providerClient = liteLlmProviderClient,
      )
      val memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = personalizationStore.asMemoryStore(),
        workspaceIdProvider = { AppWorkspaceIdentity.fromRoots(workspaceRootsProvider()) },
        candidateExtractor = MemoryCandidateExtractor(
          userIntentInterpreter = userMemoryIntentInterpreter,
        ),
        taskCommitmentResolver = com.opencray.runtime.memory.TaskCommitmentResolver(
          store = personalizationStore.asMemoryStore(),
          intentInterpreter = taskCommitmentIntentInterpreter,
        ),
        soulPlasticityProvider = {
          when (
            soulProfileStore.loadSoulProfile(workspaceRootProvider())
              ?.extensions
              ?.get(SoulProfileExtensionKeys.PLASTICITY)
              ?.trim()
              ?.lowercase(Locale.US)
          ) {
            "high" -> SoulPlasticity.HIGH
            "medium" -> SoulPlasticity.MEDIUM
            else -> SoulPlasticity.LOW
          }
        },
        relationshipEventInterpreter = relationshipEventInterpreter,
      )
      lateinit var hostRuntime: OpenCrayHostRuntime
      val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
        llmSettingsProvider = { llmSettingsStore.load() },
        safetySettingsProvider = { SafetySettingsStore.fromContext(appContext).load() },
        sessionContextFactory = chatContextFactory,
        soulProfileProvider = { soulProfileStore.loadSoulProfile(workspaceRootProvider()) },
        workspaceRootsProvider = workspaceRootsProvider,
        readRootsProvider = { approvedReadRootsProvider().roots },
        skillsRootsProvider = { hostRuntime.currentEnabledSkillRoots() },
        mcpReportProvider = { hostRuntime.currentMcpExposureReport() },
        memoryRecordsProvider = personalizationStore::listMemoryRecords,
        providerUserAgent = providerUserAgent,
        approvalRegistry = approvalRegistry,
        processRegistryProvider = processRegistryFactory::forChatSession,
        transcriptStoreProvider = transcriptStoreFactory::forChatSession,
        compactionStoreProvider = compactionStoreFactory::forChatSession,
        memoryIngestionCoordinator = memoryIngestionCoordinator,
        pythonRuntimeProvider = { pythonRuntime },
        webSearchProviderFactory = {
          AppConfiguredWebSearchProviderFactory.create(
            slots = webSearchSettingsStore.load(),
            userAgent = providerUserAgent,
          )
        },
        skillPackageManagerProvider = {
          SkillPackageManager(
            managedRoot = AppSkillsStorage.managedSkillsRootForContext(appContext),
            catalogRoot = AppSkillsStorage.catalogSkillsRootForContext(appContext),
            manifestStore = SkillInstallManifestStore.fromFile(
              AppSkillsStorage.manifestFileForContext(appContext),
            ),
          )
        },
      )
      val personalizationFacade = LocalPersonalizationFacade.createForTest(
        context = localizedContext,
        store = personalizationStore,
        soulProfileStore = soulProfileStore,
        workspaceRootProvider = workspaceRootProvider,
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
        networkSearchConfigFacade = LocalNetworkSearchConfigFacade.fromContext(localizedContext),
        llmConfigFacade = LocalLlmConfigFacade.fromContext(localizedContext),
        personalizationFacade = personalizationFacade,
        personalizationLocalStore = personalizationStore,
        workspaceSoulProfileStore = soulProfileStore,
        mcpSettingsFacade = mcpSettingsFacade,
        safetySettingsFacade = safetySettingsFacade,
        skillsFacade = skillsFacade,
        workspaceRootProvider = workspaceRootProvider,
        approvedReadRootsProvider = approvedReadRootsProvider,
        workspaceSnapshotProvider = workspaceSnapshotProvider,
        sessionRuntimeManager = DefaultAgentSessionRuntimeManager(
          agentId = "opencray-flutter-host",
          runtimeFactory = runtimeFactory,
          snapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory.fromContext(appContext),
          runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory.fromContext(appContext),
          executor = chatExecutor,
        ),
        transcriptMessagesProvider = { sessionId ->
          transcriptStoreFactory.forChatSession(sessionId).snapshot()
        },
        approvalRegistry = approvalRegistry,
        memoryIngestionCoordinator = memoryIngestionCoordinator,
        approvalReplayRecorder = runtimeFactory::recordApprovalRejection,
        approvalApprovedReplayRecorder = runtimeFactory::recordApprovalApproved,
        runCancellationReplayRecorder = runtimeFactory::recordRunCancellation,
        terminalReplayRepairer = runtimeFactory::repairTerminalReplayFromRunSnapshots,
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
      chatModeSafeLabel = context.getStringByNameOrFallback(
        resourceName = "chat_mode_safe",
        fallback = "SAFE",
      ),
      chatModeDeveloperLabel = context.getStringByNameOrFallback(
        resourceName = "chat_mode_dev",
        fallback = "DEV",
      ),
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
      chatApprovalApproved = context.getString(R.string.chat_approval_approved),
      chatApprovalRejected = context.getString(R.string.chat_approval_rejected),
    )

    private fun Context.getStringByNameOrFallback(
      resourceName: String,
      fallback: String,
    ): String {
      val resourceId = resources.getIdentifier(resourceName, "string", packageName)
      return if (resourceId != 0) getString(resourceId) else fallback
    }
  }
}

internal data class HostRuntimeStrings(
  val localeTag: String,
  val shellHostLabel: String,
  val shellHostSummary: String,
  val chatScreenTitle: String,
  val chatModeLabel: String,
  val chatModeSafeLabel: String = "SAFE",
  val chatModeDeveloperLabel: String = "DEV",
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
  val agentInternalPayloadHidden: String = "The agent produced an internal tool payload instead of a user-facing reply.",
  val chatSummaryApprovalRequired: String = "Approval required before the agent can continue.",
  val chatApprovalRequiredTitle: String = "Approval required",
  val chatHighRiskApprovalRequiredTitle: String = "High-risk approval required",
  val chatHighRiskApprovalRequiredBody: String = "High-risk approval required. Review this request carefully before approving.",
  val chatApprovalApproveLabel: String = "Approve",
  val chatApprovalRejectLabel: String = "Reject",
  val chatApprovalApproved: String = "Approval granted. The agent is resuming.",
  val chatApprovalRejected: String = "Approval rejected. The requested action was not run.",
)

private data class CompletedTurnForMemoryIngestion(
  val sessionId: String,
  val task: AgentTask,
  val result: ExecutionResult,
  val userInput: String,
  val assistantOutput: String,
  val toolObservations: List<String>,
)

private data class PendingApprovalSnapshot(
  val runId: String,
  val taskId: String,
  val pendingMessageId: String?,
  val toolName: String?,
  val requestSummary: String?,
  val primaryDetail: String?,
  val pathDetails: List<String>,
  val workingDirectory: String?,
  val reason: String?,
  val message: String?,
  val isHighRisk: Boolean,
  val title: String,
  val body: String,
)

private data class ReplayedRuntimeEvent(
  val sourceIndex: Int,
  val event: OpenCrayAgentRunEvent,
)

private data class ProjectedRuntimeChatMessage(
  val anchorMessageId: String?,
  val snapshot: Map<String, Any?>,
)

private data class DebugMemoryMetadata(
  val kind: String,
  val scope: String,
  val status: String,
  val source: String?,
  val sourceSessionId: String?,
  val sourceTaskId: String?,
  val workspaceId: String?,
  val ttlMs: Long?,
  val lastConfirmedAtEpochMs: Long?,
  val resolvedAtEpochMs: Long?,
  val resolutionReason: String?,
  val supersededBy: String?,
  val preferenceKey: String?,
  val preferenceValue: String?,
  val preferenceTemporality: String?,
)

private data class SoulFieldContribution(
  val field: String,
  val value: String,
  val record: MemoryRecord,
  val metadata: DebugMemoryMetadata,
)

private data class ResolvedSoulFieldSource(
  val field: String,
  val value: String,
  val sourceType: String,
  val sourceLabel: String,
  val recordId: String = "",
  val preferenceKey: String = "",
  val sourceScope: String = "",
  val sourceDetail: String = "",
)

private fun OpenCrayAgentRunEvent.withEmittedAtEpochMs(emittedAtEpochMs: Long): OpenCrayAgentRunEvent =
  when (this) {
    is OpenCrayLifecycleEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayAssistantEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayProgressEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayApprovalEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayToolCallEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayToolResultEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayMemoryWriteEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayMemoryRetrievalEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
    is OpenCrayCancellationEvent -> copy(emittedAtEpochMs = emittedAtEpochMs)
  }

private fun Int?.orEmptyString(): String = this?.toString().orEmpty()

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
