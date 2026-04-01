package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.Serializable

internal data class RuntimeServiceProjectionSnapshot(
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
  fun create(): RuntimeServiceProjectionStore {
    if (!runtimeRootDirectory.exists()) {
      runtimeRootDirectory.mkdirs()
    }
    return FileBackedRuntimeServiceProjectionStore(
      storage = DirectoryDurableTextStorage(runtimeRootDirectory),
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

internal fun OpenCrayRuntimeServiceBridgeSnapshot.toProjectionSnapshot(): RuntimeServiceProjectionSnapshot =
  RuntimeServiceProjectionSnapshot(
    runtimeOwnerLifecycle = runtimeAccess.lifecycleDescriptor,
    runtimeOwnerWorkSummary = runtimeAccess.hostAccess.activeWorkSummary(),
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
) : RuntimeServiceProjectionStore {
  private val lock = Any()

  override fun loadSnapshot(): RuntimeServiceProjectionSnapshot? = synchronized(lock) {
    loadRecord()?.toSnapshot()
  }

  override fun saveSnapshot(snapshot: RuntimeServiceProjectionSnapshot) {
    synchronized(lock) {
      storage.writeText(
        FILE_NAME,
        PersistenceJson.instance.encodeToString(
          serializer = PersistedRuntimeServiceProjectionRecord.serializer(),
          value = snapshot.toPersistedRecord(),
        ),
      )
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(FILE_NAME)
    }
  }

  private fun loadRecord(): PersistedRuntimeServiceProjectionRecord? {
    val encoded = storage.readText(FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return null
    }
    return PersistenceJson.instance.decodeFromString(
      deserializer = PersistedRuntimeServiceProjectionRecord.serializer(),
      string = encoded,
    )
  }

  private companion object {
    const val FILE_NAME: String = "runtime-service-projection.json"
  }
}

private fun RuntimeServiceProjectionSnapshot.toPersistedRecord():
  PersistedRuntimeServiceProjectionRecord = PersistedRuntimeServiceProjectionRecord(
    updatedAtEpochMs = maxOf(
      runtimeOwnerLifecycle.hostCreatedAtEpochMs,
      serviceLifecycle.serviceCreatedAtEpochMs,
      serviceWorkState.changedAtEpochMs,
      serviceKeepAliveState.changedAtEpochMs,
    ),
    runtimeOwnerLifecycle = PersistedHostRuntimeLifecycleDescriptor(
      processStartId = runtimeOwnerLifecycle.processStartId,
      processStartedAtEpochMs = runtimeOwnerLifecycle.processStartedAtEpochMs,
      hostInstanceId = runtimeOwnerLifecycle.hostInstanceId,
      runtimeOwnerId = runtimeOwnerLifecycle.runtimeOwnerId,
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
    runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor(
      processStartId = runtimeOwnerLifecycle.processStartId,
      processStartedAtEpochMs = runtimeOwnerLifecycle.processStartedAtEpochMs,
      hostInstanceId = runtimeOwnerLifecycle.hostInstanceId,
      runtimeOwnerId = runtimeOwnerLifecycle.runtimeOwnerId,
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
  val runtimeOwnerLifecycle: PersistedHostRuntimeLifecycleDescriptor,
  val runtimeOwnerWorkSummary: PersistedRuntimeOwnerWorkSummary,
  val serviceLifecycle: PersistedRuntimeServiceLifecycleDescriptor,
  val serviceWorkState: PersistedRuntimeServiceWorkState,
  val serviceKeepAliveState: PersistedRuntimeServiceKeepAliveState,
)

@Serializable
private data class PersistedHostRuntimeLifecycleDescriptor(
  val processStartId: String,
  val processStartedAtEpochMs: Long,
  val hostInstanceId: String,
  val runtimeOwnerId: String,
  val hostCreatedAtEpochMs: Long,
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
