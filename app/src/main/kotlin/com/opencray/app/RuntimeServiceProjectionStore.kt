package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
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
)

internal interface RuntimeServiceProjectionStore {
  fun loadSnapshot(): RuntimeServiceProjectionSnapshot?

  fun saveSnapshot(snapshot: RuntimeServiceProjectionSnapshot)

  fun clear()
}

internal fun inMemoryRuntimeServiceProjectionStore(): RuntimeServiceProjectionStore =
  InMemoryRuntimeServiceProjectionStore()

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
      fileName = fileNameForTarget(target),
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

    private fun fileNameForTarget(target: RuntimeServiceTarget): String =
      "runtime-service-projection-${target.wireValue}.json"
  }
}

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
      storage.writeText(
        fileName,
        PersistenceJson.instance.encodeToString(
          serializer = PersistedRuntimeServiceProjectionRecord.serializer(),
          value = snapshot.toPersistedRecord(),
        ),
      )
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(fileName)
    }
  }

  private fun loadRecord(): PersistedRuntimeServiceProjectionRecord? {
    val encoded = storage.readText(fileName).orEmpty().trim()
    if (encoded.isBlank()) {
      return null
    }
    return PersistenceJson.instance.decodeFromString(
      deserializer = PersistedRuntimeServiceProjectionRecord.serializer(),
      string = encoded,
    )
  }
}

private fun RuntimeServiceProjectionSnapshot.toPersistedRecord():
  PersistedRuntimeServiceProjectionRecord = PersistedRuntimeServiceProjectionRecord(
    updatedAtEpochMs = maxOf(
      localRuntimeServerState?.changedAtEpochMs ?: 0L,
      runtimeControllerLifecycle?.controllerCreatedAtEpochMs ?: 0L,
      runtimeOwnerLifecycle.hostCreatedAtEpochMs,
      serviceLifecycle.serviceCreatedAtEpochMs,
      serviceWorkState.changedAtEpochMs,
      serviceKeepAliveState.changedAtEpochMs,
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
