package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
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

  fun update(
    scheduleId: String,
    transform: (ScheduledTaskSpec) -> ScheduledTaskSpec,
  ): ScheduledTaskSpec?

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

  fun claimRun(
    scheduleRunId: String,
    expectedResult: ScheduledTaskRunResult?,
    next: (current: ScheduledTaskRunRecord?) -> ScheduledTaskRunRecord,
  ): Boolean

  fun removeForSchedule(scheduleId: String)

  fun clear()
}

internal fun scheduledTaskRunRecordClaimMatches(
  current: ScheduledTaskRunRecord?,
  expectedResult: ScheduledTaskRunResult?,
): Boolean = if (expectedResult == null) {
  current == null
} else {
  current?.result == expectedResult
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

internal fun fileBackedScheduledTaskSpecStore(
  storage: DurableTextStorage,
  config: ScheduledTaskSpecStoreConfig = ScheduledTaskSpecStoreConfig(),
  clock: () -> Long = System::currentTimeMillis,
): ScheduledTaskSpecStore = FileBackedScheduledTaskSpecStore(
  storage = storage,
  config = config,
  clock = clock,
)

internal fun fileBackedScheduledTaskRunRecordStore(
  storage: DurableTextStorage,
  config: ScheduledTaskRunRecordStoreConfig = ScheduledTaskRunRecordStoreConfig(),
  clock: () -> Long = System::currentTimeMillis,
): ScheduledTaskRunRecordStore = FileBackedScheduledTaskRunRecordStore(
  storage = storage,
  config = config,
  clock = clock,
)

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
  val snoozedUntilEpochMs: Long? = null,
  val createdAtEpochMs: Long,
  val updatedAtEpochMs: Long,
) {
  init {
    require(scheduleId.isNotBlank()) { "ScheduledTaskSpec scheduleId must not be blank." }
    require(sessionId.isNotBlank()) { "ScheduledTaskSpec sessionId must not be blank." }
    require(title.isNotBlank()) { "ScheduledTaskSpec title must not be blank." }
    require(snoozedUntilEpochMs == null || snoozedUntilEpochMs >= 0L) {
      "ScheduledTaskSpec snoozedUntilEpochMs must be >= 0."
    }
    require(updatedAtEpochMs >= createdAtEpochMs) {
      "ScheduledTaskSpec updatedAtEpochMs must be >= createdAtEpochMs."
    }
  }
}

@Serializable
internal sealed class ScheduledTrigger {
  @Serializable
  @SerialName("at")
  data class At(
    val atEpochMs: Long,
  ) : ScheduledTrigger() {
    init {
      require(atEpochMs >= 0L) {
        "ScheduledTrigger.At atEpochMs must be >= 0."
      }
    }
  }

  @Serializable
  @SerialName("after")
  data class After(
    val delayMs: Long,
    val createdAtEpochMs: Long,
  ) : ScheduledTrigger() {
    init {
      require(delayMs >= 1L) { "ScheduledTrigger.After delayMs must be >= 1." }
      require(createdAtEpochMs >= 0L) {
        "ScheduledTrigger.After createdAtEpochMs must be >= 0."
      }
    }
  }

  @Serializable
  @SerialName("recurrence")
  data class Recurrence(
    val startAtEpochMs: Long,
    val timezoneId: String,
    val rrule: String,
    val exdatesEpochMs: List<Long> = emptyList(),
    val rdatesEpochMs: List<Long> = emptyList(),
  ) : ScheduledTrigger() {
    init {
      require(startAtEpochMs >= 0L) {
        "ScheduledTrigger.Recurrence startAtEpochMs must be >= 0."
      }
      require(timezoneId.isNotBlank()) {
        "ScheduledTrigger.Recurrence timezoneId must not be blank."
      }
      require(rrule.isNotBlank()) { "ScheduledTrigger.Recurrence rrule must not be blank." }
      require(exdatesEpochMs.all { value -> value >= 0L }) {
        "ScheduledTrigger.Recurrence exdatesEpochMs must be >= 0."
      }
      require(rdatesEpochMs.all { value -> value >= 0L }) {
        "ScheduledTrigger.Recurrence rdatesEpochMs must be >= 0."
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
  // Kept in the persisted shape for compatibility; detached scheduled execution always requires FGS.
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
  SKIPPED_SNOOZED,
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

  override fun update(
    scheduleId: String,
    transform: (ScheduledTaskSpec) -> ScheduledTaskSpec,
  ): ScheduledTaskSpec? = synchronized(lock) {
    val current = specsById[scheduleId] ?: return@synchronized null
    val updated = transform(current)
    require(updated.scheduleId == current.scheduleId) {
      "Scheduled task update must not change scheduleId."
    }
    specsById[scheduleId] = updated
    updated
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

  override fun claimRun(
    scheduleRunId: String,
    expectedResult: ScheduledTaskRunResult?,
    next: (current: ScheduledTaskRunRecord?) -> ScheduledTaskRunRecord,
  ): Boolean = synchronized(lock) {
    val current = recordsById[scheduleRunId]
    if (!scheduledTaskRunRecordClaimMatches(current, expectedResult)) {
      return@synchronized false
    }
    recordsById[scheduleRunId] = next(current)
    true
  }

  override fun removeForSchedule(scheduleId: String) {
    synchronized(lock) {
      val retainedIds = recordsById.values
        .filterNot { record -> record.scheduleId == scheduleId }
        .mapTo(linkedSetOf(), ScheduledTaskRunRecord::scheduleRunId)
      recordsById.keys.retainAll(retainedIds)
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
      updateRecord { existing ->
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            specs = normalizeSpecs(
              existing.specs.filterNot { persisted -> persisted.scheduleId == spec.scheduleId } + spec,
            ),
          ),
          result = Unit,
        )
      }
    }
  }

  override fun update(
    scheduleId: String,
    transform: (ScheduledTaskSpec) -> ScheduledTaskSpec,
  ): ScheduledTaskSpec? = synchronized(lock) {
    updateRecord { existing ->
      val current = existing.specs.firstOrNull { spec -> spec.scheduleId == scheduleId }
        ?: return@updateRecord RecordStorageUpdate(
          value = existing,
          result = null,
          write = false,
        )
      val updated = transform(current)
      require(updated.scheduleId == current.scheduleId) {
        "Scheduled task update must not change scheduleId."
      }
      val normalizedSpecs = normalizeSpecs(
        existing.specs.filterNot { spec -> spec.scheduleId == scheduleId } + updated,
      )
      val persisted = normalizedSpecs.firstOrNull { spec -> spec.scheduleId == scheduleId }
      val next = existing.copy(
        recordVersion = existing.recordVersion + 1L,
        updatedAtEpochMs = clock(),
        specs = normalizedSpecs,
      )
      RecordStorageUpdate(
        value = next,
        result = persisted,
        write = next != existing,
      )
    }
  }

  override fun remove(scheduleId: String) {
    synchronized(lock) {
      updateRecord { existing ->
        val retained = existing.specs.filterNot { spec -> spec.scheduleId == scheduleId }
        if (retained.size == existing.specs.size) {
          return@updateRecord RecordStorageUpdate(
            value = existing,
            result = Unit,
            write = false,
          )
        }
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            specs = retained,
          ),
          result = Unit,
        )
      }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(SPEC_STORE_FILE_NAME)
    }
  }

  private fun loadNormalizedRecord(): ScheduledTaskSpecStoreRecord =
    storage.updateRecord(
      name = SPEC_STORE_FILE_NAME,
      serializer = ScheduledTaskSpecStoreRecord.serializer(),
    ) { current ->
      val existing = current ?: ScheduledTaskSpecStoreRecord()
      val normalized = normalizeRecord(existing)
      RecordStorageUpdate(
        value = normalized,
        result = normalized,
        write = normalized != existing,
      )
    }

  private fun <T> updateRecord(
    update: (ScheduledTaskSpecStoreRecord) -> RecordStorageUpdate<ScheduledTaskSpecStoreRecord, T>,
  ): T =
    storage.updateRecord(
      name = SPEC_STORE_FILE_NAME,
      serializer = ScheduledTaskSpecStoreRecord.serializer(),
    ) { current ->
      update(normalizeRecord(current ?: ScheduledTaskSpecStoreRecord()))
    }

  private fun normalizeRecord(record: ScheduledTaskSpecStoreRecord): ScheduledTaskSpecStoreRecord {
    val normalizedSpecs = normalizeSpecs(record.specs)
    return if (normalizedSpecs == record.specs) {
      record
    } else {
      record.copy(
        recordVersion = record.recordVersion + 1L,
        updatedAtEpochMs = clock(),
        specs = normalizedSpecs,
      )
    }
  }

  private fun normalizeSpecs(specs: List<ScheduledTaskSpec>): List<ScheduledTaskSpec> = specs
    .filter { spec -> spec.scheduleId.isNotBlank() && spec.sessionId.isNotBlank() }
    .groupBy(ScheduledTaskSpec::scheduleId)
    .values
    .mapNotNull { grouped ->
      grouped.maxByOrNull(ScheduledTaskSpec::updatedAtEpochMs)
    }
    .map { spec ->
      if (spec.policy.requiresForegroundNotification) {
        spec
      } else {
        spec.copy(
          policy = spec.policy.copy(requiresForegroundNotification = true),
          updatedAtEpochMs = maxOf(clock(), spec.updatedAtEpochMs + 1L),
        )
      }
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
      updateRecord { existing ->
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            records = normalizeRunRecords(
              existing.records.filterNot { persisted -> persisted.scheduleRunId == record.scheduleRunId } + record,
            ),
          ),
          result = Unit,
        )
      }
    }
  }

  override fun claimRun(
    scheduleRunId: String,
    expectedResult: ScheduledTaskRunResult?,
    next: (current: ScheduledTaskRunRecord?) -> ScheduledTaskRunRecord,
  ): Boolean {
    synchronized(lock) {
      var claimed = false
      updateRecord { existing ->
        val current = existing.records.firstOrNull { record ->
          record.scheduleRunId == scheduleRunId
        }
        if (!scheduledTaskRunRecordClaimMatches(current, expectedResult)) {
          return@updateRecord RecordStorageUpdate(
            value = existing,
            result = false,
            write = false,
          )
        }
        claimed = true
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            records = normalizeRunRecords(
              existing.records.filterNot { record -> record.scheduleRunId == scheduleRunId } +
                next(current),
            ),
          ),
          result = true,
        )
      }
      return claimed
    }
  }

  override fun removeForSchedule(scheduleId: String) {
    synchronized(lock) {
      updateRecord { existing ->
        val retained = existing.records.filterNot { record -> record.scheduleId == scheduleId }
        if (retained.size == existing.records.size) {
          return@updateRecord RecordStorageUpdate(
            value = existing,
            result = Unit,
            write = false,
          )
        }
        RecordStorageUpdate(
          value = existing.copy(
            recordVersion = existing.recordVersion + 1L,
            updatedAtEpochMs = clock(),
            records = retained,
          ),
          result = Unit,
        )
      }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(RUN_RECORD_STORE_FILE_NAME)
    }
  }

  private fun loadNormalizedRecord(): ScheduledTaskRunRecordStoreRecord =
    storage.updateRecord(
      name = RUN_RECORD_STORE_FILE_NAME,
      serializer = ScheduledTaskRunRecordStoreRecord.serializer(),
    ) { current ->
      val existing = current ?: ScheduledTaskRunRecordStoreRecord()
      val normalized = normalizeRecord(existing)
      RecordStorageUpdate(
        value = normalized,
        result = normalized,
        write = normalized != existing,
      )
    }

  private fun <T> updateRecord(
    update: (ScheduledTaskRunRecordStoreRecord) -> RecordStorageUpdate<ScheduledTaskRunRecordStoreRecord, T>,
  ): T =
    storage.updateRecord(
      name = RUN_RECORD_STORE_FILE_NAME,
      serializer = ScheduledTaskRunRecordStoreRecord.serializer(),
    ) { current ->
      update(normalizeRecord(current ?: ScheduledTaskRunRecordStoreRecord()))
    }

  private fun normalizeRecord(record: ScheduledTaskRunRecordStoreRecord): ScheduledTaskRunRecordStoreRecord {
    val normalizedRecords = normalizeRunRecords(record.records)
    return if (normalizedRecords == record.records) {
      record
    } else {
      record.copy(
        recordVersion = record.recordVersion + 1L,
        updatedAtEpochMs = clock(),
        records = normalizedRecords,
      )
    }
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

private const val SPEC_STORE_FILE_NAME: String = "scheduled-task-specs-v2.json"
private const val RUN_RECORD_STORE_FILE_NAME: String = "scheduled-task-run-records-v2.json"
