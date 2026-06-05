package com.opencray.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.opencray.app.facade.llm.LlmConfigFacade
import com.opencray.app.facade.llm.LocalLlmConfigFacade
import com.opencray.app.facade.media.LocalMediaSpeechSettingsFacade
import com.opencray.app.facade.media.MediaSpeechSettingsFacade
import com.opencray.app.facade.mcp.LocalMcpSettingsFacade
import com.opencray.app.facade.mcp.McpSettingsFacade
import com.opencray.app.facade.notifications.LocalNotificationSettingsFacade
import com.opencray.app.facade.notifications.NotificationSettingsFacade
import com.opencray.app.facade.personalization.LocalPersonalizationFacade
import com.opencray.app.facade.personalization.PersonalizationFacade
import com.opencray.app.facade.safety.LocalSafetySettingsFacade
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.app.facade.search.LocalNetworkSearchConfigFacade
import com.opencray.app.facade.search.NetworkSearchConfigFacade
import com.opencray.app.facade.settings.LocalSettingsFacade
import com.opencray.app.facade.settings.SettingsFacade
import com.opencray.app.facade.settings.SettingsRouteId
import com.opencray.app.facade.skills.LocalSkillsFacade
import com.opencray.app.facade.skills.SkillsFacade
import com.opencray.app.shell.AppShellDestination
import com.opencray.app.shell.AppShellStateStore
import java.nio.file.Path

private const val SERVICE_CHAT_DEBUG_TAG: String = "OpenCrayDiag"
private const val SERVICE_OWNED_GATEWAY_POLL_INTERVAL_MS: Long = 350L

private fun serviceChatDebug(message: String) {
  runCatching { Log.d(SERVICE_CHAT_DEBUG_TAG, message) }
}

internal class OpenCrayRuntimeServiceGatewayBundle(
  val shellGateway: OpenCrayShellGateway,
  val chatRuntimeGateway: OpenCrayRuntimeServiceChatGateway,
  val skillsGateway: OpenCraySkillsGateway,
  val settingsGateway: OpenCraySettingsGateway,
) {
  fun dispatchChatWriteCommand(
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult = chatRuntimeGateway.dispatchChatWriteCommand(command)

  fun dispatchSkillsWriteCommand(
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult = skillsGateway.dispatchSkillsWriteCommand(command)

  fun dispatchSettingsWriteCommand(
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult = settingsGateway.dispatchSettingsWriteCommand(command)

  fun notifyChatSnapshotsChanged() {
    chatRuntimeGateway.notifyChatSnapshotsChanged()
  }

  companion object {
    fun createForRuntimeService(
      appContext: android.content.Context,
      gatewayDependencies: RuntimeServiceGatewayBundleDependencies,
      runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState? = { null },
      runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar? = null,
      runtimeServiceConnectionState: RuntimeServiceConnectionState =
        RuntimeServiceConnectionState.binderConnected(),
    ): OpenCrayRuntimeServiceGatewayBundle {
      val chatSessionStore = ChatSessionLocalStore.fromContext(appContext)
      val chatUnreadMessageState = ChatUnreadMessageState()
      val pendingApprovalState = ChatPendingApprovalState()
      val runtimeEventState = ChatRuntimeEventState()
      val localizedContextProvider = {
        OpenCrayLocaleManager.wrap(appContext)
      }
      val strings = localizedHostRuntimeStrings(localizedContextProvider())
      val projectionChatGateway = projectionOnlyOpenCrayChatRuntimeGateway(
        context = appContext,
        diagnosticsSource = ProjectionOnlyChatRuntimeDiagnosticsSource(
          connectionStateProvider = { runtimeServiceConnectionState },
          runtimeOwnerLifecycleProvider = { gatewayDependencies.runtimeOwnerLifecycle },
          runtimeOwnerWorkSummaryProvider = gatewayDependencies.runtimeHostAccess::activeWorkSummary,
          serviceLifecycleProvider = { gatewayDependencies.serviceLifecycle },
          serviceWorkStateProvider = gatewayDependencies.serviceWorkStateProvider,
          serviceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
        ),
        stringsProvider = {
          projectionOnlyChatStrings(localizedContextProvider())
        },
        sessionUnreadCountProvider = chatUnreadMessageState::countForSession,
      )
      val shellGateway = ServiceOwnedShellGateway(
        stateStore = AppShellStateStore.fromContext(appContext),
        localeTag = strings.localeTag,
        hostLabel = strings.shellHostLabel,
        hostSummary = strings.shellHostSummary,
        runtimeHostAccess = gatewayDependencies.runtimeHostAccess,
        runtimeServiceLifecycle = gatewayDependencies.serviceLifecycle,
        runtimeServiceWorkStateProvider = gatewayDependencies.serviceWorkStateProvider,
        runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
        runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
        runtimeServiceConnectionStateProvider = { runtimeServiceConnectionState },
        mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
      )
      val skillsGateway = ServiceOwnedSkillsGateway(
        skillsFacade = LocalSkillsFacade.fromContext(localizedContextProvider()),
        localeTag = strings.localeTag,
        skillInstalled = strings.skillInstalled,
        skillRemoved = strings.skillRemoved,
        skillsReloaded = strings.skillsReloaded,
        snapshotNotifier = {},
        mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
      )
      var onWarmupStateChanged: (() -> Unit)? = null
      val onDeviceWarmupAccess = SessionAwareOnDeviceLlmWarmupAccess(
        activeSessionIdProvider = {
          chatSessionStore.loadState().activeSession.sessionId
        },
        warmupSpecProvider = gatewayDependencies.onDeviceWarmupPlanner,
        controller = AppOnDeviceLlmWarmupController(
          runtime = LiteRtOnDeviceRuntime.fromContext(appContext),
          onStateChanged = {
            onWarmupStateChanged?.invoke()
          },
        ),
      )
      val chatGateway = ServiceOwnedChatRuntimeGateway(
        readGateway = projectionChatGateway,
        snapshotNotifier = {},
        runtimeHostAccess = gatewayDependencies.runtimeHostAccess,
        onDeviceWarmupAccess = onDeviceWarmupAccess,
        onDevicePreparingPlaceholder = strings.chatMessageOnDevicePreparing,
        chatSessionMutationAccess = ServiceOwnedChatSessionMutationAccess(
          chatSessionStore = chatSessionStore,
          runtimeHostAccess = gatewayDependencies.runtimeHostAccess,
          chatUnreadMessageState = chatUnreadMessageState,
          pendingApprovalState = pendingApprovalState,
          runtimeEventState = runtimeEventState,
          terminalReplayRepairer = gatewayDependencies.runtimeReplayAccess.terminalReplayRepairer,
        ),
        chatRunControlAccess = ServiceOwnedChatRunControlAccess(
          chatSessionStore = ChatSessionLocalStore.fromContext(appContext),
          runtimeHostAccess = gatewayDependencies.runtimeHostAccess,
          pendingApprovalState = pendingApprovalState,
          runtimeEventState = runtimeEventState,
          runCancellationReplayRecorder = gatewayDependencies.runtimeReplayAccess.runCancellationRecorder,
          subAgentReplayRecorder = gatewayDependencies.runtimeReplayAccess.subAgentReplayRecorder,
          isChineseLocale = {
            localizedHostRuntimeStrings(
              OpenCrayLocaleManager.wrap(appContext),
            ).localeTag.trim().lowercase(java.util.Locale.US).startsWith("zh")
          },
        ),
        chatApprovalAccess = ServiceOwnedChatApprovalAccess(
          approveChatApprovalHandler = gatewayDependencies.approvePendingApproval,
          approveChatApprovalForSessionHandler = gatewayDependencies.approvePendingApprovalForSession,
          rejectChatApprovalHandler = gatewayDependencies.rejectPendingApproval,
        ),
        chatSubmissionAccess = ServiceOwnedChatSubmissionAccess(
          chatSessionStore = chatSessionStore,
          runtimeHostAccess = gatewayDependencies.runtimeHostAccess,
          safetySettingsFacade = gatewayDependencies.safetySettingsFacade,
          workspaceRootProvider = gatewayDependencies.workspaceRootProvider,
          approvedReadRootsProvider = gatewayDependencies.approvedReadRootsProvider,
          voiceMetadataAnalyzer = DefaultAppAgentWorkspaceVoiceMetadataAnalyzer,
          agentThinkingText = strings.agentThinking,
        ),
        refreshSandboxSessionInfoHandler = {
          val activeSessionId = chatSessionStore.loadState().activeSession.sessionId
          submitSandboxSessionInfoRefreshTask(
            sessionId = activeSessionId,
            runtimeHostAccess = gatewayDependencies.runtimeHostAccess,
            taskSafetyMetadata = buildTaskSafetyMetadata(
              snapshot = gatewayDependencies.safetySettingsFacade.load(),
              approvedReadRoots = gatewayDependencies.approvedReadRootsProvider(),
            ),
            lifecycleDescriptor = gatewayDependencies.runtimeOwnerLifecycle,
          )
        },
        mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
      )
      onWarmupStateChanged = chatGateway::notifyChatSnapshotsChanged
      onDeviceWarmupAccess.ensureWarmForActiveSession()
      lateinit var settingsGateway: ServiceOwnedSettingsGateway
      val refreshLocalizedGateways = {
        val refreshedStrings = localizedHostRuntimeStrings(
          localizedContextProvider(),
        )
        shellGateway.updateLocalizedResources(
          localeTag = refreshedStrings.localeTag,
          hostLabel = refreshedStrings.shellHostLabel,
          hostSummary = refreshedStrings.shellHostSummary,
        )
        skillsGateway.updateLocalizedResources(
          skillsFacade = LocalSkillsFacade.fromContext(localizedContextProvider()),
          localeTag = refreshedStrings.localeTag,
          skillInstalled = refreshedStrings.skillInstalled,
          skillRemoved = refreshedStrings.skillRemoved,
          skillsReloaded = refreshedStrings.skillsReloaded,
        )
        settingsGateway.updateLocalizedResources(
          localeTag = refreshedStrings.localeTag,
          settingsFacade = LocalSettingsFacade.fromContext(localizedContextProvider()),
          notificationSettingsFacade = LocalNotificationSettingsFacade.fromContext(appContext),
          networkSearchConfigFacade = LocalNetworkSearchConfigFacade.fromContext(
            localizedContextProvider(),
          ),
          mediaSpeechSettingsFacade = LocalMediaSpeechSettingsFacade.fromContext(
            localizedContextProvider(),
          ),
          personalizationFacade = LocalPersonalizationFacade.fromContext(
            localizedContextProvider(),
          ),
          safetySettingsFacade = LocalSafetySettingsFacade.fromContext(appContext),
          llmConfigFacade = LocalLlmConfigFacade.fromContext(localizedContextProvider()),
          mcpSettingsFacade = LocalMcpSettingsFacade.fromContext(localizedContextProvider()),
        )
      }
      settingsGateway = ServiceOwnedSettingsGateway(
        localeTag = strings.localeTag,
        settingsFacade = LocalSettingsFacade.fromContext(localizedContextProvider()),
        notificationSettingsFacade = LocalNotificationSettingsFacade.fromContext(appContext),
        strongBackgroundSettingsAccess = AndroidStrongBackgroundSettingsAccess.fromContext(
          appContext,
        ),
        appLanguageSettingsAccess = FacadeBackedAppLanguageSettingsGatewayAccess.fromContext(
          appContext,
        ),
        sandboxSettingsAccess = RepositoryBackedSandboxSettingsGatewayAccess(
          SandboxSettingsRepository.fromContext(appContext),
        ),
        networkSearchConfigFacade = LocalNetworkSearchConfigFacade.fromContext(
          localizedContextProvider(),
        ),
        mediaSpeechSettingsFacade = LocalMediaSpeechSettingsFacade.fromContext(
          localizedContextProvider(),
        ),
        personalizationFacade = LocalPersonalizationFacade.fromContext(localizedContextProvider()),
        safetySettingsFacade = LocalSafetySettingsFacade.fromContext(appContext),
        llmConfigFacade = LocalLlmConfigFacade.fromContext(localizedContextProvider()),
        mcpSettingsFacade = LocalMcpSettingsFacade.fromContext(localizedContextProvider()),
        shellSnapshotNotifier = shellGateway::emitLocalizedSnapshotChanged,
        chatSnapshotNotifier = chatGateway::emitLocalizedSnapshotChanged,
        onDeviceWarmupAccess = onDeviceWarmupAccess,
        settingsOverviewNotifier = {},
        skillsSnapshotNotifier = skillsGateway::emitLocalizedSnapshotChanged,
        skillsProjectionNotifier = skillsGateway::emitLocalizedSnapshotChanged,
        localizedResourcesRefresh = refreshLocalizedGateways,
        runtimeServiceConnectionStateProvider = { runtimeServiceConnectionState },
        mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
      )
      return OpenCrayRuntimeServiceGatewayBundle(
        shellGateway = shellGateway,
        chatRuntimeGateway = chatGateway,
        skillsGateway = skillsGateway,
        settingsGateway = settingsGateway,
      )
    }
  }
}

internal data class RuntimeServiceGatewayBundleDependencies(
  val runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor,
  val runtimeHostAccess: OpenCrayRuntimeHostAccess,
  val runtimeReplayAccess: OpenCrayRuntimeReplayAccess,
  val serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  val serviceWorkStateProvider: () -> RuntimeServiceWorkState,
  val safetySettingsFacade: SafetySettingsFacade,
  val workspaceRootProvider: () -> Path,
  val approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot,
  val approvePendingApproval: (String) -> Unit,
  val approvePendingApprovalForSession: (String) -> Unit,
  val rejectPendingApproval: (String) -> Unit,
  val onDeviceWarmupPlanner: (String) -> OnDeviceLlmWarmupSpec? = { null },
)

internal fun interface RuntimeServiceGatewayBundleFactory {
  fun create(
    appContext: android.content.Context,
    gatewayDependencies: RuntimeServiceGatewayBundleDependencies,
    runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState,
    runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar,
  ): OpenCrayRuntimeServiceGatewayBundle
}

internal object DefaultRuntimeServiceGatewayBundleFactory : RuntimeServiceGatewayBundleFactory {
  override fun create(
    appContext: android.content.Context,
    gatewayDependencies: RuntimeServiceGatewayBundleDependencies,
    runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState,
    runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar,
  ): OpenCrayRuntimeServiceGatewayBundle = OpenCrayRuntimeServiceGatewayBundle.createForRuntimeService(
    appContext = appContext,
    gatewayDependencies = gatewayDependencies,
    runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
    runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
  )
}

internal class ServiceOwnedShellGateway(
  private val stateStore: AppShellStateStore,
  private var localeTag: String,
  private var hostLabel: String,
  private var hostSummary: String,
  private val runtimeHostAccess: OpenCrayRuntimeHostAccess,
  private val runtimeServiceLifecycle: RuntimeServiceLifecycleDescriptor,
  private val runtimeServiceWorkStateProvider: () -> RuntimeServiceWorkState?,
  private val runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState? = { null },
  private val runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar? = null,
  private val runtimeServiceConnectionStateProvider: () -> RuntimeServiceConnectionState? = { null },
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
  private val hostLifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
) : OpenCrayShellGateway {
  private val lock = Any()
  private val listeners = linkedSetOf<(Map<String, Any?>) -> Unit>()

  init {
    runtimeHostAccess.observe(
      object : AgentSessionRuntimeListener {
        override fun onTaskStarted(
          sessionId: String,
          task: com.opencray.core.contracts.AgentTask,
        ) {
          emitShellSnapshot()
        }

        override fun onTaskFinished(
          sessionId: String,
          task: com.opencray.core.contracts.AgentTask,
          result: com.opencray.core.contracts.ExecutionResult,
        ) {
          emitShellSnapshot()
        }
      },
    )
    runtimeServiceKeepAliveChangeRegistrar?.register {
      emitShellSnapshot()
    }
  }

  override fun loadShellSnapshot(): Map<String, Any?> = buildMap {
    val currentLocaleTag: String
    val currentHostLabel: String
    val currentHostSummary: String
    val destination = stateStore.load()
    synchronized(lock) {
      currentLocaleTag = localeTag
      currentHostLabel = hostLabel
      currentHostSummary = hostSummary
    }
    put("initialTab", destination.selectedTab.routeKey)
    put("settingsSubpage", destination.settingsSubpage.routeKey)
    put("localeTag", currentLocaleTag)
    put("hostLabel", currentHostLabel)
    put("hostSummary", currentHostSummary)
    put("isHostConnected", true)
    putRuntimeServiceDiagnosticsSnapshot(
      localRuntimeServerState = OpenCrayLocalRuntimeServerRegistry.peekState(),
      hostLifecycle = hostLifecycleDescriptor,
      runtimeOwnerLifecycle = runtimeHostAccess.lifecycleDescriptor,
      runtimeOwnerWorkSummary = runtimeHostAccess.activeWorkSummary(),
      runtimeServiceLifecycle = runtimeServiceLifecycle,
      runtimeServiceWorkState = runtimeServiceWorkStateProvider(),
      runtimeServiceKeepAliveState = runtimeServiceKeepAliveStateProvider(),
      runtimeServiceConnectionState = runtimeServiceConnectionStateProvider(),
    )
  }

  override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    synchronized(lock) {
      listeners += listener
    }
    mainThreadPoster.post {
      listener(loadShellSnapshot())
    }
    return {
      synchronized(lock) {
        listeners -= listener
      }
    }
  }

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
    emitShellSnapshot()
  }

  internal fun updateLocalizedResources(
    localeTag: String,
    hostLabel: String,
    hostSummary: String,
  ) {
    synchronized(lock) {
      this.localeTag = localeTag
      this.hostLabel = hostLabel
      this.hostSummary = hostSummary
    }
  }

  internal fun emitLocalizedSnapshotChanged() {
    emitShellSnapshot()
  }

  private fun emitShellSnapshot() {
    val currentListeners = synchronized(lock) { listeners.toList() }
    if (currentListeners.isEmpty()) {
      return
    }
    val payload = loadShellSnapshot()
    mainThreadPoster.post {
      currentListeners.forEach { listener -> listener(payload) }
    }
  }
}

internal class ServiceOwnedChatRuntimeGateway(
  private val delegate: OpenCrayChatRuntimeGateway? = null,
  private val readGateway: OpenCrayChatRuntimeGateway,
  private val snapshotNotifier: () -> Unit = {},
  private val runtimeHostAccess: OpenCrayRuntimeHostAccess? = null,
  private val onDeviceWarmupAccess: OnDeviceLlmWarmupAccess = NoOpOnDeviceLlmWarmupAccess,
  private val onDevicePreparingPlaceholder: String = "Preparing on-device model",
  private val chatSessionMutationAccess: ServiceOwnedChatSessionMutationAccess? = null,
  private val chatRunControlAccess: ServiceOwnedChatRunControlAccess? = null,
  private val chatApprovalAccess: ServiceOwnedChatApprovalAccess? = null,
  private val chatSubmissionAccess: ServiceOwnedChatSubmissionAccess? = null,
  private val refreshSandboxSessionInfoHandler: (() -> Unit)? = null,
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
) : OpenCrayRuntimeServiceChatGateway {
  private val lock = Any()
  private val chatListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val chatRuntimeListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val liveAssistantDraftEventListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val runtimeEventDeltaListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val runtimeEventDeltaSequencesBySession = linkedMapOf<String, Long>()
  private var latestChatPayload: Map<String, Any?> = emptyMap()
  private val liveAssistantDraftsBySession =
    linkedMapOf<String, LinkedHashMap<String, ServiceOwnedLiveAssistantDraftSnapshot>>()
  private var latestChatRuntimePayload: Map<String, Any?> = emptyMap()

  init {
    latestChatPayload = decorateChatPayload(chatSnapshotGateway().loadChatSnapshot())
    latestChatRuntimePayload =
      decorateChatRuntimePayload(runtimeSnapshotGateway().loadChatRuntimeSnapshot())
  }

  @Suppress("unused")
  private val chatObservationDisposer = if (delegate == null) {
    observeProjectionWithPollingSnapshot(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = { readGateway.loadChatSnapshot() },
      listener = { payload ->
        emitChatPayload(chatPayloadForEmission(decorateChatPayload(payload)))
      },
      pollIntervalMs = SERVICE_OWNED_GATEWAY_POLL_INTERVAL_MS,
    )
  } else {
    chatSnapshotGateway().observeChat { payload ->
      emitChatPayload(chatPayloadForEmission(decorateChatPayload(payload)))
    }
  }

  @Suppress("unused")
  private val chatRuntimeObservationDisposer = if (delegate == null) {
    observeProjectionWithPollingSnapshot(
      mainThreadPoster = mainThreadPoster,
      payloadProvider = { readGateway.loadChatRuntimeSnapshot() },
      listener = { payload ->
        emitChatRuntimePayload(decorateChatRuntimePayload(payload))
      },
      pollIntervalMs = SERVICE_OWNED_GATEWAY_POLL_INTERVAL_MS,
    )
  } else {
    runtimeSnapshotGateway().observeChatRuntime { payload ->
      emitChatRuntimePayload(decorateChatRuntimePayload(payload))
    }
  }

  @Suppress("unused")
  private val runtimeObservationDisposer = runtimeHostAccess?.observe(
    object : AgentSessionRuntimeListener {
      override fun onTaskStarted(sessionId: String, task: com.opencray.core.contracts.AgentTask) {
        emitChatSnapshot()
        if (!emitServiceOwnedRuntimeEventDeltaFromSnapshot()) {
          emitChatRuntimeSnapshot()
        }
      }

      override fun onRunEvent(
        sessionId: String,
        task: com.opencray.core.contracts.AgentTask,
        event: com.opencray.runtime.OpenCrayAgentRunEvent,
      ) {
        emitChatSnapshot()
        if (!emitServiceOwnedRuntimeEventDeltaFromSnapshot()) {
          emitChatRuntimeSnapshot()
        }
      }

      override fun onAssistantDraftUpdated(
        sessionId: String,
        task: com.opencray.core.contracts.AgentTask,
        text: String,
        emittedAtEpochMs: Long,
      ) {
        val draftEventPayload = synchronized(lock) {
          if (
            !updateLiveAssistantDraftLocked(
              sessionId = sessionId,
              task = task,
              text = text,
              emittedAtEpochMs = emittedAtEpochMs,
            )
          ) {
            return@synchronized null
          }
          val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return@synchronized null
          liveAssistantDraftsBySession[sessionId]
            ?.get(pendingMessageId)
            ?.toLiveAssistantDraftEventPayload(sessionId = sessionId, cleared = false)
        }
        if (draftEventPayload != null) {
          serviceChatDebug(
            "service.draftUpdated session=$sessionId task=${task.id} pending=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] ?: "-"} len=${text.length} preview=${text.take(80).replace('\n', ' ')}",
          )
          emitLiveAssistantDraftEvent(draftEventPayload)
          val hasRuntimeEventDeltaListeners = synchronized(lock) {
            runtimeEventDeltaListeners.isNotEmpty()
          }
          val emittedRuntimeDelta = if (hasRuntimeEventDeltaListeners) {
            emitServiceOwnedRuntimeEventDeltaFromSnapshot()
          } else {
            false
          }
          if (!hasRuntimeEventDeltaListeners && !emittedRuntimeDelta) {
            emitChatRuntimeSnapshot()
          }
        }
      }

      override fun onAssistantDraftCleared(
        sessionId: String,
        task: com.opencray.core.contracts.AgentTask,
        emittedAtEpochMs: Long,
      ) {
        val draftEventPayload = synchronized(lock) {
          val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return@synchronized null
          if (
            !clearLiveAssistantDraftLocked(
              sessionId = sessionId,
              pendingMessageId = pendingMessageId,
            )
          ) {
            return@synchronized null
          }
          liveAssistantDraftEventPayload(
            sessionId = sessionId,
            runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?: "",
            taskId = task.id,
            pendingMessageId = pendingMessageId,
            text = "",
            updatedAtEpochMs = emittedAtEpochMs,
            cleared = true,
          )
        }
        if (draftEventPayload != null) {
          serviceChatDebug(
            "service.draftCleared session=$sessionId task=${task.id} pending=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] ?: "-"}",
          )
          emitLiveAssistantDraftEvent(draftEventPayload)
          val hasRuntimeEventDeltaListeners = synchronized(lock) {
            runtimeEventDeltaListeners.isNotEmpty()
          }
          val emittedRuntimeDelta = if (hasRuntimeEventDeltaListeners) {
            emitServiceOwnedRuntimeEventDeltaFromSnapshot()
          } else {
            false
          }
          if (!hasRuntimeEventDeltaListeners && !emittedRuntimeDelta) {
            emitChatRuntimeSnapshot()
          }
        }
      }

      override fun onTaskFinished(
        sessionId: String,
        task: com.opencray.core.contracts.AgentTask,
        result: com.opencray.core.contracts.ExecutionResult,
      ) {
        val draftEventPayload = synchronized(lock) {
          val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return@synchronized null
          if (
            !clearLiveAssistantDraftLocked(
              sessionId = sessionId,
              pendingMessageId = pendingMessageId,
            )
          ) {
            return@synchronized null
          }
          liveAssistantDraftEventPayload(
            sessionId = sessionId,
            runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
              ?.trim()
              ?.takeIf(String::isNotBlank)
              ?: "",
            taskId = task.id,
            pendingMessageId = pendingMessageId,
            text = "",
            updatedAtEpochMs = result.finishedAtEpochMs,
            cleared = true,
          )
        }
        if (draftEventPayload != null) {
          serviceChatDebug(
            "service.taskFinishedClearedDraft session=$sessionId task=${task.id} pending=${task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID] ?: "-"} status=${result.status} error=${result.errorCode ?: "-"}",
          )
          emitLiveAssistantDraftEvent(draftEventPayload)
        }
        emitChatSnapshot()
        if (!emitServiceOwnedRuntimeEventDeltaFromSnapshot()) {
          emitChatRuntimeSnapshot()
        }
        snapshotNotifier()
      }
    },
  )

  private data class ServiceOwnedLiveAssistantDraftSnapshot(
    val runId: String,
    val taskId: String,
    val pendingMessageId: String,
    val text: String,
    val updatedAtEpochMs: Long,
  )

  private fun updateLiveAssistantDraftLocked(
    sessionId: String,
    task: com.opencray.core.contracts.AgentTask,
    text: String,
    emittedAtEpochMs: Long,
  ): Boolean {
    if (delegate != null) {
      return false
    }
    val pendingMessageId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return false
    val normalizedText = text.trim().takeIf(String::isNotBlank) ?: return false
    val sessionDrafts = liveAssistantDraftsBySession.getOrPut(sessionId) { linkedMapOf() }
    val updatedDraft = ServiceOwnedLiveAssistantDraftSnapshot(
      runId = task.metadata[AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: "",
      taskId = task.id,
      pendingMessageId = pendingMessageId,
      text = normalizedText,
      updatedAtEpochMs = emittedAtEpochMs,
    )
    val existing = sessionDrafts[pendingMessageId]
    if (existing == updatedDraft) {
      return false
    }
    sessionDrafts[pendingMessageId] = updatedDraft
    return true
  }

  private fun clearLiveAssistantDraftLocked(
    sessionId: String,
    pendingMessageId: String?,
  ): Boolean {
    if (delegate != null) {
      return false
    }
    val normalizedPendingMessageId = pendingMessageId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return false
    val sessionDrafts = liveAssistantDraftsBySession[sessionId] ?: return false
    val removed = sessionDrafts.remove(normalizedPendingMessageId) != null
    if (sessionDrafts.isEmpty()) {
      liveAssistantDraftsBySession.remove(sessionId)
    }
    return removed
  }

  private fun decorateChatRuntimePayload(
    payload: Map<String, Any?>,
  ): Map<String, Any?> {
    if (delegate != null) {
      return payload
    }
    val sessionId = (payload["sessionId"] as? String)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return payload
    val liveDrafts = synchronized(lock) {
      liveAssistantDraftsBySession[sessionId]
        ?.values
        ?.sortedBy(ServiceOwnedLiveAssistantDraftSnapshot::updatedAtEpochMs)
        .orEmpty()
    }
    if (liveDrafts.isEmpty()) {
      return ensureDecoratedRuntimePayloadVersionSignal(payload)
    }
    return ensureDecoratedRuntimePayloadVersionSignal(payload.toMutableMap().apply {
      this["liveAssistantDrafts"] = liveDrafts.map { draft ->
        mapOf(
          "runId" to draft.runId,
          "taskId" to draft.taskId,
          "pendingMessageId" to draft.pendingMessageId,
          "text" to draft.text,
          "updatedAtEpochMs" to draft.updatedAtEpochMs,
        )
      }
    })
  }

  override fun loadChatSnapshot(): Map<String, Any?> =
    decorateChatPayload(chatSnapshotGateway().loadChatSnapshot()).also { payload ->
      synchronized(lock) {
        latestChatPayload = payload
      }
    }

  override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    val initialPayload = synchronized(lock) {
      chatListeners += listener
      latestChatPayload
    }
    mainThreadPoster.post {
      listener(initialPayload)
    }
    return {
      synchronized(lock) {
        chatListeners -= listener
      }
    }
  }

  override fun loadChatRuntimeSnapshot(): Map<String, Any?> =
    currentDecoratedChatRuntimePayload().also { payload ->
      synchronized(lock) {
        latestChatRuntimePayload = payload
      }
    }

  override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? =
    chatSnapshotGateway().loadChatRunSnapshot(runId)

  override fun waitForChatRun(
    runId: String,
    timeoutMs: Long,
  ): Map<String, Any?>? = chatSnapshotGateway().waitForChatRun(runId, timeoutMs)

  override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    val initialPayload = synchronized(lock) {
      chatRuntimeListeners += listener
      latestChatRuntimePayload
    }
    mainThreadPoster.post {
      listener(initialPayload)
    }
    return {
      synchronized(lock) {
        chatRuntimeListeners -= listener
      }
    }
  }

  override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    delegate?.let { return it.observeLiveAssistantDraftEvents(listener) }
    synchronized(lock) {
      liveAssistantDraftEventListeners += listener
    }
    return {
      synchronized(lock) {
        liveAssistantDraftEventListeners -= listener
      }
    }
  }

  override fun observeRuntimeEventDeltas(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    delegate?.let { return it.observeRuntimeEventDeltas(listener) }
    synchronized(lock) {
      runtimeEventDeltaListeners += listener
    }
    return {
      synchronized(lock) {
        runtimeEventDeltaListeners -= listener
      }
    }
  }

  override fun refreshSandboxSessionInfo() {
    refreshSandboxSessionInfoHandler?.let { handler ->
      handler()
      notifyChatSnapshotsChanged()
      return
    }
    delegateFor("refreshSandboxSessionInfo").refreshSandboxSessionInfo()
  }

  override fun loadMemoryDebugSnapshot(): Map<String, Any?> =
    readGateway.loadMemoryDebugSnapshot()

  override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> =
    readGateway.loadMemoryDebugLinksSnapshot()

  override fun loadSoulDebugSnapshot(): Map<String, Any?> =
    readGateway.loadSoulDebugSnapshot()

  override fun searchMemoryDebug(
    query: String,
    maxResults: Int,
    minScore: Int,
  ): Map<String, Any?> = readGateway.searchMemoryDebug(query, maxResults, minScore)

  override fun getMemoryDebugSlice(
    path: String,
    fromLine: Int?,
    lines: Int,
  ): Map<String, Any?> = readGateway.getMemoryDebugSlice(path, fromLine, lines)

  override fun applyMemoryDebugAction(
    recordId: String,
    actionId: String,
  ): Map<String, Any?> = readGateway.applyMemoryDebugAction(recordId, actionId)

  override fun createChatSession() {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("createChatSession").createChatSession()
      return
    }
    access.createChatSession()
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    notifyChatSnapshotsChanged()
  }

  override fun copyChatSession(sessionId: String) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("copyChatSession").copyChatSession(sessionId)
      return
    }
    access.copyChatSession(sessionId)
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    notifyChatSnapshotsChanged()
  }

  override fun deleteChatSession(sessionId: String) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("deleteChatSession").deleteChatSession(sessionId)
      return
    }
    if (access.deleteChatSession(sessionId)) {
      onDeviceWarmupAccess.ensureWarmForActiveSession()
      notifyChatSnapshotsChanged()
    }
  }

  override fun selectChatSession(sessionId: String) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("selectChatSession").selectChatSession(sessionId)
      return
    }
    access.selectChatSession(sessionId)
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    notifyChatSnapshotsChanged()
  }

  override fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  ) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("branchChatSessionFromMessage").branchChatSessionFromMessage(sessionId, messageId)
      return
    }
    if (access.branchChatSessionFromMessage(sessionId, messageId)) {
      onDeviceWarmupAccess.ensureWarmForActiveSession()
      notifyChatSnapshotsChanged()
    }
  }

  override fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("deleteChatMessage").deleteChatMessage(sessionId, messageId)
      return
    }
    if (access.deleteChatMessage(sessionId, messageId)) {
      notifyChatSnapshotsChanged()
    }
  }

  override fun recallChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegateFor("recallChatMessage").recallChatMessage(sessionId, messageId)
      return
    }
    if (access.recallChatMessage(sessionId, messageId)) {
      notifyChatSnapshotsChanged()
    }
  }

  override fun submitChatMessage(
    text: String,
    attachments: List<com.opencray.runtime.OpenCrayFinalAttachment>,
  ): Map<String, Any?>? {
    val access = chatSubmissionAccess
    if (access == null) {
      return delegateFor("submitChatMessage").submitChatMessage(text, attachments)
    }
    if (onDeviceWarmupAccess.ensureWarmForActiveSession().blocksChatInput()) {
      notifyChatSnapshotsChanged()
      return null
    }
    serviceChatDebug(
      "service.submitChatMessage textLen=${text.trim().length} attachments=${attachments.size}",
    )
    val result = access.submitChatMessage(text, attachments)
    if (result.didMutate) {
      notifyChatSnapshotsChanged()
    }
    return result.payload
  }

  override fun approveChatApproval(taskIdOrRunId: String) {
    val access = chatApprovalAccess
    if (access == null) {
      delegateFor("approveChatApproval").approveChatApproval(taskIdOrRunId)
      return
    }
    access.approveChatApproval(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun approveChatApprovalForSession(taskIdOrRunId: String) {
    val access = chatApprovalAccess
    if (access == null) {
      delegateFor("approveChatApprovalForSession").approveChatApprovalForSession(taskIdOrRunId)
      return
    }
    access.approveChatApprovalForSession(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun rejectChatApproval(taskIdOrRunId: String) {
    val access = chatApprovalAccess
    if (access == null) {
      delegateFor("rejectChatApproval").rejectChatApproval(taskIdOrRunId)
      return
    }
    access.rejectChatApproval(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun interruptChatRun(taskIdOrRunId: String) {
    val access = chatRunControlAccess
    if (access == null) {
      delegateFor("interruptChatRun").interruptChatRun(taskIdOrRunId)
      return
    }
    access.interruptChatRun(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun retryChatRun(taskIdOrRunId: String) {
    val access = chatRunControlAccess
    if (access == null) {
      delegateFor("retryChatRun").retryChatRun(taskIdOrRunId)
      return
    }
    access.retryChatRun(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun notifyChatSnapshotsChanged() {
    emitChatSnapshot()
    if (!emitServiceOwnedRuntimeEventDeltaFromSnapshot()) {
      emitChatRuntimeSnapshot()
    }
    snapshotNotifier()
  }

  internal fun emitLocalizedSnapshotChanged() {
    emitChatSnapshot()
  }

  private fun emitChatSnapshot() {
    emitChatPayload(chatPayloadForEmission(loadChatSnapshot()))
  }

  private fun emitChatRuntimeSnapshot() {
    emitChatRuntimePayload(currentDecoratedChatRuntimePayload())
  }

  private fun emitServiceOwnedRuntimeEventDeltaFromSnapshot(): Boolean {
    if (delegate != null) {
      return false
    }
    val hasListeners = synchronized(lock) { runtimeEventDeltaListeners.isNotEmpty() }
    if (!hasListeners) {
      return false
    }
    val nextPayload = decorateChatRuntimePayload(runtimeSnapshotGateway().loadChatRuntimeSnapshot())
    val deltaPayload = synchronized(lock) {
      runtimeEventDeltaPayloadFromRuntimePayloads(
        previousPayload = latestChatRuntimePayload,
        nextPayload = nextPayload,
      ).also {
        latestChatRuntimePayload = nextPayload
      }
    }
    if (deltaPayload != null) {
      emitRuntimeEventDelta(deltaPayload)
      return true
    }
    return false
  }

  private fun emitServiceOwnedChatAndRuntimeSnapshots() {
    if (delegate != null) {
      return
    }
    emitChatPayload(chatPayloadForEmission(loadChatSnapshot()))
    emitChatRuntimePayload(currentDecoratedChatRuntimePayload())
  }

  private fun currentDecoratedChatRuntimePayload(): Map<String, Any?> =
    decorateChatRuntimePayload(runtimeSnapshotGateway().loadChatRuntimeSnapshot())

  private fun emitLiveAssistantDraftEvent(payload: Map<String, Any?>) {
    if (payload.isEmpty()) {
      return
    }
    val listeners = synchronized(lock) { liveAssistantDraftEventListeners.toList() }
    if (listeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      listeners.forEach { listener -> listener(payload) }
    }
  }

  private fun emitRuntimeEventDelta(payload: Map<String, Any?>) {
    if (payload.isEmpty()) {
      return
    }
    val listeners = synchronized(lock) { runtimeEventDeltaListeners.toList() }
    if (listeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      listeners.forEach { listener -> listener(payload) }
    }
  }

  private fun emitChatPayload(payload: Map<String, Any?>) {
    val listeners = synchronized(lock) {
      latestChatPayload = payload
      chatListeners.toList()
    }
    if (listeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      listeners.forEach { listener -> listener(payload) }
    }
  }

  private fun emitChatRuntimePayload(payload: Map<String, Any?>) {
    val listeners = synchronized(lock) {
      if (delegate == null && payload == latestChatRuntimePayload) {
        return
      }
      latestChatRuntimePayload = payload
      chatRuntimeListeners.toList()
    }
    serviceChatDebug(
      "service.emitChatRuntimePayload listeners=${listeners.size} ${chatRuntimePayloadDebugSummary(payload)}",
    )
    if (listeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      listeners.forEach { listener -> listener(payload) }
    }
  }

  private fun runtimeEventDeltaPayloadFromRuntimePayloads(
    previousPayload: Map<String, Any?>,
    nextPayload: Map<String, Any?>,
  ): Map<String, Any?>? {
    val sessionId = (nextPayload["sessionId"] as? String)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
    val previousKeys = payloadRuntimeEvents(previousPayload)
      .mapTo(linkedSetOf(), ::runtimeEventPayloadMergeKey)
    val nextEvents = payloadRuntimeEvents(nextPayload)
    val deltaEvents = nextEvents.filter { event ->
      runtimeEventPayloadMergeKey(event) !in previousKeys
    }
    if (
      deltaEvents.isEmpty() &&
      payloadUpdatedAtEpochMs(nextPayload) <= payloadUpdatedAtEpochMs(previousPayload)
    ) {
      return null
    }
    val sequence = nextRuntimeEventDeltaSequenceLocked(sessionId)
    return mapOf(
      "sessionId" to sessionId,
      "sequence" to sequence,
      "events" to deltaEvents,
      "totalLength" to nextEvents.size,
      "activeRuns" to (nextPayload["activeRuns"] ?: emptyList<Any?>()),
      "retainedRuns" to (nextPayload["retainedRuns"] ?: emptyList<Any?>()),
      "subAgents" to (nextPayload["subAgents"] ?: emptyList<Any?>()),
      "liveAssistantDrafts" to (nextPayload["liveAssistantDrafts"] ?: emptyList<Any?>()),
      "updatedAtEpochMs" to (nextPayload["updatedAtEpochMs"] ?: 0L),
    )
  }

  private fun nextRuntimeEventDeltaSequenceLocked(sessionId: String): Long {
    val next = (runtimeEventDeltaSequencesBySession[sessionId] ?: 0L) + 1L
    runtimeEventDeltaSequencesBySession[sessionId] = next
    return next
  }

  private fun payloadRuntimeEvents(
    payload: Map<String, Any?>,
  ): List<Map<String, Any?>> = (payload["events"] as? List<*>)
    ?.mapNotNull { item ->
      @Suppress("UNCHECKED_CAST")
      item as? Map<String, Any?>
    }
    .orEmpty()

  private fun runtimeEventPayloadMergeKey(event: Map<String, Any?>): String = listOf(
    event["kind"],
    event["runId"],
    event["taskId"],
    event["executionId"],
    event["executionOrdinal"],
    event["executionKind"],
    event["turn"],
    event["phase"],
    event["stage"],
    event["toolName"],
    event["entryId"],
    event["childRunId"],
    event["childTaskId"],
    event["emittedAtEpochMs"],
  ).joinToString(separator = "|") { value -> value?.toString().orEmpty() }

  private fun chatRuntimePayloadDebugSummary(payload: Map<String, Any?>): String {
    val activeRuns = (payload["activeRuns"] as? List<*>)
      .orEmpty()
      .mapNotNull { item -> item as? Map<*, *> }
    val runSummary = activeRuns.joinToString(separator = ";") { run ->
      val runId = (run["runId"] as? String).orEmpty()
      val taskId = (run["taskId"] as? String).orEmpty()
      val managedProcessCount = (run["managedProcesses"] as? List<*>)?.size ?: 0
      val managedProcessIds = (run["managedProcessIds"] as? List<*>)?.joinToString(",") ?: ""
      val runningManagedProcessCount = run["runningManagedProcessCount"] ?: 0
      val hasLiveManagedProcesses = run["hasLiveManagedProcesses"] ?: false
      val lastEvent = run["lastEvent"] as? Map<*, *>
      val lastKind = lastEvent?.get("kind") as? String ?: "-"
      val lastTool = lastEvent?.get("toolName") as? String ?: "-"
      "${runId.takeLast(12)} task=${taskId.takeLast(12)} mp=$managedProcessCount[$managedProcessIds] running=$runningManagedProcessCount live=$hasLiveManagedProcesses last=$lastKind/$lastTool"
    }
    return "session=${payload["sessionId"] ?: "-"} liveDrafts=${(payload["liveAssistantDrafts"] as? List<*>)?.size ?: 0} activeRuns=${activeRuns.size} retainedRuns=${(payload["retainedRuns"] as? List<*>)?.size ?: 0} events=${(payload["events"] as? List<*>)?.size ?: 0} runs=[$runSummary]"
  }

  private fun payloadUpdatedAtEpochMs(payload: Map<String, Any?>): Long =
    (payload["updatedAtEpochMs"] as? Number)?.toLong() ?: 0L

  private fun payloadSessionId(payload: Map<String, Any?>): String = (payload["sessionId"] as? String)
    ?.trim()
    .orEmpty()

  private fun withPayloadUpdatedAtEpochMs(
    payload: Map<String, Any?>,
    updatedAtEpochMs: Long,
  ): Map<String, Any?> {
    if (payloadUpdatedAtEpochMs(payload) == updatedAtEpochMs) {
      return payload
    }
    return payload.toMutableMap().apply {
      this["updatedAtEpochMs"] = updatedAtEpochMs
    }
  }

  private fun ensureDecoratedChatPayloadVersionSignal(
    payload: Map<String, Any?>,
  ): Map<String, Any?> {
    val previousPayload = synchronized(lock) { latestChatPayload }
    if (previousPayload.isEmpty()) {
      return payload
    }
    val previousInputEnabled = previousPayload["isInputEnabled"] as? Boolean ?: true
    val currentInputEnabled = payload["isInputEnabled"] as? Boolean ?: true
    val previousPlaceholder = previousPayload["composerPlaceholder"] as? String ?: ""
    val currentPlaceholder = payload["composerPlaceholder"] as? String ?: ""
    if (
      previousInputEnabled == currentInputEnabled &&
      previousPlaceholder == currentPlaceholder
    ) {
      return withPayloadUpdatedAtEpochMs(
        payload,
        maxOf(
          payloadUpdatedAtEpochMs(payload),
          payloadUpdatedAtEpochMs(previousPayload),
        ),
      )
    }
    val updatedAtEpochMs = maxOf(
      payloadUpdatedAtEpochMs(payload),
      payloadUpdatedAtEpochMs(previousPayload) + 1L,
      System.currentTimeMillis(),
    )
    return withPayloadUpdatedAtEpochMs(payload, updatedAtEpochMs)
  }

  private fun ensureDecoratedRuntimePayloadVersionSignal(
    payload: Map<String, Any?>,
  ): Map<String, Any?> {
    val sessionId = payloadSessionId(payload)
    if (sessionId.isEmpty()) {
      return payload
    }
    val previousPayload = synchronized(lock) { latestChatRuntimePayload }
    if (previousPayload.isEmpty() || payloadSessionId(previousPayload) != sessionId) {
      return payload
    }
    val currentDisplayState = payloadRuntimeDisplayState(payload)
    val previousDisplayState = payloadRuntimeDisplayState(previousPayload)
    if (currentDisplayState == previousDisplayState) {
      return withPayloadUpdatedAtEpochMs(
        payload,
        maxOf(
          payloadUpdatedAtEpochMs(payload),
          payloadUpdatedAtEpochMs(previousPayload),
        ),
      )
    }
    val updatedAtEpochMs = maxOf(
      payloadUpdatedAtEpochMs(payload),
      payloadUpdatedAtEpochMs(previousPayload) + 1L,
      latestRuntimeDisplayEpochMs(payload),
      System.currentTimeMillis(),
    )
    return withPayloadUpdatedAtEpochMs(payload, updatedAtEpochMs)
  }

  private fun payloadRuntimeDisplayState(payload: Map<String, Any?>): List<Any?> = listOf(
    payload["activeRuns"],
    payload["retainedRuns"],
    payload["subAgents"],
    payload["liveAssistantDrafts"],
    payload["hostLifecycle"],
  )

  private fun latestRuntimeDisplayEpochMs(payload: Map<String, Any?>): Long {
    fun collect(value: Any?): Long = when (value) {
      is Map<*, *> -> {
        val ownEpoch = (value["updatedAtEpochMs"] as? Number)?.toLong() ?: 0L
        value.values.fold(ownEpoch) { latest, item -> maxOf(latest, collect(item)) }
      }
      is Iterable<*> -> value.fold(0L) { latest, item -> maxOf(latest, collect(item)) }
      else -> 0L
    }
    return payloadRuntimeDisplayState(payload).fold(0L) { latest, item ->
      maxOf(latest, collect(item))
    }
  }

  private fun decorateChatPayload(
    payload: Map<String, Any?>,
  ): Map<String, Any?> {
    val warmupState = onDeviceWarmupAccess.ensureWarmForActiveSession()
    if (warmupState.phase == OnDeviceLlmWarmupPhase.FAILED) {
      val failureMessage = warmupState.failureMessage
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: onDevicePreparingPlaceholder
      return ensureDecoratedChatPayloadVersionSignal(payload.toMutableMap().apply {
        this["composerPlaceholder"] = failureMessage
      })
    }
    if (!warmupState.blocksChatInput()) {
      return ensureDecoratedChatPayloadVersionSignal(payload)
    }
    return ensureDecoratedChatPayloadVersionSignal(payload.toMutableMap().apply {
      this["isInputEnabled"] = false
      this["composerPlaceholder"] = onDevicePreparingPlaceholder
    })
  }

  private fun chatPayloadForEmission(payload: Map<String, Any?>): Map<String, Any?> =
    if (payload["runtimeActivity"] == null) {
      payload
    } else {
      payload.toMutableMap().apply {
        this["runtimeActivity"] = null
      }
    }

  private fun delegateFor(operation: String): OpenCrayChatRuntimeGateway =
    requireNotNull(delegate) {
      "Service-owned chat gateway cannot '$operation' without a fallback delegate or service-owned access."
    }

  private fun liveAssistantDraftEventPayload(
    sessionId: String,
    runId: String,
    taskId: String,
    pendingMessageId: String,
    text: String,
    updatedAtEpochMs: Long,
    cleared: Boolean,
  ): Map<String, Any?> = mapOf(
    "sessionId" to sessionId,
    "runId" to runId,
    "taskId" to taskId,
    "pendingMessageId" to pendingMessageId,
    "text" to text,
    "updatedAtEpochMs" to updatedAtEpochMs,
    "cleared" to cleared,
  )

  private fun ServiceOwnedLiveAssistantDraftSnapshot.toLiveAssistantDraftEventPayload(
    sessionId: String,
    cleared: Boolean,
  ): Map<String, Any?> = liveAssistantDraftEventPayload(
    sessionId = sessionId,
    runId = runId,
    taskId = taskId,
    pendingMessageId = pendingMessageId,
    text = text,
    updatedAtEpochMs = updatedAtEpochMs,
    cleared = cleared,
  )

  private fun chatSnapshotGateway(): OpenCrayChatRuntimeGateway = delegate ?: readGateway

  private fun runtimeSnapshotGateway(): OpenCrayChatRuntimeGateway = delegate ?: readGateway
}

internal class ServiceOwnedSkillsGateway(
  @Suppress("unused")
  private val delegate: OpenCraySkillsGateway? = null,
  private var skillsFacade: SkillsFacade,
  private var localeTag: String,
  private var skillInstalled: (String) -> String,
  private var skillRemoved: (String) -> String,
  private var skillsReloaded: String,
  private val snapshotNotifier: () -> Unit,
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
) : OpenCraySkillsGateway {
  private val lock = Any()
  private val listeners = linkedSetOf<(Map<String, Any?>) -> Unit>()

  override fun loadSkillsSnapshot(
    query: String,
    suggestedLimit: Int,
  ): Map<String, Any?> {
    val normalizedQuery = query.trim()
    val snapshot = if (normalizedQuery.isEmpty() && suggestedLimit <= 0) {
      skillsFacade.loadSnapshot()
    } else {
      skillsFacade.loadSnapshot(
        query = normalizedQuery,
        suggestedLimit = suggestedLimit,
      )
    }
    return snapshot.toGatewayMap()
  }

  override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    synchronized(lock) {
      listeners += listener
    }
    mainThreadPoster.post {
      listener(loadSkillsSnapshot(query = "", suggestedLimit = 0))
    }
    return {
      synchronized(lock) {
        listeners -= listener
      }
    }
  }

  override fun setSkillEnabled(skillId: String, enabled: Boolean) {
    require(skillsFacade.setSkillEnabled(skillId = skillId, enabled = enabled)) {
      "Skill '$skillId' is not installed."
    }
    notifySkillsSnapshotChanged()
  }

  override fun installSuggestedSkill(skillId: String): String =
    installSkillSource(sourceRef = skillId, selectedSkillName = "")

  override fun installSkillSource(
    sourceRef: String,
    selectedSkillName: String,
  ): String {
    val normalizedSourceRef = sourceRef.trim()
    val normalizedSelectedSkillName = selectedSkillName.trim()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    val result = skillsFacade.installSkillSource(
      sourceRef = normalizedSourceRef,
      selectedSkillName = normalizedSelectedSkillName,
    )
    require(result.succeeded) {
      result.errorMessage?.trim()?.takeIf(String::isNotBlank)
        ?: "Unable to install '$normalizedSourceRef'."
    }
    notifySkillsSnapshotChanged()
    return renderInstalledSkillMessage(
      installedSkillId = result.installedSkillId,
      selectedSkillName = normalizedSelectedSkillName,
      sourceRef = normalizedSourceRef,
      skillInstalled = skillInstalled,
    )
  }

  override fun installSkillSourceBatch(
    sourceRef: String,
    selectedSkillNames: List<String>,
  ): String {
    val normalizedSourceRef = sourceRef.trim()
    val normalizedSelectedSkillNames = selectedSkillNames
      .asSequence()
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .toList()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    require(normalizedSelectedSkillNames.isNotEmpty()) {
      "At least one skill must be selected."
    }
    val attempt = skillsFacade.installSkillSourceBatch(
      sourceRef = normalizedSourceRef,
      selectedSkillNames = normalizedSelectedSkillNames,
    )
    val result = requireNotNull(attempt.result) {
      attempt.errorMessage?.trim()?.takeIf(String::isNotBlank)
        ?: "Unable to install selected skills from '$normalizedSourceRef'."
    }
    if (result.failedCount > 0) {
      throw IllegalStateException(
        result.entries.firstNotNullOfOrNull { entry ->
          entry.errorMessage?.trim()?.takeIf(String::isNotBlank)
        } ?: "Unable to install selected skills from '$normalizedSourceRef'.",
      )
    }
    notifySkillsSnapshotChanged()
    return renderInstalledSkillBatchMessage(
      selectedSkillNames = normalizedSelectedSkillNames,
      result = result,
      skillInstalled = skillInstalled,
    )
  }

  override fun inspectSkillSource(sourceRef: String): Map<String, Any?> {
    val normalizedSourceRef = sourceRef.trim()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    val attempt = skillsFacade.inspectSkillSource(normalizedSourceRef)
    val result = requireNotNull(attempt.result) {
      attempt.errorMessage?.trim()?.takeIf(String::isNotBlank)
        ?: "Unable to inspect '$normalizedSourceRef'."
    }
    return result.toGatewayMap()
  }

  override fun deleteInstalledSkill(skillId: String): String {
    val normalizedSkillId = skillId.trim()
    require(normalizedSkillId.isNotEmpty()) {
      "Skill id cannot be blank."
    }
    val removed = skillsFacade.deleteInstalledSkill(normalizedSkillId)
    require(removed) {
      "Unable to remove '$normalizedSkillId'."
    }
    notifySkillsSnapshotChanged()
    return skillRemoved(normalizedSkillId)
  }

  override fun refreshSkills(): String {
    skillsFacade.refresh()
    notifySkillsSnapshotChanged()
    return skillsReloaded
  }

  override fun checkInstalledSkillUpdates(skillId: String): String {
    val normalizedSkillId = skillId.trim()
    val report = skillsFacade.checkInstalledSkillUpdates(normalizedSkillId)
    return renderInstalledSkillUpdateCheckMessage(
      requestedSkillId = normalizedSkillId.takeIf(String::isNotBlank),
      report = report,
      localeTag = localeTag,
    )
  }

  override fun updateInstalledSkill(skillId: String): String {
    val normalizedSkillId = skillId.trim()
    val report = skillsFacade.updateInstalledSkill(normalizedSkillId)
    if (report.updatedCount == 0 && report.failedCount > 0 && report.skippedCount == 0) {
      throw IllegalStateException(
        report.results.firstNotNullOfOrNull { result ->
          result.errorMessage?.trim()?.takeIf(String::isNotBlank)
        } ?: "SkillsUpdate failed.",
      )
    }
    notifySkillsSnapshotChanged()
    return renderInstalledSkillUpdateMessage(
      requestedSkillId = normalizedSkillId.takeIf(String::isNotBlank),
      report = report,
      localeTag = localeTag,
    )
  }

  override fun loadSkillInstructions(skillId: String): Map<String, Any?> {
    val instructions = skillsFacade.loadInstructions(skillId)
    requireNotNull(instructions) {
      "Skill '$skillId' is unavailable."
    }
    return instructions.toGatewayMap()
  }

  override fun loadSuggestedSkillInstructions(
    sourceRef: String,
    selectedSkillName: String,
  ): Map<String, Any?> {
    val normalizedSourceRef = sourceRef.trim()
    require(normalizedSourceRef.isNotEmpty()) {
      "Skill source cannot be blank."
    }
    val instructions = skillsFacade.loadSuggestedInstructions(
      sourceRef = normalizedSourceRef,
      selectedSkillName = selectedSkillName.trim(),
    )
    requireNotNull(instructions) {
      "Skill source '$normalizedSourceRef' is unavailable."
    }
    return instructions.toGatewayMap()
  }

  override fun activateSkillsInstallSource(sourceId: String): String =
    skillsFacade.activateInstallSource(sourceId)

  internal fun updateLocalizedResources(
    skillsFacade: SkillsFacade,
    localeTag: String,
    skillInstalled: (String) -> String,
    skillRemoved: (String) -> String,
    skillsReloaded: String,
  ) {
    synchronized(lock) {
      this.skillsFacade = skillsFacade
      this.localeTag = localeTag
      this.skillInstalled = skillInstalled
      this.skillRemoved = skillRemoved
      this.skillsReloaded = skillsReloaded
    }
  }

  internal fun emitLocalizedSnapshotChanged() {
    emitSkillsSnapshot()
  }

  private fun notifySkillsSnapshotChanged() {
    emitSkillsSnapshot()
    snapshotNotifier()
  }

  private fun emitSkillsSnapshot() {
    val currentListeners = synchronized(lock) { listeners.toList() }
    if (currentListeners.isEmpty()) {
      return
    }
    val payload = loadSkillsSnapshot(query = "", suggestedLimit = 0)
    mainThreadPoster.post {
      currentListeners.forEach { listener -> listener(payload) }
    }
  }
}

internal class ServiceOwnedSettingsGateway(
  @Suppress("unused")
  private val delegate: OpenCraySettingsGateway? = null,
  private var localeTag: String,
  private var settingsFacade: SettingsFacade,
  private var notificationSettingsFacade: NotificationSettingsFacade,
  private val strongBackgroundSettingsAccess: StrongBackgroundSettingsAccess =
    NoOpStrongBackgroundSettingsAccess,
  appLanguageSettingsAccess: AppLanguageSettingsGatewayAccess? = null,
  private val sandboxSettingsAccess: SandboxSettingsGatewayAccess,
  private var networkSearchConfigFacade: NetworkSearchConfigFacade,
  private var mediaSpeechSettingsFacade: MediaSpeechSettingsFacade,
  private var personalizationFacade: PersonalizationFacade,
  private var safetySettingsFacade: SafetySettingsFacade,
  private var llmConfigFacade: LlmConfigFacade,
  private var mcpSettingsFacade: McpSettingsFacade,
  private val shellSnapshotNotifier: () -> Unit = {},
  private val chatSnapshotNotifier: () -> Unit = {},
  private val onDeviceWarmupAccess: OnDeviceLlmWarmupAccess = NoOpOnDeviceLlmWarmupAccess,
  private val settingsOverviewNotifier: () -> Unit = {},
  private val skillsSnapshotNotifier: () -> Unit = {},
  private val skillsProjectionNotifier: () -> Unit = {},
  private val localizedResourcesRefresh: () -> Unit = {},
  private val runtimeServiceConnectionStateProvider: () -> RuntimeServiceConnectionState? = { null },
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
) : OpenCraySettingsGateway {
  private val lock = Any()
  private val resolvedAppLanguageSettingsAccess: AppLanguageSettingsGatewayAccess =
    appLanguageSettingsAccess
      ?: delegate?.let(::GatewayBackedAppLanguageSettingsGatewayAccess)
      ?: error(
        "Service-owned settings gateway requires an app-language access implementation when no delegate is provided.",
      )
  private val settingsOverviewListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()

  override fun loadSettingsOverview(): Map<String, Any?> =
    settingsFacade.loadOverview().toSettingsOverviewGatewayMap()

  override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit {
    synchronized(lock) {
      settingsOverviewListeners += listener
    }
    mainThreadPoster.post {
      listener(loadSettingsOverview())
    }
    return {
      synchronized(lock) {
        settingsOverviewListeners -= listener
      }
    }
  }

  override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> {
    val routeId = SettingsRouteId.fromWireValue(routeIdRaw) ?: SettingsRouteId.WORKSPACE_ACCESS
    return settingsFacade.loadDetail(routeId).toSettingsDetailGatewayMap()
  }

  override fun loadNotificationSettings(): Map<String, Any?> =
    notificationSettingsFacade.load().toGatewayMap()

  override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> =
    notificationSettingsFacade
      .save(payload.toSaveNotificationSettingsRequest())
      .toGatewayMap()

  override fun loadStrongBackgroundSnapshot(): Map<String, Any?> = buildMap {
    putAll(strongBackgroundSettingsAccess.loadSnapshot())
    runtimeServiceConnectionStateProvider()?.let { state ->
      put("runtimeServiceConnectionState", state.snapshotMap())
    }
  }

  override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> =
    strongBackgroundSettingsAccess.performAction(actionId)

  override fun loadNetworkSearchConfig(): Map<String, Any?> =
    networkSearchConfigFacade.load().toGatewayMap()

  override fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?> {
    val snapshot = networkSearchConfigFacade.save(slots.toSaveNetworkSearchConfigRequest())
    notifySettingsOverviewChanged()
    return snapshot.toGatewayMap()
  }

  override fun loadMediaSpeechConfig(): Map<String, Any?> =
    mediaSpeechSettingsFacade.load().toGatewayMap()

  override fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?> {
    val snapshot = mediaSpeechSettingsFacade.save(payload.toSaveMediaSpeechConfigRequest())
    notifySettingsOverviewChanged()
    return snapshot.toGatewayMap()
  }

  override fun loadSandboxSettings(): Map<String, Any?> =
    sandboxSettingsAccess.load().toGatewayMap(localeTag)

  override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> {
    val current = sandboxSettingsAccess.load()
    val parsed = parseSandboxSettingsPayload(
      payload = payload,
      existingState = current.state,
    )
    val snapshot = sandboxSettingsAccess.save(
      state = parsed.state,
      e2bApiKey = parsed.e2bApiKey,
    )
    notifySettingsOverviewChanged()
    return snapshot.toGatewayMap(localeTag)
  }

  override fun loadPersonalizationConfig(): Map<String, Any?> =
    personalizationFacade.load().toPersonalizationGatewayMap()

  override fun savePersonalizationConfig(
    presetId: String,
    customLabel: String,
    customGuidance: String,
  ): Map<String, Any?> {
    val snapshot = personalizationFacade.save(
      com.opencray.app.facade.personalization.SavePersonalizationConfigRequest(
        presetId = presetId,
        customLabel = customLabel,
        customGuidance = customGuidance,
      ),
    )
    notifySettingsOverviewChanged()
    return snapshot.toPersonalizationGatewayMap()
  }

  override fun runPersonalizationReset(scopeId: String): Map<String, Any?> {
    val snapshot = personalizationFacade.reset(
      com.opencray.app.facade.personalization.PersonalizationResetScope.fromWireValue(scopeId),
    )
    notifySettingsOverviewChanged()
    return snapshot.toPersonalizationGatewayMap()
  }

  override fun setAppLanguage(languageId: String): Map<String, Any?> {
    val snapshot = resolvedAppLanguageSettingsAccess.setAppLanguage(languageId)
    localizedResourcesRefresh()
    shellSnapshotNotifier()
    chatSnapshotNotifier()
    emitSettingsOverview()
    settingsOverviewNotifier()
    skillsSnapshotNotifier()
    skillsProjectionNotifier()
    return snapshot
  }

  override fun loadSafetySettings(): Map<String, Any?> =
    safetySettingsFacade.load().toSafetyGatewayMap()

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
  ): Map<String, Any?> {
    val snapshot = safetySettingsFacade.save(
      safetySaveRequest(
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
        liveContextModeId = liveContextModeId,
        memoryToolsEnabled = memoryToolsEnabled,
        subAgentContextDefaultModeId = subAgentContextDefaultModeId,
        subAgentContextProfileOverrides = subAgentContextProfileOverrides,
      ),
    )
    chatSnapshotNotifier()
    return snapshot.toSafetyGatewayMap()
  }

  private fun notifySettingsOverviewChanged() {
    emitSettingsOverview()
    settingsOverviewNotifier()
  }

  private fun emitSettingsOverview() {
    val currentListeners = synchronized(lock) { settingsOverviewListeners.toList() }
    if (currentListeners.isEmpty()) {
      return
    }
    val payload = loadSettingsOverview()
    mainThreadPoster.post {
      currentListeners.forEach { listener -> listener(payload) }
    }
  }

  internal fun updateLocalizedResources(
    localeTag: String,
    settingsFacade: SettingsFacade,
    notificationSettingsFacade: NotificationSettingsFacade,
    networkSearchConfigFacade: NetworkSearchConfigFacade,
    mediaSpeechSettingsFacade: MediaSpeechSettingsFacade,
    personalizationFacade: PersonalizationFacade,
    safetySettingsFacade: SafetySettingsFacade,
    llmConfigFacade: LlmConfigFacade,
    mcpSettingsFacade: McpSettingsFacade,
  ) {
    synchronized(lock) {
      this.localeTag = localeTag
      this.settingsFacade = settingsFacade
      this.notificationSettingsFacade = notificationSettingsFacade
      this.networkSearchConfigFacade = networkSearchConfigFacade
      this.mediaSpeechSettingsFacade = mediaSpeechSettingsFacade
      this.personalizationFacade = personalizationFacade
      this.safetySettingsFacade = safetySettingsFacade
      this.llmConfigFacade = llmConfigFacade
      this.mcpSettingsFacade = mcpSettingsFacade
    }
  }

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
  ): Map<String, Any?> = llmConfigFacade.save(
    com.opencray.app.facade.llm.SaveLlmConfigRequest(
      enabled = enabled,
      streamingEnabled = streamingEnabled,
      providerMode = providerMode,
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
      openAiPromptCacheKeyStrategy = openAiPromptCacheKeyStrategy,
      openAiPromptCacheRetention = openAiPromptCacheRetention,
      anthropicPromptCachingEnabled = anthropicPromptCachingEnabled,
      anthropicPromptCacheTtl = anthropicPromptCacheTtl,
      contextBudgetPreset = contextBudgetPreset,
      contextBudgetReservedOutputTokens = contextBudgetReservedOutputTokens,
      contextBudgetSafetyMarginTokens = contextBudgetSafetyMarginTokens,
      contextBudgetEffectiveInputPercent = contextBudgetEffectiveInputPercent,
      selectedOnDeviceModelId = selectedOnDeviceModelId,
      onDeviceMaxContextWindow = onDeviceMaxContextWindow,
      onDeviceMaxTokens = onDeviceMaxTokens,
      onDeviceTopK = onDeviceTopK,
      onDeviceTopP = onDeviceTopP,
      onDeviceTemperature = onDeviceTemperature,
      onDeviceAccelerator = onDeviceAccelerator,
      onDeviceThinkingEnabled = onDeviceThinkingEnabled,
      onDeviceLiteModeEnabled = onDeviceLiteModeEnabled,
    ),
  ).toGatewayMap().also {
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    chatSnapshotNotifier()
  }

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
  ): Map<String, Any?> = llmConfigFacade.saveCustomProvider(
    com.opencray.app.facade.llm.SaveCustomLlmProviderRequest(
      selectedProviderOptionId = selectedProviderOptionId,
      streamingEnabled = streamingEnabled,
      protocol = protocol,
      providerName = providerName,
      providerNotes = providerNotes,
      baseUrl = baseUrl,
      apiKey = apiKey,
      model = model,
      reasoningEffort = reasoningEffort,
      systemPrompt = systemPrompt,
      openAiPromptCacheKeyStrategy = openAiPromptCacheKeyStrategy
        ?: LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_KEY_STRATEGY,
      openAiPromptCacheRetention = openAiPromptCacheRetention
        ?: LlmSettingsState.DEFAULT_OPENAI_PROMPT_CACHE_RETENTION,
      anthropicPromptCachingEnabled = anthropicPromptCachingEnabled
        ?: LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHING_ENABLED,
      anthropicPromptCacheTtl = anthropicPromptCacheTtl
        ?: LlmSettingsState.DEFAULT_ANTHROPIC_PROMPT_CACHE_TTL,
      contextBudgetPreset = contextBudgetPreset,
      contextBudgetReservedOutputTokens = contextBudgetReservedOutputTokens,
      contextBudgetSafetyMarginTokens = contextBudgetSafetyMarginTokens,
      contextBudgetEffectiveInputPercent = contextBudgetEffectiveInputPercent,
    ),
  ).toGatewayMap()

  override fun validateLlmConfig(
    providerId: String,
    protocol: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    reasoningEffort: String,
  ): Map<String, Any?> = llmConfigFacade.validate(
    com.opencray.app.facade.llm.ValidateLlmConfigRequest(
      providerId = providerId,
      protocol = protocol,
      baseUrl = baseUrl,
      apiKey = apiKey,
      model = model,
      reasoningEffort = reasoningEffort,
    ),
  ).toGatewayMap()

  override fun downloadOnDeviceLlmModel(modelId: String): Map<String, Any?> {
    val snapshot = llmConfigFacade.downloadOnDeviceModel(modelId).toGatewayMap()
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    notifySettingsOverviewChanged()
    chatSnapshotNotifier()
    return snapshot
  }

  override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> {
    val snapshot = llmConfigFacade.cancelOnDeviceModelDownload(modelId).toGatewayMap()
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    notifySettingsOverviewChanged()
    chatSnapshotNotifier()
    return snapshot
  }

  override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> {
    val snapshot = llmConfigFacade.deleteOnDeviceModel(modelId).toGatewayMap()
    onDeviceWarmupAccess.ensureWarmForActiveSession()
    notifySettingsOverviewChanged()
    chatSnapshotNotifier()
    return snapshot
  }

  override fun loadMcpSettings(): Map<String, Any?> =
    mcpSettingsFacade.load().toGatewayMap()

  override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> =
    mcpSettingsFacade.setMasterEnabled(enabled).toGatewayMap()

  override fun setMcpServerEnabled(
    serverId: String,
    enabled: Boolean,
  ): Map<String, Any?> =
    mcpSettingsFacade.setServerEnabled(serverId = serverId, enabled = enabled).toGatewayMap()
}
