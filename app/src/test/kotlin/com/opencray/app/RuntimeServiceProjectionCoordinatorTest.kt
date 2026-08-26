package com.opencray.app

import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    val projectionStore = ToggleFailingRuntimeServiceProjectionStore(
      inMemoryRuntimeServiceProjectionStore(),
    )
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

    projectionStore.failNextSave()
    now = 10_055L
    heartbeatScheduler.runNext()
    val leaseAfterFailedProjection = checkNotNull(
      ownerLeaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND),
    )
    assertEquals(10_055L, leaseAfterFailedProjection.heartbeatAtEpochMs)
    assertEquals(10_155L, leaseAfterFailedProjection.expiresAtEpochMs)
    assertEquals(3, heartbeatScheduler.tasks.size)

    val competingLease = leaseAfterFailedProjection.copy(
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
    assertEquals(leaseAfterFailedProjection, blockedLease.copy(lastAcquireFailure = null))
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
    val releasedProjection = checkNotNull(projectionStore.loadSnapshot())
    val projectedReleasedLease = checkNotNull(releasedProjection.runtimeServiceOwnerLease)
    assertEquals(releasedLease, projectedReleasedLease)
    assertEquals(RuntimeServiceOwnerLease.PHASE_RELEASED, projectedReleasedLease.phase)
    assertEquals(
      RuntimeServiceKeepAliveState.PHASE_DESTROYED,
      releasedProjection.serviceKeepAliveState.phase,
    )
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
  fun projectionCoordinatorDisposeDoesNotOverwriteReplacementOwnerProjection() {
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
        temporaryFolder.newFolder("chat-session-store-replaced-owner-dispose"),
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

    val firstLease = checkNotNull(
      ownerLeaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND),
    )
    now = 10_250L
    val replacementLease = ownerLeaseStore.save(
      firstLease.copy(
        processStartId = "process-replacement-owner",
        controllerInstanceId = "runtime-controller-b",
        runtimeOwnerId = "runtime-owner-b",
        runtimeControllerId = "runtime-controller-b",
        serviceInstanceId = "runtime-service-b",
        acquiredAtEpochMs = 10_200L,
        heartbeatAtEpochMs = 10_250L,
        expiresAtEpochMs = 10_350L,
      ),
    )
    assertEquals("runtime-owner-b", replacementLease.runtimeOwnerId)
    projectionStore.saveSnapshot(
      checkNotNull(projectionStore.loadSnapshot()).copy(
        runtimeOwnerLifecycle = runtimeOwnerLifecycle.copy(
          runtimeOwnerId = "runtime-owner-b",
          runtimeControllerId = "runtime-controller-b",
        ),
        serviceLifecycle = RuntimeServiceLifecycleDescriptor(
          serviceInstanceId = "runtime-service-b",
          serviceCreatedAtEpochMs = 10_200L,
          serviceProcess = runtimeServiceProcessDescriptor(
            packageName = "org.opencray.app",
            processName = "org.opencray.app:runtime",
          ),
        ),
        runtimeServiceOwnerLease = replacementLease,
      ),
    )

    now = 10_260L
    coordinator.dispose()

    assertEquals(replacementLease, ownerLeaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND))
    assertEquals(replacementLease, projectionStore.loadSnapshot()?.runtimeServiceOwnerLease)
    assertEquals(RuntimeServiceOwnerLease.PHASE_HELD, projectionStore.loadSnapshot()?.runtimeServiceOwnerLease?.phase)
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

  @Test
  fun projectionCoordinatorPersistsTransportFailureSnapshotBeforeStart() {
    val now = 10_000L
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
    val failedServerState = LocalRuntimeServerState(
      phase = LocalRuntimeServerState.PHASE_BIND_FAILED,
      bindAddress = "127.0.0.1",
      requestedPort = 42_618,
      listeningPort = null,
      lastStartAttemptAtEpochMs = 9_950L,
      lastStartedAtEpochMs = null,
      failureReason = "Address already in use",
      changedAtEpochMs = 9_960L,
    )
    val serviceLifecycle = RuntimeServiceLifecycleDescriptor(
      serviceInstanceId = "runtime-service-a",
      serviceCreatedAtEpochMs = 9_500L,
      serviceProcess = runtimeServiceProcessDescriptor(
        packageName = "org.opencray.app",
        processName = "org.opencray.app:runtime",
      ),
    )
    val coordinator = DefaultRuntimeServiceProjectionCoordinator(
      runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
      localRuntimeServerStateProvider = { failedServerState },
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
        temporaryFolder.newFolder("chat-session-store-transport-failure"),
      ),
      scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
      scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
      runtimeServiceAccessGateway = NoOpRuntimeServiceAccessGateway,
      projectionStore = projectionStore,
      ownerLeaseStore = ownerLeaseStore,
      ownerLeaseHeartbeatScheduler = heartbeatScheduler,
      runtimeNotificationCoordinator = null,
    )

    coordinator.bindServiceLifecycle(serviceLifecycle)

    coordinator.persistProjectionSnapshot()

    val snapshot = checkNotNull(projectionStore.loadSnapshot())
    val lease = checkNotNull(snapshot.runtimeServiceOwnerLease)
    assertEquals(LocalRuntimeServerState.PHASE_BIND_FAILED, snapshot.localRuntimeServerState?.phase)
    assertEquals("Address already in use", snapshot.localRuntimeServerState?.failureReason)
    assertEquals("runtime-service-a", snapshot.serviceLifecycle.serviceInstanceId)
    assertEquals("runtime-owner-a", snapshot.runtimeOwnerLifecycle.runtimeOwnerId)
    assertEquals(RuntimeServiceOwnerLease.PHASE_HELD, lease.phase)
    assertEquals("runtime-owner-a", lease.runtimeOwnerId)
    assertEquals(10_000L, lease.heartbeatAtEpochMs)
    assertEquals(10_100L, lease.expiresAtEpochMs)
    assertEquals(lease, ownerLeaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND))
    assertEquals(0, heartbeatScheduler.tasks.size)
    assertEquals(0, ownerAccess.observerCount)
  }

  @Test
  fun projectionCoordinatorFailsClosedWhenOwnerLeaseFileIsCorrupt() {
    val root = temporaryFolder.newFolder("coordinator-corrupt-lease-root")
    val fileName =
      "runtime-service-owner-lease-${RuntimeServiceTarget.DETACHED_BACKGROUND.wireValue}.json"
    val corruptContent = "{\"target\":\"detached_background\",\"phase\":"
    File(root, fileName).writeText(corruptContent)
    var now = 10_000L
    val projectionStore = inMemoryRuntimeServiceProjectionStore()
    val heartbeatScheduler = RecordingRuntimeServiceDelayScheduler()
    val ownerAccess = RecordingRuntimeNotificationHostAccess(baseTestRuntimeOwnerLifecycle())
    val coordinator = newDefaultProjectionCoordinator(
      clock = { now },
      projectionStore = projectionStore,
      ownerLeaseStore = FileBackedRuntimeServiceOwnerLeaseStore.fromRootDirectory(root),
      heartbeatScheduler = heartbeatScheduler,
      ownerAccess = ownerAccess,
      stateFolderName = "chat-session-store-corrupt-lease",
    )

    coordinator.bindServiceLifecycle(testServiceLifecycle("runtime-service-a"))

    assertFalse(coordinator.tryAcquireOwnerLease())
    assertNull(coordinator.currentOwnerLease())
    assertNull(projectionStore.loadSnapshot())
    assertEquals(0, heartbeatScheduler.tasks.size)
    assertEquals(corruptContent, File(root, fileName).readText())
    val backups = checkNotNull(root.listFiles()).filter { file ->
      file.name.startsWith("$fileName.corrupt-")
    }
    assertEquals(1, backups.size)
    assertEquals(corruptContent, backups.single().readText())
  }

  @Test
  fun replaceRuntimeOwnerDisposesPreviousObserverEvenWhenLeaseReleaseFails() {
    var now = 10_000L
    val projectionStore = inMemoryRuntimeServiceProjectionStore()
    val leaseStore = ToggleFailingReleaseRuntimeServiceOwnerLeaseStore(
      inMemoryRuntimeServiceOwnerLeaseStore(),
    )
    val heartbeatScheduler = RecordingRuntimeServiceDelayScheduler()
    val runtimeOwnerLifecycle = baseTestRuntimeOwnerLifecycle()
    val ownerAccess = RecordingRuntimeNotificationHostAccess(runtimeOwnerLifecycle)
    val coordinator = newDefaultProjectionCoordinator(
      clock = { now },
      projectionStore = projectionStore,
      ownerLeaseStore = leaseStore,
      heartbeatScheduler = heartbeatScheduler,
      ownerAccess = ownerAccess,
      stateFolderName = "chat-session-store-replace-release-failure",
    )
    coordinator.bindServiceLifecycle(testServiceLifecycle("runtime-service-a"))
    coordinator.start()
    assertEquals(1, ownerAccess.observerCount)

    now = 10_050L
    leaseStore.failNextRelease = true
    val replacementOwnerLifecycle = runtimeOwnerLifecycle.copy(
      runtimeOwnerId = "runtime-owner-b",
      runtimeControllerId = "runtime-controller-b",
    )
    val replacementOwnerAccess =
      RecordingRuntimeNotificationHostAccess(replacementOwnerLifecycle)

    coordinator.replaceRuntimeOwner(
      runtimeOwnerLifecycle = replacementOwnerLifecycle,
      ownerObservationAccess = replacementOwnerAccess,
      notificationHostAccess = replacementOwnerAccess,
    )

    assertEquals(0, ownerAccess.observerCount)
    assertEquals(1, replacementOwnerAccess.observerCount)
    val storedLease = checkNotNull(leaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND))
    assertEquals(RuntimeServiceOwnerLease.PHASE_HELD, storedLease.phase)
    assertEquals("runtime-owner-a", storedLease.runtimeOwnerId)
    val snapshotLease = checkNotNull(projectionStore.loadSnapshot()?.runtimeServiceOwnerLease)
    assertEquals("runtime-owner-a", snapshotLease.runtimeOwnerId)
  }

  @Test
  fun disposeCompletesCleanupWhenReleasedProjectionPersistFails() {
    var now = 10_000L
    val projectionStore = ToggleFailingRuntimeServiceProjectionStore(
      inMemoryRuntimeServiceProjectionStore(),
    )
    val ownerLeaseStore = inMemoryRuntimeServiceOwnerLeaseStore()
    val heartbeatScheduler = RecordingRuntimeServiceDelayScheduler()
    val ownerAccess =
      RecordingRuntimeNotificationHostAccess(baseTestRuntimeOwnerLifecycle())
    val coordinator = newDefaultProjectionCoordinator(
      clock = { now },
      projectionStore = projectionStore,
      ownerLeaseStore = ownerLeaseStore,
      heartbeatScheduler = heartbeatScheduler,
      ownerAccess = ownerAccess,
      stateFolderName = "chat-session-store-dispose-projection-failure",
    )
    coordinator.bindServiceLifecycle(testServiceLifecycle("runtime-service-a"))
    coordinator.start()
    assertEquals(1, ownerAccess.observerCount)

    now = 10_090L
    projectionStore.failNextSave()
    coordinator.dispose()

    assertEquals(0, ownerAccess.observerCount)
    val releasedLease =
      checkNotNull(ownerLeaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND))
    assertEquals(RuntimeServiceOwnerLease.PHASE_RELEASED, releasedLease.phase)
    assertEquals("runtime-owner-a", releasedLease.runtimeOwnerId)
  }

  @Test
  fun disposeCompletesCleanupWhenLeaseReleaseFails() {
    var now = 10_000L
    val projectionStore = inMemoryRuntimeServiceProjectionStore()
    val leaseStore = ToggleFailingReleaseRuntimeServiceOwnerLeaseStore(
      inMemoryRuntimeServiceOwnerLeaseStore(),
    )
    val heartbeatScheduler = RecordingRuntimeServiceDelayScheduler()
    val ownerAccess =
      RecordingRuntimeNotificationHostAccess(baseTestRuntimeOwnerLifecycle())
    val coordinator = newDefaultProjectionCoordinator(
      clock = { now },
      projectionStore = projectionStore,
      ownerLeaseStore = leaseStore,
      heartbeatScheduler = heartbeatScheduler,
      ownerAccess = ownerAccess,
      stateFolderName = "chat-session-store-dispose-release-failure",
    )
    coordinator.bindServiceLifecycle(testServiceLifecycle("runtime-service-a"))
    coordinator.start()

    now = 10_090L
    leaseStore.failNextRelease = true
    coordinator.dispose()

    assertEquals(0, ownerAccess.observerCount)
    val storedLease = checkNotNull(leaseStore.load(RuntimeServiceTarget.DETACHED_BACKGROUND))
    assertEquals(RuntimeServiceOwnerLease.PHASE_HELD, storedLease.phase)
    assertEquals("runtime-owner-a", storedLease.runtimeOwnerId)
  }

  private fun baseTestRuntimeOwnerLifecycle(): HostRuntimeLifecycleDescriptor =
    HostRuntimeLifecycleDescriptor(
      processStartId = "process-owner",
      processStartedAtEpochMs = 9_000L,
      runtimeOwnerId = "runtime-owner-a",
      runtimeControllerId = "runtime-controller-a",
      durableRuntimeControllerId = "runtime-controller-durable",
    )

  private fun testServiceLifecycle(serviceInstanceId: String): RuntimeServiceLifecycleDescriptor =
    RuntimeServiceLifecycleDescriptor(
      serviceInstanceId = serviceInstanceId,
      serviceCreatedAtEpochMs = 9_500L,
      serviceProcess = runtimeServiceProcessDescriptor(
        packageName = "org.opencray.app",
        processName = "org.opencray.app:runtime",
      ),
    )

  private fun newDefaultProjectionCoordinator(
    clock: () -> Long,
    projectionStore: RuntimeServiceProjectionStore,
    ownerLeaseStore: RuntimeServiceOwnerLeaseStore,
    heartbeatScheduler: RecordingRuntimeServiceDelayScheduler,
    ownerAccess: RuntimeNotificationHostAccess,
    stateFolderName: String,
  ): DefaultRuntimeServiceProjectionCoordinator = DefaultRuntimeServiceProjectionCoordinator(
    runtimeTarget = RuntimeServiceTarget.DETACHED_BACKGROUND,
    runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(
      processStartId = "process-controller",
      processStartedAtEpochMs = 8_000L,
      controllerInstanceId = "runtime-controller-a",
      durableControllerId = "runtime-controller-durable",
      controllerCreatedAtEpochMs = 8_500L,
    ),
    clock = clock,
    ownerLeaseDurationMs = 100L,
    ownerLeaseHeartbeatIntervalMs = 25L,
    runtimeOwnerLifecycle = baseTestRuntimeOwnerLifecycle(),
    ownerObservationAccess = ownerAccess,
    notificationHostAccess = ownerAccess,
    serviceWorkStateTracker = RuntimeServiceWorkStateTracker(
      workSummaryProvider = ownerAccess::activeWorkSummary,
      clock = clock,
    ),
    appContext = ContextWrapper(null),
    localizedContext = ContextWrapper(null),
    chatSessionStore = ChatSessionLocalStore(
      temporaryFolder.newFolder(stateFolderName),
    ),
    scheduledTaskSpecStore = inMemoryScheduledTaskSpecStoreFactory().create(),
    scheduledTaskRunRecordStore = inMemoryScheduledTaskRunRecordStoreFactory().create(),
    runtimeServiceAccessGateway = NoOpRuntimeServiceAccessGateway,
    projectionStore = projectionStore,
    ownerLeaseStore = ownerLeaseStore,
    ownerLeaseHeartbeatScheduler = heartbeatScheduler,
    runtimeNotificationCoordinator = null,
  )

  private class ToggleFailingReleaseRuntimeServiceOwnerLeaseStore(
    private val delegate: RuntimeServiceOwnerLeaseStore,
  ) : RuntimeServiceOwnerLeaseStore {
    var failNextRelease: Boolean = false

    override fun load(target: RuntimeServiceTarget): RuntimeServiceOwnerLease? =
      delegate.load(target)

    override fun save(lease: RuntimeServiceOwnerLease): RuntimeServiceOwnerLease =
      delegate.save(lease)

    override fun release(lease: RuntimeServiceOwnerLease): RuntimeServiceOwnerLease {
      if (failNextRelease) {
        failNextRelease = false
        throw IllegalStateException("injected lease release failure")
      }
      return delegate.release(lease)
    }

    override fun clear(target: RuntimeServiceTarget) = delegate.clear(target)
  }

  private class RecordingRuntimeNotificationHostAccess(
    override val lifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  ) : RuntimeNotificationHostAccess {
    private val listeners = linkedSetOf<AgentSessionRuntimeListener>()
    val observerCount: Int
      get() = listeners.size
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

  private class ToggleFailingRuntimeServiceProjectionStore(
    private val delegate: RuntimeServiceProjectionStore,
  ) : RuntimeServiceProjectionStore {
    private var shouldFailNextSave: Boolean = false

    fun failNextSave() {
      shouldFailNextSave = true
    }

    override fun loadSnapshot(): RuntimeServiceProjectionSnapshot? = delegate.loadSnapshot()

    override fun saveSnapshot(snapshot: RuntimeServiceProjectionSnapshot) {
      if (shouldFailNextSave) {
        shouldFailNextSave = false
        throw IllegalStateException("injected projection persistence failure")
      }
      delegate.saveSnapshot(snapshot)
    }

    override fun clear() {
      delegate.clear()
    }
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
    ): Boolean = true

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
      executionId: String?,
      executionOrdinal: Int?,
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
