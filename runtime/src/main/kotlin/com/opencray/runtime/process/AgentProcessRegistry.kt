package com.opencray.runtime.process

import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

data class ManagedProcessStartRequest(
  val processId: String,
  val taskId: String,
  val command: String,
  val args: List<String> = emptyList(),
  val workingDirectory: String? = null,
  val timeoutMs: Long = 300_000L,
  val requestedAtEpochMs: Long,
  val ownerIdentity: ManagedProcessRuntimeIdentity? = null,
  val metadata: Map<String, String> = emptyMap(),
) {
  init {
    require(processId.isNotBlank()) { "ManagedProcessStartRequest processId must not be blank." }
    require(taskId.isNotBlank()) { "ManagedProcessStartRequest taskId must not be blank." }
    require(command.isNotBlank()) { "ManagedProcessStartRequest command must not be blank." }
    require(timeoutMs > 0L) { "ManagedProcessStartRequest timeoutMs must be > 0." }
  }
}

@Serializable
enum class ManagedProcessStatus {
  RUNNING,
  SUCCESS,
  FAILED,
  CANCELLED,
  TIMEOUT,
  SPAWN_ERROR,
  ;

  val isTerminal: Boolean
    get() = this != RUNNING
}

@Serializable
data class ManagedProcessRemoteHandle(
  val provider: String? = null,
  val sandboxId: String? = null,
  val sandboxDomain: String? = null,
  val sessionId: String? = null,
  val commandIdKind: String? = null,
  val commandId: String? = null,
  val providerHandleKind: String? = null,
  val stableSelectorKind: String? = null,
  val stableSelectorValue: String? = null,
  val liveSelectorKind: String? = null,
  val liveSelectorValue: String? = null,
  val remoteWorkspaceRoot: String? = null,
  val remoteWorkingDirectory: String? = null,
  val nativeProtocol: String? = null,
)

@Serializable
data class ManagedProcessObservationState(
  val mode: String? = null,
  val hostEventCount: Long? = null,
  val hostCursor: String? = null,
  val stdoutBytes: Long? = null,
  val stderrBytes: Long? = null,
  val providerMode: String? = null,
  val providerEventCount: Long? = null,
  val providerCursor: String? = null,
  val providerBackfillSupported: Boolean? = null,
  val liveObservationSupported: Boolean? = null,
  val cursorResumeSupported: Boolean? = null,
  val backfillSupported: Boolean? = null,
)

@Serializable
data class ManagedProcessReconnectSeed(
  val source: String? = null,
  val providerObservationSeedConsumed: Boolean? = null,
  val providerObservationSeedState: String? = null,
  val providerObservationSeedConsumedAtEpochMs: Long? = null,
  val hostObservationCursor: String? = null,
  val hostObservationEventCount: Long? = null,
  val stdoutBytes: Long? = null,
  val stderrBytes: Long? = null,
  val providerObservationCursor: String? = null,
  val providerObservationEventCount: Long? = null,
  val providerObservationSeedSource: String? = null,
)

@Serializable
data class ManagedProcessReconnectState(
  val api: String? = null,
  val source: String? = null,
  val status: String? = null,
  val recoveryState: String? = null,
  val resumeMode: String? = null,
  val backfillSupported: Boolean? = null,
  val outputGapRisk: Boolean? = null,
  val retryable: Boolean? = null,
  val retryAfterEpochMs: Long? = null,
  val attemptCount: Int? = null,
  val httpStatusCode: Int? = null,
  val selectorKind: String? = null,
  val selectorValue: String? = null,
  val selectorSource: String? = null,
  val lastAttachedAtEpochMs: Long? = null,
  val lastEventAtEpochMs: Long? = null,
  val lastEventKind: String? = null,
  val lastFailureAtEpochMs: Long? = null,
  val failureStage: String? = null,
  val failureClass: String? = null,
  val failureMessage: String? = null,
  val providerObservationResumeApplied: Boolean? = null,
  val providerObservationResumeReason: String? = null,
  val seed: ManagedProcessReconnectSeed? = null,
)

@Serializable
data class ManagedProcessDeliveredObservationState(
  val mode: String? = null,
  val cursor: String? = null,
  val stdoutBytes: Long? = null,
  val stderrBytes: Long? = null,
  val providerMode: String? = null,
  val providerCursor: String? = null,
  val providerEventCount: Long? = null,
  val deliveredAtEpochMs: Long? = null,
)

@Serializable
data class ManagedProcessRuntimeIdentity(
  val processStartId: String? = null,
  val runtimeControllerId: String? = null,
  val durableRuntimeControllerId: String? = null,
) {
  val isEmpty: Boolean
    get() = processStartId.isNullOrBlank() &&
      runtimeControllerId.isNullOrBlank() &&
      durableRuntimeControllerId.isNullOrBlank()
}

@Serializable
enum class ManagedProcessRestoreMode {
  ACTIVE,
  PROJECTION_ONLY,
}

@Serializable
enum class ManagedProcessRestoreScope {
  SAME_CONTROLLER,
  SAME_PROCESS_NEW_CONTROLLER,
  CROSS_PROCESS,
  UNKNOWN,
  ;

  val wireValue: String
    get() = name.lowercase()

  companion object {
    fun fromWireValue(value: String?): ManagedProcessRestoreScope? =
      entries.firstOrNull { scope -> scope.wireValue == value?.trim()?.lowercase() }
  }
}

@Serializable
enum class ManagedProcessRestoreDecision {
  RECONNECT_ATTEMPTED,
  RECONNECT_DEFERRED,
  LIVE_CONTROLLER_REATTACHED,
  INTERRUPTED_NO_CONTROLLER,
  ;

  val wireValue: String
    get() = name.lowercase()

  companion object {
    fun fromWireValue(value: String?): ManagedProcessRestoreDecision? =
      entries.firstOrNull { decision -> decision.wireValue == value?.trim()?.lowercase() }
  }
}

const val MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY: String = "managedProcessRestoreScope"
const val MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY: String = "managedProcessRestoreDecision"
const val MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY: String =
  "managedProcessRestoreCurrentProcessStartId"
const val MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY: String =
  "managedProcessRestoreCurrentRuntimeControllerId"
const val MANAGED_PROCESS_RESTORE_CURRENT_DURABLE_RUNTIME_CONTROLLER_ID_METADATA_KEY: String =
  "managedProcessRestoreCurrentDurableRuntimeControllerId"

private val MANAGED_PROCESS_RESTORE_METADATA_KEYS: Set<String> = setOf(
  MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY,
  MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY,
  MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY,
  MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY,
  MANAGED_PROCESS_RESTORE_CURRENT_DURABLE_RUNTIME_CONTROLLER_ID_METADATA_KEY,
)

@Serializable
data class ManagedProcessSnapshot(
  val processId: String,
  val taskId: String,
  val command: String,
  val args: List<String> = emptyList(),
  val workingDirectory: String? = null,
  val status: ManagedProcessStatus,
  val processStarted: Boolean,
  val timeoutMs: Long,
  val stdout: String = "",
  val stderr: String = "",
  val exitCode: Int? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
  val startedAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val finishedAtEpochMs: Long? = null,
  val timedOut: Boolean = false,
  val cancelled: Boolean = false,
  val outputLimitExceeded: Boolean = false,
  val remoteHandle: ManagedProcessRemoteHandle? = null,
  val observationState: ManagedProcessObservationState? = null,
  val reconnectState: ManagedProcessReconnectState? = null,
  val deliveredObservationState: ManagedProcessDeliveredObservationState? = null,
  val ownerIdentity: ManagedProcessRuntimeIdentity? = null,
  val metadata: Map<String, String> = emptyMap(),
) {
  init {
    require(processId.isNotBlank()) { "ManagedProcessSnapshot processId must not be blank." }
    require(taskId.isNotBlank()) { "ManagedProcessSnapshot taskId must not be blank." }
    require(command.isNotBlank()) { "ManagedProcessSnapshot command must not be blank." }
    require(timeoutMs > 0L) { "ManagedProcessSnapshot timeoutMs must be > 0." }
    require(updatedAtEpochMs >= startedAtEpochMs) {
      "ManagedProcessSnapshot updatedAtEpochMs must be >= startedAtEpochMs."
    }
    require(finishedAtEpochMs == null || finishedAtEpochMs >= startedAtEpochMs) {
      "ManagedProcessSnapshot finishedAtEpochMs must be >= startedAtEpochMs."
    }
  }
}

interface ManagedProcessController {
  fun snapshot(): ManagedProcessSnapshot

  fun await(timeoutMs: Long): ManagedProcessSnapshot

  fun terminate(): ManagedProcessSnapshot
}

fun interface ManagedProcessControllerFactory {
  fun start(request: ManagedProcessStartRequest): ManagedProcessController
}

interface ReconnectableManagedProcessControllerFactory : ManagedProcessControllerFactory {
  fun reconnect(snapshot: ManagedProcessSnapshot): ManagedProcessController?
}

fun ManagedProcessSnapshot.withNormalizedRemoteState(): ManagedProcessSnapshot {
  val normalizedRemoteHandle = normalizedRemoteHandle()
  val normalizedObservationState = normalizedObservationState()
  val normalizedReconnectState = normalizedReconnectState()
  val normalizedDeliveredObservationState = normalizedDeliveredObservationState()
  val normalizedMetadata = metadata
    .withProjectedReconnectState(normalizedReconnectState)
    .withProjectedDeliveredObservationState(normalizedDeliveredObservationState)
  if (
    normalizedRemoteHandle == remoteHandle &&
    normalizedObservationState == observationState &&
    normalizedReconnectState == reconnectState &&
    normalizedDeliveredObservationState == deliveredObservationState &&
    normalizedMetadata == metadata
  ) {
    return this
  }
  return copy(
    remoteHandle = normalizedRemoteHandle,
    observationState = normalizedObservationState,
    reconnectState = normalizedReconnectState,
    deliveredObservationState = normalizedDeliveredObservationState,
    metadata = normalizedMetadata,
  )
}

fun ManagedProcessSnapshot.normalizedRemoteHandle(): ManagedProcessRemoteHandle? =
  remoteHandle.mergeMissing(inferredRemoteHandleFromMetadata(metadata))

fun ManagedProcessSnapshot.normalizedObservationState(): ManagedProcessObservationState? =
  observationState.mergeMissing(inferredObservationStateFromMetadata(metadata))

fun ManagedProcessSnapshot.normalizedReconnectState(): ManagedProcessReconnectState? =
  reconnectState.mergeMissing(inferredReconnectStateFromMetadata(metadata))

fun ManagedProcessSnapshot.normalizedDeliveredObservationState(): ManagedProcessDeliveredObservationState? =
  deliveredObservationState.mergeMissing(inferredDeliveredObservationStateFromMetadata(metadata))

data class AgentProcessRegistryConfig(
  val maxTrackedProcesses: Int = 64,
) {
  init {
    require(maxTrackedProcesses >= 1) { "AgentProcessRegistryConfig maxTrackedProcesses must be >= 1." }
  }
}

interface AgentProcessRegistry {
  fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot

  fun list(): List<ManagedProcessSnapshot>

  fun read(processId: String): ManagedProcessSnapshot?

  fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot?

  fun terminate(processId: String): ManagedProcessSnapshot?

  fun recordObservationDelivery(
    processId: String,
    deliveredObservationState: ManagedProcessDeliveredObservationState?,
  ) = Unit
}

internal object ManagedProcessControllerRegistry {
  private val lock = Any()
  private val controllersByScopeId =
    linkedMapOf<String, MutableMap<String, ManagedProcessController>>()

  fun register(
    scopeId: String,
    processId: String,
    controller: ManagedProcessController,
  ) {
    synchronized(lock) {
      controllersByScopeId.getOrPut(scopeId) { linkedMapOf() }[processId] = controller
    }
  }

  fun find(
    scopeId: String,
    processId: String,
  ): ManagedProcessController? = synchronized(lock) {
    controllersByScopeId[scopeId]?.get(processId)
  }

  fun retain(
    scopeId: String,
    retainedProcessIds: Set<String>,
  ) {
    synchronized(lock) {
      val scopedControllers = controllersByScopeId[scopeId] ?: return
      scopedControllers.keys.retainAll(retainedProcessIds)
      if (scopedControllers.isEmpty()) {
        controllersByScopeId.remove(scopeId)
      }
    }
  }

  fun clearForTest() {
    synchronized(lock) {
      controllersByScopeId.clear()
    }
  }
}

class InMemoryAgentProcessRegistry(
  private val controllerFactory: ManagedProcessControllerFactory = LocalManagedProcessControllerFactory(),
  private val config: AgentProcessRegistryConfig = AgentProcessRegistryConfig(),
) : AgentProcessRegistry {
  private val lock = Any()
  private val controllersByProcessId = linkedMapOf<String, ManagedProcessController>()
  private val deliveredObservationStatesByProcessId =
    linkedMapOf<String, ManagedProcessDeliveredObservationState>()

  override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
    val controller = controllerFactory.start(request)
    synchronized(lock) {
      require(request.processId !in controllersByProcessId) {
        "Managed process '${request.processId}' already exists."
      }
      controllersByProcessId[request.processId] = controller
      trimTrackedProcessesLocked()
    }
    return snapshotWithPersistedDeliveredObservationState(controller.snapshot())
  }

  override fun list(): List<ManagedProcessSnapshot> = synchronized(lock) {
    controllersByProcessId.values.toList()
  }.map(ManagedProcessController::snapshot)
    .map(::snapshotWithPersistedDeliveredObservationState)
    .sortedByDescending(ManagedProcessSnapshot::startedAtEpochMs)

  override fun read(processId: String): ManagedProcessSnapshot? = synchronized(lock) {
    controllersByProcessId[processId]
  }?.snapshot()?.let(::snapshotWithPersistedDeliveredObservationState)

  override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? {
    val controller = synchronized(lock) { controllersByProcessId[processId] } ?: return null
    return snapshotWithPersistedDeliveredObservationState(controller.await(timeoutMs.coerceAtLeast(0L)))
  }

  override fun terminate(processId: String): ManagedProcessSnapshot? {
    val controller = synchronized(lock) { controllersByProcessId[processId] } ?: return null
    return snapshotWithPersistedDeliveredObservationState(controller.terminate())
  }

  override fun recordObservationDelivery(
    processId: String,
    deliveredObservationState: ManagedProcessDeliveredObservationState?,
  ) {
    synchronized(lock) {
      if (processId !in controllersByProcessId) {
        return
      }
      if (deliveredObservationState == null) {
        deliveredObservationStatesByProcessId.remove(processId)
      } else {
        deliveredObservationStatesByProcessId[processId] = deliveredObservationState
      }
    }
  }

  private fun trimTrackedProcessesLocked() {
    while (controllersByProcessId.size > config.maxTrackedProcesses) {
      val removableProcessId = controllersByProcessId.entries
        .firstOrNull { (_, controller) -> controller.snapshot().status.isTerminal }
        ?.key
        ?: controllersByProcessId.keys.firstOrNull()
        ?: return
      controllersByProcessId.remove(removableProcessId)
      deliveredObservationStatesByProcessId.remove(removableProcessId)
    }
  }

  private fun snapshotWithPersistedDeliveredObservationState(
    snapshot: ManagedProcessSnapshot,
  ): ManagedProcessSnapshot = synchronized(lock) {
    snapshot.withPreservedDeliveredObservationState(
      deliveredObservationStatesByProcessId[snapshot.processId],
    )
  }
}

class FileBackedAgentProcessRegistry(
  private val directory: File,
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory),
  private val controllerFactory: ManagedProcessControllerFactory = LocalManagedProcessControllerFactory(),
  private val config: AgentProcessRegistryConfig = AgentProcessRegistryConfig(),
  private val runtimeIdentity: ManagedProcessRuntimeIdentity = ManagedProcessRuntimeIdentity(),
  private val restoreMode: ManagedProcessRestoreMode = ManagedProcessRestoreMode.ACTIVE,
  private val clock: () -> Long = { System.currentTimeMillis() },
) : AgentProcessRegistry {
  private val lock = Any()
  private val controllerScopeId: String = buildControllerScopeId(
    directory = directory,
    runtimeIdentity = runtimeIdentity,
  )
  private val processScopeId: String? = buildControllerProcessScopeId(
    directory = directory,
    runtimeIdentity = runtimeIdentity,
  )
  private val controllersByProcessId = linkedMapOf<String, ManagedProcessController>()
  private val reconnectableControllerFactory =
    controllerFactory as? ReconnectableManagedProcessControllerFactory

  init {
    synchronizedStoreLocked {
      loadNormalizedRecordLocked()
    }
  }

  override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
    val controller = controllerFactory.start(request)
    val snapshot = controller.snapshot()
    return synchronizedStoreLocked {
      val existing = loadNormalizedRecordLocked()
      require(existing.snapshots.none { persisted -> persisted.processId == request.processId }) {
        "Managed process '${request.processId}' already exists."
      }
      registerControllerLocked(
        processId = request.processId,
        controller = controller,
      )
      persistSnapshotsLocked(existing.snapshots + snapshot).first { persisted ->
        persisted.processId == request.processId
      }
    }
  }

  override fun list(): List<ManagedProcessSnapshot> = synchronizedStoreLocked {
    when (restoreMode) {
      ManagedProcessRestoreMode.ACTIVE -> synchronizeLiveSnapshotsLocked().snapshots
      ManagedProcessRestoreMode.PROJECTION_ONLY -> loadNormalizedRecordLocked().snapshots
    }
  }

  override fun read(processId: String): ManagedProcessSnapshot? = synchronizedStoreLocked {
    when (restoreMode) {
      ManagedProcessRestoreMode.ACTIVE -> synchronizeLiveSnapshotsLocked().snapshots
      ManagedProcessRestoreMode.PROJECTION_ONLY -> loadNormalizedRecordLocked().snapshots
    }.firstOrNull { snapshot ->
      snapshot.processId == processId
    }
  }

  override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? {
    val controllerLookup = synchronizedStoreLocked {
      val existing = loadNormalizedRecordLocked()
      val snapshot = existing.snapshots.firstOrNull { persisted -> persisted.processId == processId }
      snapshot?.let(::controllerForSnapshot)
    }
    if (controllerLookup == null) {
      return synchronizedStoreLocked {
        loadNormalizedRecordLocked().snapshots.firstOrNull { snapshot ->
          snapshot.processId == processId
        }
      }
    }
    val snapshot = controllerLookup.controller.await(timeoutMs.coerceAtLeast(0L))
    return synchronizedStoreLocked {
      val existing = loadNormalizedRecordLocked()
      val persistedSnapshot = existing.snapshots.firstOrNull { persisted ->
        persisted.processId == processId
      }
      persistSnapshotsLocked(
        existing.snapshots.filterNot { persisted -> persisted.processId == processId } +
          preserveDeliveredObservationState(snapshot, persistedSnapshot)
            .withControllerLookupRestoreMetadata(
              persistedSnapshot = persistedSnapshot,
              controllerLookup = controllerLookup,
            ),
      ).firstOrNull { persisted -> persisted.processId == processId }
    }
  }

  override fun terminate(processId: String): ManagedProcessSnapshot? {
    val controllerLookup = synchronizedStoreLocked {
      val existing = loadNormalizedRecordLocked()
      val snapshot = existing.snapshots.firstOrNull { persisted -> persisted.processId == processId }
      snapshot?.let(::controllerForSnapshot)
    }
    if (controllerLookup == null) {
      return synchronizedStoreLocked {
        loadNormalizedRecordLocked().snapshots.firstOrNull { snapshot ->
          snapshot.processId == processId
        }
      }
    }
    val snapshot = controllerLookup.controller.terminate()
    return synchronizedStoreLocked {
      val existing = loadNormalizedRecordLocked()
      val persistedSnapshot = existing.snapshots.firstOrNull { persisted ->
        persisted.processId == processId
      }
      persistSnapshotsLocked(
        existing.snapshots.filterNot { persisted -> persisted.processId == processId } +
          preserveDeliveredObservationState(snapshot, persistedSnapshot)
            .withControllerLookupRestoreMetadata(
              persistedSnapshot = persistedSnapshot,
              controllerLookup = controllerLookup,
            ),
      ).firstOrNull { persisted -> persisted.processId == processId }
    }
  }

  override fun recordObservationDelivery(
    processId: String,
    deliveredObservationState: ManagedProcessDeliveredObservationState?,
  ) {
    synchronizedStoreLocked {
      val existing = loadNormalizedRecordLocked()
      var changed = false
      val updatedSnapshots = existing.snapshots.map { snapshot ->
        if (snapshot.processId != processId) {
          return@map snapshot
        }
        val updatedSnapshot = snapshot.copy(
          deliveredObservationState = deliveredObservationState,
        ).withNormalizedRemoteState()
        changed = changed || updatedSnapshot != snapshot
        updatedSnapshot
      }
      if (changed) {
        persistSnapshotsLocked(updatedSnapshots)
      }
    }
  }

  private fun <T> synchronizedStoreLocked(block: () -> T): T =
    ManagedProcessRegistryStoreLock.withLock(directory) {
      synchronized(lock) {
        block()
      }
    }

  private fun synchronizeLiveSnapshotsLocked(): ManagedProcessRegistryRecord {
    val existing = loadNormalizedRecordLocked()
    var changed = false
    val syncedSnapshots = existing.snapshots.map { snapshot ->
      val controllerLookup = controllerForSnapshot(snapshot) ?: return@map snapshot
      val liveSnapshot = preserveDeliveredObservationState(
        controllerLookup.controller.snapshot(),
        snapshot,
      ).withControllerLookupRestoreMetadata(
        persistedSnapshot = snapshot,
        controllerLookup = controllerLookup,
      )
      if (liveSnapshot != snapshot) {
        changed = true
      }
      liveSnapshot
    }
    return if (!changed) {
      existing
    } else {
      existing.copy(
        recordVersion = existing.recordVersion + 1L,
        updatedAtEpochMs = clock(),
        snapshots = persistSnapshotsLocked(syncedSnapshots),
      )
    }
  }

  private fun loadRecordLocked(): ManagedProcessRegistryRecord {
    val encoded = storage.readText(FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return ManagedProcessRegistryRecord()
    }
    return runCatching {
      PersistenceJson.instance.decodeFromString(
        deserializer = ManagedProcessRegistryRecord.serializer(),
        string = encoded,
      )
    }.getOrDefault(ManagedProcessRegistryRecord())
  }

  private fun loadNormalizedRecordLocked(): ManagedProcessRegistryRecord {
    val existing = loadRecordLocked()
    val normalizedSnapshots = normalizeSnapshots(existing.snapshots)
    if (restoreMode == ManagedProcessRestoreMode.PROJECTION_ONLY) {
      return if (normalizedSnapshots == existing.snapshots) {
        existing
      } else {
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = clock(),
          snapshots = normalizedSnapshots,
        )
      }
    }
    if (normalizedSnapshots == existing.snapshots) {
      synchronizeControllersLocked(normalizedSnapshots.map(ManagedProcessSnapshot::processId).toSet())
      return existing
    }
    val repaired = existing.copy(
      recordVersion = existing.recordVersion + 1L,
      updatedAtEpochMs = clock(),
      snapshots = normalizedSnapshots,
    )
    saveRecordLocked(repaired)
    synchronizeControllersLocked(normalizedSnapshots.map(ManagedProcessSnapshot::processId).toSet())
    return repaired
  }

  private fun persistSnapshotsLocked(
    snapshots: List<ManagedProcessSnapshot>,
  ): List<ManagedProcessSnapshot> {
    val normalizedSnapshots = normalizeSnapshots(snapshots)
    val existing = loadRecordLocked()
    val updated = existing.copy(
      recordVersion = existing.recordVersion + 1L,
      updatedAtEpochMs = clock(),
      snapshots = normalizedSnapshots,
    )
    saveRecordLocked(updated)
    synchronizeControllersLocked(normalizedSnapshots.map(ManagedProcessSnapshot::processId).toSet())
    return normalizedSnapshots
  }

  private fun normalizeSnapshots(
    snapshots: List<ManagedProcessSnapshot>,
  ): List<ManagedProcessSnapshot> {
    val orderedByProcessId = linkedMapOf<String, ManagedProcessSnapshot>()
    snapshots
      .sortedWith(
        compareByDescending<ManagedProcessSnapshot> { snapshot -> snapshot.startedAtEpochMs }
          .thenByDescending { snapshot -> snapshot.updatedAtEpochMs },
      )
      .forEach { snapshot ->
        if (snapshot.processId !in orderedByProcessId) {
          val normalizedSnapshot = if (
            restoreMode == ManagedProcessRestoreMode.ACTIVE &&
            snapshot.status == ManagedProcessStatus.RUNNING
          ) {
            snapshot
          } else {
            snapshot.withNormalizedRemoteState()
          }
          orderedByProcessId[snapshot.processId] = when (restoreMode) {
            ManagedProcessRestoreMode.ACTIVE -> repairRestoredRunningSnapshot(normalizedSnapshot)
            ManagedProcessRestoreMode.PROJECTION_ONLY -> normalizedSnapshot
          }
        }
      }
    val retained = orderedByProcessId.values.toMutableList()
    while (retained.size > config.maxTrackedProcesses) {
      val removableIndex = retained.indexOfLast { snapshot -> snapshot.status.isTerminal }
        .takeIf { index -> index >= 0 }
        ?: retained.lastIndex
      retained.removeAt(removableIndex)
    }
    return retained
  }

  private fun repairRestoredRunningSnapshot(
    snapshot: ManagedProcessSnapshot,
  ): ManagedProcessSnapshot {
    if (snapshot.status != ManagedProcessStatus.RUNNING) {
      return snapshot
    }
    controllerForSnapshot(snapshot)?.let { controllerLookup ->
      return preserveDeliveredObservationState(
        controllerLookup.controller.snapshot(),
        snapshot,
      ).withControllerLookupRestoreMetadata(
        persistedSnapshot = snapshot,
        controllerLookup = controllerLookup,
      )
    }
    if (shouldDeferRetryableReconnect(snapshot)) {
      return snapshot.copy(
        metadata = snapshot.metadata + managedProcessRestoreMetadata(
          snapshot = snapshot,
          runtimeIdentity = runtimeIdentity,
          decision = ManagedProcessRestoreDecision.RECONNECT_DEFERRED,
        ),
      )
    }
    val repairedAt = maxOf(clock(), snapshot.updatedAtEpochMs)
    return snapshot.copy(
      status = ManagedProcessStatus.FAILED,
      errorCode = ERROR_INTERRUPTED_ON_RESTORE,
      errorMessage = "Managed process state was restored without a live controller; marking it interrupted.",
      updatedAtEpochMs = repairedAt,
      finishedAtEpochMs = repairedAt,
      metadata = snapshot.metadata + managedProcessRestoreMetadata(
        snapshot = snapshot,
        runtimeIdentity = runtimeIdentity,
        decision = ManagedProcessRestoreDecision.INTERRUPTED_NO_CONTROLLER,
      ) + mapOf(
        "restoredFromDurableStore" to "true",
        "restoredTerminalState" to "interrupted",
      ),
    )
  }

  private fun saveRecordLocked(record: ManagedProcessRegistryRecord) {
    storage.updateText(FILE_NAME) {
      DurableTextUpdate(
        text = PersistenceJson.instance.encodeToString(
          serializer = ManagedProcessRegistryRecord.serializer(),
          value = record,
        ),
        result = Unit,
      )
    }
  }

  private fun synchronizeControllersLocked(retainedProcessIds: Set<String>) {
    controllersByProcessId.keys.retainAll(retainedProcessIds)
    retainedProcessIds.forEach(::controllerForProcessId)
    ManagedProcessControllerRegistry.retain(
      scopeId = controllerScopeId,
      retainedProcessIds = retainedProcessIds,
    )
    processScopeId?.let { scopeId ->
      ManagedProcessControllerRegistry.retain(
        scopeId = scopeId,
        retainedProcessIds = retainedProcessIds,
      )
    }
  }

  private fun registerControllerLocked(
    processId: String,
    controller: ManagedProcessController,
  ) {
    controllersByProcessId[processId] = controller
    ManagedProcessControllerRegistry.register(
      scopeId = controllerScopeId,
      processId = processId,
      controller = controller,
    )
    processScopeId?.let { scopeId ->
      ManagedProcessControllerRegistry.register(
        scopeId = scopeId,
        processId = processId,
        controller = controller,
      )
    }
  }

  private fun controllerForProcessId(processId: String): ManagedProcessController? =
    controllerForProcessIdLookup(processId)?.controller

  private fun controllerForProcessIdLookup(processId: String): ManagedProcessControllerLookup? =
    controllersByProcessId[processId]?.let { controller ->
      ManagedProcessControllerLookup(controller = controller)
    } ?: ManagedProcessControllerRegistry.find(
      scopeId = controllerScopeId,
      processId = processId,
    )?.also { controller ->
      registerControllerLocked(
        processId = processId,
        controller = controller,
      )
    }?.let { controller ->
      ManagedProcessControllerLookup(controller = controller)
    }

  private fun sameProcessControllerForSnapshot(
    snapshot: ManagedProcessSnapshot,
  ): ManagedProcessControllerLookup? {
    val scopeId = processScopeId ?: return null
    if (reconnectableControllerFactory != null) {
      return null
    }
    if (managedProcessRestoreScope(snapshot, runtimeIdentity) !=
      ManagedProcessRestoreScope.SAME_PROCESS_NEW_CONTROLLER
    ) {
      return null
    }
    val controller = ManagedProcessControllerRegistry.find(
      scopeId = scopeId,
      processId = snapshot.processId,
    ) ?: return null
    registerControllerLocked(
      processId = snapshot.processId,
      controller = controller,
    )
    return ManagedProcessControllerLookup(
      controller = controller,
      restoreDecision = ManagedProcessRestoreDecision.LIVE_CONTROLLER_REATTACHED,
    )
  }

  private fun controllerForSnapshot(
    snapshot: ManagedProcessSnapshot,
  ): ManagedProcessControllerLookup? {
    controllerForProcessIdLookup(snapshot.processId)
      ?.takeUnless { lookup ->
        snapshotRequestsRetryableReconnectReplacement(snapshot) &&
          shouldReplaceRetryableReconnectController(
            persistedSnapshot = snapshot,
            liveSnapshot = lookup.controller.snapshot(),
          )
      }?.let { lookup -> return lookup }
    sameProcessControllerForSnapshot(snapshot)
      ?.takeUnless { lookup ->
        snapshotRequestsRetryableReconnectReplacement(snapshot) &&
          shouldReplaceRetryableReconnectController(
            persistedSnapshot = snapshot,
            liveSnapshot = lookup.controller.snapshot(),
          )
      }?.let { lookup -> return lookup }
    return reconnectControllerForSnapshot(snapshot)
  }

  private fun reconnectRecoveryState(snapshot: ManagedProcessSnapshot): String? =
    snapshot.metadata["sandboxCommandReconnectRecoveryState"]
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotBlank)
      ?: snapshot.reconnectState?.recoveryState
        ?.trim()
        ?.lowercase()
        ?.takeIf(String::isNotBlank)

  private fun reconnectRetryable(snapshot: ManagedProcessSnapshot): Boolean? =
    snapshot.metadata["sandboxCommandReconnectRetryable"]
      ?.trim()
      ?.lowercase()
      ?.toBooleanStrictOrNull()
      ?: snapshot.reconnectState?.retryable

  private fun reconnectRetryAfterEpochMs(snapshot: ManagedProcessSnapshot): Long? =
    snapshot.metadata["sandboxCommandReconnectRetryAfterEpochMs"]
      ?.trim()
      ?.toLongOrNull()
      ?: snapshot.reconnectState?.retryAfterEpochMs

  private fun reconnectAttemptCount(snapshot: ManagedProcessSnapshot): Int? =
    snapshot.metadata["sandboxCommandReconnectAttemptCount"]
      ?.trim()
      ?.toIntOrNull()
      ?: snapshot.reconnectState?.attemptCount

  private fun snapshotRequestsRetryableReconnectReplacement(
    snapshot: ManagedProcessSnapshot,
  ): Boolean {
    val recoveryState = reconnectRecoveryState(snapshot)
    val retryable = reconnectRetryable(snapshot)
    return recoveryState == "retry_scheduled" || (recoveryState == null && retryable == true)
  }

  private fun shouldReplaceRetryableReconnectController(
    persistedSnapshot: ManagedProcessSnapshot,
    liveSnapshot: ManagedProcessSnapshot,
  ): Boolean {
    if (persistedSnapshot.status != ManagedProcessStatus.RUNNING) {
      return false
    }
    if (liveSnapshot.status != ManagedProcessStatus.RUNNING) {
      return false
    }
    val persistedAttemptCount = reconnectAttemptCount(persistedSnapshot)
    val liveAttemptCount = reconnectAttemptCount(liveSnapshot)
    if (
      persistedAttemptCount != null &&
      liveAttemptCount != null &&
      liveAttemptCount > persistedAttemptCount
    ) {
      return false
    }
    if (!snapshotRequestsRetryableReconnectReplacement(liveSnapshot)) {
      return false
    }
    val retryAfterEpochMs = reconnectRetryAfterEpochMs(liveSnapshot) ?: return true
    return clock() >= retryAfterEpochMs
  }

  private fun reconnectControllerForSnapshot(
    snapshot: ManagedProcessSnapshot,
  ): ManagedProcessControllerLookup? {
    if (snapshot.status != ManagedProcessStatus.RUNNING) {
      return null
    }
    if (shouldDeferRetryableReconnect(snapshot)) {
      return null
    }
    val reconnectSnapshot = snapshot.copy(
      metadata = snapshot.metadata + managedProcessRestoreMetadata(
        snapshot = snapshot,
        runtimeIdentity = runtimeIdentity,
        decision = ManagedProcessRestoreDecision.RECONNECT_ATTEMPTED,
      ),
    )
    val controller = reconnectableControllerFactory?.reconnect(reconnectSnapshot) ?: return null
    registerControllerLocked(
      processId = snapshot.processId,
      controller = controller,
    )
    return ManagedProcessControllerLookup(
      controller = controller,
      restoreDecision = ManagedProcessRestoreDecision.RECONNECT_ATTEMPTED,
    )
  }

  private fun shouldDeferRetryableReconnect(
    snapshot: ManagedProcessSnapshot,
  ): Boolean {
    if (!snapshotRequestsRetryableReconnectReplacement(snapshot)) {
      return false
    }
    val retryAfterEpochMs = reconnectRetryAfterEpochMs(snapshot) ?: return false
    return clock() < retryAfterEpochMs
  }

  private fun preserveDeliveredObservationState(
    liveSnapshot: ManagedProcessSnapshot,
    persistedSnapshot: ManagedProcessSnapshot?,
  ): ManagedProcessSnapshot = liveSnapshot.withPreservedDeliveredObservationState(
    persistedSnapshot?.deliveredObservationState,
  )

  private fun ManagedProcessSnapshot.withControllerLookupRestoreMetadata(
    persistedSnapshot: ManagedProcessSnapshot?,
    controllerLookup: ManagedProcessControllerLookup,
  ): ManagedProcessSnapshot {
    val preservedRestoreMetadata = persistedSnapshot
      ?.metadata
      .orEmpty()
      .filterKeys { key -> key in MANAGED_PROCESS_RESTORE_METADATA_KEYS }
    val baseSnapshot = if (preservedRestoreMetadata.isEmpty()) {
      this
    } else {
      copy(metadata = preservedRestoreMetadata + metadata)
    }
    val restoreDecision = controllerLookup.restoreDecision ?: return baseSnapshot
    val restoreSource = persistedSnapshot ?: return baseSnapshot
    return baseSnapshot.copy(
      metadata = baseSnapshot.metadata + managedProcessRestoreMetadata(
        snapshot = restoreSource,
        runtimeIdentity = runtimeIdentity,
        decision = restoreDecision,
      ),
    )
  }

  @Serializable
  private data class ManagedProcessRegistryRecord(
    val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
    val recordVersion: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val snapshots: List<ManagedProcessSnapshot> = emptyList(),
  )

  companion object {
    internal const val FILE_NAME: String = "managed-process-registry.json"
    const val ERROR_INTERRUPTED_ON_RESTORE: String = "PROCESS_INTERRUPTED_ON_RESTORE"
  }
}

private data class ManagedProcessControllerLookup(
  val controller: ManagedProcessController,
  val restoreDecision: ManagedProcessRestoreDecision? = null,
)

private fun buildControllerScopeId(
  directory: File,
  runtimeIdentity: ManagedProcessRuntimeIdentity,
): String {
  val normalizedDirectory = directory.absoluteFile.normalize().path
  val runtimeControllerId = runtimeIdentity.runtimeControllerId
    ?.trim()
    ?.takeIf(String::isNotBlank)
  return if (runtimeControllerId == null) {
    normalizedDirectory
  } else {
    "$normalizedDirectory#$runtimeControllerId"
  }
}

private fun buildControllerProcessScopeId(
  directory: File,
  runtimeIdentity: ManagedProcessRuntimeIdentity,
): String? {
  val processStartId = runtimeIdentity.processStartId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: return null
  val normalizedDirectory = directory.absoluteFile.normalize().path
  return "$normalizedDirectory#processStart:$processStartId"
}

private fun managedProcessRestoreMetadata(
  snapshot: ManagedProcessSnapshot,
  runtimeIdentity: ManagedProcessRuntimeIdentity,
  decision: ManagedProcessRestoreDecision? = null,
): Map<String, String> = buildMap {
  put(
    MANAGED_PROCESS_RESTORE_SCOPE_METADATA_KEY,
    managedProcessRestoreScope(
      snapshot = snapshot,
      runtimeIdentity = runtimeIdentity,
    ).wireValue,
  )
  decision?.let { restoreDecision ->
    put(MANAGED_PROCESS_RESTORE_DECISION_METADATA_KEY, restoreDecision.wireValue)
  }
  runtimeIdentity.processStartId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let { processStartId ->
      put(MANAGED_PROCESS_RESTORE_CURRENT_PROCESS_START_ID_METADATA_KEY, processStartId)
    }
  runtimeIdentity.runtimeControllerId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let { runtimeControllerId ->
      put(MANAGED_PROCESS_RESTORE_CURRENT_RUNTIME_CONTROLLER_ID_METADATA_KEY, runtimeControllerId)
    }
  runtimeIdentity.durableRuntimeControllerId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let { durableRuntimeControllerId ->
      put(
        MANAGED_PROCESS_RESTORE_CURRENT_DURABLE_RUNTIME_CONTROLLER_ID_METADATA_KEY,
        durableRuntimeControllerId,
      )
    }
}

private fun managedProcessRestoreScope(
  snapshot: ManagedProcessSnapshot,
  runtimeIdentity: ManagedProcessRuntimeIdentity,
): ManagedProcessRestoreScope {
  val ownerIdentity = snapshot.ownerIdentity?.takeUnless(ManagedProcessRuntimeIdentity::isEmpty)
    ?: return ManagedProcessRestoreScope.UNKNOWN
  val currentRuntimeControllerId = runtimeIdentity.runtimeControllerId
    ?.trim()
    ?.takeIf(String::isNotBlank)
  val ownerRuntimeControllerId = ownerIdentity.runtimeControllerId
    ?.trim()
    ?.takeIf(String::isNotBlank)
  if (
    currentRuntimeControllerId != null &&
    ownerRuntimeControllerId != null &&
    currentRuntimeControllerId == ownerRuntimeControllerId
  ) {
    return ManagedProcessRestoreScope.SAME_CONTROLLER
  }
  val currentProcessStartId = runtimeIdentity.processStartId
    ?.trim()
    ?.takeIf(String::isNotBlank)
  val ownerProcessStartId = ownerIdentity.processStartId
    ?.trim()
    ?.takeIf(String::isNotBlank)
  if (
    currentProcessStartId != null &&
    ownerProcessStartId != null &&
    currentProcessStartId == ownerProcessStartId
  ) {
    return ManagedProcessRestoreScope.SAME_PROCESS_NEW_CONTROLLER
  }
  if (
    currentProcessStartId != null &&
    ownerProcessStartId != null &&
    currentProcessStartId != ownerProcessStartId
  ) {
    return ManagedProcessRestoreScope.CROSS_PROCESS
  }
  return ManagedProcessRestoreScope.UNKNOWN
}

private object ManagedProcessRegistryStoreLock {
  private val locksByDirectory = ConcurrentHashMap<String, Any>()

  fun <T> withLock(directory: File, block: () -> T): T {
    val normalizedDirectory = directory.absoluteFile.normalize()
    val directoryKey = normalizedDirectory.path
    val processLocalLock = locksByDirectory.computeIfAbsent(directoryKey) { Any() }
    synchronized(processLocalLock) {
      ensureDirectory(normalizedDirectory)
      val lockFile = File(normalizedDirectory, LOCK_FILE_NAME)
      RandomAccessFile(lockFile, "rw").channel.use { channel ->
        channel.lock().use {
          return block()
        }
      }
    }
  }

  private fun ensureDirectory(directory: File) {
    if (directory.exists()) {
      require(directory.isDirectory) {
        "Managed process registry lock path must be a directory: ${directory.path}"
      }
      return
    }
    require(directory.mkdirs()) {
      "Failed to create managed process registry directory: ${directory.path}"
    }
  }

  private const val LOCK_FILE_NAME: String = ".managed-process-registry.lock"
}

private fun inferredRemoteHandleFromMetadata(
  metadata: Map<String, String>,
): ManagedProcessRemoteHandle? {
  val provider = metadata.optionalString("sandboxProvider")
  val sandboxId = metadata.optionalString("sandboxId")
  val sandboxDomain = metadata.optionalString("sandboxDomain")
  val sessionId = metadata.optionalString("sandboxSessionId")
  val commandIdKind = metadata.optionalString("sandboxCommandIdKind")
    ?: metadata.optionalString("sandboxCommandProviderStableSelectorKind")
  val commandId = metadata.optionalString("sandboxCommandId")
    ?: metadata.optionalString("sandboxCommandProviderStableSelectorValue")
  val providerHandleKind = metadata.optionalString("sandboxCommandProviderHandleKind")
  val stableSelectorKind = metadata.optionalString("sandboxCommandProviderStableSelectorKind")
  val stableSelectorValue = metadata.optionalString("sandboxCommandProviderStableSelectorValue")
  val liveSelectorKind = metadata.optionalString("sandboxCommandProviderLiveSelectorKind")
  val liveSelectorValue = metadata.optionalString("sandboxCommandProviderLiveSelectorValue")
  val remoteWorkspaceRoot = metadata.optionalString("remoteWorkspaceRoot")
  val remoteWorkingDirectory = metadata.optionalString("remoteWorkingDirectory")
  val nativeProtocol = metadata.optionalString("sandboxCommandNativeProtocol")
  if (
    provider == null &&
    sandboxId == null &&
    sandboxDomain == null &&
    sessionId == null &&
    commandIdKind == null &&
    commandId == null &&
    providerHandleKind == null &&
    stableSelectorKind == null &&
    stableSelectorValue == null &&
    liveSelectorKind == null &&
    liveSelectorValue == null &&
    remoteWorkspaceRoot == null &&
    remoteWorkingDirectory == null &&
    nativeProtocol == null
  ) {
    return null
  }
  return ManagedProcessRemoteHandle(
    provider = provider,
    sandboxId = sandboxId,
    sandboxDomain = sandboxDomain,
    sessionId = sessionId,
    commandIdKind = commandIdKind,
    commandId = commandId,
    providerHandleKind = providerHandleKind,
    stableSelectorKind = stableSelectorKind,
    stableSelectorValue = stableSelectorValue,
    liveSelectorKind = liveSelectorKind,
    liveSelectorValue = liveSelectorValue,
    remoteWorkspaceRoot = remoteWorkspaceRoot,
    remoteWorkingDirectory = remoteWorkingDirectory,
    nativeProtocol = nativeProtocol,
  )
}

private fun inferredObservationStateFromMetadata(
  metadata: Map<String, String>,
): ManagedProcessObservationState? {
  val mode = metadata.optionalString("sandboxCommandObservationMode")
  val hostEventCount = metadata.optionalLong("sandboxCommandObservationEventCount")
  val hostCursor = metadata.optionalString("sandboxCommandObservationCursor")
  val stdoutBytes = metadata.optionalLong("sandboxCommandObservationStdoutBytes")
  val stderrBytes = metadata.optionalLong("sandboxCommandObservationStderrBytes")
  val providerMode = metadata.optionalString("sandboxCommandProviderObservationMode")
  val providerEventCount = metadata.optionalLong("sandboxCommandProviderObservationEventCount")
  val providerCursor = metadata.optionalString("sandboxCommandProviderObservationCursor")
  val providerBackfillSupported = metadata.optionalBoolean("sandboxCommandProviderObservationBackfillSupported")
  val liveObservationSupported = metadata.optionalBoolean("sandboxCommandSupportsManagedProcessLiveObservation")
  val cursorResumeSupported = metadata.optionalBoolean("sandboxCommandSupportsManagedProcessObservationCursorResume")
  val backfillSupported = metadata.optionalBoolean("sandboxCommandSupportsManagedProcessObservationBackfill")
  if (
    mode == null &&
    hostEventCount == null &&
    hostCursor == null &&
    stdoutBytes == null &&
    stderrBytes == null &&
    providerMode == null &&
    providerEventCount == null &&
    providerCursor == null &&
    providerBackfillSupported == null &&
    liveObservationSupported == null &&
    cursorResumeSupported == null &&
    backfillSupported == null
  ) {
    return null
  }
  return ManagedProcessObservationState(
    mode = mode,
    hostEventCount = hostEventCount,
    hostCursor = hostCursor,
    stdoutBytes = stdoutBytes,
    stderrBytes = stderrBytes,
    providerMode = providerMode,
    providerEventCount = providerEventCount,
    providerCursor = providerCursor,
    providerBackfillSupported = providerBackfillSupported,
    liveObservationSupported = liveObservationSupported,
    cursorResumeSupported = cursorResumeSupported,
    backfillSupported = backfillSupported,
  )
}

private fun inferredReconnectSeedFromMetadata(
  metadata: Map<String, String>,
): ManagedProcessReconnectSeed? {
  val source = metadata.optionalString("sandboxCommandReconnectSeedSource")
  val providerObservationSeedConsumed =
    metadata.optionalBoolean("sandboxCommandReconnectProviderObservationSeedConsumed")
  val providerObservationSeedState =
    metadata.optionalString("sandboxCommandReconnectProviderObservationSeedState")
  val providerObservationSeedConsumedAtEpochMs =
    metadata.optionalLong("sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs")
  val hostObservationCursor = metadata.optionalString("sandboxCommandReconnectSeedObservationCursor")
  val hostObservationEventCount = metadata.optionalLong("sandboxCommandReconnectSeedEventCount")
  val stdoutBytes = metadata.optionalLong("sandboxCommandReconnectSeededStdoutBytes")
  val stderrBytes = metadata.optionalLong("sandboxCommandReconnectSeededStderrBytes")
  val providerObservationCursor =
    metadata.optionalString("sandboxCommandReconnectSeedProviderObservationCursor")
  val providerObservationEventCount =
    metadata.optionalLong("sandboxCommandReconnectSeedProviderObservationEventCount")
  val providerObservationSeedSource =
    metadata.optionalString("sandboxCommandReconnectProviderObservationSeedSource")
  if (
    source == null &&
    providerObservationSeedConsumed == null &&
    providerObservationSeedState == null &&
    providerObservationSeedConsumedAtEpochMs == null &&
    hostObservationCursor == null &&
    hostObservationEventCount == null &&
    stdoutBytes == null &&
    stderrBytes == null &&
    providerObservationCursor == null &&
    providerObservationEventCount == null &&
    providerObservationSeedSource == null
  ) {
    return null
  }
  return ManagedProcessReconnectSeed(
    source = source,
    providerObservationSeedConsumed = providerObservationSeedConsumed,
    providerObservationSeedState = providerObservationSeedState,
    providerObservationSeedConsumedAtEpochMs = providerObservationSeedConsumedAtEpochMs,
    hostObservationCursor = hostObservationCursor,
    hostObservationEventCount = hostObservationEventCount,
    stdoutBytes = stdoutBytes,
    stderrBytes = stderrBytes,
    providerObservationCursor = providerObservationCursor,
    providerObservationEventCount = providerObservationEventCount,
    providerObservationSeedSource = providerObservationSeedSource,
  )
}

private fun inferredReconnectStateFromMetadata(
  metadata: Map<String, String>,
): ManagedProcessReconnectState? {
  val seed = inferredReconnectSeedFromMetadata(metadata)
  val api = metadata.optionalString("sandboxCommandReconnectApi")
  val source = metadata.optionalString("sandboxCommandReconnectSource")
  val status = metadata.optionalString("sandboxCommandReconnectStatus")
  val recoveryState = metadata.optionalString("sandboxCommandReconnectRecoveryState")
  val resumeMode = metadata.optionalString("sandboxCommandReconnectResumeMode")
  val backfillSupported = metadata.optionalBoolean("sandboxCommandReconnectBackfillSupported")
  val outputGapRisk = metadata.optionalBoolean("sandboxCommandReconnectOutputGapRisk")
  val retryable = metadata.optionalBoolean("sandboxCommandReconnectRetryable")
  val retryAfterEpochMs = metadata.optionalLong("sandboxCommandReconnectRetryAfterEpochMs")
  val attemptCount = metadata.optionalInt("sandboxCommandReconnectAttemptCount")
  val httpStatusCode = metadata.optionalInt("sandboxCommandReconnectHttpStatusCode")
  val selectorKind = metadata.optionalString("sandboxCommandReconnectSelectorKind")
  val selectorValue = metadata.optionalString("sandboxCommandReconnectSelectorValue")
  val selectorSource = metadata.optionalString("sandboxCommandReconnectSelectorSource")
  val lastAttachedAtEpochMs = metadata.optionalLong("sandboxCommandReconnectLastAttachedAtEpochMs")
  val lastEventAtEpochMs = metadata.optionalLong("sandboxCommandReconnectLastEventAtEpochMs")
  val lastEventKind = metadata.optionalString("sandboxCommandReconnectLastEventKind")
  val lastFailureAtEpochMs = metadata.optionalLong("sandboxCommandReconnectLastFailureAtEpochMs")
  val failureStage = metadata.optionalString("sandboxCommandReconnectFailureStage")
  val failureClass = metadata.optionalString("sandboxCommandReconnectFailureClass")
  val failureMessage = metadata.optionalString("sandboxCommandReconnectFailureMessage")
  val providerObservationResumeApplied =
    metadata.optionalBoolean("sandboxCommandReconnectProviderObservationResumeApplied")
  val providerObservationResumeReason =
    metadata.optionalString("sandboxCommandReconnectProviderObservationResumeReason")
  if (
    api == null &&
    source == null &&
    status == null &&
    recoveryState == null &&
    resumeMode == null &&
    backfillSupported == null &&
    outputGapRisk == null &&
    retryable == null &&
    retryAfterEpochMs == null &&
    attemptCount == null &&
    httpStatusCode == null &&
    selectorKind == null &&
    selectorValue == null &&
    selectorSource == null &&
    lastAttachedAtEpochMs == null &&
    lastEventAtEpochMs == null &&
    lastEventKind == null &&
    lastFailureAtEpochMs == null &&
    failureStage == null &&
    failureClass == null &&
    failureMessage == null &&
    providerObservationResumeApplied == null &&
    providerObservationResumeReason == null &&
    seed == null
  ) {
    return null
  }
  return ManagedProcessReconnectState(
    api = api,
    source = source,
    status = status,
    recoveryState = recoveryState,
    resumeMode = resumeMode,
    backfillSupported = backfillSupported,
    outputGapRisk = outputGapRisk,
    retryable = retryable,
    retryAfterEpochMs = retryAfterEpochMs,
    attemptCount = attemptCount,
    httpStatusCode = httpStatusCode,
    selectorKind = selectorKind,
    selectorValue = selectorValue,
    selectorSource = selectorSource,
    lastAttachedAtEpochMs = lastAttachedAtEpochMs,
    lastEventAtEpochMs = lastEventAtEpochMs,
    lastEventKind = lastEventKind,
    lastFailureAtEpochMs = lastFailureAtEpochMs,
    failureStage = failureStage,
    failureClass = failureClass,
    failureMessage = failureMessage,
    providerObservationResumeApplied = providerObservationResumeApplied,
    providerObservationResumeReason = providerObservationResumeReason,
    seed = seed,
  )
}

private fun inferredDeliveredObservationStateFromMetadata(
  metadata: Map<String, String>,
): ManagedProcessDeliveredObservationState? {
  val mode = metadata.optionalString("sandboxCommandLastDeliveredObservationMode")
  val cursor = metadata.optionalString("sandboxCommandLastDeliveredObservationCursor")
  val stdoutBytes = metadata.optionalLong("sandboxCommandLastDeliveredStdoutBytes")
  val stderrBytes = metadata.optionalLong("sandboxCommandLastDeliveredStderrBytes")
  val providerMode = metadata.optionalString("sandboxCommandLastDeliveredProviderObservationMode")
  val providerCursor = metadata.optionalString("sandboxCommandLastDeliveredProviderObservationCursor")
  val providerEventCount = metadata.optionalLong("sandboxCommandLastDeliveredProviderObservationEventCount")
  val deliveredAtEpochMs = metadata.optionalLong("sandboxCommandLastDeliveredAtEpochMs")
  if (
    mode == null &&
    cursor == null &&
    stdoutBytes == null &&
    stderrBytes == null &&
    providerMode == null &&
    providerCursor == null &&
    providerEventCount == null &&
    deliveredAtEpochMs == null
  ) {
    return null
  }
  return ManagedProcessDeliveredObservationState(
    mode = mode,
    cursor = cursor,
    stdoutBytes = stdoutBytes,
    stderrBytes = stderrBytes,
    providerMode = providerMode,
    providerCursor = providerCursor,
    providerEventCount = providerEventCount,
    deliveredAtEpochMs = deliveredAtEpochMs,
  )
}

private fun ManagedProcessRemoteHandle?.mergeMissing(
  inferred: ManagedProcessRemoteHandle?,
): ManagedProcessRemoteHandle? {
  if (this == null) {
    return inferred
  }
  if (inferred == null) {
    return this
  }
  return copy(
    provider = provider ?: inferred.provider,
    sandboxId = sandboxId ?: inferred.sandboxId,
    sandboxDomain = sandboxDomain ?: inferred.sandboxDomain,
    sessionId = sessionId ?: inferred.sessionId,
    commandIdKind = commandIdKind ?: inferred.commandIdKind,
    commandId = commandId ?: inferred.commandId,
    providerHandleKind = providerHandleKind ?: inferred.providerHandleKind,
    stableSelectorKind = stableSelectorKind ?: inferred.stableSelectorKind,
    stableSelectorValue = stableSelectorValue ?: inferred.stableSelectorValue,
    liveSelectorKind = liveSelectorKind ?: inferred.liveSelectorKind,
    liveSelectorValue = liveSelectorValue ?: inferred.liveSelectorValue,
    remoteWorkspaceRoot = remoteWorkspaceRoot ?: inferred.remoteWorkspaceRoot,
    remoteWorkingDirectory = remoteWorkingDirectory ?: inferred.remoteWorkingDirectory,
    nativeProtocol = nativeProtocol ?: inferred.nativeProtocol,
  )
}

private fun ManagedProcessObservationState?.mergeMissing(
  inferred: ManagedProcessObservationState?,
): ManagedProcessObservationState? {
  if (this == null) {
    return inferred
  }
  if (inferred == null) {
    return this
  }
  return copy(
    mode = mode ?: inferred.mode,
    hostEventCount = hostEventCount ?: inferred.hostEventCount,
    hostCursor = hostCursor ?: inferred.hostCursor,
    stdoutBytes = stdoutBytes ?: inferred.stdoutBytes,
    stderrBytes = stderrBytes ?: inferred.stderrBytes,
    providerMode = providerMode ?: inferred.providerMode,
    providerEventCount = providerEventCount ?: inferred.providerEventCount,
    providerCursor = providerCursor ?: inferred.providerCursor,
    providerBackfillSupported = providerBackfillSupported ?: inferred.providerBackfillSupported,
    liveObservationSupported = liveObservationSupported ?: inferred.liveObservationSupported,
    cursorResumeSupported = cursorResumeSupported ?: inferred.cursorResumeSupported,
    backfillSupported = backfillSupported ?: inferred.backfillSupported,
  )
}

private fun ManagedProcessReconnectSeed?.mergeMissing(
  inferred: ManagedProcessReconnectSeed?,
): ManagedProcessReconnectSeed? {
  if (this == null) {
    return inferred
  }
  if (inferred == null) {
    return this
  }
  return copy(
    source = source ?: inferred.source,
    providerObservationSeedConsumed =
      providerObservationSeedConsumed ?: inferred.providerObservationSeedConsumed,
    providerObservationSeedState = providerObservationSeedState ?: inferred.providerObservationSeedState,
    providerObservationSeedConsumedAtEpochMs =
      providerObservationSeedConsumedAtEpochMs ?: inferred.providerObservationSeedConsumedAtEpochMs,
    hostObservationCursor = hostObservationCursor ?: inferred.hostObservationCursor,
    hostObservationEventCount = hostObservationEventCount ?: inferred.hostObservationEventCount,
    stdoutBytes = stdoutBytes ?: inferred.stdoutBytes,
    stderrBytes = stderrBytes ?: inferred.stderrBytes,
    providerObservationCursor = providerObservationCursor ?: inferred.providerObservationCursor,
    providerObservationEventCount = providerObservationEventCount ?: inferred.providerObservationEventCount,
    providerObservationSeedSource =
      providerObservationSeedSource ?: inferred.providerObservationSeedSource,
  )
}

private fun ManagedProcessReconnectState?.mergeMissing(
  inferred: ManagedProcessReconnectState?,
): ManagedProcessReconnectState? {
  if (this == null) {
    return inferred
  }
  if (inferred == null) {
    return this
  }
  return copy(
    api = api ?: inferred.api,
    source = source ?: inferred.source,
    status = status ?: inferred.status,
    recoveryState = recoveryState ?: inferred.recoveryState,
    resumeMode = resumeMode ?: inferred.resumeMode,
    backfillSupported = backfillSupported ?: inferred.backfillSupported,
    outputGapRisk = outputGapRisk ?: inferred.outputGapRisk,
    retryable = retryable ?: inferred.retryable,
    retryAfterEpochMs = retryAfterEpochMs ?: inferred.retryAfterEpochMs,
    attemptCount = attemptCount ?: inferred.attemptCount,
    httpStatusCode = httpStatusCode ?: inferred.httpStatusCode,
    selectorKind = selectorKind ?: inferred.selectorKind,
    selectorValue = selectorValue ?: inferred.selectorValue,
    selectorSource = selectorSource ?: inferred.selectorSource,
    lastAttachedAtEpochMs = lastAttachedAtEpochMs ?: inferred.lastAttachedAtEpochMs,
    lastEventAtEpochMs = lastEventAtEpochMs ?: inferred.lastEventAtEpochMs,
    lastEventKind = lastEventKind ?: inferred.lastEventKind,
    lastFailureAtEpochMs = lastFailureAtEpochMs ?: inferred.lastFailureAtEpochMs,
    failureStage = failureStage ?: inferred.failureStage,
    failureClass = failureClass ?: inferred.failureClass,
    failureMessage = failureMessage ?: inferred.failureMessage,
    providerObservationResumeApplied =
      providerObservationResumeApplied ?: inferred.providerObservationResumeApplied,
    providerObservationResumeReason =
      providerObservationResumeReason ?: inferred.providerObservationResumeReason,
    seed = seed.mergeMissing(inferred.seed),
  )
}

private fun ManagedProcessDeliveredObservationState?.mergeMissing(
  inferred: ManagedProcessDeliveredObservationState?,
): ManagedProcessDeliveredObservationState? {
  if (this == null) {
    return inferred
  }
  if (inferred == null) {
    return this
  }
  return copy(
    mode = mode ?: inferred.mode,
    cursor = cursor ?: inferred.cursor,
    stdoutBytes = stdoutBytes ?: inferred.stdoutBytes,
    stderrBytes = stderrBytes ?: inferred.stderrBytes,
    providerMode = providerMode ?: inferred.providerMode,
    providerCursor = providerCursor ?: inferred.providerCursor,
    providerEventCount = providerEventCount ?: inferred.providerEventCount,
    deliveredAtEpochMs = deliveredAtEpochMs ?: inferred.deliveredAtEpochMs,
  )
}

private fun ManagedProcessSnapshot.withPreservedDeliveredObservationState(
  persisted: ManagedProcessDeliveredObservationState?,
): ManagedProcessSnapshot {
  val mergedState = deliveredObservationState.mergeMissing(persisted)
  if (mergedState == deliveredObservationState) {
    return this
  }
  return copy(deliveredObservationState = mergedState).withNormalizedRemoteState()
}

private fun Map<String, String>.withProjectedDeliveredObservationState(
  deliveredObservationState: ManagedProcessDeliveredObservationState?,
): Map<String, String> {
  val projected = LinkedHashMap(this)
  MANAGED_PROCESS_DELIVERED_OBSERVATION_METADATA_KEYS.forEach(projected::remove)
  deliveredObservationState?.mode?.let { mode ->
    projected["sandboxCommandLastDeliveredObservationMode"] = mode
  }
  deliveredObservationState?.cursor?.let { cursor ->
    projected["sandboxCommandLastDeliveredObservationCursor"] = cursor
  }
  deliveredObservationState?.stdoutBytes?.let { stdoutBytes ->
    projected["sandboxCommandLastDeliveredStdoutBytes"] = stdoutBytes.toString()
  }
  deliveredObservationState?.stderrBytes?.let { stderrBytes ->
    projected["sandboxCommandLastDeliveredStderrBytes"] = stderrBytes.toString()
  }
  deliveredObservationState?.providerMode?.let { providerMode ->
    projected["sandboxCommandLastDeliveredProviderObservationMode"] = providerMode
  }
  deliveredObservationState?.providerCursor?.let { providerCursor ->
    projected["sandboxCommandLastDeliveredProviderObservationCursor"] = providerCursor
  }
  deliveredObservationState?.providerEventCount?.let { providerEventCount ->
    projected["sandboxCommandLastDeliveredProviderObservationEventCount"] =
      providerEventCount.toString()
  }
  deliveredObservationState?.deliveredAtEpochMs?.let { deliveredAtEpochMs ->
    projected["sandboxCommandLastDeliveredAtEpochMs"] = deliveredAtEpochMs.toString()
  }
  return projected
}

private fun Map<String, String>.withProjectedReconnectState(
  reconnectState: ManagedProcessReconnectState?,
): Map<String, String> {
  val projected = LinkedHashMap(this)
  MANAGED_PROCESS_RECONNECT_METADATA_KEYS.forEach(projected::remove)
  reconnectState?.api?.let { api ->
    projected["sandboxCommandReconnectApi"] = api
  }
  reconnectState?.source?.let { source ->
    projected["sandboxCommandReconnectSource"] = source
  }
  reconnectState?.status?.let { status ->
    projected["sandboxCommandReconnectStatus"] = status
  }
  reconnectState?.recoveryState?.let { recoveryState ->
    projected["sandboxCommandReconnectRecoveryState"] = recoveryState
  }
  reconnectState?.resumeMode?.let { resumeMode ->
    projected["sandboxCommandReconnectResumeMode"] = resumeMode
  }
  reconnectState?.backfillSupported?.let { backfillSupported ->
    projected["sandboxCommandReconnectBackfillSupported"] = backfillSupported.toString()
  }
  reconnectState?.outputGapRisk?.let { outputGapRisk ->
    projected["sandboxCommandReconnectOutputGapRisk"] = outputGapRisk.toString()
  }
  reconnectState?.retryable?.let { retryable ->
    projected["sandboxCommandReconnectRetryable"] = retryable.toString()
  }
  reconnectState?.retryAfterEpochMs?.let { retryAfterEpochMs ->
    projected["sandboxCommandReconnectRetryAfterEpochMs"] = retryAfterEpochMs.toString()
  }
  reconnectState?.attemptCount?.let { attemptCount ->
    projected["sandboxCommandReconnectAttemptCount"] = attemptCount.toString()
  }
  reconnectState?.httpStatusCode?.let { httpStatusCode ->
    projected["sandboxCommandReconnectHttpStatusCode"] = httpStatusCode.toString()
  }
  reconnectState?.selectorKind?.let { selectorKind ->
    projected["sandboxCommandReconnectSelectorKind"] = selectorKind
  }
  reconnectState?.selectorValue?.let { selectorValue ->
    projected["sandboxCommandReconnectSelectorValue"] = selectorValue
  }
  reconnectState?.selectorSource?.let { selectorSource ->
    projected["sandboxCommandReconnectSelectorSource"] = selectorSource
  }
  reconnectState?.lastAttachedAtEpochMs?.let { lastAttachedAtEpochMs ->
    projected["sandboxCommandReconnectLastAttachedAtEpochMs"] = lastAttachedAtEpochMs.toString()
  }
  reconnectState?.lastEventAtEpochMs?.let { lastEventAtEpochMs ->
    projected["sandboxCommandReconnectLastEventAtEpochMs"] = lastEventAtEpochMs.toString()
  }
  reconnectState?.lastEventKind?.let { lastEventKind ->
    projected["sandboxCommandReconnectLastEventKind"] = lastEventKind
  }
  reconnectState?.lastFailureAtEpochMs?.let { lastFailureAtEpochMs ->
    projected["sandboxCommandReconnectLastFailureAtEpochMs"] = lastFailureAtEpochMs.toString()
  }
  reconnectState?.failureStage?.let { failureStage ->
    projected["sandboxCommandReconnectFailureStage"] = failureStage
  }
  reconnectState?.failureClass?.let { failureClass ->
    projected["sandboxCommandReconnectFailureClass"] = failureClass
  }
  reconnectState?.failureMessage?.let { failureMessage ->
    projected["sandboxCommandReconnectFailureMessage"] = failureMessage
  }
  reconnectState?.providerObservationResumeApplied?.let { providerObservationResumeApplied ->
    projected["sandboxCommandReconnectProviderObservationResumeApplied"] =
      providerObservationResumeApplied.toString()
  }
  reconnectState?.providerObservationResumeReason?.let { providerObservationResumeReason ->
    projected["sandboxCommandReconnectProviderObservationResumeReason"] =
      providerObservationResumeReason
  }
  reconnectState?.seed?.source?.let { source ->
    projected["sandboxCommandReconnectSeedSource"] = source
  }
  reconnectState?.seed?.providerObservationSeedConsumed?.let { providerObservationSeedConsumed ->
    projected["sandboxCommandReconnectProviderObservationSeedConsumed"] =
      providerObservationSeedConsumed.toString()
  }
  reconnectState?.seed?.providerObservationSeedState?.let { providerObservationSeedState ->
    projected["sandboxCommandReconnectProviderObservationSeedState"] = providerObservationSeedState
  }
  reconnectState?.seed?.providerObservationSeedConsumedAtEpochMs?.let { consumedAtEpochMs ->
    projected["sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs"] =
      consumedAtEpochMs.toString()
  }
  reconnectState?.seed?.hostObservationCursor?.let { hostObservationCursor ->
    projected["sandboxCommandReconnectSeedObservationCursor"] = hostObservationCursor
  }
  reconnectState?.seed?.hostObservationEventCount?.let { hostObservationEventCount ->
    projected["sandboxCommandReconnectSeedEventCount"] = hostObservationEventCount.toString()
  }
  reconnectState?.seed?.stdoutBytes?.let { stdoutBytes ->
    projected["sandboxCommandReconnectSeededStdoutBytes"] = stdoutBytes.toString()
  }
  reconnectState?.seed?.stderrBytes?.let { stderrBytes ->
    projected["sandboxCommandReconnectSeededStderrBytes"] = stderrBytes.toString()
  }
  reconnectState?.seed?.providerObservationCursor?.let { providerObservationCursor ->
    projected["sandboxCommandReconnectSeedProviderObservationCursor"] = providerObservationCursor
  }
  reconnectState?.seed?.providerObservationEventCount?.let { providerObservationEventCount ->
    projected["sandboxCommandReconnectSeedProviderObservationEventCount"] =
      providerObservationEventCount.toString()
  }
  reconnectState?.seed?.providerObservationSeedSource?.let { providerObservationSeedSource ->
    projected["sandboxCommandReconnectProviderObservationSeedSource"] =
      providerObservationSeedSource
  }
  return projected
}

private val MANAGED_PROCESS_DELIVERED_OBSERVATION_METADATA_KEYS: Set<String> = setOf(
  "sandboxCommandLastDeliveredObservationMode",
  "sandboxCommandLastDeliveredObservationCursor",
  "sandboxCommandLastDeliveredStdoutBytes",
  "sandboxCommandLastDeliveredStderrBytes",
  "sandboxCommandLastDeliveredProviderObservationMode",
  "sandboxCommandLastDeliveredProviderObservationCursor",
  "sandboxCommandLastDeliveredProviderObservationEventCount",
  "sandboxCommandLastDeliveredAtEpochMs",
)

private val MANAGED_PROCESS_RECONNECT_METADATA_KEYS: Set<String> = setOf(
  "sandboxCommandReconnectApi",
  "sandboxCommandReconnectSource",
  "sandboxCommandReconnectStatus",
  "sandboxCommandReconnectRecoveryState",
  "sandboxCommandReconnectResumeMode",
  "sandboxCommandReconnectBackfillSupported",
  "sandboxCommandReconnectOutputGapRisk",
  "sandboxCommandReconnectRetryable",
  "sandboxCommandReconnectRetryAfterEpochMs",
  "sandboxCommandReconnectAttemptCount",
  "sandboxCommandReconnectHttpStatusCode",
  "sandboxCommandReconnectSelectorKind",
  "sandboxCommandReconnectSelectorValue",
  "sandboxCommandReconnectSelectorSource",
  "sandboxCommandReconnectLastAttachedAtEpochMs",
  "sandboxCommandReconnectLastEventAtEpochMs",
  "sandboxCommandReconnectLastEventKind",
  "sandboxCommandReconnectLastFailureAtEpochMs",
  "sandboxCommandReconnectFailureStage",
  "sandboxCommandReconnectFailureClass",
  "sandboxCommandReconnectFailureMessage",
  "sandboxCommandReconnectSeedSource",
  "sandboxCommandReconnectProviderObservationSeedConsumed",
  "sandboxCommandReconnectProviderObservationSeedState",
  "sandboxCommandReconnectProviderObservationSeedSource",
  "sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs",
  "sandboxCommandReconnectSeedObservationCursor",
  "sandboxCommandReconnectSeedEventCount",
  "sandboxCommandReconnectSeededStdoutBytes",
  "sandboxCommandReconnectSeededStderrBytes",
  "sandboxCommandReconnectSeedProviderObservationCursor",
  "sandboxCommandReconnectSeedProviderObservationEventCount",
  "sandboxCommandReconnectProviderObservationResumeApplied",
  "sandboxCommandReconnectProviderObservationResumeReason",
)

private fun Map<String, String>.optionalString(key: String): String? =
  get(key)?.trim()?.takeIf(String::isNotBlank)

private fun Map<String, String>.optionalLong(key: String): Long? =
  optionalString(key)?.toLongOrNull()

private fun Map<String, String>.optionalInt(key: String): Int? =
  optionalString(key)?.toIntOrNull()

private fun Map<String, String>.optionalBoolean(key: String): Boolean? =
  optionalString(key)?.toBooleanStrictOrNull()
