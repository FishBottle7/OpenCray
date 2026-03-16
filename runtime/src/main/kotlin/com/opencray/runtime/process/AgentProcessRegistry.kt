package com.opencray.runtime.process

import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.Serializable

data class ManagedProcessStartRequest(
  val processId: String,
  val taskId: String,
  val command: String,
  val args: List<String> = emptyList(),
  val workingDirectory: String? = null,
  val timeoutMs: Long = 300_000L,
  val requestedAtEpochMs: Long,
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

data class AgentProcessRegistryConfig(
  val maxTrackedProcesses: Int = 16,
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
}

class InMemoryAgentProcessRegistry(
  private val controllerFactory: ManagedProcessControllerFactory = LocalManagedProcessControllerFactory(),
  private val config: AgentProcessRegistryConfig = AgentProcessRegistryConfig(),
) : AgentProcessRegistry {
  private val lock = Any()
  private val controllersByProcessId = linkedMapOf<String, ManagedProcessController>()

  override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
    val controller = controllerFactory.start(request)
    synchronized(lock) {
      require(request.processId !in controllersByProcessId) {
        "Managed process '${request.processId}' already exists."
      }
      controllersByProcessId[request.processId] = controller
      trimTrackedProcessesLocked()
    }
    return controller.snapshot()
  }

  override fun list(): List<ManagedProcessSnapshot> = synchronized(lock) {
    controllersByProcessId.values.toList()
  }.map(ManagedProcessController::snapshot)
    .sortedByDescending(ManagedProcessSnapshot::startedAtEpochMs)

  override fun read(processId: String): ManagedProcessSnapshot? = synchronized(lock) {
    controllersByProcessId[processId]
  }?.snapshot()

  override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? {
    val controller = synchronized(lock) { controllersByProcessId[processId] } ?: return null
    return controller.await(timeoutMs.coerceAtLeast(0L))
  }

  override fun terminate(processId: String): ManagedProcessSnapshot? {
    val controller = synchronized(lock) { controllersByProcessId[processId] } ?: return null
    return controller.terminate()
  }

  private fun trimTrackedProcessesLocked() {
    while (controllersByProcessId.size > config.maxTrackedProcesses) {
      val removableProcessId = controllersByProcessId.entries
        .firstOrNull { (_, controller) -> controller.snapshot().status.isTerminal }
        ?.key
        ?: controllersByProcessId.keys.firstOrNull()
        ?: return
      controllersByProcessId.remove(removableProcessId)
    }
  }
}

class FileBackedAgentProcessRegistry(
  directory: File,
  private val controllerFactory: ManagedProcessControllerFactory = LocalManagedProcessControllerFactory(),
  private val config: AgentProcessRegistryConfig = AgentProcessRegistryConfig(),
  private val clock: () -> Long = { System.currentTimeMillis() },
) : AgentProcessRegistry {
  private val lock = Any()
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory)
  private val controllersByProcessId = linkedMapOf<String, ManagedProcessController>()

  init {
    synchronized(lock) {
      loadNormalizedRecordLocked()
    }
  }

  override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
    val controller = controllerFactory.start(request)
    val snapshot = controller.snapshot()
    synchronized(lock) {
      val existing = loadNormalizedRecordLocked()
      require(existing.snapshots.none { persisted -> persisted.processId == request.processId }) {
        "Managed process '${request.processId}' already exists."
      }
      controllersByProcessId[request.processId] = controller
      return persistSnapshotsLocked(existing.snapshots + snapshot).first { persisted ->
        persisted.processId == request.processId
      }
    }
  }

  override fun list(): List<ManagedProcessSnapshot> = synchronized(lock) {
    synchronizeLiveSnapshotsLocked().snapshots
  }

  override fun read(processId: String): ManagedProcessSnapshot? = synchronized(lock) {
    synchronizeLiveSnapshotsLocked().snapshots.firstOrNull { snapshot ->
      snapshot.processId == processId
    }
  }

  override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? {
    val controller = synchronized(lock) {
      loadNormalizedRecordLocked()
      controllersByProcessId[processId]
    }
    if (controller == null) {
      return synchronized(lock) {
        loadNormalizedRecordLocked().snapshots.firstOrNull { snapshot ->
          snapshot.processId == processId
        }
      }
    }
    val snapshot = controller.await(timeoutMs.coerceAtLeast(0L))
    return synchronized(lock) {
      val existing = loadNormalizedRecordLocked()
      persistSnapshotsLocked(
        existing.snapshots.filterNot { persisted -> persisted.processId == processId } + snapshot,
      ).firstOrNull { persisted -> persisted.processId == processId }
    }
  }

  override fun terminate(processId: String): ManagedProcessSnapshot? {
    val controller = synchronized(lock) {
      loadNormalizedRecordLocked()
      controllersByProcessId[processId]
    }
    if (controller == null) {
      return synchronized(lock) {
        loadNormalizedRecordLocked().snapshots.firstOrNull { snapshot ->
          snapshot.processId == processId
        }
      }
    }
    val snapshot = controller.terminate()
    return synchronized(lock) {
      val existing = loadNormalizedRecordLocked()
      persistSnapshotsLocked(
        existing.snapshots.filterNot { persisted -> persisted.processId == processId } + snapshot,
      ).firstOrNull { persisted -> persisted.processId == processId }
    }
  }

  private fun synchronizeLiveSnapshotsLocked(): ManagedProcessRegistryRecord {
    val existing = loadNormalizedRecordLocked()
    var changed = false
    val syncedSnapshots = existing.snapshots.map { snapshot ->
      val controller = controllersByProcessId[snapshot.processId] ?: return@map snapshot
      val liveSnapshot = controller.snapshot()
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
    return PersistenceJson.instance.decodeFromString(
      deserializer = ManagedProcessRegistryRecord.serializer(),
      string = encoded,
    )
  }

  private fun loadNormalizedRecordLocked(): ManagedProcessRegistryRecord {
    val existing = loadRecordLocked()
    val normalizedSnapshots = normalizeSnapshots(existing.snapshots)
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
          orderedByProcessId[snapshot.processId] = repairRestoredRunningSnapshot(snapshot)
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
    if (snapshot.status != ManagedProcessStatus.RUNNING || snapshot.processId in controllersByProcessId) {
      return snapshot
    }
    val repairedAt = maxOf(clock(), snapshot.updatedAtEpochMs)
    return snapshot.copy(
      status = ManagedProcessStatus.FAILED,
      errorCode = ERROR_INTERRUPTED_ON_RESTORE,
      errorMessage = "Managed process state was restored without a live controller; marking it interrupted.",
      updatedAtEpochMs = repairedAt,
      finishedAtEpochMs = repairedAt,
      metadata = snapshot.metadata + mapOf(
        "restoredFromDurableStore" to "true",
        "restoredTerminalState" to "interrupted",
      ),
    )
  }

  private fun saveRecordLocked(record: ManagedProcessRegistryRecord) {
    storage.writeText(
      FILE_NAME,
      PersistenceJson.instance.encodeToString(
        serializer = ManagedProcessRegistryRecord.serializer(),
        value = record,
      ),
    )
  }

  private fun synchronizeControllersLocked(retainedProcessIds: Set<String>) {
    controllersByProcessId.keys.retainAll(retainedProcessIds)
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
