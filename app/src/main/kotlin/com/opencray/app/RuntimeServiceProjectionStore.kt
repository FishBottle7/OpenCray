package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.Serializable

internal data class RuntimeServiceProjectionSnapshot(
  val localRuntimeServerState: LocalRuntimeServerState? = null,
  val runtimeControllerLifecycle: RuntimeControllerLifecycleDescriptor? = null,
  val runtimeOwnerLifecycle: HostRuntimeLifecycleDescriptor,
  val runtimeOwnerWorkSummary: RuntimeOwnerWorkSummary,
  val serviceLifecycle: RuntimeServiceLifecycleDescriptor,
  val serviceWorkState: RuntimeServiceWorkState,
  val serviceKeepAliveState: RuntimeServiceKeepAliveState,
  val runtimeServiceOwnerLease: RuntimeServiceOwnerLease? = null,
  val lastInterruptedRunRepair: RuntimeServiceInterruptedRunRepairProjection? = null,
)

internal interface RuntimeServiceProjectionStore {
  fun loadSnapshot(): RuntimeServiceProjectionSnapshot?

  fun saveSnapshot(snapshot: RuntimeServiceProjectionSnapshot)

  fun clear()
}

internal fun inMemoryRuntimeServiceProjectionStore(): RuntimeServiceProjectionStore =
  InMemoryRuntimeServiceProjectionStore()

internal fun fileBackedRuntimeServiceProjectionStore(
  storage: DurableTextStorage,
  target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
): RuntimeServiceProjectionStore = FileBackedRuntimeServiceProjectionStore(
  storage = storage,
  fileName = runtimeServiceProjectionFileNameForTarget(target),
)

internal class FileBackedRuntimeServiceProjectionStoreFactory(
  private val runtimeRootDirectory: File,
) {
  fun create(
    target: RuntimeServiceTarget = DEFAULT_RUNTIME_SERVICE_TARGET,
  ): RuntimeServiceProjectionStore {
    if (!runtimeRootDirectory.exists()) {
      runtimeRootDirectory.mkdirs()
    }
    return FileBackedRuntimeServiceProjectionStore(
      storage = DirectoryDurableTextStorage(runtimeRootDirectory),
      fileName = runtimeServiceProjectionFileNameForTarget(target),
    )
  }

  companion object {
    fun fromContext(context: Context): FileBackedRuntimeServiceProjectionStoreFactory =
      FileBackedRuntimeServiceProjectionStoreFactory(
        runtimeRootDirectory = File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )

  }
}

private fun runtimeServiceProjectionFileNameForTarget(target: RuntimeServiceTarget): String =
  "runtime-service-projection-${target.wireValue}.json"

internal fun OpenCrayRuntimeServiceBridgeSnapshot.toProjectionSnapshot(): RuntimeServiceProjectionSnapshot =
  RuntimeServiceProjectionSnapshot(
    localRuntimeServerState = localRuntimeServerState,
    runtimeControllerLifecycle = runtimeControllerLifecycle,
    runtimeOwnerLifecycle = runtimeOwnerLifecycle,
    runtimeOwnerWorkSummary = runtimeOwnerWorkSummary,
    serviceLifecycle = serviceLifecycle,
    serviceWorkState = serviceWorkState,
    serviceKeepAliveState = serviceKeepAliveState,
  )

private class InMemoryRuntimeServiceProjectionStore : RuntimeServiceProjectionStore {
  private val lock = Any()
  private var snapshot: RuntimeServiceProjectionSnapshot? = null

  override fun loadSnapshot(): RuntimeServiceProjectionSnapshot? = synchronized(lock) { snapshot }

  override fun saveSnapshot(snapshot: RuntimeServiceProjectionSnapshot) {
    synchronized(lock) {
      this.snapshot = snapshot
    }
  }

  override fun clear() {
    synchronized(lock) {
      snapshot = null
    }
  }
}

private class FileBackedRuntimeServiceProjectionStore(
  private val storage: DurableTextStorage,
  private val fileName: String,
) : RuntimeServiceProjectionStore {
  private val lock = Any()

  override fun loadSnapshot(): RuntimeServiceProjectionSnapshot? = synchronized(lock) {
    loadRecord()?.toSnapshot()
  }

  override fun saveSnapshot(snapshot: RuntimeServiceProjectionSnapshot) {
    synchronized(lock) {
      storage.updateText(fileName) { currentText ->
        val currentSnapshot = decodeRecordOrNull(currentText)?.toSnapshot()
        val mergedSnapshot = snapshot.withRetainedNewerCollateral(currentSnapshot)
        DurableTextUpdate(
          text = encodeRecord(mergedSnapshot.toPersistedRecord()),
          result = Unit,
        )
      }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(fileName)
    }
  }

  private fun loadRecord(): PersistedRuntimeServiceProjectionRecord? {
    return decodeRecordOrNull(storage.readText(fileName))
  }
}

private fun RuntimeServiceProjectionSnapshot.withRetainedNewerCollateral(
  currentSnapshot: RuntimeServiceProjectionSnapshot?,
): RuntimeServiceProjectionSnapshot {
  if (currentSnapshot == null) {
    return this
  }
  return copy(
    runtimeServiceOwnerLease = runtimeServiceOwnerLease.newerProjectionThan(
      currentSnapshot.runtimeServiceOwnerLease,
    ),
    lastInterruptedRunRepair = lastInterruptedRunRepair.newerProjectionThan(
      currentSnapshot.lastInterruptedRunRepair,
    ),
  )
}

private fun RuntimeServiceOwnerLease?.newerProjectionThan(
  current: RuntimeServiceOwnerLease?,
): RuntimeServiceOwnerLease? = when {
  this == null -> current
  current == null -> this
  projectionUpdatedAtEpochMs() >= current.projectionUpdatedAtEpochMs() -> this
  else -> current
}

private fun RuntimeServiceOwnerLease.projectionUpdatedAtEpochMs(): Long = maxOf(
  heartbeatAtEpochMs,
  releasedAtEpochMs ?: 0L,
  lastAcquireFailure?.attemptedAtEpochMs ?: 0L,
)

private fun RuntimeServiceInterruptedRunRepairProjection?.newerProjectionThan(
  current: RuntimeServiceInterruptedRunRepairProjection?,
): RuntimeServiceInterruptedRunRepairProjection? = when {
  this == null -> current
  current == null -> this
  recordedAtEpochMs >= current.recordedAtEpochMs -> this
  else -> current
}

private fun decodeRecord(
  encoded: String?,
): PersistedRuntimeServiceProjectionRecord? {
  val normalized = encoded.orEmpty().trim()
  if (normalized.isBlank()) {
    return null
  }
  return PersistenceJson.instance.decodeFromString(
    deserializer = PersistedRuntimeServiceProjectionRecord.serializer(),
    string = normalized,
  )
}

private fun decodeRecordOrNull(
  encoded: String?,
): PersistedRuntimeServiceProjectionRecord? = runCatching {
  decodeRecord(encoded)
}.getOrNull()

private fun encodeRecord(record: PersistedRuntimeServiceProjectionRecord): String =
  PersistenceJson.instance.encodeToString(
    serializer = PersistedRuntimeServiceProjectionRecord.serializer(),
    value = record,
  )

private fun RuntimeServiceProjectionSnapshot.toPersistedRecord():
  PersistedRuntimeServiceProjectionRecord = PersistedRuntimeServiceProjectionRecord(
    updatedAtEpochMs = maxOf(
      localRuntimeServerState?.changedAtEpochMs ?: 0L,
      runtimeControllerLifecycle?.controllerCreatedAtEpochMs ?: 0L,
      runtimeOwnerLifecycle.hostCreatedAtEpochMs,
      serviceLifecycle.serviceCreatedAtEpochMs,
      serviceWorkState.changedAtEpochMs,
      serviceKeepAliveState.changedAtEpochMs,
      runtimeServiceOwnerLease?.heartbeatAtEpochMs ?: 0L,
      runtimeServiceOwnerLease?.lastAcquireFailure?.attemptedAtEpochMs ?: 0L,
      lastInterruptedRunRepair?.recordedAtEpochMs ?: 0L,
    ),
    localRuntimeServerState = localRuntimeServerState?.toPersistedRecord(),
    runtimeControllerLifecycle = runtimeControllerLifecycle?.let { lifecycle ->
      PersistedRuntimeControllerLifecycleDescriptor(
        processStartId = lifecycle.processStartId,
        processStartedAtEpochMs = lifecycle.processStartedAtEpochMs,
        controllerInstanceId = lifecycle.controllerInstanceId,
        durableControllerId = lifecycle.durableControllerId,
        controllerCreatedAtEpochMs = lifecycle.controllerCreatedAtEpochMs,
      )
    },
    runtimeOwnerLifecycle = PersistedHostRuntimeLifecycleDescriptor(
      processStartId = runtimeOwnerLifecycle.processStartId,
      processStartedAtEpochMs = runtimeOwnerLifecycle.processStartedAtEpochMs,
      hostInstanceId = runtimeOwnerLifecycle.hostInstanceId,
      runtimeOwnerId = runtimeOwnerLifecycle.runtimeOwnerId,
      runtimeControllerId = runtimeOwnerLifecycle.runtimeControllerId,
      durableRuntimeControllerId = runtimeOwnerLifecycle.durableRuntimeControllerId,
      hostCreatedAtEpochMs = runtimeOwnerLifecycle.hostCreatedAtEpochMs,
    ),
    runtimeOwnerWorkSummary = PersistedRuntimeOwnerWorkSummary(
      trackedSessionCount = runtimeOwnerWorkSummary.trackedSessionCount,
      activeRunCount = runtimeOwnerWorkSummary.activeRunCount,
      activeSessionIds = runtimeOwnerWorkSummary.activeSessionIds,
      pendingWorkSessionIds = runtimeOwnerWorkSummary.pendingWorkSessionIds,
      liveManagedProcessSessionIds = runtimeOwnerWorkSummary.liveManagedProcessSessionIds,
      liveSubAgentSessionIds = runtimeOwnerWorkSummary.liveSubAgentSessionIds,
    ),
    serviceLifecycle = PersistedRuntimeServiceLifecycleDescriptor(
      processStartId = serviceLifecycle.processStartId,
      processStartedAtEpochMs = serviceLifecycle.processStartedAtEpochMs,
      serviceInstanceId = serviceLifecycle.serviceInstanceId,
      serviceCreatedAtEpochMs = serviceLifecycle.serviceCreatedAtEpochMs,
      serviceProcess = serviceLifecycle.serviceProcess?.toPersistedRecord(),
    ),
    serviceWorkState = PersistedRuntimeServiceWorkState(
      phase = serviceWorkState.phase,
      hasActiveWork = serviceWorkState.hasActiveWork,
      activeRunCount = serviceWorkState.activeRunCount,
      activeSessionCount = serviceWorkState.activeSessionCount,
      pendingWorkSessionCount = serviceWorkState.pendingWorkSessionCount,
      liveManagedProcessSessionCount = serviceWorkState.liveManagedProcessSessionCount,
      liveSubAgentSessionCount = serviceWorkState.liveSubAgentSessionCount,
      keepAliveRequired = serviceWorkState.keepAliveRequired,
      keepAliveReason = serviceWorkState.keepAliveReason,
      changedAtEpochMs = serviceWorkState.changedAtEpochMs,
      activeSinceEpochMs = serviceWorkState.activeSinceEpochMs,
      idleSinceEpochMs = serviceWorkState.idleSinceEpochMs,
    ),
    serviceKeepAliveState = PersistedRuntimeServiceKeepAliveState(
      phase = serviceKeepAliveState.phase,
      idleGraceMs = serviceKeepAliveState.idleGraceMs,
      stopScheduled = serviceKeepAliveState.stopScheduled,
      stopDeadlineEpochMs = serviceKeepAliveState.stopDeadlineEpochMs,
      lastStartId = serviceKeepAliveState.lastStartId,
      lastStartCommandAtEpochMs = serviceKeepAliveState.lastStartCommandAtEpochMs,
      lastStopRequestAtEpochMs = serviceKeepAliveState.lastStopRequestAtEpochMs,
      lastStopSucceeded = serviceKeepAliveState.lastStopSucceeded,
      changedAtEpochMs = serviceKeepAliveState.changedAtEpochMs,
    ),
    runtimeServiceOwnerLease = runtimeServiceOwnerLease?.toPersistedRecord(),
    lastInterruptedRunRepair = lastInterruptedRunRepair?.toPersistedRecord(),
  )

private fun PersistedRuntimeServiceProjectionRecord.toSnapshot(): RuntimeServiceProjectionSnapshot =
  RuntimeServiceProjectionSnapshot(
    localRuntimeServerState = localRuntimeServerState?.toSnapshot(),
    runtimeControllerLifecycle = runtimeControllerLifecycle?.let { lifecycle ->
      RuntimeControllerLifecycleDescriptor(
        processStartId = lifecycle.processStartId,
        processStartedAtEpochMs = lifecycle.processStartedAtEpochMs,
        controllerInstanceId = lifecycle.controllerInstanceId,
        durableControllerId = lifecycle.durableControllerId ?: lifecycle.controllerInstanceId,
        controllerCreatedAtEpochMs = lifecycle.controllerCreatedAtEpochMs,
      )
    },
    runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor(
      processStartId = runtimeOwnerLifecycle.processStartId,
      processStartedAtEpochMs = runtimeOwnerLifecycle.processStartedAtEpochMs,
      hostInstanceId = runtimeOwnerLifecycle.hostInstanceId,
      runtimeOwnerId = runtimeOwnerLifecycle.runtimeOwnerId,
      runtimeControllerId = runtimeOwnerLifecycle.runtimeControllerId ?: runtimeOwnerLifecycle.runtimeOwnerId,
      durableRuntimeControllerId = runtimeOwnerLifecycle.durableRuntimeControllerId
        ?: runtimeOwnerLifecycle.runtimeControllerId
        ?: runtimeOwnerLifecycle.runtimeOwnerId,
      hostCreatedAtEpochMs = runtimeOwnerLifecycle.hostCreatedAtEpochMs,
    ),
    runtimeOwnerWorkSummary = RuntimeOwnerWorkSummary(
      trackedSessionCount = runtimeOwnerWorkSummary.trackedSessionCount,
      activeRunCount = runtimeOwnerWorkSummary.activeRunCount,
      activeSessionIds = runtimeOwnerWorkSummary.activeSessionIds,
      pendingWorkSessionIds = runtimeOwnerWorkSummary.pendingWorkSessionIds,
      liveManagedProcessSessionIds = runtimeOwnerWorkSummary.liveManagedProcessSessionIds,
      liveSubAgentSessionIds = runtimeOwnerWorkSummary.liveSubAgentSessionIds,
    ),
    serviceLifecycle = RuntimeServiceLifecycleDescriptor(
      processStartId = serviceLifecycle.processStartId,
      processStartedAtEpochMs = serviceLifecycle.processStartedAtEpochMs,
      serviceInstanceId = serviceLifecycle.serviceInstanceId,
      serviceCreatedAtEpochMs = serviceLifecycle.serviceCreatedAtEpochMs,
      serviceProcess = serviceLifecycle.serviceProcess?.toSnapshot(),
    ),
    serviceWorkState = RuntimeServiceWorkState(
      phase = serviceWorkState.phase,
      hasActiveWork = serviceWorkState.hasActiveWork,
      activeRunCount = serviceWorkState.activeRunCount,
      activeSessionCount = serviceWorkState.activeSessionCount,
      pendingWorkSessionCount = serviceWorkState.pendingWorkSessionCount,
      liveManagedProcessSessionCount = serviceWorkState.liveManagedProcessSessionCount,
      liveSubAgentSessionCount = serviceWorkState.liveSubAgentSessionCount,
      keepAliveRequired = serviceWorkState.keepAliveRequired,
      keepAliveReason = serviceWorkState.keepAliveReason,
      changedAtEpochMs = serviceWorkState.changedAtEpochMs,
      activeSinceEpochMs = serviceWorkState.activeSinceEpochMs,
      idleSinceEpochMs = serviceWorkState.idleSinceEpochMs,
    ),
    serviceKeepAliveState = RuntimeServiceKeepAliveState(
      phase = serviceKeepAliveState.phase,
      idleGraceMs = serviceKeepAliveState.idleGraceMs,
      stopScheduled = serviceKeepAliveState.stopScheduled,
      stopDeadlineEpochMs = serviceKeepAliveState.stopDeadlineEpochMs,
      lastStartId = serviceKeepAliveState.lastStartId,
      lastStartCommandAtEpochMs = serviceKeepAliveState.lastStartCommandAtEpochMs,
      lastStopRequestAtEpochMs = serviceKeepAliveState.lastStopRequestAtEpochMs,
      lastStopSucceeded = serviceKeepAliveState.lastStopSucceeded,
      changedAtEpochMs = serviceKeepAliveState.changedAtEpochMs,
    ),
    runtimeServiceOwnerLease = runtimeServiceOwnerLease?.toSnapshot(),
    lastInterruptedRunRepair = lastInterruptedRunRepair?.toSnapshot(),
  )

@Serializable
private data class PersistedRuntimeServiceProjectionRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val updatedAtEpochMs: Long = 0L,
  val localRuntimeServerState: PersistedLocalRuntimeServerState? = null,
  val runtimeControllerLifecycle: PersistedRuntimeControllerLifecycleDescriptor? = null,
  val runtimeOwnerLifecycle: PersistedHostRuntimeLifecycleDescriptor,
  val runtimeOwnerWorkSummary: PersistedRuntimeOwnerWorkSummary,
  val serviceLifecycle: PersistedRuntimeServiceLifecycleDescriptor,
  val serviceWorkState: PersistedRuntimeServiceWorkState,
  val serviceKeepAliveState: PersistedRuntimeServiceKeepAliveState,
  val runtimeServiceOwnerLease: PersistedRuntimeServiceOwnerLeaseProjection? = null,
  val lastInterruptedRunRepair: PersistedRuntimeServiceInterruptedRunRepairProjection? = null,
)

@Serializable
private data class PersistedLocalRuntimeServerState(
  val phase: String,
  val bindAddress: String,
  val requestedPort: Int,
  val listeningPort: Int? = null,
  val lastStartAttemptAtEpochMs: Long? = null,
  val lastStartedAtEpochMs: Long? = null,
  val failureReason: String? = null,
  val changedAtEpochMs: Long,
)

@Serializable
private data class PersistedHostRuntimeLifecycleDescriptor(
  val processStartId: String,
  val processStartedAtEpochMs: Long,
  val hostInstanceId: String,
  val runtimeOwnerId: String,
  val runtimeControllerId: String? = null,
  val durableRuntimeControllerId: String? = null,
  val hostCreatedAtEpochMs: Long,
)

@Serializable
private data class PersistedRuntimeControllerLifecycleDescriptor(
  val processStartId: String,
  val processStartedAtEpochMs: Long,
  val controllerInstanceId: String,
  val durableControllerId: String? = null,
  val controllerCreatedAtEpochMs: Long,
)

@Serializable
private data class PersistedRuntimeOwnerWorkSummary(
  val trackedSessionCount: Int,
  val activeRunCount: Int,
  val activeSessionIds: List<String>,
  val pendingWorkSessionIds: List<String>,
  val liveManagedProcessSessionIds: List<String>,
  val liveSubAgentSessionIds: List<String>,
)

@Serializable
private data class PersistedRuntimeServiceLifecycleDescriptor(
  val processStartId: String,
  val processStartedAtEpochMs: Long,
  val serviceInstanceId: String,
  val serviceCreatedAtEpochMs: Long,
  val serviceProcess: PersistedRuntimeServiceProcessDescriptor? = null,
)

@Serializable
private data class PersistedRuntimeServiceProcessDescriptor(
  val packageName: String? = null,
  val processName: String? = null,
  val expectedProcessName: String? = null,
  val expectedProcessSuffix: String = RUNTIME_SERVICE_PROCESS_SUFFIX,
  val isDedicatedRuntimeProcess: Boolean = false,
  val mismatchReason: String? = null,
)

@Serializable
private data class PersistedRuntimeServiceWorkState(
  val phase: String,
  val hasActiveWork: Boolean,
  val activeRunCount: Int,
  val activeSessionCount: Int,
  val pendingWorkSessionCount: Int,
  val liveManagedProcessSessionCount: Int,
  val liveSubAgentSessionCount: Int,
  val keepAliveRequired: Boolean,
  val keepAliveReason: String? = null,
  val changedAtEpochMs: Long,
  val activeSinceEpochMs: Long? = null,
  val idleSinceEpochMs: Long? = null,
)

@Serializable
private data class PersistedRuntimeServiceKeepAliveState(
  val phase: String,
  val idleGraceMs: Long,
  val stopScheduled: Boolean,
  val stopDeadlineEpochMs: Long? = null,
  val lastStartId: Int? = null,
  val lastStartCommandAtEpochMs: Long? = null,
  val lastStopRequestAtEpochMs: Long? = null,
  val lastStopSucceeded: Boolean? = null,
  val changedAtEpochMs: Long,
)

@Serializable
private data class PersistedRuntimeServiceOwnerLeaseProjection(
  val target: String,
  val phase: String,
  val processStartId: String,
  val processStartedAtEpochMs: Long,
  val controllerInstanceId: String? = null,
  val durableControllerId: String? = null,
  val runtimeOwnerId: String,
  val runtimeControllerId: String? = null,
  val durableRuntimeControllerId: String? = null,
  val serviceInstanceId: String? = null,
  val serviceProcessName: String? = null,
  val acquiredAtEpochMs: Long,
  val heartbeatAtEpochMs: Long,
  val expiresAtEpochMs: Long,
  val releasedAtEpochMs: Long? = null,
  val lastAcquireFailure: PersistedRuntimeServiceOwnerLeaseAcquireFailureProjection? = null,
)

@Serializable
private data class PersistedRuntimeServiceOwnerLeaseAcquireFailureProjection(
  val target: String,
  val reason: String,
  val attemptedAtEpochMs: Long,
  val attemptedProcessStartId: String,
  val attemptedControllerInstanceId: String? = null,
  val attemptedDurableControllerId: String? = null,
  val attemptedRuntimeOwnerId: String,
  val attemptedRuntimeControllerId: String? = null,
  val attemptedDurableRuntimeControllerId: String? = null,
  val attemptedServiceInstanceId: String? = null,
  val attemptedServiceProcessName: String? = null,
  val holderRuntimeOwnerId: String,
  val holderControllerInstanceId: String? = null,
  val holderDurableControllerId: String? = null,
  val holderServiceInstanceId: String? = null,
  val holderHeartbeatAtEpochMs: Long,
  val holderExpiresAtEpochMs: Long,
)

@Serializable
private data class PersistedRuntimeServiceInterruptedRunRepairProjection(
  val recordedAtEpochMs: Long,
  val scannedSessionIds: List<String> = emptyList(),
  val resumedSessionIds: List<String> = emptyList(),
  val repairedSessionIds: List<String> = emptyList(),
  val repairEvidenceBySession: Map<String, List<PersistedInterruptedRunRepairEvidence>> =
    emptyMap(),
  val nextRepairAfterEpochMs: Long? = null,
  val nextRepairReason: String? = null,
  val requestedRepairReason: String? = null,
)

@Serializable
private data class PersistedInterruptedRunRepairEvidence(
  val sessionId: String? = null,
  val kind: String,
  val target: String,
  val runId: String? = null,
  val taskId: String? = null,
  val detailId: String? = null,
  val repairAfterEpochMs: Long? = null,
  val managedProcessReconnectStatus: String? = null,
  val managedProcessReconnectRecoveryState: String? = null,
  val managedProcessReconnectAttemptCount: Int? = null,
  val runtimeExecutionOwnershipTier: String? = null,
  val durableRuntimeControllerId: String? = null,
  val managedProcessContinuationBasis: String? = null,
  val managedProcessRestoreScope: String? = null,
  val managedProcessRestoreDecision: String? = null,
)

private fun LocalRuntimeServerState.toPersistedRecord(): PersistedLocalRuntimeServerState =
  PersistedLocalRuntimeServerState(
    phase = phase,
    bindAddress = bindAddress,
    requestedPort = requestedPort,
    listeningPort = listeningPort,
    lastStartAttemptAtEpochMs = lastStartAttemptAtEpochMs,
    lastStartedAtEpochMs = lastStartedAtEpochMs,
    failureReason = failureReason,
    changedAtEpochMs = changedAtEpochMs,
  )

private fun RuntimeServiceProcessDescriptor.toPersistedRecord():
  PersistedRuntimeServiceProcessDescriptor = PersistedRuntimeServiceProcessDescriptor(
    packageName = packageName,
    processName = processName,
    expectedProcessName = expectedProcessName,
    expectedProcessSuffix = expectedProcessSuffix,
    isDedicatedRuntimeProcess = isDedicatedRuntimeProcess,
    mismatchReason = mismatchReason,
  )

private fun RuntimeServiceOwnerLease.toPersistedRecord(): PersistedRuntimeServiceOwnerLeaseProjection =
  PersistedRuntimeServiceOwnerLeaseProjection(
    target = target.wireValue,
    phase = phase,
    processStartId = processStartId,
    processStartedAtEpochMs = processStartedAtEpochMs,
    controllerInstanceId = controllerInstanceId,
    durableControllerId = durableControllerId,
    runtimeOwnerId = runtimeOwnerId,
    runtimeControllerId = runtimeControllerId,
    durableRuntimeControllerId = durableRuntimeControllerId,
    serviceInstanceId = serviceInstanceId,
    serviceProcessName = serviceProcessName,
    acquiredAtEpochMs = acquiredAtEpochMs,
    heartbeatAtEpochMs = heartbeatAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
    releasedAtEpochMs = releasedAtEpochMs,
    lastAcquireFailure = lastAcquireFailure?.toPersistedRecord(),
  )

private fun RuntimeServiceOwnerLeaseAcquireFailure.toPersistedRecord():
  PersistedRuntimeServiceOwnerLeaseAcquireFailureProjection =
  PersistedRuntimeServiceOwnerLeaseAcquireFailureProjection(
    target = target.wireValue,
    reason = reason,
    attemptedAtEpochMs = attemptedAtEpochMs,
    attemptedProcessStartId = attemptedProcessStartId,
    attemptedControllerInstanceId = attemptedControllerInstanceId,
    attemptedDurableControllerId = attemptedDurableControllerId,
    attemptedRuntimeOwnerId = attemptedRuntimeOwnerId,
    attemptedRuntimeControllerId = attemptedRuntimeControllerId,
    attemptedDurableRuntimeControllerId = attemptedDurableRuntimeControllerId,
    attemptedServiceInstanceId = attemptedServiceInstanceId,
    attemptedServiceProcessName = attemptedServiceProcessName,
    holderRuntimeOwnerId = holderRuntimeOwnerId,
    holderControllerInstanceId = holderControllerInstanceId,
    holderDurableControllerId = holderDurableControllerId,
    holderServiceInstanceId = holderServiceInstanceId,
    holderHeartbeatAtEpochMs = holderHeartbeatAtEpochMs,
    holderExpiresAtEpochMs = holderExpiresAtEpochMs,
  )

private fun RuntimeServiceInterruptedRunRepairProjection.toPersistedRecord():
  PersistedRuntimeServiceInterruptedRunRepairProjection =
  PersistedRuntimeServiceInterruptedRunRepairProjection(
    recordedAtEpochMs = recordedAtEpochMs,
    scannedSessionIds = scannedSessionIds,
    resumedSessionIds = resumedSessionIds,
    repairedSessionIds = repairedSessionIds,
    repairEvidenceBySession = repairEvidenceBySession.mapValues { entry ->
      entry.value.map { evidence -> evidence.toPersistedRecord() }
    },
    nextRepairAfterEpochMs = nextRepairAfterEpochMs,
    nextRepairReason = nextRepairReason,
    requestedRepairReason = requestedRepairReason,
  )

private fun InterruptedRunRepairEvidence.toPersistedRecord():
  PersistedInterruptedRunRepairEvidence = PersistedInterruptedRunRepairEvidence(
    sessionId = sessionId,
    kind = kind.wireValue,
    target = target.wireValue,
    runId = runId,
    taskId = taskId,
    detailId = detailId,
    repairAfterEpochMs = repairAfterEpochMs,
    managedProcessReconnectStatus = managedProcessReconnectStatus,
    managedProcessReconnectRecoveryState = managedProcessReconnectRecoveryState,
    managedProcessReconnectAttemptCount = managedProcessReconnectAttemptCount,
    runtimeExecutionOwnershipTier = runtimeExecutionOwnershipTier,
    durableRuntimeControllerId = durableRuntimeControllerId,
    managedProcessContinuationBasis = managedProcessContinuationBasis,
    managedProcessRestoreScope = managedProcessRestoreScope,
    managedProcessRestoreDecision = managedProcessRestoreDecision,
  )

private fun PersistedRuntimeServiceInterruptedRunRepairProjection.toSnapshot():
  RuntimeServiceInterruptedRunRepairProjection {
  val evidenceBySession = linkedMapOf<String, List<InterruptedRunRepairEvidence>>()
  repairEvidenceBySession.forEach { (sessionId, evidence) ->
    val decodedEvidence = evidence.mapNotNull { item ->
      item.toSnapshot(defaultSessionId = sessionId)
    }
    if (decodedEvidence.isNotEmpty()) {
      evidenceBySession[sessionId] = decodedEvidence
    }
  }
  return RuntimeServiceInterruptedRunRepairProjection(
    scannedSessionIds = scannedSessionIds,
    resumedSessionIds = resumedSessionIds,
    repairedSessionIds = repairedSessionIds,
    repairEvidenceBySession = evidenceBySession,
    nextRepairAfterEpochMs = nextRepairAfterEpochMs,
    nextRepairReason = nextRepairReason,
    requestedRepairReason = requestedRepairReason,
    recordedAtEpochMs = recordedAtEpochMs,
  )
}

private fun PersistedRuntimeServiceOwnerLeaseProjection.toSnapshot(): RuntimeServiceOwnerLease? {
  val resolvedTarget = RuntimeServiceTarget.fromWireValue(target) ?: return null
  return runCatching {
    RuntimeServiceOwnerLease(
      target = resolvedTarget,
      phase = phase,
      processStartId = processStartId,
      processStartedAtEpochMs = processStartedAtEpochMs,
      controllerInstanceId = controllerInstanceId,
      durableControllerId = durableControllerId,
      runtimeOwnerId = runtimeOwnerId,
      runtimeControllerId = runtimeControllerId,
      durableRuntimeControllerId = durableRuntimeControllerId,
      serviceInstanceId = serviceInstanceId,
      serviceProcessName = serviceProcessName,
      acquiredAtEpochMs = acquiredAtEpochMs,
      heartbeatAtEpochMs = heartbeatAtEpochMs,
      expiresAtEpochMs = expiresAtEpochMs,
      releasedAtEpochMs = releasedAtEpochMs,
      lastAcquireFailure = lastAcquireFailure?.toSnapshot(),
    )
  }.getOrNull()
}

private fun PersistedRuntimeServiceOwnerLeaseAcquireFailureProjection.toSnapshot():
  RuntimeServiceOwnerLeaseAcquireFailure? {
  val resolvedTarget = RuntimeServiceTarget.fromWireValue(target) ?: return null
  return runCatching {
    RuntimeServiceOwnerLeaseAcquireFailure(
      target = resolvedTarget,
      reason = reason,
      attemptedAtEpochMs = attemptedAtEpochMs,
      attemptedProcessStartId = attemptedProcessStartId,
      attemptedControllerInstanceId = attemptedControllerInstanceId,
      attemptedDurableControllerId = attemptedDurableControllerId,
      attemptedRuntimeOwnerId = attemptedRuntimeOwnerId,
      attemptedRuntimeControllerId = attemptedRuntimeControllerId,
      attemptedDurableRuntimeControllerId = attemptedDurableRuntimeControllerId,
      attemptedServiceInstanceId = attemptedServiceInstanceId,
      attemptedServiceProcessName = attemptedServiceProcessName,
      holderRuntimeOwnerId = holderRuntimeOwnerId,
      holderControllerInstanceId = holderControllerInstanceId,
      holderDurableControllerId = holderDurableControllerId,
      holderServiceInstanceId = holderServiceInstanceId,
      holderHeartbeatAtEpochMs = holderHeartbeatAtEpochMs,
      holderExpiresAtEpochMs = holderExpiresAtEpochMs,
    )
  }.getOrNull()
}

private fun PersistedInterruptedRunRepairEvidence.toSnapshot(
  defaultSessionId: String,
): InterruptedRunRepairEvidence? {
  val resolvedSessionId = sessionId
    ?.takeIf(String::isNotBlank)
    ?: defaultSessionId
  if (resolvedSessionId.isBlank()) {
    return null
  }
  val resolvedKind = interruptedRunRepairEvidenceKindFromWireValue(kind) ?: return null
  val resolvedTarget = RuntimeServiceTarget.fromWireValue(target) ?: return null
  return runCatching {
    InterruptedRunRepairEvidence(
      sessionId = resolvedSessionId,
      kind = resolvedKind,
      target = resolvedTarget,
      runId = runId,
      taskId = taskId,
      detailId = detailId,
      repairAfterEpochMs = repairAfterEpochMs,
      managedProcessReconnectStatus = managedProcessReconnectStatus
        ?.trim()
        ?.takeIf(String::isNotBlank),
      managedProcessReconnectRecoveryState = managedProcessReconnectRecoveryState
        ?.trim()
        ?.takeIf(String::isNotBlank),
      managedProcessReconnectAttemptCount = managedProcessReconnectAttemptCount
        ?.takeIf { attempt -> attempt > 0 },
      runtimeExecutionOwnershipTier = runtimeExecutionOwnershipTier
        ?.trim()
        ?.takeIf(String::isNotBlank),
      durableRuntimeControllerId = durableRuntimeControllerId
        ?.trim()
        ?.takeIf(String::isNotBlank),
      managedProcessContinuationBasis = managedProcessContinuationBasis
        ?.trim()
        ?.takeIf(String::isNotBlank),
      managedProcessRestoreScope = managedProcessRestoreScope
        ?.trim()
        ?.takeIf(String::isNotBlank),
      managedProcessRestoreDecision = managedProcessRestoreDecision
        ?.trim()
        ?.takeIf(String::isNotBlank),
    )
  }.getOrNull()
}

private fun PersistedRuntimeServiceProcessDescriptor.toSnapshot():
  RuntimeServiceProcessDescriptor = RuntimeServiceProcessDescriptor(
    packageName = packageName,
    processName = processName,
    expectedProcessName = expectedProcessName,
    expectedProcessSuffix = expectedProcessSuffix,
    isDedicatedRuntimeProcess = isDedicatedRuntimeProcess,
    mismatchReason = mismatchReason,
  )

private fun PersistedLocalRuntimeServerState.toSnapshot(): LocalRuntimeServerState =
  LocalRuntimeServerState(
    phase = phase,
    bindAddress = bindAddress,
    requestedPort = requestedPort,
    listeningPort = listeningPort,
    lastStartAttemptAtEpochMs = lastStartAttemptAtEpochMs,
    lastStartedAtEpochMs = lastStartedAtEpochMs,
    failureReason = failureReason,
    changedAtEpochMs = changedAtEpochMs,
  )
