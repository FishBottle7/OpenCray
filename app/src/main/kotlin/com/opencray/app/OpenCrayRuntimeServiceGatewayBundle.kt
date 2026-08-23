package com.opencray.app

import android.os.Handler
import android.os.Looper
import com.opencray.app.facade.llm.LocalLlmConfigFacade
import com.opencray.app.facade.media.LocalMediaSpeechSettingsFacade
import com.opencray.app.facade.mcp.LocalMcpSettingsFacade
import com.opencray.app.facade.notifications.LocalNotificationSettingsFacade
import com.opencray.app.facade.personalization.LocalPersonalizationFacade
import com.opencray.app.facade.safety.LocalSafetySettingsFacade
import com.opencray.app.facade.safety.SafetySettingsFacade
import com.opencray.app.facade.search.LocalNetworkSearchConfigFacade
import com.opencray.app.facade.settings.LocalSettingsFacade
import com.opencray.app.facade.skills.LocalSkillsFacade
import com.opencray.app.shell.AppShellStateStore
import java.nio.file.Path

internal class OpenCrayRuntimeServiceGatewayBundle(
  val shellGateway: OpenCrayShellGateway,
  val chatRuntimeGateway: OpenCrayRuntimeServiceChatGateway,
  val skillsGateway: OpenCraySkillsGateway,
  val settingsGateway: OpenCraySettingsGateway,
  private val disposeHandler: () -> Unit = {},
) {
  private val lock = Any()
  private var disposed: Boolean = false

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

  fun dispose() {
    val handler = synchronized(lock) {
      if (disposed) {
        null
      } else {
        disposed = true
        disposeHandler
      }
    } ?: return
    handler()
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
      val runtimeServicePort = gatewayDependencies.runtimeServicePort
      val serviceShellHostLifecycleDescriptor = serviceShellHostLifecycleDescriptor(
        runtimeControllerLifecycle = gatewayDependencies.runtimeControllerLifecycle,
        runtimeOwnerLifecycle = runtimeServicePort.lifecycleDescriptor,
        runtimeServiceLifecycle = gatewayDependencies.serviceLifecycle,
      )
      val localizedContextProvider = {
        OpenCrayLocaleManager.wrap(appContext)
      }
      val strings = localizedHostRuntimeStrings(localizedContextProvider())
      val projectionChatGateway = projectionOnlyOpenCrayChatRuntimeGateway(
        context = appContext,
        diagnosticsSource = ProjectionOnlyChatRuntimeDiagnosticsSource(
          hostLifecycleDescriptor = serviceShellHostLifecycleDescriptor,
          connectionStateProvider = { runtimeServiceConnectionState },
          localRuntimeServerStateProvider = gatewayDependencies.localRuntimeServerStateProvider,
          runtimeControllerLifecycleProvider = {
            gatewayDependencies.runtimeControllerLifecycle
          },
          runtimeOwnerLifecycleProvider = { runtimeServicePort.lifecycleDescriptor },
          runtimeOwnerWorkSummaryProvider = runtimeServicePort.ownerObservationAccess::activeWorkSummary,
          serviceLifecycleProvider = { gatewayDependencies.serviceLifecycle },
          serviceWorkStateProvider = gatewayDependencies.serviceWorkStateProvider,
          serviceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
          ownerLeaseProvider = gatewayDependencies.runtimeServiceOwnerLeaseProvider,
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
        runtimeHostAccess = runtimeServicePort.ownerObservationAccess,
        runtimeControllerLifecycle = gatewayDependencies.runtimeControllerLifecycle,
        runtimeServiceLifecycle = gatewayDependencies.serviceLifecycle,
        runtimeServiceWorkStateProvider = gatewayDependencies.serviceWorkStateProvider,
        runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
        runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
        runtimeServiceOwnerLeaseProvider = gatewayDependencies.runtimeServiceOwnerLeaseProvider,
        runtimeServiceConnectionStateProvider = { runtimeServiceConnectionState },
        localRuntimeServerStateProvider = gatewayDependencies.localRuntimeServerStateProvider,
        mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
        hostLifecycleDescriptor = serviceShellHostLifecycleDescriptor,
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
        runtimeHostAccess = runtimeServicePort.ownerObservationAccess,
        onDeviceWarmupAccess = onDeviceWarmupAccess,
        onDevicePreparingPlaceholder = strings.chatMessageOnDevicePreparing,
        chatSessionMutationAccess = ServiceOwnedChatSessionMutationAccess(
          chatSessionStore = chatSessionStore,
          runtimeHostAccess = runtimeServicePort.chatMutationAccess,
          chatUnreadMessageState = chatUnreadMessageState,
          pendingApprovalState = pendingApprovalState,
          runtimeEventState = runtimeEventState,
          terminalReplayRepairer = runtimeServicePort.replayAccess.terminalReplayRepairer,
          mediaGc = {
            AppAgentWorkspaceMediaGc.sweep(
              workspaceRoot = gatewayDependencies.workspaceRootProvider(),
              chatSessionStore = chatSessionStore,
            )
          },
        ),
        chatRunControlAccess = ServiceOwnedChatRunControlAccess(
          chatSessionStore = ChatSessionLocalStore.fromContext(appContext),
          runtimeHostAccess = runtimeServicePort.chatMutationAccess,
          pendingApprovalState = pendingApprovalState,
          runtimeEventState = runtimeEventState,
          runCancellationReplayRecorder = runtimeServicePort.replayAccess.runCancellationRecorder,
          subAgentReplayRecorder = runtimeServicePort.replayAccess.subAgentReplayRecorder,
          isChineseLocale = {
            localizedHostRuntimeStrings(
              OpenCrayLocaleManager.wrap(appContext),
            ).localeTag.trim().lowercase(java.util.Locale.US).startsWith("zh")
          },
        ),
        chatApprovalAccess = ServiceOwnedChatApprovalAccess(
          approveChatApprovalHandler = gatewayDependencies.approvalDecisionAccess::approve,
          approveChatApprovalForSessionHandler =
            gatewayDependencies.approvalDecisionAccess::approveForSession,
          rejectChatApprovalHandler = gatewayDependencies.approvalDecisionAccess::reject,
        ),
        chatSubmissionAccess = ServiceOwnedChatSubmissionAccess(
          chatSessionStore = chatSessionStore,
          runtimeHostAccess = runtimeServicePort.chatSubmissionHostAccess,
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
            runtimeHostAccess = runtimeServicePort.chatSubmissionHostAccess,
            taskSafetyMetadata = buildTaskSafetyMetadata(
              snapshot = gatewayDependencies.safetySettingsFacade.load(),
              approvedReadRoots = gatewayDependencies.approvedReadRootsProvider(),
            ),
            lifecycleDescriptor = runtimeServicePort.lifecycleDescriptor,
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
        scheduledTaskManager = AppScheduledTaskManager.fromContext(appContext),
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
        disposeHandler = {
          chatGateway.dispose()
          shellGateway.dispose()
        },
      )
    }
  }
}

internal data class RuntimeServiceGatewayBundleDependencies(
  val runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor? = null,
  val runtimeServicePort: RuntimeServicePort,
  val localRuntimeServerStateProvider: () -> LocalRuntimeServerState? = { null },
  val serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  val serviceWorkStateProvider: () -> RuntimeServiceWorkState,
  val safetySettingsFacade: SafetySettingsFacade,
  val workspaceRootProvider: () -> Path,
  val approvedReadRootsProvider: () -> ApprovedReadRootsSnapshot,
  val approvalDecisionAccess: RuntimeServiceApprovalDecisionAccess,
  val runtimeServiceOwnerLeaseProvider: () -> RuntimeServiceOwnerLease? = { null },
  val runtimeServiceOwnerWriteGuard: () -> Boolean = { true },
  val onDeviceWarmupPlanner: (String) -> OnDeviceLlmWarmupSpec? = { null },
) {
  val runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor
    get() = runtimeServicePort.lifecycleDescriptor
}

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

internal fun serviceShellHostLifecycleDescriptor(
  runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor?,
  runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor,
  runtimeServiceLifecycle: RuntimeServiceLifecycleDescriptor,
): HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(
  processStartId = runtimeServiceLifecycle.processStartId,
  processStartedAtEpochMs = runtimeServiceLifecycle.processStartedAtEpochMs,
  hostInstanceId = runtimeServiceLifecycle.serviceInstanceId,
  runtimeOwnerId = runtimeOwnerLifecycle.runtimeOwnerId,
  runtimeControllerId = runtimeControllerLifecycle?.controllerInstanceId
    ?: runtimeOwnerLifecycle.runtimeControllerId,
  durableRuntimeControllerId = runtimeControllerLifecycle?.durableControllerId
    ?: runtimeOwnerLifecycle.durableRuntimeControllerId,
  hostCreatedAtEpochMs = runtimeServiceLifecycle.serviceCreatedAtEpochMs,
)
