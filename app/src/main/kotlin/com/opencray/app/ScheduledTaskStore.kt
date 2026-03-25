package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal interface ScheduledTaskSpecStoreFactory {
  fun create(): ScheduledTaskSpecStore
}

internal interface ScheduledTaskSpecStore {
  fun list(): List<ScheduledTaskSpec>

  fun listEnabled(): List<ScheduledTaskSpec>

  fun get(scheduleId: String): ScheduledTaskSpec?

  fun upsert(spec: ScheduledTaskSpec)

  fun remove(scheduleId: String)

  fun clear()
}

internal interface ScheduledTaskRunRecordStoreFactory {
  fun create(): ScheduledTaskRunRecordStore
}

internal interface ScheduledTaskRunRecordStore {
  fun list(): List<ScheduledTaskRunRecord>

  fun listForSchedule(scheduleId: String): List<ScheduledTaskRunRecord>

  fun get(scheduleRunId: String): ScheduledTaskRunRecord?

  fun upsert(record: ScheduledTaskRunRecord)

  fun clear()
}

internal fun inMemoryScheduledTaskSpecStoreFactory(): ScheduledTaskSpecStoreFactory =
  InMemoryScheduledTaskSpecStoreFactory()

internal fun inMemoryScheduledTaskRunRecordStoreFactory(): ScheduledTaskRunRecordStoreFactory =
  InMemoryScheduledTaskRunRecordStoreFactory()

internal class InMemoryScheduledTaskSpecStoreFactory : ScheduledTaskSpecStoreFactory {
  private val store = InMemoryScheduledTaskSpecStore()

  override fun create(): ScheduledTaskSpecStore = store
}

internal class InMemoryScheduledTaskRunRecordStoreFactory : ScheduledTaskRunRecordStoreFactory {
  private val store = InMemoryScheduledTaskRunRecordStore()

  override fun create(): ScheduledTaskRunRecordStore = store
}

internal class FileBackedScheduledTaskSpecStoreFactory(
  private val runtimeRootDirectory: File,
  private val config: ScheduledTaskSpecStoreConfig = ScheduledTaskSpecStoreConfig(),
) : ScheduledTaskSpecStoreFactory {
  override fun create(): ScheduledTaskSpecStore {
    if (!runtimeRootDirectory.exists()) {
      runtimeRootDirectory.mkdirs()
    }
    return FileBackedScheduledTaskSpecStore(
      storage = DirectoryDurableTextStorage(runtimeRootDirectory),
      config = config,
    )
  }

  companion object {
    fun fromContext(context: Context): ScheduledTaskSpecStoreFactory =
      FileBackedScheduledTaskSpecStoreFactory(
        runtimeRootDirectory = File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )
  }
}

internal class FileBackedScheduledTaskRunRecordStoreFactory(
  private val runtimeRootDirectory: File,
  private val config: ScheduledTaskRunRecordStoreConfig = ScheduledTaskRunRecordStoreConfig(),
) : ScheduledTaskRunRecordStoreFactory {
  override fun create(): ScheduledTaskRunRecordStore {
    if (!runtimeRootDirectory.exists()) {
      runtimeRootDirectory.mkdirs()
    }
    return FileBackedScheduledTaskRunRecordStore(
      storage = DirectoryDurableTextStorage(runtimeRootDirectory),
      config = config,
    )
  }

  companion object {
    fun fromContext(context: Context): ScheduledTaskRunRecordStoreFactory =
      FileBackedScheduledTaskRunRecordStoreFactory(
        runtimeRootDirectory = File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )
  }
}

internal data class ScheduledTaskSpecStoreConfig(
  val maxTrackedSpecs: Int = 256,
) {
  init {
    require(maxTrackedSpecs >= 1) { "ScheduledTaskSpecStoreConfig maxTrackedSpecs must be >= 1." }
  }
}

internal data class ScheduledTaskRunRecordStoreConfig(
  val maxTrackedRecords: Int = 1024,
) {
  init {
    require(maxTrackedRecords >= 1) {
      "ScheduledTaskRunRecordStoreConfig maxTrackedRecords must be >= 1."
    }
  }
}

@Serializable
internal data class ScheduledTaskSpec(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val scheduleId: String,
  val sessionId: String,
  val title: String,
  val enabled: Boolean,
  val trigger: ScheduledTrigger,
  val payload: ScheduledTaskPayload,
  val policy: ScheduledTaskPolicy = ScheduledTaskPolicy(),
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
) {
  init {
    require(scheduleId.isNotBlank()) { "ScheduledTaskSpec scheduleId must not be blank." }
    require(sessionId.isNotBlank()) { "ScheduledTaskSpec sessionId must not be blank." }
    require(title.isNotBlank()) { "ScheduledTaskSpec title must not be blank." }
    require(updatedAtEpochMs >= createdAtEpochMs) {
      "ScheduledTaskSpec updatedAtEpochMs must be >= createdAtEpochMs."
    }
  }
}

@Serializable
internal sealed class ScheduledTrigger {
  @Serializable
  @SerialName("run_at_timestamp")
  data class RunAtTimestamp(
    val triggerAtEpochMs: Long,
  ) : ScheduledTrigger() {
    init {
      require(triggerAtEpochMs >= 0L) {
        "ScheduledTrigger.RunAtTimestamp triggerAtEpochMs must be >= 0."
      }
    }
  }

  @Serializable
  @SerialName("run_after_delay")
  data class RunAfterDelay(
    val delayMs: Long,
    val createdAtEpochMs: Long,
  ) : ScheduledTrigger() {
    init {
      require(delayMs >= 1L) { "ScheduledTrigger.RunAfterDelay delayMs must be >= 1." }
      require(createdAtEpochMs >= 0L) {
        "ScheduledTrigger.RunAfterDelay createdAtEpochMs must be >= 0."
      }
    }
  }

  @Serializable
  @SerialName("periodic")
  data class Periodic(
    val intervalMs: Long,
    val flexMs: Long? = null,
    val anchorEpochMs: Long? = null,
  ) : ScheduledTrigger() {
    init {
      require(intervalMs >= 1L) { "ScheduledTrigger.Periodic intervalMs must be >= 1." }
      require(flexMs == null || flexMs >= 0L) {
        "ScheduledTrigger.Periodic flexMs must be >= 0."
      }
      require(anchorEpochMs == null || anchorEpochMs >= 0L) {
        "ScheduledTrigger.Periodic anchorEpochMs must be >= 0."
      }
    }
  }
}

@Serializable
internal data class ScheduledTaskPayload(
  val prompt: String,
  val workingDirectory: String? = null,
  val attachmentRelativePaths: List<String> = emptyList(),
  val variables: Map<String, String> = emptyMap(),
) {
  init {
    require(prompt.isNotBlank()) { "ScheduledTaskPayload prompt must not be blank." }
  }
}

@Serializable
internal data class ScheduledTaskPolicy(
  val conflictPolicy: ScheduledConflictPolicy = ScheduledConflictPolicy.ENQUEUE_NEW_RUN,
  val requiresForegroundNotification: Boolean = true,
  val notifyOnQueued: Boolean = false,
  val notifyOnApproval: Boolean = true,
  val notifyOnCompletion: Boolean = true,
  val notifyOnInterruption: Boolean = true,
)

@Serializable
internal enum class ScheduledConflictPolicy {
  ENQUEUE_NEW_RUN,
  SKIP_IF_SESSION_BUSY,
  CANCEL_OLDER_WAITING_TRIGGER,
}

@Serializable
internal data class ScheduledTaskRunRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val scheduleRunId: String,
  val scheduleId: String,
  val sessionId: String,
  val triggerReason: String,
  val triggeredAtEpochMs: Long,
  val acceptedAtEpochMs: Long? = null,
  val createdRunId: String? = null,
  val createdTaskId: String? = null,
  val result: ScheduledTaskRunResult,
  val failureReason: String? = null,
  val recoverySource: String? = null,
  val updatedAtEpochMs: Long = acceptedAtEpochMs ?: triggeredAtEpochMs,
) {
  init {
    require(scheduleRunId.isNotBlank()) { "ScheduledTaskRunRecord scheduleRunId must not be blank." }
    require(scheduleId.isNotBlank()) { "ScheduledTaskRunRecord scheduleId must not be blank." }
    require(sessionId.isNotBlank()) { "ScheduledTaskRunRecord sessionId must not be blank." }
    require(triggerReason.isNotBlank()) { "ScheduledTaskRunRecord triggerReason must not be blank." }
    require(triggeredAtEpochMs >= 0L) {
      "ScheduledTaskRunRecord triggeredAtEpochMs must be >= 0."
    }
    require(acceptedAtEpochMs == null || acceptedAtEpochMs >= triggeredAtEpochMs) {
      "ScheduledTaskRunRecord acceptedAtEpochMs must be >= triggeredAtEpochMs."
    }
    require(updatedAtEpochMs >= triggeredAtEpochMs) {
      "ScheduledTaskRunRecord updatedAtEpochMs must be >= triggeredAtEpochMs."
    }
  }
}

@Serializable
internal enum class ScheduledTaskRunResult {
  TRIGGERED,
  ACCEPTED,
  WAITING_APPROVAL,
  COMPLETED_SUCCESS,
  COMPLETED_CANCELLED,
  COMPLETED_FAILED,
  COMPLETED_INTERRUPTED,
  SKIPPED_DUPLICATE,
  SKIPPED_SESSION_BUSY,
  FAILED_DISABLED,
  FAILED_MISSING_SPEC,
  FAILED_MISSING_SESSION,
  FAILED_SESSION_MISMATCH,
  FAILED_DISPATCH,
}

private class InMemoryScheduledTaskSpecStore : ScheduledTaskSpecStore {
  private val lock = Any()
  private val specsById = linkedMapOf<String, ScheduledTaskSpec>()

  override fun list(): List<ScheduledTaskSpec> = synchronized(lock) {
    specsById.values
      .sortedByDescending(ScheduledTaskSpec::updatedAtEpochMs)
  }

  override fun listEnabled(): List<ScheduledTaskSpec> = synchronized(lock) {
    list().filter(ScheduledTaskSpec::enabled)
  }

  override fun get(scheduleId: String): ScheduledTaskSpec? = synchronized(lock) {
    specsById[scheduleId]
  }

  override fun upsert(spec: ScheduledTaskSpec) {
    synchronized(lock) {
      specsById[spec.scheduleId] = spec
    }
  }

  override fun remove(scheduleId: String) {
    synchronized(lock) {
      specsById.remove(scheduleId)
    }
  }

  override fun clear() {
    synchronized(lock) {
      specsById.clear()
    }
  }
}

private class InMemoryScheduledTaskRunRecordStore : ScheduledTaskRunRecordStore {
  private val lock = Any()
  private val recordsById = linkedMapOf<String, ScheduledTaskRunRecord>()

  override fun list(): List<ScheduledTaskRunRecord> = synchronized(lock) {
    recordsById.values
      .sortedByDescending(ScheduledTaskRunRecord::updatedAtEpochMs)
  }

  override fun listForSchedule(scheduleId: String): List<ScheduledTaskRunRecord> = synchronized(lock) {
    list().filter { record -> record.scheduleId == scheduleId }
  }

  override fun get(scheduleRunId: String): ScheduledTaskRunRecord? = synchronized(lock) {
    recordsById[scheduleRunId]
  }

  override fun upsert(record: ScheduledTaskRunRecord) {
    synchronized(lock) {
      recordsById[record.scheduleRunId] = record
    }
  }

  override fun clear() {
    synchronized(lock) {
      recordsById.clear()
    }
  }
}

private class FileBackedScheduledTaskSpecStore(
  private val storage: DurableTextStorage,
  private val config: ScheduledTaskSpecStoreConfig,
  private val clock: () -> Long = System::currentTimeMillis,
) : ScheduledTaskSpecStore {
  private val lock = Any()

  override fun list(): List<ScheduledTaskSpec> = synchronized(lock) {
    loadNormalizedRecord().specs
  }

  override fun listEnabled(): List<ScheduledTaskSpec> = synchronized(lock) {
    loadNormalizedRecord().specs.filter(ScheduledTaskSpec::enabled)
  }

  override fun get(scheduleId: String): ScheduledTaskSpec? = synchronized(lock) {
    loadNormalizedRecord().specs.firstOrNull { spec -> spec.scheduleId == scheduleId }
  }

  override fun upsert(spec: ScheduledTaskSpec) {
    synchronized(lock) {
      val existing = loadNormalizedRecord()
      saveRecord(
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = clock(),
          specs = normalizeSpecs(
            existing.specs.filterNot { persisted -> persisted.scheduleId == spec.scheduleId } + spec,
          ),
        ),
      )
    }
  }

  override fun remove(scheduleId: String) {
    synchronized(lock) {
      val existing = loadNormalizedRecord()
      val retained = existing.specs.filterNot { spec -> spec.scheduleId == scheduleId }
      if (retained.size == existing.specs.size) {
        return
      }
      saveRecord(
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = clock(),
          specs = retained,
        ),
      )
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(SPEC_STORE_FILE_NAME)
    }
  }

  private fun loadNormalizedRecord(): ScheduledTaskSpecStoreRecord {
    val normalized = loadRecord().copy(
      specs = normalizeSpecs(loadRecord().specs),
    )
    return if (normalized == loadRecord()) {
      normalized
    } else {
      saveRecord(normalized.copy(updatedAtEpochMs = clock()))
      normalized
    }
  }

  private fun loadRecord(): ScheduledTaskSpecStoreRecord {
    val encoded = storage.readText(SPEC_STORE_FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return ScheduledTaskSpecStoreRecord()
    }
    return PersistenceJson.instance.decodeFromString(
      ScheduledTaskSpecStoreRecord.serializer(),
      encoded,
    )
  }

  private fun saveRecord(record: ScheduledTaskSpecStoreRecord) {
    storage.writeText(
      SPEC_STORE_FILE_NAME,
      PersistenceJson.instance.encodeToString(
        ScheduledTaskSpecStoreRecord.serializer(),
        record,
      ),
    )
  }

  private fun normalizeSpecs(specs: List<ScheduledTaskSpec>): List<ScheduledTaskSpec> = specs
    .filter { spec -> spec.scheduleId.isNotBlank() && spec.sessionId.isNotBlank() }
    .groupBy(ScheduledTaskSpec::scheduleId)
    .values
    .mapNotNull { grouped ->
      grouped.maxByOrNull(ScheduledTaskSpec::updatedAtEpochMs)
    }
    .sortedByDescending(ScheduledTaskSpec::updatedAtEpochMs)
    .take(config.maxTrackedSpecs)
}

private class FileBackedScheduledTaskRunRecordStore(
  private val storage: DurableTextStorage,
  private val config: ScheduledTaskRunRecordStoreConfig,
  private val clock: () -> Long = System::currentTimeMillis,
) : ScheduledTaskRunRecordStore {
  private val lock = Any()

  override fun list(): List<ScheduledTaskRunRecord> = synchronized(lock) {
    loadNormalizedRecord().records
  }

  override fun listForSchedule(scheduleId: String): List<ScheduledTaskRunRecord> = synchronized(lock) {
    loadNormalizedRecord().records.filter { record -> record.scheduleId == scheduleId }
  }

  override fun get(scheduleRunId: String): ScheduledTaskRunRecord? = synchronized(lock) {
    loadNormalizedRecord().records.firstOrNull { record -> record.scheduleRunId == scheduleRunId }
  }

  override fun upsert(record: ScheduledTaskRunRecord) {
    synchronized(lock) {
      val existing = loadNormalizedRecord()
      saveRecord(
        existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = clock(),
          records = normalizeRunRecords(
            existing.records.filterNot { persisted -> persisted.scheduleRunId == record.scheduleRunId } + record,
          ),
        ),
      )
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(RUN_RECORD_STORE_FILE_NAME)
    }
  }

  private fun loadNormalizedRecord(): ScheduledTaskRunRecordStoreRecord {
    val normalized = loadRecord().copy(
      records = normalizeRunRecords(loadRecord().records),
    )
    return if (normalized == loadRecord()) {
      normalized
    } else {
      saveRecord(normalized.copy(updatedAtEpochMs = clock()))
      normalized
    }
  }

  private fun loadRecord(): ScheduledTaskRunRecordStoreRecord {
    val encoded = storage.readText(RUN_RECORD_STORE_FILE_NAME).orEmpty().trim()
    if (encoded.isBlank()) {
      return ScheduledTaskRunRecordStoreRecord()
    }
    return PersistenceJson.instance.decodeFromString(
      ScheduledTaskRunRecordStoreRecord.serializer(),
      encoded,
    )
  }

  private fun saveRecord(record: ScheduledTaskRunRecordStoreRecord) {
    storage.writeText(
      RUN_RECORD_STORE_FILE_NAME,
      PersistenceJson.instance.encodeToString(
        ScheduledTaskRunRecordStoreRecord.serializer(),
        record,
      ),
    )
  }

  private fun normalizeRunRecords(
    records: List<ScheduledTaskRunRecord>,
  ): List<ScheduledTaskRunRecord> = records
    .filter { record ->
      record.scheduleRunId.isNotBlank() &&
        record.scheduleId.isNotBlank() &&
        record.sessionId.isNotBlank()
    }
    .groupBy(ScheduledTaskRunRecord::scheduleRunId)
    .values
    .mapNotNull { grouped ->
      grouped.maxByOrNull(ScheduledTaskRunRecord::updatedAtEpochMs)
    }
    .sortedByDescending(ScheduledTaskRunRecord::updatedAtEpochMs)
    .take(config.maxTrackedRecords)
}

@Serializable
private data class ScheduledTaskSpecStoreRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val specs: List<ScheduledTaskSpec> = emptyList(),
)

@Serializable
private data class ScheduledTaskRunRecordStoreRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val records: List<ScheduledTaskRunRecord> = emptyList(),
)

private const val SPEC_STORE_FILE_NAME: String = "scheduled-task-specs.json"
private const val RUN_RECORD_STORE_FILE_NAME: String = "scheduled-task-run-records.json"
