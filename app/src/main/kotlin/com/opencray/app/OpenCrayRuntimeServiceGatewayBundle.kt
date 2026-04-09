package com.opencray.app

import android.os.Handler
import android.os.Looper
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
import com.opencray.app.shell.AppShellStateStore

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
      serviceHost: OpenCrayRuntimeServiceHost,
      runtimeServiceKeepAliveStateProvider: () -> RuntimeServiceKeepAliveState? = { null },
      runtimeServiceKeepAliveChangeRegistrar: RuntimeServiceKeepAliveChangeRegistrar? = null,
      runtimeServiceConnectionState: RuntimeServiceConnectionState =
        RuntimeServiceConnectionState.binderConnected(),
    ): OpenCrayRuntimeServiceGatewayBundle {
      val chatUnreadMessageState = ChatUnreadMessageState()
      val pendingApprovalState = ChatPendingApprovalState()
      val runtimeEventState = ChatRuntimeEventState()
      val hostRuntime = OpenCrayHostRuntime.createForRuntimeService(
        appContext = appContext,
        serviceHost = serviceHost,
        runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
        runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
        runtimeServiceConnectionState = runtimeServiceConnectionState,
        chatUnreadMessageState = chatUnreadMessageState,
        chatPendingApprovalState = pendingApprovalState,
        chatRuntimeEventState = runtimeEventState,
      )
      val strings = OpenCrayHostRuntime.localizedHostRuntimeStrings(
        OpenCrayLocaleManager.wrap(appContext),
      )
      val shellGateway = ServiceOwnedShellGateway(
        stateStore = AppShellStateStore.fromContext(appContext),
        localeTag = strings.localeTag,
        hostLabel = strings.shellHostLabel,
        hostSummary = strings.shellHostSummary,
        runtimeHostAccess = serviceHost.runtimeAccess.hostAccess,
        runtimeServiceLifecycle = serviceHost.serviceLifecycle,
        runtimeServiceWorkStateProvider = serviceHost.serviceWorkStateTracker::currentState,
        runtimeServiceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
        runtimeServiceKeepAliveChangeRegistrar = runtimeServiceKeepAliveChangeRegistrar,
        runtimeServiceConnectionStateProvider = { runtimeServiceConnectionState },
        mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
      )
      val skillsGateway = ServiceOwnedSkillsGateway(
        delegate = hostRuntime,
        skillsFacade = LocalSkillsFacade.fromContext(appContext),
        localeTag = strings.localeTag,
        skillInstalled = strings.skillInstalled,
        skillRemoved = strings.skillRemoved,
        skillsReloaded = strings.skillsReloaded,
        snapshotNotifier = hostRuntime::notifySkillsSnapshotChanged,
        mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
      )
      val chatGateway = ServiceOwnedChatRuntimeGateway(
        delegate = hostRuntime,
        readGateway = ProjectionOnlyOpenCrayChatRuntimeGateway(
          chatSessionStore = ChatSessionLocalStore.fromContext(appContext),
          queueSnapshotStoreFactory = FileBackedAgentQueueSnapshotStoreFactory
            .fromContext(appContext),
          runRecordStoreFactory = FileBackedAgentRunRecordStoreFactory.fromContext(appContext),
          runEventJournalStoreFactory = FileBackedRunEventJournalStoreFactory
            .fromContext(appContext),
          promptCheckpointStoreFactory = FileBackedPromptCheckpointStoreFactory
            .fromContext(appContext),
          processRegistryFactory = FileBackedAgentProcessRegistryFactory.fromContext(appContext),
          supplementStoreFactory = FileBackedAgentSessionSupplementStoreFactory
            .fromContext(appContext),
          subAgentHandleStoreFactory = FileBackedSubAgentHandleStoreFactory.fromContext(
            appContext,
          ),
          strings = projectionOnlyChatStrings(appContext),
          connectionStateProvider = { runtimeServiceConnectionState },
          personalizationLocalStore = PersonalizationLocalStore.fromContext(appContext),
          workspaceRootProvider = {
            AppAgentWorkspace.directoryForContext(appContext)
              .takeIf { directory -> directory.isDirectory }
              ?.toPath()
          },
          mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
          runtimeOwnerLifecycleProvider = { serviceHost.runtimeAccess.lifecycleDescriptor },
          runtimeOwnerWorkSummaryProvider = serviceHost.runtimeAccess.hostAccess::activeWorkSummary,
          serviceLifecycleProvider = { serviceHost.serviceLifecycle },
          serviceWorkStateProvider = serviceHost.serviceWorkStateTracker::currentState,
          serviceKeepAliveStateProvider = runtimeServiceKeepAliveStateProvider,
        ),
        snapshotNotifier = hostRuntime::notifyChatSnapshotsChanged,
        chatSessionMutationAccess = ServiceOwnedChatSessionMutationAccess(
          chatSessionStore = ChatSessionLocalStore.fromContext(appContext),
          runtimeHostAccess = serviceHost.runtimeAccess.hostAccess,
          chatUnreadMessageState = chatUnreadMessageState,
          pendingApprovalState = pendingApprovalState,
          runtimeEventState = runtimeEventState,
          terminalReplayRepairer = serviceHost.runtimeAccess.replayAccess.terminalReplayRepairer,
        ),
        chatRunControlAccess = ServiceOwnedChatRunControlAccess(
          chatSessionStore = ChatSessionLocalStore.fromContext(appContext),
          runtimeHostAccess = serviceHost.runtimeAccess.hostAccess,
          pendingApprovalState = pendingApprovalState,
          runtimeEventState = runtimeEventState,
          runCancellationReplayRecorder = serviceHost.runtimeAccess.replayAccess.runCancellationRecorder,
          subAgentReplayRecorder = serviceHost.runtimeAccess.replayAccess.subAgentReplayRecorder,
          isChineseLocale = {
            OpenCrayHostRuntime.localizedHostRuntimeStrings(
              OpenCrayLocaleManager.wrap(appContext),
            ).localeTag.trim().lowercase(java.util.Locale.US).startsWith("zh")
          },
        ),
        chatApprovalAccess = ServiceOwnedChatApprovalAccess(
          approveChatApprovalHandler = serviceHost::approvePendingApproval,
          approveChatApprovalForSessionHandler = serviceHost::approvePendingApprovalForSession,
          rejectChatApprovalHandler = serviceHost::rejectPendingApproval,
        ),
        chatSubmissionAccess = ServiceOwnedChatSubmissionAccess(
          chatSessionStore = ChatSessionLocalStore.fromContext(appContext),
          runtimeHostAccess = serviceHost.runtimeAccess.hostAccess,
          safetySettingsFacade = serviceHost.dependencies.safetySettingsFacade,
          workspaceRootProvider = serviceHost.dependencies.workspaceRootProvider,
          approvedReadRootsProvider = serviceHost.dependencies.approvedReadRootsProvider,
          voiceMetadataAnalyzer = DefaultAppAgentWorkspaceVoiceMetadataAnalyzer,
          agentThinkingText = strings.agentThinking,
        ),
        mainThreadPoster = HandlerMainThreadPoster(Handler(Looper.getMainLooper())),
      )
      lateinit var settingsGateway: ServiceOwnedSettingsGateway
      val refreshLocalizedGateways = {
        hostRuntime.refreshLocalizedResourcesForService()
        val refreshedStrings = OpenCrayHostRuntime.localizedHostRuntimeStrings(
          OpenCrayLocaleManager.wrap(appContext),
        )
        shellGateway.updateLocalizedResources(
          localeTag = refreshedStrings.localeTag,
          hostLabel = refreshedStrings.shellHostLabel,
          hostSummary = refreshedStrings.shellHostSummary,
        )
        skillsGateway.updateLocalizedResources(
          skillsFacade = LocalSkillsFacade.fromContext(appContext),
          localeTag = refreshedStrings.localeTag,
          skillInstalled = refreshedStrings.skillInstalled,
          skillRemoved = refreshedStrings.skillRemoved,
          skillsReloaded = refreshedStrings.skillsReloaded,
        )
        settingsGateway.updateLocalizedResources(
          localeTag = refreshedStrings.localeTag,
          settingsFacade = LocalSettingsFacade.fromContext(appContext),
          notificationSettingsFacade = LocalNotificationSettingsFacade.fromContext(appContext),
          networkSearchConfigFacade = LocalNetworkSearchConfigFacade.fromContext(appContext),
          mediaSpeechSettingsFacade = LocalMediaSpeechSettingsFacade.fromContext(appContext),
          personalizationFacade = LocalPersonalizationFacade.fromContext(appContext),
          safetySettingsFacade = LocalSafetySettingsFacade.fromContext(appContext),
          llmConfigFacade = LocalLlmConfigFacade.fromContext(appContext),
          mcpSettingsFacade = LocalMcpSettingsFacade.fromContext(appContext),
        )
      }
      settingsGateway = ServiceOwnedSettingsGateway(
        delegate = hostRuntime,
        localeTag = strings.localeTag,
        settingsFacade = LocalSettingsFacade.fromContext(appContext),
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
        networkSearchConfigFacade = LocalNetworkSearchConfigFacade.fromContext(appContext),
        mediaSpeechSettingsFacade = LocalMediaSpeechSettingsFacade.fromContext(appContext),
        personalizationFacade = LocalPersonalizationFacade.fromContext(appContext),
        safetySettingsFacade = LocalSafetySettingsFacade.fromContext(appContext),
        llmConfigFacade = LocalLlmConfigFacade.fromContext(appContext),
        mcpSettingsFacade = LocalMcpSettingsFacade.fromContext(appContext),
        shellSnapshotNotifier = shellGateway::emitLocalizedSnapshotChanged,
        chatSnapshotNotifier = hostRuntime::notifyChatSnapshotChanged,
        settingsOverviewNotifier = hostRuntime::notifySettingsOverviewChanged,
        skillsSnapshotNotifier = skillsGateway::emitLocalizedSnapshotChanged,
        skillsProjectionNotifier = hostRuntime::notifySkillsSnapshotChanged,
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
    synchronized(lock) {
      currentLocaleTag = localeTag
      currentHostLabel = hostLabel
      currentHostSummary = hostSummary
    }
    put("initialTab", stateStore.load().selectedTab.routeKey)
    put("localeTag", currentLocaleTag)
    put("hostLabel", currentHostLabel)
    put("hostSummary", currentHostSummary)
    put("isHostConnected", true)
    put("localRuntimeServerState", OpenCrayLocalRuntimeServerRegistry.peekState().snapshotMap())
    put("hostLifecycle", hostLifecycleDescriptor.snapshotMap())
    put("runtimeOwnerLifecycle", runtimeHostAccess.lifecycleDescriptor.snapshotMap())
    put("runtimeOwnerWorkSummary", runtimeHostAccess.activeWorkSummary().snapshotMap())
    put("runtimeServiceLifecycle", runtimeServiceLifecycle.snapshotMap())
    runtimeServiceWorkStateProvider()?.snapshotMap()?.let { workState ->
      put("runtimeServiceWorkState", workState)
    }
    runtimeServiceKeepAliveStateProvider()?.snapshotMap()?.let { keepAliveState ->
      put("runtimeServiceKeepAliveState", keepAliveState)
    }
    runtimeServiceConnectionStateProvider()?.snapshotMap()?.let { state ->
      put("runtimeServiceConnectionState", state)
    }
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
  private val delegate: OpenCrayChatRuntimeGateway,
  private val readGateway: OpenCrayChatRuntimeGateway,
  private val snapshotNotifier: () -> Unit,
  private val chatSessionMutationAccess: ServiceOwnedChatSessionMutationAccess? = null,
  private val chatRunControlAccess: ServiceOwnedChatRunControlAccess? = null,
  private val chatApprovalAccess: ServiceOwnedChatApprovalAccess? = null,
  private val chatSubmissionAccess: ServiceOwnedChatSubmissionAccess? = null,
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
) : OpenCrayRuntimeServiceChatGateway {
  private val lock = Any()
  private val chatListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private val chatRuntimeListeners = linkedSetOf<(Map<String, Any?>) -> Unit>()
  private var latestChatPayload: Map<String, Any?> = delegate.loadChatSnapshot()
  private var latestChatRuntimePayload: Map<String, Any?> = delegate.loadChatRuntimeSnapshot()

  @Suppress("unused")
  private val chatObservationDisposer = delegate.observeChat { payload ->
    emitChatPayload(payload)
  }

  @Suppress("unused")
  private val chatRuntimeObservationDisposer = delegate.observeChatRuntime { payload ->
    emitChatRuntimePayload(payload)
  }

  override fun loadChatSnapshot(): Map<String, Any?> =
    delegate.loadChatSnapshot().also { payload ->
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
    delegate.loadChatRuntimeSnapshot().also { payload ->
      synchronized(lock) {
        latestChatRuntimePayload = payload
      }
    }

  override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? =
    delegate.loadChatRunSnapshot(runId)

  override fun waitForChatRun(
    runId: String,
    timeoutMs: Long,
  ): Map<String, Any?>? = delegate.waitForChatRun(runId, timeoutMs)

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

  override fun refreshSandboxSessionInfo() = delegate.refreshSandboxSessionInfo()

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
      delegate.createChatSession()
      return
    }
    access.createChatSession()
    notifyChatSnapshotsChanged()
  }

  override fun copyChatSession(sessionId: String) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegate.copyChatSession(sessionId)
      return
    }
    access.copyChatSession(sessionId)
    notifyChatSnapshotsChanged()
  }

  override fun deleteChatSession(sessionId: String) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegate.deleteChatSession(sessionId)
      return
    }
    if (access.deleteChatSession(sessionId)) {
      notifyChatSnapshotsChanged()
    }
  }

  override fun selectChatSession(sessionId: String) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegate.selectChatSession(sessionId)
      return
    }
    access.selectChatSession(sessionId)
    notifyChatSnapshotsChanged()
  }

  override fun branchChatSessionFromMessage(
    sessionId: String,
    messageId: String,
  ) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegate.branchChatSessionFromMessage(sessionId, messageId)
      return
    }
    if (access.branchChatSessionFromMessage(sessionId, messageId)) {
      notifyChatSnapshotsChanged()
    }
  }

  override fun deleteChatMessage(
    sessionId: String,
    messageId: String,
  ) {
    val access = chatSessionMutationAccess
    if (access == null) {
      delegate.deleteChatMessage(sessionId, messageId)
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
      delegate.recallChatMessage(sessionId, messageId)
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
      return delegate.submitChatMessage(text, attachments)
    }
    val result = access.submitChatMessage(text, attachments)
    if (result.didMutate) {
      notifyChatSnapshotsChanged()
    }
    return result.payload
  }

  override fun approveChatApproval(taskIdOrRunId: String) {
    val access = chatApprovalAccess
    if (access == null) {
      delegate.approveChatApproval(taskIdOrRunId)
      return
    }
    access.approveChatApproval(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun approveChatApprovalForSession(taskIdOrRunId: String) {
    val access = chatApprovalAccess
    if (access == null) {
      delegate.approveChatApprovalForSession(taskIdOrRunId)
      return
    }
    access.approveChatApprovalForSession(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun rejectChatApproval(taskIdOrRunId: String) {
    val access = chatApprovalAccess
    if (access == null) {
      delegate.rejectChatApproval(taskIdOrRunId)
      return
    }
    access.rejectChatApproval(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun interruptChatRun(taskIdOrRunId: String) {
    val access = chatRunControlAccess
    if (access == null) {
      delegate.interruptChatRun(taskIdOrRunId)
      return
    }
    access.interruptChatRun(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun retryChatRun(taskIdOrRunId: String) {
    val access = chatRunControlAccess
    if (access == null) {
      delegate.retryChatRun(taskIdOrRunId)
      return
    }
    access.retryChatRun(taskIdOrRunId)
    notifyChatSnapshotsChanged()
  }

  override fun notifyChatSnapshotsChanged() {
    emitChatSnapshot()
    emitChatRuntimeSnapshot()
    snapshotNotifier()
  }

  internal fun emitLocalizedSnapshotChanged() {
    emitChatSnapshot()
  }

  private fun emitChatSnapshot() {
    emitChatPayload(loadChatSnapshot())
  }

  private fun emitChatRuntimeSnapshot() {
    emitChatRuntimePayload(loadChatRuntimeSnapshot())
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
      latestChatRuntimePayload = payload
      chatRuntimeListeners.toList()
    }
    if (listeners.isEmpty()) {
      return
    }
    mainThreadPoster.post {
      listeners.forEach { listener -> listener(payload) }
    }
  }
}

internal class ServiceOwnedSkillsGateway(
  private val delegate: OpenCraySkillsGateway,
  private var skillsFacade: SkillsFacade,
  private var localeTag: String,
  private var skillInstalled: (String) -> String,
  private var skillRemoved: (String) -> String,
  private var skillsReloaded: String,
  private val snapshotNotifier: () -> Unit,
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
) : OpenCraySkillsGateway by delegate {
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
  private val delegate: OpenCraySettingsGateway,
  private var localeTag: String,
  private var settingsFacade: SettingsFacade,
  private var notificationSettingsFacade: NotificationSettingsFacade,
  private val strongBackgroundSettingsAccess: StrongBackgroundSettingsAccess =
    NoOpStrongBackgroundSettingsAccess,
  private val appLanguageSettingsAccess: AppLanguageSettingsGatewayAccess =
    GatewayBackedAppLanguageSettingsGatewayAccess(delegate),
  private val sandboxSettingsAccess: SandboxSettingsGatewayAccess,
  private var networkSearchConfigFacade: NetworkSearchConfigFacade,
  private var mediaSpeechSettingsFacade: MediaSpeechSettingsFacade,
  private var personalizationFacade: PersonalizationFacade,
  private var safetySettingsFacade: SafetySettingsFacade,
  private var llmConfigFacade: LlmConfigFacade,
  private var mcpSettingsFacade: McpSettingsFacade,
  private val shellSnapshotNotifier: () -> Unit = {},
  private val chatSnapshotNotifier: () -> Unit = {},
  private val settingsOverviewNotifier: () -> Unit = {},
  private val skillsSnapshotNotifier: () -> Unit = {},
  private val skillsProjectionNotifier: () -> Unit = {},
  private val localizedResourcesRefresh: () -> Unit = {},
  private val runtimeServiceConnectionStateProvider: () -> RuntimeServiceConnectionState? = { null },
  private val mainThreadPoster: MainThreadPoster = ImmediateMainThreadPoster,
) : OpenCraySettingsGateway by delegate {
  private val lock = Any()
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
    val snapshot = appLanguageSettingsAccess.setAppLanguage(languageId)
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
  ): Map<String, Any?> = llmConfigFacade.save(
    com.opencray.app.facade.llm.SaveLlmConfigRequest(
      enabled = enabled,
      streamingEnabled = streamingEnabled,
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
    ),
  ).toGatewayMap()

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
