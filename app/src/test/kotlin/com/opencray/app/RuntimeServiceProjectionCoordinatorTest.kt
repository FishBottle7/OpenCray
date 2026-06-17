package com.opencray.app

import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeServiceProjectionCoordinatorTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun projectionCoordinatorHeartbeatsAndReleasesRuntimeOwnerLease() {
    var now = 10_000L
    val projectionStore = inMemoryRuntimeServiceProjectionStore()
    val ownerLeaseStore = inMemoryRuntimeServiceOwnerLeaseStore()
    val heartbeatScheduler = RecordingRuntimeServiceDelayScheduler()
    val runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor(
      processStartId = "process-owner",
      processStartedAtEpochMs = 9_000L,
      runtimeOwnerId = "runtime-owner-a",
      runtimeControllerId = "runtime-controller-a",
      durableRuntimeControllerId = "runtime-controller-durable",
    )
    val ownerAccess = RecordingRuntimeNotificationHostAccess(runtimeOwnerLifecycle)
    val coordinator = DefaultRuntimeServiceProjectionCoordinator(
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(
        processStartId = "process-controller",
        processStartedAtEpochMs = 8_000L,
        controllerInstanceId = "runtime-controller-a",
        durableControllerId = "runtime-controller-durable",
        controllerCreatedAtEpochMs = 8_500L,
      ),
      clock = { now },
      ownerLeaseDurationMs = 100L,
      ownerLeaseHeartbeatIntervalMs = 25L,
      runtimeOwnerLifecycle = runtimeOwnerLifecycle,
      ownerObservationAccess = ownerAccess,
      notificationHostAccess = ownerAccess,
      serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
        workSummaryProvider = ownerAccess::activeWorkSummary,
        clock = { now },
      ),
      appContext = ContextWrapper(null),
      localizedContext = ContextWrapper(null),
      chatSessionStore = ChatSessionLocalStore(
        temporaryFolder.newFolder("chat-session-store"),
      ),
      scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
      scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
      runtimeServiceAccessGateway = NoOpRuntimeServiceAccessGateway,
      projectionStore = projectionStore,
      ownerLeaseStore = ownerLeaseStore,
      ownerLeaseHeartbeatScheduler = heartbeatScheduler,
      runtimeNotificationCoordinator = null,
    )
    val serviceLifecycle = RuntimeServiceLifecycleDescriptor(
      serviceInstanceId = "runtime-service-a",
      serviceCreatedAtEpochMs = 9_500L,
      serviceProcess = runtimeServiceProcessDescriptor(
        packageName = "org.opencray.app",
        processName = "org.opencray.app:runtime",
      ),
    )

    coordinator.bindServiceLifecycle(serviceLifecycle)
    coordinator.start()

    val firstLease = checkNotNull(
      ownerLeaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND),
    )
    assertEquals(RuntimeServiceOwnerLease.PHASE_HELD, firstLease.phase)
    assertEquals("runtime-owner-a", firstLease.runtimeOwnerId)
    assertEquals("runtime-controller-durable", firstLease.durableControllerId)
    assertEquals("runtime-service-a", firstLease.serviceInstanceId)
    assertEquals(10_000L, firstLease.heartbeatAtEpochMs)
    assertEquals(10_100L, firstLease.expiresAtEpochMs)
    assertEquals(firstLease, projectionStore.loadSnapshot()?.runtimeServiceOwnerLease)
    assertEquals(1, heartbeatScheduler.tasks.size)

    now = 10_050L
    heartbeatScheduler.runNext()

    val refreshedLease = checkNotNull(
      ownerLeaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND),
    )
    assertEquals(RuntimeServiceOwnerLease.PHASE_HELD, refreshedLease.phase)
    assertEquals(10_050L, refreshedLease.heartbeatAtEpochMs)
    assertEquals(10_150L, refreshedLease.expiresAtEpochMs)
    assertEquals(refreshedLease, projectionStore.loadSnapshot()?.runtimeServiceOwnerLease)
    assertEquals(2, heartbeatScheduler.tasks.size)

    val competingLease = refreshedLease.copy(
      processStartId = "process-competing-owner",
      controllerInstanceId = "runtime-controller-competing",
      runtimeOwnerId = "runtime-owner-competing",
      runtimeControllerId = "runtime-controller-competing",
      serviceInstanceId = "runtime-service-competing",
      acquiredAtEpochMs = 10_055L,
      heartbeatAtEpochMs = 10_055L,
      expiresAtEpochMs = 10_155L,
    )
    val blockedLease = ownerLeaseStore.save(competingLease)
    assertEquals(refreshedLease, blockedLease.copy(lastAcquireFailure = null))
    assertEquals("runtime-owner-competing", blockedLease.lastAcquireFailure?.attemptedRuntimeOwnerId)

    now = 10_060L
    heartbeatScheduler.runNext()

    val refreshedLeaseWithFailure = checkNotNull(
      projectionStore.loadSnapshot()?.runtimeServiceOwnerLease,
    )
    assertEquals(10_060L, refreshedLeaseWithFailure.heartbeatAtEpochMs)
    assertEquals("runtime-owner-a", refreshedLeaseWithFailure.runtimeOwnerId)
    assertEquals(
      "runtime-owner-competing",
      refreshedLeaseWithFailure.lastAcquireFailure?.attemptedRuntimeOwnerId,
    )

    now = 10_075L
    val replacementOwnerLifecycle = runtimeOwnerLifecycle.copy(
      runtimeOwnerId = "runtime-owner-b",
      runtimeControllerId = "runtime-controller-b",
    )
    val replacementOwnerAccess = RecordingRuntimeNotificationHostAccess(replacementOwnerLifecycle)

    coordinator.replaceRuntimeOwner(
      runtimeOwnerLifecycle = replacementOwnerLifecycle,
      ownerObservationAccess = replacementOwnerAccess,
      notificationHostAccess = replacementOwnerAccess,
    )

    val replacementLease = checkNotNull(
      ownerLeaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND),
    )
    assertEquals(RuntimeServiceOwnerLease.PHASE_HELD, replacementLease.phase)
    assertEquals("runtime-owner-b", replacementLease.runtimeOwnerId)
    assertEquals(10_075L, replacementLease.acquiredAtEpochMs)
    assertEquals(10_075L, replacementLease.heartbeatAtEpochMs)
    assertEquals(10_175L, replacementLease.expiresAtEpochMs)
    assertEquals(replacementLease, projectionStore.loadSnapshot()?.runtimeServiceOwnerLease)

    now = 10_090L
    coordinator.dispose()

    val releasedLease = checkNotNull(
      ownerLeaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND),
    )
    assertEquals(RuntimeServiceOwnerLease.PHASE_RELEASED, releasedLease.phase)
    assertEquals("runtime-owner-b", releasedLease.runtimeOwnerId)
    assertEquals(10_090L, releasedLease.heartbeatAtEpochMs)
    assertEquals(10_090L, releasedLease.expiresAtEpochMs)
    assertEquals(10_090L, releasedLease.releasedAtEpochMs)
    assertTrue(heartbeatScheduler.tasks.last().cancelled)
  }

  @Test
  fun projectionCoordinatorDoesNotPersistSnapshotWhenAnotherOwnerHoldsLease() {
    var now = 10_000L
    val projectionStore = inMemoryRuntimeServiceProjectionStore()
    val ownerLeaseStore = inMemoryRuntimeServiceOwnerLeaseStore()
    val heartbeatScheduler = RecordingRuntimeServiceDelayScheduler()
    val existingOwnerLease = RuntimeServiceOwnerLease(
      target = RuntimeServiceTarget.DETACHED_BACKGROUND,
      processStartId = "process-other-owner",
      processStartedAtEpochMs = 9_000L,
      controllerInstanceId = "runtime-controller-other",
      durableControllerId = "runtime-controller-durable",
      runtimeOwnerId = "runtime-owner-other",
      runtimeControllerId = "runtime-controller-other",
      durableRuntimeControllerId = "runtime-controller-durable",
      serviceInstanceId = "runtime-service-other",
      serviceProcessName = "org.opencray.app:runtime",
      acquiredAtEpochMs = 9_500L,
      heartbeatAtEpochMs = 9_900L,
      expiresAtEpochMs = 20_000L,
    )
    ownerLeaseStore.save(existingOwnerLease)
    val runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor(
      processStartId = "process-owner",
      processStartedAtEpochMs = 9_000L,
      runtimeOwnerId = "runtime-owner-a",
      runtimeControllerId = "runtime-controller-a",
      durableRuntimeControllerId = "runtime-controller-durable",
    )
    val ownerAccess = RecordingRuntimeNotificationHostAccess(runtimeOwnerLifecycle)
    val coordinator = DefaultRuntimeServiceProjectionCoordinator(
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(
        processStartId = "process-controller",
        processStartedAtEpochMs = 8_000L,
        controllerInstanceId = "runtime-controller-a",
        durableControllerId = "runtime-controller-durable",
        controllerCreatedAtEpochMs = 8_500L,
      ),
      clock = { now },
      ownerLeaseDurationMs = 100L,
      ownerLeaseHeartbeatIntervalMs = 25L,
      runtimeOwnerLifecycle = runtimeOwnerLifecycle,
      ownerObservationAccess = ownerAccess,
      notificationHostAccess = ownerAccess,
      serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
        workSummaryProvider = ownerAccess::activeWorkSummary,
        clock = { now },
      ),
      appContext = ContextWrapper(null),
      localizedContext = ContextWrapper(null),
      chatSessionStore = ChatSessionLocalStore(
        temporaryFolder.newFolder("chat-session-store-conflict"),
      ),
      scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
      scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
      runtimeServiceAccessGateway = NoOpRuntimeServiceAccessGateway,
      projectionStore = projectionStore,
      ownerLeaseStore = ownerLeaseStore,
      ownerLeaseHeartbeatScheduler = heartbeatScheduler,
      runtimeNotificationCoordinator = null,
    )

    coordinator.bindServiceLifecycle(
      RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-a",
        serviceCreatedAtEpochMs = 9_500L,
        serviceProcess = runtimeServiceProcessDescriptor(
          packageName = "org.opencray.app",
          processName = "org.opencray.app:runtime",
        ),
      ),
    )
    coordinator.start()

    val blockedLease = checkNotNull(
      ownerLeaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND),
    )
    assertEquals(existingOwnerLease, blockedLease.copy(lastAcquireFailure = null))
    val firstFailure = checkNotNull(blockedLease.lastAcquireFailure)
    assertEquals("runtime-owner-a", firstFailure.attemptedRuntimeOwnerId)
    assertEquals("runtime-owner-other", firstFailure.holderRuntimeOwnerId)
    assertEquals("runtime-service-a", firstFailure.attemptedServiceInstanceId)
    assertEquals("runtime-service-other", firstFailure.holderServiceInstanceId)
    assertEquals(10_000L, firstFailure.attemptedAtEpochMs)
    assertNull(coordinator.currentOwnerLease())
    assertNull(projectionStore.loadSnapshot())

    now = 10_050L
    heartbeatScheduler.runNext()

    val refreshedBlockedLease = checkNotNull(
      ownerLeaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND),
    )
    assertEquals(existingOwnerLease, refreshedBlockedLease.copy(lastAcquireFailure = null))
    assertEquals(10_050L, refreshedBlockedLease.lastAcquireFailure?.attemptedAtEpochMs)
    assertNull(coordinator.currentOwnerLease())
    assertNull(projectionStore.loadSnapshot())
  }

  @Test
  fun projectionCoordinatorDefersActivationUntilStart() {
    var now = 10_000L
    val projectionStore = inMemoryRuntimeServiceProjectionStore()
    val ownerLeaseStore = inMemoryRuntimeServiceOwnerLeaseStore()
    val heartbeatScheduler = RecordingRuntimeServiceDelayScheduler()
    val runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor(
      processStartId = "process-owner",
      processStartedAtEpochMs = 9_000L,
      runtimeOwnerId = "runtime-owner-a",
      runtimeControllerId = "runtime-controller-a",
      durableRuntimeControllerId = "runtime-controller-durable",
    )
    val ownerAccess = RecordingRuntimeNotificationHostAccess(runtimeOwnerLifecycle)
    val coordinator = DefaultRuntimeServiceProjectionCoordinator(
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(
        processStartId = "process-controller",
        processStartedAtEpochMs = 8_000L,
        controllerInstanceId = "runtime-controller-a",
        durableControllerId = "runtime-controller-durable",
        controllerCreatedAtEpochMs = 8_500L,
      ),
      clock = { now },
      ownerLeaseDurationMs = 100L,
      ownerLeaseHeartbeatIntervalMs = 25L,
      runtimeOwnerLifecycle = runtimeOwnerLifecycle,
      ownerObservationAccess = ownerAccess,
      notificationHostAccess = ownerAccess,
      serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
        workSummaryProvider = ownerAccess::activeWorkSummary,
        clock = { now },
      ),
      appContext = ContextWrapper(null),
      localizedContext = ContextWrapper(null),
      chatSessionStore = ChatSessionLocalStore(
        temporaryFolder.newFolder("chat-session-store-deferred-start"),
      ),
      scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
      scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
      runtimeServiceAccessGateway = NoOpRuntimeServiceAccessGateway,
      projectionStore = projectionStore,
      ownerLeaseStore = ownerLeaseStore,
      ownerLeaseHeartbeatScheduler = heartbeatScheduler,
      runtimeNotificationCoordinator = null,
    )

    coordinator.bindServiceLifecycle(
      RuntimeServiceLifecycleDescriptor(
        serviceInstanceId = "runtime-service-a",
        serviceCreatedAtEpochMs = 9_500L,
        serviceProcess = runtimeServiceProcessDescriptor(
          packageName = "org.opencray.app",
          processName = "org.opencray.app:runtime",
        ),
      ),
    )

    assertNull(ownerLeaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND))
    assertNull(projectionStore.loadSnapshot())
    assertEquals(0, heartbeatScheduler.tasks.size)

    coordinator.start()

    val firstLease = checkNotNull(
      ownerLeaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND),
    )
    assertEquals(RuntimeServiceOwnerLease.PHASE_HELD, firstLease.phase)
    assertEquals(10_000L, firstLease.acquiredAtEpochMs)
    assertEquals(10_000L, firstLease.heartbeatAtEpochMs)
    assertEquals(firstLease, projectionStore.loadSnapshot()?.runtimeServiceOwnerLease)
    assertEquals(1, heartbeatScheduler.tasks.size)
  }

  private class RecordingRuntimeNotificationHostAccess(
    override val lifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  ) : RuntimeNotificationHostAccess {
    private val listeners = linkedSetOf<AgentSessionRuntimeListener>()
    private val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    private val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    private val supplementStores = linkedMapOf<String, SessionSupplementStore>()

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit {
      listeners += listener
      return { listeners -= listener }
    }

    override fun activeWorkSummary(): RuntimeOwnerWorkSummary = RuntimeOwnerWorkSummary()

    override fun session(sessionId: String): OpenCrayRuntimeSessionAccess =
      error("session access is unused in this test")

    override fun releaseSession(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit

    override fun runEventJournalStore(sessionId: String): RunEventJournalStore =
      runEventJournalStoreFactory.forChatSession(sessionId)

    override fun promptCheckpointStore(sessionId: String): PromptCheckpointStore =
      promptCheckpointStoreFactory.forChatSession(sessionId)

    override fun supplementStore(sessionId: String): SessionSupplementStore =
      supplementStores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
  }

  private object NoOpRuntimeServiceAccessGateway : RuntimeServiceAccessGateway {
    override fun ensureClient(
      context: Context,
      target: RuntimeServiceTarget,
    ): OpenCrayRuntimeServiceClient = error("unused in test")

    override fun startScheduledTask(
      context: Context,
      command: ScheduledTaskWakeCommand,
      target: RuntimeServiceTarget,
    ) = Unit

    override fun repairSchedules(
      context: Context,
      repairReason: String,
      target: RuntimeServiceTarget,
    ): Boolean = false

    override fun resumeInterruptedRuns(
      context: Context,
      repairReason: String,
      target: RuntimeServiceTarget,
    ): Boolean = false

    override fun approvalActionPendingIntent(
      context: Context,
      action: String,
      sessionId: String,
      taskId: String,
      runId: String,
      requestCode: Int,
      target: RuntimeServiceTarget,
    ): PendingIntent = error("unused in test")
  }

  private class RecordingRuntimeServiceDelayScheduler : RuntimeServiceDelayScheduler {
    val tasks = mutableListOf<RecordingRuntimeServiceDelayedTask>()

    override fun schedule(
      delayMs: Long,
      action: () -> Unit,
    ): RuntimeServiceDelayedTask {
      val task = RecordingRuntimeServiceDelayedTask(delayMs, action)
      tasks += task
      return task
    }

    fun runNext() {
      tasks.firstOrNull { task -> !task.cancelled && !task.ran }?.run()
    }
  }

  private class RecordingRuntimeServiceDelayedTask(
    val delayMs: Long,
    private val action: () -> Unit,
  ) : RuntimeServiceDelayedTask {
    var cancelled: Boolean = false
      private set
    var ran: Boolean = false
      private set

    fun run() {
      if (cancelled || ran) {
        return
      }
      ran = true
      action()
    }

    override fun cancel() {
      cancelled = true
    }
  }
}
