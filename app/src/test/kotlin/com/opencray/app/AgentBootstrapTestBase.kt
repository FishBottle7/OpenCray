package com.opencray.app

import android.app.Service
import android.content.Context
import android.content.SharedPreferences
import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import com.opencray.app.facade.mcp.EmptyMcpSettingsFacade
import com.opencray.app.facade.safety.EmptySafetySettingsFacade
import com.opencray.app.facade.skills.EmptySkillsFacade
import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskState
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.QueueTaskLifecycleState
import com.opencray.core.orchestrator.SessionLifecycleState
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueTaskSnapshot
import com.opencray.persistence.store.MemoryStore
import java.io.File
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder

abstract class AgentBootstrapTestBase {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  internal val recordingStarter = RecordingRuntimeServiceStarter()

  internal val recordingEndpoint = RecordingRuntimeServiceEndpoint()

  internal val defaultRuntimeEnvironment = OpenCrayRuntimeServiceEnvironment(
    projectionHostLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
  )

  internal val defaultRuntimeBootstrapDependencies = defaultRuntimeServiceBootstrapDependencies(
    defaultRuntimeEnvironment,
  )

  @Before
  fun setUp() {
    clearRuntimeSingletons()
    OpenCrayRuntimeServiceAccess.clearForTest()
    OpenCrayRuntimeServiceAccess.setRuntimeServiceStarterForTest(recordingStarter)
    OpenCrayRuntimeServiceAccess.setRuntimeServiceEndpointForTest(recordingEndpoint)
  }

  @After
  fun tearDown() {
    OpenCrayRuntimeServiceAccess.clearForTest()
    clearRuntimeSingletons()
  }

  internal fun RuntimeServiceBootstrapDependencies.resolveRuntimeServiceBootstrapStateForTest(
    context: Context,
    serviceLifecycleFactory: () -> RuntimeServiceLifecycleDescriptor = {
      RuntimeServiceLifecycleDescriptor()
    },
  ): RuntimeServiceBootstrapState = resolveRuntimeServiceBootstrap(
    context = context,
    serviceLifecycle = serviceLifecycleFactory(),
  ).bootstrapState

  internal fun runtimeServiceProcessDescriptorForTest(
    target: RuntimeServiceTarget = RuntimeServiceTarget.INTERACTIVE,
  ): RuntimeServiceProcessDescriptor =
    runtimeServiceProcessDescriptor(
      packageName = "org.opencray.app",
      processName = "org.opencray.app${runtimeServiceProcessSuffixForTarget(target)}",
      expectedProcessSuffix = runtimeServiceProcessSuffixForTarget(target),
    )

  internal fun guardOnlyRuntimeServiceBootstrapDependencies(
    bootstrapStateProviderCalled: () -> Unit,
  ): RuntimeServiceBootstrapDependencies = RuntimeServiceBootstrapDependencies(
    runtimeServiceBootstrapStateProvider = RuntimeServiceBootstrapStateProvider { _, _, _ ->
      bootstrapStateProviderCalled()
      error("runtime owner bootstrap should not run")
    },
    localHostGatewayProvider = { error("unused") },
    runtimeServiceGatewayBundleFactory = RuntimeServiceGatewayBundleFactory { _, _, _, _ ->
      error("unused")
    },
    runtimeServiceTransportBootstrapFactory = OpenCrayRuntimeServiceTransportBootstrapFactory {
        _,
        _,
        _,
        _,
        _,
        _,
        _,
        _,
      ->
      error("unused")
    },
    runtimeServiceExecutionCoordinatorFactory = RuntimeServiceExecutionCoordinatorFactory {
        _,
        _,
        _,
        _,
      ->
      error("unused")
    },
    runtimeServiceShellControlBundleFactory = RuntimeServiceShellControlBundleFactory {
        _,
        _,
        _,
        _,
        _,
      ->
      error("unused")
    },
    runtimeServiceWakeCommandDispatcherFactory = RuntimeServiceWakeCommandDispatcherFactory {
        _,
        _,
        _,
        _,
      ->
      error("unused")
    },
    runtimeServiceBinderEndpointFactory = RuntimeServiceBinderEndpointFactory {
        _,
        _,
        _,
        _,
      ->
      error("unused")
    },
  )

  internal fun clearRuntimeSingletons() {
    resetDefaultRuntimeServiceExecutionController()
    OpenCrayRuntimeServiceHostRegistry.clearForTest()
    InProcessOpenCrayRuntimeOwnerRegistry.clearForTest()
  }

  internal data class PendingApprovalWakeDispatcherFixture(
    val serviceHost: OpenCrayRuntimeServiceHost,
    val sessionId: String,
    val runId: String,
    val taskId: String,
    val resumedSessionIds: MutableList<String>,
    val handle: RecordingAgentSessionHandle,
  )

  internal data class ScheduledRepairWakeDispatcherFixture(
    val serviceHost: OpenCrayRuntimeServiceHost,
    val sessionId: String,
    val scheduleId: String,
    val handle: RecordingAgentSessionHandle,
  )

  internal fun pendingApprovalWakeDispatcherFixture(
    root: java.io.File,
    cancelRequestResult: Boolean = false,
    resumeRequestResult: Boolean = true,
  ): PendingApprovalWakeDispatcherFixture {
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runId = "pending-approval-run-1"
    val taskId = "pending-approval-task-1"
    val pendingMessageId = "pending-message-1"
    val queueSnapshot = SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      lifecycleState = SessionLifecycleState.RUNNING,
      updatedAtEpochMs = 1_200L,
      tasks = listOf(
        SessionQueueTaskSnapshot(
          enqueueOrder = 1L,
          task = AgentTask(
            id = taskId,
            type = AgentTaskType.PROMPT,
            input = "Need approval",
            state = AgentTaskState.SUSPENDED,
            policyDecision = PolicyDecision(
              outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
              reasonCode = "test",
            ),
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_200L,
            metadata = mapOf(
              AppAgentSessionTaskRuntimeFactory.METADATA_RUN_ID to runId,
              AppAgentSessionTaskRuntimeFactory.METADATA_PENDING_MESSAGE_ID to pendingMessageId,
            ),
          ),
          lifecycleState = QueueTaskLifecycleState.SUSPENDED,
          attempt = 1,
          lastErrorCode = "APPROVAL_REQUIRED",
          lastErrorMessage = "Approval is required before Bash can run.",
        ),
      ),
    )
    val runSnapshot = AgentRunSnapshot(
      sessionId = sessionId,
      runId = runId,
      taskId = taskId,
      acceptedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_200L,
      lifecycleState = QueueTaskLifecycleState.SUSPENDED,
      taskState = AgentTaskState.SUSPENDED,
      attempt = 1,
      executionOrdinal = 1,
      executionId = "execution-1",
      executionKind = "initial",
      executionStatus = ExecutionStatus.DENIED,
      errorCode = "APPROVAL_REQUIRED",
      errorMessage = "Approval is required before Bash can run.",
      resultMetadata = mapOf(
        "toolName" to "Bash",
        "canonicalToolName" to "bash",
      ),
      pendingMessageId = pendingMessageId,
    )
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    val handle = RecordingAgentSessionHandle(
      sessionId = sessionId,
      resumedSessionIds = runtimeManager.resumedSessionIds,
      runs = listOf(runSnapshot),
      queueSnapshot = queueSnapshot,
      cancelRequestResult = cancelRequestResult,
      resumeRequestResult = resumeRequestResult,
    )
    runtimeManager.putHandle(handle)
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    promptCheckpointStoreFactory.forChatSession(sessionId).upsert(
      PersistedPromptCheckpoint(
        sessionId = sessionId,
        runId = runId,
        taskId = taskId,
        checkpointId = "checkpoint-waiting-approval",
        checkpointKind = PromptCheckpointKind.WAITING_APPROVAL,
        createdAtEpochMs = 1_200L,
        updatedAtEpochMs = 1_200L,
        toolName = "bash",
        pendingMessageId = pendingMessageId,
      ),
    )
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      private val stores = linkedMapOf<String, SessionSupplementStore>()

      override fun forChatSession(sessionId: String): SessionSupplementStore =
        stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
    }
    val approvalRegistry = AgentTaskApprovalRegistry()
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = lifecycleDescriptor,
      hostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = lifecycleDescriptor,
        sessionRuntimeManager = runtimeManager,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        supplementStoreFactory = supplementStoreFactory,
        approvalRegistry = approvalRegistry,
      ),
      transcriptMessagesProvider = { emptyList() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )
    val serviceHost = OpenCrayRuntimeServiceHost(
      dependencies = testRuntimeDependencies(
        root = root.toPath(),
        chatStore = chatStore,
      ),
      runtimeAccess = runtimeAccess,
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-pending-approval-test",
        serviceCreatedAtEpochMs = 1_000L,
      ),
      serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
        workSummaryProvider = runtimeAccess.hostAccess::activeWorkSummary,
      ).apply { refresh() },
    )
    return PendingApprovalWakeDispatcherFixture(
      serviceHost = serviceHost,
      sessionId = sessionId,
      runId = runId,
      taskId = taskId,
      resumedSessionIds = runtimeManager.resumedSessionIds,
      handle = handle,
    )
  }

  internal fun scheduledRepairWakeDispatcherFixture(
    root: java.io.File,
    nowEpochMs: Long,
  ): ScheduledRepairWakeDispatcherFixture {
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeManager = RecordingAgentSessionRuntimeManager()
    val handle = RecordingAgentSessionHandle(
      sessionId = sessionId,
      resumedSessionIds = runtimeManager.resumedSessionIds,
    )
    runtimeManager.putHandle(handle)
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeAccess = OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = lifecycleDescriptor,
      hostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = lifecycleDescriptor,
        sessionRuntimeManager = runtimeManager,
        runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory(),
        promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory(),
        supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
          private val stores = linkedMapOf<String, SessionSupplementStore>()

          override fun forChatSession(sessionId: String): SessionSupplementStore =
            stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
        },
        approvalRegistry = AgentTaskApprovalRegistry(),
      ),
      transcriptMessagesProvider = { emptyList() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )
    val serviceHost = OpenCrayRuntimeServiceHost(
      dependencies = testRuntimeDependencies(
        root = root.toPath(),
        chatStore = chatStore,
      ),
      runtimeAccess = runtimeAccess,
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-repair-wake-test",
        serviceCreatedAtEpochMs = nowEpochMs,
      ),
      serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
        workSummaryProvider = runtimeAccess.hostAccess::activeWorkSummary,
      ).apply { refresh() },
    )
    val scheduleId = "repair-schedule-1"
    serviceHost.scheduledTaskSpecStore.upsert(
      ScheduledTaskSpec(
        scheduleId = scheduleId,
        sessionId = sessionId,
        title = "Repair wake schedule",
        enabled = true,
        trigger = ScheduledTrigger.At(atEpochMs = nowEpochMs - 500L),
        payload = ScheduledTaskPayload(prompt = "Run repaired scheduled task"),
        createdAtEpochMs = nowEpochMs - 1_000L,
        updatedAtEpochMs = nowEpochMs - 1_000L,
      ),
    )
    return ScheduledRepairWakeDispatcherFixture(
      serviceHost = serviceHost,
      sessionId = sessionId,
      scheduleId = scheduleId,
      handle = handle,
    )
  }

  internal class MinimalContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this

    override fun getPackageName(): String = "org.opencray.app"
  }

  internal class RuntimeEnvironmentContext(
    val environment: OpenCrayRuntimeServiceEnvironment,
  ) : ContextWrapper(null), OpenCrayRuntimeServiceEnvironmentOwner {
    override val openCrayRuntimeServiceEnvironment: OpenCrayRuntimeServiceEnvironment
      get() = environment

    override fun getApplicationContext(): Context = this

    override fun getPackageName(): String = "org.opencray.app"
  }

  internal class FilesDirBackedContext(
    private val resolvedFilesDir: File,
  ) : ContextWrapper(null) {
    private val sharedPreferences = linkedMapOf<String, SharedPreferences>()

    override fun getApplicationContext(): Context = this

    override fun getPackageName(): String = "org.opencray.app"

    override fun getFilesDir(): File = resolvedFilesDir

    override fun getSharedPreferences(
      name: String?,
      mode: Int,
    ): SharedPreferences = sharedPreferences.getOrPut(name.orEmpty()) {
      InMemorySharedPreferences()
    }
  }

  internal class InMemorySharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(
      key: String?,
      defValue: String?,
    ): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
      key: String?,
      defValues: MutableSet<String>?,
    ): MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(
      key: String?,
      defValue: Int,
    ): Int = values[key] as? Int ?: defValue

    override fun getLong(
      key: String?,
      defValue: Long,
    ): Long = values[key] as? Long ?: defValue

    override fun getFloat(
      key: String?,
      defValue: Float,
    ): Float = values[key] as? Float ?: defValue

    override fun getBoolean(
      key: String?,
      defValue: Boolean,
    ): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
      listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
      listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
      private val pendingValues = linkedMapOf<String, Any?>()
      private val removals = linkedSetOf<String>()
      private var clearRequested: Boolean = false

      override fun putString(
        key: String?,
        value: String?,
      ): SharedPreferences.Editor = applyChange(key, value)

      override fun putStringSet(
        key: String?,
        values: MutableSet<String>?,
      ): SharedPreferences.Editor = applyChange(key, values?.toSet())

      override fun putInt(
        key: String?,
        value: Int,
      ): SharedPreferences.Editor = applyChange(key, value)

      override fun putLong(
        key: String?,
        value: Long,
      ): SharedPreferences.Editor = applyChange(key, value)

      override fun putFloat(
        key: String?,
        value: Float,
      ): SharedPreferences.Editor = applyChange(key, value)

      override fun putBoolean(
        key: String?,
        value: Boolean,
      ): SharedPreferences.Editor = applyChange(key, value)

      override fun remove(key: String?): SharedPreferences.Editor {
        if (key != null) {
          removals += key
        }
        return this
      }

      override fun clear(): SharedPreferences.Editor {
        clearRequested = true
        pendingValues.clear()
        removals.clear()
        return this
      }

      override fun commit(): Boolean {
        if (clearRequested) {
          values.clear()
        }
        removals.forEach(values::remove)
        pendingValues.forEach { (key, value) ->
          if (value == null) {
            values.remove(key)
          } else {
            values[key] = value
          }
        }
        return true
      }

      override fun apply() {
        commit()
      }

      private fun applyChange(
        key: String?,
        value: Any?,
      ): SharedPreferences.Editor {
        if (key != null) {
          pendingValues[key] = value
          removals.remove(key)
        }
        return this
      }
    }
  }

  internal class RecordingCommandIntent : Intent() {
    private val extras: MutableMap<String, Any?> = linkedMapOf()
    private var storedAction: String? = null

    override fun setAction(action: String?): Intent {
      storedAction = action
      return this
    }

    override fun getAction(): String? = storedAction

    override fun putExtra(name: String?, value: String?): Intent {
      if (name != null) {
        extras[name] = value
      }
      return this
    }

    override fun putExtra(name: String?, value: Int): Intent {
      if (name != null) {
        extras[name] = value
      }
      return this
    }

    override fun putExtra(name: String?, value: Long): Intent {
      if (name != null) {
        extras[name] = value
      }
      return this
    }

    override fun putExtra(name: String?, value: Boolean): Intent {
      if (name != null) {
        extras[name] = value
      }
      return this
    }

    override fun getStringExtra(name: String?): String? =
      name?.let(extras::get) as? String

    override fun getIntExtra(
      name: String?,
      defaultValue: Int,
    ): Int = (name?.let(extras::get) as? Int) ?: defaultValue

    override fun getLongExtra(
      name: String?,
      defaultValue: Long,
    ): Long = (name?.let(extras::get) as? Long) ?: defaultValue
  }

  internal class RecordingRuntimeServiceStarter : RuntimeServiceStarter {
    var throwOnStart: Boolean = false
    val startAttempts = mutableListOf<RecordedStart>()
    val startedRequests = mutableListOf<RecordedStart>()

    override fun start(
      context: Context,
      intent: Intent,
      foreground: Boolean,
    ): Boolean {
      val attempt = RecordedStart(
        contextPackageName = context.packageName,
        intent = intent,
        foreground = foreground,
      )
      startAttempts += attempt
      if (throwOnStart) {
        return false
      }
      startedRequests += attempt
      return true
    }
  }

  internal data class RecordedStart(
    val contextPackageName: String,
    val intent: Intent,
    val foreground: Boolean,
  )

  internal data class RecordedChatWriteActionPendingIntent(
    val contextPackageName: String,
    val command: OpenCrayChatWriteCommand,
    val requestCode: Int,
    val target: RuntimeServiceTarget,
    val terminalNotificationTaskId: String?,
  )

  internal class RecordingRuntimeServiceEndpoint : RuntimeServiceEndpoint {
    val baseIntentSentinel: Intent = Intent()
    val scheduledTaskIntentSentinel: Intent = Intent()
    val scheduledRepairIntentSentinel: Intent = Intent()
    val resetRuntimeIntentSentinel: Intent = Intent()
    val resumeInterruptedRunsIntentSentinel: Intent = Intent()
    val scheduleNotificationActionPendingIntentSentinel: android.app.PendingIntent? = null
    var chatWriteIntentSentinel: Intent? = null
    var baseIntentCallCount: Int = 0
      private set
    val baseIntentTargets = mutableListOf<RuntimeServiceTarget>()
    val scheduledCommands = mutableListOf<ScheduledTaskWakeCommand>()
    val scheduledTaskTargets = mutableListOf<RuntimeServiceTarget>()
    val scheduledRepairReasons = mutableListOf<String>()
    val scheduledRepairTargets = mutableListOf<RuntimeServiceTarget>()
    val resetRuntimeReasons = mutableListOf<String>()
    val resetRuntimeTargets = mutableListOf<RuntimeServiceTarget>()
    val resumeInterruptedRunsReasons = mutableListOf<String>()
    val resumeInterruptedRunsTargets = mutableListOf<RuntimeServiceTarget>()
    val chatWriteActionPendingIntentRequests =
      mutableListOf<RecordedChatWriteActionPendingIntent>()

    override fun baseIntent(
      context: Context,
      target: RuntimeServiceTarget,
    ): Intent {
      baseIntentCallCount += 1
      baseIntentTargets += target
      return baseIntentSentinel
    }

    override fun scheduledTaskIntent(
      context: Context,
      command: ScheduledTaskWakeCommand,
      target: RuntimeServiceTarget,
    ): Intent {
      scheduledCommands += command
      scheduledTaskTargets += target
      return scheduledTaskIntentSentinel
    }

    override fun scheduledRepairIntent(
      context: Context,
      repairReason: String,
      target: RuntimeServiceTarget,
    ): Intent {
      scheduledRepairReasons += repairReason
      scheduledRepairTargets += target
      return scheduledRepairIntentSentinel
    }

    override fun resetRuntimeIntent(
      context: Context,
      repairReason: String,
      target: RuntimeServiceTarget,
    ): Intent {
      resetRuntimeReasons += repairReason
      resetRuntimeTargets += target
      return resetRuntimeIntentSentinel
    }

    override fun resumeInterruptedRunsIntent(
      context: Context,
      repairReason: String,
      target: RuntimeServiceTarget,
    ): Intent {
      resumeInterruptedRunsReasons += repairReason
      resumeInterruptedRunsTargets += target
      return resumeInterruptedRunsIntentSentinel
    }

    override fun chatWriteIntent(
      context: Context,
      command: OpenCrayChatWriteCommand,
      target: RuntimeServiceTarget,
    ): Intent? = chatWriteIntentSentinel

    override fun approvalActionPendingIntent(
      context: Context,
      action: String,
      sessionId: String,
      taskId: String,
      runId: String,
      requestCode: Int,
      target: RuntimeServiceTarget,
    ): android.app.PendingIntent = error("Approval pending intent should not be used in this test.")

    override fun chatWriteActionPendingIntent(
      context: Context,
      command: OpenCrayChatWriteCommand,
      requestCode: Int,
      target: RuntimeServiceTarget,
      terminalNotificationTaskId: String?,
    ): android.app.PendingIntent {
      chatWriteActionPendingIntentRequests += RecordedChatWriteActionPendingIntent(
        contextPackageName = context.packageName,
        command = command,
        requestCode = requestCode,
        target = target,
        terminalNotificationTaskId = terminalNotificationTaskId,
      )
      error("Chat write action pending intent should not be used in this test.")
    }

    override fun scheduleNotificationActionPendingIntent(
      context: Context,
      action: String,
      scheduleId: String,
      sessionId: String?,
      taskId: String?,
      runId: String?,
      requestCode: Int,
      target: RuntimeServiceTarget,
    ): android.app.PendingIntent =
      scheduleNotificationActionPendingIntentSentinel
        ?: error("Schedule notification pending intent should not be used in this test.")
  }

  internal class RecordingRuntimeServiceClientProvider(
    initialClient: OpenCrayRuntimeServiceClient,
  ) : RuntimeServiceClientProvider {
    var clientOverride: OpenCrayRuntimeServiceClient = initialClient
    val createdContexts = mutableListOf<Context>()
    val createdBootstraps = mutableListOf<RuntimeServiceClientBootstrap>()
    var createCallCount: Int = 0
      private set

    override fun create(
      context: Context,
      bootstrap: RuntimeServiceClientBootstrap,
    ): OpenCrayRuntimeServiceClient {
      createCallCount += 1
      createdContexts += context
      createdBootstraps += bootstrap
      return clientOverride
    }
  }

  internal class DisposableRuntimeServiceClient : OpenCrayRuntimeServiceClient {
    var disposeCallCount: Int = 0
      private set

    override fun loadSnapshot(): OpenCrayRuntimeServiceClientSnapshot = error("unused in test")

    override fun dispose() {
      disposeCallCount += 1
    }
  }

  internal fun notificationTargetTestTask(
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "task-notification-target",
    type = AgentTaskType.PROMPT,
    input = "hello",
    state = AgentTaskState.QUEUED,
    policyDecision = PolicyDecision(
      outcome = com.opencray.core.contracts.PolicyDecisionOutcome.ALLOW,
      reasonCode = "test",
    ),
    createdAtEpochMs = 1L,
    metadata = metadata,
  )

  internal fun testServiceHost(root: java.io.File): OpenCrayRuntimeServiceHost {
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session"))
    val runtimeAccess = testRuntimeAccess()
    return OpenCrayRuntimeServiceHost(
      dependencies = testRuntimeDependencies(
        root = root.toPath(),
        chatStore = chatStore,
      ),
      runtimeAccess = runtimeAccess,
      serviceLifecycle = RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-host-test",
        serviceCreatedAtEpochMs = 4321L,
      ),
      serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
        workSummaryProvider = runtimeAccess.hostAccess::activeWorkSummary,
      ).apply { refresh() },
    )
  }

  internal fun testRuntimeDependencies(
    root: Path,
    chatStore: ChatSessionLocalStore,
  ): OpenCrayRuntimeContextDependencies {
    val appContext = FilesDirBackedContext(
      root.resolve("android-context-files").toFile().apply {
        mkdirs()
      },
    )
    return OpenCrayRuntimeContextDependencies(
      appContext = appContext,
      localizedContext = appContext,
      llmSettingsStore = LlmSettingsStore(InMemoryLlmSettingsKeyValueStore()),
      sandboxSettingsRepository = testSandboxSettingsRepository(),
      personalizationStore = PersonalizationLocalStore(root.resolve("personalization").toFile()),
      chatSessionStore = chatStore,
      skillsFacade = EmptySkillsFacade,
      mcpSettingsFacade = EmptyMcpSettingsFacade,
      webSearchSettingsStore = WebSearchSettingsStore(InMemoryWebSearchSettingsKeyValueStore()),
      providerUserAgent = "OpenCrayAgentRuntimeServiceBootstrapTest",
      workspaceRootProvider = { root },
      workspaceRootsProvider = { setOf(root) },
      voiceMetadataCacheStore = null,
      soulProfileStore = WorkspaceSoulProfileStore(),
      liveContextModeStore = LiveContextModeStore(InMemoryLiveContextModeKeyValueStore()),
      safetySettingsFacade = EmptySafetySettingsFacade,
      mediaSpeechSettingsStore = MediaSpeechSettingsStore(InMemoryMediaSpeechSettingsKeyValueStore()),
      approvedReadRootsProvider = {
        ApprovedReadRootsSnapshot(
          roots = setOf(root),
          summary = "workspace=${root.toString().replace('\\', '/')}",
        )
      },
      workspaceSnapshotProvider = { emptyMap() },
      runtimeServiceAccessGateway = DefaultRuntimeServiceAccessGateway(
        defaultRuntimeServiceAccessDependencies(),
      ),
      chatRuntimeWriteTargetResolverFactory = ChatRuntimeWriteTargetResolverFactory {
        object : ChatRuntimeWriteTargetResolver {
          override fun targetFor(command: OpenCrayChatWriteCommand): RuntimeServiceTarget =
            RuntimeServiceTarget.INTERACTIVE
        }
      },
    )
  }

  internal fun testRuntimeAccess(
    lifecycleDescriptor: HostRuntimeLifecycleDescriptor = HostRuntimeLifecycleDescriptor(),
    sessionRuntimeManager: AgentSessionRuntimeManager = NoOpAgentSessionRuntimeManager(),
  ): OpenCrayRuntimeOwnerAccess {
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      private val stores = linkedMapOf<String, SessionSupplementStore>()

      override fun forChatSession(sessionId: String): SessionSupplementStore =
        stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
    }
    val approvalRegistry = AgentTaskApprovalRegistry()
    return OpenCrayRuntimeOwnerAccess(
      lifecycleDescriptor = lifecycleDescriptor,
      hostAccess = DefaultOpenCrayRuntimeHostAccess(
        lifecycleDescriptor = lifecycleDescriptor,
        sessionRuntimeManager = sessionRuntimeManager,
        runEventJournalStoreFactory = runEventJournalStoreFactory,
        promptCheckpointStoreFactory = promptCheckpointStoreFactory,
        supplementStoreFactory = supplementStoreFactory,
        approvalRegistry = approvalRegistry,
      ),
      transcriptMessagesProvider = { emptyList() },
      memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
        memoryStore = InMemoryMemoryStore(),
      ),
      replayAccess = OpenCrayRuntimeReplayAccess(
        approvalRejectionRecorder = { _, _, _, _, _, _ -> },
        approvalApprovedRecorder = { _, _, _, _, _, _ -> },
        subAgentReplayRecorder = { _, _ -> },
        runCancellationRecorder = { _, _, _, _, _ -> },
        terminalReplayRepairer = { _, _ -> },
      ),
    )
  }

  internal fun testRuntimeExecutionController(
    serviceHost: OpenCrayRuntimeServiceHost,
  ): RuntimeServiceExecutionController = RuntimeServiceExecutionController(
    runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(),
    bootstrapAssembly = serviceHost.toRuntimeServiceBootstrapAssembly(),
  )

  internal fun recordingRuntimeServiceExecutionControllerHandle(
    root: Path,
  ): RecordingRuntimeServiceExecutionControllerHandle {
    val chatStore = ChatSessionLocalStore(root.resolve("chat-session").toFile())
    val dependencies = testRuntimeDependencies(root = root, chatStore = chatStore)
    val runtimeAccess = testRuntimeAccess()
    val disposeCallCount = intArrayOf(0)
    return RecordingRuntimeServiceExecutionControllerHandle(
      controller = RuntimeServiceExecutionController(
        runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(),
        bootstrapAssembly = RuntimeServiceBootstrapAssembly(
          bootstrapContext = runtimeServiceBootstrapContext(dependencies),
          retainedOwnerState = retainedOwnerStateFor(runtimeAccess),
          projectionCoordinator = RecordingRuntimeServiceProjectionCoordinator(),
          transportCoordinator = DefaultRuntimeServiceTransportCoordinator(),
          retainedShellControl = testRuntimeServiceRetainedShellControl(),
          runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(),
          bootstrapResult = RuntimeServiceBootstrapResult(
            scannedSessionIds = emptyList(),
            resumedSessionIds = emptyList(),
            repairedSessionIds = emptyList(),
          ),
          serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
            workSummaryProvider = { RuntimeOwnerWorkSummary() },
          ),
          scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
          scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
          scheduledTaskTriggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory()
            .create(),
          scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
          disposeHandler = {
            disposeCallCount[0] += 1
          },
        ),
      ),
      disposeCallCountProvider = {
        disposeCallCount[0]
      },
    )
  }

  internal fun retainedOwnerStateFor(
    runtimeAccess: OpenCrayRuntimeOwnerAccess,
    disposeHandler: () -> Unit = {},
  ): RuntimeServiceRetainedOwnerState = RuntimeServiceRetainedOwnerState(
    initialBootstrap = RuntimeOwnerBootstrap(
      runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
      ownerObservationAccess = runtimeAccess.hostAccess,
      notificationHostAccess = runtimeAccess.hostAccess,
      approvalDecisionHostAccess = runtimeAccess.hostAccess,
      chatMutationAccess = runtimeAccess.hostAccess,
      chatSubmissionHostAccess = runtimeAccess.hostAccess,
      runtimeReplayAccess = runtimeAccess.replayAccess,
      disposeHandler = disposeHandler,
    ),
    replacementBootstrapProvider = { _ ->
      error("Owner replacement is not configured for this test.")
    },
  )

  internal fun runtimeOwnerBootstrapFor(
    runtimeAccess: OpenCrayRuntimeOwnerAccess,
    retainedHandle: RetainedRuntimeOwnerHandle? = null,
    disposeHandler: () -> Unit = {},
  ): RuntimeOwnerBootstrap = RuntimeOwnerBootstrap(
    runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
    ownerObservationAccess = runtimeAccess.hostAccess,
    notificationHostAccess = runtimeAccess.hostAccess,
    approvalDecisionHostAccess = runtimeAccess.hostAccess,
    chatMutationAccess = runtimeAccess.hostAccess,
    chatSubmissionHostAccess = runtimeAccess.hostAccess,
    runtimeReplayAccess = runtimeAccess.replayAccess,
    retainedHandle = retainedHandle,
    disposeHandler = disposeHandler,
  )

  internal fun testRuntimeServiceBootstrapFactory(): RuntimeServiceBootstrapFactory =
    RuntimeServiceBootstrapFactory { _ ->
      RuntimeServiceBootstrapParts(
        scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
        scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
        scheduledTaskTriggerSyncStateStore = inMemoryScheduledTaskTriggerSyncStateStoreFactory()
          .create(),
        scheduledTriggerRegistrar = NoOpScheduledTriggerRegistrar,
      )
    }

  internal class RecordingScheduledWorkScheduler : ScheduledWorkScheduler {
    val wakeRequests = mutableListOf<Pair<String, Long>>()
    val repairRequests = mutableListOf<Pair<String, Long>>()

    override fun scheduleWake(
      scheduleId: String,
      triggerAtEpochMs: Long,
    ) {
      wakeRequests += scheduleId to triggerAtEpochMs
    }

    override fun cancel(scheduleId: String) = Unit

    override fun enqueueRepair(
      reason: String,
      initialDelayMs: Long,
    ) {
      repairRequests += reason to initialDelayMs
    }

    override fun ensurePeriodicRepair() = Unit
  }

  internal class RecordingRuntimeServiceExecutionControllerHandle(
    val controller: RuntimeServiceExecutionController,
    private val disposeCallCountProvider: () -> Int,
  ) {
    val disposeCallCount: Int
      get() = disposeCallCountProvider()
  }

  internal fun testRuntimeServiceRetainedShellControl(): RuntimeServiceRetainedShellControl =
    RuntimeServiceRetainedShellControl(
      keepAliveController = RuntimeServiceKeepAliveController(
        appVisibleProvider = { true },
        scheduler = object : RuntimeServiceDelayScheduler {
          override fun schedule(
            delayMs: Long,
            action: () -> Unit,
          ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
        },
        stopRequester = { false },
      ),
      runtimeForegroundController = RuntimeForegroundController(
        serviceAdapter = object : RuntimeForegroundServiceAdapter {
          override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

          override fun stopForeground(removeNotification: Boolean) = Unit
        },
        appVisibleProvider = { true },
      ),
    )

  internal fun testServiceGatewayBundle(
    notifyChatSnapshotsChanged: () -> Unit = {},
    refreshSandboxSessionInfo: () -> Unit = {},
    interruptChatRun: (String) -> Unit = {},
    retryChatRun: (String) -> Unit = {},
    refreshSkills: () -> String = { "" },
    saveNotificationSettings: (Map<String, Any?>) -> Map<String, Any?> = { emptyMap() },
    dispose: () -> Unit = {},
  ): OpenCrayRuntimeServiceGatewayBundle =
    OpenCrayRuntimeServiceGatewayBundle(
      shellGateway = object : OpenCrayShellGateway {
        override fun loadShellSnapshot(): Map<String, Any?> = emptyMap()

        override fun observeShell(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

        override fun saveShellDestination(
          selectedTab: String,
          settingsSubpage: String?,
        ) = Unit
      },
      chatRuntimeGateway = object : OpenCrayRuntimeServiceChatGateway {
        override fun loadChatSnapshot(): Map<String, Any?> = emptyMap()

        override fun observeChat(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

        override fun loadChatRuntimeSnapshot(): Map<String, Any?> = emptyMap()

        override fun observeLiveAssistantDraftEvents(listener: (Map<String, Any?>) -> Unit): () -> Unit =
          { }

        override fun loadChatRunSnapshot(runId: String): Map<String, Any?>? = null

        override fun waitForChatRun(runId: String, timeoutMs: Long): Map<String, Any?>? = null

        override fun observeChatRuntime(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

        override fun refreshSandboxSessionInfo() = refreshSandboxSessionInfo()

        override fun loadMemoryDebugSnapshot(): Map<String, Any?> = emptyMap()

        override fun loadMemoryDebugLinksSnapshot(): Map<String, Any?> = emptyMap()

        override fun loadSoulDebugSnapshot(): Map<String, Any?> = emptyMap()

        override fun searchMemoryDebug(
          query: String,
          maxResults: Int,
          minScore: Int,
        ): Map<String, Any?> = emptyMap()

        override fun getMemoryDebugSlice(
          path: String,
          fromLine: Int?,
          lines: Int,
        ): Map<String, Any?> = emptyMap()

        override fun applyMemoryDebugAction(
          recordId: String,
          actionId: String,
        ): Map<String, Any?> = emptyMap()

        override fun createChatSession() = Unit

        override fun copyChatSession(sessionId: String) = Unit

        override fun deleteChatSession(sessionId: String) = Unit

        override fun selectChatSession(sessionId: String) = Unit

        override fun branchChatSessionFromMessage(
          sessionId: String,
          messageId: String,
        ) = Unit

        override fun deleteChatMessage(
          sessionId: String,
          messageId: String,
        ) = Unit

        override fun recallChatMessage(
          sessionId: String,
          messageId: String,
        ) = Unit

        override fun submitChatMessage(
          text: String,
          attachments: List<com.opencray.runtime.OpenCrayFinalAttachment>,
        ): Map<String, Any?>? = null

        override fun approveChatApproval(taskIdOrRunId: String) = Unit

        override fun approveChatApprovalForSession(taskIdOrRunId: String) = Unit

        override fun rejectChatApproval(taskIdOrRunId: String) = Unit

        override fun interruptChatRun(taskIdOrRunId: String) = interruptChatRun(taskIdOrRunId)

        override fun retryChatRun(taskIdOrRunId: String) = retryChatRun(taskIdOrRunId)

        override fun notifyChatSnapshotsChanged() = notifyChatSnapshotsChanged()
      },
      skillsGateway = object : OpenCraySkillsGateway {
        override fun loadSkillsSnapshot(
          query: String,
          suggestedLimit: Int,
        ): Map<String, Any?> = emptyMap()

        override fun observeSkills(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

        override fun setSkillEnabled(skillId: String, enabled: Boolean) = Unit

        override fun installSuggestedSkill(skillId: String): String = ""

        override fun installSkillSource(
          sourceRef: String,
          selectedSkillName: String,
        ): String = ""

        override fun installSkillSourceBatch(
          sourceRef: String,
          selectedSkillNames: List<String>,
        ): String = ""

        override fun inspectSkillSource(sourceRef: String): Map<String, Any?> = emptyMap()

        override fun deleteInstalledSkill(skillId: String): String = ""

        override fun refreshSkills(): String = refreshSkills()

        override fun checkInstalledSkillUpdates(skillId: String): String = ""

        override fun updateInstalledSkill(skillId: String): String = ""

        override fun loadSkillInstructions(skillId: String): Map<String, Any?> = emptyMap()

        override fun loadSuggestedSkillInstructions(
          sourceRef: String,
          selectedSkillName: String,
        ): Map<String, Any?> = emptyMap()

        override fun activateSkillsInstallSource(sourceId: String): String = ""
      },
      settingsGateway = object : OpenCraySettingsGateway {
        override fun loadSettingsOverview(): Map<String, Any?> = emptyMap()

        override fun observeSettingsOverview(listener: (Map<String, Any?>) -> Unit): () -> Unit = { }

        override fun loadSettingsDetail(routeIdRaw: String): Map<String, Any?> = emptyMap()

        override fun loadNotificationSettings(): Map<String, Any?> = emptyMap()

        override fun saveNotificationSettings(payload: Map<String, Any?>): Map<String, Any?> =
          saveNotificationSettings(payload)

        override fun loadStrongBackgroundSnapshot(): Map<String, Any?> = emptyMap()

        override fun performStrongBackgroundAction(actionId: String): Map<String, Any?> =
          emptyMap()

        override fun loadNetworkSearchConfig(): Map<String, Any?> = emptyMap()

        override fun saveNetworkSearchConfig(slots: List<Map<String, Any?>>): Map<String, Any?> =
          emptyMap()

        override fun loadMediaSpeechConfig(): Map<String, Any?> = emptyMap()

        override fun saveMediaSpeechConfig(payload: Map<String, Any?>): Map<String, Any?> =
          emptyMap()

        override fun loadSandboxSettings(): Map<String, Any?> = emptyMap()

        override fun saveSandboxSettings(payload: Map<String, Any?>): Map<String, Any?> =
          emptyMap()

        override fun loadLlmConfig(): Map<String, Any?> = emptyMap()

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
        ): Map<String, Any?> = emptyMap()

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
        ): Map<String, Any?> = emptyMap()

        override fun validateLlmConfig(
          providerId: String,
          protocol: String,
          baseUrl: String,
          apiKey: String,
          model: String,
          reasoningEffort: String,
          contextWindowTokensOverride: Int?,
        ): Map<String, Any?> = emptyMap()

        override fun downloadOnDeviceLlmModel(modelId: String): Map<String, Any?> = emptyMap()

        override fun cancelOnDeviceLlmModelDownload(modelId: String): Map<String, Any?> =
          emptyMap()

        override fun deleteOnDeviceLlmModel(modelId: String): Map<String, Any?> = emptyMap()

        override fun loadPersonalizationConfig(): Map<String, Any?> = emptyMap()

        override fun savePersonalizationConfig(
          presetId: String,
          customLabel: String,
          customGuidance: String,
        ): Map<String, Any?> = emptyMap()

        override fun setAppLanguage(languageId: String): Map<String, Any?> = emptyMap()

        override fun runPersonalizationReset(scopeId: String): Map<String, Any?> = emptyMap()

        override fun loadMcpSettings(): Map<String, Any?> = emptyMap()

        override fun setMcpMasterEnabled(enabled: Boolean): Map<String, Any?> = emptyMap()

        override fun setMcpServerEnabled(
          serverId: String,
          enabled: Boolean,
        ): Map<String, Any?> = emptyMap()

        override fun loadSafetySettings(): Map<String, Any?> = emptyMap()

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
        ): Map<String, Any?> = emptyMap()
      },
      disposeHandler = dispose,
    )

  internal class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, com.opencray.persistence.model.MemoryRecord>()

    override fun list(): List<com.opencray.persistence.model.MemoryRecord> = records.values.toList()

    override fun upsert(record: com.opencray.persistence.model.MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadRecords = records.isNotEmpty()
      records.clear()
      return hadRecords
    }
  }

  internal class NoOpAgentSessionRuntimeManager : AgentSessionRuntimeManager {
    var releaseAllSessionsCallCount: Int = 0
      private set

    override fun forSession(sessionId: String): AgentSessionHandle = error("unused in test")

    override fun existingSession(sessionId: String): AgentSessionHandle? = null

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun release(sessionId: String) = Unit

    override fun releaseAllSessions() {
      releaseAllSessionsCallCount += 1
    }

    override fun releaseIdleSessions() = Unit
  }

  internal class RecordingAgentSessionRuntimeManager : AgentSessionRuntimeManager {
    private val handlesBySession = linkedMapOf<String, RecordingAgentSessionHandle>()
    private val listeners = linkedSetOf<AgentSessionRuntimeListener>()
    val observerCount: Int
      get() = listeners.size
    val resumedSessionIds = mutableListOf<String>()

    fun putHandle(handle: RecordingAgentSessionHandle) {
      handlesBySession[handle.sessionId] = handle
    }

    override fun forSession(sessionId: String): AgentSessionHandle =
      handlesBySession.getOrPut(sessionId) {
        RecordingAgentSessionHandle(
          sessionId = sessionId,
          resumedSessionIds = resumedSessionIds,
        )
      }

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit {
      listeners += listener
      return {
        listeners -= listener
      }
    }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  internal class RecordingAgentSessionHandle(
    override val sessionId: String,
    private val resumedSessionIds: MutableList<String>,
    private val runs: List<AgentRunSnapshot> = emptyList(),
    private val queueSnapshot: SessionQueueSnapshot? = null,
    private val hasPendingWorkResult: Boolean = false,
    private val cancelRequestResult: Boolean = false,
    private val resumeRequestResult: Boolean = false,
  ) : AgentSessionHandle {
    val submittedTasks = mutableListOf<AgentTask>()
    val cancelledTaskIds = mutableListOf<String>()
    val resumedTaskIds = mutableListOf<String>()
    var ensureProcessingCallCount: Int = 0
      private set

    override fun submitPrompt(
      userText: String,
      pendingMessageId: String,
      visibleThroughMessageId: String,
      policyDecision: PolicyDecision,
      metadata: Map<String, String>,
    ): AgentRunSubmission = error("unused in test")

    override fun submitTask(task: AgentTask): AgentRunSubmission {
      submittedTasks += task
      return AgentRunSubmission(
        sessionId = sessionId,
        runId = "submitted-run-${submittedTasks.size}",
        taskId = task.id,
        acceptedAtEpochMs = task.createdAtEpochMs,
      )
    }

    override fun ensureProcessing() {
      ensureProcessingCallCount += 1
    }

    override fun requestCancel(taskId: String): Boolean {
      cancelledTaskIds += taskId
      return cancelRequestResult
    }

    override fun requestRetry(taskId: String): Boolean = false

    override fun requestResumeTask(taskId: String): Boolean {
      resumedTaskIds += taskId
      return resumeRequestResult
    }

    override fun listRuns(): List<AgentRunSnapshot> = runs

    override fun findRun(runId: String): AgentRunSnapshot? =
      runs.firstOrNull { run -> run.runId == runId }

    override fun waitForRun(runId: String, timeoutMs: Long): AgentRunSnapshot? = findRun(runId)

    override fun requestCancelForPendingMessageIds(pendingMessageIds: Set<String>): Int = 0

    override fun resume(): SessionLifecycleState {
      resumedSessionIds += sessionId
      return SessionLifecycleState.IDLE
    }

    override fun snapshot(): SessionQueueSnapshot = queueSnapshot ?: SessionQueueSnapshot(
      sessionId = sessionId,
      agentId = "test-agent",
      updatedAtEpochMs = 1_000L,
      tasks = emptyList(),
    )

    override fun hasPendingWork(): Boolean = hasPendingWorkResult
  }

  internal fun assertSameGatewayRuntimeAccess(
    expected: OpenCrayRuntimeHostAccess,
    actual: RuntimeServiceGatewayBundleDependencies,
  ) {
    assertSame(expected, actual.runtimeServicePort.ownerObservationAccess)
    assertSame(expected, actual.runtimeServicePort.chatMutationAccess)
    assertSame(expected, actual.runtimeServicePort.chatSubmissionHostAccess)
  }

  internal fun defaultShellControlBundle(): RuntimeServiceShellControlBundle =
    RuntimeServiceShellControlBundle(
      keepAliveController = RuntimeServiceKeepAliveController(
        appVisibleProvider = { true },
        scheduler = object : RuntimeServiceDelayScheduler {
          override fun schedule(
            delayMs: Long,
            action: () -> Unit,
          ): RuntimeServiceDelayedTask = RuntimeServiceDelayedTask { }
        },
        stopRequester = { false },
      ),
      runtimeForegroundController = RuntimeForegroundController(
        serviceAdapter = object : RuntimeForegroundServiceAdapter {
          override fun startOrUpdateForeground(model: RuntimeForegroundNotificationModel) = Unit

          override fun stopForeground(removeNotification: Boolean) = Unit
        },
        appVisibleProvider = { true },
      ),
    )

  internal class RecordingRuntimeServiceExecutionCoordinator : RuntimeServiceExecutionCoordinator {
    var attachCallCount: Int = 0
      private set
    val startIds = mutableListOf<Int>()
    var persistCallCount: Int = 0
      private set
    val scheduledDispatchOutcomes = mutableListOf<ScheduledTaskDispatchOutcome>()
    var disposeCallCount: Int = 0
      private set

    override fun attach() {
      attachCallCount += 1
    }

    override fun onStartCommand(startId: Int) {
      startIds += startId
    }

    override fun currentKeepAliveState(): RuntimeServiceKeepAliveState = RuntimeServiceKeepAliveState()

    override fun currentForegroundState(): RuntimeForegroundState = RuntimeForegroundState()

    override fun persistProjectionSnapshot(
      workState: RuntimeServiceWorkState?,
      keepAliveState: RuntimeServiceKeepAliveState?,
    ) {
      persistCallCount += 1
    }

    override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) {
      scheduledDispatchOutcomes += outcome
    }

    override fun dispose() {
      disposeCallCount += 1
    }
  }

  internal class FixedStateRuntimeServiceExecutionCoordinator(
    private val keepAliveState: RuntimeServiceKeepAliveState = RuntimeServiceKeepAliveState(),
    private val foregroundState: RuntimeForegroundState = RuntimeForegroundState(),
  ) : RuntimeServiceExecutionCoordinator {
    override fun attach() = Unit

    override fun onStartCommand(startId: Int) = Unit

    override fun currentKeepAliveState(): RuntimeServiceKeepAliveState = keepAliveState

    override fun currentForegroundState(): RuntimeForegroundState = foregroundState

    override fun persistProjectionSnapshot(
      workState: RuntimeServiceWorkState?,
      keepAliveState: RuntimeServiceKeepAliveState?,
    ) = Unit

    override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) = Unit

    override fun dispose() = Unit
  }

  internal class RecordingRuntimeServiceShellStateAccess(
    private val keepAliveState: RuntimeServiceKeepAliveState = RuntimeServiceKeepAliveState(),
    private val foregroundState: RuntimeForegroundState = RuntimeForegroundState(),
  ) : RuntimeServiceShellStateAccess {
    override fun currentKeepAliveState(): RuntimeServiceKeepAliveState = keepAliveState

    override fun currentForegroundState(): RuntimeForegroundState = foregroundState
  }

  internal class NoOpLocalHostGateway : OpenCrayLocalHostGateway {
    override fun loadFilesSnapshot(): Map<String, Any?> = emptyMap()

    override fun loadWorkspaceImagePreview(relativePath: String): Map<String, Any?> = emptyMap()

    override fun loadWorkspaceTextPreview(relativePath: String): Map<String, Any?> = emptyMap()

    override fun loadWorkspaceVoicePlaybackSource(relativePath: String): Map<String, Any?> = emptyMap()

    override fun loadWorkspaceTextDocument(relativePath: String): Map<String, Any?> = emptyMap()

    override fun openWorkspaceEntry(relativePath: String) = Unit

    override fun openExternalUri(uri: String) = Unit

    override fun copyRichTextToClipboard(plainText: String, htmlText: String?) = Unit

    override fun createWorkspaceFolder(parentRelativePath: String, name: String): Map<String, Any?> =
      emptyMap()

    override fun createWorkspaceTextFile(parentRelativePath: String, name: String): Map<String, Any?> =
      emptyMap()

    override fun renameWorkspaceEntry(targetRelativePath: String, newName: String): Map<String, Any?> =
      emptyMap()

    override fun deleteWorkspaceEntries(relativePaths: List<String>): Map<String, Any?> = emptyMap()

    override fun saveWorkspaceTextDocument(
      targetRelativePath: String,
      content: String,
    ): Map<String, Any?> = emptyMap()

    override fun pasteWorkspaceEntries(
      sourceRelativePaths: List<String>,
      destinationRelativePath: String,
      move: Boolean,
    ): Map<String, Any?> = emptyMap()

    override fun shareWorkspaceEntries(relativePaths: List<String>) = Unit

    override fun saveWorkspaceMediaAttachment(relativePath: String, kind: String): Map<String, Any?> =
      emptyMap()

    override fun showNativeToast(message: String) = Unit

    override fun importDraftChatAttachments(
      requestedKind: String,
      uriStrings: List<String>,
    ): List<Map<String, Any?>> = emptyList()

    override fun probeTwinImportSource(filePath: String): Map<String, Any?> = emptyMap()
  }

  internal class RecordingRuntimeServiceProjectionCoordinator(
    private val ownerLeaseAcquired: Boolean = true,
    private val ownerLeaseAcquireResults: List<Boolean> = emptyList(),
  ) : RuntimeServiceProjectionCoordinator {
    var bindCallCount: Int = 0
      private set
    var startCallCount: Int = 0
      private set
    var persistCallCount: Int = 0
      private set
    var ownerLeaseAcquireCallCount: Int = 0
      private set
    val scheduledDispatchOutcomes = mutableListOf<ScheduledTaskDispatchOutcome>()
    val interruptedRunRepairResults = mutableListOf<RuntimeServiceInterruptedRunRepairResult>()

    override fun bindServiceLifecycle(serviceLifecycle: RuntimeServiceLifecycleDescriptor) {
      bindCallCount += 1
    }

    override fun start() {
      startCallCount += 1
    }

    override fun persistProjectionSnapshot(
      workState: RuntimeServiceWorkState?,
      keepAliveState: RuntimeServiceKeepAliveState?,
    ) {
      persistCallCount += 1
    }

    override fun onScheduledDispatchOutcome(outcome: ScheduledTaskDispatchOutcome) {
      scheduledDispatchOutcomes += outcome
    }

    override fun onInterruptedRunRepairResult(result: RuntimeServiceInterruptedRunRepairResult) {
      interruptedRunRepairResults += result
    }

    override fun tryAcquireOwnerLease(): Boolean {
      ownerLeaseAcquireCallCount += 1
      return ownerLeaseAcquireResults.getOrNull(ownerLeaseAcquireCallCount - 1)
        ?: ownerLeaseAcquired
    }
  }

  internal class RecordingRuntimeServiceWakeCommandDispatcher : RuntimeServiceWakeCommandDispatcher {
    var dispatchCallCount: Int = 0
      private set

    override fun dispatch(intent: Intent?) {
      dispatchCallCount += 1
    }
  }

  internal class RecordingRuntimeServiceBinderEndpoint(
    private val dispatchChatWriteCommandHandler: ((OpenCrayChatWriteCommand) -> OpenCrayChatWriteDispatchResult)? = null,
  ) : Binder(), RuntimeServiceBinderEndpoint {
    val dispatchedChatWriteCommands = mutableListOf<OpenCrayChatWriteCommand>()

    override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot = error("unused in test")

    override fun dispatchChatWriteCommand(
      command: OpenCrayChatWriteCommand,
    ): OpenCrayChatWriteDispatchResult? {
      dispatchedChatWriteCommands += command
      return dispatchChatWriteCommandHandler?.invoke(command)
    }
  }

  internal class TestDelegatingRuntimeServiceBinderEndpoint(
    private val endpointProvider: () -> RuntimeServiceBinderEndpoint,
  ) : Binder(), RuntimeServiceBinderEndpoint {
    override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
      endpointProvider().loadSnapshot()

    override fun dispatchChatWriteCommand(
      command: OpenCrayChatWriteCommand,
    ): OpenCrayChatWriteDispatchResult? =
      endpointProvider().dispatchChatWriteCommand(command)
  }

  internal class TestRuntimeService : Service() {
    override fun onBind(intent: Intent?) = null
  }
}
